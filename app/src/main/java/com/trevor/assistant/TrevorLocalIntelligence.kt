package com.trevor.assistant

import android.content.Context
import android.os.Build
import java.text.DateFormat
import java.util.Date
import java.util.Locale

object TrevorLocalIntelligence {
    fun answer(context: Context, input: String): String? {
        val lower = input.trim().lowercase(Locale.ROOT)
        return when {
            lower == "time" || lower.contains("what time is it") || lower.contains("current time") ->
                "Local time: " + DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date())
            lower == "date" || lower.contains("what is today's date") || lower.contains("today's date") ->
                "Today: " + DateFormat.getDateInstance(DateFormat.FULL).format(Date())
            lower == "system info" || lower == "device info" || lower.contains("phone specs") ->
                "Device: " + Build.MANUFACTURER + " " + Build.MODEL +
                    "\nAndroid: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")" +
                    "\nApp: T.R.E.V.O.R " + TrevorVersion.label(context)
            lower == "memory status" || lower == "show memory" ->
                "Local memory is enabled. Recent conversation and approved long-term memory are stored on-device."
            lower == "pending tasks" || lower == "show tasks" ->
                "Use TREVOR notifications for scheduled tasks. Pending task scheduling is handled by the background task engine."
            else -> null
        }
    }
}
