package com.banner.cleanbrowser.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import androidx.compose.runtime.mutableStateListOf
import com.banner.cleanbrowser.App
import com.banner.cleanbrowser.browser.Engine
import com.banner.cleanbrowser.browser.Prompt
import com.banner.cleanbrowser.browser.Tab
import com.banner.cleanbrowser.browser.Ui
import com.banner.cleanbrowser.core.Urls
import com.banner.cleanbrowser.net.Net
import com.banner.cleanbrowser.torrent.Torrents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class DownloadRequest(
    val url: String,
    val fileName: String,
    val mime: String,
    val size: Long,
    val userAgent: String,
    val referer: String,
    val cookies: String?,
    val private: Boolean,
    val checksumType: String? = null,
    val checksum: String? = null,
)

object Downloads {
    private lateinit var ctx: Context
    private lateinit var store: DlStore
    val items = mutableStateListOf<DlItem>()
    private val jobs = HashMap<Long, Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var online = true
    @Volatile private var unmetered = true
    @Volatile var vpnUp = false
        private set

    fun init(context: Context) {
        ctx = context.applicationContext
        store = DlStore(ctx)
        KeepAlive.channels(ctx)
        watchNetwork()
        // Empty temp files left by the first speed test version.
        scope.launch { ctx.cacheDir.listFiles { f -> f.name.startsWith("cbnull") && f.name.endsWith(".tmp") }?.forEach { it.delete() } }
        scope.launch {
            val saved = store.all()
            withContext(Dispatchers.Main) {
                items.clear()
                items.addAll(saved)
                saved.forEach { d ->
                    // Transfers that were running when the app stopped continue.
                    if (d.status.active) d.status = DlStatus.QUEUED
                }
                Torrents.restore(ctx, saved.filter { it.kind == DlKind.TORRENT })
                schedule()
            }
        }
    }

    fun defaultFolder(): String =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath

    fun folder(): String = App.prefs.state.dlFolder.ifEmpty { defaultFolder() }

    fun safeName(raw: String): String {
        val n = raw.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trim('.')
        return n.take(180).ifEmpty { "download" }
    }

    // From the page --------------------------------------------------------------------

    fun fromPage(tab: Tab, url: String, userAgent: String?, disposition: String?, mime: String?, length: Long) {
        when {
            url.startsWith("blob:") -> { saveBlob(tab, url, disposition, mime); return }
            url.startsWith("data:") -> { saveDataUrl(url, mime); return }
            !(url.startsWith("http://") || url.startsWith("https://")) -> { Ui.toast("This link cannot be downloaded"); return }
        }
        if (mime == "application/x-bittorrent" || url.substringBefore('?').endsWith(".torrent", ignoreCase = true)) {
            addTorrentUrl(url, userAgent, tab)
            return
        }
        val name = safeName(URLUtil.guessFileName(url, disposition, mime))
        val cookies = runCatching { Engine.cookieManager(tab.isPrivate).getCookie(url) }.getOrNull()
        val req = DownloadRequest(url, name, mime ?: "", length, userAgent ?: "", tab.url, cookies, tab.isPrivate)
        if (App.prefs.state.confirmDownloads) Ui.prompt = Prompt.Download(req) else enqueue(req)
    }

    fun enqueue(r: DownloadRequest) {
        val s = App.prefs.state
        val item = DlItem(
            id = 0, kind = DlKind.HTTP, url = r.url, name = safeName(r.fileName), mime = r.mime,
            total = r.size, done = 0, status = DlStatus.QUEUED, error = null, created = System.currentTimeMillis(),
            finished = 0, etag = null, lastModified = null, userAgent = r.userAgent, referer = r.referer,
            cookies = r.cookies, folder = folder(), target = null, parts = null, connections = s.dlConnections,
            checksumType = r.checksumType, checksumValue = r.checksum, checksumOk = -1, isPrivate = r.private,
            infoHash = null, sequential = false, uploaded = 0,
        )
        val saved = copyWithId(item, store.insert(item))
        items.add(0, saved)
        Ui.toast("Downloading ${saved.name}")
        schedule()
    }

    private fun copyWithId(d: DlItem, id: Long) = DlItem(
        id, d.kind, d.url, d.name, d.mime, d.total, d.done, d.status, d.errorText, d.created, d.finished, d.etag,
        d.lastModified, d.userAgent, d.referer, d.cookies, d.folder, d.target, d.parts, d.connections, d.checksumType,
        d.checksumValue, d.checksumOk, d.isPrivate, d.infoHash, d.sequential, d.uploaded,
    )

