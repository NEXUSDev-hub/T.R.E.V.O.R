package com.trevor.assistant

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Locale
import java.util.UUID

enum class TrevorAutomationTaskState {
    PLANNED, VALIDATED, QUEUED, RUNNING, STEP_COMPLETE, COMPLETED, FAILED, RECOVERING, CANCELLED
}

data class TrevorAutomationStep(
    val action: String,
    val argument: String = "",
    val requiresForeground: Boolean = false,
    val verify: String = ""
)

data class TrevorAutomationPlan(
    val title: String,
    val steps: List<TrevorAutomationStep>,
    val id: String = UUID.randomUUID().toString()
)

data class TrevorAutomationTask(
    val id: String,
    val title: String,
    val plan: TrevorAutomationPlan,
    val state: TrevorAutomationTaskState,
    val currentStep: Int,
    val attempts: Int
)

object TrevorAutomationEngine {
    private const val PREFS = "trevor_automation_runtime"
    private const val MAX_RETRIES = 2

    private val split = Regex("""\s*(?:,|;|\band then\b|\bthen\b|\band\b)\s*""", RegexOption.IGNORE_CASE)

    fun plan(input: String): TrevorAutomationPlan? {
        val parts = input.trim().split(split).map { it.trim() }.filter { it.isNotBlank() }
        if (parts.size < 2) return null
        val steps = parts.mapNotNull(::parseStep)
        if (steps.size < 2 || steps.size != parts.size) return null
        return TrevorAutomationPlan("Multi-step Android task", steps)
    }

    private fun parseStep(text: String): TrevorAutomationStep? {
        val lower = text.lowercase(Locale.ROOT)
        return when {
            lower == "open wifi" || lower == "open wi-fi" || lower.contains("open wifi settings") ->
                TrevorAutomationStep("OPEN_WIFI", requiresForeground = true, verify = "activity")
            lower.contains("open bluetooth settings") || lower == "open bluetooth" ->
                TrevorAutomationStep("OPEN_BLUETOOTH", requiresForeground = true, verify = "activity")
            lower.contains("open display settings") ->
                TrevorAutomationStep("OPEN_DISPLAY", requiresForeground = true, verify = "activity")
            lower.contains("open sound settings") ->
                TrevorAutomationStep("OPEN_SOUND", requiresForeground = true, verify = "activity")
            lower.contains("open battery") || lower.contains("battery saver") ->
                TrevorAutomationStep("OPEN_BATTERY", requiresForeground = true, verify = "activity")
            lower.contains("open notification settings") ->
                TrevorAutomationStep("OPEN_NOTIFICATIONS", requiresForeground = true, verify = "activity")
            lower == "open settings" || lower.endsWith(" open settings") ->
                TrevorAutomationStep("OPEN_SETTINGS", requiresForeground = true, verify = "activity")
            lower.startsWith("open app ") ->
                TrevorAutomationStep("OPEN_APP", text.substringAfter("open app ", "").trim(), true, "activity")
            lower.startsWith("share ") ->
                TrevorAutomationStep("SHARE_TEXT", text.substringAfter(" ").trim(), true, "activity")
            lower.startsWith("copy ") ->
                TrevorAutomationStep("COPY_TEXT", text.substringAfter(" ").trim(), false, "clipboard")
            lower.startsWith("analyse my usage") || lower.startsWith("analyze my usage") ->
                TrevorAutomationStep("USAGE_SUMMARY")
            lower == "show battery" || lower == "check battery" ->
                TrevorAutomationStep("BATTERY_STATUS", verify = "battery")
            else -> null
        }
    }

    fun validate(plan: TrevorAutomationPlan): Result<Unit> {
        if (plan.steps.isEmpty()) return Result.failure(IllegalArgumentException("Automation contains no steps."))
        if (plan.steps.size > 20) return Result.failure(IllegalArgumentException("Automation is limited to 20 steps."))
        return if (plan.steps.all { it.action in supportedActions }) Result.success(Unit)
        else Result.failure(IllegalArgumentException("Automation contains an unsupported Android capability."))
    }

    fun enqueue(context: Context, plan: TrevorAutomationPlan): UUID {
        validate(plan).getOrThrow()
        saveTask(context, TrevorAutomationTask(plan.id, plan.title, plan, TrevorAutomationTaskState.QUEUED, 0, 0))
        val data = Data.Builder().putString("task_id", plan.id).build()
        val request = OneTimeWorkRequestBuilder<TrevorAutomationWorker>().setInputData(data).addTag("trevor-task-${plan.id}").build()
        WorkManager.getInstance(context).enqueue(request)
        return request.id
    }

