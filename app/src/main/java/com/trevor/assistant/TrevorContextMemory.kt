package com.trevor.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Part 8: proactive context + memory.
 *
 * Context observations are compact, local, and derived from existing Part 2 data.
 * Conversation text is never stored here. Durable user facts require an explicit
 * caller decision and are separately marked as confirmed.
 */
object TrevorContextMemory {
    private const val MAX_CONTEXT_ROWS = 240
    private const val MAX_FACTS = 80
    private const val MAX_FACT_LENGTH = 500
    private const val CONTEXT_RETENTION_DAYS = 30L
    private const val FACT_RETENTION_DAYS = 365L

    data class ContextRecord(
        val fingerprint: String,
        val signature: String,
        val appPackage: String,
        val observedAt: Long
    )

    data class MemoryFact(
        val key: String,
        val value: String,
        val confirmed: Boolean,
        val source: String,
        val updatedAt: Long,
        val confidence: Double,
        val shareWithAi: Boolean = false
    )

    suspend fun recordContext(
        context: Context,
        snapshot: TrevorDeviceContextLearning.ContextSnapshot,
        foregroundPackage: String?
    ) = withContext(Dispatchers.IO) {
        val safePackage = foregroundPackage?.takeIf { it.isNotBlank() && it != context.packageName } ?: "UNKNOWN"
        val signature = snapshot.signature()
        val fingerprint = hash(signature + "|" + safePackage)
        val now = System.currentTimeMillis()
        TrevorDatabase.get(context).memoryDao().insertContext(
            TrevorContextObservation(
                fingerprint = fingerprint,
                signature = signature.take(1000),
                appPackage = safePackage.take(200),
                observedAt = now
            )
        )
        prune(context, now)
    }

    suspend fun recentContext(context: Context, limit: Int = 12): List<ContextRecord> =
        withContext(Dispatchers.IO) {
            TrevorDatabase.get(context).memoryDao().recentContext(limit.coerceIn(1, 50)).map {
                ContextRecord(it.fingerprint, it.signature, it.appPackage, it.observedAt)
            }
        }

    suspend fun rememberConfirmedFact(
        context: Context,
        key: String,
        value: String,
        source: String = "user"
    ) = withContext(Dispatchers.IO) {
        val safeKey = key.trim().take(120)
        val safeValue = value.trim().take(MAX_FACT_LENGTH)
        require(safeKey.isNotBlank()) { "Memory key cannot be blank" }
        require(safeValue.isNotBlank()) { "Memory value cannot be blank" }
        TrevorDatabase.get(context).memoryDao().upsertFact(
            TrevorMemoryFact(
                key = safeKey,
                value = safeValue,
                confirmed = true,
                source = source.trim().take(80).ifBlank { "user" },
                updatedAt = System.currentTimeMillis(),
                confidence = 1.0,
                shareWithAi = false
            )
        )
        prune(context, System.currentTimeMillis())
    }

    suspend fun confirmedFacts(context: Context, limit: Int = 20): List<MemoryFact> =
        withContext(Dispatchers.IO) {
            TrevorDatabase.get(context).memoryDao().confirmedFacts(limit.coerceIn(1, MAX_FACTS)).map {
                MemoryFact(it.key, it.value, it.confirmed, it.source, it.updatedAt, it.confidence, it.shareWithAi)
            }
        }

    suspend fun setFactAiSharing(context: Context, key: String, allowed: Boolean) = withContext(Dispatchers.IO) {\n        val dao = TrevorDatabase.get(context).memoryDao()\n        val fact = dao.confirmedFacts(MAX_FACTS).firstOrNull { it.key == key } ?: return@withContext\n        dao.upsertFact(fact.copy(shareWithAi = allowed))\n    }\n\n    suspend fun forgetFact(context: Context, key: String) = withContext(Dispatchers.IO) {
        TrevorDatabase.get(context).memoryDao().deleteFact(key)
    }

    suspend fun buildProactiveContext(
        context: Context,
        pendingTasks: List<TrevorTask>,
        limit: Int = 8
    ): String = withContext(Dispatchers.IO) {
        val snapshot = TrevorDeviceContextLearning.snapshot(context)
        val recent = recentContext(context, limit)
        val facts = confirmedFacts(context, 8)
        buildString {
            append("device=")
                .append(snapshot.network)
                .append("; validatedNetwork=").append(snapshot.networkValidated)
                .append("; metered=").append(snapshot.metered)
                .append("; batteryBucket=").append(snapshot.batteryPercent / 5)
                .append("; charging=").append(snapshot.charging)
                .append("; screenInteractive=").append(snapshot.screenInteractive)
                .append("; orientation=").append(snapshot.orientation)
            val next = pendingTasks.filter { it.status != "COMPLETED" }.minByOrNull { it.triggerAt }
            append("; activeTasks=").append(pendingTasks.count { it.status != "COMPLETED" })
            append("; nextTaskDue=").append(next?.triggerAt ?: 0L)
            if (recent.isNotEmpty()) {
                append("; recurringContext=").append(
                    recent.take(4).joinToString(",") { it.appPackage + "@" + it.observedAt }
                )
            }
            if (facts.isNotEmpty()) {
                append("; confirmedMemory=").append(
                    facts.joinToString(" | ") { it.key + "=" + it.value.take(180) }
                )
            }
        }
    }

    private suspend fun prune(context: Context, now: Long) {
        val contextCutoff = now - TimeUnit.DAYS.toMillis(CONTEXT_RETENTION_DAYS)
        val factCutoff = now - TimeUnit.DAYS.toMillis(FACT_RETENTION_DAYS)
        val dao = TrevorDatabase.get(context).memoryDao()
        dao.pruneContextOlderThan(contextCutoff)
        dao.pruneFactsOlderThan(factCutoff)
        dao.pruneContextToLimit(MAX_CONTEXT_ROWS)
        dao.pruneFactsToLimit(MAX_FACTS)
    }

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)
}
