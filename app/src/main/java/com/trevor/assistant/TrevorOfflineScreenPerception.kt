package com.trevor.assistant

import android.graphics.Bitmap

/** Adds local visual-control detection to the existing screen observation pipeline. */
object TrevorOfflineScreenPerception {
    suspend fun observe(service: TrevorAccessibilityService): Result<TrevorScreenObservation> {
        val base = TrevorOfflineVision.observe(service)
        if (base.isFailure) return base
        return runCatching {
            // Re-capture is intentionally avoided here; accessibility/OCR remain the
            // authoritative text layer. The visual model can be fed frames by callers
            // that already own a screenshot through analyzeFrame().
            base.getOrThrow()
        }
    }

    fun analyzeFrame(bitmap: Bitmap, ocr: List<TrevorOcrBlock> = emptyList()): List<TrevorVisualControl> =
        TrevorLocalVisualModel.detect(bitmap, ocr)
}
