package com.banner.cleanbrowser.net

import com.banner.cleanbrowser.App
import com.banner.cleanbrowser.core.NetMode
import com.banner.cleanbrowser.core.Settings
import okhttp3.Credentials
import okhttp3.OkHttpClient
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** Where a kind of traffic should go. */
data class Route(val proxy: Proxy, val user: String = "", val pass: String = "", val viaVpn: Boolean = false) {
    val direct get() = proxy == Proxy.NO_PROXY
}

/** Shared HTTP client for downloads, lists and updates, following the VPN & proxy settings. */
object Net {
    @Volatile private var cached: OkHttpClient? = null
    @Volatile private var cachedKey: String = ""

    /** Route for downloads (and the app's own requests). */
    fun downloadRoute(s: Settings = App.prefs.state): Route = routeFor(s, s.netDownloads)

    fun routeFor(s: Settings, enabled: Boolean): Route {
        if (!enabled) return Route(Proxy.NO_PROXY)
        return when (s.netMode) {
            NetMode.DIRECT -> Route(Proxy.NO_PROXY)
            NetMode.PROXY -> {
                if (s.proxyHost.isBlank()) Route(Proxy.NO_PROXY) else {
                    val type = if (s.proxyType == "HTTP") Proxy.Type.HTTP else Proxy.Type.SOCKS
                    Route(Proxy(type, InetSocketAddress.createUnresolved(s.proxyHost.trim(), s.proxyPort)), s.proxyUser, s.proxyPass)
                }
            }
            NetMode.TOR -> {
                val port = Tor.socksPort
                if (port <= 0) Route(Proxy.NO_PROXY, viaVpn = true) // not ready: callers check [blocked]
                else Route(Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", port)))
            }
            // WireGuard tunnels the whole app at the system level, so requests go out directly.
            NetMode.WIREGUARD -> Route(Proxy.NO_PROXY, viaVpn = true)
        }
    }

    /** True when the kill switch should hold traffic because the chosen VPN/proxy is not up. */
    fun blocked(s: Settings, enabled: Boolean): Boolean {
        if (!enabled || !s.killSwitch) return false
        return when (s.netMode) {
            NetMode.DIRECT -> false
            NetMode.PROXY -> s.proxyHost.isBlank()
            NetMode.TOR -> Tor.socksPort <= 0
            NetMode.WIREGUARD -> !WireGuard.connected
        }
    }

    val client: OkHttpClient
        get() {
            val s = App.prefs.state
            val r = downloadRoute(s)
            val key = "${r.proxy}|${r.user}|${r.pass}"
            cached?.let { if (key == cachedKey) return it }
            synchronized(this) {
                cached?.let { if (key == cachedKey) return it }
                val b = OkHttpClient.Builder()
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .readTimeout(45, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .retryOnConnectionFailure(true)
                    // HTTP/2 would squeeze all parallel connections of a download into one TCP stream.
                    .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
                    .connectionPool(okhttp3.ConnectionPool(32, 5, TimeUnit.MINUTES))
                    .proxy(r.proxy)
                if (r.proxy.type() == Proxy.Type.HTTP && r.user.isNotEmpty()) {
                    val cred = Credentials.basic(r.user, r.pass)
                    b.proxyAuthenticator { _, resp ->
                        if (resp.request.header("Proxy-Authorization") != null) null
                        else resp.request.newBuilder().header("Proxy-Authorization", cred).build()
                    }
                }
                if (r.proxy.type() == Proxy.Type.SOCKS && r.user.isNotEmpty()) {
                    // Java's SOCKS client asks the default Authenticator for the user name and password.
                    val user = r.user
                    val pass = r.pass.toCharArray()
                    Authenticator.setDefault(object : Authenticator() {
                        override fun getPasswordAuthentication(): PasswordAuthentication? =
                            if (requestingHost == null || requestingProtocol?.startsWith("SOCKS", true) == true) PasswordAuthentication(user, pass) else null
                    })
                }
                val c = b.build()
                cached = c
                cachedKey = key
                return c
            }
        }

    fun invalidate() {
        cached = null
        cachedKey = ""
    }
}
