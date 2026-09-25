package com.trevor.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.zip.ZipInputStream

object TrevorDocumentExtractor {
    private const val MAX_CHARS = 80_000

    suspend fun extract(context: Context, uri: Uri, name: String, mime: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                when {
                    mime == "application/pdf" || name.endsWith(".pdf", true) -> extractPdf(context, uri)
                    mime.contains("wordprocessingml", true) || name.endsWith(".docx", true) -> extractDocx(context, uri)
                    mime == "application/rtf" || name.endsWith(".rtf", true) -> extractRtf(context, uri)
                    name.endsWith(".doc", true) || mime == "application/msword" -> extractLegacyDoc(context, uri)
                    mime.startsWith("image/") || listOf(".png", ".jpg", ".jpeg", ".webp").any { name.endsWith(it, true) } ->
                        extractImageOcr(context, uri)
                    else -> context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText().take(MAX_CHARS) }
                        ?: error("Unable to open file.")
                }
            }
        }

    private suspend fun extractPdf(context: Context, uri: Uri): String {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: error("Unable to open PDF.")
        return pfd.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                val out = StringBuilder()
                val pages = minOf(renderer.pageCount, 30)
                for (i in 0 until pages) {
                    renderer.openPage(i).use { page ->
                        val maxPixels = 7_000_000.0
                        val scale = minOf(1.5, 1800.0 / page.width.coerceAtLeast(1))
                        val safe = kotlin.math.sqrt(maxPixels / (page.width.toDouble() * page.height.toDouble())).coerceAtMost(1.0)
                        val width = (page.width * scale * safe).toInt().coerceAtLeast(1)
                        val height = (page.height * scale * safe).toInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val text = TrevorOcr.recognize(bitmap)
                        if (text.isNotBlank()) out.append("\n[Page ").append(i + 1).append("]\n").append(text)
                        bitmap.recycle()
                    }
                    if (out.length >= MAX_CHARS) break
                }
                out.toString().trim().take(MAX_CHARS).ifBlank {
                    "PDF opened, but no readable text was detected in the first $pages pages."
                }
            }
        }
    }

    private fun extractDocx(context: Context, uri: Uri): String {
        val input = context.contentResolver.openInputStream(uri) ?: error("Unable to open DOCX.")
        input.use { stream ->
            ZipInputStream(stream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == "word/document.xml") {
                        return zip.readBytes().toString(Charsets.UTF_8)
                            .replace(Regex("<w:tab[^>]*/>"), "\t")
                            .replace(Regex("</w:p>"), "\n")
                            .replace(Regex("<[^>]+>"), "")
                            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                            .replace("&quot;", """).replace("&#39;", "'")
                            .take(MAX_CHARS).trim()
                    }
                    entry = zip.nextEntry
                }
            }
        }
        error("DOCX document.xml was not found.")
    }

    private fun extractRtf(context: Context, uri: Uri): String {
        val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("Unable to open RTF.")
        return raw
            .replace(Regex("""\\'[0-9a-fA-F]{2}"""), "")
            .replace(Regex("""\\[a-zA-Z]+-?\\d* ?"""), "")
            .replace(Regex("""[{}]"""), "")
            .replace(Regex("""\\~"""), " ")
            .replace(Regex("""\\par\b"""), "\n")
            .replace(Regex("""\s+"""), " ")
            .trim().take(MAX_CHARS)
    }

    private fun extractLegacyDoc(context: Context, uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Unable to open DOC.")
        val text = buildString {
            var run = StringBuilder()
            fun flush() {
                val value = run.toString().trim()
                if (value.length >= 3) {
                    if (isNotEmpty()) append(' ')
                    append(value)
                }
                run = StringBuilder()
            }
            bytes.forEach { b ->
                val c = (b.toInt() and 0xff).toChar()
                if (c.code in 32..126 || c == '\n' || c == '\t') run.append(c) else flush()
                if (run.length > 400) flush()
            }
            flush()
        }.replace(Regex("""\s+"""), " ").trim()
        return text.take(MAX_CHARS).ifBlank { "Legacy .doc detected, but no recoverable plain text was found." }
    }

    private suspend fun extractImageOcr(context: Context, uri: Uri): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: error("Unable to open image.")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("Unable to determine image dimensions.")
        val maxPixels = 8_000_000L
        var sample = 1
        while ((bounds.outWidth.toLong() / sample) * (bounds.outHeight.toLong() / sample) > maxPixels) sample *= 2
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("Unable to decode image.")
        return try { TrevorOcr.recognize(bitmap).take(MAX_CHARS) } finally { bitmap.recycle() }
    }
}
