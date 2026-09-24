package com.trevor.assistant

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun TrevorFeatureSurface(context: Context) {
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    var showIntro by remember {
        mutableStateOf(!context.getSharedPreferences("trevor_onboarding", Context.MODE_PRIVATE)
            .getBoolean("full_intro_seen", false))
    }
    var tab by remember { mutableStateOf("TOOLS") }
    val activity = LocalContext.current
    val captureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            TrevorScreenCapture.start(activity, result.resultCode, result.data!!)
        }
    }

    if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            tonalElevation = 6.dp
        ) {
            Column(
                Modifier.fillMaxWidth().padding(10.dp)
                    .heightIn(max = 210.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("TREVOR LANDSCAPE TOOL DECK", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("TOOLS", "TERMINAL", "MODES", "VISUAL").forEach { item ->
                        FilterChip(selected = tab == item, onClick = { tab = item }, label = { Text(item) })
                    }
                }
                when (tab) {
                    "TOOLS" -> TrevorLandscapeTools()
                    "TERMINAL" -> TrevorLandscapeTerminal(context)
                    "MODES" -> TrevorModeTutorial()
                    "VISUAL" -> TrevorVisualControls(activity)
                }
            }
        }
    }

    if (showIntro) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("WELCOME TO T.R.E.V.O.R.") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(TrevorIdentity.FULL_NAME)
                    Text("Created by " + TrevorIdentity.CREATORS + ".")
                    Text("TREVOR is the assistant identity. AI models are engines TREVOR can use; switching models does not rename or recreate TREVOR.")
                    Text("Core features: offline intelligence, AI chat, persistent memory, project memory, files and OCR, research, tasks/reminders, proactive behavior, controlled Android actions, terminal diagnostics, background orb and Live voice.")
                    Text("Modes: NORMAL, ANALYSE, RESEARCH, PROJECT, RATIO SHIFTER and TERMINAL.")
                    Text("Landscape adds scientific tools and the expanded technical deck. Portrait keeps the main interface clean.")
                    Text("API setup: choose a supported free-tier provider, create your own key through its official service, save it locally, then test it. Never put a key in GitHub, source code, screenshots or chat.")
                    Text("Memory is stored separately from the AI provider, so changing models does not erase TREVOR's local conversation/project context.")
                    Text("The proactive system uses current TREVOR state and context to generate dynamic messages instead of selecting only from a fixed sentence list.")
                }
            },
            confirmButton = {
                Button(onClick = {
                    showIntro = false
                    context.getSharedPreferences("trevor_onboarding", Context.MODE_PRIVATE).edit()
                        .putBoolean("full_intro_seen", true)
                        .putBoolean("api_tutorial_seen", true)
                        .apply()
                }) { Text("START USING TREVOR") }
            }
        )
    }
}

@Composable
private fun TrevorLandscapeTools() {
    var degrees by remember { mutableStateOf(false) }
    var expression by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var ratioA by remember { mutableStateOf("") }
    var ratioB by remember { mutableStateOf("") }
    var ratioC by remember { mutableStateOf("") }

    Text("Scientific calculator", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(expression, { expression = it }, Modifier.weight(1f), singleLine = true, label = { Text("Expression") })
        Button(onClick = { result = TrevorScientificCalculator.evaluate(expression, degrees) }) { Text("=") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(degrees, { degrees = true }, label = { Text("DEG") })
        FilterChip(!degrees, { degrees = false }, label = { Text("RAD") })
        Text(result)
    }
    Text("Functions: sin cos tan asin acos atan log ln sqrt abs exp • π • e • ^ • !", style = MaterialTheme.typography.bodySmall)

    Spacer(Modifier.height(6.dp))
    Text("Ratio / scaling workspace", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(ratioA, { ratioA = it }, Modifier.weight(1f), singleLine = true, label = { Text("A") })
        Text(":")
        OutlinedTextField(ratioB, { ratioB = it }, Modifier.weight(1f), singleLine = true, label = { Text("B") })
        Text("=")
        OutlinedTextField(ratioC, { ratioC = it }, Modifier.weight(1f), singleLine = true, label = { Text("C") })
    }
    Button(onClick = {
        val a = ratioA.toDoubleOrNull()
        val b = ratioB.toDoubleOrNull()
        val c = ratioC.toDoubleOrNull()
        result = when {
            a != null && b != null && c != null && a != 0.0 -> "D = " + (b * c / a)
            a != null && b != null && c == null && a != 0.0 -> "D = " + (b * 1.0 / a)
            else -> "Enter A:B=C:D values; leave D blank to solve it."
        }
    }) { Text("CALCULATE") }
}

@Composable
private fun TrevorLandscapeTerminal(context: Context) {
    var command by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("TREVOR TERMINAL READY. Type help.") }
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(command, { command = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Controlled command") })
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = {
                val q = command.trim()
                if (q.isNotBlank()) scope.launch {
                    output = TrevorTerminalService.execute(context, q, TrevorStateStore.state.value)
                }
            }) { Text("EXECUTE") }
            OutlinedButton(onClick = { output = ""; command = "" }) { Text("CLEAR") }
        }
        Text(output, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TrevorModeTutorial() {
    val descriptions = listOf(
        "NORMAL — General TREVOR conversation and everyday assistance.",
        "ANALYSE — Break down information, detect assumptions/errors, and produce actionable conclusions.",
        "RESEARCH — Research-oriented answers with source verification where supported.",
        "PROJECT — Persistent project context, memory, files and engineering work.",
        "RATIO SHIFTER — Ratios, scaling, percentages and responsive-layout calculations.",
        "TERMINAL — Controlled diagnostics and technical commands; it does not provide unrestricted shell access."
    )
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text("MODE TUTORIAL", style = MaterialTheme.typography.titleSmall)
        descriptions.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text("Landscape tools appear only while the device is in landscape orientation.", style = MaterialTheme.typography.bodySmall)
    }
}


@Composable
private fun TrevorVisualControls(context: android.content.Context) {
    var latest by remember { mutableStateOf(TrevorScreenCaptureStore.latest(context)) }
    Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
        Text("VISUAL INTELLIGENCE", style = MaterialTheme.typography.titleSmall)
        Text("User-consented screen capture performs one bounded local OCR pass. TREVOR never starts capture silently.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
            Button(onClick = {
                val intent = TrevorScreenCapture.permissionIntent(context)
                (context as? android.app.Activity)?.let { _ ->
                    // Launcher is owned by the parent composable; this button is replaced by the helper below.
                }
            }) { Text("USE VISUAL MODE") }
            OutlinedButton(onClick = { latest = TrevorScreenCaptureStore.latest(context) }) { Text("REFRESH") }
        }
        if (latest.isNotBlank()) Text(latest.take(6000), style = MaterialTheme.typography.bodySmall)
    }
}
