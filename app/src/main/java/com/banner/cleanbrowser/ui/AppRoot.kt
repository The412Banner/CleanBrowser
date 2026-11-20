package com.banner.cleanbrowser.ui

import android.text.format.Formatter
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.banner.cleanbrowser.App
import com.banner.cleanbrowser.browser.Tabs
import com.banner.cleanbrowser.browser.Ui
import com.banner.cleanbrowser.update.Updater
import kotlinx.coroutines.launch

@Composable
fun AppRoot(onQuit: () -> Unit, onSystemBars: (dark: Boolean) -> Unit) {
    val s = App.prefs.state
    val dark = isDark(s)
    val privateTab = Tabs.current?.isPrivate == true
    SideEffect {
        if (Tabs.dark != dark) {
            Tabs.dark = dark
            Tabs.reapplySettings()
        }
        onSystemBars(dark || (privateTab && Nav.screen == Screen.BROWSER))
    }
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { Updater.autoCheck(ctx) }
    val notifLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { App.prefs.putBool("askedNotifications", true) }
    val dlCount = com.banner.cleanbrowser.download.Downloads.items.size
    LaunchedEffect(dlCount) {
        if (dlCount > 0 && android.os.Build.VERSION.SDK_INT >= 33 && s.dlNotifications && !App.prefs.getBool("askedNotifications") &&
            androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    CleanTheme(s) {
        androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize()) {
            CleanTheme(s, privateMode = privateTab) {
                BrowserScreen(onQuit)
            }
            Overlay(Nav.screen == Screen.TABS, zoom = true) {
                CleanTheme(s, privateMode = Nav.showPrivateTabs) { TabsScreen() }
            }
            Overlay(Nav.screen == Screen.SETTINGS) { SettingsScreen() }
            Overlay(Nav.screen == Screen.BOOKMARKS) { BookmarksScreen() }
            Overlay(Nav.screen == Screen.HISTORY) { HistoryScreen() }
            Overlay(Nav.screen == Screen.DOWNLOADS) { DownloadsScreen() }
            PromptHost()
            UpdateDialog()
            MessageHost()
            FullscreenVideo()
        }
        }
        BackHandler(enabled = Nav.screen == Screen.TABS) { Nav.screen = Screen.BROWSER }
    }
}

@Composable
private fun Overlay(visible: Boolean, zoom: Boolean = false, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = if (zoom) fadeIn(Motion.fast()) + scaleIn(Motion.medium(), initialScale = 0.96f)
        else fadeIn(Motion.fast()) + slideInHorizontally(Motion.medium()) { it / 8 },
        exit = if (zoom) fadeOut(Motion.fast()) + scaleOut(Motion.fast(), targetScale = 0.98f)
        else fadeOut(Motion.fast()) + slideOutHorizontally(Motion.fast()) { it / 10 },
    ) { content() }
}

@Composable
private fun FullscreenVideo() {
    val cv = Ui.customView ?: return
    BackHandler { cv.callback.onCustomViewHidden(); Ui.customView = null }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                FrameLayout(ctx).apply {
                    (cv.view.parent as? android.view.ViewGroup)?.removeView(cv.view)
                    addView(cv.view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                }
            },
            onRelease = { it.removeAllViews() },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun UpdateDialog() {
    if (!Updater.showDialog) return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    when (val st = Updater.state) {
        is Updater.State.Available -> CleanDialog(
            onDismissRequest = { Updater.showDialog = false },
            title = { Text("Update ${st.release.version}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(Formatter.formatFileSize(ctx, st.release.size), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (st.release.notes.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(st.release.notes.take(1500), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { scope.launch { Updater.download(ctx, st.release) } }) { Text("Download") } },
            dismissButton = { TextButton(onClick = { Updater.showDialog = false }) { Text("Later") } },
        )
        is Updater.State.Downloading -> CleanDialog(
            onDismissRequest = {},
            title = { Text("Downloading ${st.release.version}") },
            text = { Text("${st.percent}%") },
            confirmButton = {},
        )
        is Updater.State.Ready -> CleanDialog(
            onDismissRequest = { Updater.showDialog = false },
            title = { Text("Ready to install") },
            text = { Text("Checksum and signature verified.") },
            confirmButton = { TextButton(onClick = { Updater.install(ctx, st.file) }) { Text("Install") } },
            dismissButton = { TextButton(onClick = { Updater.showDialog = false }) { Text("Later") } },
        )
        is Updater.State.Failed -> CleanDialog(
            onDismissRequest = { Updater.showDialog = false },
            title = { Text("Update failed") },
            text = { Text(st.message) },
            confirmButton = { TextButton(onClick = { Updater.showDialog = false }) { Text("OK") } },
        )
        else -> {}
    }
}
