package com.trevor.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume

object TrevorDocumentExtractor {
    private const val MAX_CHARS = 80000
    suspend fun extract(context: Context, uri: Uri, name: String, mime: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                when {
                    mime == "application/pdf" || name.endsWith(".pdf", true) -> extractPdf(context, uri)
                    mime.contains("wordprocessingml") || name.endsWith(".docx", true) -> extractDocx(context, uri)
                    name.endsWith(".doc", true) || mime == "application/msword" -> extractLegacyDoc(context, uri)
                    mime.startsWith("image/") -> extractImageOcr(context, uri)
                    else -> context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText().take(MAX_CHARS) }
                        ?: error("Unable to open file.")
                }
            }
        }

    private suspend fun extractPdf(context: Context, uri: Uri): String {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: error("Unable to open PDF.")
        pfd.use {
            PdfRenderer(it).use { renderer ->
                val out = StringBuilder()
                val count = minOf(renderer.pageCount, 30)
                for (i in 0 until count) {
                    renderer.openPage(i).use { page ->
                        if (out.length >= MAX_CHARS) return@use
                        val scale = minOf(1.25f, 1800f / page.width.coerceAtLeast(1))
                        val bitmap = Bitmap.createBitmap(
                            (page.width * scale).toInt().coerceAtLeast(1),
                            (page.height * scale).toInt().coerceAtLeast(1),
                            Bitmap.Config.ARGB_8888
                        )
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val text = TrevorOcr.recognize(bitmap)
                        if (text.isNotBlank()) out.append("\n[Page ").append(i + 1).append("]\n").append(text)
                        bitmap.recycle()
                    }
                }
                out.toString().trim().take(MAX_CHARS).ifBlank {
                    "PDF opened, but no readable text was detected in the first " + count + " pages."
                }
            }
        }
    }

    private fun extractDocx(context: Context, uri: Uri): String {
        val input = context.contentResolver.openInputStream(uri) ?: error("Unable to open DOCX.")
        input.use {
            ZipInputStream(it).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    if (e.name == "word/document.xml") {
                        return zip.readBytes().toString(Charsets.UTF_8)
                            .replace(Regex("<w:tab[^>]*/>"), "\t")
                            .replace(Regex("</w:p>"), "\n")
                            .replace(Regex("<[^>]+>"), "")
                            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                            .replace("&quot;", "\"").replace("&#39;", "'")
                            .take(MAX_CHARS).trim()
                    }
                    e = zip.nextEntry
                }
            }
        }
        error("DOCX document.xml was not found.")
    }

    private fun extractLegacyDoc(context: Context, uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Unable to open DOC.")
        val text = buildString {
            var run = StringBuilder()
            fun flush() {
                val s = run.toString().trim()
                if (s.length >= 3) {
                    if (isNotEmpty()) append(' ')
                    append(s)
                }
                run = StringBuilder()
            }
            bytes.forEach { b ->
                val c = (b.toInt() and 255).toChar()
                if (c.code in 32..126 || c == '\n' || c == '\t') run.append(c) else flush()
                if (run.length > 400) flush()
            }
            flush()
        }.replace(Regex("\\s+"), " ").trim()
        return text.take(MAX_CHARS).ifBlank {
            "Legacy .doc detected, but no recoverable plain text was found."
        }
    }

    private suspend fun extractImageOcr(context: Context, uri: Uri): String {
        val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            ?: error("Unable to decode image.")
        return try { TrevorOcr.recognize(bitmap).take(MAX_CHARS) } finally { bitmap.recycle() }
    }
}

object TrevorOcr {
    suspend fun recognize(bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
        val image = com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap, 0)
        val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(
            com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS
        )
        recognizer.process(image).addOnSuccessListener {
            if (cont.isActive) cont.resume(it.text.trim())
            recognizer.close()
        }.addOnFailureListener {
            if (cont.isActive) cont.resume("")
            recognizer.close()
        }
        cont.invokeOnCancellation { recognizer.close() }
    }
}

data class TrevorVerifiedSource(val url: String, val reachable: Boolean, val status: Int?, val note: String)

object TrevorResearchVerifier {
    private val urlRegex = Regex("https?://[^\\s)\\]}>]+")
    suspend fun appendVerification(text: String): String = withContext(Dispatchers.IO) {
        val sources = urlRegex.findAll(text)
            .map { it.value.trimEnd('.', ',', ';') }
            .distinct().take(8).map { verifyOne(it) }.toList()
        if (sources.isEmpty()) text else text + buildString {
            append("\n\nSource verification (reachability only):\n")
            sources.forEach {
                append(if (it.reachable) "✓ " else "✗ ")
                append(it.url).append(" — ").append(it.note).append('\n')
            }
            append("Reachability does not prove a source's claims are correct.")
        }
    }

