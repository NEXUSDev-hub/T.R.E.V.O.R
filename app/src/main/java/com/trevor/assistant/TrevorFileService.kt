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
    private const val MAX_FILE_BYTES = 20L * 1024L * 1024L
    private const val MAX_TEXT_CHARS = 50_000

    suspend fun inspect(context: Context, uri: Uri): Result<TrevorAttachment> =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            var name = "Selected file"
            var size: Long? = null
            try {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                    }
                }
            } catch (_: Exception) {
                return@withContext Result.failure(IllegalArgumentException("Unable to inspect the selected file."))
            }

            if (size != null && size!! > MAX_FILE_BYTES) {
                return@withContext Result.failure(IllegalArgumentException("File is larger than TREVOR's 20 MB Phase 1 input limit."))
            }

            val mime = resolver.getType(uri) ?: guessMime(name)
            val supported = isSupported(name, mime)
            if (!supported) {
                return@withContext Result.failure(IllegalArgumentException("Unsupported file type: $mime"))
            }

            TrevorStateStore.update { it.copy(fileState = TrevorFileState.VALIDATING, orbState = TrevorOrbState.PROCESSING_FILE, lastError = null) }

            val extractable = isTextLike(name, mime) || mime == "application/pdf" ||
                name.endsWith(".doc", true) || name.endsWith(".docx", true) || mime == "application/rtf" || mime.startsWith("image/")
            if (!extractable) {
                TrevorStateStore.update { it.copy(fileState = TrevorFileState.READY, orbState = TrevorOrbState.IDLE) }
                return@withContext Result.success(TrevorAttachment(uri, name, mime, size, null, false))
            }

            TrevorStateStore.update { it.copy(fileState = TrevorFileState.EXTRACTING, orbState = TrevorOrbState.PROCESSING_FILE) }
            val extracted = try {
                if (isTextLike(name, mime)) {
                    resolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                        val out = StringBuilder()
                        val buffer = CharArray(8192)
                        while (out.length < MAX_TEXT_CHARS) {
                            val read = reader.read(buffer, 0, minOf(buffer.size, MAX_TEXT_CHARS - out.length))
                            if (read <= 0) break
                            out.append(buffer, 0, read)
                        }
                        out.toString()
                    } ?: throw IllegalArgumentException("Unable to open the selected file.")
                } else {
                    TrevorDocumentExtractor.extract(context, uri, name, mime).getOrThrow()
                }
            } catch (e: Exception) {
                TrevorStateStore.update { it.copy(fileState = TrevorFileState.ERROR, orbState = TrevorOrbState.ERROR, lastError = e.message ?: "Unable to extract the selected file.") }
                return@withContext Result.failure(IllegalArgumentException(e.message ?: "Unable to extract the selected file."))
            }

            TrevorStateStore.update { it.copy(fileState = TrevorFileState.READY, orbState = TrevorOrbState.IDLE, lastError = null) }
            Result.success(TrevorAttachment(uri, name, mime, size, extracted, true))
        }

    private fun isTextLike(name: String, mime: String): Boolean =
        mime.startsWith("text/") || mime.contains("json") || mime.contains("xml") ||
            mime.contains("javascript") || mime.contains("kotlin") || mime.contains("csv") ||
            name.substringAfterLast('.', "").lowercase() in setOf(
                "txt","md","csv","json","xml","html","css","js","ts","kt","kts","java","py","c","cpp","h","hpp","gradle","properties","yaml","yml","toml","log"
            )

    private fun isSupported(name: String, mime: String): Boolean =
        isTextLike(name, mime) ||
            mime in setOf(
                "application/pdf","application/rtf",
                "image/jpeg","image/png","image/webp","image/bmp",
                "audio/mpeg","audio/wav","audio/ogg",
                "video/mp4","video/webm"
            )

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "jpg","jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "json" -> "application/json"
        "csv" -> "text/csv"
        "html" -> "text/html"
        "css" -> "text/css"
        "js","ts" -> "text/javascript"
        else -> "text/plain"
    }
}
