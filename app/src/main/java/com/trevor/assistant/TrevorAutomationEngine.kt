package com.trevor.assistant

import android.content.Context
import android.content.Intent
import android.provider.Settings
import java.util.Locale

data class TrevorAutomationStep(
    val action: String,
    val argument: String = "",
    val requiresForeground: Boolean = false
)

data class TrevorAutomationPlan(val title: String, val steps: List<TrevorAutomationStep>)

object TrevorAutomationEngine {
    private val split = Regex("\\s*(?:,|;|\\band then\\b|\\bthen\\b|\\band\\b)\\s*", RegexOption.IGNORE_CASE)

    fun plan(input: String): TrevorAutomationPlan? {
        val parts = input.trim().split(split).map { it.trim() }.filter { it.isNotBlank() }
        if (parts.size < 2) return null
        val steps = parts.mapNotNull { parseStep(it) }
        return if (steps.size >= 2) TrevorAutomationPlan("Multi-step Android task", steps) else null
    }

    private fun parseStep(text: String): TrevorAutomationStep? {
        val lower = text.lowercase(Locale.ROOT)
        return when {
            lower.contains("open wi-fi") || lower.contains("open wifi") ->
                TrevorAutomationStep("OPEN_WIFI", requiresForeground = true)
            lower.contains("open bluetooth") ->
                TrevorAutomationStep("OPEN_BLUETOOTH", requiresForeground = true)
            lower.contains("open display settings") ->
                TrevorAutomationStep("OPEN_DISPLAY", requiresForeground = true)
            lower.contains("open sound settings") ->
                TrevorAutomationStep("OPEN_SOUND", requiresForeground = true)
            lower.contains("open battery") || lower.contains("battery saver") ->
                TrevorAutomationStep("OPEN_BATTERY", requiresForeground = true)
            lower.contains("open notification settings") ->
                TrevorAutomationStep("OPEN_NOTIFICATIONS", requiresForeground = true)
            lower == "open settings" || lower.endsWith(" open settings") ->
                TrevorAutomationStep("OPEN_SETTINGS", requiresForeground = true)
            lower.startsWith("analyse my usage") || lower.startsWith("analyze my usage") ->
                TrevorAutomationStep("USAGE_SUMMARY")
            lower.startsWith("share ") ->
                TrevorAutomationStep("SHARE_TEXT", text.substringAfter(" ").trim(), true)
            else -> null
        }
    }

    fun execute(context: Context, plan: TrevorAutomationPlan): String {
        val results = mutableListOf<String>()
        for ((index, step) in plan.steps.withIndex()) {
            val result = executeStep(context, step)
            results += "Step " + (index + 1) + ": " + result
            if (!result.startsWith("OK")) {
                return "TREVOR task stopped after step " + (index + 1) + ".
" + results.joinToString("
")
            }
        }
        return "TREVOR task completed and every dispatched step returned successfully.
" +
            results.joinToString("
")
    }

    private fun executeStep(context: Context, step: TrevorAutomationStep): String = runCatching {
        if (step.action == "USAGE_SUMMARY") return@runCatching "OK • " + TrevorUsageIntelligence.summary(context)
        val intent = when (step.action) {
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
            else -> return@runCatching "FAIL • Unsupported action."
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) return@runCatching "FAIL • No Android handler."
        context.startActivity(intent)
        "OK • dispatched " + step.action
    }.getOrElse { "FAIL • " + (it.message ?: "Android action failed.") }
}
