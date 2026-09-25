package com.trevor.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

data class TrevorVerifiedSource(val url: String, val reachable: Boolean, val status: Int?, val note: String)

object TrevorResearchVerifier {
    private val urlRegex = Regex("https?://[^\\s)\\]}>]+")
    suspend fun appendVerification(text: String): String = withContext(Dispatchers.IO) {
        val sources = urlRegex.findAll(text).map { it.value.trimEnd('.', ',', ';') }.distinct().take(8).map { verifyOne(it) }.toList()
        if (sources.isEmpty()) text else text + buildString {
            append("\n\nRESEARCH SOURCES — reachability checked\n")
            sources.forEachIndexed { index, source ->
                append(index + 1).append(". ")
                append(if (source.reachable) "[REACHABLE] " else "[UNREACHABLE] ")
                append(source.url).append(" — ").append(source.note).append('\n')
            }
            append("\nReachability is a network check only; it does not independently validate a source's claims.")
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
        TrevorVerifiedSource(url, code in 200..399, code, "HTTP $code")
    }.getOrElse { TrevorVerifiedSource(url, false, null, "network check failed") }
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
            appendLine("AI: " + s.aiEnabled + "; offline-first: " + s.offlineFirst + "; fixed model: Gemini 3.6 Flash")
            appendLine("AI provider: Google Gemini; model: Gemini 3.6 Flash; proactive: " + s.proactiveEnabled)
            val usage = TrevorProviderLimitTracker.usage(context, TrevorProviderId.GEMINI)
            appendLine("Provider usage: minute=" + usage.minuteRequests + ", day=" + usage.dayRequests +
                ", remainingRequests=" + (usage.remainingRequests?.toString() ?: "not reported") +
                ", remainingTokens=" + (usage.remainingTokens?.toString() ?: "not reported"))
            appendLine("Mode: " + state.currentMode + "; request: " + state.requestState + "; AI: " + state.aiState)
            appendLine("File: " + state.fileState + "; orb: " + state.orbState + "; last error: " + (state.lastError ?: "none"))
        }
    }
}
