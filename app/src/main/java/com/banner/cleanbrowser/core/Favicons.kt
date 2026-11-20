package com.banner.cleanbrowser.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.banner.cleanbrowser.App
import com.banner.cleanbrowser.net.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Site icons kept on the device. Saved from pages the user opens; missing ones are fetched from
 * the site itself (never from a third-party icon service), without cookies, via the user's route.
 */
object Favicons {
    private const val MAX_BYTES = 512 * 1024
    private const val SIZE = 128
    private val mem = LruCache<String, ImageBitmap>(96)
    private val failed = ConcurrentHashMap<String, Long>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    private fun dir(): File = File(App.instance.filesDir, "favicons").apply { mkdirs() }
    private fun file(host: String) = File(dir(), host.replace(Regex("[^a-z0-9.-]"), "_") + ".png")

    fun key(url: String): String = Urls.host(url).removePrefix("www.")

    fun cached(host: String): ImageBitmap? = if (host.isEmpty()) null else mem.get(host)

    /** From WebView's onReceivedIcon. Keeps the larger of the stored and the new icon. */
    fun save(url: String, icon: Bitmap) {
        val host = key(url)
        if (host.isEmpty() || !App.prefs.state.siteIcons) return
        App.io {
            val f = file(host)
            val old = if (f.exists()) BitmapFactory.decodeFile(f.path) else null
            if (old != null && old.width >= icon.width) return@io
            val scaled = scale(icon)
            f.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
            mem.put(host, scaled.asImageBitmap())
        }
    }

    suspend fun load(host: String): ImageBitmap? {
        if (host.isEmpty() || !App.prefs.state.siteIcons) return null
        mem.get(host)?.let { return it }
        val lock = locks.getOrPut(host) { Mutex() }
        return lock.withLock {
            mem.get(host)?.let { return@withLock it }
            withContext(Dispatchers.IO) {
                val f = file(host)
                val disk = if (f.exists()) BitmapFactory.decodeFile(f.path) else null
                if (disk != null && disk.width >= 48) return@withContext disk.asImageBitmap().also { mem.put(host, it) }
                val last = failed[host]
                if (last != null && System.currentTimeMillis() - last < 24 * 60 * 60 * 1000L) return@withContext disk?.asImageBitmap()
                val s = App.prefs.state
                if (Net.blocked(s, s.netDownloads)) return@withContext disk?.asImageBitmap()
                val fetched = fetch(host)
                if (fetched != null && fetched.width > (disk?.width ?: 0)) {
                    val scaled = scale(fetched)
                    runCatching { f.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                    scaled.asImageBitmap().also { mem.put(host, it) }
                } else {
                    if (fetched == null) failed[host] = System.currentTimeMillis()
                    disk?.asImageBitmap()?.also { mem.put(host, it) }
                }
            }
        }
    }

    private fun scale(b: Bitmap): Bitmap {
        val m = maxOf(b.width, b.height)
        if (m <= SIZE) return b
        val r = SIZE.toFloat() / m
        return Bitmap.createScaledBitmap(b, (b.width * r).toInt().coerceAtLeast(1), (b.height * r).toInt().coerceAtLeast(1), true)
    }

    private fun get(url: String): ByteArray? = runCatching {
        Net.client.newCall(
            Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").header("Accept", "image/*,*/*;q=0.5").build(),
        ).execute().use { r ->
            if (!r.isSuccessful) return null
            val body = r.body ?: return null
            if (body.contentLength() > MAX_BYTES) return null
            val bytes = body.source().let { src -> src.request(MAX_BYTES.toLong() + 1); src.buffer.readByteArray(minOf(src.buffer.size, MAX_BYTES.toLong() + 1)) }
            if (bytes.size > MAX_BYTES) null else bytes
        }
    }.getOrNull()

    private fun decode(bytes: ByteArray?): Bitmap? =
        bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }?.takeIf { it.width >= 16 }

    private fun fetch(host: String): Bitmap? {
        val base = "https://$host"
        // 1. Large icons most sites publish at fixed paths.
        for (p in listOf("/apple-touch-icon.png", "/apple-touch-icon-precomposed.png")) {
            decode(get(base + p))?.let { return it }
        }
        // 2. Icons declared in the home page.
        val html = get("$base/")?.let { String(it, Charsets.UTF_8) }
        if (html != null) {
            val links = Regex("<link[^>]+>", RegexOption.IGNORE_CASE).findAll(html).map { it.value }
                .filter { Regex("rel=[\"']?[^\"'>]*icon", RegexOption.IGNORE_CASE).containsMatchIn(it) }
                .mapNotNull { tag ->
                    val href = Regex("href=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1) ?: return@mapNotNull null
                    val size = Regex("sizes=[\"'](\\d+)x", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val apple = tag.contains("apple-touch", true)
                    Triple(href, size + if (apple) 1000 else 0, tag)
                }
                .filter { !it.first.endsWith(".svg", true) }
                .sortedByDescending { it.second }
                .toList()
            for ((href, _, _) in links.take(4)) {
                val abs = when {
                    href.startsWith("//") -> "https:$href"
                    href.startsWith("http") -> href
                    href.startsWith("/") -> base + href
                    else -> "$base/$href"
                }
                decode(get(abs.replace("&amp;", "&")))?.let { return it }
            }
        }
        // 3. The classic favicon.
        return decode(get("$base/favicon.ico"))
    }

    fun clearAll() {
        mem.evictAll()
        failed.clear()
        dir().listFiles()?.forEach { it.delete() }
    }
}