    /** "Add link" from the Downloads screen. */
    fun addUrl(url: String, name: String?, checksumType: String?, checksum: String?) {
        val u = url.trim()
        Urls.magnetIn(u)?.let { addMagnet(it); return }
        if (!(u.startsWith("http://") || u.startsWith("https://"))) { Ui.toast("Not a web address"); return }
        if (u.substringBefore('?').endsWith(".torrent", ignoreCase = true)) { addTorrentUrl(u, null, null); return }
        val n = name?.takeIf { it.isNotBlank() } ?: URLUtil.guessFileName(u, null, null)
        enqueue(DownloadRequest(u, n, "", -1, Engine.userAgent(false) ?: "", "", null, false, checksumType, checksum?.takeIf { it.isNotBlank() }))
    }

    // Torrents -----------------------------------------------------------------------------

    fun addMagnet(uri: String) = addTorrentItem(uri, nameFromMagnet(uri))

    private fun nameFromMagnet(uri: String): String =
        Regex("[?&]dn=([^&]+)").find(uri)?.groupValues?.get(1)?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            ?: "Magnet link"

    fun addTorrentFile(uri: Uri) {
        scope.launch {
            val bytes = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            withContext(Dispatchers.Main) {
                if (bytes == null || bytes.isEmpty()) Ui.toast("Could not read the torrent file") else addTorrentBytes(bytes)
            }
        }
    }

    private fun addTorrentUrl(url: String, ua: String?, tab: Tab?) {
        scope.launch {
            val bytes = runCatching {
                val b = okhttp3.Request.Builder().url(url)
                ua?.let { b.header("User-Agent", it) }
                tab?.let { t -> runCatching { Engine.cookieManager(t.isPrivate).getCookie(url) }.getOrNull()?.let { b.header("Cookie", it) } }
                Net.client.newCall(b.build()).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
            }.getOrNull()
            withContext(Dispatchers.Main) {
                if (bytes == null || bytes.isEmpty()) Ui.toast("Could not get the torrent file") else addTorrentBytes(bytes)
            }
        }
    }

    private fun addTorrentBytes(bytes: ByteArray) {
        val name = Torrents.nameOf(bytes) ?: run { Ui.toast("Not a valid torrent"); return }
        val file = File(ctx.filesDir, "torrents/${UUID.randomUUID()}.torrent").apply { parentFile?.mkdirs(); writeBytes(bytes) }
        addTorrentItem("file:" + file.absolutePath, name)
    }

    private fun addTorrentItem(source: String, name: String) {
        val s = App.prefs.state
        val item = DlItem(
            id = 0, kind = DlKind.TORRENT, url = source, name = name, mime = "", total = -1, done = 0,
            status = DlStatus.QUEUED, error = null, created = System.currentTimeMillis(), finished = 0, etag = null,
            lastModified = null, userAgent = "", referer = "", cookies = null,
            folder = s.torFolder.ifEmpty { defaultFolder() }, target = null, parts = null, connections = 0,
            checksumType = null, checksumValue = null, checksumOk = -1, isPrivate = false, infoHash = null,
            sequential = s.torSequential, uploaded = 0,
        )
        val saved = copyWithId(item, store.insert(item))
        items.add(0, saved)
        Ui.toast("Torrent added")
        Torrents.add(ctx, saved)
        schedule()
    }

    // Controls -----------------------------------------------------------------------------

    fun pause(d: DlItem) {
        if (d.kind == DlKind.TORRENT) Torrents.pause(d) else jobs.remove(d.id)?.cancel()
        d.status = DlStatus.PAUSED
        d.speed = 0
        d.upSpeed = 0
        persist(d)
        schedule()
    }

    fun resume(d: DlItem) {
        d.errorText = null
        d.status = DlStatus.QUEUED
        if (d.kind == DlKind.TORRENT) Torrents.resume(ctx, d)
        persist(d)
        schedule()
    }

    fun retry(d: DlItem) = resume(d)

    /** Starts again from zero (new file). */
    fun restart(d: DlItem) {
        jobs.remove(d.id)?.cancel()
        deleteFile(d)
        d.parts = null; d.done = 0; d.target = null; d.etag = null; d.lastModified = null; d.checksumOk = -1
        resume(d)
    }

