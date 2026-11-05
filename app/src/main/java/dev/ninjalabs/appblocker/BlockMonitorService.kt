package dev.ninjalabs.appblocker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Optional fallback for devices that drop accessibility events.
 *
 * The accessibility service is the primary mechanism. This watchdog polls
 * UsageStatsManager once a second and shows the same overlay if it sees a
 * blocked package. It only runs when the user turns it on and grants usage
 * access, because polling costs battery.
 */
class BlockMonitorService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            checkForegroundApp()
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!UsageStats.hasPermission(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat()
        if (!running) {
            running = true
            handler.post(poll)
        }
        return START_STICKY
    }

    private fun checkForegroundApp() {
        val packageName = UsageStats.foregroundPackage(this) ?: return
        val reason = BlockerConfig.reasonToBlock(this, packageName)
        if (reason != null) {
            val label = try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
            } catch (_: Exception) {
                packageName
            }
            BlockOverlay.show(this, packageName, label, reason)
        } else if (BlockOverlay.isShowing) {
            BlockOverlay.hide()
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.watchdog_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun startForegroundCompat() {
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.watchdog_title))
            .setContentText(getString(R.string.watchdog_text))
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        BlockOverlay.hide()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "app_blocker_watchdog"
        private const val NOTIFICATION_ID = 4102

        fun start(context: Context) {
            val intent = Intent(context, BlockMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BlockMonitorService::class.java))
        }
    }
}
