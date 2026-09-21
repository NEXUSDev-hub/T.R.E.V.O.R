package com.trevor.assistant

import android.app.*
import android.content.*
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.TimeUnit

object TrevorNotificationCenter {
    const val CHANNEL_ID = "trevor_intelligence"
    const val TASK_ID = "task_id"
    const val ACTION_DONE = "com.trevor.assistant.TASK_DONE"
    const val ACTION_SNOOZE = "com.trevor.assistant.TASK_SNOOZE"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "TREVOR intelligence", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    fun post(context: Context, title: String, message: String, task: TrevorTask? = null) {
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 1001, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(open).setAutoCancel(true)
        task?.let {
            val done = PendingIntent.getBroadcast(
                context, it.id.hashCode(),
                Intent(ACTION_DONE).putExtra(TASK_ID, it.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val snooze = PendingIntent.getBroadcast(
                context, it.id.hashCode() + 1,
                Intent(ACTION_SNOOZE).putExtra(TASK_ID, it.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_menu_save, "Done", done)
                .addAction(android.R.drawable.ic_menu_recent_history, "Snooze 10m", snooze)
        }
        context.getSystemService(NotificationManager::class.java)
            .notify(task?.id?.hashCode() ?: System.currentTimeMillis().toInt(), builder.build())
    }
}

object TrevorTaskEngine {
    private const val PREFIX = "trevor_task_"

    suspend fun schedule(context: Context, title: String, action: String, triggerAt: Long): TrevorTask {
        val task = TrevorTask(UUID.randomUUID().toString(), title, action, triggerAt)
        TrevorPersistentMemory.saveTask(context, task)
        scheduleExisting(context, task)
        return task
    }

    fun reschedulePending(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            TrevorPersistentMemory.pendingTasks(context).forEach { task ->
                if (task.triggerAt > System.currentTimeMillis()) scheduleExisting(context, task)
            }
        }
    }

    suspend fun complete(context: Context, id: String) {
        TrevorPersistentMemory.setTaskStatus(context, id, "COMPLETED")
        WorkManager.getInstance(context).cancelUniqueWork(PREFIX + id)
    }

    suspend fun snooze(context: Context, id: String) {
        val task = TrevorPersistentMemory.pendingTasks(context).firstOrNull { it.id == id } ?: return
        TrevorPersistentMemory.setTaskStatus(context, id, "SNOOZED")
        val replacement = task.copy(triggerAt = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(10), status = "PENDING")
        TrevorPersistentMemory.saveTask(context, replacement)
        scheduleExisting(context, replacement)
    }

    private fun scheduleExisting(context: Context, task: TrevorTask) {
        val request = OneTimeWorkRequestBuilder<TrevorTaskWorker>()
            .setInitialDelay((task.triggerAt - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("task_id" to task.id))
            .addTag("trevor_task")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(PREFIX + task.id, ExistingWorkPolicy.REPLACE, request)
    }
}

class TrevorTaskWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("task_id") ?: return Result.failure()
        val task = TrevorPersistentMemory.pendingTasks(applicationContext).firstOrNull { it.id == id } ?: return Result.success()
        TrevorNotificationCenter.post(
            applicationContext,
            "TREVOR • " + TrevorNotificationPersonality.title(applicationContext),
            TrevorNotificationPersonality.taskMessage(applicationContext, task),
            task
        )
        TrevorPersistentMemory.setTaskStatus(applicationContext, id, "NOTIFIED")
        return Result.success()
    }
}

class TrevorTaskActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(TrevorNotificationCenter.TASK_ID) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    TrevorNotificationCenter.ACTION_DONE -> TrevorTaskEngine.complete(context, id)
                    TrevorNotificationCenter.ACTION_SNOOZE -> TrevorTaskEngine.snooze(context, id)
                }
            } finally { pending.finish() }
        }
    }
}

object TrevorBackgroundScheduler {
    private const val WORK = "trevor_proactive_intelligence"

    fun ensureScheduled(context: Context) {
        val settings = TrevorSettingsStore.load(context)
        if (!settings.backgroundNotifications) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<TrevorProactiveWorker>(30, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
        TrevorTaskEngine.reschedulePending(context)
    }
}

class TrevorProactiveWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val settings = TrevorSettingsStore.load(applicationContext)
        if (!settings.proactiveEnabled || !settings.backgroundNotifications) return Result.success()
        val pending = TrevorPersistentMemory.pendingTasks(applicationContext)
        val conversationId = applicationContext.getSharedPreferences("trevor_runtime", Context.MODE_PRIVATE)
            .getString("conversation_id", null)
        val recent = conversationId?.let { TrevorPersistentMemory.recentConversation(applicationContext, it, 4) }.orEmpty()
        val message = TrevorProactiveIntelligence.compose(settings.personality, pending, recent)
        TrevorNotificationCenter.post(
            applicationContext,
            "TREVOR • " + TrevorNotificationPersonality.title(applicationContext),
            message
        )
        return Result.success()
    }
}

object TrevorProactiveIntelligence {
    fun compose(personality: TrevorPersonality, pending: List<TrevorTask>, recent: List<TrevorConversationMessage>): String {
        val overdue = pending.count { it.triggerAt <= System.currentTimeMillis() }
        return when {
            overdue > 0 -> "You have " + overdue + " scheduled task" + if (overdue == 1) "" else "s" + " waiting for attention."
            pending.isNotEmpty() -> "I have " + pending.size + " upcoming task" + if (pending.size == 1) "" else "s" + " queued."
            recent.isNotEmpty() -> when (personality) {
                TrevorPersonality.DEADPOOL -> "Chat memory is intact. No pending tasks. The chaos department is currently unemployed."
                TrevorPersonality.CHAOTIC -> "Memory is online, tasks are quiet, and the situation remains suspiciously under control."
                TrevorPersonality.FOR_YOU -> "I checked your current TREVOR state. Nothing needs your attention right now."
                else -> "TREVOR checked the current state. Nothing needs your attention right now."
            }
            else -> "TREVOR is standing by. No scheduled task needs your attention."
        }
    }
}

object TrevorNotificationPersonality {
    fun title(context: Context): String = TrevorSettingsStore.load(context).personality.label

    fun taskMessage(context: Context, task: TrevorTask): String = when (TrevorSettingsStore.load(context).personality) {
        TrevorPersonality.DEADPOOL -> "Reminder: " + task.title + ". Yes, I remembered. Miracles happen."
        TrevorPersonality.CHAOTIC -> "TASK ALERT: " + task.title + ". The calendar gremlins have spoken."
        TrevorPersonality.FOR_YOU -> "For you: " + task.title + "."
        else -> "Scheduled task: " + task.title + "."
    }
}
