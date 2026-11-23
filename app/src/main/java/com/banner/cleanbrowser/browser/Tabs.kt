package com.banner.cleanbrowser.browser

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import com.banner.cleanbrowser.App
import com.banner.cleanbrowser.core.Urls
import org.json.JSONArray
import org.json.JSONObject

object Tabs {
    private const val MAX_LIVE = 6

    val list = mutableStateListOf<Tab>()
    var currentId by mutableLongStateOf(-1L)
    private var nextId = 1L

    var activity: Activity? = null
    var dark: Boolean = false

    val current: Tab? get() = list.firstOrNull { it.id == currentId }

    fun open(url: String, private: Boolean, parent: Tab? = null, select: Boolean = true): Tab {
        val tab = Tab(nextId++, private, url, parent?.id)
        val at = parent?.let { list.indexOf(it) + 1 }?.takeIf { it in 1..list.size } ?: list.size
        list.add(at, tab)
        if (select || current == null) select(tab)
        return tab
    }

    fun select(tab: Tab) {
        currentId = tab.id
        tab.lastUsed = System.currentTimeMillis()
        trim()
    }

    /** Creates the WebView of a tab if needed and starts its pending load. */
    fun webViewFor(tab: Tab): WebView? {
        tab.webView?.let { return it }
        val act = activity ?: return null
        val wv = Engine.create(act, tab, dark)
        tab.webView = wv
        tab.pendingLoad?.let { url ->
            tab.pendingLoad = null
            load(tab, url)
        }
        return wv
    }

    /** New tab for window.open / target=_blank; returns its WebView for the transport. */
    fun openForWindow(parent: Tab): WebView? {
        val tab = open("", parent.isPrivate, parent, select = true)
        tab.url = "about:blank"
        return webViewFor(tab)
    }

    fun load(tab: Tab, url: String) {
        Urls.magnetIn(url)?.let { m ->
            Ui.prompt = Prompt.Magnet(m)
            if (tab.webView?.url == null) {
                tab.pendingLoad = null
                tab.url = ""
            }
            return
        }
        val wv = tab.webView
        if (wv == null) {
            tab.pendingLoad = url
            tab.url = url
            return
        }
        var target = url
        val p = App.prefs.state
        if (Urls.isHttp(target) && p.httpsOnly && Urls.host(target) !in tab.httpAllowed) {
            tab.upgradedHost = Urls.host(target)
            target = Urls.upgradeToHttps(target)
        }
        Engine.prepareFor(wv, tab, target)
        tab.url = target
        val headers = if (p.gpc) mapOf("Sec-GPC" to "1") else emptyMap()
        wv.loadUrl(target, headers)
    }

    fun close(tab: Tab) {
        val idx = list.indexOf(tab)
        if (idx < 0) return
        destroyWebView(tab)
        list.removeAt(idx)
        if (tab.isPrivate && list.none { it.isPrivate }) clearPrivateData()
        if (currentId == tab.id) {
            val next = list.firstOrNull { it.id == tab.parentId && it.isPrivate == tab.isPrivate }
                ?: list.filter { it.isPrivate == tab.isPrivate }.maxByOrNull { it.lastUsed }
                ?: list.maxByOrNull { it.lastUsed }
            if (next != null) select(next) else open("", false)
        }
    }

    fun closeAll(private: Boolean) {
        list.filter { it.isPrivate == private }.forEach { destroyWebView(it) }
        list.removeAll { it.isPrivate == private }
        if (private) clearPrivateData()
        if (current == null) {
            val next = list.maxByOrNull { it.lastUsed }
            if (next != null) select(next) else open("", false)
        }
    }

    fun closeEverything() {
        list.forEach { destroyWebView(it) }
        list.clear()
        clearPrivateData()
    }

    fun onRendererGone(tab: Tab) {
        val url = tab.url
        destroyWebView(tab)
        tab.pendingLoad = url.ifEmpty { null }
        tab.loading = false
        Ui.toast("Page crashed and was reloaded")
    }

    private fun destroyWebView(tab: Tab) {
        val wv = tab.webView ?: return
        tab.webView = null
        tab.webrtcScript = null
        tab.generation++
        (wv.parent as? ViewGroup)?.removeView(wv)
        wv.stopLoading()
        wv.webChromeClient = null
        wv.destroy()
    }

    /** Keeps at most [MAX_LIVE] background WebViews; older ones reload when opened again. */
    private fun trim() {
        val live = list.filter { it.webView != null && it.id != currentId }.sortedByDescending { it.lastUsed }
        live.drop(MAX_LIVE).forEach { t ->
            t.pendingLoad = t.url.ifEmpty { null }
            destroyWebView(t)
        }
    }

    fun captureThumbnail(tab: Tab) {
        val wv = tab.webView ?: return
        if (wv.width <= 0 || wv.height <= 0) return
        runCatching {
            val scale = 0.33f
            val w = (wv.width * scale).toInt().coerceAtLeast(1)
            val h = (wv.width * scale * 1.25f).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            val c = Canvas(bmp)
            c.scale(scale, scale)
            c.translate(-wv.scrollX.toFloat(), -wv.scrollY.toFloat())
            wv.draw(c)
            tab.thumbnail = bmp.asImageBitmap()
        }
    }

    fun clearPrivateData() {
        if (!Engine.multiProfile) return
        runCatching {
            Engine.cookieManager(true).removeAllCookies(null)
            Engine.webStorage(true).deleteAllData()
        }
    }

    /** Called when the activity goes away: WebViews hold the activity, so they go too. */
    fun detachActivity() {
        list.forEach { t ->
            if (t.webView != null) {
                t.pendingLoad = t.url.ifEmpty { null }
                destroyWebView(t)
            }
        }
        activity = null
    }

    fun reapplySettings() {
        list.forEach { t -> t.webView?.let { Engine.apply(it, t, dark) } }
    }

    // Session (normal tabs only).
    fun saveSession() {
        val p = App.prefs
        if (!p.state.restoreTabs) {
            p.putString("session", "")
            return
        }
        val arr = JSONArray()
        list.filter { !it.isPrivate && it.url.isNotEmpty() && it.url != "about:blank" }.forEach { t ->
            arr.put(JSONObject().put("url", t.url).put("title", t.title).put("current", t.id == currentId))
        }
        p.putString("session", arr.toString())
    }

    fun restoreSession(): Boolean {
        val raw = App.prefs.getString("session")
        if (raw.isEmpty() || !App.prefs.state.restoreTabs) return false
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return false
        var selectTab: Tab? = null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val t = open(o.optString("url"), private = false, select = false)
            t.title = o.optString("title")
            if (o.optBoolean("current")) selectTab = t
        }
        (selectTab ?: list.lastOrNull())?.let { select(it) }
        return list.isNotEmpty()
    }
}
