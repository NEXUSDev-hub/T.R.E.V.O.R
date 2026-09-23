package com.trevor.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrevorParts4And5Test {

    @Test
    fun explicitModesHaveAuthority() {
        assertEquals(
            TrevorSmartCore.IntentKind.RESEARCH,
            TrevorSmartCore.classify("tell me anything", TrevorMode.RESEARCH).kind
        )
        assertEquals(
            TrevorSmartCore.IntentKind.ANALYSIS,
            TrevorSmartCore.classify("tell me anything", TrevorMode.ANALYSE).kind
        )
        assertEquals(
            TrevorSmartCore.IntentKind.PROJECT,
            TrevorSmartCore.classify("tell me anything", TrevorMode.PROJECT).kind
        )
        assertEquals(
            TrevorSmartCore.IntentKind.TERMINAL,
            TrevorSmartCore.classify("tell me anything", TrevorMode.TERMINAL).kind
        )
    }

    @Test
    fun freshInformationAndAttachmentsRequireAdvancedRouting() {
        val research = TrevorSmartCore.classify("verify the latest information today", TrevorMode.NORMAL)
        assertTrue(research.needsFreshInformation)
        assertTrue(research.needsAdvancedModel)

        val attachment = TrevorSmartCore.classify("summarize this", TrevorMode.NORMAL, attachmentPresent = true)
        assertTrue(attachment.needsAdvancedModel)
    }

    @Test
    fun automationPlannerCoversSupportedActions() {
        val commands = listOf(
            "open wifi settings" to "OPEN_WIFI",
            "open bluetooth settings" to "OPEN_BLUETOOTH",
            "open display settings" to "OPEN_DISPLAY",
            "open sound settings" to "OPEN_SOUND",
            "open battery settings" to "OPEN_BATTERY",
            "open notification settings" to "OPEN_NOTIFICATIONS",
            "open settings" to "OPEN_SETTINGS",
            "open app YouTube" to "OPEN_APP",
            "share hello world" to "SHARE_TEXT",
            "copy hello world" to "COPY_TEXT",
            "analyse my usage" to "USAGE_SUMMARY",
            "check battery" to "BATTERY_STATUS"
        )

        commands.forEach { (input, expectedAction) ->
            val plan = TrevorAutomationEngine.plan(input)
            assertNotNull(input, plan)
            assertEquals(input, expectedAction, plan!!.steps.single().action)
            assertTrue(input, TrevorStructuredPlanner.validate(plan).valid)
        }
    }

    @Test
    fun plannerPreservesNaturalArgumentsAndStableIdentity() {
        val first = TrevorAutomationEngine.plan("share rock and roll")
        val second = TrevorAutomationEngine.plan("share rock and roll")
        assertNotNull(first)
        assertNotNull(second)
        assertEquals(first!!.id, second!!.id)
        assertEquals("rock and roll", first.steps.single().argument)
    }

    @Test
    fun plannerRejectsUnsafeOrMalformedStructure() {
        val empty = TrevorAutomationPlan("", emptyList())
        assertFalse(TrevorStructuredPlanner.validate(empty).valid)

        val unsupported = TrevorAutomationPlan(
            "test",
            listOf(TrevorAutomationStep("DELETE_EVERYTHING"))
        )
        assertFalse(TrevorStructuredPlanner.validate(unsupported).valid)

        val invalidVerification = TrevorAutomationPlan(
            "test",
            listOf(TrevorAutomationStep("BATTERY_STATUS", verify = "launch"))
        )
        assertFalse(TrevorStructuredPlanner.validate(invalidVerification).valid)

        val tooLarge = TrevorAutomationPlan(
            "test",
            List(21) { TrevorAutomationStep("BATTERY_STATUS", verify = "result") }
        )
        assertFalse(TrevorStructuredPlanner.validate(tooLarge).valid)
    }
}
