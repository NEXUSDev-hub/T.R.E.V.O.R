package com.trevor.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.content.pm.ServiceInfo
import androidx.core.app.ServiceCompat

/**
 * Explicit foreground service only for capabilities that genuinely need it,
 * currently Gemini Live microphone sessions. Proactive/background work uses
 * WorkManager instead of a permanent foreground loop.
 */
class TrevorProactiveService : Service() {
    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_OVERLAY -> TrevorFloatingOverlay.show(applicationContext)
            ACTION_HIDE_OVERLAY -> TrevorFloatingOverlay.hide()
            ACTION_LIVE_START -> promoteLive()
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun promoteLive() {
        val notification = baseNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        TrevorFloatingOverlay.hide()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "TREVOR Live",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun baseNotification(): Notification =
        android.app.Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(TrevorIdentity.NAME + " Live")
            .setContentText("TREVOR Live is using the microphone.")
            .setOngoing(true)
            .build()

    companion object {
        const val ACTION_SHOW_OVERLAY = "com.trevor.assistant.SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY = "com.trevor.assistant.HIDE_OVERLAY"
        const val ACTION_LIVE_START = "com.trevor.assistant.LIVE_START"
        private const val CHANNEL_ID = "trevor_live"
        private const val NOTIFICATION_ID = 731
    }
}
