package com.banner.cleanbrowser.core

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Bookmark(val id: Long, val url: String, val title: String, val created: Long)
data class HistoryItem(val id: Long, val url: String, val title: String, val visited: Long)
data class SiteSettings(val host: String, val javascript: Int = -1, val blocking: Int = -1, val desktop: Int = -1)

/** Bookmarks, history and per-site settings in one small SQLite file. */
class Db(context: Context) : SQLiteOpenHelper(context, "browser.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE bookmarks (id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT NOT NULL UNIQUE, title TEXT NOT NULL, created INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE history (id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT NOT NULL, title TEXT NOT NULL, visited INTEGER NOT NULL, visits INTEGER NOT NULL DEFAULT 1)")
        db.execSQL("CREATE INDEX history_visited ON history(visited)")
        db.execSQL("CREATE INDEX history_url ON history(url)")
        db.execSQL("CREATE TABLE sites (host TEXT PRIMARY KEY, javascript INTEGER NOT NULL DEFAULT -1, blocking INTEGER NOT NULL DEFAULT -1, desktop INTEGER NOT NULL DEFAULT -1)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE history ADD COLUMN visits INTEGER NOT NULL DEFAULT 1")
    }

    // Bookmarks
    fun addBookmark(url: String, title: String) {
        val v = ContentValues().apply {
            put("url", url); put("title", title.ifBlank { url }); put("created", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("bookmarks", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun removeBookmark(url: String) {
        writableDatabase.delete("bookmarks", "url = ?", arrayOf(url))
    }

    fun renameBookmark(id: Long, title: String, url: String) {
        val v = ContentValues().apply { put("title", title); put("url", url) }
        writableDatabase.update("bookmarks", v, "id = ?", arrayOf(id.toString()))
    }

    fun isBookmarked(url: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM bookmarks WHERE url = ? LIMIT 1", arrayOf(url)).use { it.moveToFirst() }

    fun bookmarks(filter: String = ""): List<Bookmark> {
        val like = "%$filter%"
        return readableDatabase.rawQuery(
            "SELECT id, url, title, created FROM bookmarks WHERE url LIKE ? OR title LIKE ? ORDER BY created DESC",
            arrayOf(like, like),
        ).use { c ->
            buildList { while (c.moveToNext()) add(Bookmark(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3))) }
        }
    }

    // History
    fun addHistory(url: String, title: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val visits = db.rawQuery("SELECT COALESCE(SUM(visits), 0) FROM history WHERE url = ?", arrayOf(url)).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
            db.delete("history", "url = ?", arrayOf(url))
            val v = ContentValues().apply {
                put("url", url); put("title", title.ifBlank { url }); put("visited", System.currentTimeMillis())
                put("visits", visits + 1)
            }
            db.insert("history", null, v)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun updateHistoryTitle(url: String, title: String) {
        if (title.isBlank()) return
        val v = ContentValues().apply { put("title", title) }
        writableDatabase.update("history", v, "url = ?", arrayOf(url))
    }

    fun history(filter: String = "", limit: Int = 500): List<HistoryItem> {
        val like = "%$filter%"
        return readableDatabase.rawQuery(
            "SELECT id, url, title, visited FROM history WHERE url LIKE ? OR title LIKE ? ORDER BY visited DESC LIMIT $limit",
            arrayOf(like, like),
        ).use { c ->
            buildList { while (c.moveToNext()) add(HistoryItem(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3))) }
        }
    }

    fun removeHistory(id: Long) {
        writableDatabase.delete("history", "id = ?", arrayOf(id.toString()))
    }

    fun clearHistory() {
        writableDatabase.delete("history", null, null)
    }

    data class Frequent(val host: String, val url: String, val title: String, val visits: Int)

    /** Most visited sites, grouped by host (needs at least two visits). */
    fun frequent(hidden: Set<String>, limit: Int = 8): List<Frequent> {
        data class Acc(var visits: Int, var url: String, var title: String, var best: Int)
        val map = LinkedHashMap<String, Acc>()
        readableDatabase.rawQuery("SELECT url, title, visits FROM history ORDER BY visits DESC, visited DESC LIMIT 500", null).use { c ->
            while (c.moveToNext()) {
                val url = c.getString(0); val title = c.getString(1); val n = c.getInt(2)
                val host = Urls.host(url).removePrefix("www.")
                if (host.isEmpty() || host in hidden) continue
                val a = map.getOrPut(host) { Acc(0, url, title, -1) }
                a.visits += n
                if (n > a.best) { a.best = n; a.url = url.substringBefore("://") + "://" + Urls.host(url) + "/"; a.title = title }
            }
        }
        return map.filter { it.value.visits >= 2 }.entries.sortedByDescending { it.value.visits }.take(limit)
            .map { (h, a) -> Frequent(h, a.url, a.title, a.visits) }
    }

    /** Hosts known from history and bookmarks, most visited first, for address autofill. */
    fun knownHosts(limit: Int = 400): List<String> {
        val out = LinkedHashSet<String>()
        readableDatabase.rawQuery("SELECT url FROM history ORDER BY visits DESC, visited DESC LIMIT $limit", null).use { c ->
            while (c.moveToNext()) Urls.host(c.getString(0)).removePrefix("www.").takeIf { it.isNotEmpty() }?.let(out::add)
        }
        readableDatabase.rawQuery("SELECT url FROM bookmarks", null).use { c ->
            while (c.moveToNext()) Urls.host(c.getString(0)).removePrefix("www.").takeIf { it.isNotEmpty() }?.let(out::add)
        }
        return out.toList()
    }

    fun removeHistoryHost(host: String) {
        writableDatabase.delete(
            "history", "url LIKE ? OR url LIKE ? OR url LIKE ? OR url LIKE ?",
            arrayOf("%://$host/%", "%://www.$host/%", "%://$host", "%://www.$host"),
        )
    }

    fun removeHistoryUrl(url: String) {
        writableDatabase.delete("history", "url = ?", arrayOf(url))
    }

    fun resetVisits() {
        writableDatabase.execSQL("UPDATE history SET visits = 0")
    }

    /** Address-bar suggestions from bookmarks and history. */
    fun suggest(text: String, limit: Int = 6): List<Pair<String, String>> {
        if (text.isBlank()) return emptyList()
        val like = "%$text%"
        val out = LinkedHashMap<String, String>()
        readableDatabase.rawQuery(
            "SELECT url, title FROM bookmarks WHERE url LIKE ? OR title LIKE ? LIMIT $limit", arrayOf(like, like),
        ).use { c -> while (c.moveToNext()) out.putIfAbsent(c.getString(0), c.getString(1)) }
        readableDatabase.rawQuery(
            "SELECT url, title FROM history WHERE url LIKE ? OR title LIKE ? ORDER BY visited DESC LIMIT $limit", arrayOf(like, like),
        ).use { c -> while (c.moveToNext() && out.size < limit) out.putIfAbsent(c.getString(0), c.getString(1)) }
        return out.entries.take(limit).map { it.key to it.value }
    }

    // Per-site settings
    fun site(host: String): SiteSettings =
        readableDatabase.rawQuery("SELECT javascript, blocking, desktop FROM sites WHERE host = ?", arrayOf(host)).use { c ->
            if (c.moveToFirst()) SiteSettings(host, c.getInt(0), c.getInt(1), c.getInt(2)) else SiteSettings(host)
        }

    fun saveSite(s: SiteSettings) {
        if (s.javascript == -1 && s.blocking == -1 && s.desktop == -1) {
            writableDatabase.delete("sites", "host = ?", arrayOf(s.host))
            return
        }
        val v = ContentValues().apply {
            put("host", s.host); put("javascript", s.javascript); put("blocking", s.blocking); put("desktop", s.desktop)
        }
        writableDatabase.insertWithOnConflict("sites", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun sites(): List<SiteSettings> =
        readableDatabase.rawQuery("SELECT host, javascript, blocking, desktop FROM sites ORDER BY host", null).use { c ->
            buildList { while (c.moveToNext()) add(SiteSettings(c.getString(0), c.getInt(1), c.getInt(2), c.getInt(3))) }
        }

    fun clearSites() {
        writableDatabase.delete("sites", null, null)
    }
}
