package com.banner.cleanbrowser.download

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import com.banner.cleanbrowser.net.Net
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/** A byte range of the file handled by one connection. [end] is inclusive; -1 = until the end. */
private const val WRITE_BATCH = 1024 * 1024
const val DISCARD = "discard:"

class Segment(val start: Long, @Volatile var end: Long, done: Long) {
    val done = AtomicLong(done)
    @Volatile var working = false
    val pos get() = start + done.get()
    val remaining get() = if (end < 0) Long.MAX_VALUE else end - pos + 1
    val complete get() = end >= 0 && pos > end
}

class ServerChanged : IOException("The file changed on the server")
class NoSpace(msg: String) : IOException(msg)
class HttpError(val code: Int) : IOException("Server error $code")

/** Destination of an HTTP download: a ".part" file that is renamed when complete. */
interface Sink {
    val channel: FileChannel
    val fd: java.io.FileDescriptor
    /** Positional write; returns bytes written. */
    fun write(b: ByteBuffer, pos: Long): Int = channel.write(b, pos)
    fun truncate(size: Long) { channel.truncate(size) }
    fun force() { channel.force(true) }
    /** Free bytes where the file is written, or -1 when unknown. */
    fun freeSpace(): Long
    fun openRead(): java.io.InputStream
    fun finish(finalName: String): String
    fun delete()
    fun close()
}

private class FileSink(private val part: File) : Sink {
    private val dir: File = part.parentFile ?: File("/")
    private val raf = RandomAccessFile(part.apply { parentFile?.mkdirs() }, "rw")
    override val channel: FileChannel = raf.channel
    override val fd: java.io.FileDescriptor get() = raf.fd
    override fun freeSpace(): Long = runCatching { android.os.StatFs(dir.absolutePath).availableBytes }.getOrDefault(-1L)
    override fun openRead(): java.io.InputStream = java.io.FileInputStream(part)
    override fun finish(finalName: String): String {
        close()
        val target = uniqueFile(dir, finalName)
        if (!part.renameTo(target)) throw IOException("Could not rename the file")
        return target.absolutePath
    }
    override fun delete() { close(); part.delete() }
    override fun close() { runCatching { raf.close() } }
}

private class DocSink(private val ctx: Context, private val doc: Uri) : Sink {
    private val pfd: ParcelFileDescriptor = ctx.contentResolver.openFileDescriptor(doc, "rw") ?: throw IOException("Cannot open the folder")
    private val fos = FileOutputStream(pfd.fileDescriptor)
    override val channel: FileChannel = fos.channel
    override val fd: java.io.FileDescriptor get() = pfd.fileDescriptor
    override fun freeSpace(): Long = runCatching { android.system.Os.fstatvfs(pfd.fileDescriptor).let { it.f_bavail * it.f_bsize } }.getOrDefault(-1L)
    override fun openRead(): java.io.InputStream = ctx.contentResolver.openInputStream(doc) ?: throw IOException("Cannot read the file")
    override fun finish(finalName: String): String {
        close()
        val renamed = runCatching { DocumentsContract.renameDocument(ctx.contentResolver, doc, finalName) }.getOrNull()
        return (renamed ?: doc).toString()
    }
    override fun delete() { close(); runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, doc) } }
    override fun close() { runCatching { fos.close() }; runCatching { pfd.close() } }
}

/** Speed test only: accepts data and stores nothing (measures network + engine without storage). */
private class NullSink : Sink {
    // No file at all: every operation that would touch storage is a no-op.
    override val channel: FileChannel get() = throw UnsupportedOperationException("no file")
    override val fd: java.io.FileDescriptor get() = throw UnsupportedOperationException("no file")
    override fun write(b: ByteBuffer, pos: Long): Int { val n = b.remaining(); b.position(b.limit()); return n }
    override fun truncate(size: Long) {}
    override fun force() {}
    override fun freeSpace(): Long = -1
    override fun openRead(): java.io.InputStream = java.io.ByteArrayInputStream(ByteArray(0))
    override fun finish(finalName: String): String = ""
    override fun delete() {}
    override fun close() {}
}

fun uniqueFile(dir: File, name: String): File {
    var f = File(dir, name)
    if (!f.exists()) return f
    val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
    val base = name.substring(0, dot)
    val ext = name.substring(dot)
    var i = 1
    while (f.exists()) { f = File(dir, "$base ($i)$ext"); i++ }
    return f
}

