package com.banner.cleanbrowser.ui

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.banner.cleanbrowser.App
import com.banner.cleanbrowser.browser.Ui
import com.banner.cleanbrowser.core.NetMode
import com.banner.cleanbrowser.core.Settings
import com.banner.cleanbrowser.net.Routing
import com.banner.cleanbrowser.net.Tor
import com.banner.cleanbrowser.net.WgServer
import com.banner.cleanbrowser.net.WireGuard

private fun setNet(change: (Settings) -> Settings, modeChanged: Boolean = false) {
    App.prefs.update(change)
    if (modeChanged) Routing.onModeChanged() else Routing.apply()
}

@Composable
fun VpnSettingsPage() {
    val s = App.prefs.state
    val ctx = LocalContext.current
    var pendingServer by remember { mutableStateOf<WgServer?>(null) }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val srv = pendingServer
        pendingServer = null
        if (res.resultCode == Activity.RESULT_OK && srv != null) WireGuard.connect(ctx, srv) else if (srv != null) Ui.toast("VPN permission needed")
    }
    fun connectWg(srv: WgServer) {
        setNet({ it.copy(wgActive = srv.id, netMode = NetMode.WIREGUARD) })
        val i = VpnService.prepare(ctx)
        if (i != null) { pendingServer = srv; consent.launch(i) } else WireGuard.connect(ctx, srv)
    }

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeCard("Off", s.netMode == NetMode.DIRECT, Modifier.weight(1f)) { setNet({ it.copy(netMode = NetMode.DIRECT) }, true) }
            ModeCard("Tor", s.netMode == NetMode.TOR, Modifier.weight(1f)) { setNet({ it.copy(netMode = NetMode.TOR) }, true) }
            ModeCard("WireGuard", s.netMode == NetMode.WIREGUARD, Modifier.weight(1f)) { setNet({ it.copy(netMode = NetMode.WIREGUARD) }, true) }
            ModeCard("Proxy", s.netMode == NetMode.PROXY, Modifier.weight(1f)) { setNet({ it.copy(netMode = NetMode.PROXY) }, true) }
        }
        StatusLine()

        when (s.netMode) {
            NetMode.TOR -> {
                SectionTitle("Tor")
                ChoiceRow("Exit country", s.torExitCountry, Tor.countries) { v ->
                    App.prefs.update { it.copy(torExitCountry = v) }
                    Tor.restart(ctx)
                }
                RowItem("New identity", icon = Icons.Filled.Refresh, enabled = Tor.state == Tor.State.ON, onClick = {
                    Tor.newIdentity(); Ui.toast("New Tor circuit")
                })
            }
            NetMode.WIREGUARD -> WireGuardSection(s, onConnect = { connectWg(it) })
            NetMode.PROXY -> {
                SectionTitle("Proxy")
                ChoiceRow("Type", s.proxyType, listOf("SOCKS5" to "SOCKS5", "HTTP" to "HTTP")) { v -> setNet({ it.copy(proxyType = v) }) }
                TextRow("Host", s.proxyHost, "proxy.example.com", KeyboardType.Uri) { v -> setNet({ it.copy(proxyHost = v) }) }
                TextRow("Port", s.proxyPort.toString(), "1080", KeyboardType.Number) { v ->
                    v.toIntOrNull()?.takeIf { it in 1..65535 }?.let { p -> setNet({ it.copy(proxyPort = p) }) }
                }
                TextRow("User name", s.proxyUser, "Optional") { v -> setNet({ it.copy(proxyUser = v) }) }
                PasswordRow("Password", s.proxyPass) { v -> setNet({ it.copy(proxyPass = v) }) }
            }
            NetMode.DIRECT -> {}
        }

        if (s.netMode != NetMode.DIRECT) {
            SectionTitle("Use for")
            SwitchRow("Browser", s.netBrowser) { v -> setNet({ it.copy(netBrowser = v) }) }
            SwitchRow("Downloads", s.netDownloads) { v -> setNet({ it.copy(netDownloads = v) }) }
            SwitchRow("Torrents", s.netTorrents, enabled = s.netMode != NetMode.TOR) { v -> setNet({ it.copy(netTorrents = v) }) }
            SwitchRow("Kill switch", s.killSwitch) { v -> setNet({ it.copy(killSwitch = v) }) }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun StatusLine() {
    val s = App.prefs.state
    val (label, color) = when (s.netMode) {
        NetMode.DIRECT -> "Not protected" to MaterialTheme.colorScheme.outline
        NetMode.TOR -> when (Tor.state) {
            Tor.State.ON -> "Tor connected" to Color(0xFF2E9E4F)
            Tor.State.STARTING -> "Tor connecting…" to Color(0xFFC99A1E)
            Tor.State.ERROR -> (Tor.detail.ifEmpty { "Tor error" }) to MaterialTheme.colorScheme.error
            Tor.State.OFF -> "Tor off" to MaterialTheme.colorScheme.outline
        }
        NetMode.WIREGUARD -> when (WireGuard.state) {
            WireGuard.State.ON -> "Connected · ${WireGuard.detail}" to Color(0xFF2E9E4F)
            WireGuard.State.CONNECTING -> WireGuard.detail to Color(0xFFC99A1E)
            WireGuard.State.ERROR -> WireGuard.detail to MaterialTheme.colorScheme.error
            WireGuard.State.OFF -> "Not connected" to MaterialTheme.colorScheme.outline
        }
        NetMode.PROXY -> (if (s.proxyHost.isBlank()) "Proxy not set" else "${s.proxyType} ${s.proxyHost}:${s.proxyPort}") to
            (if (s.proxyHost.isBlank()) MaterialTheme.colorScheme.error else Color(0xFF2E9E4F))
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Browser: ${Routing.browserRoute}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ModeCard(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = MaterialTheme.shapes.medium
    Box(
        modifier
            .heightIn(min = 52.dp)
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(if (selected) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, style = MaterialTheme.typography.labelLarge, maxLines = 1,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WireGuardSection(s: Settings, onConnect: (WgServer) -> Unit) {
    val ctx = LocalContext.current
    var adding by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importText = runCatching { ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
            if (importText == null) Ui.toast("Could not read the file") else adding = true
        }
    }
    SectionTitle("Servers")
    WireGuard.servers.forEach { srv ->
        val active = s.wgActive == srv.id
        RowItem(
            srv.name, value = srv.location.ifEmpty { null }, icon = if (active) Icons.Filled.Check else AppIcons.Vpn,
            onClick = { if (active && WireGuard.connected) WireGuard.disconnect() else onConnect(srv) },
            trailing = { IconButton(onClick = { WireGuard.remove(ctx, srv) }) { Icon(Icons.Filled.Delete, "Delete") } },
        )
    }
    RowItem("Import config file", icon = AppIcons.Folder, onClick = { picker.launch(arrayOf("*/*")) })
    RowItem("Paste config", icon = Icons.Filled.Add, onClick = { importText = ""; adding = true })
    if (WireGuard.connected) RowItem("Disconnect", icon = AppIcons.Stop, onClick = { WireGuard.disconnect() })
    if (adding) AddServerDialog(importText ?: "") { adding = false; importText = null }
}

@Composable
private fun AddServerDialog(initial: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var text by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    CleanDialog(
        onDismissRequest = onDismiss,
        title = { Text("WireGuard server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(location, { location = it }, label = { Text("Location") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    text, { text = it; error = null }, label = { Text("Config") }, minLines = 4, maxLines = 8,
                    isError = error != null, supportingText = { error?.let { Text(it) } }, modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val e = WireGuard.add(ctx, name, location, text)
                if (e == null) onDismiss() else error = e
            }, enabled = text.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PasswordRow(title: String, value: String, onSave: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    RowItem(title, value = if (value.isEmpty()) "Optional" else "••••••••", onClick = { open = true })
    if (open) {
        var t by remember { mutableStateOf(value) }
        CleanDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                OutlinedTextField(t, { t = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth())
            },
            confirmButton = { TextButton(onClick = { open = false; onSave(t) }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        )
    }
}
