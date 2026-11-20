package com.banner.cleanbrowser.download

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.text.format.Formatter
import androidx.core.content.FileProvider
import com.banner.cleanbrowser.App
import com.banner.cleanbrowser.MainActivity
import com.banner.cleanbrowser.R
import java.io.File

/**
 * Keeps the process alive while downloads run: a user-initiated data transfer job on
 * Android 14+, a foreground service before that. Holds a wake lock and a Wi-Fi lock.
 * Notifications are silent; when the user turns them off the progress notification moves
 * to a minimised channel (Android needs one for long transfers).
 */
object KeepAlive {
    const val CH_PROGRESS = "downloads"
    const val CH_QUIET = "downloads_quiet"
    const val CH_DONE = "downloads_done"
    const val ID_PROGRESS = 7001
    private const val JOB_ID = 7001

    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var wifiFast: WifiManager.WifiLock? = null
    @Volatile var job: Pair<DownloadJob, JobParameters>? = null
    @Volatile var serviceRunning = false
    @Volatile private var wanted = false

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null); enableVibration(false); setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_QUIET, "Downloads (hidden)", NotificationManager.IMPORTANCE_MIN).apply {
            setSound(null, null); enableVibration(false); setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "Finished downloads", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null); enableVibration(false)
        })
    }

    /** Called by the engine whenever the number of active transfers changes. */
    fun update(ctx: Context, active: Boolean) {
        if (active == wanted && (!active || job != null || serviceRunning)) {
            if (active) refresh(ctx)
            return
        }
        wanted = active
        if (active) start(ctx) else stop(ctx)
    }

    private fun start(ctx: Context) {
        locks(ctx, true)
        var ok = false
        if (Build.VERSION.SDK_INT >= 34) ok = scheduleJob(ctx)
        if (!ok) ok = runCatching {
            ctx.startForegroundService(Intent(ctx, DownloadService::class.java))
            true
        }.getOrDefault(false)
        if (!ok) refresh(ctx)
    }

    @SuppressLint("MissingPermission")
    private fun scheduleJob(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        val js = ctx.getSystemService(JobScheduler::class.java)
        if (js.getPendingJob(JOB_ID) != null) return true
        val info = JobInfo.Builder(JOB_ID, ComponentName(ctx, DownloadJob::class.java))
            .setUserInitiated(true)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setEstimatedNetworkBytes(Downloads.remainingBytes().coerceAtLeast(1L), 0)
            .build()
        return runCatching { js.schedule(info) == JobScheduler.RESULT_SUCCESS }.getOrDefault(false)
    }

    private fun stop(ctx: Context) {
        locks(ctx, false)
        job?.let { (svc, params) -> runCatching { svc.jobFinished(params, false) } }
        job = null
        if (Build.VERSION.SDK_INT >= 34) runCatching { ctx.getSystemService(JobScheduler::class.java).cancel(JOB_ID) }
        if (serviceRunning) runCatching { ctx.stopService(Intent(ctx, DownloadService::class.java)) }
        ctx.getSystemService(NotificationManager::class.java).cancel(ID_PROGRESS)
    }

    @Suppress("DEPRECATION")
    private fun locks(ctx: Context, on: Boolean) {
        if (on) {
            if (wake == null) {
                wake = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CleanBrowser:downloads").apply { setReferenceCounted(false); acquire() }
            }
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (wifi == null) {
                wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "CleanBrowser:downloads").apply { setReferenceCounted(false); acquire() }
            }
            if (wifiFast == null) {
                wifiFast = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "CleanBrowser:downloads-ll").apply { setReferenceCounted(false); acquire() }
            }
        } else {
            runCatching { wake?.release() }; wake = null
            runCatching { wifi?.release() }; wifi = null
            runCatching { wifiFast?.release() }; wifiFast = null
        }
    }

    fun refresh(ctx: Context) {
        if (!wanted) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        runCatching { nm.notify(ID_PROGRESS, progressNotification(ctx)) }
    }

    fun progressNotification(ctx: Context): Notification {
        val visible = App.prefs.state.dlNotifications
        val active = Downloads.items.filter { it.status.active }
        val total = active.sumOf { it.total.coerceAtLeast(0) }
        val done = active.sumOf { it.done }
        val speed = active.sumOf { it.speed }
        val pct = if (total > 0) (done * 100 / total).toInt() else 0
        val title = if (active.size == 1) active[0].name else "Downloading ${active.size} files"
        val text = buildString {
            if (total > 0) append("$pct% · ")
            append(Formatter.formatShortFileSize(ctx, speed)).append("/s")
            val remaining = total - done
            if (speed > 0 && total > 0) append(" · ").append(eta(remaining / speed))
        }
        val open = PendingIntent.getActivity(
            ctx, 1, Intent(ctx, MainActivity::class.java).setAction(ACTION_OPEN_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val pause = PendingIntent.getBroadcast(
            ctx, 2, Intent(ctx, DownloadActions::class.java).setAction(ACTION_PAUSE_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = Notification.Builder(ctx, if (visible) CH_PROGRESS else CH_QUIET)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(if (active.isEmpty()) (if (SpeedTest.running) "Speed test" else "Downloads") else title)
            .setContentText(if (active.isEmpty()) (if (SpeedTest.running) "Measuring download speed" else "Waiting") else text)
            .setProgress(100, pct, total <= 0 && active.isNotEmpty())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Pause all", pause).build())
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return b.build()
    }

    fun notifyDone(ctx: Context, item: DlItem) {
        if (!App.prefs.state.dlNotifications) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val ok = item.status.finished
        val b = Notification.Builder(ctx, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(item.name)
            .setContentText(if (ok) "Download complete" else "Download failed")
            .setAutoCancel(true)
        if (ok) openIntent(ctx, item)?.let { i ->
            b.setContentIntent(PendingIntent.getActivity(ctx, item.id.toInt(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        runCatching { nm.notify(10_000 + item.id.toInt(), b.build()) }
    }

    fun openIntent(ctx: Context, item: DlItem): Intent? {
        val t = item.target ?: return null
        val uri: Uri = if (t.startsWith("content://")) Uri.parse(t) else {
            val f = File(t)
            if (!f.exists()) return null
            if (f.isDirectory) return null
            runCatching { FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f) }.getOrNull() ?: return null
        }
        val mime = item.mime.ifEmpty { ctx.contentResolver.getType(uri) ?: "*/*" }
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun eta(sec: Long): String = when {
        sec < 60 -> "${sec}s left"
        sec < 3600 -> "${sec / 60} min left"
        else -> "${sec / 3600} h ${sec % 3600 / 60} min left"
    }

    const val ACTION_OPEN_DOWNLOADS = "com.banner.cleanbrowser.OPEN_DOWNLOADS"
    const val ACTION_PAUSE_ALL = "com.banner.cleanbrowser.PAUSE_ALL"
}

/** Android 14+: user-initiated data transfer job. */
class DownloadJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        if (Build.VERSION.SDK_INT >= 34) {
            setNotification(params, KeepAlive.ID_PROGRESS, KeepAlive.progressNotification(this), JOB_END_NOTIFICATION_POLICY_REMOVE)
        }
        KeepAlive.job = this to params
        Downloads.ensureRunning()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        KeepAlive.job = null
        // Reschedule; the engine keeps its state and continues when the job runs again.
        return Downloads.items.any { it.status.active }
    }
}

/** Before Android 14 (and as a fallback): foreground service. */
class DownloadService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = KeepAlive.progressNotification(this)
        if (Build.VERSION.SDK_INT >= 29) startForeground(KeepAlive.ID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(KeepAlive.ID_PROGRESS, n)
        KeepAlive.serviceRunning = true
        Downloads.ensureRunning()
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ limits dataSync services to 6 hours a day; pause cleanly and let the user resume.
        Downloads.pauseAll(system = true)
        stopSelf()
    }

    override fun onDestroy() {
        KeepAlive.serviceRunning = false
        super.onDestroy()
    }
}

const val ACTION_SPEED_TEST = "com.banner.cleanbrowser.SPEED_TEST"

class DownloadActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            KeepAlive.ACTION_PAUSE_ALL -> Downloads.pauseAll(system = false)
            ACTION_SPEED_TEST -> SpeedTest.start(context, detailed = intent.getBooleanExtra("detailed", false))
        }
    }
}
