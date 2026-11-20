package com.banner.cleanbrowser.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.text.format.Formatter
import android.webkit.PermissionRequest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.banner.cleanbrowser.browser.Engine
import com.banner.cleanbrowser.browser.Prompt
import com.banner.cleanbrowser.browser.Tabs
import com.banner.cleanbrowser.browser.Ui
import com.banner.cleanbrowser.core.Urls
import com.banner.cleanbrowser.download.DownloadRequest
import com.banner.cleanbrowser.download.Downloads

@Composable
fun PromptHost() {
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    var pendingGrant by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        pendingGrant?.invoke(res.values.all { it })
        pendingGrant = null
    }
    fun withAndroidPermissions(perms: List<String>, then: (Boolean) -> Unit) {
        val missing = perms.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) then(true) else {
            pendingGrant = then
            permLauncher.launch(missing.toTypedArray())
        }
    }

    // File uploads
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val fc = Ui.fileChooser
        Ui.fileChooser = null
        fc?.callback?.onReceiveValue(android.webkit.WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data))
    }
    val chooser = Ui.fileChooser
    LaunchedEffect(chooser) {
        if (chooser != null) {
            try {
                fileLauncher.launch(chooser.params.createIntent())
            } catch (_: Exception) {
                chooser.callback.onReceiveValue(null)
                Ui.fileChooser = null
            }
        }
    }

    when (val p = Ui.prompt) {
        null -> {}
        is Prompt.Permission -> {
            val what = p.resources.map {
                when (it) {
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> "camera"
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> "microphone"
                    PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> "protected content"
                    else -> "device access"
                }
            }.joinToString(" and ")
            CleanDialog(
                onDismissRequest = { p.request.deny(); Ui.prompt = null },
                title = { Text(Urls.display(p.request.origin.toString())) },
                text = { Text("Use your $what?") },
                confirmButton = {
                    TextButton(onClick = {
                        Ui.prompt = null
                        val perms = buildList {
                            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in p.resources) add(Manifest.permission.CAMERA)
                            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in p.resources) {
                                add(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                        withAndroidPermissions(perms) { ok ->
                            if (ok) p.request.grant(p.resources.toTypedArray()) else p.request.deny()
                        }
                    }) { Text("Allow") }
                },
                dismissButton = { TextButton(onClick = { p.request.deny(); Ui.prompt = null }) { Text("Block") } },
            )
        }
        is Prompt.Geo -> CleanDialog(
            onDismissRequest = { p.callback.invoke(p.origin, false, false); Ui.prompt = null },
            title = { Text(Urls.display(p.origin)) },
            text = { Text("Use your location?") },
            confirmButton = {
                TextButton(onClick = {
                    Ui.prompt = null
                    withAndroidPermissions(listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)) { ok ->
                        p.callback.invoke(p.origin, ok, false)
                    }
                }) { Text("Allow") }
            },
            dismissButton = { TextButton(onClick = { p.callback.invoke(p.origin, false, false); Ui.prompt = null }) { Text("Block") } },
        )
        is Prompt.OpenApp -> CleanDialog(
            onDismissRequest = { Ui.prompt = null },
            title = { Text("Open in ${p.label}?") },
            confirmButton = { TextButton(onClick = { Ui.prompt = null; activity?.let { Engine.launch(it, p.intent) } }) { Text("Open") } },
            dismissButton = { TextButton(onClick = { Ui.prompt = null }) { Text("Cancel") } },
        )
        is Prompt.HttpFallback -> CleanDialog(
            onDismissRequest = { Ui.prompt = null },
            title = { Text("No secure version") },
            text = { Text("${Urls.host(p.httpUrl)} does not support HTTPS.") },
            confirmButton = {
                TextButton(onClick = {
                    Ui.prompt = null
                    p.tab.httpAllowed.add(Urls.host(p.httpUrl))
                    Tabs.load(p.tab, p.httpUrl)
                }) { Text("Load over HTTP") }
            },
            dismissButton = { TextButton(onClick = { Ui.prompt = null }) { Text("Cancel") } },
        )
        is Prompt.Ssl -> CleanDialog(
            onDismissRequest = { Ui.prompt = null },
            title = { Text("Connection not secure") },
            text = { Text("${Urls.host(p.url)}: ${p.reason}") },
            confirmButton = { TextButton(onClick = { Ui.prompt = null }) { Text("OK") } },
        )
        is Prompt.Download -> DownloadDialog(p.request)
        is Prompt.Magnet -> CleanDialog(
            onDismissRequest = { Ui.prompt = null },
            title = { Text("Add torrent?") },
            text = { Text(magnetName(p.uri), maxLines = 3, overflow = TextOverflow.Ellipsis) },
            confirmButton = { TextButton(onClick = { Ui.prompt = null; Downloads.addMagnet(p.uri) }) { Text("Download") } },
            dismissButton = { TextButton(onClick = { Ui.prompt = null }) { Text("Cancel") } },
        )
        is Prompt.LinkMenu -> LinkMenu(p)
    }
}

private fun magnetName(uri: String): String =
    Regex("[?&]dn=([^&]+)").find(uri)?.groupValues?.get(1)?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
        ?: uri.take(80)

@Composable
private fun DownloadDialog(r: DownloadRequest) {
    CleanDialog(
        onDismissRequest = { Ui.prompt = null },
        title = { Text("Download file?") },
        text = {
            Column {
                Text(r.fileName, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                val ctx = LocalContext.current
                val size = if (r.size > 0) Formatter.formatFileSize(ctx, r.size) + " · " else ""
                Text(size + Urls.display(r.url), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { Ui.prompt = null; Downloads.enqueue(r) }) { Text("Download") } },
        dismissButton = { TextButton(onClick = { Ui.prompt = null }) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinkMenu(p: Prompt.LinkMenu) {
    val ctx = LocalContext.current
    fun close() { Ui.prompt = null }
    CleanSheet(onDismissRequest = { close() }) {
        Text(
            p.link ?: p.image ?: "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        val link = p.link
        if (link != null) {
            RowItem("Open in new tab", icon = Icons.Filled.Add, onClick = { close(); Tabs.open(link, p.tab.isPrivate, p.tab, select = false); Ui.toast("Opened in background") })
            if (!p.tab.isPrivate) RowItem("Open in private tab", icon = AppIcons.Private, onClick = { close(); Tabs.open(link, true, null, select = true) })
            RowItem("Copy link", icon = AppIcons.Copy, onClick = { close(); Browser.copy(ctx, link); Ui.toast("Link copied") })
            RowItem("Share link", icon = Icons.Filled.Share, onClick = { close(); Browser.share(ctx, link) })
            if (Urls.isWeb(link)) RowItem("Download link", icon = AppIcons.Download, onClick = { close(); downloadUrl(p, link) })
        }
        val img = p.image
        if (img != null && Urls.isWeb(img)) {
            RowItem("Open image", icon = AppIcons.Image, onClick = { close(); Tabs.open(img, p.tab.isPrivate, p.tab, select = true) })
            RowItem("Download image", icon = AppIcons.Download, onClick = { close(); downloadUrl(p, img) })
            RowItem("Copy image address", icon = AppIcons.Copy, onClick = { close(); Browser.copy(ctx, img); Ui.toast("Address copied") })
        }
        Spacer(Modifier.navigationBarsPadding().height(8.dp))
    }
}

private fun downloadUrl(p: Prompt.LinkMenu, url: String) {
    val ua = p.tab.webView?.settings?.userAgentString ?: ""
    Downloads.fromPage(p.tab, url, ua, null, null, -1)
}
