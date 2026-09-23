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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class TrevorAutomationTaskState {
    PLANNED, VALIDATED, QUEUED, RUNNING, STEP_COMPLETE, COMPLETED, FAILED, RECOVERING, CANCELLED
}

data class TrevorAutomationStep(
    val action: String,
    val argument: String = "",
    /**
     * Requires an interactive device at execution time. TREVOR does not
     * interpret this as permission to bypass Android lock-screen/security
     * boundaries or force itself into the foreground.
     */
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
    val attempts: Int,
    val scheduledAt: Long = 0L
)

data class TrevorAutomationExecutionResult(
    val message: String,
    val completed: Boolean,
    val retryable: Boolean = false,
    val cancelled: Boolean = false
)

object TrevorAutomationEngine {
    private const val PREFS = "trevor_automation_runtime"
    private const val MAX_RETRIES = 2
    private const val MAX_TASK_RECORDS = 120
    private const val MAX_DELAY_MILLIS = 7L * 24L * 60L * 60L * 1000L
    private const val WORK_PREFIX = "trevor-automation-"
    private val executionMutex = Mutex()

    fun plan(input: String): TrevorAutomationPlan? {
        val parts = input.trim()
            .split(Regex("\\s*(?:,|;|\\band then\\b|\\bthen\\b)\\s*", RegexOption.IGNORE_CASE))
            .map(String::trim)
            .filter(String::isNotBlank)
        if (parts.isEmpty()) return null

        val steps = parts.map { TrevorStructuredPlanner.normalizeAction(it) ?: return null }
        val normalized = steps.joinToString("|") { it.action + ":" + it.argument.trim() }
        val id = stableId(normalized)
        val result = TrevorAutomationPlan("TREVOR automation", steps, id)
        return if (TrevorStructuredPlanner.validate(result).valid) result else null
    }

    fun validate(plan: TrevorAutomationPlan): Result<Unit> {
        val v = TrevorStructuredPlanner.validate(plan)
        return if (v.valid) Result.success(Unit)
        else Result.failure(IllegalArgumentException(v.errors.joinToString(" ")))
    }

    fun describe(plan: TrevorAutomationPlan): String = TrevorStructuredPlanner.describe(plan)

    fun enqueue(context: Context, plan: TrevorAutomationPlan, delayMillis: Long = 0L): UUID {
        validate(plan).getOrThrow()
        val delay = delayMillis.coerceIn(0L, MAX_DELAY_MILLIS)
        val now = System.currentTimeMillis()
        val initialTask = TrevorAutomationTask(
            plan.id, plan.title, plan, TrevorAutomationTaskState.PLANNED, 0, 0,
            if (delay == 0L) now else now + delay
        )
        saveTask(context, initialTask)
        saveTask(context, initialTask.copy(state = TrevorAutomationTaskState.VALIDATED))

        val request = OneTimeWorkRequestBuilder<TrevorAutomationWorker>()
            .setInputData(Data.Builder().putString("task_id", plan.id).build())
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .addTag(WORK_PREFIX + plan.id)
            .build()

        try {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_PREFIX + plan.id,
                ExistingWorkPolicy.KEEP,
                request
            )
        } catch (error: Throwable) {
            saveTask(
                context,
                loadTask(context, plan.id)?.copy(state = TrevorAutomationTaskState.FAILED)
                    ?: initialTask.copy(state = TrevorAutomationTaskState.FAILED)
            )
            throw error
        }

        saveTask(
            context,
            loadTask(context, plan.id)?.copy(state = TrevorAutomationTaskState.QUEUED)
                ?: initialTask.copy(state = TrevorAutomationTaskState.QUEUED)
        )

