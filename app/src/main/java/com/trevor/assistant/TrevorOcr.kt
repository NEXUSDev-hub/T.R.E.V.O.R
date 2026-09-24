package com.trevor.assistant

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

data class TrevorOcrBlock(val text: String, val bounds: Rect)

/** Fully on-device OCR. No image is uploaded. */
object TrevorOcr {
    suspend fun recognize(bitmap: Bitmap): String =
        recognizeBlocks(bitmap).joinToString("\n") { it.text }

    suspend fun recognizeBlocks(bitmap: Bitmap): List<TrevorOcrBlock> =
        suspendCancellableCoroutine { continuation ->
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    if (continuation.isActive) {
                        continuation.resume(result.textBlocks.flatMap { block ->
                            block.lines.mapNotNull { line ->
                                val text = line.text.trim()
                                val bounds = line.boundingBox
                                if (text.isBlank() || bounds == null) null
                                else TrevorOcrBlock(text.take(240), Rect(bounds))
                            }
                        }.take(300))
                    }
                    recognizer.close()
                }
                .addOnFailureListener { error ->
                    if (continuation.isActive) continuation.resumeWithException(error)
                    recognizer.close()
                }
            continuation.invokeOnCancellation { recognizer.close() }
        }
}
