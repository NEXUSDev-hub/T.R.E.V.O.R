package com.trevor.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrevorStructuredPlannerTest {
    @Test
    fun normalizesSingleStepPlan() {
        val plan = TrevorAutomationEngine.plan("open wifi settings")
        assertNotNull(plan)
        assertTrue(plan!!.steps.size == 1)
        assertTrue(TrevorStructuredPlanner.validate(plan).valid)
    }

    @Test
    fun normalizesMultiStepPlan() {
        val plan = TrevorAutomationEngine.plan("open wifi settings, copy hello world, open settings")
        assertNotNull(plan)
        assertTrue(plan!!.steps.size == 3)
        assertTrue(TrevorStructuredPlanner.validate(plan).valid)
    }

    @Test
    fun rejectsUnsupportedAndOversizedPlans() {
        val unsupported = TrevorAutomationPlan(
            "test",
            listOf(TrevorAutomationStep("DELETE_EVERYTHING"))
        )
        assertFalse(TrevorStructuredPlanner.validate(unsupported).valid)

        val tooLarge = TrevorAutomationPlan(
            "test",
            List(21) { TrevorAutomationStep("BATTERY_STATUS", verify = "result") }
        )
        assertFalse(TrevorStructuredPlanner.validate(tooLarge).valid)
    }

    @Test
    fun rejectsMissingArguments() {
        val plan = TrevorAutomationPlan(
            "test",
            listOf(TrevorAutomationStep("COPY_TEXT", verify = "clipboard"))
        )
        assertFalse(TrevorStructuredPlanner.validate(plan).valid)
    }

    @Test
    fun rejectsInvalidVerificationContract() {
        val plan = TrevorAutomationPlan(
            "test",
            listOf(TrevorAutomationStep("BATTERY_STATUS", verify = "activity"))
        )
        assertFalse(TrevorStructuredPlanner.validate(plan).valid)
    }
}
