package com.trevor.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private fun providerHelpUrl(provider: TrevorProviderId): String = when (provider) {
    TrevorProviderId.GEMINI -> "https://aistudio.google.com/app/apikey"
    TrevorProviderId.OPENAI -> "https://platform.openai.com/api-keys"
    TrevorProviderId.ANTHROPIC -> "https://console.anthropic.com/settings/keys"
    TrevorProviderId.XAI -> "https://console.x.ai/"
    TrevorProviderId.MISTRAL -> "https://console.mistral.ai/api-keys/"
    TrevorProviderId.DEEPSEEK -> "https://platform.deepseek.com/api_keys"
    TrevorProviderId.COHERE -> "https://dashboard.cohere.com/api-keys"
    TrevorProviderId.GROQ -> "https://console.groq.com/keys"
    TrevorProviderId.TOGETHER -> "https://api.together.ai/settings/api-keys"
    TrevorProviderId.OPENROUTER -> "https://openrouter.ai/settings/keys"
}

@Composable
fun TrevorProviderSetupScreen(context: Context, onBack: () -> Unit) {
    var provider by remember { mutableStateOf(TrevorProviderId.GEMINI) }
    var key by remember { mutableStateOf(TrevorProviderKeyStore.load(context, provider).orEmpty()) }
    var message by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val spec = TrevorProviderRegistry.spec(provider)

    fun select(next: TrevorProviderId) {
        provider = next
        key = TrevorProviderKeyStore.load(context, next).orEmpty()
        message = ""
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) { Text("← Back") }
            Spacer(Modifier.width(8.dp))
            Text("AI PROVIDER SETUP", color = Color(0xFFE8FAFF), fontSize = 22.sp)
        }
        Text("TREVOR can use multiple providers. Configure one or several. Auto Switch keeps the conversation in TREVOR while changing providers when a configured provider reaches a detected limit or temporary failure.", color = Color(0xFF9FC4D0), fontSize = 12.sp)

        Text("1 • Choose a provider", color = Color(0xFF58D9FF), fontSize = 11.sp)
        TrevorProviderRegistry.providers.forEach { p ->
            Surface(Modifier.fillMaxWidth().clickable { select(p.id) }, color = if (p.id == provider) Color(0xFF123244) else Color(0xFF0B2230), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Color(0xFF58D9FF).copy(alpha = if (p.id == provider) 0.7f else 0.18f))) {
                Row(Modifier.padding(12.dp)) {
                    Text(p.displayName, Modifier.weight(1f), color = Color.White)
                    if (!TrevorProviderKeyStore.load(context, p.id).isNullOrBlank()) Icon(Icons.Filled.CheckCircle, "Configured", tint = Color(0xFF72F0D1))
                }
            }
        }

        Text("2 • Get your API key", color = Color(0xFF58D9FF), fontSize = 11.sp)
        Text("Open the provider's official key page, sign in, create/copy an API key, then return here. TREVOR never needs you to paste a key into GitHub or source code.", color = Color(0xFF9FC4D0), fontSize = 11.sp)
        Button(onClick = {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(providerHelpUrl(provider)))) }
                .onFailure { message = "Could not open the provider page." }
        }) { Text("OPEN OFFICIAL KEY PAGE") }

        Text("3 • Insert the key", color = Color(0xFF58D9FF), fontSize = 11.sp)
        OutlinedTextField(value = key, onValueChange = { key = it; message = "" }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(spec.displayName + " API key") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (key.isBlank()) message = "API key cannot be empty."
                else {
                    TrevorProviderKeyStore.save(context, provider, key)
                    if (provider == TrevorProviderId.GEMINI) SecureApiKeyStore.save(context, key)
                    message = "Saved securely on this device."
                }
            }) { Icon(Icons.Filled.Key, null); Spacer(Modifier.width(6.dp)); Text("SAVE SECURELY") }
            OutlinedButton(onClick = {
                TrevorProviderKeyStore.clear(context, provider)
                if (provider == TrevorProviderId.GEMINI) SecureApiKeyStore.clear(context)
                key = ""
                message = "Key removed."
            }) { Text("REMOVE") }
        }

        Text("4 • Test the connection", color = Color(0xFF58D9FF), fontSize = 11.sp)
        Button(enabled = !testing && key.isNotBlank(), onClick = {
            TrevorProviderKeyStore.save(context, provider, key)
            if (provider == TrevorProviderId.GEMINI) SecureApiKeyStore.save(context, key)
            testing = true
            message = "Testing " + spec.displayName + " • " + spec.models.first().label + "…"
            scope.launch {
                val result = TrevorMultiProviderRouter.testSingle(context, provider)
                testing = false
                message = result.fold(
                    { "✓ Connection successful. " + spec.models.first().label + " responded." },
                    { "✕ Test failed: " + (it.message ?: "Unknown provider error.") }
                )
            }
        }) { Text(if (testing) "TESTING…" else "TEST CONNECTION") }

        Text("5 • Auto Switch", color = Color(0xFF58D9FF), fontSize = 11.sp)
        Text("When Auto Switch is enabled, TREVOR checks configured providers in its routing order. It reacts to observable errors such as rate limits, quota errors, timeouts and temporary server failures. It does not pretend to know future quota usage.", color = Color(0xFF9FC4D0), fontSize = 11.sp)

        Text("6 • Your chat stays with TREVOR", color = Color(0xFF58D9FF), fontSize = 11.sp)
        Text("Conversation history is stored independently from the AI provider. Gemini → Groq → OpenAI can therefore continue the same TREVOR conversation instead of starting over.", color = Color(0xFF9FC4D0), fontSize = 11.sp)

        Text("7 • Security", color = Color(0xFF58D9FF), fontSize = 11.sp)
        Text("API keys are encrypted with Android Keystore-backed AES/GCM storage. Never publish keys in a public repository, screenshots or chat messages.", color = Color(0xFF9FC4D0), fontSize = 11.sp)

        if (message.isNotBlank()) Text(message, color = if (message.startsWith("✕")) Color(0xFFFF7180) else Color(0xFF72F0D1), fontSize = 12.sp)
    }
}
