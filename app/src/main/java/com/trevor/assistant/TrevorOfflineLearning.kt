package com.trevor.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import kotlin.math.min

/**
 * TrevorCore's first general-purpose offline learning layer.
 *
 * This is deliberately a bounded, data-driven learner. It never rewrites Kotlin,
 * grants permissions, or executes an action merely because it learned about it.
 *
 * Loop:
 * OBSERVE -> MODEL -> ACT/OUTCOME -> REMEMBER -> UPDATE CONFIDENCE
 */
object TrevorOfflineLearning {
    private const val PREFS = "trevor_offline_learning_v1"
    private const val MAX_KNOWLEDGE = 500
    private const val MAX_EXPERIENCES = 500
    private const val MAX_DOMAINS = 100
    private const val MIN_CONFIDENCE = 0.20

    enum class Outcome { SUCCESS, FAILURE, UNKNOWN }

    data class Knowledge(
        val domain: String,
        val subject: String,
        val predicate: String,
        val value: String,
        val confidence: Double,
        val observations: Int,
        val successes: Int,
        val lastSeen: Long,
        val source: String
    )

    data class Experience(
        val id: String,
        val domain: String,
        val input: String,
        val action: String,
        val outcome: Outcome,
        val result: String,
        val timestamp: Long
    )

    data class Domain(
        val id: String,
        val observations: Int,
        val successfulExperiences: Int,
        val failedExperiences: Int,
        val lastSeen: Long
    )

    data class Snapshot(
        val domains: List<Domain>,
        val knowledge: List<Knowledge>,
        val experiences: List<Experience>
    )

    fun observe(
        context: Context,
        input: String,
        domainHint: String? = null,
        action: String? = null,
        outcome: Outcome = Outcome.UNKNOWN,
        result: String = "",
        source: String = "local"
    ) {
        val clean = input.trim()
        if (clean.isBlank()) return

        val domain = normalizeDomain(domainHint ?: inferDomain(clean))
        val now = System.currentTimeMillis()
        synchronized(this) {
            val p = prefs(context)
            val domains = readDomains(p).toMutableMap()
            val oldDomain = domains[domain]
            domains[domain] = Domain(
                domain,
                (oldDomain?.observations ?: 0) + 1,
                (oldDomain?.successfulExperiences ?: 0) + if (outcome == Outcome.SUCCESS) 1 else 0,
                (oldDomain?.failedExperiences ?: 0) + if (outcome == Outcome.FAILURE) 1 else 0,
                now
            )

            val knowledge = readKnowledge(p).toMutableMap()
            extractCandidateKnowledge(clean, domain, source).forEach { candidate ->
                val key = knowledgeKey(candidate.domain, candidate.subject, candidate.predicate)
                val old = knowledge[key]
                val observations = (old?.observations ?: 0) + 1
                val sameValue = old?.value == candidate.value
                val successes = (old?.successes ?: 0) + if (sameValue) 1 else 0
                knowledge[key] = candidate.copy(
                    confidence = updateConfidence(old?.confidence ?: 0.30, sameValue, observations),
                    observations = observations,
                    successes = successes,
                    lastSeen = now
                )
            }

            val experiences = readExperiences(p).toMutableList()
            if (action != null || outcome != Outcome.UNKNOWN) {
                experiences += Experience(
                    UUID.randomUUID().toString(),
                    domain,
                    clean.take(2000),
                    action.orEmpty().take(500),
                    outcome,
                    result.take(2000),
                    now
                )
            }

            writeDomains(p, domains.values.sortedByDescending { it.lastSeen }.take(MAX_DOMAINS))
            writeKnowledge(p, knowledge.values.sortedByDescending { it.lastSeen }.take(MAX_KNOWLEDGE))
            writeExperiences(p, experiences.takeLast(MAX_EXPERIENCES))
        }
    }

