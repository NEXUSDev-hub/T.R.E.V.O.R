package com.trevor.assistant

import java.util.Locale

/** Deterministic planning. UI actions require the user-enabled accessibility bridge. */
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
            val n=index+1
            if (step.action.isBlank()) errors += "Step $n has no action."
            if (step.action !in supportedActions) errors += "Step $n uses unsupported action ${step.action}."
            if (step.argument.length > MAX_ARGUMENT_CHARS) errors += "Step $n argument is too long."
            if (step.action in argumentActions && step.argument.isBlank()) errors += "Step $n requires an argument."
            if (step.verify.isNotBlank() && step.verify !in verificationContracts[step.action].orEmpty()) errors += "Step $n requests unsupported verification ${step.verify}."
        }
        return Validation(errors.isEmpty(), errors)
    }

    fun describe(plan: TrevorAutomationPlan)=buildString {
        appendLine(plan.title)
        plan.steps.forEachIndexed { i,s ->
            append(i+1).append(". ").append(s.action)
            if(s.argument.isNotBlank()) append(" → ").append(s.argument.take(120))
            if(s.verify.isNotBlank()) append(" [verify: ").append(s.verify).append(']')
            appendLine()
        }
    }.trim()

    fun normalizeAction(text:String):TrevorAutomationStep? {
        val o=text.trim(); val l=o.lowercase(Locale.ROOT)
        return when {
            l.startsWith("click description ") -> TrevorAutomationStep("UI_CLICK_DESCRIPTION", o.substringAfter("click description ").trim(), true, "ui")
            l.startsWith("click ") -> TrevorAutomationStep("UI_CLICK", o.substringAfter(" ").trim(), true, "ui")
            l.startsWith("tap ") -> TrevorAutomationStep("UI_CLICK", o.substringAfter(" ").trim(), true, "ui")
            l=="swipe up" -> TrevorAutomationStep("UI_SWIPE", "0.5,0.8,0.5,0.2", true, "ui")
            l=="swipe down" -> TrevorAutomationStep("UI_SWIPE", "0.5,0.2,0.5,0.8", true, "ui")
            l=="open wifi" || l=="open wi-fi" || l.contains("open wifi settings") -> TrevorAutomationStep("OPEN_WIFI",requiresForeground=true,verify="dispatch")
            l.contains("open bluetooth settings") || l=="open bluetooth" -> TrevorAutomationStep("OPEN_BLUETOOTH",requiresForeground=true,verify="dispatch")
            l.contains("open display settings") -> TrevorAutomationStep("OPEN_DISPLAY",requiresForeground=true,verify="dispatch")
            l.contains("open sound settings") -> TrevorAutomationStep("OPEN_SOUND",requiresForeground=true,verify="dispatch")
            l.contains("open battery") || l.contains("battery saver") -> TrevorAutomationStep("OPEN_BATTERY",requiresForeground=true,verify="dispatch")
            l.contains("open notification settings") -> TrevorAutomationStep("OPEN_NOTIFICATIONS",requiresForeground=true,verify="dispatch")
            l=="open settings" || l.endsWith(" open settings") -> TrevorAutomationStep("OPEN_SETTINGS",requiresForeground=true,verify="dispatch")
            l.startsWith("open app ") -> TrevorAutomationStep("OPEN_APP",o.substringAfter("open app ").trim(),true,"launch")
            l.startsWith("share ") -> TrevorAutomationStep("SHARE_TEXT",o.substringAfter(" ").trim(),true,"dispatch")
            l.startsWith("copy ") -> TrevorAutomationStep("COPY_TEXT",o.substringAfter(" ").trim(),false,"clipboard")
            l.startsWith("analyse my usage") || l.startsWith("analyze my usage") -> TrevorAutomationStep("USAGE_SUMMARY",verify="result")
            l=="show battery" || l=="check battery" -> TrevorAutomationStep("BATTERY_STATUS",verify="result")
            else -> null
        }
    }
    private val argumentActions=setOf("OPEN_APP","SHARE_TEXT","COPY_TEXT","UI_CLICK","UI_CLICK_DESCRIPTION","UI_SWIPE")
    private val verificationContracts=mapOf("OPEN_WIFI" to setOf("dispatch"),"OPEN_BLUETOOTH" to setOf("dispatch"),"OPEN_DISPLAY" to setOf("dispatch"),"OPEN_SOUND" to setOf("dispatch"),"OPEN_BATTERY" to setOf("dispatch"),"OPEN_NOTIFICATIONS" to setOf("dispatch"),"OPEN_SETTINGS" to setOf("dispatch"),"OPEN_APP" to setOf("launch"),"SHARE_TEXT" to setOf("dispatch"),"COPY_TEXT" to setOf("clipboard"),"USAGE_SUMMARY" to setOf("result"),"BATTERY_STATUS" to setOf("result"),"UI_CLICK" to setOf("ui"),"UI_CLICK_DESCRIPTION" to setOf("ui"),"UI_SWIPE" to setOf("ui"))
    private val supportedActions=verificationContracts.keys
}