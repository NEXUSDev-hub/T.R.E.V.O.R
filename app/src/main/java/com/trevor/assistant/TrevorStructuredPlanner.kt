package com.trevor.assistant

import java.util.Locale

/** Part 5: deterministic structured planning. No execution happens here. */
object TrevorStructuredPlanner {
    private const val MAX_STEPS = 20
    private const val MAX_TITLE_CHARS = 120
    private const val MAX_ARGUMENT_CHARS = 2_000

    data class Validation(val valid: Boolean, val errors: List<String> = emptyList())

    fun validate(plan: TrevorAutomationPlan): Validation {
        val errors = mutableListOf<String>()
        if (plan.title.isBlank()) errors += "Plan title is empty."
        if (plan.title.length > MAX_TITLE_CHARS) errors += "Plan title is too long."
        if (plan.steps.isEmpty()) errors += "Plan contains no steps."
        if (plan.steps.size > MAX_STEPS) errors += "Plan exceeds the 20-step safety limit."
        plan.steps.forEachIndexed { index, step ->
            val number = index + 1
            if (step.action.isBlank()) errors += "Step " + number + " has no action."
            if (step.action !in supportedActions) errors += "Step " + number + " uses unsupported action " + step.action + "."
            if (step.argument.length > MAX_ARGUMENT_CHARS) errors += "Step " + number + " argument is too long."
            if (step.action in argumentActions && step.argument.isBlank()) errors += "Step " + number + " requires an argument."
            val allowedVerification = verificationContracts[step.action].orEmpty()
            if (step.verify.isNotBlank() && step.verify !in allowedVerification) {
                errors += "Step " + number + " requests unsupported verification " + step.verify + "."
            }
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

    fun normalizeAction(text: String): TrevorAutomationStep? {
        val original = text.trim()
        val lower = original.lowercase(Locale.ROOT)
        return when {
            lower == "open wifi" || lower == "open wi-fi" || lower.contains("open wifi settings") -> TrevorAutomationStep("OPEN_WIFI", requiresForeground = true, verify = "dispatch")
            lower.contains("open bluetooth settings") || lower == "open bluetooth" -> TrevorAutomationStep("OPEN_BLUETOOTH", requiresForeground = true, verify = "dispatch")
            lower.contains("open display settings") -> TrevorAutomationStep("OPEN_DISPLAY", requiresForeground = true, verify = "dispatch")
            lower.contains("open sound settings") -> TrevorAutomationStep("OPEN_SOUND", requiresForeground = true, verify = "dispatch")
            lower.contains("open battery") || lower.contains("battery saver") -> TrevorAutomationStep("OPEN_BATTERY", requiresForeground = true, verify = "dispatch")
            lower.contains("open notification settings") -> TrevorAutomationStep("OPEN_NOTIFICATIONS", requiresForeground = true, verify = "dispatch")
            lower == "open settings" || lower.endsWith(" open settings") -> TrevorAutomationStep("OPEN_SETTINGS", requiresForeground = true, verify = "dispatch")
            lower.startsWith("open app ") -> TrevorAutomationStep("OPEN_APP", original.substringAfter("open app ", "").trim(), true, "launch")
            lower.startsWith("share ") -> TrevorAutomationStep("SHARE_TEXT", original.substringAfter(" ").trim(), true, "dispatch")
            lower.startsWith("copy ") -> TrevorAutomationStep("COPY_TEXT", original.substringAfter(" ").trim(), false, "clipboard")
            lower.startsWith("analyse my usage") || lower.startsWith("analyze my usage") -> TrevorAutomationStep("USAGE_SUMMARY", verify = "result")
            lower == "show battery" || lower == "check battery" -> TrevorAutomationStep("BATTERY_STATUS", verify = "result")
            else -> null
        }
    }

    private val argumentActions = setOf("OPEN_APP", "SHARE_TEXT", "COPY_TEXT")
    private val verificationContracts = mapOf(
        "OPEN_WIFI" to setOf("dispatch"),
        "OPEN_BLUETOOTH" to setOf("dispatch"),
        "OPEN_DISPLAY" to setOf("dispatch"),
        "OPEN_SOUND" to setOf("dispatch"),
        "OPEN_BATTERY" to setOf("dispatch"),
        "OPEN_NOTIFICATIONS" to setOf("dispatch"),
        "OPEN_SETTINGS" to setOf("dispatch"),
        "OPEN_APP" to setOf("launch"),
        "SHARE_TEXT" to setOf("dispatch"),
        "COPY_TEXT" to setOf("clipboard"),
        "USAGE_SUMMARY" to setOf("result"),
        "BATTERY_STATUS" to setOf("result")
    )
    private val supportedActions = verificationContracts.keys
}
