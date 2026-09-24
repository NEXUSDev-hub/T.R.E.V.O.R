package com.trevor.assistant

import android.content.Context
import kotlinx.coroutines.delay

/** Offline text-input agent: find -> focus -> enter -> observe -> verify. */
object TrevorUiInputAgent {
    private const val WAIT_MS = 350L

    suspend fun enter(context: Context, value: String): Result<String> {
        val service = TrevorAccessibilityService.instance
            ?: return Result.failure(IllegalStateException("Enable TREVOR Accessibility Service first."))
        if (value.isBlank()) return Result.failure(IllegalArgumentException("Text input is blank."))

        val ok = service.enterText(value)
        if (!ok) {
            TrevorOfflineLearning.observe(
                context, "UI text input", service.currentPackage(),
                "UI_TYPE", TrevorOfflineLearning.Outcome.FAILURE,
                "No editable field accepted the requested text."
            )
            return Result.failure(IllegalStateException("No editable UI field accepted the text."))
        }

        delay(WAIT_MS)
        val verified = service.editableContains(value)
        TrevorOfflineLearning.observe(
            context, "UI text input", service.currentPackage(),
            "UI_TYPE", if (verified) TrevorOfflineLearning.Outcome.SUCCESS else TrevorOfflineLearning.Outcome.FAILURE,
            if (verified) "Text appeared in the editable field." else "Text entry could not be verified."
        )
        return if (verified) Result.success("Text entered and verified locally.")
        else Result.failure(IllegalStateException("Text entry was not verified."))
    }
}
