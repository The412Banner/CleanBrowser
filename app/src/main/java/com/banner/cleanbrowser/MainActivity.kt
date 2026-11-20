package com.banner.cleanbrowser

import android.app.SearchManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.banner.cleanbrowser.browser.Tabs
import com.banner.cleanbrowser.browser.Ui
import com.banner.cleanbrowser.core.SearchEngines
import com.banner.cleanbrowser.core.Urls
import com.banner.cleanbrowser.ui.AppRoot
import com.banner.cleanbrowser.ui.Browser
import com.banner.cleanbrowser.ui.Cleaner
import com.banner.cleanbrowser.ui.Nav
import com.banner.cleanbrowser.ui.Screen

class MainActivity : ComponentActivity() {
    private var quitting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val p = App.prefs

        // A previous run that should have cleared its data but was killed first.
        if (p.state.clearOnExit && p.getBool("dirty")) Cleaner.onQuit()
        p.putBool("dirty", true)

        Tabs.activity = this
        if (Tabs.list.isEmpty()) {
            val restored = Tabs.restoreSession()
            if (!restored) Tabs.open(Browser.homeUrl(), p.state.privateByDefault)
        }
        handleIntent(intent, fresh = true)

        setContent {
            AppRoot(onQuit = { quit() }, onSystemBars = { dark -> setBarIcons(dark) })
            BackHandler(enabled = Nav.screen == Screen.BROWSER) { onBrowserBack() }
            LaunchedEffect(Ui.customView) { immersive(Ui.customView != null) }
            val secure = App.prefs.state.privateNoScreenshots && Tabs.list.any { it.isPrivate }
            LaunchedEffect(secure) { secureWindow(secure) }
        }
    }

    private fun setBarIcons(dark: Boolean) {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.isAppearanceLightStatusBars = !dark
        c.isAppearanceLightNavigationBars = !dark
    }

    /** No screenshots, screen recording or Recents preview while private tabs are open. */
    private fun secureWindow(on: Boolean) {
        if (on) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(!on)
    }

    private fun immersive(on: Boolean) {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun onBrowserBack() {
        if (Ui.findBar) {
            Ui.findBar = false
            Tabs.current?.webView?.clearMatches()
            return
        }
        val tab = Tabs.current ?: return finishOrBackground()
        val wv = tab.webView
        val shown = wv?.url
        if (tab.isBlank && wv != null && shown != null && shown != "about:blank") {
            tab.url = shown
            tab.title = wv.title ?: ""
            return
        }
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
            return
        }
        if (tab.parentId != null && Tabs.list.any { it.id == tab.parentId }) {
            Tabs.close(tab)
            return
        }
        finishOrBackground()
    }

    private fun finishOrBackground() {
        if (App.prefs.state.clearOnExit) quit() else moveTaskToBack(true)
    }

    private fun quit() {
        quitting = true
        Tabs.saveSession()
        Cleaner.onQuit()
        App.prefs.putBool("dirty", false)
        finishAndRemoveTask()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent, fresh = false)
    }

    private fun handleIntent(i: Intent?, fresh: Boolean) {
        i ?: return
        val s = App.prefs.state
        if (i.action == com.banner.cleanbrowser.download.KeepAlive.ACTION_OPEN_DOWNLOADS) {
            Nav.open(Screen.DOWNLOADS)
            return
        }
        if (i.action == Intent.ACTION_VIEW) {
            val data = i.data
            if (data?.scheme.equals("magnet", true)) {
                Ui.prompt = com.banner.cleanbrowser.browser.Prompt.Magnet(data.toString())
                return
            }
            if (data != null && (i.type == "application/x-bittorrent" || data.scheme == "content" || data.scheme == "file")) {
                com.banner.cleanbrowser.download.Downloads.addTorrentFile(data)
                Nav.open(Screen.DOWNLOADS)
                return
            }
        }
        val target = when (i.action) {
            Intent.ACTION_VIEW -> i.dataString?.takeIf { Urls.isWeb(it) }
            Intent.ACTION_WEB_SEARCH -> i.getStringExtra(SearchManager.QUERY)?.let { q -> Urls.toUrl(q) ?: SearchEngines.searchUrl(s, q) }
            Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)?.let { t -> Urls.firstUrlIn(t) ?: SearchEngines.searchUrl(s, t.take(500)) }
            Intent.ACTION_PROCESS_TEXT -> i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.let { q ->
                Urls.toUrl(q.trim()) ?: SearchEngines.searchUrl(s, q.take(500))
            }
            else -> null
        } ?: return
        val cur = Tabs.current
        if (cur != null && cur.isBlank && fresh) Tabs.load(cur, target)
        else Tabs.open(target, s.privateByDefault)
        Nav.screen = Screen.BROWSER
    }

    override fun onResume() {
        super.onResume()
        Tabs.activity = this
        Tabs.current?.webView?.onResume()
    }

    override fun onPause() {
        super.onPause()
        Tabs.current?.webView?.onPause()
        Tabs.saveSession()
        com.banner.cleanbrowser.browser.Engine.cookieManager(false).flush()
    }

    override fun onDestroy() {
        if (isFinishing && !quitting) {
            Tabs.saveSession()
            Cleaner.onQuit()
            App.prefs.putBool("dirty", false)
        }
        Tabs.detachActivity()
        super.onDestroy()
    }
}