    fun recordOutcome(
        context: Context,
        domain: String,
        input: String,
        action: String,
        outcome: Outcome,
        result: String = ""
    ) = observe(context, input, domain, action, outcome, result, "experience")

    fun recall(
        context: Context,
        query: String,
        domainHint: String? = null,
        limit: Int = 8
    ): List<Knowledge> {
        val terms = tokenize(query)
        val domain = domainHint?.let(::normalizeDomain)
        return readKnowledge(prefs(context)).values
            .filter { domain == null || it.domain == domain }
            .filter { it.confidence >= MIN_CONFIDENCE }
            .sortedByDescending { item ->
                val overlap = terms.count {
                    item.subject.lowercase(Locale.ROOT).contains(it) ||
                        item.value.lowercase(Locale.ROOT).contains(it) ||
                        item.predicate.lowercase(Locale.ROOT).contains(it)
                }
                overlap * 10 + item.confidence * 5
            }
            .take(limit.coerceIn(1, 30))
    }

    fun snapshot(context: Context): Snapshot {
        val p = prefs(context)
        return Snapshot(
            readDomains(p).values.sortedByDescending { it.lastSeen },
            readKnowledge(p).values.sortedByDescending { it.confidence },
            readExperiences(p).sortedByDescending { it.timestamp }
        )
    }

    fun summary(context: Context): String {
        val s = snapshot(context)
        if (s.domains.isEmpty()) return "Offline learning is initialized but has not observed enough data yet."
        val top = s.domains.take(6).joinToString(", ") { "${it.id} (${it.observations})" }
        return "Offline learning active. Domains: ${s.domains.size}. Knowledge records: ${s.knowledge.size}. Experiences: ${s.experiences.size}. Top domains: $top."
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private data class Candidate(
        val domain: String,
        val subject: String,
        val predicate: String,
        val value: String,
        val confidence: Double = 0.30,
        val observations: Int = 0,
        val successes: Int = 0,
        val lastSeen: Long = System.currentTimeMillis(),
        val source: String
    )

    private fun extractCandidateKnowledge(input: String, domain: String, source: String): List<Candidate> {
        val out = mutableListOf<Candidate>()
        val normalized = input.replace(Regex("\\s+"), " ").trim()
        val patterns = listOf(
            Regex("""^(.{2,80}?)\\s+is\\s+(.{2,200})$""", RegexOption.IGNORE_CASE),
            Regex("""^(.{2,80}?)\\s+means\\s+(.{2,200})$""", RegexOption.IGNORE_CASE),
            Regex("""^(.{2,80}?)\\s+has\\s+(.{2,200})$""", RegexOption.IGNORE_CASE),
            Regex("""^(.{2,80}?)\\s+uses\\s+(.{2,200})$""", RegexOption.IGNORE_CASE)
        )
        patterns.forEachIndexed { index, regex ->
            regex.find(normalized)?.let {
                out += Candidate(
                    domain,
                    cleanSubject(it.groupValues[1]),
                    listOf("is", "means", "has", "uses")[index],
                    it.groupValues[2].take(200),
                    source = source
                )
            }
        }
        return out.distinctBy { knowledgeKey(it.domain, it.subject, it.predicate) }
    }

    private fun inferDomain(input: String): String {
        val t = input.lowercase(Locale.ROOT)
        return when {
            Regex("""\\bsfs2\\b|spaceflight simulator""").containsMatchIn(t) -> "sfs2"
            Regex("""\\bchrome\\b|browser|website|web page""").containsMatchIn(t) -> "chrome"
            Regex("""\\byoutube\\b|video|channel""").containsMatchIn(t) -> "youtube"
            Regex("""\\binstagram\\b|reel|story""").containsMatchIn(t) -> "instagram"
            Regex("""\\bterminal\\b|shell|command""").containsMatchIn(t) -> "terminal"
            else -> "general"
        }
    }

    private fun normalizeDomain(value: String): String =
        value.trim().lowercase(Locale.ROOT).replace(Regex("""[^a-z0-9._-]"""), "_").take(64).ifBlank { "general" }

    private fun cleanSubject(value: String): String =
        value.trim().trim('.', ':', ' ', '\t').take(120).lowercase(Locale.ROOT)

    private fun tokenize(value: String): List<String> =
        value.lowercase(Locale.ROOT).split(Regex("""[^a-z0-9]+""")).filter { it.length > 2 }.distinct().take(32)

    private fun updateConfidence(old: Double, sameValue: Boolean, observations: Int): Double {
        val evidence = min(0.75, observations / 20.0)
        val direction = if (sameValue) 0.08 else -0.06
        return (old + direction + evidence * 0.02).coerceIn(0.05, 0.99)
    }

    private fun knowledgeKey(domain: String, subject: String, predicate: String) =
        "$domain|$subject|$predicate"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readDomains(p: android.content.SharedPreferences): MutableMap<String, Domain> {
        val a = runCatching { JSONArray(p.getString("domains", "[]") ?: "[]") }.getOrDefault(JSONArray())
        return buildMap {
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val d = Domain(o.optString("id"), o.optInt("observations"), o.optInt("successfulExperiences"), o.optInt("failedExperiences"), o.optLong("lastSeen"))
                if (d.id.isNotBlank()) put(d.id, d)
            }
        }.toMutableMap()
    }

