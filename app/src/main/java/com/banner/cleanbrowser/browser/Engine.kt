package com.banner.cleanbrowser.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.ContextThemeWrapper
import android.view.View
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.graphics.asImageBitmap
import androidx.webkit.ProfileStore
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.banner.cleanbrowser.App
import com.banner.cleanbrowser.R
import com.banner.cleanbrowser.block.FilterLists
import com.banner.cleanbrowser.block.ReqType
import com.banner.cleanbrowser.core.AppLinks
import com.banner.cleanbrowser.core.SitePermission
import com.banner.cleanbrowser.core.UserAgentMode
import com.banner.cleanbrowser.core.Urls
import com.banner.cleanbrowser.download.Downloads
import java.io.ByteArrayInputStream

/** WebView that can ask the keyboard not to learn (incognito keyboard) in private tabs. */
class CbWebView(context: android.content.Context) : WebView(context) {
    var incognitoKeyboard = false

    override fun onCreateInputConnection(outAttrs: android.view.inputmethod.EditorInfo): android.view.inputmethod.InputConnection? {
        val ic = super.onCreateInputConnection(outAttrs)
        if (incognitoKeyboard) {
            outAttrs.imeOptions = outAttrs.imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        return ic
    }
}

object Engine {
    const val PRIVATE_PROFILE = "private"
    private val main = Handler(Looper.getMainLooper())

    val multiProfile: Boolean by lazy {
        runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }.getOrDefault(false)
    }

    private const val GPC_JS = "try{Object.defineProperty(Navigator.prototype,'globalPrivacyControl',{get:function(){return true},configurable:true});}catch(e){}"

    private var defaultUa: String? = null

    fun cookieManager(private: Boolean): CookieManager =
        if (private && multiProfile) ProfileStore.getInstance().getOrCreateProfile(PRIVATE_PROFILE).cookieManager
        else CookieManager.getInstance()

    fun webStorage(private: Boolean): WebStorage =
        if (private && multiProfile) ProfileStore.getInstance().getOrCreateProfile(PRIVATE_PROFILE).webStorage
        else WebStorage.getInstance()

    @SuppressLint("SetJavaScriptEnabled")
    fun create(activity: Activity, tab: Tab, dark: Boolean): WebView {
        val ctx = ContextThemeWrapper(activity, if (dark) R.style.WebDark else R.style.WebLight)
        val wv = CbWebView(ctx)
        if (tab.isPrivate && multiProfile) WebViewCompat.setProfile(wv, PRIVATE_PROFILE)
        if (defaultUa == null) defaultUa = wv.settings.userAgentString
        wv.isFocusable = true
        wv.isFocusableInTouchMode = true
        wv.webViewClient = Client(tab)
        wv.webChromeClient = Chrome(tab)
        wv.setDownloadListener { url, ua, disposition, mime, length ->
            Downloads.fromPage(tab, url, ua, disposition, mime, length)
        }
        wv.setFindListener { active, total, _ ->
            tab.findActive = if (total == 0) 0 else active + 1
            tab.findTotal = total
        }
        wv.setOnLongClickListener { onLongPress(wv, tab) }
        wv.addJavascriptInterface(Downloads.BlobBridge(), "CleanBrowserBlob")
        if (App.prefs.state.gpc && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching { WebViewCompat.addDocumentStartJavaScript(wv, GPC_JS, setOf("*")) }
        }
        tab.desktop = siteDesktop(tab.pendingLoad ?: "") ?: App.prefs.state.desktopDefault
        apply(wv, tab, dark)
        return wv
    }

    private fun siteDesktop(url: String): Boolean? {
        val h = Urls.host(url)
        if (h.isEmpty()) return null
        return when (App.sites.get(h).desktop) { 1 -> true; 0 -> false; else -> null }
    }

    fun userAgent(desktop: Boolean): String? {
        val p = App.prefs.state
        val base = defaultUa ?: return null
        val chrome = Regex("Chrome/([0-9.]+)").find(base)?.groupValues?.get(1) ?: "130.0.0.0"
        val major = chrome.substringBefore('.')
        if (desktop) return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36"
        return when (p.userAgent) {
            UserAgentMode.WEBVIEW -> base
            UserAgentMode.DESKTOP -> "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36"
            UserAgentMode.CUSTOM -> p.customUserAgent.ifBlank { base }
            UserAgentMode.CHROME -> base.replace("; wv)", ")").replace(" Version/4.0", "")
        }
    }

