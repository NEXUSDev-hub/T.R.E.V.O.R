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
import androidx.compose.material.icons.filled.Info
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
    var showTutorial by remember { mutableStateOf(!context.getSharedPreferences("trevor_onboarding", Context.MODE_PRIVATE).getBoolean("api_tutorial_seen", false)) }
    var modelMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val settings = TrevorSettingsStore.load(context)
    val spec = TrevorProviderRegistry.spec(provider)
    val selectedModel = TrevorProviderRegistry.findModel(provider, if (provider == settings.preferredProvider) settings.selectedModelId else null) ?: spec.models.first()

    fun select(next: TrevorProviderId) {
        provider = next
        key = TrevorProviderKeyStore.load(context, next).orEmpty()
        message = ""
    }

    fun chooseModel(model: TrevorModelSpec) {
        val current = TrevorSettingsStore.load(context)
        TrevorSettingsStore.save(context, current.copy(preferredProvider = provider, selectedModelId = model.id))
        message = "Active model: " + model.label
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

        Text("3 • Choose the model", color = Color(0xFF58D9FF), fontSize = 11.sp)
        Box(Modifier.fillMaxWidth()) {
            Surface(
                Modifier.fillMaxWidth().clickable { modelMenu = true },
                color = Color(0xFF0B2230).copy(alpha = 0.82f),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color(0xFF58D9FF).copy(alpha = 0.32f))
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("Active model", color = Color(0xFF58D9FF), fontSize = 10.sp)
                    Text(selectedModel.label, color = Color.White, fontSize = 14.sp)
                    Text("Provider: " + provider.displayName, color = Color(0xFF83AAB7), fontSize = 10.sp)
                }
            }
            DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                spec.models.forEach { model ->
                    DropdownMenuItem(
                        text = { Text(model.label) },
                        onClick = { chooseModel(model); modelMenu = false }
                    )
                }
            }
        }

        Text("4 • Insert the key", color = Color(0xFF58D9FF), fontSize = 11.sp)
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

        Text("5 • Test the connection", color = Color(0xFF58D9FF), fontSize = 11.sp)
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

        Text("6 • Auto Switch", color = Color(0xFF58D9FF), fontSize = 11.sp)
        Text("When Auto Switch is enabled, TREVOR checks configured providers in its routing order. It reacts to observable errors such as rate limits, quota errors, timeouts and temporary server failures. It does not pretend to know future quota usage.", color = Color(0xFF9FC4D0), fontSize = 11.sp)

        Text("7 • Your chat stays with TREVOR", color = Color(0xFF58D9FF), fontSize = 11.sp)
        Text("Conversation history is stored independently from the AI provider. Gemini → Groq → OpenAI can therefore continue the same TREVOR conversation instead of starting over.", color = Color(0xFF9FC4D0), fontSize = 11.sp)

        Text("8 • Security", color = Color(0xFF58D9FF), fontSize = 11.sp)
        Text("API keys are encrypted with Android Keystore-backed AES/GCM storage. Never publish keys in a public repository, screenshots or chat messages.", color = Color(0xFF9FC4D0), fontSize = 11.sp)

        OutlinedButton(onClick = { showTutorial = true }) {
            Icon(Icons.Filled.Info, null)
            Spacer(Modifier.width(6.dp))
            Text("OPEN API KEY TUTORIAL")
        }

        if (message.isNotBlank()) Text(message, color = if (message.startsWith("✕")) Color(0xFFFF7180) else Color(0xFF72F0D1), fontSize = 12.sp)

        if (showTutorial) {
            AlertDialog(
                onDismissRequest = {
                    showTutorial = false
                    context.getSharedPreferences("trevor_onboarding", Context.MODE_PRIVATE).edit().putBoolean("api_tutorial_seen", true).apply()
                },
                title = { Text("TREVOR API KEY TUTORIAL") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("1. Pick a provider.")
                        Text("2. Open its official API-key page.")
                        Text("3. Sign in and create an API key.")
                        Text("4. Copy it and return to TREVOR.")
                        Text("5. Paste it and tap SAVE SECURELY.")
                        Text("6. Choose the model, then TEST CONNECTION.")
                        Text("7. Never put API keys in GitHub, source code, screenshots, or chat.")
                        Text("Keys are stored locally with Android Keystore-backed encryption.")
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        showTutorial = false
                        context.getSharedPreferences("trevor_onboarding", Context.MODE_PRIVATE).edit().putBoolean("api_tutorial_seen", true).apply()
                    }) { Text("GOT IT") }
                }
            )
        }
    }
}