/** Downloads one HTTP item with several connections, resume, change detection and retries. */
class HttpEngine(private val ctx: Context, private val item: DlItem, private val callbacks: Callbacks) {
    interface Callbacks {
        fun persist(item: DlItem)
        suspend fun waitForNetwork(item: DlItem)
        suspend fun throttle(bytes: Int)
        val connections: Int
        val maxRetries: Int
        val preallocate: Boolean
    }

    private val segments = ArrayList<Segment>()
    private val lock = Any()
    @Volatile private var lastPersist = 0L

    suspend fun run() = withContext(Dispatchers.IO) {
        var attempt = 0
        while (true) {
            ensureActive()
            try {
                download()
                return@withContext
            } catch (e: CancellationException) {
                saveParts()
                callbacks.persist(item)
                throw e
            } catch (e: NoSpace) {
                throw e
            } catch (e: ServerChanged) {
                // Start again from zero; the old bytes belong to a different file.
                synchronized(lock) { segments.clear() }
                item.parts = null
                item.done = 0
                item.etag = null
                item.lastModified = null
                attempt++
            } catch (e: HttpError) {
                if (e.code in 400..499 && e.code != 408 && e.code != 429) throw e
                attempt++
                if (attempt > callbacks.maxRetries) throw e
                backoff(attempt)
            } catch (e: IOException) {
                saveParts()
                callbacks.persist(item)
                attempt++
                if (attempt > callbacks.maxRetries) throw e
                item.status = DlStatus.WAITING_NETWORK
                callbacks.waitForNetwork(item)
                backoff(attempt)
            }
        }
    }

    private suspend fun backoff(attempt: Int) {
        delay((1000L shl (attempt - 1).coerceAtMost(5)).coerceAtMost(30_000L))
    }

    private fun request(from: Long, to: Long, conditional: Boolean): Request {
        val b = Request.Builder().url(item.url)
        if (item.userAgent.isNotEmpty()) b.header("User-Agent", item.userAgent)
        if (item.referer.isNotEmpty() && (item.referer.startsWith("http"))) b.header("Referer", item.referer)
        item.cookies?.takeIf { it.isNotEmpty() }?.let { b.header("Cookie", it) }
        b.header("Accept-Encoding", "identity")
        if (from > 0 || to >= 0) b.header("Range", "bytes=$from-" + if (to >= 0) "$to" else "")
        if (conditional) {
            val v = item.etag?.takeIf { !it.startsWith("W/") } ?: item.lastModified
            if (v != null) b.header("If-Range", v)
        }
        return b.build()
    }

    private suspend fun download() = coroutineScope {
        item.status = DlStatus.CONNECTING
        val sink = openSink()
        try {
            if (segments.isEmpty()) loadOrPlan(sink)
            prepare(sink)
            item.status = DlStatus.RUNNING
            val workers = ArrayList<kotlinx.coroutines.Job>()
            val single = synchronized(lock) { segments.size == 1 && segments[0].end < 0 }
            synchronized(lock) {
                segments.filter { !it.complete }.forEach { seg -> workers += launch { work(seg, sink) } }
            }
            // Work stealing: when a connection finishes, split the largest remaining range.
            if (!single) {
                launch {
                    while (isActive) {
                        delay(500)
                        val n = synchronized(lock) { segments.count { it.working } }
                        if (n < callbacks.connections) {
                            val newSeg = split()
                            if (newSeg != null) workers += launch { work(newSeg, sink) } else if (n == 0) break
                        }
                        if (synchronized(lock) { segments.all { it.complete } }) break
                    }
                }.join()
            }
            workers.forEach { it.join() }
            val total = item.total
            val written = synchronized(lock) { segments.sumOf { it.done.get() } }
            if (total > 0 && written < total) throw IOException("Incomplete download")
            if (total <= 0) item.total = written
            item.done = item.total
            sink.force()
            item.status = DlStatus.CHECKING
            verifyChecksum(sink)
            item.target = sink.finish(item.name)
            item.parts = null
        } catch (e: Throwable) {
            sink.close()
            throw e
        }
    }

    private fun openSink(): Sink {
        val folder = item.folder
        if (folder == DISCARD) return NullSink()
        val partName = "${item.name}.${item.id}.part"
        return if (folder.startsWith("content://")) {
            val existing = item.target?.takeIf { it.startsWith("content://") }?.let { Uri.parse(it) }
            val doc = existing ?: run {
                val tree = Uri.parse(folder)
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                DocumentsContract.createDocument(ctx.contentResolver, parent, "application/octet-stream", partName)
                    ?: throw IOException("Cannot create the file")
            }
            item.target = doc.toString()
            DocSink(ctx, doc)
        } else {
            val part = item.target?.takeIf { it.endsWith(".part") }?.let { File(it) } ?: File(folder, partName)
            item.target = part.absolutePath
            FileSink(part)
        }
    }

