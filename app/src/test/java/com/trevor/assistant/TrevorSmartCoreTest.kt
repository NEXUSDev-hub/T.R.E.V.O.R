package com.trevor.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrevorSmartCoreTest {
    @Test
    fun offlineFallback_understandsResearchMode() {
        val intent = TrevorSmartCore.classify(
            "Could you check what the latest official information says about this?",
            null
        )
        assertEquals(TrevorSmartCore.IntentKind.RESEARCH, intent.kind)
        assertEquals(TrevorMode.RESEARCH, intent.suggestedMode)
        assertTrue(intent.needsFreshInformation)
    }

    @Test
    fun explicitModeAlwaysWins() {
        val intent = TrevorSmartCore.classify(
            "Why is this happening?",
            TrevorMode.ANALYSE
        )
        assertEquals(TrevorSmartCore.IntentKind.ANALYSIS, intent.kind)
        assertEquals(TrevorMode.ANALYSE, intent.suggestedMode)
    }

    @Test
    fun offlineFallbackRoutesProjectLanguage() {
        val intent = TrevorSmartCore.classify(
            "Help me design the architecture and roadmap for my app.",
            null
        )
        assertEquals(TrevorSmartCore.IntentKind.PROJECT, intent.kind)
        assertEquals(TrevorMode.PROJECT, intent.suggestedMode)
    }
}
