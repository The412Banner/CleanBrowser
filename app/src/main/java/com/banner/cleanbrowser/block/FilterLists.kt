package com.banner.cleanbrowser.block

import android.content.Context
import com.banner.cleanbrowser.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class FilterList(val id: String, val name: String, val url: String, val hosts: Boolean)

/** Downloads the chosen filter lists (never bundled) and builds the [Blocker]. */
object FilterLists {
    val all = listOf(
        FilterList("easylist", "EasyList", "https://easylist.to/easylist/easylist.txt", false),
        FilterList("easyprivacy", "EasyPrivacy", "https://easylist.to/easylist/easyprivacy.txt", false),
        FilterList("ubo-filters", "uBlock filters", "https://ublockorigin.github.io/uAssets/filters/filters.txt", false),
        FilterList("ubo-privacy", "uBlock privacy", "https://ublockorigin.github.io/uAssets/filters/privacy.txt", false),
        FilterList("annoyances", "Fanboy Annoyances", "https://secure.fanboy.co.nz/fanboy-annoyance.txt", false),
        FilterList("pgl", "Peter Lowe's list",
            "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext", true),
        FilterList("stevenblack", "StevenBlack hosts", "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts", true),
    )

    private const val MAX_AGE_MS = 4L * 24 * 60 * 60 * 1000

    @Volatile var blocker: Blocker = Blocker.EMPTY
        private set

    var ruleCount by mutableStateOf(0)
        private set
    var updating by mutableStateOf(false)
        private set
    var lastUpdate by mutableStateOf(0L)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    private fun dir(ctx: Context) = File(ctx.filesDir, "filters").apply { mkdirs() }
    private fun file(ctx: Context, l: FilterList) = File(dir(ctx), l.id + ".txt")

    /** Builds from the files on disk, then refreshes lists that are missing or old. */
    fun start(ctx: Context) {
        scope.launch {
            rebuild(ctx)
            refresh(ctx, force = false)
        }
    }

    fun refreshNow(ctx: Context) {
        scope.launch { refresh(ctx, force = true) }
    }

    fun onSelectionChanged(ctx: Context) {
        scope.launch {
            rebuild(ctx)
            refresh(ctx, force = false)
        }
    }

    private suspend fun refresh(ctx: Context, force: Boolean) {
        val chosen = chosen()
        val stale = chosen.filter { force || !file(ctx, it).exists() || System.currentTimeMillis() - file(ctx, it).lastModified() > MAX_AGE_MS }
        if (stale.isEmpty()) return
        updating = true
        var changed = false
        try {
            for (l in stale) if (download(ctx, l)) changed = true
        } finally {
            updating = false
        }
        if (changed) rebuild(ctx)
    }

    private fun chosen(): List<FilterList> {
        val ids = App.prefs.state.filterLists
        return all.filter { it.id in ids }
    }

    private fun download(ctx: Context, l: FilterList): Boolean {
        val target = file(ctx, l)
        val tmp = File(target.path + ".part")
        return try {
            val c = URL(l.url).openConnection() as HttpURLConnection
            c.connectTimeout = 20_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = true
            if (target.exists()) c.ifModifiedSince = target.lastModified()
            c.setRequestProperty("User-Agent", "CleanBrowser")
            when (c.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> { target.setLastModified(System.currentTimeMillis()); false }
                HttpURLConnection.HTTP_OK -> {
                    c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    if (tmp.length() > 0 && tmp.renameTo(target)) {
                        target.setLastModified(System.currentTimeMillis()); true
                    } else false
                }
                else -> false
            }
        } catch (_: Exception) {
            tmp.delete()
            false
        }
    }

    suspend fun rebuild(ctx: Context) = lock.withLock {
        val b = Blocker.Builder()
        var newest = 0L
        for (l in chosen()) {
            val f = file(ctx, l)
            if (!f.exists()) continue
            newest = maxOf(newest, f.lastModified())
            try {
                f.bufferedReader().useLines { lines ->
                    if (l.hosts) lines.forEach(b::addHostsLine) else lines.forEach(b::addFilterLine)
                }
            } catch (_: Exception) {
            }
        }
        val built = b.build()
        blocker = built
        ruleCount = built.ruleCount
        lastUpdate = newest
    }
}
