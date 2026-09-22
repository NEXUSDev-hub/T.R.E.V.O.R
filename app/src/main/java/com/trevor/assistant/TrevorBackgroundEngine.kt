package com.trevor.assistant

import android.app.*
import android.content.*
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import kotlinx.coroutines.*
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.TimeUnit

object TrevorNotificationCenter {
    private fun isQuietHours(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour >= 22 || hour < 7
    }

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

    fun post(context: Context, title: String, message: String, task: TrevorTask? = null): Boolean {
        val settings = TrevorSettingsStore.load(context)
        if (settings.quietHours && isQuietHours()) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 1001, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

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
        return true
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
                if (task.status == "PENDING" && task.triggerAt > System.currentTimeMillis()) {
                    scheduleExisting(context, task)
                }
            }
        }
    }

    suspend fun complete(context: Context, id: String) {
        TrevorPersistentMemory.setTaskStatus(context, id, "COMPLETED")
        WorkManager.getInstance(context).cancelUniqueWork(PREFIX + id)
    }

    suspend fun snooze(context: Context, id: String) {
        val task = TrevorPersistentMemory.task(context, id) ?: return
        if (task.status == "COMPLETED") return
        WorkManager.getInstance(context).cancelUniqueWork(PREFIX + id)
        TrevorPersistentMemory.setTaskStatus(context, id, "SNOOZED")
        val replacement = task.copy(
            triggerAt = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(10),
            status = "PENDING"
        )
        TrevorPersistentMemory.saveTask(context, replacement)
        scheduleExisting(context, replacement)
    }

    suspend fun defer(context: Context, id: String, delayMillis: Long) {
        val task = TrevorPersistentMemory.task(context, id) ?: return
        if (task.status == "COMPLETED") return
        val replacement = task.copy(triggerAt = System.currentTimeMillis() + delayMillis, status = "PENDING")
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
        val task = TrevorPersistentMemory.task(applicationContext, id) ?: return Result.success()
        if (task.status != "PENDING") return Result.success()

        val delivered = TrevorNotificationCenter.post(
            applicationContext,
            "TREVOR • " + TrevorNotificationPersonality.title(applicationContext),
            TrevorNotificationPersonality.taskMessage(applicationContext, task),
            task
        )
        if (delivered) {
            TrevorPersistentMemory.setTaskStatus(applicationContext, id, "NOTIFIED")
        } else {
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val delay = if (hour >= 22 || hour < 7) {
                val next = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 7)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                    if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
                }
                next.timeInMillis - System.currentTimeMillis()
            } else {
                TimeUnit.MINUTES.toMillis(15)
            }
            TrevorTaskEngine.defer(applicationContext, id, delay)
        }
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
            } finally {
                pending.finish()
            }
        }
    }
}

object TrevorBackgroundScheduler {
    private const val WORK = "trevor_proactive_intelligence"

    fun ensureScheduled(context: Context) {
        // Silent local behaviour learning runs independently of proactive notifications.
        TrevorBehaviorLearning.ensureBackgroundLearning(context)

        val settings = TrevorSettingsStore.load(context)
        if (!settings.backgroundNotifications || !settings.proactiveEnabled) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<TrevorProactiveWorker>(30, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
        TrevorTaskEngine.reschedulePending(context)
    }
}

class TrevorProactiveWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val settings = TrevorSettingsStore.load(applicationContext)
        if (!settings.proactiveEnabled || !settings.backgroundNotifications || !settings.proactiveNotifications) return Result.success()

        val pending = TrevorPersistentMemory.pendingTasks(applicationContext)
        val conversationId = applicationContext.getSharedPreferences("trevor_runtime", Context.MODE_PRIVATE)
            .getString("conversation_id", null)
        val recent = conversationId?.let {
            TrevorPersistentMemory.recentConversation(applicationContext, it, 6)
        }.orEmpty()

        val decision = TrevorProactiveDecisionEngine.decide(applicationContext, pending, recent)
        if (decision.action != TrevorProactiveDecisionEngine.Action.GENERATE) return Result.success()

        val message = TrevorProactiveDecisionEngine.generate(applicationContext, decision, pending, recent)
        if (message.trim().equals("[NOOP]", ignoreCase = true) || message.isBlank()) return Result.success()

        val delivered = TrevorNotificationCenter.post(
            applicationContext,
            "TREVOR • " + TrevorNotificationPersonality.title(applicationContext),
            message
        )
        if (delivered) TrevorProactiveDecisionEngine.markDelivered(applicationContext, decision)
        return Result.success()
    }
}

object TrevorProactiveIntelligence {
    fun compose(
        context: Context,
        personality: TrevorPersonality,
        pending: List<TrevorTask>,
        recent: List<TrevorConversationMessage>,
        actionable: Boolean
    ): String {
        val now = System.currentTimeMillis()
        val overdue = pending.count { it.triggerAt <= now && it.status != "COMPLETED" }
        val next = pending.filter { it.triggerAt > now }.minByOrNull { it.triggerAt }
        val minutesToNext = next?.let { TimeUnit.MILLISECONDS.toMinutes(it.triggerAt - now) }
        val battery = context.getSystemService(android.os.BatteryManager::class.java)
            ?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

        return when {
            overdue > 0 ->
                "You have $overdue overdue task${if (overdue == 1) "" else "s"} waiting for attention."
            next != null && minutesToNext != null && minutesToNext <= 30 ->
                "Heads-up: ${next.title} is due in about ${minutesToNext.coerceAtLeast(1)} minute${if (minutesToNext == 1L) "" else "s"}."
            actionable && pending.isNotEmpty() ->
                "TREVOR checked your task queue. ${pending.size} task${if (pending.size == 1) "" else "s"} need attention soon."
            recent.isNotEmpty() -> when (personality) {
                TrevorPersonality.DEADPOOL -> "Memory is intact, tasks are quiet, and the chaos department is currently unemployed."
                TrevorPersonality.CHAOTIC -> "Memory is online, tasks are quiet, and the situation remains suspiciously under control."
                TrevorPersonality.FOR_YOU -> if (battery in 0..15) "Low battery detected. Might be a good time to charge the device." else "I checked the current state. Nothing needs your attention right now."
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
