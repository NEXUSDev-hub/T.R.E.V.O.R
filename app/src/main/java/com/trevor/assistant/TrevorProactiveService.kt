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
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class TrevorProactiveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, baseNotification())
        scope.launch {
            while (isActive) {
                val settings = TrevorSettingsStore.load(applicationContext)
                if (!settings.proactiveEnabled) {
                    delay(10_000)
                    continue
                }
                val interval = if (settings.personality == TrevorPersonality.FOR_YOU && settings.rubbishMode)
                    settings.rubbishIntervalSeconds.toLong()
                else settings.spontaneousFrequencySeconds.toLong()
                delay(interval * 1000L)
                if (!isActive) break
                val message = TrevorProactiveEngine.nextMessage(settings.personality, settings.rubbishMode)
                TrevorStateStore.update { it.copy(lastOutput = "TREVOR • proactive\n$message") }
                if (settings.backgroundNotifications) notifyMessage(message, settings.personality)
            }
        }
    }

    override fun onDestroy() {
        TrevorFloatingOverlay.hide()
        TrevorLiveSession.disconnect()
        scope.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_OVERLAY -> TrevorFloatingOverlay.show(applicationContext)
            ACTION_HIDE_OVERLAY -> TrevorFloatingOverlay.hide()
            ACTION_LIVE_START -> promoteLive()
        }
        return START_STICKY
    }

    private fun promoteLive() {
        if (Build.VERSION.SDK_INT >= 29) ServiceCompat.startForeground(this, NOTIFICATION_ID, baseNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "TREVOR proactive assistant", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Optional proactive TREVOR messages." }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun baseNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(TrevorIdentity.NAME)
            .setContentText("Proactive assistant is enabled")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    private fun notifyMessage(message: String, personality: TrevorPersonality) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("${TrevorIdentity.NAME} • ${personality.label}")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify((System.currentTimeMillis() % 100000).toInt(), notification)
    }

    companion object {
        const val ACTION_SHOW_OVERLAY = "com.trevor.assistant.SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY = "com.trevor.assistant.HIDE_OVERLAY"
        const val ACTION_LIVE_START = "com.trevor.assistant.LIVE_START"
        private const val CHANNEL_ID = "trevor_proactive"
        private const val NOTIFICATION_ID = 731
    }
}

object TrevorProactiveEngine {
    private val professional = listOf(
        "I am monitoring the assistant state. No action is required right now.",
        "Everything is quiet. I will only interrupt if something useful needs your attention.",
        "TREVOR is standing by."
    )
    private val chaotic = listOf(
        "Important update: I have absolutely no update. Carry on.",
        "I have inspected the situation. The situation remains suspiciously situation-shaped.",
        "TREVOR has entered maximum nonsense containment."
    )
    private val deadpool = listOf(
        "Sir. I have news. It is mostly unnecessary, but I have news.",
        "I checked the imaginary dashboard. It is very dashboard-y today.",
        "Your highly sophisticated digital assistant has once again resisted becoming a toaster."
    )
    private val rubbish = listOf(
        "Sir, urgent discovery: 🐟",
        "I have concluded that the ceiling is probably doing its job.",
        "Breaking news: a potato remains a potato.",
        "TREVOR has achieved absolutely nothing. Productivity is terrifying."
    )

    fun nextMessage(personality: TrevorPersonality, rubbishMode: Boolean): String {
        val pool = when {
            personality == TrevorPersonality.FOR_YOU && rubbishMode -> rubbish
            personality == TrevorPersonality.CHAOTIC -> chaotic
            personality == TrevorPersonality.DEADPOOL -> deadpool
            else -> professional
        }
        return pool.random()
    }
}