    private fun loadOrPlan(sink: Sink) {
        val saved = item.parts
        if (saved != null && item.done > 0) {
            runCatching {
                val arr = JSONArray(saved)
                synchronized(lock) {
                    for (i in 0 until arr.length()) {
                        val a = arr.getJSONArray(i)
                        segments += Segment(a.getLong(0), a.getLong(1), a.getLong(2))
                    }
                }
            }
            if (segments.isNotEmpty()) return
        }
        // Probe: ask for the first byte range to learn size and range support.
        val resp = Net.client.newCall(request(0, -1, conditional = false)).execute()
        resp.use { r ->
            if (!r.isSuccessful) throw HttpError(r.code)
            readValidators(r)
            val rangeOk = r.code == 206
            val total: Long = if (rangeOk) contentRangeTotal(r) ?: -1L else r.body?.contentLength() ?: -1L
            item.total = total
            improveName(r)
            if (r.body?.contentType()?.toString()?.let { it.isNotEmpty() } == true && item.mime.isEmpty()) {
                item.mime = r.body?.contentType()?.toString()?.substringBefore(';') ?: ""
            }
            sink.truncate(0)
            if (total > 0) preallocate(sink, total)
            synchronized(lock) {
                segments.clear()
                if (rangeOk && total > 0) {
                    val n = callbacks.connections.coerceIn(1, 32)
                    val count = if (total < 2L * 1024 * 1024) 1 else minOf(n.toLong(), maxOf(1L, total / (1024 * 1024))).toInt()
                    val size = total / count
                    for (i in 0 until count) {
                        val s = i * size
                        val e = if (i == count - 1) total - 1 else s + size - 1
                        segments += Segment(s, e, 0)
                    }
                } else {
                    segments += Segment(0, -1, 0)
                }
            }
        }
        item.done = 0
        saveParts()
    }

    /**
     * Checks free space and, when enabled, reserves the whole file up front (posix_fallocate),
     * so parallel connections write into a file that does not have to grow.
     */
    private fun preallocate(sink: Sink, total: Long) {
        val free = sink.freeSpace()
        if (free in 0 until total) {
            throw NoSpace("Not enough storage: needs ${total / 1_048_576} MB, ${free / 1_048_576} MB free")
        }
        if (!callbacks.preallocate || !Storage.preallocOk(item.folder)) return
        runCatching { android.system.Os.posix_fallocate(sink.fd, 0, total) }
        // No fallback: extending the file by hand can mean writing zeros through all of it.
    }

    /**
     * On storage without sparse files (SD cards), several connections writing at different offsets
     * would make the kernel zero-fill up to each offset in one long call. Grow the file in steps first.
     */
    private suspend fun prepare(sink: Sink) {
        val total = item.total
        if (total <= 0 || sink is NullSink || Storage.preallocOk(item.folder)) return
        if (synchronized(lock) { segments.size } < 2) return
        val have = runCatching { android.system.Os.fstat(sink.fd).st_size }.getOrDefault(0L)
        if (have >= total) return
        val context = kotlinx.coroutines.currentCoroutineContext()
        item.prepTotal = total
        item.prepDone = have
        item.status = DlStatus.PREPARING
        val ok = withContext(Dispatchers.IO) {
            FilePrep.grow(sink.fd, total, cancelled = { !context.isActive }) { item.prepDone = it }
        }
        if (!ok) throw CancellationException("Paused")
    }

    private fun split(): Segment? = synchronized(lock) {
        val big = segments.filter { it.working && it.end >= 0 && it.remaining > 4L * 1024 * 1024 }.maxByOrNull { it.remaining } ?: return null
        val mid = big.pos + big.remaining / 2
        val seg = Segment(mid, big.end, 0)
        big.end = mid - 1
        segments += seg
        seg
    }

    private suspend fun work(seg: Segment, sink: Sink) {
        seg.working = true
        try {
            var tries = 0
            while (!seg.complete) {
                coroutineContextActive()
                try {
                    fetch(seg, sink)
                    if (seg.end < 0) return // single stream finished at end of body
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ServerChanged) {
                    throw e
                } catch (e: HttpError) {
                    if (e.code == 416 && seg.complete) return
                    throw e
                } catch (e: IOException) {
                    tries++
                    if (tries > 3) throw e
                    delay(1000L * tries)
                }
            }
        } finally {
            seg.working = false
        }
    }

    private suspend fun coroutineContextActive() = kotlinx.coroutines.currentCoroutineContext().ensureActive()

