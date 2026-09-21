package com.trevor.assistant

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import java.text.DateFormat
import java.util.Date
import java.util.Locale

object TrevorLocalIntelligence {
    fun answer(context: Context, input: String): String? {
        val lower = input.trim().lowercase(Locale.ROOT)
        return when {
            lower in setOf("time", "what time is it", "current time") ||
                lower.contains("what time is it") || lower.contains("current time") ->
                "Local time: " + DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date())
            lower == "date" || lower.contains("today's date") || lower.contains("todays date") ->
                "Today: " + DateFormat.getDateInstance(DateFormat.FULL).format(Date())
            lower == "day" || lower.contains("what day is it") ->
                "Today is " + DateFormat.getDateInstance(DateFormat.FULL).format(Date())
            lower == "system info" || lower == "device info" || lower.contains("phone specs") ->
                deviceInfo(context)
            lower in setOf("battery", "battery status", "how much battery") ->
                batteryInfo(context)
            lower in setOf("memory status", "show memory", "memory") ->
                memoryInfo(context)
            lower in setOf("pending tasks", "show tasks", "my tasks", "tasks") ->
                taskInfo(context)
            lower == "uptime" || lower.contains("how long has the phone been running") ->
                "Device uptime: " + formatDuration(android.os.SystemClock.elapsedRealtime())
            lower in setOf("local help", "offline help", "what can you do offline", "what can you do locally") ->
                "Offline TREVOR can answer time/date/day, device and battery status, memory/task status, uptime, dictionary lookups, calculations, percentages and common unit conversions. No AI provider is required for those."
            else -> null
        }
    }

    private fun deviceInfo(context: Context): String {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        return "Device: ${Build.MANUFACTURER} ${Build.MODEL}" +
            "\nAndroid: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})" +
            "\nRAM available: ${memory.availMem / (1024 * 1024)} MB" +
            "\nApp: T.R.E.V.O.R ${TrevorVersion.label(context)}"
    }

    private fun batteryInfo(context: Context): String {
        val battery = context.getSystemService(BatteryManager::class.java)
        val percent = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val status = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) ?: -1
        val state = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not charging"
            else -> "unknown"
        }
        return if (percent >= 0) "Battery: $percent% ($state)" else "Battery status: $state"
    }

    private fun memoryInfo(context: Context): String {
        val conversationId = context.getSharedPreferences("trevor_runtime", Context.MODE_PRIVATE)
            .getString("conversation_id", null)
        return "Local memory is enabled. Room stores conversation history, approved long-term memories, project context and tasks." +
            if (conversationId != null) " Current conversation memory is active." else " No conversation has been initialized yet."
    }

    private fun taskInfo(context: Context): String {
        val conversation = context.getSharedPreferences("trevor_runtime", Context.MODE_PRIVATE)
            .getString("conversation_id", null)
        return "Task scheduling is active." +
            if (conversation != null) " TREVOR can create, persist, notify, complete and snooze tasks." else " No active conversation is initialized yet."
    }

    private fun formatDuration(milliseconds: Long): String {
        var seconds = milliseconds / 1000
        val days = seconds / 86400
        seconds %= 86400
        val hours = seconds / 3600
        seconds %= 3600
        val minutes = seconds / 60
        seconds %= 60
        return buildString {
            if (days > 0) append(days).append("d ")
            if (hours > 0 || days > 0) append(hours).append("h ")
            if (minutes > 0 || hours > 0 || days > 0) append(minutes).append("m ")
            append(seconds).append("s")
        }.trim()
    }
}
