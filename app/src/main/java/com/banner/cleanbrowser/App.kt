package com.banner.cleanbrowser

import android.app.Application
import com.banner.cleanbrowser.block.FilterLists
import com.banner.cleanbrowser.core.Db
import com.banner.cleanbrowser.core.Prefs
import com.banner.cleanbrowser.core.SiteSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** In-memory copy of per-site settings (read on the network thread). */
class SiteCache(private val db: Db) {
    private val map = ConcurrentHashMap<String, SiteSettings>()

    fun load() {
        map.clear()
        db.sites().forEach { map[it.host] = it }
    }

    fun get(host: String): SiteSettings = map[host] ?: SiteSettings(host)

    fun save(s: SiteSettings) {
        if (s.javascript == -1 && s.blocking == -1 && s.desktop == -1) map.remove(s.host) else map[s.host] = s
        App.io { db.saveSite(s) }
    }

    fun all(): List<SiteSettings> = map.values.sortedBy { it.host }

    fun clear() {
        map.clear()
        App.io { db.clearSites() }
    }
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        db = Db(this)
        sites = SiteCache(db)
        io { sites.load() }
        FilterLists.start(this)
        com.banner.cleanbrowser.net.WireGuard.load(this)
        com.banner.cleanbrowser.download.Downloads.init(this)
        com.banner.cleanbrowser.net.Routing.onModeChanged()
    }

    companion object {
        lateinit var instance: App
            private set
        lateinit var prefs: Prefs
            private set
        lateinit var db: Db
            private set
        lateinit var sites: SiteCache
            private set

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun io(block: suspend () -> Unit) {
            scope.launch { runCatching { block() } }
        }
    }
}
