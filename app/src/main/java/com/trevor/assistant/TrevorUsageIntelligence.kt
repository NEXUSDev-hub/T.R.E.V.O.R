package com.trevor.assistant

import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

data class TrevorUsagePattern(val packageName: String, val minutes: Int, val sharePercent: Int)
data class TrevorUsageAnalysis(val available: Boolean, val summary: String, val topApps: List<TrevorUsagePattern>)

object TrevorUsageIntelligence {
    fun hasAccess(context: Context): Boolean {
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return false
        val now = System.currentTimeMillis()
        return manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 86400000L, now).any { it.getTotalTimeInForeground() > 0L }
    }
    fun analyze(context: Context, days: Int = 7): TrevorUsageAnalysis {
        if (!hasAccess(context)) return TrevorUsageAnalysis(false, "Usage Access is not enabled.", emptyList())
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return TrevorUsageAnalysis(false, "Usage statistics are unavailable.", emptyList())
        val safeDays = days.coerceIn(1, 30)
        val end = System.currentTimeMillis()
        val start = end - safeDays * 86400000L
        val stats = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end).filter { it.getTotalTimeInForeground() > 0L }
        val total = stats.sumOf { it.getTotalTimeInForeground() }.coerceAtLeast(1L)
        val top = stats.groupBy { it.packageName }.map { entry ->
            val ms = entry.value.sumOf { it.getTotalTimeInForeground() }
            TrevorUsagePattern(entry.key, (ms / 60000L).toInt(), ((ms.toDouble() / total) * 100.0).roundToInt())
        }.sortedByDescending { it.minutes }.take(10)
        val peak = stats.groupBy { Calendar.getInstance().apply { timeInMillis = it.lastTimeUsed }.get(Calendar.HOUR_OF_DAY) }
            .maxByOrNull { it.value.sumOf(UsageStats::totalTimeInForeground) }?.key
        val summary = buildString {
            append("Analysed ").append(safeDays).append(" days of local app-usage data.")
            if (top.isNotEmpty()) append(" Most-used app: ").append(top.first().packageName).append(" (~").append(top.first().minutes).append(" min).")
            if (peak != null) append(" Peak recorded hour: ").append(String.format(Locale.ROOT, "%02d:00", peak)).append(".")
        }
        return TrevorUsageAnalysis(true, summary, top)
    }
    fun summary(context: Context): String {
        val a = analyze(context)
        if (!a.available) return a.summary
        return a.summary + "\nTop apps:\n" + a.topApps.joinToString("\n") { "• " + it.packageName + ": " + it.minutes + " min (" + it.sharePercent + "%)" }
    }
}
