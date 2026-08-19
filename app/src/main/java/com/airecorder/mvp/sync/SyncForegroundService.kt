package com.airecorder.mvp.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.net.wifi.WifiManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.airecorder.mvp.RecorderApplication

class SyncForegroundService : Service() {
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification(this, "Preparing recorder sync"))
        acquireTransferLocks()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ can enforce a data-transfer foreground-service time limit.
        // Cancel through the application-scoped owner so the sync UI leaves its
        // running state and partial FTP files remain resumable.
        (application as? RecorderApplication)?.container?.syncSession?.cancelForSystemTimeout()
        stopSelf(startId)
    }

    override fun onDestroy() {
        releaseTransferLocks()
        super.onDestroy()
    }

    private fun acquireTransferLocks() {
        if (cpuWakeLock == null) {
            cpuWakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:recorder-sync")
                .apply { setReferenceCounted(false) }
        }
        if (wifiLock == null) {
            wifiLock = getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$packageName:recorder-sync")
                .apply { setReferenceCounted(false) }
        }
        if (cpuWakeLock?.isHeld != true) cpuWakeLock?.acquire()
        if (wifiLock?.isHeld != true) wifiLock?.acquire()
    }

    private fun releaseTransferLocks() {
        if (wifiLock?.isHeld == true) wifiLock?.release()
        if (cpuWakeLock?.isHeld == true) cpuWakeLock?.release()
    }

    companion object {
        private const val CHANNEL_ID = "recorder_sync"
        private const val NOTIFICATION_ID = 1001
        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, SyncForegroundService::class.java))
        fun update(context: Context, contentText: String, progress: Int? = null) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Recorder sync", NotificationManager.IMPORTANCE_LOW))
            manager.notify(NOTIFICATION_ID, notification(context, contentText, progress))
        }

        private fun notification(context: Context, contentText: String, progress: Int? = null) =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("Syncing recorder")
                .setContentText(contentText)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .apply {
                    progress?.let { setProgress(100, it.coerceIn(0, 100), false) }
                }
                .build()
    }
}