        loadTask(context, plan.id)?.let {
            TrevorAutomationNotificationHelper.show(
                context,
                it,
                if (delay == 0L) "Queued and ready to execute." else "Scheduled for later."
            )
        }
        return request.id
    }

    fun cancel(context: Context, taskId: String) {
        loadTask(context, taskId)?.let {
            saveTask(context, it.copy(state = TrevorAutomationTaskState.CANCELLED))
        }
        WorkManager.getInstance(context).cancelUniqueWork(WORK_PREFIX + taskId)
        WorkManager.getInstance(context).cancelAllWorkByTag(WORK_PREFIX + taskId)
    }

    fun completeManually(context: Context, taskId: String): Boolean {
        val task = loadTask(context, taskId) ?: return false
        if (task.state == TrevorAutomationTaskState.CANCELLED) return false
        saveTask(context, task.copy(state = TrevorAutomationTaskState.COMPLETED, currentStep = task.plan.steps.size, attempts = 0))
        WorkManager.getInstance(context).cancelUniqueWork(WORK_PREFIX + taskId)
        return true
    }

    fun snooze(context: Context, taskId: String, delayMillis: Long): UUID? {
        val task = loadTask(context, taskId) ?: return null
        if (task.state == TrevorAutomationTaskState.COMPLETED || task.state == TrevorAutomationTaskState.CANCELLED) return null

        WorkManager.getInstance(context).cancelUniqueWork(WORK_PREFIX + taskId)
        return enqueue(context, task.plan, delayMillis)
    }

    suspend fun execute(context: Context, plan: TrevorAutomationPlan): String =
        executeDetailed(context, plan).message

    suspend fun executeDetailed(context: Context, plan: TrevorAutomationPlan): TrevorAutomationExecutionResult =
        executionMutex.withLock {
            currentCoroutineContext().ensureActive()
            val validation = TrevorStructuredPlanner.validate(plan)
            if (!validation.valid) {
                return@withLock TrevorAutomationExecutionResult(
                    "TREVOR rejected this automation: " + validation.errors.joinToString(" "),
                    completed = false
                )
            }

            var task = loadTask(context, plan.id)
                ?: TrevorAutomationTask(plan.id, plan.title, plan, TrevorAutomationTaskState.VALIDATED, 0, 0)

            if (task.state == TrevorAutomationTaskState.CANCELLED) {
                return@withLock TrevorAutomationExecutionResult("TREVOR task was cancelled.", false, cancelled = true)
            }
            if (task.state == TrevorAutomationTaskState.COMPLETED) {
                return@withLock TrevorAutomationExecutionResult("TREVOR task is already completed.", true)
            }

            task = task.copy(state = TrevorAutomationTaskState.RUNNING)
            saveTask(context, task)

            while (task.currentStep < plan.steps.size) {
                currentCoroutineContext().ensureActive()

                val persisted = loadTask(context, plan.id)
                if (persisted?.state == TrevorAutomationTaskState.CANCELLED) {
                    return@withLock TrevorAutomationExecutionResult("TREVOR task was cancelled.", false, cancelled = true)
                }
                if (persisted != null) task = persisted

                val index = task.currentStep
                val step = plan.steps[index]
                var attempts = 0
                var success: String? = null
                var retryableFailure = false

                while (attempts <= MAX_RETRIES && success == null) {
                    currentCoroutineContext().ensureActive()
                    attempts++
                    task = task.copy(
                        state = if (attempts == 1) TrevorAutomationTaskState.RUNNING else TrevorAutomationTaskState.RECOVERING,
                        attempts = attempts
                    )
                    saveTask(context, task)

                    val execution = executeStep(context, step)
                    retryableFailure = execution.retryable
                    if (execution.ok && verifyStep(context, step)) {
                        success = execution.message
                    }
                }

                if (success == null) {
                    task = task.copy(state = TrevorAutomationTaskState.FAILED, attempts = attempts)
                    saveTask(context, task)
                    return@withLock TrevorAutomationExecutionResult(
                        "TREVOR task failed at step " + (index + 1) + "/" + plan.steps.size +
                            " after " + attempts + " attempt(s). " + executeStepDescription(step),
                        false,
                        retryable = retryableFailure
                    )
                }

                val next = index + 1
                task = task.copy(
                    state = if (next == plan.steps.size) TrevorAutomationTaskState.COMPLETED else TrevorAutomationTaskState.STEP_COMPLETE,
                    currentStep = next,
                    attempts = 0
                )
                saveTask(context, task)
            }

            TrevorAutomationExecutionResult(
                "TREVOR task completed successfully. " + plan.steps.size + " step(s) executed and checkpointed.",
                true
            )
        }

    fun loadTask(context: Context, taskId: String): TrevorAutomationTask? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(taskId, null) ?: return null
        return runCatching {
            val root = org.json.JSONObject(raw)
            val steps = root.getJSONArray("steps")
            val parsed = buildList {
                for (i in 0 until steps.length()) {
                    val s = steps.getJSONObject(i)
                    add(
                        TrevorAutomationStep(
                            s.getString("action"),
                            s.optString("argument"),
                            s.optBoolean("foreground"),
                            s.optString("verify")
                        )
                    )
                }
            }
            TrevorAutomationTask(
                root.getString("id"),
                root.getString("title"),
                TrevorAutomationPlan(root.getString("title"), parsed, root.getString("id")),
                TrevorAutomationTaskState.valueOf(root.getString("state")),
                root.getInt("currentStep"),
                root.getInt("attempts"),
                root.optLong("scheduledAt", 0L)
            )
        }.getOrNull()
    }

    private fun saveTask(context: Context, task: TrevorAutomationTask) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val steps = org.json.JSONArray()
        task.plan.steps.forEach { s ->
            steps.put(
                org.json.JSONObject()
                    .put("action", s.action)
                    .put("argument", s.argument)
                    .put("foreground", s.requiresForeground)
                    .put("verify", s.verify)
            )
        }
        val json = org.json.JSONObject()
            .put("id", task.id)
            .put("title", task.title)
            .put("state", task.state.name)
            .put("currentStep", task.currentStep)
            .put("attempts", task.attempts)
            .put("scheduledAt", task.scheduledAt)
            .put("steps", steps)

        synchronized(prefs) {
            prefs.edit().putString(task.id, json.toString()).commit()
            pruneTaskRecords(prefs)
        }
    }

    private fun pruneTaskRecords(prefs: android.content.SharedPreferences) {
        val entries = prefs.all.entries
            .filter { it.value is String }
            .sortedByDescending { it.key }
        if (entries.size <= MAX_TASK_RECORDS) return
        val editor = prefs.edit()
        entries.drop(MAX_TASK_RECORDS).forEach { editor.remove(it.key) }
        editor.commit()
    }

    private data class StepExecution(val ok: Boolean, val message: String, val retryable: Boolean)

    private fun executeStep(context: Context, step: TrevorAutomationStep): StepExecution = runCatching {
        if (step.requiresForeground && !isDeviceInteractive(context)) {
            return@runCatching StepExecution(
                false,
                "FAIL • Step requires an interactive device; execution will retry when the device is active.",
                true
            )
        }
        when (step.action) {
            "USAGE_SUMMARY" -> StepExecution(true, "OK • " + TrevorUsageIntelligence.summary(context), false)
            "BATTERY_STATUS" -> StepExecution(true, "OK • " + (TrevorLocalIntelligence.answer(context, "battery") ?: "Battery unavailable."), false)
            "COPY_TEXT" -> {
                val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                    ?: return@runCatching StepExecution(false, "FAIL • Clipboard unavailable.", false)
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("TREVOR", step.argument))
                StepExecution(true, "OK • copied text to clipboard", false)
            }
            "OPEN_APP" -> {
                val packageName = resolvePackage(context, step.argument)
                    ?: return@runCatching StepExecution(false, "FAIL • Could not resolve app: " + step.argument, false)
                val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                    ?: return@runCatching StepExecution(false, "FAIL • App has no launch activity.", false)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                StepExecution(true, "OK • launched " + packageName, false)
            }
            else -> {
                val intent = intentForStep(context, step)
                    ?: return@runCatching StepExecution(false, "FAIL • Unsupported action.", false)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent.resolveActivity(context.packageManager) == null) {
                    return@runCatching StepExecution(false, "FAIL • No Android handler.", false)
                }
                context.startActivity(intent)
                StepExecution(true, "OK • dispatched " + step.action, false)
            }
        }
    }.getOrElse {
        StepExecution(false, "FAIL • " + (it.message ?: "Android action failed."), true)
    }

    private fun verifyStep(context: Context, step: TrevorAutomationStep): Boolean = when (step.verify) {
        "dispatch" -> intentForStep(context, step)?.resolveActivity(context.packageManager) != null
        "launch" -> resolvePackage(context, step.argument)?.let {
            context.packageManager.getLaunchIntentForPackage(it) != null
        } == true
        "clipboard" -> context.getSystemService(android.content.ClipboardManager::class.java)
            ?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() == step.argument
        "result" -> when (step.action) {
            "USAGE_SUMMARY" -> TrevorUsageIntelligence.summary(context).isNotBlank()
            "BATTERY_STATUS" -> TrevorLocalIntelligence.answer(context, "battery") != null
            else -> false
        }
        else -> false
    }

    private fun intentForStep(context: Context, step: TrevorAutomationStep): Intent? = when (step.action) {
        "OPEN_WIFI" -> Intent(Settings.ACTION_WIFI_SETTINGS)
        "OPEN_BLUETOOTH" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        "OPEN_DISPLAY" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
        "OPEN_SOUND" -> Intent(Settings.ACTION_SOUND_SETTINGS)
        "OPEN_BATTERY" -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
        "OPEN_NOTIFICATIONS" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        "OPEN_SETTINGS" -> Intent(Settings.ACTION_SETTINGS)
        "SHARE_TEXT" -> Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, step.argument),
            "Share with…"
        )
        else -> null
    }

    private fun isDeviceInteractive(context: Context): Boolean =
        context.getSystemService(android.os.PowerManager::class.java)?.isInteractive == true

    private fun executeStepDescription(step: TrevorAutomationStep): String =
        "Action=" + step.action + if (step.argument.isBlank()) "" else " argument=" + step.argument.take(120)

    private fun resolvePackage(context: Context, requested: String): String? {
        val q = requested.trim().lowercase(Locale.ROOT)
        if (q.isBlank()) return null
        if (q.contains('.')) {
            runCatching {
                if (context.packageManager.getLaunchIntentForPackage(q) != null) return q
            }
        }
        val known = mapOf(
            "youtube" to "com.google.android.youtube",
            "chrome" to "com.android.chrome",
            "google chrome" to "com.android.chrome",
            "settings" to "com.android.settings"
        )
        known[q]?.let { if (context.packageManager.getLaunchIntentForPackage(it) != null) return it }
        return context.packageManager.getInstalledApplications(0)
            .firstOrNull { it.loadLabel(context.packageManager).toString().trim().lowercase(Locale.ROOT) == q }
            ?.packageName
    }

    private fun stableId(value: String): String =
        UUID.nameUUIDFromBytes(
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
        ).toString()
}
