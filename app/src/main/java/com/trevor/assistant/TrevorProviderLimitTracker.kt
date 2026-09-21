package com.trevor.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

data class TrevorProviderUsage(
    val minuteRequests: Int,
    val dayRequests: Int,
    val cooldownUntil: Long
)

object TrevorProviderLimitTracker {
    private const val PREFS = "trevor_provider_limits"
    private const val WINDOW_MS = 60_000L
    private const val DAY_MS = 86_400_000L
    private val memory = ConcurrentHashMap<TrevorProviderId, MutableList<Long>>()

    fun allow(context: Context, provider: TrevorProviderId): Boolean {
        val now = System.currentTimeMillis()
        val list = memory.getOrPut(provider) { mutableListOf() }
        synchronized(list) {
            list.removeAll { now - it > WINDOW_MS }
            return now >= cooldown(context, provider)
        }
    }

    fun recordRequest(context: Context, provider: TrevorProviderId) {
        val now = System.currentTimeMillis()
        val list = memory.getOrPut(provider) { mutableListOf() }
        synchronized(list) { list.add(now) }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val dayKey = now / DAY_MS
        val storedDay = prefs.getLong(provider.name + "_day", dayKey)
        val count = if (storedDay == dayKey) prefs.getInt(provider.name + "_count", 0) + 1 else 1
        prefs.edit().putLong(provider.name + "_day", dayKey).putInt(provider.name + "_count", count).apply()
    }

    fun recordFailure(context: Context, provider: TrevorProviderId, error: Throwable?) {
        val text = error?.message.orEmpty()
        val retry = Regex("retryAfter=(\\d+)").find(text)?.groupValues?.getOrNull(1)?.toLongOrNull()
        val delay = retry?.coerceIn(1, 3600)?.times(1000L)
            ?: if (text.contains("429") || text.contains("rate", true) || text.contains("quota", true)) 60_000L else 0L
        if (delay > 0) context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(provider.name + "_cooldown", System.currentTimeMillis() + delay).apply()
    }

    fun usage(context: Context, provider: TrevorProviderId): TrevorProviderUsage {
        val now = System.currentTimeMillis()
        val list = memory[provider].orEmpty()
        val minute = synchronized(list) { list.count { now - it <= WINDOW_MS } }
        val dayKey = now / DAY_MS
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val storedDay = prefs.getLong(provider.name + "_day", dayKey)
        val day = if (storedDay == dayKey) prefs.getInt(provider.name + "_count", 0) else 0
        return TrevorProviderUsage(minute, day, cooldown(context, provider))
    }

    private fun cooldown(context: Context, provider: TrevorProviderId): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(provider.name + "_cooldown", 0L)
}
