package com.banner.cleanbrowser.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.banner.cleanbrowser.App
import com.banner.cleanbrowser.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Checks the public releases repo, downloads the APK, verifies hash + signer, opens the installer. */
object Updater {
    data class Release(val tag: String, val version: String, val notes: String, val apkUrl: String, val apkName: String, val size: Long, val sha256: String?, val prerelease: Boolean)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val percent: Int) : State
        data class Ready(val release: Release, val file: File) : State
        data class Failed(val message: String) : State
    }

    var state by mutableStateOf<State>(State.Idle)
        private set
    var showDialog by mutableStateOf(false)

    private const val API = "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases?per_page=15"

    fun statusText(): String? = when (val s = state) {
        is State.UpToDate -> "Up to date"
        is State.Available -> "${s.release.version} available"
        is State.Ready -> "${s.release.version} ready to install"
        is State.Failed -> s.message
        else -> null
    }

    suspend fun check(ctx: Context, manual: Boolean) {
        if (state is State.Checking || state is State.Downloading) return
        state = State.Checking
        state = try {
            val rel = withContext(Dispatchers.IO) { latest() }
            if (rel != null && isNewer(rel.version, BuildConfig.VERSION_NAME)) {
                showDialog = true
                State.Available(rel)
            } else State.UpToDate
        } catch (e: Exception) {
            State.Failed(if (manual) "Check failed" else "")
        }
        App.prefs.putLong("lastUpdateCheck", System.currentTimeMillis())
    }

    suspend fun autoCheck(ctx: Context) {
        if (!App.prefs.state.autoUpdateCheck) return
        val last = App.prefs.getLong("lastUpdateCheck")
        if (System.currentTimeMillis() - last < 6 * 60 * 60 * 1000L) return
        check(ctx, manual = false)
    }

    private fun latest(): Release? {
        val body = get(API)
        val arr = JSONArray(body)
        val allowPre = App.prefs.state.testBuilds
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            if (r.optBoolean("draft")) continue
            val pre = r.optBoolean("prerelease")
            if (pre && !allowPre) continue
            val assets = r.optJSONArray("assets") ?: continue
            var apk: org.json.JSONObject? = null
            var sums: String? = null
            for (j in 0 until assets.length()) {
                val a = assets.getJSONObject(j)
                val name = a.optString("name")
                if (name.endsWith(".apk") && apk == null) apk = a
                if (name == "SHA256SUMS" || name.endsWith(".sha256")) sums = a.optString("browser_download_url")
            }
            if (apk == null) continue
            val apkName = apk.optString("name")
            val sha = sums?.let { u ->
                runCatching { get(u) }.getOrNull()?.lineSequence()
                    ?.map { it.trim().split(Regex("\\s+")) }
                    ?.firstOrNull { it.size >= 2 && it.last().trimStart('*') == apkName }
                    ?.first()?.lowercase()
            }
            val tag = r.optString("tag_name")
            return Release(
                tag = tag,
                version = tag.removePrefix("v"),
                notes = r.optString("body"),
                apkUrl = apk.optString("browser_download_url"),
                apkName = apkName,
                size = apk.optLong("size"),
                sha256 = sha,
                prerelease = pre,
            )
        }
        return null
    }

    suspend fun download(ctx: Context, rel: Release) {
        state = State.Downloading(rel, 0)
        state = try {
            val file = withContext(Dispatchers.IO) {
                val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val out = File(dir, "update.apk")
                val c = open(rel.apkUrl)
                val total = c.contentLengthLong.takeIf { it > 0 } ?: rel.size
                val md = MessageDigest.getInstance("SHA-256")
                c.inputStream.use { input ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            md.update(buf, 0, n)
                            done += n
                            val pct = if (total > 0) (done * 100 / total).toInt() else 0
                            if (pct != lastPct) {
                                lastPct = pct
                                withContext(Dispatchers.Main) { state = State.Downloading(rel, pct) }
                            }
                        }
                    }
                }
                val hash = md.digest().joinToString("") { "%02x".format(it) }
                if (rel.sha256 == null) throw IllegalStateException("No checksum published")
                if (hash != rel.sha256) throw IllegalStateException("Checksum mismatch")
                verifySigner(ctx, out)
                out
            }
            State.Ready(rel, file)
        } catch (e: Exception) {
            State.Failed(e.message ?: "Download failed")
        }
    }

    private fun verifySigner(ctx: Context, apk: File) {
        val pm = ctx.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: throw IllegalStateException("Not a valid APK")
        if (archive.packageName != ctx.packageName) throw IllegalStateException("Different app")
        val installed = pm.getPackageInfo(ctx.packageName, flags)
        fun certs(info: android.content.pm.PackageInfo): Set<String> {
            val si = info.signingInfo ?: return emptySet()
            val sigs = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
            return sigs.map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
        }
        val a = certs(archive)
        val b = certs(installed)
        if (a.isEmpty() || a.intersect(b).isEmpty()) throw IllegalStateException("Signature does not match")
    }

    /** Returns false when the user first has to allow installs from this app. */
    fun install(ctx: Context, file: File): Boolean {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        return true
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "CleanBrowser-updater")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        return c
    }

    private fun get(url: String): String {
        val c = open(url)
        if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    /** Compares versions like 0.2.0, 0.2.0-pre3. A plain release is newer than its pre-releases. */
    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0

    fun compare(a: String, b: String): Int {
        fun split(v: String): Pair<List<Int>, Int> {
            val main = v.substringBefore('-')
            val pre = v.substringAfter('-', "")
            val nums = main.split('.').map { it.toIntOrNull() ?: 0 }
            val preN = if (pre.isEmpty()) Int.MAX_VALUE else Regex("\\d+").find(pre)?.value?.toIntOrNull() ?: 0
            return nums to preN
        }
        val (an, ap) = split(a)
        val (bn, bp) = split(b)
        for (i in 0 until maxOf(an.size, bn.size)) {
            val x = an.getOrElse(i) { 0 }
            val y = bn.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return ap.compareTo(bp)
    }
}
