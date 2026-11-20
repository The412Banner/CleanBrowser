package com.banner.cleanbrowser.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.banner.cleanbrowser.App
import com.banner.cleanbrowser.browser.Ui
import com.banner.cleanbrowser.core.Settings
import com.banner.cleanbrowser.download.DlItem
import com.banner.cleanbrowser.download.DlKind
import com.banner.cleanbrowser.download.DlStatus
import com.banner.cleanbrowser.download.Downloads
import com.banner.cleanbrowser.download.KeepAlive
import com.banner.cleanbrowser.torrent.Torrents
import kotlinx.coroutines.delay

private fun size(ctx: Context, b: Long) = Formatter.formatShortFileSize(ctx, b.coerceAtLeast(0))

@Composable
fun DownloadsScreen() {
    BackHandler { Nav.back() }
    val ctx = LocalContext.current
    var filter by remember { mutableStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val torrentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) Downloads.addTorrentFile(uri)
    }
    val all = Downloads.items
    val shown = when (filter) {
        1 -> all.filter { !it.status.finished }
        2 -> all.filter { it.status.finished }
        3 -> all.filter { it.kind == DlKind.TORRENT }
        else -> all.toList()
    }
    ScreenScaffold("Downloads", onBack = { Nav.back() }, actions = {
        IconButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, "Add") }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
            CleanMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Open torrent file") }, onClick = { menu = false; torrentPicker.launch(arrayOf("application/x-bittorrent", "application/octet-stream")) })
                MenuDivider()
                DropdownMenuItem(text = { Text("Resume all") }, onClick = { menu = false; Downloads.resumeAll() })
                MenuDivider()
                DropdownMenuItem(text = { Text("Pause all") }, onClick = { menu = false; Downloads.pauseAll(system = false) })
                MenuDivider()
                DropdownMenuItem(text = { Text("Clear finished") }, onClick = { menu = false; Downloads.clearFinished() })
                MenuDivider()
                DropdownMenuItem(text = { Text("Settings") }, onClick = { menu = false; Nav.open(Screen.SETTINGS); Nav.settingsStack.add("downloads") })
            }
        }
    }) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Pill(listOf("All", "Active", "Done", "Torrents"), filter) { filter = it }
            }
            if (shown.isEmpty()) EmptyState("No downloads")
            else LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
                items(shown, key = { it.id }) { d -> DownloadRow(d, Modifier.animateItem()) }
            }
        }
    }
    if (adding) AddDialog(onDismiss = { adding = false })
}

private fun statusText(ctx: Context, d: DlItem): String {
    val parts = ArrayList<String>()
    when (d.status) {
        DlStatus.RUNNING, DlStatus.CONNECTING -> {
            parts += if (d.total > 0) "${size(ctx, d.done)} / ${size(ctx, d.total)}" else size(ctx, d.done)
            parts += size(ctx, d.speed) + "/s"
            if (d.speed > 0 && d.total > 0) {
                val sec = (d.total - d.done) / d.speed
                parts += if (sec < 60) "${sec}s" else if (sec < 3600) "${sec / 60} min" else "${sec / 3600} h ${sec % 3600 / 60} min"
            }
            if (d.kind == DlKind.TORRENT) parts += "${d.seeds}/${d.peers} peers"
            else if (d.liveConnections > 1) parts += "${d.liveConnections} connections"
        }
        DlStatus.QUEUED -> parts += "Queued"
        DlStatus.WAITING_NETWORK -> parts += d.errorText ?: "Waiting for network"
        DlStatus.PAUSED -> { parts += "Paused"; if (d.total > 0) parts += "${(d.progress * 100).toInt()}%" }
        DlStatus.CHECKING -> parts += "Checking"
        DlStatus.METADATA -> parts += "Getting torrent info · ${d.peers} peers"
        DlStatus.SEEDING -> { parts += "Seeding"; parts += size(ctx, d.upSpeed) + "/s up"; parts += "ratio %.2f".format(d.ratio) }
        DlStatus.DONE -> {
            parts += size(ctx, d.total)
            if (d.checksumOk == 1) parts += "checksum OK" else if (d.checksumOk == 0) parts += "checksum MISMATCH"
        }
        DlStatus.FAILED -> parts += d.errorText ?: "Failed"
    }
    return parts.joinToString(" · ")
}

