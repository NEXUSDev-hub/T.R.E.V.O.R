package com.trevor.assistant

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class TrevorAutomationTaskState { PLANNED, VALIDATED, QUEUED, RUNNING, STEP_COMPLETE, COMPLETED, FAILED, RECOVERING, CANCELLED }

data class TrevorAutomationStep(val action: String, val argument: String = "", val requiresForeground: Boolean = false, val verify: String = "")
data class TrevorAutomationPlan(val title: String, val steps: List<TrevorAutomationStep>, val id: String = UUID.randomUUID().toString())
data class TrevorAutomationTask(val id: String, val title: String, val plan: TrevorAutomationPlan, val state: TrevorAutomationTaskState, val currentStep: Int, val attempts: Int)

object TrevorAutomationEngine {
    private const val PREFS = "trevor_automation_runtime"
    private const val MAX_RETRIES = 2
    private const val WORK_PREFIX = "trevor-automation-"

    fun plan(input: String): TrevorAutomationPlan? {
        val parts = input.trim().split(Regex("\\s*(?:,|;|\\band then\\b|\\bthen\\b)\\s*", RegexOption.IGNORE_CASE)).map(String::trim).filter(String::isNotBlank)
        if (parts.size < 2) return null
        val steps = parts.map { TrevorStructuredPlanner.normalizeAction(it) ?: return null }
        val result = TrevorAutomationPlan("Multi-step Android task", steps)
        return if (TrevorStructuredPlanner.validate(result).valid) result else null
    }

    fun validate(plan: TrevorAutomationPlan): Result<Unit> = TrevorStructuredPlanner.validate(plan).let { v ->
        if (v.valid) Result.success(Unit) else Result.failure(IllegalArgumentException(v.errors.joinToString(" ")))
    }
    fun describe(plan: TrevorAutomationPlan): String = TrevorStructuredPlanner.describe(plan)

    fun enqueue(context: Context, plan: TrevorAutomationPlan): UUID {
        validate(plan).getOrThrow()
        saveTask(context, TrevorAutomationTask(plan.id, plan.title, plan, TrevorAutomationTaskState.QUEUED, 0, 0))
        val request = OneTimeWorkRequestBuilder<TrevorAutomationWorker>()
            .setInputData(Data.Builder().putString("task_id", plan.id).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .addTag(WORK_PREFIX + plan.id).build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_PREFIX + plan.id, ExistingWorkPolicy.KEEP, request)
        return request.id
    }

    fun execute(context: Context, plan: TrevorAutomationPlan): String {
        val validation = TrevorStructuredPlanner.validate(plan)
        if (!validation.valid) return "TREVOR rejected this automation: " + validation.errors.joinToString(" ")
        var task = loadTask(context, plan.id) ?: TrevorAutomationTask(plan.id, plan.title, plan, TrevorAutomationTaskState.RUNNING, 0, 0)
        if (task.state == TrevorAutomationTaskState.CANCELLED) return "TREVOR task was cancelled."
        if (task.state == TrevorAutomationTaskState.COMPLETED) return "TREVOR task is already completed."

        while (task.currentStep < plan.steps.size) {
            val index = task.currentStep
            val step = plan.steps[index]
            var attempts = 0
            var success: String? = null
            while (attempts <= MAX_RETRIES && success == null) {
                attempts++
                task = task.copy(state = if (attempts == 1) TrevorAutomationTaskState.RUNNING else TrevorAutomationTaskState.RECOVERING, attempts = attempts)
                saveTask(context, task)
                val execution = executeStep(context, step)
                if (execution.first && verifyStep(context, step)) success = execution.second
            }
            if (success == null) {
                task = task.copy(state = TrevorAutomationTaskState.FAILED, attempts = attempts)
                saveTask(context, task)
                return "TREVOR task failed at step " + (index + 1) + "/" + plan.steps.size + ".\n" + executeStepDescription(step)
            }
            val next = index + 1
            task = task.copy(state = if (next == plan.steps.size) TrevorAutomationTaskState.COMPLETED else TrevorAutomationTaskState.STEP_COMPLETE, currentStep = next, attempts = 0)
            saveTask(context, task)
        }
        return "TREVOR task completed successfully. " + plan.steps.size + " step(s) executed and checkpointed."
    }

    fun cancel(context: Context, taskId: String) {
        loadTask(context, taskId)?.let { saveTask(context, it.copy(state = TrevorAutomationTaskState.CANCELLED)) }
        WorkManager.getInstance(context).cancelUniqueWork(WORK_PREFIX + taskId)
        WorkManager.getInstance(context).cancelAllWorkByTag(WORK_PREFIX + taskId)
    }