    private fun writeDomains(p: android.content.SharedPreferences, values: List<Domain>) {
        val a = JSONArray()
        values.forEach { d -> a.put(JSONObject().put("id", d.id).put("observations", d.observations).put("successfulExperiences", d.successfulExperiences).put("failedExperiences", d.failedExperiences).put("lastSeen", d.lastSeen)) }
        p.edit().putString("domains", a.toString()).apply()
    }

    private fun readKnowledge(p: android.content.SharedPreferences): MutableMap<String, Knowledge> {
        val a = runCatching { JSONArray(p.getString("knowledge", "[]") ?: "[]") }.getOrDefault(JSONArray())
        return buildMap {
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val k = Knowledge(o.optString("domain"), o.optString("subject"), o.optString("predicate"), o.optString("value"), o.optDouble("confidence", 0.3), o.optInt("observations"), o.optInt("successes"), o.optLong("lastSeen"), o.optString("source", "unknown"))
                put(knowledgeKey(k.domain, k.subject, k.predicate), k)
            }
        }.toMutableMap()
    }

    private fun writeKnowledge(p: android.content.SharedPreferences, values: List<Knowledge>) {
        val a = JSONArray()
        values.forEach { k -> a.put(JSONObject().put("domain", k.domain).put("subject", k.subject).put("predicate", k.predicate).put("value", k.value).put("confidence", k.confidence).put("observations", k.observations).put("successes", k.successes).put("lastSeen", k.lastSeen).put("source", k.source)) }
        p.edit().putString("knowledge", a.toString()).apply()
    }

    private fun readExperiences(p: android.content.SharedPreferences): MutableList<Experience> {
        val a = runCatching { JSONArray(p.getString("experiences", "[]") ?: "[]") }.getOrDefault(JSONArray())
        return buildList {
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                add(Experience(o.optString("id"), o.optString("domain"), o.optString("input"), o.optString("action"), runCatching { Outcome.valueOf(o.optString("outcome", "UNKNOWN")) }.getOrDefault(Outcome.UNKNOWN), o.optString("result"), o.optLong("timestamp")))
            }
        }.toMutableList()
    }

    private fun writeExperiences(p: android.content.SharedPreferences, values: List<Experience>) {
        val a = JSONArray()
        values.forEach { e -> a.put(JSONObject().put("id", e.id).put("domain", e.domain).put("input", e.input).put("action", e.action).put("outcome", e.outcome.name).put("result", e.result).put("timestamp", e.timestamp)) }
        p.edit().putString("experiences", a.toString()).apply()
    }
}