    private fun verifyOne(url: String): TrevorVerifiedSource = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.requestMethod = "HEAD"
        c.connectTimeout = 5000
        c.readTimeout = 7000
        val code = c.responseCode
        c.disconnect()
        TrevorVerifiedSource(url, code in 200..399, code, if (code in 200..399) "reachable" else "HTTP " + code)
    }.getOrElse { TrevorVerifiedSource(url, false, null, "unreachable") }
}

object TrevorDeveloperAuth {
    private const val PREFS = "trevor_developer"
    private const val PIN_HASH = "pin_hash"
    private fun digest(pin: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(pin.trim().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun isConfigured(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(PIN_HASH)
    fun setPin(context: Context, pin: String): Boolean {
        if (pin.trim().length < 4) return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(PIN_HASH, digest(pin)).apply()
        return true
    }
    fun verify(context: Context, pin: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PIN_HASH, null) == digest(pin)
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(PIN_HASH).apply()
    }
}

object TrevorErrorEngine {
    enum class Kind { AUTH, QUOTA, RATE_LIMIT, NETWORK, TIMEOUT, SERVER, INPUT, UNKNOWN }
    fun classify(message: String): Kind {
        val m = message.lowercase()
        return when {
            "401" in m || "403" in m || "api key" in m || "permission" in m -> Kind.AUTH
            "429" in m || "quota" in m -> Kind.QUOTA
            "rate" in m -> Kind.RATE_LIMIT
            "timeout" in m -> Kind.TIMEOUT
            "502" in m || "503" in m || "504" in m || "server" in m -> Kind.SERVER
            "connect" in m || "network" in m || "unknownhost" in m -> Kind.NETWORK
            "unsupported" in m || "empty" in m -> Kind.INPUT
            else -> Kind.UNKNOWN
        }
    }
    fun userMessage(message: String): String = when (classify(message)) {
        Kind.AUTH -> "Provider authentication failed. Check the API key and provider."
        Kind.RATE_LIMIT, Kind.QUOTA -> "Provider limits were reached. Auto-routing can try another configured provider."
        Kind.NETWORK -> "Network connection failed. Local tools remain available."
        Kind.TIMEOUT -> "The provider timed out. Retry or allow another provider."
        Kind.SERVER -> "The provider returned a temporary server error. Retry is safe."
        else -> message
    }
}

object TrevorAndroidBridge {
    fun tasker(context: Context, action: String, payload: String = ""): Result<Unit> = runCatching {
        context.sendBroadcast(
            android.content.Intent("net.dinglisch.android.tasker.ACTION_TASK")
                .putExtra("task_name", action).putExtra("trevor_payload", payload)
        )
    }
    fun openSettings(context: Context): Result<Unit> = runCatching {
        context.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
    fun shareText(context: Context, value: String): Result<Unit> = runCatching {
        val send = android.content.Intent(android.content.Intent.ACTION_SEND)
            .setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, value)
        context.startActivity(
            android.content.Intent.createChooser(send, "Share with…")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

object TrevorDiagnostics {
    fun snapshot(context: Context): String {
        val s = TrevorSettingsStore.load(context)
        val state = TrevorStateStore.state.value
        return buildString {
            appendLine(TrevorIdentity.NAME + " " + TrevorVersion.label(context))
            appendLine("Creators: " + TrevorIdentity.CREATORS)
            appendLine("Android: " + android.os.Build.VERSION.RELEASE + " (SDK " + android.os.Build.VERSION.SDK_INT + ")")
            appendLine("Device: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)
            appendLine("AI: " + s.aiEnabled + "; offline-first: " + s.offlineFirst + "; auto-routing: " + s.autoProviderSwitch)
            appendLine("Preferred provider: " + s.preferredProvider + "; proactive: " + s.proactiveEnabled)
            appendLine("Mode: " + state.currentMode + "; request: " + state.requestState + "; AI: " + state.aiState)
            appendLine("File: " + state.fileState + "; orb: " + state.orbState + "; last error: " + (state.lastError ?: "none"))
        }
    }
}

object TrevorMultiFileService {
    suspend fun inspectAll(context: Context, uris: List<Uri>): List<Result<TrevorAttachment>> =
        uris.distinct().take(10).map { TrevorFileService.inspect(context, it) }
}
