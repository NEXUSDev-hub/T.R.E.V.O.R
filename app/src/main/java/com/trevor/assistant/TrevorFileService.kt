package com.trevor.assistant

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

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

    fun inspect(context: Context, uri: Uri): Result<TrevorAttachment> {
        val resolver = context.contentResolver
        var name = "Selected file"
        var size: Long? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0) name = c.getString(ni) ?: name
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        }.onFailure { return Result.failure(IllegalArgumentException("Unable to inspect the selected file.")) }

        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val extractable = isTextLike(name, mime)
        TrevorStateStore.update { it.copy(fileState = TrevorFileState.VALIDATING) }

        if (!extractable) {
            TrevorStateStore.update { it.copy(fileState = TrevorFileState.READY, orbState = TrevorOrbState.IDLE, lastError = null) }
            return Result.success(TrevorAttachment(uri, name, mime, size, null, false))
        }

        TrevorStateStore.update { it.copy(fileState = TrevorFileState.EXTRACTING, orbState = TrevorOrbState.PROCESSING_FILE) }
        val extracted = runCatching {
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
        }.getOrElse {
            TrevorStateStore.update { it.copy(fileState = TrevorFileState.ERROR, orbState = TrevorOrbState.ERROR, lastError = "Unable to extract text from the selected file.") }
            return Result.failure(IllegalArgumentException("Unable to extract text from the selected file."))
        }

        TrevorStateStore.update { it.copy(fileState = TrevorFileState.READY, orbState = TrevorOrbState.IDLE, lastError = null) }
        return Result.success(TrevorAttachment(uri, name, mime, size, extracted, true))
    }

    private fun isTextLike(name: String, mime: String): Boolean =
        mime.startsWith("text/") || mime.contains("json") || mime.contains("xml") ||
        mime.contains("javascript") || mime.contains("kotlin") || mime.contains("csv") ||
        name.endsWith(".kt", true) || name.endsWith(".java", true) || name.endsWith(".py", true) ||
        name.endsWith(".js", true) || name.endsWith(".ts", true) || name.endsWith(".md", true) ||
        name.endsWith(".txt", true) || name.endsWith(".csv", true) || name.endsWith(".json", true) ||
        name.endsWith(".xml", true)
}