    private suspend fun fetch(seg: Segment, sink: Sink) {
        val from = seg.pos
        val to = seg.end
        val conditional = from > 0 || item.done > 0
        val resp = Net.client.newCall(request(from, to, conditional)).execute()
        resp.use { r ->
            if (r.code == 200 && from > 0) throw ServerChanged()
            if (!r.isSuccessful) throw HttpError(r.code)
            if (r.code == 206) {
                val crStart = r.header("Content-Range")?.substringAfter("bytes ")?.substringBefore('-')?.trim()?.toLongOrNull()
                if (crStart != null && crStart != from) throw ServerChanged()
            }
            if (item.etag == null && item.lastModified == null) readValidators(r)
            val body = r.body ?: throw IOException("Empty response")
            val input = body.byteStream()
            // Read in 256 KB pieces, write in ~1 MB batches: far fewer trips through Android's storage layer.
            val buf = ByteArray(256 * 1024)
            val batch = ByteBuffer.allocate(WRITE_BATCH)
            fun flush() {
                if (batch.position() == 0) return
                batch.flip()
                var pos = seg.pos
                val n = batch.remaining()
                while (batch.hasRemaining()) pos += sink.write(batch, pos)
                batch.clear()
                seg.done.addAndGet(n.toLong())
                tick()
            }
            try {
                while (true) {
                    coroutineContextActive()
                    val end = seg.end
                    val left = if (end < 0) Long.MAX_VALUE else end - seg.pos + 1 - batch.position()
                    if (left <= 0) break
                    val want = minOf(buf.size.toLong(), left, batch.remaining().toLong()).toInt()
                    val n = input.read(buf, 0, want)
                    if (n < 0) break
                    callbacks.throttle(n)
                    batch.put(buf, 0, n)
                    if (!batch.hasRemaining()) flush()
                }
            } finally {
                // Keep what already arrived, also when cancelled or the connection drops.
                runCatching { flush() }
            }
            if (seg.end >= 0 && !seg.complete) throw IOException("Connection closed early")
        }
    }

    private val speedWindow = AtomicLong(0)
    @Volatile private var speedStart = System.currentTimeMillis()

    private fun tick() {
        val done = synchronized(lock) { segments.sumOf { it.done.get() } }
        val now = System.currentTimeMillis()
        val elapsed = now - speedStart
        if (elapsed >= 1000) {
            val delta = done - speedWindow.get()
            item.speed = if (elapsed > 0) delta * 1000 / elapsed else 0
            speedWindow.set(done)
            speedStart = now
            item.done = done
            item.liveConnections = synchronized(lock) { segments.count { it.working } }
        }
        if (now - lastPersist > 2000) {
            lastPersist = now
            item.done = done
            saveParts()
            callbacks.persist(item)
        }
    }

    private fun saveParts() {
        val arr = JSONArray()
        synchronized(lock) {
            if (segments.isEmpty()) return
            if (segments.size == 1 && segments[0].end < 0) {
                // A single stream without range support cannot resume.
                item.parts = null
                return
            }
            segments.forEach { s -> arr.put(JSONArray().put(s.start).put(s.end).put(s.done.get())) }
            item.done = segments.sumOf { it.done.get() }
        }
        item.parts = arr.toString()
    }

    private fun readValidators(r: Response) {
        item.etag = r.header("ETag")
        item.lastModified = r.header("Last-Modified")
    }

    private fun contentRangeTotal(r: Response): Long? =
        r.header("Content-Range")?.substringAfter('/', "")?.trim()?.toLongOrNull()

    private fun improveName(r: Response) {
        val cd = r.header("Content-Disposition") ?: return
        val star = Regex("filename\\*=(?:UTF-8|utf-8)''([^;]+)").find(cd)?.groupValues?.get(1)
            ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
        val plain = Regex("filename=\"?([^\";]+)\"?").find(cd)?.groupValues?.get(1)
        val n = (star ?: plain)?.let { Downloads.safeName(it) } ?: return
        if (n.isNotBlank() && item.done == 0L) item.name = n
    }

    private fun verifyChecksum(sink: Sink) {
        val type = item.checksumType ?: return
        val want = item.checksumValue?.lowercase()?.trim() ?: return
        val md = MessageDigest.getInstance(
            when (type) { "MD5" -> "MD5"; "SHA-1" -> "SHA-1"; "SHA-512" -> "SHA-512"; else -> "SHA-256" },
        )
        sink.openRead().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        val got = md.digest().joinToString("") { "%02x".format(it) }
        item.checksumOk = if (got == want) 1 else 0
    }
}