    /** Applies the current settings to a WebView. Safe to call again after settings change. */
    @SuppressLint("SetJavaScriptEnabled")
    fun apply(wv: WebView, tab: Tab, dark: Boolean) {
        val p = App.prefs.state
        val s = wv.settings
        s.javaScriptEnabled = javascriptFor(Urls.host(tab.url.ifEmpty { tab.pendingLoad ?: "" }))
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = p.blockAutoplay
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = !p.blockPopups
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.setSupportZoom(true)
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.textZoom = p.textZoom
        s.loadsImagesAutomatically = p.loadImages
        s.blockNetworkImage = !p.loadImages
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.setGeolocationEnabled(p.location != SitePermission.BLOCK)
        s.safeBrowsingEnabled = p.safeBrowsing
        userAgent(tab.desktop)?.let { if (s.userAgentString != it) s.userAgentString = it }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            runCatching { WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, emptySet()) }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            runCatching { WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, p.darkPages && dark) }
        }
        if (p.noSpeculative && WebViewFeature.isFeatureSupported(WebViewFeature.SPECULATIVE_LOADING)) {
            runCatching { WebSettingsCompat.setSpeculativeLoadingStatus(s, WebSettingsCompat.SPECULATIVE_LOADING_DISABLED) }
        }
        if (p.noAttribution && WebViewFeature.isFeatureSupported(WebViewFeature.ATTRIBUTION_REGISTRATION_BEHAVIOR)) {
            runCatching { WebSettingsCompat.setAttributionRegistrationBehavior(s, WebSettingsCompat.ATTRIBUTION_BEHAVIOR_DISABLED) }
        }
        (wv as? CbWebView)?.incognitoKeyboard = tab.isPrivate && p.privateIncognitoKeyboard
        applyWebRtc(wv, tab)
        val cm = cookieManager(tab.isPrivate)
        cm.setAcceptCookie(p.cookies)
        cm.setAcceptThirdPartyCookies(wv, p.cookies && p.thirdPartyCookies)
        WebView.setWebContentsDebuggingEnabled(p.remoteDebugging)
    }

    private const val NO_WEBRTC_JS = "(function(){var n=['RTCPeerConnection','webkitRTCPeerConnection','RTCDataChannel'," +
        "'RTCIceCandidate','RTCSessionDescription','RTCRtpSender','RTCRtpReceiver','RTCRtpTransceiver'];" +
        "for(var i=0;i<n.length;i++){try{Object.defineProperty(window,n[i],{get:function(){return undefined},configurable:false});}catch(e){}}})();"

    /** WebRTC can reach the network around a proxy and reveal the real address; hide it when asked. */
    fun webRtcBlocked(): Boolean {
        val p = App.prefs.state
        return when (p.webrtcBlock) {
            "always" -> true
            "never" -> false
            else -> p.netBrowser && (p.netMode == com.banner.cleanbrowser.core.NetMode.TOR || p.netMode == com.banner.cleanbrowser.core.NetMode.PROXY)
        }
    }

    private fun applyWebRtc(wv: WebView, tab: Tab) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        val want = webRtcBlocked()
        if (want && tab.webrtcScript == null) {
            tab.webrtcScript = runCatching { WebViewCompat.addDocumentStartJavaScript(wv, NO_WEBRTC_JS, setOf("*")) }.getOrNull()
        } else if (!want && tab.webrtcScript != null) {
            runCatching { tab.webrtcScript?.remove() }
            tab.webrtcScript = null
        }
    }

    fun javascriptFor(host: String): Boolean {
        val site = if (host.isEmpty()) -1 else App.sites.get(host).javascript
        return when (site) { 1 -> true; 0 -> false; else -> App.prefs.state.javascript }
    }

    fun blockingFor(host: String): Boolean {
        val site = if (host.isEmpty()) -1 else App.sites.get(host).blocking
        return when (site) { 1 -> true; 0 -> false; else -> App.prefs.state.blockAds }
    }

    private fun onLongPress(wv: WebView, tab: Tab): Boolean {
        val r = wv.hitTestResult
        when (r.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                val link = r.extra ?: return false
                Ui.prompt = Prompt.LinkMenu(tab, link, null)
                return true
            }
            WebView.HitTestResult.IMAGE_TYPE -> {
                val img = r.extra ?: return false
                Ui.prompt = Prompt.LinkMenu(tab, null, img)
                return true
            }
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                val img = r.extra
                val h = Handler(Looper.getMainLooper()) { msg ->
                    val href = msg.data.getString("url")
                    Ui.prompt = Prompt.LinkMenu(tab, href, img)
                    true
                }
                wv.requestFocusNodeHref(h.obtainMessage())
                return true
            }
        }
        return false
    }

    /** Decides what happens with a top-level navigation. True = we handled it. */
    fun handleNavigation(view: WebView, tab: Tab, url: String): Boolean {
        val p = App.prefs.state
        val lower = url.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            Urls.magnetIn(url)?.let { m ->
                Ui.prompt = Prompt.Magnet(m)
                return true
            }
            val host = Urls.host(url)
            if (lower.startsWith("http://") && p.httpsOnly && host !in tab.httpAllowed) {
                tab.upgradedHost = host
                view.loadUrl(Urls.upgradeToHttps(url))
                return true
            }
            prepareFor(view, tab, url)
            return false
        }
        if (lower.startsWith("about:") || lower.startsWith("data:") || lower.startsWith("blob:") || lower.startsWith("javascript:")) return false
        if (lower.startsWith("magnet:")) {
            Ui.prompt = Prompt.Magnet(url)
            return true
        }
        openExternal(view, url)
        return true
    }

    /** Per-site JavaScript and desktop mode, set before the page starts loading. */
    @SuppressLint("SetJavaScriptEnabled")
    fun prepareFor(view: WebView, tab: Tab, url: String) {
        val host = Urls.host(url)
        val js = javascriptFor(host)
        if (view.settings.javaScriptEnabled != js) view.settings.javaScriptEnabled = js
        siteDesktop(url)?.let { d ->
            if (d != tab.desktop) {
                tab.desktop = d
                userAgent(d)?.let { view.settings.userAgentString = it }
            }
        }
    }

    private fun openExternal(view: WebView, url: String) {
        val p = App.prefs.state
        val intent = try {
            if (url.startsWith("intent:", true)) Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            else Intent(Intent.ACTION_VIEW, Uri.parse(url))
        } catch (_: Exception) {
            return
        }
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        val fallback = intent.getStringExtra("browser_fallback_url")
        val pm = view.context.packageManager
        val resolvable = intent.resolveActivity(pm) != null
        if (p.appLinks == AppLinks.NEVER || !resolvable) {
            if (fallback != null && Urls.isWeb(fallback)) view.loadUrl(fallback)
            else if (!resolvable) Ui.toast("No app can open this link")
            return
        }
        val label = runCatching { intent.resolveActivityInfo(pm, 0)?.loadLabel(pm)?.toString() }.getOrNull() ?: "another app"
        Ui.prompt = Prompt.OpenApp(intent, label)
    }

    fun launch(activity: Activity, intent: Intent) {
        try {
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Ui.toast("No app can open this link")
        } catch (_: SecurityException) {
            Ui.toast("Not allowed")
        }
    }

    private fun guessType(r: WebResourceRequest): ReqType {
        if (r.isForMainFrame) return ReqType.DOCUMENT
        val accept = r.requestHeaders["Accept"] ?: r.requestHeaders["accept"] ?: ""
        if (accept.startsWith("text/css")) return ReqType.STYLESHEET
        if (accept.startsWith("image/")) return ReqType.IMAGE
        if (accept.startsWith("text/html")) return ReqType.SUBDOCUMENT
        if (r.requestHeaders["X-Requested-With"] == "XMLHttpRequest") return ReqType.XHR
        val path = (r.url.path ?: "").lowercase()
        return when {
            path.endsWith(".js") || path.endsWith(".mjs") -> ReqType.SCRIPT
            path.endsWith(".css") -> ReqType.STYLESHEET
            path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".gif") ||
                path.endsWith(".webp") || path.endsWith(".svg") || path.endsWith(".ico") || path.endsWith(".avif") -> ReqType.IMAGE
            path.endsWith(".woff") || path.endsWith(".woff2") || path.endsWith(".ttf") || path.endsWith(".otf") -> ReqType.FONT
            path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".m3u8") || path.endsWith(".mp3") ||
                path.endsWith(".ogg") || path.endsWith(".m4a") -> ReqType.MEDIA
            else -> ReqType.UNKNOWN
        }
    }

    private class Client(private val tab: Tab) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            return handleNavigation(view, tab, request.url.toString())
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url.toString()
            if (request.isForMainFrame) {
                val host = Urls.host(url)
                tab.pageHost = host
                tab.blockingOn = blockingFor(host)
                main.post { tab.blockedCount = 0 }
                return null
            }
            if (!tab.blockingOn) return null
            if (!(url.startsWith("http://") || url.startsWith("https://"))) return null
            val type = guessType(request)
            if (FilterLists.blocker.shouldBlock(url, tab.pageHost, type)) {
                main.post { tab.blockedCount++ }
                return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            }
            return null
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            tab.url = url
            tab.loading = true
            tab.favicon = favicon?.asImageBitmap()
            tab.findTotal = 0
            tab.canBack = view.canGoBack()
            tab.canForward = view.canGoForward()
        }

        override fun onPageFinished(view: WebView, url: String) {
            tab.loading = false
            tab.url = url
            view.title?.let { if (it.isNotBlank() && !it.startsWith("http")) tab.title = it }
            tab.canBack = view.canGoBack()
            tab.canForward = view.canGoForward()
            if (!tab.isPrivate && App.prefs.state.saveHistory && Urls.isWeb(url)) {
                val title = tab.title
                App.io { App.db.addHistory(url, title) }
            }
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            tab.url = url
            tab.canBack = view.canGoBack()
            tab.canForward = view.canGoForward()
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            if (error.url == tab.url || error.url == view.url || Urls.host(error.url) == tab.pageHost) {
                val reason = when (error.primaryError) {
                    SslError.SSL_EXPIRED -> "The certificate has expired."
                    SslError.SSL_IDMISMATCH -> "The certificate is for a different site."
                    SslError.SSL_UNTRUSTED -> "The certificate is not trusted."
                    SslError.SSL_NOTYETVALID -> "The certificate is not valid yet."
                    SslError.SSL_DATE_INVALID -> "The certificate date is invalid."
                    else -> "The certificate is invalid."
                }
                Ui.prompt = Prompt.Ssl(tab, error.url, reason)
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            val url = request.url.toString()
            val host = Urls.host(url)
            if (Urls.isHttps(url) && tab.upgradedHost == host) {
                when (error.errorCode) {
                    ERROR_CONNECT, ERROR_TIMEOUT, ERROR_FAILED_SSL_HANDSHAKE, ERROR_UNKNOWN, ERROR_IO -> {
                        Ui.prompt = Prompt.HttpFallback(tab, "http://" + url.substring(8))
                    }
                }
            }
        }

        override fun onReceivedHttpAuthRequest(view: WebView, handler: android.webkit.HttpAuthHandler, host: String, realm: String?) {
            val p = App.prefs.state
            // Credentials for the user's own HTTP proxy; other sites get the normal prompt-less cancel.
            if (p.netMode == com.banner.cleanbrowser.core.NetMode.PROXY && host.equals(p.proxyHost.trim(), true) && p.proxyUser.isNotEmpty()) {
                handler.proceed(p.proxyUser, p.proxyPass)
            } else {
                handler.cancel()
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            main.post { Tabs.onRendererGone(tab) }
            return true
        }
    }

    private class Chrome(private val tab: Tab) : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            tab.progress = newProgress
            if (newProgress >= 100) tab.loading = false
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            if (!title.isNullOrBlank()) {
                tab.title = title
                if (!tab.isPrivate && App.prefs.state.saveHistory) {
                    val u = tab.url
                    App.io { App.db.updateHistoryTitle(u, title) }
                }
            }
        }

        override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
            tab.favicon = icon?.asImageBitmap()
            if (icon != null && !tab.isPrivate) com.banner.cleanbrowser.core.Favicons.save(tab.url, icon)
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (App.prefs.state.blockPopups && !isUserGesture) {
                Ui.toast("Pop-up blocked")
                return false
            }
            val newWv = Tabs.openForWindow(tab) ?: return false
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            transport.webView = newWv
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView) {
            Tabs.close(tab)
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            val p = App.prefs.state
            val wanted = request.resources.filter { r ->
                when (r) {
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> p.camera != SitePermission.BLOCK
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> p.microphone != SitePermission.BLOCK
                    PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> p.protectedMedia != SitePermission.BLOCK
                    else -> false
                }
            }
            if (wanted.isEmpty()) {
                request.deny()
                return
            }
            main.post { Ui.prompt = Prompt.Permission(tab, request, wanted) }
        }

        override fun onPermissionRequestCanceled(request: PermissionRequest) {
            val cur = Ui.prompt
            if (cur is Prompt.Permission && cur.request == request) Ui.prompt = null
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            if (App.prefs.state.location == SitePermission.BLOCK) {
                callback.invoke(origin, false, false)
                return
            }
            Ui.prompt = Prompt.Geo(tab, origin, callback)
        }

        override fun onShowFileChooser(webView: WebView, filePathCallback: ValueCallback<Array<Uri>>, fileChooserParams: FileChooserParams): Boolean {
            Ui.fileChooser?.callback?.onReceiveValue(null)
            Ui.fileChooser = FileChooser(filePathCallback, fileChooserParams)
            return true
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            Ui.customView?.callback?.onCustomViewHidden()
            Ui.customView = CustomView(view, callback)
        }

        override fun onHideCustomView() {
            Ui.customView = null
        }

        override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }
}
