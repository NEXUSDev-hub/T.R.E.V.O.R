package com.trevor.assistant

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat

object TrevorAutomationNotificationHelper {
    private const val CHANNEL_ID = "trevor_tasks"
    const val ACTION_COMPLETE = "com.trevor.assistant.TASK_COMPLETE"
    const val ACTION_SNOOZE = "com.trevor.assistant.TASK_SNOOZE"
    const val EXTRA_TASK_ID = "task_id"
    const val EXTRA_DELAY_MILLIS = "delay_millis"

    fun show(context: Context, task: TrevorAutomationTask, message: String) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val manager = context.getSystemService(NotificationManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "TREVOR tasks", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }

        val complete = PendingIntent.getBroadcast(
            context, task.id.hashCode(),
            Intent(context, TrevorTaskActionReceiver::class.java)
                .setAction(ACTION_COMPLETE)
                .putExtra(EXTRA_TASK_ID, task.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val snooze = PendingIntent.getBroadcast(
            context, task.id.hashCode() + 1,
            Intent(context, TrevorTaskActionReceiver::class.java)
                .setAction(ACTION_SNOOZE)
                .putExtra(EXTRA_TASK_ID, task.id)
                .putExtra(EXTRA_DELAY_MILLIS, 15L * 60L * 1000L),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("TREVOR • " + task.title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Done", complete)
            .addAction(android.R.drawable.ic_lock_idle_alarm, "Snooze 15m", snooze)
            .build()

        runCatching { manager.notify(("trevor-task-" + task.id).hashCode(), notification) }
    }
}
