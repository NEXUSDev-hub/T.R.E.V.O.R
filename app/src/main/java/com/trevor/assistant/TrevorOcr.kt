package com.trevor.assistant

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Part 9/14: on-device OCR. No image is sent to a cloud service. */
object TrevorOcr {
    suspend fun recognize(bitmap: Bitmap): String =
        suspendCancellableCoroutine { continuation ->
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val task = recognizer.process(InputImage.fromBitmap(bitmap, 0))
            task.addOnSuccessListener { result ->
                if (continuation.isActive) continuation.resume(result.text)
                recognizer.close()
            }.addOnFailureListener { error ->
                if (continuation.isActive) continuation.resumeWithException(error)
                recognizer.close()
            }
            continuation.invokeOnCancellation { recognizer.close() }
        }
}
