package com.banner.cleanbrowser.core

import com.banner.cleanbrowser.App

/** Address autofill and site suggestions from history, bookmarks and the built-in site list. */
object Suggest {
    @Volatile private var known: List<String> = emptyList()
    @Volatile private var loadedAt = 0L

    /** Refreshes the host list from the database (call off the main thread). */
    fun refresh() {
        if (System.currentTimeMillis() - loadedAt < 5_000) return
        known = runCatching { App.db.knownHosts() }.getOrDefault(emptyList())
        loadedAt = System.currentTimeMillis()
    }

    private fun candidates(): Sequence<String> {
        val hidden = App.prefs.state.hiddenSites
        return (known.asSequence() + TopSites.list.asSequence()).distinct().filter { it !in hidden }
    }

    /** Full host that starts with what was typed, for inline autofill. */
    fun complete(typed: String): String? {
        val t = typed.lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.")
        if (t.length < 2 || t.contains(' ') || t.contains('/')) return null
        val hit = candidates().firstOrNull { it.startsWith(t) && it.length > t.length } ?: return null
        return typed + hit.substring(t.length)
    }

    /** Site addresses matching the text, for the list under the address bar. */
    fun sites(text: String, limit: Int = 4): List<String> {
        val t = text.lowercase().trim().removePrefix("https://").removePrefix("http://").removePrefix("www.")
        if (t.length < 2 || t.contains(' ')) return emptyList()
        val starts = candidates().filter { it.startsWith(t) }.take(limit).toList()
        if (starts.size >= limit) return starts
        val contains = candidates().filter { !it.startsWith(t) && it.contains(t) }.take(limit - starts.size).toList()
        return starts + contains
    }

    fun invalidate() {
        loadedAt = 0
    }
}
