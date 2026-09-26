package com.trevor.assistant

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/**
 * Learns reusable UI workflows from observed accessibility trees and outcomes.
 * It stores semantic targets, not screen coordinates, whenever possible.
 */
object TrevorUiLearning {
    private const val PREFS = "trevor_ui_learning_v1"
    private const val MAX_WORKFLOWS = 200
    private const val MAX_STEPS = 20

    data class UiStep(
        val action: String,
        val targetText: String = "",
        val contentDescription: String = "",
        val packageName: String = "",
        val x: Int = -1,
        val y: Int = -1
    )

    data class Workflow(
        val id: String,
        val appPackage: String,
        val goal: String,
        val steps: List<UiStep>,
        val successes: Int,
        val failures: Int,
        val lastSeen: Long
    )

    fun startTeaching(context: Context, goal: String, appPackage: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("teaching", true).putString("teachingGoal", goal.trim().take(300))
            .putString("teachingPackage", appPackage).putString("teachingSteps", "[]").apply()
    }

    fun appendTeachingStep(context: Context, step: UiStep) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.getBoolean("teaching", false)) return
        val a = runCatching { JSONArray(p.getString("teachingSteps", "[]") ?: "[]") }.getOrDefault(JSONArray())
        if (a.length() >= MAX_STEPS) return
        a.put(JSONObject().put("action", step.action).put("targetText", step.targetText)
            .put("description", step.contentDescription).put("package", step.packageName)
            .put("x", step.x).put("y", step.y))
        p.edit().putString("teachingSteps", a.toString()).apply()
    }

    fun stopTeaching(context: Context, success: Boolean): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.getBoolean("teaching", false)) return false
        val goal = p.getString("teachingGoal", "") ?: ""
        val pkg = p.getString("teachingPackage", "") ?: ""
        val a = runCatching { JSONArray(p.getString("teachingSteps", "[]") ?: "[]") }.getOrDefault(JSONArray())
        val steps = buildList {
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                add(UiStep(o.optString("action"), o.optString("targetText"), o.optString("description"),
                    o.optString("package"), o.optInt("x", -1), o.optInt("y", -1)))
            }
        }
        p.edit().putBoolean("teaching", false).remove("teachingSteps").apply()
        if (goal.isBlank() || pkg.isBlank() || steps.isEmpty()) return false
        record(context, pkg, goal, steps, success)
        return true
    }

    fun isTeaching(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("teaching", false)

    fun record(
        context: Context,
        appPackage: String,
        goal: String,
        steps: List<UiStep>,
        success: Boolean
    ) {
        if (appPackage.isBlank() || goal.isBlank() || steps.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val all = read(prefs).toMutableMap()
        val key = key(appPackage, goal, steps)
        val old = all[key]
        all[key] = Workflow(
            old?.id ?: UUID.randomUUID().toString(),
            appPackage,
            goal.trim().take(300),
            steps.take(MAX_STEPS),
            (old?.successes ?: 0) + if (success) 1 else 0,
            (old?.failures ?: 0) + if (!success) 1 else 0,
            System.currentTimeMillis()
        )
        write(prefs, all.values.sortedByDescending { it.lastSeen }.take(MAX_WORKFLOWS))
    }

    fun find(context: Context, appPackage: String, goal: String): List<Workflow> {
        val terms = tokenize(goal)
        if (appPackage.isBlank() || terms.isEmpty()) return emptyList()
        return read(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)).values
            .filter { it.appPackage == appPackage }
            .map { workflow ->
                val overlap = terms.count { term ->
                    workflow.goal.lowercase(Locale.ROOT).contains(term)
                }
                workflow to overlap
            }
            .filter { (_, overlap) -> overlap > 0 }
            .sortedWith(
                compareByDescending<Pair<Workflow, Int>> { it.second * 10 + it.first.successes * 2 - it.first.failures }
                    .thenByDescending { it.first.lastSeen }
            )
            .map { it.first }
            .take(5)
    }

    fun describeCurrentScreen(root: AccessibilityNodeInfo?): List<UiStep> {
        if (root == null) return emptyList()
        val result = mutableListOf<UiStep>()
        fun walk(node: AccessibilityNodeInfo) {
            val text = node.text?.toString()?.trim().orEmpty().take(160)
            val desc = node.contentDescription?.toString()?.trim().orEmpty().take(160)
            val pkg = node.packageName?.toString().orEmpty()
            if ((text.isNotBlank() || desc.isNotBlank()) &&
                (node.isClickable || node.isFocusable || node.isScrollable)
            ) {
                val r = android.graphics.Rect()
                node.getBoundsInScreen(r)
                result += UiStep("CLICK", text, desc, pkg, r.centerX(), r.centerY())
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(::walk)
        }
        walk(root)
        return result.distinctBy { it.targetText.lowercase(Locale.ROOT) + "|" + it.contentDescription.lowercase(Locale.ROOT) }
            .take(100)
    }

    fun clear(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()

    private fun key(pkg: String, goal: String, steps: List<UiStep>) =
        pkg + "|" + goal.lowercase(Locale.ROOT).trim() + "|" +
            steps.joinToString(";") { it.action + "|" + it.targetText + "|" + it.contentDescription }

    private fun tokenize(s: String) =
        s.lowercase(Locale.ROOT).split(Regex("[^a-z0-9]+")).filter { it.length > 2 }.distinct().take(20)

    private fun read(prefs: android.content.SharedPreferences): MutableMap<String, Workflow> {
        val a = runCatching { JSONArray(prefs.getString("workflows", "[]") ?: "[]") }.getOrDefault(JSONArray())
        return buildMap {
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val steps = buildList {
                    val sa = o.optJSONArray("steps") ?: JSONArray()
                    for (j in 0 until sa.length()) {
                        val s = sa.optJSONObject(j) ?: continue
                        add(UiStep(
                            s.optString("action"), s.optString("targetText"), s.optString("description"),
                            s.optString("package"), s.optInt("x", -1), s.optInt("y", -1)
                        ))
                    }
                }
                val w = Workflow(o.optString("id"), o.optString("appPackage"), o.optString("goal"),
                    steps, o.optInt("successes"), o.optInt("failures"), o.optLong("lastSeen"))
                if (w.id.isNotBlank()) put(w.id, w)
            }
        }.toMutableMap()
    }

    private fun write(prefs: android.content.SharedPreferences, values: List<Workflow>) {
        val a = JSONArray()
        values.forEach { w ->
            val steps = JSONArray()
            w.steps.forEach { s ->
                steps.put(JSONObject().put("action", s.action).put("targetText", s.targetText)
                    .put("description", s.contentDescription).put("package", s.packageName)
                    .put("x", s.x).put("y", s.y))
            }
            a.put(JSONObject().put("id", w.id).put("appPackage", w.appPackage)
                .put("goal", w.goal).put("successes", w.successes).put("failures", w.failures)
                .put("lastSeen", w.lastSeen).put("steps", steps))
        }
        prefs.edit().putString("workflows", a.toString()).apply()
    }
}
