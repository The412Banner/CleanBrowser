package com.banner.cleanbrowser.net

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.banner.cleanbrowser.App
import com.banner.cleanbrowser.core.NetMode
import com.banner.cleanbrowser.torrent.Torrents
import java.util.concurrent.Executor

/**
 * Applies the VPN & proxy choice everywhere: WebView proxy override, the download client
 * and the torrent session. With the kill switch on, the browser is pointed at a closed local
 * port while the chosen route is down, so nothing leaks around it.
 */
object Routing {
    var browserRoute by mutableStateOf("Direct")
        private set

    private val direct = Executor { it.run() }
    private const val BLACKHOLE = "http://127.0.0.1:9"

    /** Starts or stops Tor / WireGuard to match the setting, then re-applies routes. */
    fun onModeChanged() {
        val ctx = App.instance
        val s = App.prefs.state
        if (s.netMode == NetMode.TOR) Tor.start(ctx) else if (Tor.state != Tor.State.OFF) Tor.stop(ctx)
        if (s.netMode != NetMode.WIREGUARD && WireGuard.state != WireGuard.State.OFF) WireGuard.disconnect()
        if (s.netMode == NetMode.WIREGUARD && WireGuard.state == WireGuard.State.OFF) {
            WireGuard.servers.firstOrNull { it.id == s.wgActive }?.let { srv ->
                if (!WireGuard.needsConsent(ctx)) WireGuard.connect(ctx, srv)
            }
        }
        apply()
    }

    fun apply() {
        Net.invalidate()
        Torrents.applySettings()
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            applyBrowser()
            com.banner.cleanbrowser.browser.Tabs.reapplySettings()
        }
    }

    private fun applyBrowser() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            browserRoute = "Direct (proxy not supported by this WebView)"
            return
        }
        val s = App.prefs.state
        val pc = ProxyController.getInstance()
        val blocked = Net.blocked(s, s.netBrowser)
        val rules: List<String>? = when {
            !s.netBrowser || s.netMode == NetMode.DIRECT -> null
            blocked -> listOf(BLACKHOLE)
            s.netMode == NetMode.PROXY -> {
                val hp = "${s.proxyHost.trim()}:${s.proxyPort}"
                if (s.proxyType == "HTTP") listOf("http://$hp") else listOf("socks5://$hp", "socks://$hp")
            }
            s.netMode == NetMode.TOR -> buildList {
                if (Tor.socksPort > 0) add("socks5://127.0.0.1:${Tor.socksPort}")
                if (Tor.httpPort > 0) add("http://127.0.0.1:${Tor.httpPort}")
            }.ifEmpty { listOf(BLACKHOLE) }
            else -> null // WireGuard: the VPN service carries the app's traffic.
        }
        if (rules == null) {
            runCatching { pc.clearProxyOverride(direct) {} }
            browserRoute = if (s.netMode == NetMode.WIREGUARD && s.netBrowser) "WireGuard" else "Direct"
            return
        }
        for (rule in rules) {
            val ok = runCatching {
                val cfg = ProxyConfig.Builder().addProxyRule(rule).build()
                pc.setProxyOverride(cfg, direct) {}
            }.isSuccess
            if (ok) {
                browserRoute = when {
                    rule == BLACKHOLE -> "Blocked until connected"
                    s.netMode == NetMode.TOR -> "Tor"
                    else -> "Proxy " + rule.substringBefore("://").uppercase()
                }
                return
            }
        }
        // No rule was accepted: keep traffic blocked if the kill switch is on.
        if (s.killSwitch) runCatching { pc.setProxyOverride(ProxyConfig.Builder().addProxyRule(BLACKHOLE).build(), direct) {} }
        browserRoute = "Proxy not accepted by WebView"
    }
}
