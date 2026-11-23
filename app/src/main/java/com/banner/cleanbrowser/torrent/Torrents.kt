package com.banner.cleanbrowser.torrent

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.banner.cleanbrowser.App
import com.banner.cleanbrowser.core.NetMode
import com.banner.cleanbrowser.core.Settings
import com.banner.cleanbrowser.download.DlItem
import com.banner.cleanbrowser.download.DlStatus
import com.banner.cleanbrowser.download.Downloads
import com.banner.cleanbrowser.download.FilePrep
import com.banner.cleanbrowser.download.KeepAlive
import com.banner.cleanbrowser.download.Storage
import com.banner.cleanbrowser.net.WireGuard
import com.frostwire.jlibtorrent.AddTorrentParams
import com.frostwire.jlibtorrent.Priority
import com.frostwire.jlibtorrent.StorageMode
import com.frostwire.jlibtorrent.SessionHandle
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.SessionParams
import com.frostwire.jlibtorrent.SettingsPack
import com.frostwire.jlibtorrent.Sha1Hash
import com.frostwire.jlibtorrent.TorrentFlags
import com.frostwire.jlibtorrent.TorrentHandle
import com.frostwire.jlibtorrent.TorrentInfo
import com.frostwire.jlibtorrent.TorrentStatus
import com.frostwire.jlibtorrent.swig.settings_pack
import com.frostwire.jlibtorrent.swig.torrent_flags_t
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class TorrentFile(val index: Int, val path: String, val size: Long, val wanted: Boolean, val progress: Long)

/** libtorrent session (via FrostWire jlibtorrent). One handle per torrent item. */
object Torrents {
    private var session: SessionManager? = null
    private val handles = HashMap<Long, TorrentHandle>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var held = false
    private var poller = false
    private lateinit var ctx: Context
    /** Add jobs per item, so a resume never runs two at once. */
    private val addJobs = java.util.concurrent.ConcurrentHashMap<Long, Job>()
    /** Items growing a newly selected file; the poller leaves them alone meanwhile. */
    private val preparing = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    fun nameOf(bytes: ByteArray): String? = runCatching { TorrentInfo.bdecode(bytes).name() }.getOrNull()

    private fun ensureSession(c: Context): SessionManager {
        ctx = c.applicationContext
        session?.let { return it }
        val s = SessionManager(false)
        s.start(SessionParams(settingsPack(App.prefs.state)))
        session = s
        startPoller()
        return s
    }

    fun restore(c: Context, items: List<DlItem>) {
        if (items.isEmpty()) return
        ctx = c.applicationContext
        scope.launch {
            items.forEach { d ->
                if (d.status != DlStatus.PAUSED && d.status != DlStatus.DONE && d.status != DlStatus.FAILED) add(c, d)
            }
        }
    }

