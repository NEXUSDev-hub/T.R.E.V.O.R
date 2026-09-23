package com.trevor.assistant

import java.util.Locale

/** Part 5: deterministic structured planning. No execution happens here. */
object TrevorStructuredPlanner {
    data class Validation(val valid: Boolean, val errors: List<String> = emptyList())

    fun validate(plan: TrevorAutomationPlan): Validation {
        val errors = mutableListOf<String>()
        if (plan.title.isBlank()) errors += "Plan title is empty."
        if (plan.steps.isEmpty()) errors += "Plan contains no steps."
        if (plan.steps.size > 20) errors += "Plan exceeds the 20-step safety limit."
        plan.steps.forEachIndexed { index, step ->
            if (step.action.isBlank()) errors += "Step " + (index + 1) + " has no action."
            if (step.action !in supportedActions) errors += "Step " + (index + 1) + " uses unsupported action " + step.action + "."
            if (step.action in setOf("OPEN_APP", "SHARE_TEXT", "COPY_TEXT") && step.argument.isBlank()) errors += "Step " + (index + 1) + " requires an argument."
        }
        return Validation(errors.isEmpty(), errors)
    }

    fun describe(plan: TrevorAutomationPlan): String = buildString {
        appendLine(plan.title)
        plan.steps.forEachIndexed { index, step ->
            append(index + 1).append(". ").append(step.action)
            if (step.argument.isNotBlank()) append(" → ").append(step.argument.take(120))
            if (step.verify.isNotBlank()) append(" [verify: ").append(step.verify).append(']')
            appendLine()
        }
    }.trim()

    private val supportedActions = setOf(
        "OPEN_WIFI", "OPEN_BLUETOOTH", "OPEN_DISPLAY", "OPEN_SOUND", "OPEN_BATTERY",
        "OPEN_NOTIFICATIONS", "OPEN_SETTINGS", "OPEN_APP", "SHARE_TEXT", "COPY_TEXT",
        "USAGE_SUMMARY", "BATTERY_STATUS"
    )

    fun normalizeAction(text: String): TrevorAutomationStep? {
        val lower = text.trim().lowercase(Locale.ROOT)
        return when {
            lower == "open wifi" || lower == "open wi-fi" || lower.contains("open wifi settings") -> TrevorAutomationStep("OPEN_WIFI", requiresForeground = true, verify = "activity")
            lower.contains("open bluetooth settings") || lower == "open bluetooth" -> TrevorAutomationStep("OPEN_BLUETOOTH", requiresForeground = true, verify = "activity")
            lower.contains("open display settings") -> TrevorAutomationStep("OPEN_DISPLAY", requiresForeground = true, verify = "activity")
            lower.contains("open sound settings") -> TrevorAutomationStep("OPEN_SOUND", requiresForeground = true, verify = "activity")
            lower.contains("open battery") || lower.contains("battery saver") -> TrevorAutomationStep("OPEN_BATTERY", requiresForeground = true, verify = "activity")
            lower.contains("open notification settings") -> TrevorAutomationStep("OPEN_NOTIFICATIONS", requiresForeground = true, verify = "activity")
            lower == "open settings" || lower.endsWith(" open settings") -> TrevorAutomationStep("OPEN_SETTINGS", requiresForeground = true, verify = "activity")
            lower.startsWith("open app ") -> TrevorAutomationStep("OPEN_APP", text.substringAfter("open app ", "").trim(), true, "activity")
            lower.startsWith("share ") -> TrevorAutomationStep("SHARE_TEXT", text.substringAfter(" ").trim(), true, "activity")
            lower.startsWith("copy ") -> TrevorAutomationStep("COPY_TEXT", text.substringAfter(" ").trim(), false, "clipboard")
            lower.startsWith("analyse my usage") || lower.startsWith("analyze my usage") -> TrevorAutomationStep("USAGE_SUMMARY")
            lower == "show battery" || lower == "check battery" -> TrevorAutomationStep("BATTERY_STATUS", verify = "battery")
            else -> null
        }
    }
}