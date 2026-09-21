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
            try {
                val value = when {
                    mime == "application/pdf" || name.endsWith(".pdf", true) -> extractPdf(context, uri)
                    mime.contains("wordprocessingml") || name.endsWith(".docx", true) -> extractDocx(context, uri)
                    name.endsWith(".doc", true) || mime == "application/msword" -> extractLegacyDoc(context, uri)
                    mime.startsWith("image/") -> extractImageOcr(context, uri)
                    else -> context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText().take(MAX_CHARS) }
                        ?: error("Unable to open file.")
                }
                Result.success(value)
            } catch (e: Exception) {
                Result.failure(e)
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
                        val width = (page.width * scale).toInt().coerceAtLeast(1)
                        val height = (page.height * scale).toInt().coerceAtLeast(1)
                        val maxPixels = 7_000_000L
                        val safeScale = if (width.toLong() * height > maxPixels) {
                            kotlin.math.sqrt(maxPixels.toDouble() / (page.width.toDouble() * page.height.toDouble()))
                        } else 1.0
                        val safeWidth = (page.width * scale * safeScale).toInt().coerceAtLeast(1)
                        val safeHeight = (page.height * scale * safeScale).toInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(safeWidth, safeHeight, Bitmap.Config.ARGB_8888)
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
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: error("Unable to open image.")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("Unable to determine image dimensions.")
        val maxPixels = 8_000_000L
        var sample = 1
        while ((bounds.outWidth.toLong() / sample) * (bounds.outHeight.toLong() / sample) > maxPixels) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
        val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
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
        var code = c.responseCode
        c.disconnect()
        if (code == 405 || code == 501) {
            val get = URL(url).openConnection() as HttpURLConnection
            get.instanceFollowRedirects = true
            get.requestMethod = "GET"
            get.connectTimeout = 5000
            get.readTimeout = 7000
            get.setRequestProperty("Range", "bytes=0-0")
            code = get.responseCode
            get.inputStream?.close()
            get.disconnect()
        }
        TrevorVerifiedSource(url, code in 200..399, code, if (code in 200..399) "reachable" else "HTTP " + code)
    }.getOrElse { TrevorVerifiedSource(url, false, null, "unreachable") }
}

object TrevorDeveloperAuth {
    private const val PREFS = "trevor_developer"
    private const val PIN_HASH = "pin_hash"
    private const val PIN_SALT = "pin_salt"
    private const val PIN_ITERATIONS = 120_000

    private fun legacyDigest(pin: String): ByteArray =
        java.security.MessageDigest.getInstance("SHA-256").digest(pin.trim().toByteArray(Charsets.UTF_8))

    private fun derive(pin: String, salt: ByteArray): ByteArray =
        javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(javax.crypto.spec.PBEKeySpec(pin.trim().toCharArray(), salt, PIN_ITERATIONS, 256))
            .encoded

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean =
        java.security.MessageDigest.isEqual(a, b)

    fun isConfigured(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(PIN_HASH)

    fun setPin(context: Context, pin: String): Boolean {
        val clean = pin.trim()
        if (clean.length < 4) return false
        val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val hash = derive(clean, salt)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(PIN_HASH, android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP))
            .putString(PIN_SALT, android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP))
            .apply()
        return true
    }

    fun verify(context: Context, pin: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(PIN_HASH, null) ?: return false
        val saltEncoded = prefs.getString(PIN_SALT, null)
        if (saltEncoded == null) {
            // Migrate an older SHA-256 PIN after a successful verification.
            val ok = constantTimeEquals(legacyDigest(pin), runCatching {
                android.util.Base64.decode(stored, android.util.Base64.NO_WRAP)
            }.getOrElse { hexToBytes(stored) })
            if (ok) setPin(context, pin)
            return ok
        }
        val expected = runCatching { android.util.Base64.decode(stored, android.util.Base64.NO_WRAP) }.getOrNull() ?: return false
        val salt = runCatching { android.util.Base64.decode(saltEncoded, android.util.Base64.NO_WRAP) }.getOrNull() ?: return false
        return constantTimeEquals(derive(pin, salt), expected)
    }

    private fun hexToBytes(hex: String): ByteArray {
        if (hex.length % 2 != 0) return ByteArray(0)
        return ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(PIN_HASH).remove(PIN_SALT).apply()
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

data class TrevorActionResult(val requested: String, val dispatched: Boolean, val verified: Boolean, val detail: String)

object TrevorActionVerifier {
    fun verifyDispatch(context: Context, action: String): TrevorActionResult {
        val pm = context.packageManager
        val intent = android.content.Intent(action)
        val resolvable = intent.resolveActivity(pm) != null
        return TrevorActionResult(action, resolvable, resolvable, if (resolvable) "Intent handler available." else "No handler is installed.")
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
