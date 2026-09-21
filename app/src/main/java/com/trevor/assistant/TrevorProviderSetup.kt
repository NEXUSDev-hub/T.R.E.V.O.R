package com.trevor.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

@Composable
fun TrevorProviderSetupScreen(context: Context, onBack: () -> Unit) {
    var key by remember { mutableStateOf(TrevorProviderKeyStore.load(context, TrevorProviderId.GEMINI).orEmpty()) }
    var message by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) { Text("← Back") }
            Spacer(Modifier.width(8.dp))
            Text("GEMINI SETUP", color = Color(0xFFE8FAFF), fontSize = 22.sp)
        }
        Text("TREVOR uses one fixed AI engine: Google Gemini with Gemini 3.6 Flash. There is no provider or model switcher.", color = Color(0xFF9FC4D0), fontSize = 12.sp)
        Text("1 • Get your API key", color = Color(0xFF58D9FF), fontSize = 11.sp)
        Text("Open Google's official AI Studio key page, create/copy your key, then return here. Never put the key in GitHub or source code.", color = Color(0xFF9FC4D0), fontSize = 11.sp)
        Button(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/app/apikey"))) }.onFailure { message = "Could not open the official key page." } }) { Text("OPEN OFFICIAL KEY PAGE") }

        Text("2 • Save locally", color = Color(0xFF58D9FF), fontSize = 11.sp)
        OutlinedTextField(value = key, onValueChange = { key = it; message = "" }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Gemini API key") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (key.isBlank()) message = "API key cannot be empty."
                else {
                    TrevorProviderKeyStore.save(context, TrevorProviderId.GEMINI, key)
                    SecureApiKeyStore.save(context, key)
                    message = "✓ Saved securely on this device."
                }
            }) { Icon(Icons.Filled.Key, null); Spacer(Modifier.width(6.dp)); Text("SAVE SECURELY") }
            OutlinedButton(onClick = { TrevorProviderKeyStore.clear(context, TrevorProviderId.GEMINI); SecureApiKeyStore.clear(context); key = ""; message = "Key removed." }) { Text("REMOVE") }
        }

        Text("3 • Test Gemini 3.6 Flash", color = Color(0xFF58D9FF), fontSize = 11.sp)
        Button(enabled = !testing && key.isNotBlank(), onClick = {
            TrevorProviderKeyStore.save(context, TrevorProviderId.GEMINI, key)
            SecureApiKeyStore.save(context, key)
            testing = true
            message = "Testing Gemini 3.6 Flash…"
            scope.launch {
                val result = TrevorMultiProviderRouter.testSingle(context)
                testing = false
                message = result.fold({ "✓ Gemini 3.6 Flash responded successfully." }, { "✕ Test failed: " + (it.message ?: "Unknown Gemini error.") })
            }
        }) { Text(if (testing) "TESTING…" else "TEST CONNECTION") }

        Text("4 • Security", color = Color(0xFF58D9FF), fontSize = 11.sp)
        Text("The key is stored locally using Android Keystore-backed encryption. TREVOR's identity remains TREVOR; Gemini is only its fixed reasoning engine.", color = Color(0xFF9FC4D0), fontSize = 11.sp)

        if (message.isNotBlank()) Text(message, color = if (message.startsWith("✕")) Color(0xFFFF7180) else Color(0xFF72F0D1), fontSize = 12.sp)

        AlertDialog(
            onDismissRequest = {},
            title = { Text("FIXED AI ENGINE") },
            text = { Text("Google Gemini • Gemini 3.6 Flash\n\nLocal intelligence and automation run first. Gemini is used only when TREVOR needs external reasoning or multimodal/complex analysis.") },
            confirmButton = {}
        )
    }
}