    fun add(c: Context, d: DlItem) {
        addJobs.remove(d.id)?.cancel()
        addJobs[d.id] = scope.launch {
            try {
                val s = ensureSession(c)
                val dir = File(d.folder).apply { mkdirs() }
                val magnet = d.url.startsWith("magnet:")
                // SD cards (exFAT/FAT): libtorrent sets each file's full size the first time it writes to
                // it, which zero-fills the whole file in one call. Get the file list first and grow the
                // files in small steps before the torrent is added.
                val stepGrow = !Storage.preallocOk(d.folder)
                var ti: TorrentInfo? = if (magnet) null else TorrentInfo(File(d.url.removePrefix("file:")))
                if (stepGrow && ti == null) ti = metadata(s, d) ?: return@launch
                val info = ti
                if (info != null) {
                    d.infoHash = info.infoHashV1().toHex()
                    d.total = info.totalSize()
                }
                if (stepGrow && info != null) {
                    if (!prepare(d, info, dir, null)) return@launch
                    main.post { spaceChecked += d.id } // space was checked before growing
                }
                val params = if (info == null) {
                    d.status = DlStatus.METADATA
                    AddTorrentParams.parseMagnetUri(d.url)
                } else {
                    AddTorrentParams.createInstance().also { p ->
                        p.torrentInfo(info)
                        if (magnet) runCatching { p.trackers(AddTorrentParams.parseMagnetUri(d.url).trackers()) }
                    }
                }
                params.savePath(dir.absolutePath)
                if (App.prefs.state.torExtraTrackers) {
                    val extra = Trackers.list()
                    if (extra.isNotEmpty()) params.trackers((params.trackers() + extra).distinct())
                }
                // Pre-allocate reserves every file's full size up front (libtorrent allocate mode).
                val allocate = App.prefs.state.dlPreallocate && com.banner.cleanbrowser.download.Storage.preallocOk(d.folder)
                params.storageMode(if (allocate) StorageMode.STORAGE_MODE_ALLOCATE else StorageMode.STORAGE_MODE_SPARSE)
                var flags: torrent_flags_t = params.swig().flags
                if (d.sequential) flags = flags.or_(TorrentFlags.SEQUENTIAL_DOWNLOAD)
                params.swig().flags = flags
                s.swig().async_add_torrent(params.swig())
                // The handle appears once libtorrent has added the torrent.
                repeat(60) {
                    val h = findHandle(s, d)
                    if (h != null) {
                        handles[d.id] = h
                        if (held) h.pause()
                        return@launch
                    }
                    delay(250)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                d.status = DlStatus.FAILED
                d.errorText = e.message ?: "Could not add torrent"
                Downloads.persist(d)
            }
        }
    }

    private fun wanted(d: DlItem) = d.status != DlStatus.PAUSED && Downloads.items.any { it.id == d.id }

    private fun infoFile(d: DlItem) = File(App.instance.filesDir, "torrents/item-${d.id}.torrent")

    /** A magnet's file list: the saved copy, or fetched from peers without writing to the save folder. */
    private suspend fun metadata(s: SessionManager, d: DlItem): TorrentInfo? {
        val f = infoFile(d)
        if (f.exists()) runCatching { return TorrentInfo(f) }
        d.status = DlStatus.METADATA
        val tmp = File(ctx.cacheDir, "magnet").apply { mkdirs() }
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!wanted(d)) return null
            if (held) { delay(2000); continue }
            val bytes = runCatching { s.fetchMagnet(d.url, 30, tmp) }.getOrNull() ?: continue
            f.parentFile?.mkdirs()
            f.writeBytes(bytes)
            return TorrentInfo(bytes)
        }
    }

    /**
     * Grows the torrent's files (all, or the indexes in [only]) to full size in small steps.
     * False when paused, removed or out of space.
     */
    private suspend fun prepare(d: DlItem, ti: TorrentInfo, dir: File, only: Set<Int>?): Boolean {
        val fs = ti.files()
        val todo = (0 until fs.numFiles()).filter { (only == null || it in only) && !fs.padFileAt(it) }
            .map { File(dir, fs.filePath(it)) to fs.fileSize(it) }
        val total = todo.sumOf { it.second }
        var done = todo.sumOf { (f, size) -> minOf(f.length(), size) }
        if (done >= total) return true
        val free = Storage.free(d.folder)
        if (free in 0 until total - done) {
            d.errorText = "Not enough storage: needs ${(total - done) / 1_073_741_824} GB, ${free / 1_073_741_824} GB free"
            if (only == null) d.status = DlStatus.PAUSED // not added yet; a running torrent keeps running
            Downloads.persist(d)
            return false
        }
        val context = currentCoroutineContext()
        d.prepTotal = total
        d.prepDone = done
        d.status = DlStatus.PREPARING
        Downloads.persist(d)
        for ((f, size) in todo) {
            val start = minOf(f.length(), size)
            val base = done
            val ok = FilePrep.grow(f, size, cancelled = { !context.isActive || !wanted(d) }) { d.prepDone = base + (it - start) }
            if (!ok) return false
            done = base + (size - start)
        }
        return true
    }

    private fun magnetHash(uri: String): String? =
        Regex("xt=urn:btih:([0-9a-fA-F]{40})").find(uri)?.groupValues?.get(1)?.lowercase()

    private fun findHandle(s: SessionManager, d: DlItem): TorrentHandle? {
        val hex = d.infoHash ?: magnetHash(d.url)
        if (hex != null) {
            val h = runCatching { s.find(Sha1Hash(hex)) }.getOrNull()
            if (h != null && h.isValid) return h
        }
        // Base32 magnets or unknown hash: match by name among handles we do not track yet.
        val known = handles.values.toSet()
        return s.torrentHandles.firstOrNull { it.isValid && it !in known && (it.name() == d.name || d.infoHash == null) }
    }

    fun pause(d: DlItem) {
        addJobs.remove(d.id)?.cancel()
        handles[d.id]?.let { h -> runCatching { h.unsetFlags(TorrentFlags.AUTO_MANAGED); h.pause(); h.saveResumeData() } }
    }

    fun resume(c: Context, d: DlItem) {
        val h = handles[d.id]
        if (h == null || !h.isValid) { add(c, d); return }
        runCatching { h.resume() }
    }

    fun remove(d: DlItem, deleteFiles: Boolean) {
        val job = addJobs.remove(d.id)?.also { it.cancel() }
        val h = handles.remove(d.id)
        val s = session
        if (h != null && s != null) runCatching { if (deleteFiles) s.remove(h, SessionHandle.DELETE_FILES) else s.remove(h) }
        val info = infoFile(d)
        scope.launch {
            job?.join()
            // Removed while getting the file list or growing files: libtorrent never had these files.
            if (h == null && deleteFiles) {
                val ti = runCatching { if (info.exists()) TorrentInfo(info) else TorrentInfo(File(d.url.removePrefix("file:"))) }.getOrNull()
                val name = ti?.name()
                if (!name.isNullOrBlank()) runCatching {
                    val root = File(d.folder).canonicalFile
                    val target = File(root, name).canonicalFile
                    // Only the torrent's own file or folder directly inside the download folder.
                    if (target.parentFile == root && target != root) {
                        if (target.isDirectory) target.deleteRecursively() else target.delete()
                    }
                }
            }
            if (d.url.startsWith("file:")) runCatching { File(d.url.removePrefix("file:")).delete() }
            runCatching { info.delete() }
        }
    }

    fun setSequential(d: DlItem, on: Boolean) {
        val h = handles[d.id] ?: return
        runCatching { if (on) h.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD) else h.unsetFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD) }
    }

    fun files(d: DlItem): List<TorrentFile> {
        val h = handles[d.id] ?: return emptyList()
        return runCatching {
            val ti = h.torrentFile() ?: return emptyList()
            val fs = ti.files()
            val prios = h.filePriorities()
            val prog = h.fileProgress()
            (0 until fs.numFiles()).map { i ->
                TorrentFile(i, fs.filePath(i), fs.fileSize(i), prios.getOrNull(i) != Priority.IGNORE, prog.getOrElse(i) { 0L })
            }
        }.getOrDefault(emptyList())
    }

    fun setWanted(d: DlItem, index: Int, wanted: Boolean) {
        val h = handles[d.id] ?: return
        if (wanted && !Storage.preallocOk(d.folder)) {
            // Grow the file before libtorrent writes into it (see add).
            if (!preparing.add(d.id)) return
            scope.launch {
                val before = d.status
                try {
                    val ti = runCatching { h.torrentFile() }.getOrNull() ?: return@launch
                    if (prepare(d, ti, File(d.folder), setOf(index))) runCatching { h.filePriority(index, Priority.NORMAL) }
                } finally {
                    preparing -= d.id
                    if (d.status == DlStatus.PREPARING) d.status = before
                }
            }
            return
        }
        runCatching { h.filePriority(index, if (wanted) Priority.NORMAL else Priority.IGNORE) }
    }

    /** Applies settings, the kill switch and "only over VPN". */
    fun applySettings() {
        val s = session ?: return
        runCatching { s.applySettings(settingsPack(App.prefs.state)) }
        onNetworkChanged()
    }

    fun onNetworkChanged() {
        val st = App.prefs.state
        val hold = shouldHold(st)
        if (hold == held) return
        held = hold
        handles.values.forEach { h -> runCatching { if (hold) h.pause() else h.resume() } }
        main.post {
            Downloads.items.filter { it.kind == com.banner.cleanbrowser.download.DlKind.TORRENT && it.status != DlStatus.PAUSED && !it.status.finished }
                .forEach { it.errorText = if (hold) holdReason(st) else null }
        }
    }

    private fun shouldHold(s: Settings): Boolean = holdReason(s) != null

    private fun holdReason(s: Settings): String? {
        if (s.torOnlyVpn && !Downloads.vpnUp && !WireGuard.connected) return "Waiting for VPN"
        if (s.netTorrents) {
            when (s.netMode) {
                NetMode.TOR -> return "Torrents are not sent over Tor"
                NetMode.WIREGUARD -> if (s.killSwitch && !WireGuard.connected) return "Waiting for VPN"
                NetMode.PROXY -> if (s.killSwitch && s.proxyHost.isBlank()) return "Proxy not set"
                NetMode.DIRECT -> {}
            }
        }
        if (s.dlWifiOnly && !Downloads.isUnmetered()) return "Waiting for Wi-Fi"
        return null
    }

    private fun settingsPack(s: Settings): SettingsPack {
        val p = SettingsPack()
        p.activeDownloads(s.torMaxActive.coerceIn(1, 20))
        p.connectionsLimit(s.torConnections.coerceIn(20, 1000))
        p.activeSeeds(s.torMaxActive.coerceIn(1, 20))
        p.downloadRateLimit(s.torDownLimit * 1024)
        p.uploadRateLimit(s.torUpLimit * 1024)
        p.setEnableDht(s.torDht)
        p.setEnableLsd(s.torLsd)
        p.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), s.torUpnp)
        p.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), s.torUpnp)
        val enc = when (s.torEncryption) {
            "force" -> settings_pack.enc_policy.pe_forced
            "off" -> settings_pack.enc_policy.pe_disabled
            else -> settings_pack.enc_policy.pe_enabled
        }
        p.setInteger(settings_pack.int_types.out_enc_policy.swigValue(), enc.swigValue())
        p.setInteger(settings_pack.int_types.in_enc_policy.swigValue(), enc.swigValue())
        if (s.netTorrents && s.netMode == NetMode.PROXY && s.proxyHost.isNotBlank()) {
            val auth = s.proxyUser.isNotEmpty()
            val type = when {
                s.proxyType == "HTTP" && auth -> settings_pack.proxy_type_t.http_pw
                s.proxyType == "HTTP" -> settings_pack.proxy_type_t.http
                auth -> settings_pack.proxy_type_t.socks5_pw
                else -> settings_pack.proxy_type_t.socks5
            }
            p.setInteger(settings_pack.int_types.proxy_type.swigValue(), type.swigValue())
            p.setString(settings_pack.string_types.proxy_hostname.swigValue(), s.proxyHost.trim())
            p.setInteger(settings_pack.int_types.proxy_port.swigValue(), s.proxyPort)
            if (auth) {
                p.setString(settings_pack.string_types.proxy_username.swigValue(), s.proxyUser)
                p.setString(settings_pack.string_types.proxy_password.swigValue(), s.proxyPass)
            }
            p.setBoolean(settings_pack.bool_types.proxy_hostnames.swigValue(), true)
            p.setBoolean(settings_pack.bool_types.proxy_peer_connections.swigValue(), true)
            p.setBoolean(settings_pack.bool_types.proxy_tracker_connections.swigValue(), true)
        } else {
            p.setInteger(settings_pack.int_types.proxy_type.swigValue(), settings_pack.proxy_type_t.none.swigValue())
        }
        return p
    }

    private fun startPoller() {
        if (poller) return
        poller = true
        scope.launch {
            while (isActive) {
                delay(1000)
                val snapshot = handles.toMap()
                if (snapshot.isEmpty()) continue
                val updates = snapshot.mapNotNull { (id, h) ->
                    val st = runCatching { if (h.isValid) h.status() else null }.getOrNull() ?: return@mapNotNull null
                    id to Snap(st, h)
                }
                main.post { apply(updates) }
            }
        }
    }

    private class Snap(st: TorrentStatus, h: TorrentHandle) {
        val name: String = st.name()
        val total: Long = st.totalWanted()
        val done: Long = st.totalWantedDone()
        val down: Long = st.downloadPayloadRate().toLong()
        val up: Long = st.uploadPayloadRate().toLong()
        val peers: Int = st.numPeers()
        val seeds: Int = st.numSeeds()
        val state: TorrentStatus.State = st.state()
        val finished: Boolean = st.isFinished
        val uploaded: Long = st.allTimeUpload()
        val paused: Boolean = runCatching { h.flags().and_(TorrentFlags.PAUSED).nonZero() }.getOrDefault(false)
        val hash: String = runCatching { st.infoHash().toHex() }.getOrDefault("")
        val error: String? = runCatching { st.errorCode() }.getOrNull()?.takeIf { it.isError }?.message()
        val savePath: String = runCatching { h.savePath() }.getOrDefault("")
    }

    private val spaceChecked = HashSet<Long>()

    private fun apply(updates: List<Pair<Long, Snap>>) {
        val st = App.prefs.state
        var changed = false
        for ((id, s) in updates) {
            val d = Downloads.items.firstOrNull { it.id == id } ?: continue
            if (id in preparing) continue
            // Once the size is known, make sure it fits; pause instead of filling the storage.
            if (s.total > 0 && id !in spaceChecked) {
                spaceChecked += id
                val free = com.banner.cleanbrowser.download.Storage.free(d.folder)
                val need = s.total - s.done
                if (free in 0 until need) {
                    pause(d)
                    d.status = DlStatus.PAUSED
                    d.errorText = "Not enough storage: needs ${need / 1_073_741_824} GB, ${free / 1_073_741_824} GB free"
                    Downloads.persist(d)
                    changed = true
                    continue
                }
            }
            if (s.name.isNotBlank() && d.name != s.name) d.name = s.name
            if (s.hash.isNotEmpty() && d.infoHash != s.hash) d.infoHash = s.hash
            if (s.total > 0) d.total = s.total
            d.done = s.done
            d.speed = s.down
            d.upSpeed = s.up
            d.peers = s.peers
            d.seeds = s.seeds
            d.uploaded = s.uploaded
            d.ratio = if (s.total > 0) s.uploaded.toFloat() / s.total else 0f
            if (s.savePath.isNotEmpty() && s.name.isNotEmpty()) d.target = File(s.savePath, s.name).absolutePath
            if (s.error != null) d.errorText = s.error
            val before = d.status
            if (d.status == DlStatus.PAUSED) continue
            d.status = when {
                s.finished && (st.torSeedRatio == 0 || d.ratio * 100 >= st.torSeedRatio) -> DlStatus.DONE
                s.finished -> DlStatus.SEEDING
                s.state == TorrentStatus.State.DOWNLOADING_METADATA -> DlStatus.METADATA
                s.state == TorrentStatus.State.CHECKING_FILES || s.state == TorrentStatus.State.CHECKING_RESUME_DATA -> DlStatus.CHECKING
                held -> DlStatus.WAITING_NETWORK
                else -> DlStatus.RUNNING
            }
            if (d.status == DlStatus.DONE && before != DlStatus.DONE) {
                handles[id]?.let { h -> runCatching { h.unsetFlags(TorrentFlags.AUTO_MANAGED); h.pause() } }
                d.speed = 0; d.upSpeed = 0
                d.finished = System.currentTimeMillis()
                KeepAlive.notifyDone(ctx, d)
            }
            if (d.status == DlStatus.SEEDING && before != DlStatus.SEEDING && before.active) {
                d.finished = System.currentTimeMillis()
                KeepAlive.notifyDone(ctx, d)
            }
            if (before != d.status) changed = true
            Downloads.persist(d)
        }
        if (changed) Downloads.schedule()
        KeepAlive.refresh(ctx)
    }
}