    fun execute(context: Context, plan: TrevorAutomationPlan): String {
        validate(plan).getOrElse { return "TREVOR rejected this automation: " + it.message }
        var task = loadTask(context, plan.id)
            ?: TrevorAutomationTask(plan.id, plan.title, plan, TrevorAutomationTaskState.RUNNING, 0, 0)

        while (task.currentStep < plan.steps.size) {
            if (task.state == TrevorAutomationTaskState.CANCELLED) return "TREVOR task was cancelled."
            task = task.copy(state = TrevorAutomationTaskState.RUNNING)
            saveTask(context, task)
            val step = plan.steps[task.currentStep]
            var result: String? = null
            var attempts = task.attempts

            repeat(MAX_RETRIES + 1) {
                attempts++
                val candidate = executeStep(context, step)
                if (candidate.startsWith("OK")) {
                    result = candidate
                    return@repeat
                }
                if (it < MAX_RETRIES) { task = task.copy(state = TrevorAutomationTaskState.RECOVERING, attempts = attempts); saveTask(context, task) }
            }

            if (result == null) {
                val failed = task.copy(state = TrevorAutomationTaskState.FAILED, attempts = attempts)
                saveTask(context, failed)
                return "TREVOR task failed at step " + (task.currentStep + 1) + "/" + plan.steps.size + ".\n" + executeStepDescription(step)
            }

            task = task.copy(
                state = if (task.currentStep + 1 == plan.steps.size) TrevorAutomationTaskState.COMPLETED else TrevorAutomationTaskState.STEP_COMPLETE,
                currentStep = task.currentStep + 1,
                attempts = 0
            )
            saveTask(context, task)
        }

        return "TREVOR task completed successfully. " + plan.steps.size + " step(s) executed and checkpointed."
    }

    fun cancel(context: Context, taskId: String) {
        val task = loadTask(context, taskId)
        if (task != null) {
            saveTask(context, task.copy(state = TrevorAutomationTaskState.CANCELLED))
        }
        WorkManager.getInstance(context).cancelAllWorkByTag("trevor-task-$taskId")
    }

    fun loadTask(context: Context, taskId: String): TrevorAutomationTask? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(taskId, null) ?: return null
        return runCatching {
            val root = org.json.JSONObject(raw)
            val steps = root.getJSONArray("steps")
            val parsed = buildList {
                for (i in 0 until steps.length()) {
                    val s = steps.getJSONObject(i)
                    add(TrevorAutomationStep(s.getString("action"), s.optString("argument"), s.optBoolean("foreground"), s.optString("verify")))
                }
            }
            TrevorAutomationTask(
                root.getString("id"),
                root.getString("title"),
                TrevorAutomationPlan(root.getString("title"), parsed, root.getString("id")),
                TrevorAutomationTaskState.valueOf(root.getString("state")),
                root.getInt("currentStep"),
                root.getInt("attempts")
            )
        }.getOrNull()
    }

    private fun saveTask(context: Context, task: TrevorAutomationTask) {
        val steps = org.json.JSONArray()
        task.plan.steps.forEach { s ->
            steps.put(org.json.JSONObject().put("action", s.action).put("argument", s.argument).put("foreground", s.requiresForeground).put("verify", s.verify))
        }
        val json = org.json.JSONObject()
            .put("id", task.id)
            .put("title", task.title)
            .put("state", task.state.name)
            .put("currentStep", task.currentStep)
            .put("attempts", task.attempts)
            .put("steps", steps)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(task.id, json.toString()).apply()
    }

    private fun executeStep(context: Context, step: TrevorAutomationStep): String = runCatching {
        when (step.action) {
            "USAGE_SUMMARY" -> "OK • " + TrevorUsageIntelligence.summary(context)
            "BATTERY_STATUS" -> "OK • " + (TrevorLocalIntelligence.answer(context, "battery") ?: "Battery unavailable.")
            "COPY_TEXT" -> {
                val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                    ?: return@runCatching "FAIL • Clipboard unavailable."
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("TREVOR", step.argument))
                "OK • copied text to clipboard"
            }
            "OPEN_APP" -> {
                val packageName = resolvePackage(context, step.argument)
                    ?: return@runCatching "FAIL • Could not resolve app: " + step.argument
                val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                    ?: return@runCatching "FAIL • App has no launch activity."
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                "OK • launched " + packageName
            }
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
                    else -> return@runCatching "FAIL • Unsupported action."
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent.resolveActivity(context.packageManager) == null) return@runCatching "FAIL • No Android handler."
                context.startActivity(intent)
                "OK • dispatched " + step.action
            }
        }
    }.getOrElse { "FAIL • " + (it.message ?: "Android action failed.") }

    private fun executeStepDescription(step: TrevorAutomationStep): String =
        "Action=" + step.action + if (step.argument.isBlank()) "" else " argument=" + step.argument.take(120)

    private fun resolvePackage(context: Context, requested: String): String? {
        val q = requested.trim().lowercase(Locale.ROOT)
        val known = mapOf("youtube" to "com.google.android.youtube", "chrome" to "com.android.chrome", "google chrome" to "com.android.chrome", "settings" to "com.android.settings")
        known[q]?.let { if (context.packageManager.getLaunchIntentForPackage(it) != null) return it }
        return context.packageManager.getInstalledApplications(0)
            .firstOrNull { it.loadLabel(context.packageManager).toString().lowercase(Locale.ROOT) == q }
            ?.packageName
    }

    private val supportedActions = setOf(
        "OPEN_WIFI", "OPEN_BLUETOOTH", "OPEN_DISPLAY", "OPEN_SOUND", "OPEN_BATTERY",
        "OPEN_NOTIFICATIONS", "OPEN_SETTINGS", "OPEN_APP", "SHARE_TEXT", "COPY_TEXT",
        "USAGE_SUMMARY", "BATTERY_STATUS"
    )
}
