package com.banner.cleanbrowser.net

import android.content.Context
import android.net.VpnService
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.banner.cleanbrowser.App
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A saved WireGuard server (the user's own config file). */
data class WgServer(val id: String, val name: String, val location: String, val config: String)

/**
 * WireGuard tunnels through Android's VPN service, limited to this app only
 * (IncludedApplications = our package), so other apps are not affected.
 */
object WireGuard {
    enum class State { OFF, CONNECTING, ON, ERROR }

    var state by mutableStateOf(State.OFF)
        private set
    var detail by mutableStateOf("")
        private set
    val servers = mutableStateListOf<WgServer>()

    val connected: Boolean get() = state == State.ON

    private var backend: GoBackend? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tunnel = object : Tunnel {
        override fun getName() = "cleanbrowser"
        override fun onStateChange(newState: Tunnel.State) {
            state = if (newState == Tunnel.State.UP) State.ON else State.OFF
            Routing.apply()
        }
    }

    private fun file(ctx: Context) = File(ctx.filesDir, "wireguard.json")

    fun load(ctx: Context) {
        val f = file(ctx)
        servers.clear()
        if (!f.exists()) return
        runCatching {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                servers += WgServer(o.getString("id"), o.getString("name"), o.optString("location"), o.getString("config"))
            }
        }
    }

    private fun save(ctx: Context) {
        val arr = JSONArray()
        servers.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("location", it.location).put("config", it.config)) }
        file(ctx).writeText(arr.toString())
    }

    /** Validates and stores a config. Returns an error message or null. */
    fun add(ctx: Context, name: String, location: String, text: String): String? {
        val err = runCatching { Config.parse(text.byteInputStream()) }.exceptionOrNull()
        if (err != null) return "Not a valid WireGuard config"
        val endpoint = runCatching { Config.parse(text.byteInputStream()).peers.firstOrNull()?.endpoint?.orElse(null)?.host }.getOrNull()
        servers += WgServer(UUID.randomUUID().toString(), name.ifBlank { endpoint ?: "Server ${servers.size + 1}" }, location, text)
        save(ctx)
        return null
    }

    fun remove(ctx: Context, s: WgServer) {
        if (App.prefs.state.wgActive == s.id) disconnect()
        servers.remove(s)
        save(ctx)
    }

    /** Only this app goes through the tunnel. */
    private fun appOnly(ctx: Context, text: String): String {
        val out = StringBuilder()
        var inInterface = false
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.startsWith("[")) {
                if (inInterface) out.append("IncludedApplications = ").append(ctx.packageName).append('\n')
                inInterface = line.equals("[Interface]", true)
            }
            val key = line.substringBefore('=').trim().lowercase()
            if (key == "includedapplications" || key == "excludedapplications") return@forEach
            out.append(raw).append('\n')
        }
        if (inInterface) out.append("IncludedApplications = ").append(ctx.packageName).append('\n')
        return out.toString()
    }

    /** True when Android first needs the user's OK for a VPN (show [VpnService.prepare] intent). */
    fun needsConsent(ctx: Context): Boolean = VpnService.prepare(ctx) != null

    fun connect(ctx: Context, s: WgServer) {
        state = State.CONNECTING
        detail = "Connecting to ${s.name}…"
        scope.launch {
            try {
                val b = backend ?: GoBackend(ctx.applicationContext).also { backend = it }
                runCatching { b.setState(tunnel, Tunnel.State.DOWN, null) }
                val cfg = Config.parse(appOnly(ctx, s.config).byteInputStream())
                b.setState(tunnel, Tunnel.State.UP, cfg)
                state = State.ON
                detail = s.name
            } catch (e: Throwable) {
                state = State.ERROR
                detail = e.message ?: "Could not connect"
            }
            withContext(Dispatchers.Main) { Routing.apply() }
        }
    }

    fun disconnect() {
        scope.launch {
            runCatching { backend?.setState(tunnel, Tunnel.State.DOWN, null) }
            state = State.OFF
            detail = ""
            withContext(Dispatchers.Main) { Routing.apply() }
        }
    }

    fun stats(): Pair<Long, Long>? = runCatching {
        val st = backend?.getStatistics(tunnel) ?: return null
        st.totalRx() to st.totalTx()
    }.getOrNull()
}
