package com.trevor.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class TrevorVisualResult(
    val width: Int,
    val height: Int,
    val ocrText: String,
    val hasReadableText: Boolean
)

/** Part 9/10: bounded visual understanding pipeline. */
object TrevorVisualPipeline {
    private const val MAX_PIXELS = 8_000_000L
    private const val MAX_TEXT = 30_000

    suspend fun inspect(context: Context, uri: Uri): Result<TrevorVisualResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                } ?: error("Unable to open image.")
                require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Invalid image." }
                var sample = 1
                while ((bounds.outWidth.toLong() / sample) * (bounds.outHeight.toLong() / sample) > MAX_PIXELS) sample *= 2
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                val bitmap = context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                } ?: error("Unable to decode image.")
                try {
                    val text = TrevorOcr.recognize(bitmap).take(MAX_TEXT)
                    TrevorVisualResult(bitmap.width, bitmap.height, text, text.isNotBlank())
                } finally { bitmap.recycle() }
            }
        }
}
