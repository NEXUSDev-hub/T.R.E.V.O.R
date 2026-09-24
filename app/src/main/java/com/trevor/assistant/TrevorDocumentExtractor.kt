package com.trevor.assistant

import android.content.Context
import android.net.Uri
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.ZipInputStream
import org.xmlpull.v1.XmlPullParser

/** Local, dependency-light extraction for common document formats. */
object TrevorDocumentExtractor {
    private const val MAX_CHARS = 50_000

    fun extract(context: Context, uri: Uri, name: String, mime: String): Result<String> = runCatching {
        when {
            name.endsWith(".docx", true) -> extractDocx(context, uri)
            name.endsWith(".rtf", true) || mime == "application/rtf" -> extractRtf(context, uri)
            name.endsWith(".pdf", true) || mime == "application/pdf" -> extractPdf(context, uri)
            else -> throw IllegalArgumentException("No local text extractor for $mime")
        }.take(MAX_CHARS)
    }

    private fun extractDocx(context: Context, uri: Uri): String {
        val out = StringBuilder()
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open DOCX." }
            ZipInputStream(input).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.name == "word/document.xml") {
                        val parser = android.util.Xml.newPullParser()
                        parser.setInput(zip, "UTF-8")
                        var event = parser.eventType
                        while (event != XmlPullParser.END_DOCUMENT && out.length < MAX_CHARS) {
                            if (event == XmlPullParser.TEXT) {
                                val t = parser.text.trim()
                                if (t.isNotBlank()) out.append(t).append(' ')
                            }
                            event = parser.next()
                        }
                        break
                    }
                }
            }
        }
        return out.toString().trim().ifBlank { throw IllegalArgumentException("DOCX contains no readable text.") }
    }

    private fun extractRtf(context: Context, uri: Uri): String {
        val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use(BufferedReader::readText)
            ?: throw IllegalArgumentException("Unable to open RTF.")
        return raw
            .replace(Regex("\\'[0-9a-fA-F]{2}"), "")
            .replace(Regex("\\[a-zA-Z]+-?\d* ?"), "")
            .replace(Regex("[{}]"), "")
            .replace(Regex("\s+"), " ")
            .trim()
    }

    private fun extractPdf(context: Context, uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("Unable to open PDF.")
        val raw = bytes.toString(Charsets.ISO_8859_1)
        val strings = Regex("\(([^()]*)\)").findAll(raw).map { it.groupValues[1] }
            .filter { it.any(Char::isLetterOrDigit) }
            .joinToString(" ")
            .replace("\n", " ")
            .replace("\r", " ")
            .trim()
        return strings.ifBlank {
            throw IllegalArgumentException("PDF has no directly extractable text. Scanned PDFs require OCR rendering.")
        }
    }
}
