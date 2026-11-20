package com.banner.cleanbrowser.net

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.banner.cleanbrowser.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.torproject.jni.TorService
import java.io.File

/** Embedded Tor (Guardian Project tor-android). Free, run by volunteers; slow but anonymous. */
object Tor {
    enum class State { OFF, STARTING, ON, ERROR }

    var state by mutableStateOf(State.OFF)
        private set
    var detail by mutableStateOf("")
        private set

    @Volatile var socksPort: Int = -1
        private set
    @Volatile var httpPort: Int = -1
        private set

    private var service: TorService? = null
    private var bound = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Exit countries offered in the picker (ISO codes Tor understands). */
    val countries = listOf(
        "" to "Automatic", "us" to "United States", "ca" to "Canada", "gb" to "United Kingdom", "de" to "Germany",
        "nl" to "Netherlands", "fr" to "France", "ch" to "Switzerland", "se" to "Sweden", "no" to "Norway",
        "fi" to "Finland", "dk" to "Denmark", "at" to "Austria", "ro" to "Romania", "pl" to "Poland",
        "cz" to "Czechia", "es" to "Spain", "it" to "Italy", "lu" to "Luxembourg", "is" to "Iceland",
        "jp" to "Japan", "sg" to "Singapore", "au" to "Australia", "br" to "Brazil",
    )

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as? TorService.LocalBinder)?.service
            pollPorts()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            socksPort = -1
            httpPort = -1
            state = State.OFF
            Routing.apply()
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getStringExtra(TorService.EXTRA_STATUS)) {
                TorService.STATUS_ON -> pollPorts()
                TorService.STATUS_STARTING -> if (state != State.ON) state = State.STARTING
                TorService.STATUS_OFF -> { state = State.OFF; socksPort = -1; httpPort = -1; Routing.apply() }
            }
        }
    }

    fun writeTorrc(ctx: Context) {
        val cc = App.prefs.state.torExitCountry
        val lines = buildList {
            add("SocksPort auto")
            add("HTTPTunnelPort auto")
            add("AvoidDiskWrites 1")
            if (cc.isNotEmpty()) {
                add("ExitNodes {$cc}")
                add("StrictNodes 1")
            }
        }
        runCatching { TorService.getTorrc(ctx).writeText(lines.joinToString("\n") + "\n") }
    }

    fun start(ctx: Context) {
        if (bound) return
        writeTorrc(ctx)
        state = State.STARTING
        detail = "Connecting…"
        LocalBroadcastManager.getInstance(ctx).registerReceiver(receiver, IntentFilter(TorService.ACTION_STATUS))
        bound = ctx.applicationContext.bindService(Intent(ctx, TorService::class.java), conn, Context.BIND_AUTO_CREATE)
        if (!bound) { state = State.ERROR; detail = "Could not start Tor" }
    }

    fun stop(ctx: Context) {
        if (bound) {
            runCatching { ctx.applicationContext.unbindService(conn) }
            runCatching { LocalBroadcastManager.getInstance(ctx).unregisterReceiver(receiver) }
        }
        runCatching { ctx.stopService(Intent(ctx, TorService::class.java)) }
        bound = false
        service = null
        socksPort = -1
        httpPort = -1
        state = State.OFF
        detail = ""
    }

    fun restart(ctx: Context) {
        stop(ctx)
        scope.launch {
            delay(800)
            kotlinx.coroutines.withContext(Dispatchers.Main) { start(ctx) }
        }
    }

    /** Asks Tor for new circuits (new exit address). */
    fun newIdentity() {
        scope.launch { runCatching { service?.torControlConnection?.signal("NEWNYM") } }
    }

    private fun pollPorts() {
        scope.launch {
            repeat(120) {
                val s = service
                val sp = runCatching { s?.socksPort ?: -1 }.getOrDefault(-1)
                val hp = runCatching { s?.httpTunnelPort ?: -1 }.getOrDefault(-1)
                if (sp > 0) {
                    socksPort = sp
                    httpPort = hp
                    state = State.ON
                    detail = "Connected"
                    Net.invalidate()
                    kotlinx.coroutines.withContext(Dispatchers.Main) { Routing.apply() }
                    return@launch
                }
                delay(500)
            }
            state = State.ERROR
            detail = "Tor did not start"
        }
    }

    @Suppress("unused")
    private fun dataDir(ctx: Context) = File(ctx.filesDir, "tor")
}
