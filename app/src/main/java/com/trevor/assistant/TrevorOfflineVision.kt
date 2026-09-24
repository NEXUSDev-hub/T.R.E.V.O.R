package com.trevor.assistant

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.coroutines.resume

data class TrevorVisualElement(
    val text: String,
    val description: String = "",
    val packageName: String = "",
    val bounds: Rect,
    val source: String,
    val clickable: Boolean = false,
    val scrollable: Boolean = false
)

data class TrevorScreenObservation(
    val packageName: String,
    val width: Int,
    val height: Int,
    val elements: List<TrevorVisualElement>,
    val ocrText: String,
    val timestamp: Long
)

/** Offline screen perception: accessibility semantics + on-device OCR. */
object TrevorOfflineVision {
    private const val MAX_ELEMENTS = 250

    suspend fun observe(service: AccessibilityService): Result<TrevorScreenObservation> {
        if (Build.VERSION.SDK_INT < 30) {
            return Result.failure(IllegalStateException("Live screenshot perception requires Android 11+."))
        }
        return runCatching {
            val screenshot = takeScreenshot(service)
            try {
                val elements = mutableListOf<TrevorVisualElement>()
                collectAccessibility(service.rootInActiveWindow, elements)
                val blocks = TrevorOcr.recognizeBlocks(screenshot)
                blocks.forEach {
                    elements += TrevorVisualElement(
                        text = it.text,
                        bounds = Rect(it.bounds),
                        source = "ocr"
                    )
                }
                val root = service.rootInActiveWindow
                TrevorScreenObservation(
                    packageName = root?.packageName?.toString().orEmpty(),
                    width = screenshot.width,
                    height = screenshot.height,
                    elements = elements.distinctBy {
                        it.text.lowercase(Locale.ROOT) + "|" + it.description.lowercase(Locale.ROOT) +
                            "|" + it.bounds.left + "," + it.bounds.top + "," + it.bounds.right + "," + it.bounds.bottom
                    }.take(MAX_ELEMENTS),
                    ocrText = blocks.joinToString("\n") { it.text }.take(30_000),
                    timestamp = System.currentTimeMillis()
                )
            } finally {
                screenshot.recycle()
            }
        }
    }

    private fun collectAccessibility(node: AccessibilityNodeInfo?, out: MutableList<TrevorVisualElement>) {
        if (node == null || out.size >= MAX_ELEMENTS) return
        val text = node.text?.toString()?.trim().orEmpty().take(240)
        val desc = node.contentDescription?.toString()?.trim().orEmpty().take(240)
        if (text.isNotBlank() || desc.isNotBlank()) {
            val r = Rect()
            node.getBoundsInScreen(r)
            out += TrevorVisualElement(text, desc, node.packageName?.toString().orEmpty(), r,
                "accessibility", node.isClickable || node.isFocusable, node.isScrollable)
        }
        for (i in 0 until node.childCount) {
            collectAccessibility(node.getChild(i), out)
            if (out.size >= MAX_ELEMENTS) break
        }
    }

    private suspend fun takeScreenshot(service: AccessibilityService): Bitmap =
        suspendCancellableCoroutine { continuation ->
            val executor = Executors.newSingleThreadExecutor()
            continuation.invokeOnCancellation { executor.shutdownNow() }
            service.takeScreenshot(0, executor, object : AccessibilityService.TakeScreenshotCallback() {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    executor.shutdown()
                    if (!continuation.isActive) {
                        result.hardwareBuffer.close()
                        return
                    }
                    val bitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    result.hardwareBuffer.close()
                    if (bitmap == null) {
                        continuation.resumeWith(Result.failure(IllegalStateException("Unable to map screenshot.")))
                    } else {
                        continuation.resume(bitmap.copy(Bitmap.Config.ARGB_8888, false))
                        bitmap.recycle()
                    }
                }
                override fun onFailure(errorCode: Int) {
                    executor.shutdown()
                    if (continuation.isActive) continuation.resumeWith(
                        Result.failure(IllegalStateException("Screenshot failed: $errorCode"))
                    )
                }
            })
        }
}
