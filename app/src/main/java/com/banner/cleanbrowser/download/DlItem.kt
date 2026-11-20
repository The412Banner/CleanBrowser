package com.banner.cleanbrowser.download

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class DlKind { HTTP, TORRENT }

enum class DlStatus {
    QUEUED, CONNECTING, RUNNING, WAITING_NETWORK, PAUSED, CHECKING, METADATA, SEEDING, DONE, FAILED;

    val active get() = this == QUEUED || this == CONNECTING || this == RUNNING || this == WAITING_NETWORK || this == CHECKING || this == METADATA
    val finished get() = this == DONE || this == SEEDING
}

/** One download (HTTP or torrent). Persisted fields + live Compose state. */
class DlItem(
    val id: Long,
    val kind: DlKind,
    var url: String,
    name: String,
    var mime: String,
    total: Long,
    done: Long,
    status: DlStatus,
    var error: String?,
    val created: Long,
    var finished: Long,
    var etag: String?,
    var lastModified: String?,
    var userAgent: String,
    var referer: String,
    var cookies: String?,
    /** Folder: a file-system path or a SAF tree URI (content://). */
    var folder: String,
    /** Final file path or document URI once known. */
    var target: String?,
    var parts: String?,
    var connections: Int,
    var checksumType: String?,
    var checksumValue: String?,
    checksumOk: Int,
    val isPrivate: Boolean,
    var infoHash: String?,
    sequential: Boolean,
    var uploaded: Long,
) {
    var name by mutableStateOf(name)
    var total by mutableLongStateOf(total)
    var done by mutableLongStateOf(done)
    var status by mutableStateOf(status)
    var speed by mutableLongStateOf(0L)
    var upSpeed by mutableLongStateOf(0L)
    var peers by mutableIntStateOf(0)
    var seeds by mutableIntStateOf(0)
    var ratio by mutableFloatStateOf(0f)
    var checksumOk by mutableIntStateOf(checksumOk)
    var sequential by mutableStateOf(sequential)
    var liveConnections by mutableIntStateOf(0)
    var errorText by mutableStateOf(error)

    val progress: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
}

class DlStore(context: Context) : SQLiteOpenHelper(context, "downloads.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE downloads (
                id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, url TEXT NOT NULL, name TEXT NOT NULL,
                mime TEXT NOT NULL DEFAULT '', total INTEGER NOT NULL DEFAULT -1, done INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL, error TEXT, created INTEGER NOT NULL, finished INTEGER NOT NULL DEFAULT 0,
                etag TEXT, last_modified TEXT, user_agent TEXT NOT NULL DEFAULT '', referer TEXT NOT NULL DEFAULT '',
                cookies TEXT, folder TEXT NOT NULL, target TEXT, parts TEXT, connections INTEGER NOT NULL DEFAULT 1,
                checksum_type TEXT, checksum_value TEXT, checksum_ok INTEGER NOT NULL DEFAULT -1,
                private INTEGER NOT NULL DEFAULT 0, info_hash TEXT, sequential INTEGER NOT NULL DEFAULT 0,
                uploaded INTEGER NOT NULL DEFAULT 0)""",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    private fun values(d: DlItem) = ContentValues().apply {
        put("kind", d.kind.name); put("url", d.url); put("name", d.name); put("mime", d.mime)
        put("total", d.total); put("done", d.done); put("status", d.status.name); put("error", d.errorText)
        put("created", d.created); put("finished", d.finished); put("etag", d.etag); put("last_modified", d.lastModified)
        put("user_agent", d.userAgent); put("referer", d.referer)
        put("cookies", if (d.isPrivate) null else d.cookies)
        put("folder", d.folder); put("target", d.target); put("parts", d.parts); put("connections", d.connections)
        put("checksum_type", d.checksumType); put("checksum_value", d.checksumValue); put("checksum_ok", d.checksumOk)
        put("private", if (d.isPrivate) 1 else 0); put("info_hash", d.infoHash); put("sequential", if (d.sequential) 1 else 0)
        put("uploaded", d.uploaded)
    }

    fun insert(d: DlItem): Long = writableDatabase.insert("downloads", null, values(d))

    fun update(d: DlItem) {
        writableDatabase.update("downloads", values(d), "id = ?", arrayOf(d.id.toString()))
    }

    fun delete(id: Long) {
        writableDatabase.delete("downloads", "id = ?", arrayOf(id.toString()))
    }

    fun all(): List<DlItem> = readableDatabase.rawQuery("SELECT * FROM downloads ORDER BY created DESC", null).use { c ->
        buildList { while (c.moveToNext()) add(read(c)) }
    }

    private fun read(c: Cursor): DlItem {
        fun s(n: String) = c.getString(c.getColumnIndexOrThrow(n))
        fun l(n: String) = c.getLong(c.getColumnIndexOrThrow(n))
        fun i(n: String) = c.getInt(c.getColumnIndexOrThrow(n))
        return DlItem(
            id = l("id"), kind = runCatching { DlKind.valueOf(s("kind")) }.getOrDefault(DlKind.HTTP),
            url = s("url"), name = s("name"), mime = s("mime") ?: "", total = l("total"), done = l("done"),
            status = runCatching { DlStatus.valueOf(s("status")) }.getOrDefault(DlStatus.PAUSED),
            error = s("error"), created = l("created"), finished = l("finished"), etag = s("etag"),
            lastModified = s("last_modified"), userAgent = s("user_agent") ?: "", referer = s("referer") ?: "",
            cookies = s("cookies"), folder = s("folder"), target = s("target"), parts = s("parts"),
            connections = i("connections"), checksumType = s("checksum_type"), checksumValue = s("checksum_value"),
            checksumOk = i("checksum_ok"), isPrivate = i("private") == 1, infoHash = s("info_hash"),
            sequential = i("sequential") == 1, uploaded = l("uploaded"),
        )
    }
}