@Composable
private fun DownloadRow(d: DlItem, modifier: Modifier) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var files by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val frac by animateFloatAsState(d.progress, Motion.medium(), label = "dl")
    Row(
        modifier.fillMaxWidth().clickable {
            if (d.status.finished) KeepAlive.openIntent(ctx, d)?.let { runCatching { ctx.startActivity(it) }.onFailure { Ui.toast("No app can open this file") } }
                ?: run { if (d.kind == DlKind.TORRENT) files = true }
        }.padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp).animateContentSize(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (d.kind == DlKind.TORRENT) AppIcons.Magnet else AppIcons.Download, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(d.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            if (!d.status.finished && d.status != DlStatus.FAILED) {
                Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                    Box(
                        Modifier.fillMaxWidth(if (d.total > 0) frac else 0f).height(4.dp).clip(CircleShape)
                            .background(if (d.status == DlStatus.PAUSED) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary),
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
            Text(
                statusText(ctx, d), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (d.status == DlStatus.FAILED || d.checksumOk == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            d.status.active -> IconButton(onClick = { Downloads.pause(d) }) { Icon(AppIcons.Pause, "Pause") }
            d.status == DlStatus.SEEDING -> IconButton(onClick = { Downloads.pause(d) }) { Icon(AppIcons.Stop, "Stop seeding") }
            d.status == DlStatus.PAUSED -> IconButton(onClick = { Downloads.resume(d) }) { Icon(Icons.Filled.PlayArrow, "Resume") }
            d.status == DlStatus.FAILED -> IconButton(onClick = { Downloads.retry(d) }) { Icon(Icons.Filled.Refresh, "Retry") }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            CleanMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (d.status.finished) {
                    DropdownMenuItem(text = { Text("Open") }, onClick = {
                        menu = false
                        KeepAlive.openIntent(ctx, d)?.let { runCatching { ctx.startActivity(it) } } ?: Ui.toast("File not found")
                    })
                    MenuDivider()
                    DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(Icons.Filled.Share, null) }, onClick = {
                        menu = false
                        KeepAlive.openIntent(ctx, d)?.data?.let { uri ->
                            val i = Intent(Intent.ACTION_SEND).setType(d.mime.ifEmpty { "*/*" }).putExtra(Intent.EXTRA_STREAM, uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            ctx.startActivity(Intent.createChooser(i, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    })
                    MenuDivider()
                }
                if (d.kind == DlKind.TORRENT) {
                    DropdownMenuItem(text = { Text("Files") }, leadingIcon = { Icon(Icons.Filled.List, null) }, onClick = { menu = false; files = true })
                    MenuDivider()
                    DropdownMenuItem(
                        text = { Text("Sequential") },
                        trailingIcon = { Checkbox(checked = d.sequential, onCheckedChange = null) },
                        onClick = { menu = false; Downloads.setSequential(d, !d.sequential) },
                    )
                    if (d.status == DlStatus.DONE) {
                        MenuDivider()
                        DropdownMenuItem(text = { Text("Seed") }, onClick = { menu = false; Downloads.resume(d) })
                    }
                    MenuDivider()
                }
                if (d.kind == DlKind.HTTP && d.url.startsWith("http")) {
                    DropdownMenuItem(text = { Text("Copy link") }, onClick = { menu = false; Browser.copy(ctx, d.url); Ui.toast("Link copied") })
                    MenuDivider()
                    DropdownMenuItem(text = { Text("Restart") }, leadingIcon = { Icon(Icons.Filled.Refresh, null) }, onClick = { menu = false; Downloads.restart(d) })
                    MenuDivider()
                }
                DropdownMenuItem(text = { Text("Remove from list") }, leadingIcon = { Icon(Icons.Filled.Close, null) }, onClick = { menu = false; Downloads.remove(d, deleteFile = false) })
                MenuDivider()
                DropdownMenuItem(text = { Text("Delete file") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { menu = false; confirmDelete = true })
            }
        }
    }
    Divider()
    if (files) TorrentFilesDialog(d) { files = false }
    if (confirmDelete) CleanDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete file?") },
        text = { Text(d.name) },
        confirmButton = { TextButton(onClick = { confirmDelete = false; Downloads.remove(d, deleteFile = true) }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}

@Composable
private fun TorrentFilesDialog(d: DlItem, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(Torrents.files(d)) }
    LaunchedEffect(d.id) {
        while (true) { delay(1500); list = Torrents.files(d) }
    }
    CleanDialog(
        onDismissRequest = onDismiss,
        title = { Text("Files") },
        text = {
            if (list.isEmpty()) Text("Waiting for torrent info…")
            else Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                list.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable {
                        Torrents.setWanted(d, f.index, !f.wanted); list = Torrents.files(d)
                    }) {
                        Checkbox(checked = f.wanted, onCheckedChange = { Torrents.setWanted(d, f.index, it); list = Torrents.files(d) })
                        Column(Modifier.weight(1f)) {
                            Text(f.path.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val pct = if (f.size > 0) (f.progress * 100 / f.size).toInt() else 0
                            Text("${size(ctx, f.size)} · $pct%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun AddDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var sum by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val clip = (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip
        val t = clip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim().orEmpty()
        if (t.startsWith("http") || t.startsWith("magnet:")) url = t
    }
    CleanDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add download") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(url, { url = it }, label = { Text("Link or magnet") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (!url.startsWith("magnet:")) {
                    OutlinedTextField(name, { name = it }, label = { Text("File name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(sum, { sum = it }, label = { Text("SHA-256 or MD5") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val s = sum.trim()
                val type = when (s.length) { 32 -> "MD5"; 40 -> "SHA-1"; 128 -> "SHA-512"; 64 -> "SHA-256"; else -> null }
                Downloads.addUrl(url, name, type, if (type != null) s else null)
                onDismiss()
            }, enabled = url.isNotBlank()) { Text("Download") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun setDl(change: (Settings) -> Settings) {
    App.prefs.update(change)
    Torrents.applySettings()
    Downloads.schedule()
}

@Composable
fun DownloadSettingsPage() {
    val s = App.prefs.state
    val ctx = LocalContext.current
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            setDl { it.copy(dlFolder = uri.toString()) }
        }
    }
    val sd = remember { ctx.getExternalFilesDirs(null).getOrNull(1)?.absolutePath }
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    var unrestricted by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(ctx.packageName)) }

    Column(Modifier.fillMaxWidth()) {
        SectionTitle("Downloads")
        RowItem("Save to", value = folderLabel(s.dlFolder), icon = AppIcons.Folder, onClick = { folderPicker.launch(null) })
        if (s.dlFolder.isNotEmpty()) RowItem("Use Download folder", onClick = { setDl { it.copy(dlFolder = "") } })
        SliderRow("Connections per file", s.dlConnections, 1..32, 1, "") { v -> setDl { it.copy(dlConnections = v) } }
        SliderRow("Downloads at once", s.dlParallel, 1..8, 1, "") { v -> setDl { it.copy(dlParallel = v) } }
        ChoiceRow("Speed limit", s.dlSpeedLimit, speedOptions) { v -> setDl { it.copy(dlSpeedLimit = v) } }
        SliderRow("Retries", s.dlRetries, 0..30, 1, "") { v -> setDl { it.copy(dlRetries = v) } }
        SwitchRow("Pre-allocate files", s.dlPreallocate) { v -> setDl { it.copy(dlPreallocate = v) } }
        RowItem(
            "Speed test",
            value = if (com.banner.cleanbrowser.download.SpeedTest.running) "Running…" else com.banner.cleanbrowser.download.SpeedTest.results
                .maxByOrNull { it.mbPerSec }?.let { "Best ${"%.1f".format(it.mbPerSec)} MB/s" },
            enabled = !com.banner.cleanbrowser.download.SpeedTest.running,
            onClick = { com.banner.cleanbrowser.download.SpeedTest.start(ctx) },
        )
        com.banner.cleanbrowser.download.SpeedTest.results.forEach { r ->
            RowItem(r.label, value = r.error ?: "${"%.1f".format(r.mbPerSec)} MB/s · ${"%.0f".format(r.mbit)} Mbps")
        }
        SwitchRow("Wi-Fi only", s.dlWifiOnly) { v -> setDl { it.copy(dlWifiOnly = v) } }
        SwitchRow("Notifications", s.dlNotifications) { v -> setDl { it.copy(dlNotifications = v) } }
        SwitchRow("Ask before downloading", s.confirmDownloads) { v -> setDl { it.copy(confirmDownloads = v) } }
        RowItem(
            "Battery", value = if (unrestricted) "Unrestricted" else "Optimised",
            onClick = {
                runCatching {
                    ctx.startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                }
                unrestricted = pm.isIgnoringBatteryOptimizations(ctx.packageName)
            },
        )

        SectionTitle("Torrents")
        ChoiceRow(
            "Save to", s.torFolder,
            listOfNotNull("" to "Download folder", sd?.let { it to "SD card (app folder)" }),
            icon = AppIcons.Folder,
        ) { v -> setDl { it.copy(torFolder = v) } }
        ChoiceRow("Seed until ratio", s.torSeedRatio, listOf(0 to "Do not seed", 50 to "0.5", 100 to "1.0", 200 to "2.0", 500 to "5.0", 100000 to "Forever")) { v ->
            setDl { it.copy(torSeedRatio = v) }
        }
        SwitchRow("Seed in background", s.torSeedBackground) { v -> setDl { it.copy(torSeedBackground = v) } }
        SwitchRow("Sequential by default", s.torSequential) { v -> setDl { it.copy(torSequential = v) } }
        ChoiceRow("Encryption", s.torEncryption, listOf("prefer" to "Prefer", "force" to "Require", "off" to "Off")) { v -> setDl { it.copy(torEncryption = v) } }
        SwitchRow("Only over VPN", s.torOnlyVpn) { v -> setDl { it.copy(torOnlyVpn = v) } }
        SwitchRow("Add more trackers", s.torExtraTrackers) { v -> setDl { it.copy(torExtraTrackers = v) } }
        SliderRow("Max connections", s.torConnections, 50..800, 50, "") { v -> setDl { it.copy(torConnections = v) } }
        SwitchRow("DHT", s.torDht) { v -> setDl { it.copy(torDht = v) } }
        SwitchRow("Local peer discovery", s.torLsd) { v -> setDl { it.copy(torLsd = v) } }
        SwitchRow("UPnP / NAT-PMP", s.torUpnp) { v -> setDl { it.copy(torUpnp = v) } }
        ChoiceRow("Download limit", s.torDownLimit, speedOptions) { v -> setDl { it.copy(torDownLimit = v) } }
        ChoiceRow("Upload limit", s.torUpLimit, speedOptions) { v -> setDl { it.copy(torUpLimit = v) } }
        SliderRow("Active torrents", s.torMaxActive, 1..10, 1, "") { v -> setDl { it.copy(torMaxActive = v) } }
    }
}

private val speedOptions = listOf(0 to "No limit", 256 to "256 KB/s", 512 to "512 KB/s", 1024 to "1 MB/s", 2048 to "2 MB/s", 5120 to "5 MB/s", 10240 to "10 MB/s", 25600 to "25 MB/s")

private fun folderLabel(f: String): String = when {
    f.isEmpty() -> "Download"
    f.startsWith("content://") -> Uri.decode(f.substringAfterLast("/")).substringAfter(':').ifEmpty { "Chosen folder" }
    else -> f
}

@Composable
fun SectionTitle(title: String) {
    Text(
        title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp),
    )
}
