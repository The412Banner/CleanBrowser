package com.banner.cleanbrowser.download

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Measures the download engine itself: downloads a public test file (Hetzner, Ashburn) for a few
 * seconds per run with different connection counts and folders, then deletes it.
 */
object SpeedTest {
    data class Result(val label: String, val bytes: Long, val seconds: Double, val error: String?) {
        val mbPerSec: Double get() = if (seconds > 0) bytes / seconds / 1_048_576 else 0.0
        val mbit: Double get() = if (seconds > 0) bytes * 8 / seconds / 1e6 else 0.0
    }

    const val URL = "https://ash-speed.hetzner.com/1GB.bin"
    private var seconds = 8
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var running by mutableStateOf(false)
        private set
    val results = mutableStateListOf<Result>()

    /** Quick test from the settings button; detailed = 2 passes over 1/2/4/8 connections and three targets. */
    fun start(ctx: Context, detailed: Boolean = false) {
        if (running) return
        running = true
        results.clear()
        // Count as an active transfer so Android keeps the app awake (job / foreground service).
        Downloads.ensureRunning()
        scope.launch {
            val app = ctx.applicationContext
            // Leftovers from the first version of the test (empty temp files).
            app.cacheDir.listFiles { f -> f.name.startsWith("cbnull") && f.name.endsWith(".tmp") }?.forEach { it.delete() }
            val dl = Downloads.defaultFolder()
            val own = app.getExternalFilesDir(null)?.absolutePath ?: app.filesDir.absolutePath
            val sd = app.getExternalFilesDirs(null).getOrNull(1)?.absolutePath
            val runs = buildList {
                if (detailed) {
                    seconds = 6
                    repeat(2) { pass ->
                        for (n in listOf(1, 2, 4, 8)) {
                            add(Triple("P${pass + 1} Download · $n", dl, n))
                            add(Triple("P${pass + 1} App folder · $n", own, n))
                            add(Triple("P${pass + 1} No storage · $n", DISCARD, n))
                        }
                    }
                } else {
                    seconds = 8
                    for (n in listOf(1, 4, 8)) add(Triple("Download folder · $n", dl, n))
                    add(Triple("App folder · 4", own, 4))
                    add(Triple("No storage · 4", DISCARD, 4))
                    if (sd != null) add(Triple("SD card · 4", sd, 4))
                }
            }
            try {
                for ((label, folder, n) in runs) {
                    val r = runOne(app, label, folder, n)
                    results += r
                    Log.i("CleanBrowserSpeed", "$label: ${r.bytes} bytes in ${"%.1f".format(r.seconds)} s = ${"%.1f".format(r.mbPerSec)} MB/s (${"%.0f".format(r.mbit)} Mbps)${r.error?.let { " error=$it" } ?: ""}")
                }
                Log.i("CleanBrowserSpeed", "done")
            } finally {
                running = false
                Downloads.ensureRunning()
            }
        }
    }

    private suspend fun runOne(ctx: Context, label: String, folder: String, n: Int): Result {
        val item = DlItem(
            id = -(System.nanoTime() and 0xFFFFFF), kind = DlKind.HTTP, url = URL, name = "cb-speedtest.bin", mime = "",
            total = -1, done = 0, status = DlStatus.QUEUED, error = null, created = System.currentTimeMillis(), finished = 0,
            etag = null, lastModified = null, userAgent = "CleanBrowser-speedtest", referer = "", cookies = null,
            folder = folder, target = null, parts = null, connections = n, checksumType = null, checksumValue = null,
            checksumOk = -1, isPrivate = true, infoHash = null, sequential = false, uploaded = 0,
        )
        val callbacks = object : HttpEngine.Callbacks {
            override fun persist(item: DlItem) {}
            override suspend fun waitForNetwork(item: DlItem) {}
            override suspend fun throttle(bytes: Int) {}
            override val connections: Int = n
            override val maxRetries: Int = 0
            override val preallocate: Boolean = false
        }
        val start = System.nanoTime()
        var error: String? = null
        val job = scope.async { runCatching { HttpEngine(ctx, item, callbacks).run() }.exceptionOrNull() }
        var waited = 0
        while (waited < seconds * 10 && job.isActive) { delay(100); waited++ }
        if (job.isActive) job.cancel() else job.await()?.let { if (it !is kotlinx.coroutines.CancellationException) error = it.message }
        runCatching { job.join() }
        val secs = (System.nanoTime() - start) / 1e9
        val bytes = item.done
        item.target?.takeIf { it.isNotEmpty() && folder != DISCARD }?.let { t -> runCatching { File(t).delete() } }
        return Result(label, bytes, secs, error)
    }
}
