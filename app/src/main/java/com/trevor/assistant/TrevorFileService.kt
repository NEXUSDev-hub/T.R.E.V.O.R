package com.trevor.assistant

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class TrevorAttachment(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long?,
    val extractedText: String?,
    val extractable: Boolean
)

object TrevorFileService {
    private const val MAX_TEXT_CHARS = 50_000

    suspend fun inspect(context: Context, uri: Uri): Result<TrevorAttachment> =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            var name = "Selected file"
            var size: Long? = null

            try {
                resolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                            size = cursor.getLong(sizeIndex)
                        }
                    }
                }
            } catch (_: Exception) {
                return@withContext Result.failure(
                    IllegalArgumentException("Unable to inspect the selected file.")
                )
            }

            val mime = resolver.getType(uri) ?: "application/octet-stream"
            val extractable = isTextLike(name, mime)

            TrevorStateStore.update {
                it.copy(fileState = TrevorFileState.VALIDATING, orbState = TrevorOrbState.PROCESSING_FILE, lastError = null)
            }

            if (!extractable) {
                TrevorStateStore.update {
                    it.copy(fileState = TrevorFileState.READY, orbState = TrevorOrbState.IDLE, lastError = null)
                }
                return@withContext Result.success(
                    TrevorAttachment(uri, name, mime, size, null, false)
                )
            }

            TrevorStateStore.update {
                it.copy(fileState = TrevorFileState.EXTRACTING, orbState = TrevorOrbState.PROCESSING_FILE)
            }

            val extracted = try {
                resolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                    val out = StringBuilder()
                    val buffer = CharArray(8192)
                    while (out.length < MAX_TEXT_CHARS) {
                        val remaining = MAX_TEXT_CHARS - out.length
                        val read = reader.read(buffer, 0, minOf(buffer.size, remaining))
                        if (read <= 0) break
                        out.append(buffer, 0, read)
                    }
                    out.toString()
                } ?: throw IllegalArgumentException("Unable to open the selected file.")
            } catch (_: Exception) {
                TrevorStateStore.update {
                    it.copy(
                        fileState = TrevorFileState.ERROR,
                        orbState = TrevorOrbState.ERROR,
                        lastError = "Unable to extract text from the selected file."
                    )
                }
                return@withContext Result.failure(
                    IllegalArgumentException("Unable to extract text from the selected file.")
                )
            }

            TrevorStateStore.update {
                it.copy(fileState = TrevorFileState.READY, orbState = TrevorOrbState.IDLE, lastError = null)
            }

            Result.success(
                TrevorAttachment(uri, name, mime, size, extracted, true)
            )
        }

    private fun isTextLike(name: String, mime: String): Boolean =
        mime.startsWith("text/") ||
            mime.contains("json") ||
            mime.contains("xml") ||
            mime.contains("javascript") ||
            mime.contains("kotlin") ||
            mime.contains("csv") ||
            name.endsWith(".kt", true) ||
            name.endsWith(".java", true) ||
            name.endsWith(".py", true) ||
            name.endsWith(".js", true) ||
            name.endsWith(".ts", true) ||
            name.endsWith(".md", true) ||
            name.endsWith(".txt", true) ||
            name.endsWith(".csv", true) ||
            name.endsWith(".json", true) ||
            name.endsWith(".xml", true)
}
