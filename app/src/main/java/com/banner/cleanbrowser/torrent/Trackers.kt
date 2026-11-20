package com.banner.cleanbrowser.torrent

import com.banner.cleanbrowser.App
import com.banner.cleanbrowser.net.Net
import okhttp3.Request
import java.io.File

/** Public tracker list (ngosang/trackerslist), downloaded and refreshed daily, never bundled. */
object Trackers {
    private const val URL = "https://raw.githubusercontent.com/ngosang/trackerslist/master/trackers_best.txt"
    private const val MAX_AGE = 24 * 60 * 60 * 1000L

    private fun file() = File(App.instance.filesDir, "trackers.txt")

    /** Call off the main thread. Returns the cached list, refreshed when older than a day. */
    fun list(): List<String> {
        val f = file()
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > MAX_AGE) {
            runCatching {
                Net.client.newCall(Request.Builder().url(URL).header("User-Agent", "CleanBrowser").build()).execute().use { r ->
                    val body = r.body?.string()
                    if (r.isSuccessful && !body.isNullOrBlank()) f.writeText(body)
                }
            }
        }
        return runCatching { f.readLines() }.getOrDefault(emptyList())
            .map { it.trim() }
            .filter { it.startsWith("udp://") || it.startsWith("http://") || it.startsWith("https://") || it.startsWith("wss://") }
            .distinct()
    }
}
