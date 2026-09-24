package com.trevor.assistant

import android.content.Context
import android.net.Uri

data class TrevorMultimodalResult(
    val text: String,
    val visual: TrevorVisualResult?
)

/** Part 14: common entry point for text/file/image understanding. */
object TrevorMultimodalIntelligence {
    suspend fun inspect(context: Context, uri: Uri): Result<TrevorMultimodalResult> {
        val visual = TrevorVisualPipeline.inspect(context, uri).getOrNull()
        if (visual != null) return Result.success(TrevorMultimodalResult(visual.ocrText, visual))
        return Result.failure(IllegalArgumentException("Unsupported or unreadable multimodal input."))
    }
}