    fun loadTask(context: Context, taskId: String): TrevorAutomationTask? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(taskId, null) ?: return null
        return runCatching {
            val root = org.json.JSONObject(raw); val steps = root.getJSONArray("steps")
            val parsed = buildList { for (i in 0 until steps.length()) { val s = steps.getJSONObject(i); add(TrevorAutomationStep(s.getString("action"), s.optString("argument"), s.optBoolean("foreground"), s.optString("verify"))) } }
            TrevorAutomationTask(root.getString("id"), root.getString("title"), TrevorAutomationPlan(root.getString("title"), parsed, root.getString("id")), TrevorAutomationTaskState.valueOf(root.getString("state")), root.getInt("currentStep"), root.getInt("attempts"))
        }.getOrNull()
    }

    private fun saveTask(context: Context, task: TrevorAutomationTask) {
        val steps = org.json.JSONArray(); task.plan.steps.forEach { s -> steps.put(org.json.JSONObject().put("action", s.action).put("argument", s.argument).put("foreground", s.requiresForeground).put("verify", s.verify)) }
        val json = org.json.JSONObject().put("id", task.id).put("title", task.title).put("state", task.state.name).put("currentStep", task.currentStep).put("attempts", task.attempts).put("steps", steps)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(task.id, json.toString()).apply()
    }

    private fun executeStep(context: Context, step: TrevorAutomationStep): Pair<Boolean, String> = runCatching {
        when (step.action) {
            "USAGE_SUMMARY" -> true to "OK • " + TrevorUsageIntelligence.summary(context)
            "BATTERY_STATUS" -> true to "OK • " + (TrevorLocalIntelligence.answer(context, "battery") ?: "Battery unavailable.")
            "COPY_TEXT" -> { val clipboard = context.getSystemService(android.content.ClipboardManager::class.java) ?: return@runCatching false to "FAIL • Clipboard unavailable."; clipboard.setPrimaryClip(android.content.ClipData.newPlainText("TREVOR", step.argument)); true to "OK • copied text to clipboard" }
            "OPEN_APP" -> { val packageName = resolvePackage(context, step.argument) ?: return@runCatching false to "FAIL • Could not resolve app: " + step.argument; val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return@runCatching false to "FAIL • App has no launch activity."; intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); context.startActivity(intent); true to "OK • launched " + packageName }
            else -> {
                val intent = when (step.action) {
                    "OPEN_WIFI" -> Intent(Settings.ACTION_WIFI_SETTINGS)
                    "OPEN_BLUETOOTH" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                    "OPEN_DISPLAY" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
                    "OPEN_SOUND" -> Intent(Settings.ACTION_SOUND_SETTINGS)
                    "OPEN_BATTERY" -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                    "OPEN_NOTIFICATIONS" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    "OPEN_SETTINGS" -> Intent(Settings.ACTION_SETTINGS)
                    "SHARE_TEXT" -> Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, step.argument), "Share with…")
                    else -> return@runCatching false to "FAIL • Unsupported action."
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent.resolveActivity(context.packageManager) == null) return@runCatching false to "FAIL • No Android handler."
                context.startActivity(intent); true to "OK • dispatched " + step.action
            }
        }
    }.getOrElse { false to "FAIL • " + (it.message ?: "Android action failed.") }

    private fun verifyStep(context: Context, step: TrevorAutomationStep): Boolean = when (step.verify) {
        "activity" -> true
        "clipboard" -> context.getSystemService(android.content.ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() == step.argument
        "battery" -> TrevorLocalIntelligence.answer(context, "battery") != null
        else -> true
    }

    private fun executeStepDescription(step: TrevorAutomationStep): String = "Action=" + step.action + if (step.argument.isBlank()) "" else " argument=" + step.argument.take(120)
    private fun resolvePackage(context: Context, requested: String): String? {
        val q = requested.trim().lowercase(Locale.ROOT)
        val known = mapOf("youtube" to "com.google.android.youtube", "chrome" to "com.android.chrome", "google chrome" to "com.android.chrome", "settings" to "com.android.settings")
        known[q]?.let { if (context.packageManager.getLaunchIntentForPackage(it) != null) return it }
        return context.packageManager.getInstalledApplications(0).firstOrNull { it.loadLabel(context.packageManager).toString().lowercase(Locale.ROOT) == q }?.packageName
    }
}