package com.banner.cleanbrowser.browser

import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap

class Tab(val id: Long, val isPrivate: Boolean, startUrl: String, val parentId: Long?) {
    var webView: WebView? = null
    /** Document-start script that hides WebRTC while it is wanted (see Engine.apply). */
    var webrtcScript: androidx.webkit.ScriptHandler? = null

    var url by mutableStateOf(startUrl)
    var title by mutableStateOf("")
    var progress by mutableIntStateOf(100)
    var loading by mutableStateOf(false)
    var canBack by mutableStateOf(false)
    var canForward by mutableStateOf(false)
    var blockedCount by mutableIntStateOf(0)
    var thumbnail by mutableStateOf<ImageBitmap?>(null)
    var favicon by mutableStateOf<ImageBitmap?>(null)
    var desktop by mutableStateOf(false)
    var findActive by mutableIntStateOf(0)
    var findTotal by mutableIntStateOf(0)
    /** Bumped when the WebView is destroyed, so the view host builds a new one. */
    var generation by mutableIntStateOf(0)

    /** Host of the top page, read from the network thread by the blocker. */
    @Volatile var pageHost: String = ""
    @Volatile var blockingOn: Boolean = true

    /** Hosts the user allowed over plain http in this tab. */
    val httpAllowed = HashSet<String>()
    /** Host that was upgraded to https on the last navigation, for the http fallback offer. */
    var upgradedHost: String? = null

    /** URL to load when the WebView is (re)created. */
    var pendingLoad: String? = startUrl.ifEmpty { null }
    var lastUsed: Long = System.currentTimeMillis()

    val isBlank: Boolean get() = url.isEmpty()
}