    fun remove(d: DlItem, deleteFile: Boolean) {
        jobs.remove(d.id)?.cancel()
        if (d.kind == DlKind.TORRENT) Torrents.remove(d, deleteFile)
        else if (deleteFile || !d.status.finished) deleteFile(d)
        items.remove(d)
        App.io { store.delete(d.id) }
        schedule()
    }

    fun clearFinished() {
        items.filter { it.status == DlStatus.DONE }.forEach { remove(it, deleteFile = false) }
    }

    private fun deleteFile(d: DlItem) {
        val t = d.target ?: return
        App.io {
            if (t.startsWith("content://")) runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, Uri.parse(t)) }
            else File(t).let { f -> if (f.isDirectory) f.deleteRecursively() else f.delete() }
        }
    }

    fun pauseAll(system: Boolean) {
        main.post {
            items.filter { it.status.active || (it.status == DlStatus.SEEDING && !system) }.forEach { pause(it) }
        }
    }

    fun resumeAll() {
        items.filter { it.status == DlStatus.PAUSED || it.status == DlStatus.FAILED }.forEach { resume(it) }
    }

    fun setSequential(d: DlItem, on: Boolean) {
        d.sequential = on
        Torrents.setSequential(d, on)
        persist(d)
    }

    fun ensureRunning() {
        main.post { schedule() }
    }

    fun remainingBytes(): Long = items.filter { it.status.active }.sumOf { (it.total - it.done).coerceAtLeast(0) }

    fun persist(d: DlItem) {
        App.io { store.update(d) }
    }

    fun isOnline() = online
    fun isUnmetered() = unmetered

    // Scheduler ----------------------------------------------------------------------------

    fun schedule() {
        if (!::ctx.isInitialized) return
        val s = App.prefs.state
        val http = items.filter { it.kind == DlKind.HTTP }
        val running = http.count { jobs.containsKey(it.id) }
        var free = s.dlParallel.coerceIn(1, 10) - running
        for (d in http.reversed()) {
            if (free <= 0) break
            if (d.status == DlStatus.QUEUED && !jobs.containsKey(d.id)) {
                start(d)
                free--
            }
        }
        val active = items.any { it.status.active } || SpeedTest.running ||
            (s.torSeedBackground && items.any { it.status == DlStatus.SEEDING })
        KeepAlive.update(ctx, active)
    }

    private fun start(d: DlItem) {
        val callbacks = object : HttpEngine.Callbacks {
            override fun persist(item: DlItem) = store.update(item)
            override suspend fun waitForNetwork(item: DlItem) = waitNet(item)
            override suspend fun throttle(bytes: Int) = Throttle.take(bytes)
            override val connections: Int get() = d.connections.coerceIn(1, 32)
            override val maxRetries: Int get() = App.prefs.state.dlRetries
            override val preallocate: Boolean get() = App.prefs.state.dlPreallocate
        }
        val job = scope.launch {
            try {
                waitNet(d)
                HttpEngine(ctx, d, callbacks).run()
                d.status = DlStatus.DONE
                d.finished = System.currentTimeMillis()
                d.speed = 0
                d.errorText = if (d.checksumOk == 0) "Checksum does not match" else null
                store.update(d)
                KeepAlive.notifyDone(ctx, d)
            } catch (e: CancellationException) {
                d.speed = 0
                store.update(d)
            } catch (e: Throwable) {
                d.status = DlStatus.FAILED
                d.speed = 0
                d.errorText = e.message ?: e.javaClass.simpleName
                store.update(d)
                KeepAlive.notifyDone(ctx, d)
            }
        }
        jobs[d.id] = job
        job.invokeOnCompletion {
            main.post {
                if (jobs[d.id] === job) jobs.remove(d.id)
                schedule()
            }
        }
        // Progress notification refresh while this job runs.
        scope.launch {
            while (job.isActive) {
                KeepAlive.refresh(ctx)
                delay(1500)
            }
        }
    }

    private suspend fun waitNet(d: DlItem) {
        while (true) {
            val s = App.prefs.state
            val netOk = online && (!s.dlWifiOnly || unmetered)
            val vpnOk = !Net.blocked(s, s.netDownloads)
            if (netOk && vpnOk) break
            d.status = DlStatus.WAITING_NETWORK
            d.errorText = if (!vpnOk) "Waiting for VPN" else if (!online) "Waiting for network" else "Waiting for Wi-Fi"
            delay(1500)
        }
        if (d.status == DlStatus.WAITING_NETWORK) d.errorText = null
        d.status = DlStatus.CONNECTING
    }

    private fun watchNetwork() {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        fun recompute() {
            val n = cm.activeNetwork
            val caps = n?.let { cm.getNetworkCapabilities(it) }
            online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            unmetered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
            vpnUp = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            Torrents.onNetworkChanged()
        }
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = recompute()
                override fun onLost(network: Network) = recompute()
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = recompute()
            })
        }
        recompute()
    }

    // blob: and data: ----------------------------------------------------------------------

    private val blobTokens = HashMap<String, String>()

    private fun saveBlob(tab: Tab, url: String, disposition: String?, mime: String?) {
        val wv = tab.webView ?: return
        val token = UUID.randomUUID().toString()
        synchronized(blobTokens) { blobTokens[token] = URLUtil.guessFileName(url, disposition, mime) }
        val js = """
            (function(){fetch(${quote(url)}).then(function(r){return r.blob();}).then(function(b){
              if (b.size > 268435456) { CleanBrowserBlob.fail(${quote(token)}, 'too large'); return; }
              var fr = new FileReader(); fr.onloadend = function(){ CleanBrowserBlob.save(${quote(token)}, fr.result, b.type || ''); };
              fr.readAsDataURL(b);
            }).catch(function(e){ CleanBrowserBlob.fail(${quote(token)}, String(e)); });})();
        """.trimIndent()
        wv.evaluateJavascript(js, null)
    }

    private fun quote(s: String) = org.json.JSONObject.quote(s)

    /** Bridge added to every WebView; it only accepts tokens this app issued for a download. */
    class BlobBridge {
        @JavascriptInterface
        fun save(token: String, dataUrl: String, type: String) {
            val name = synchronized(blobTokens) { blobTokens.remove(token) } ?: return
            main.post { saveDataUrl(dataUrl, type.ifEmpty { null }, name) }
        }

        @JavascriptInterface
        fun fail(token: String, reason: String) {
            synchronized(blobTokens) { blobTokens.remove(token) } ?: return
            main.post { Ui.toast("Download failed: $reason") }
        }
    }

    private fun saveDataUrl(dataUrl: String, mime: String?, suggested: String? = null) {
        val comma = dataUrl.indexOf(',')
        if (comma < 5) return
        val meta = dataUrl.substring(5, comma)
        val type = meta.substringBefore(';').ifEmpty { mime ?: "application/octet-stream" }
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(type)
        val name = safeName(suggested?.takeIf { !it.startsWith("downloadfile") } ?: ("download" + (ext?.let { ".$it" } ?: "")))
        scope.launch {
            val ok = runCatching {
                val payload = dataUrl.substring(comma + 1)
                val bytes = if (meta.contains(";base64")) Base64.decode(payload, Base64.DEFAULT)
                else java.net.URLDecoder.decode(payload, "UTF-8").toByteArray()
                val dir = File(folder().takeIf { !it.startsWith("content://") } ?: defaultFolder()).apply { mkdirs() }
                val f = uniqueFile(dir, name)
                f.writeBytes(bytes)
                val d = DlItem(
                    0, DlKind.HTTP, "data:", f.name, type, bytes.size.toLong(), bytes.size.toLong(), DlStatus.DONE, null,
                    System.currentTimeMillis(), System.currentTimeMillis(), null, null, "", "", null, dir.absolutePath,
                    f.absolutePath, null, 1, null, null, -1, false, null, false, 0,
                )
                val saved = copyWithId(d, store.insert(d))
                withContext(Dispatchers.Main) { items.add(0, saved); Ui.toast("Saved ${saved.name}") }
            }.isSuccess
            if (!ok) withContext(Dispatchers.Main) { Ui.toast("Could not save the file") }
        }
    }
}

/** Global download speed limit (KB/s from settings; 0 = no limit). */
object Throttle {
    private var windowStart = System.currentTimeMillis()
    private var used = 0L

    suspend fun take(bytes: Int) {
        val limit = App.prefs.state.dlSpeedLimit.toLong() * 1024
        if (limit <= 0) return
        var wait = 0L
        synchronized(this) {
            val now = System.currentTimeMillis()
            if (now - windowStart >= 1000) { windowStart = now; used = 0 }
            used += bytes
            if (used > limit) wait = 1000 - (now - windowStart)
        }
        if (wait > 0) delay(wait)
    }
}
