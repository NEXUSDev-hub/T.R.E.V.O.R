package com.trevor.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrevorAutomationReliabilityTest {
    @Test
    fun retryPolicyHasBoundedAttempts() {
        assertTrue(TrevorAutomationReliability.MAX_ATTEMPTS_PER_STEP == 3)
    }

    @Test
    fun idempotentActionsAreRetrySafe() {
        assertTrue(TrevorAutomationReliability.isRetrySafe("OPEN_WIFI"))
        assertTrue(TrevorAutomationReliability.isRetrySafe("OPEN_APP"))
        assertTrue(TrevorAutomationReliability.isRetrySafe("COPY_TEXT"))
    }

    @Test
    fun nonIdempotentShareIsNotRetried() {
        assertFalse(TrevorAutomationReliability.isRetrySafe("SHARE_TEXT"))
    }
}
