package com.novelgrabber.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Keeps a running download alive while the app is in the background / screen off.
 * It does NOT scrape anything itself — Engine + the scraper WebView still live in MainActivity.
 * This service just (1) holds foreground priority so Android doesn't freeze or kill the process,
 * (2) holds a partial wake lock so the CPU keeps running with the screen off, and
 * (3) shows a progress notification with a Stop button.
 */
class ScrapeService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        var instance: ScrapeService? = null
        const val ACTION_STOP = "com.novelgrabber.app.SCRAPE_STOP"
        private const val CH_RUN = "scrape_running"
        private const val CH_DONE = "scrape_done"
        private const val ID_RUN = 2
        private const val ID_DONE = 3

        // latest status, kept here so the notification is right even if the service starts a moment late
        @Volatile private var lastText = "Starting…"
        @Volatile private var lastDone = 0
        @Volatile private var lastTotal = 0

        fun start(ctx: Context) {
            lastText = "Starting…"; lastDone = 0; lastTotal = 0
            try { ContextCompat.startForegroundService(ctx, Intent(ctx, ScrapeService::class.java)) }
            catch (e: Exception) { /* background-start restrictions: the download itself still runs while the app is open */ }
        }

        fun status(s: String) { lastText = s; instance?.refresh() }
        fun progress(done: Int, total: Int) { lastDone = done; lastTotal = total; instance?.refresh() }

        /** Download ended: drop the ongoing notification and, if the user is away, say how it went. */
        fun finish(ctx: Context, how: String) {
            val text = lastText
            try { ctx.stopService(Intent(ctx, ScrapeService::class.java)) } catch (e: Exception) {}
            if (how != "stopped" && !MainActivity.foreground)
                notifyDone(ctx, if (how == "error") text.ifBlank { "The download stopped with an error" } else text)
        }

        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, ScrapeService::class.java)) } catch (e: Exception) {}
        }

        private fun ensureChannels(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CH_RUN, "Downloading novels", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false); setSound(null, null)
            })
            nm.createNotificationChannel(NotificationChannel(CH_DONE, "Download finished", NotificationManager.IMPORTANCE_DEFAULT))
        }

        private fun openAppIntent(ctx: Context) = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE)

        private fun notifyDone(ctx: Context, text: String) {
            try {
                ensureChannels(ctx)
                val n = NotificationCompat.Builder(ctx, CH_DONE)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle("NovelGrabber")
                    .setContentText(text)
                    .setContentIntent(openAppIntent(ctx))
                    .setAutoCancel(true)
                    .build()
                (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID_DONE, n)
            } catch (e: Exception) {}
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        ensureChannels(this)
        startForeground(ID_RUN, build())     // type (dataSync) comes from the manifest
        try {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NovelGrabber:scrape").apply {
                    setReferenceCounted(false)
                    acquire(6 * 60 * 60 * 1000L)      // safety net: never hold the CPU awake forever
                }
        } catch (e: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val a = MainActivity.instance
            if (a != null) a.stopDownloadFromService() else stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Android 15+ caps dataSync foreground services (~6 h); it must stop promptly when told. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        MainActivity.instance?.stopDownloadFromService()
        stopSelf()
    }

    fun refresh() {
        try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(ID_RUN, build()) } catch (e: Exception) {}
    }

    private fun build(): Notification {
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, ScrapeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val done = lastDone; val total = lastTotal
        val text = if (done > 0) "$done chapter(s) saved · $lastText" else lastText
        val b = NotificationCompat.Builder(this, CH_RUN)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Downloading novel")
            .setContentText(text)
            .setContentIntent(openAppIntent(this))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
        // "download everything" reports total = done + 1, i.e. no real total → indeterminate bar
        if (total > 0 && done + 1 != total) b.setProgress(total, done.coerceIn(0, total), false)
        else b.setProgress(0, 0, true)
        return b.build()
    }

    override fun onDestroy() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (e: Exception) {}
        wakeLock = null
        instance = null
        super.onDestroy()
    }
}
