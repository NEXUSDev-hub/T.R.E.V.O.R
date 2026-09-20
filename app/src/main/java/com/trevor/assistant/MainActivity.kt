package com.trevor.assistant

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Transform
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.platform.LocalConfiguration
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

private enum class TrevorScreen { DASHBOARD, SETTINGS, API_KEY, DEVELOPER }

class MainActivity : ComponentActivity() {
    private var attachment by mutableStateOf<TrevorAttachment?>(null)
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            TrevorFileService.inspect(this@MainActivity, uri)
                .onSuccess { attachment = it }
                .onFailure { error ->
                    attachment = null
                    TrevorStateStore.update {
                        it.copy(
                            fileState = TrevorFileState.ERROR,
                            orbState = TrevorOrbState.ERROR,
                            lastError = error.message
                        )
                    }
                }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TrevorApp(
                this,
                attachment,
                { picker.launch(arrayOf("*/*")) },
                {
                    attachment = null
                    TrevorStateStore.update {
                        it.copy(
                            fileState = TrevorFileState.NONE,
                            orbState = TrevorOrbState.IDLE,
                            lastError = null
                        )
                    }
                }
            )
        }
    }
}

@Composable
private fun TrevorApp(context: Context, attachment: TrevorAttachment?, onPickFile: () -> Unit, onRemoveFile: () -> Unit) {
    var screen by remember { mutableStateOf(TrevorScreen.DASHBOARD) }
    var settings by remember { mutableStateOf(TrevorSettingsStore.load(context)) }

    fun save(next: TrevorSettings) {
        settings = next
        TrevorSettingsStore.save(context, next)
    }

    BackHandler(enabled = screen != TrevorScreen.DASHBOARD) {
        screen = if (screen == TrevorScreen.API_KEY || screen == TrevorScreen.DEVELOPER) TrevorScreen.SETTINGS else TrevorScreen.DASHBOARD
    }

    when (screen) {
        TrevorScreen.DASHBOARD -> TrevorDashboard(context, settings, attachment, onPickFile, onRemoveFile) { screen = TrevorScreen.SETTINGS }
        TrevorScreen.SETTINGS -> TrevorSettingsScreen(context, settings, ::save, { screen = TrevorScreen.DASHBOARD }, { screen = TrevorScreen.API_KEY }, { screen = TrevorScreen.DEVELOPER })
        TrevorScreen.API_KEY -> TrevorApiKeyScreen(context) { screen = TrevorScreen.SETTINGS }
        TrevorScreen.DEVELOPER -> TrevorDeveloperScreen(context) { screen = TrevorScreen.SETTINGS }
    }
}

@Composable
private fun TrevorDashboard(
    context: Context,
    settings: TrevorSettings,
    attachment: TrevorAttachment?,
    onPickFile: () -> Unit,
    onRemoveFile: () -> Unit,
    onSettings: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    var output by remember { mutableStateOf<String?>(null) }
    var mode by remember { mutableStateOf(TrevorMode.NORMAL) }
    var busy by remember { mutableStateOf(false) }
    val state by TrevorStateStore.state.collectAsState()
    val scope = rememberCoroutineScope()
    val accent = settings.accent.color
    val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp

    fun send() {
        val clean = input.trim()
        if (clean.isBlank() || busy) return
        busy = true
        output = null
        input = ""
        TrevorStateStore.update { it.copy(orbState = TrevorOrbState.THINKING, requestState = TrevorRequestState.PROCESSING) }
        scope.launch {
            val prompt = buildString {
                append(clean)
                attachment?.let {
                    append("\n\nAttached file: " + it.name + " (" + it.mimeType + ")")
                    it.extractedText?.let { text ->
                        append("\n--- extracted content (max 50,000 chars) ---\n")
                        append(text)
                    }
                }
            }
            val result = TrevorCore.process(
                context = context,
                command = prompt,
                aiEnabled = settings.aiEnabled,
                geminiEnabled = settings.geminiEnabled,
                conciseResponses = settings.conciseResponses,
                technicalDetail = settings.technicalDetail,
                offlineFirst = settings.offlineFirst,
                mode = mode.takeUnless { it == TrevorMode.NORMAL }
            )
            output = when (result) {
                is TrevorCoreResult.Answer -> result.text
                is TrevorCoreResult.Error -> "ERROR\n" + result.message
            }
            busy = false
        }
    }

    Box(
        Modifier.fillMaxSize().background(
            Brush.radialGradient(listOf(Color(0xFFF8FEFF), Color(0xFFE5F6FA), Color(0xFFD1EAF0), Color(0xFFC5E0E7)))
        )
    ) {
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("T.R.E.V.O.R", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Color(0xFF173A48))
                    Text("The Really Efficient Virtual Operation Robot", fontSize = 10.sp, color = Color(0xFF55727D))
                    Text(
                        TrevorVersion.label(context) + "  •  " + state.orbState.name,
                        fontSize = 10.sp, color = accent, fontWeight = FontWeight.SemiBold
                    )
                }
                IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings", tint = Color(0xFF31515D)) }
            }

            if (landscape) {
                Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    TrevorOrbStage(
                        Modifier.weight(1.05f).fillMaxHeight(),
                        settings,
                        mode,
                        state.orbState,
                        accent
                    ) { selected ->
                        mode = selected
                        TrevorStateStore.update {
                            it.copy(
                                currentMode = selected,
                                orbState = TrevorOrbState.IDLE,
                                lastError = null
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    TrevorOutputPanel(
                        Modifier.weight(0.95f).fillMaxHeight(),
                        mode, output, attachment, onRemoveFile, accent
                    ) { toolPrompt ->
                        input = toolPrompt
                    }
                }
            } else {
                TrevorOrbStage(
                    Modifier.fillMaxWidth().weight(1f),
                    settings,
                    mode,
                    state.orbState,
                    accent
                ) { selected ->
                    mode = selected
                    TrevorStateStore.update {
                        it.copy(
                            currentMode = selected,
                            orbState = TrevorOrbState.IDLE,
                            lastError = null
                        )
                    }
                }
                TrevorOutputPanel(
                    Modifier.fillMaxWidth().heightIn(min = 105.dp, max = 230.dp),
                    mode, output, attachment, onRemoveFile, accent
                ) { toolPrompt ->
                    input = toolPrompt
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPickFile, enabled = !busy) { Icon(Icons.Filled.AttachFile, "Attach file", tint = accent) }
                OutlinedTextField(
                    value = input, onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask TREVOR…") },
                    maxLines = 3,
                    shape = RoundedCornerShape(26.dp),
                    trailingIcon = {
                        if (input.isNotEmpty()) {
                            IconButton(onClick = { input = "" }, enabled = !busy) {
                                Icon(Icons.Filled.Close, "Clear command")
                            }
                        }
                    }
                )
                IconButton(onClick = ::send, enabled = !busy && input.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = accent)
                }
            }
        }
    }
}

@Composable
private fun TrevorOrbStage(
    modifier: Modifier,
    settings: TrevorSettings,
    selectedMode: TrevorMode,
    orbState: TrevorOrbState,
    accent: Color,
    onMode: (TrevorMode) -> Unit
) {
    BoxWithConstraints(modifier) {
        val minDimension = minOf(maxWidth.value, maxHeight.value)
        val radius = (minDimension * 0.36f).coerceIn(112f, 182f).dp
        val angle = rememberInfiniteTransition(label = "satellites").animateFloat(
            0f, 360f,
            androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(18000, easing = androidx.compose.animation.core.LinearEasing)
            ),
            label = "satelliteAngle"
        ).value

        var orbView by remember { mutableStateOf<TrevorOrbView?>(null) }

        if (settings.orbEnabled) {
            AndroidView(
                modifier = Modifier.size(if (maxWidth < 380.dp) 190.dp else 215.dp).align(Alignment.Center),
                factory = { ctx ->
                    TrevorOrbView(ctx).apply {
                        orbView = this
                        setAccent(accent.toArgb())
                        setAnimated(settings.animations)
                        setState(orbState)
                    }
                },
                update = {
                    it.setAccent(accent.toArgb())
                    it.setAnimated(settings.animations)
                    it.setState(orbState)
                }
            )
        }

        if (settings.orbEnabled) {
            IconButton(
                onClick = { orbView?.resetView() },
                modifier = Modifier.align(Alignment.Center).offset(y = 122.dp)
            ) {
                Icon(Icons.Filled.Refresh, "Reset orb view", tint = accent)
            }
        }

        listOf(
            Triple("ANALYSE", Icons.Filled.Analytics, TrevorMode.ANALYSE),
            Triple("RESEARCH", Icons.Filled.Science, TrevorMode.RESEARCH),
            Triple("PROJECT", Icons.Filled.Folder, TrevorMode.PROJECT),
            Triple("RATIO SHIFTER", Icons.Filled.Transform, TrevorMode.RATIO_SHIFTER)
        ).forEachIndexed { index, item ->
            val a = Math.toRadians((angle + index * 90f - 45f).toDouble())
            val x = (cos(a) * radius.value).toInt()
            val y = (sin(a) * radius.value).toInt()
            Surface(
                modifier = Modifier.align(Alignment.Center).offset { IntOffset(x, y) }
                    .border(1.dp, accent.copy(alpha = if (selectedMode == item.third) 0.75f else 0.25f), RoundedCornerShape(22.dp))
                    .clickable(role = androidx.compose.ui.semantics.Role.Button) { onMode(item.third) }
                    .heightIn(min = 48.dp),
                color = Color.White.copy(alpha = if (selectedMode == item.third) 0.78f else 0.48f),
                shadowElevation = 4.dp,
                shape = RoundedCornerShape(22.dp)
            ) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(item.second, null, tint = accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(item.first, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFF31515D))
                }
            }
        }
    }
}

@Composable
private fun TrevorOutputPanel(
    modifier: Modifier,
    mode: TrevorMode,
    output: String?,
    attachment: TrevorAttachment?,
    onRemoveFile: () -> Unit,
    accent: Color,
    onTool: (String) -> Unit
) {
    GlassPanel(modifier.animateContentSize(), 0.58f, accent.copy(alpha = 0.3f)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(
                if (output == null) mode.name + " WORKSPACE" else "TREVOR OUTPUT",
                color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(7.dp))

            if (output == null) {
                TrevorModeWorkspace(mode, attachment, accent, onTool)
                Spacer(Modifier.height(8.dp))
            }

            attachment?.let {
                Row(
                    Modifier.fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.42f), RoundedCornerShape(14.dp))
                        .padding(7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.AttachFile, null, tint = accent, modifier = Modifier.size(16.dp))
                    Text(it.name, Modifier.weight(1f), fontSize = 11.sp, color = Color(0xFF31515D))
                    IconButton(onClick = onRemoveFile, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Close, "Remove attachment")
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            Text(
                output ?: when (mode) {
                    TrevorMode.NORMAL -> "Ready. Ask TREVOR anything."
                    TrevorMode.ANALYSE -> if (attachment?.extractable == true)
                        "File loaded. Choose an analysis tool or write your own instruction."
                    else
                        "Attach a supported text/code file to use file analysis."
                    TrevorMode.RESEARCH -> "Research workspace. Enter a topic or use a research action below. Web grounding is a Phase 2 capability."
                    TrevorMode.PROJECT -> "Project workspace. Use planning/debug/review actions with the current AI and file tools."
                    TrevorMode.RATIO_SHIFTER -> "Adaptive layout is active. The interface is already responding to the device orientation and available space."
                },
                color = if (output?.startsWith("ERROR") == true) Color(0xFF9A3340) else Color(0xFF294A55),
                fontSize = 13.sp, lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun TrevorModeWorkspace(
    mode: TrevorMode,
    attachment: TrevorAttachment?,
    accent: Color,
    onTool: (String) -> Unit
) {
    when (mode) {
        TrevorMode.NORMAL -> {
            Text("GENERAL ASSISTANT", color = Color(0xFF49636C), fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            TrevorToolGrid(
                listOf(
                    "Explain a topic" to "Explain this clearly:",
                    "Solve a problem" to "Solve this step by step:",
                    "Write / rewrite" to "Help me write:",
                    "Ask anything" to ""
                ), accent, onTool
            )
        }
        TrevorMode.ANALYSE -> {
            Text("FILE + INFORMATION ANALYSIS", color = Color(0xFF49636C), fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text(
                if (attachment?.extractable == true) "Extracted text is available to TREVOR." else "No extractable text file attached.",
                fontSize = 11.sp, color = Color(0xFF49636C)
            )
            Spacer(Modifier.height(5.dp))
            TrevorToolGrid(
                listOf(
                    "Summarize" to "Summarize the attached file and identify the key points.",
                    "Find issues" to "Analyse the attached file and identify errors, inconsistencies, or suspicious assumptions.",
                    "Explain code" to "Analyse the attached code and explain how it works, including important functions and data flow.",
                    "Key points" to "Extract the most important facts, decisions, risks, and open questions from the attached file."
                ), accent, onTool
            )
        }
        TrevorMode.RESEARCH -> {
            Text("RESEARCH WORKSPACE", color = Color(0xFF49636C), fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text("Current tools: Gemini reasoning + supported file context. Search grounding is not enabled yet.", fontSize = 11.sp, color = Color(0xFF49636C))
            Spacer(Modifier.height(5.dp))
            TrevorToolGrid(
                listOf(
                    "Research topic" to "Research this topic carefully and separate established facts from uncertainty:",
                    "Compare" to "Compare these topics using clear criteria and explain the evidence:",
                    "Check claims" to "Examine these claims, identify what is established, uncertain, or needs verification:",
                    "Research file" to "Analyse the attached file as research material and identify its claims, evidence, and gaps."
                ), accent, onTool
            )
        }
        TrevorMode.PROJECT -> {
            Text("PROJECT WORKSPACE", color = Color(0xFF49636C), fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text("Current tools: Gemini + file context + TREVOR's project-mode routing. Persistent project memory comes later.", fontSize = 11.sp, color = Color(0xFF49636C))
            Spacer(Modifier.height(5.dp))
            TrevorToolGrid(
                listOf(
                    "Plan project" to "Help me create a practical project plan for:",
                    "Debug" to "Help me debug this project. Analyse the attached code/context and identify likely problems:",
                    "Architecture" to "Review this project's architecture and suggest necessary improvements without unnecessary restructuring:",
                    "Checklist" to "Create a development checklist for this project based on the supplied context:"
                ), accent, onTool
            )
        }
        TrevorMode.RATIO_SHIFTER -> {
            Text("ADAPTIVE UI WORKSPACE", color = Color(0xFF49636C), fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text("Ratio Shifter uses the available screen dimensions to adapt the orb, satellites, workspace, output and input layout.", fontSize = 11.sp, color = Color(0xFF49636C))
            Spacer(Modifier.height(5.dp))
            TrevorToolGrid(
                listOf(
                    "Explain layout" to "Explain how TREVOR should adapt its UI for this device:",
                    "Portrait plan" to "Create a compact portrait layout plan for TREVOR:",
                    "Landscape plan" to "Create a wide landscape layout plan for TREVOR:",
                    "Responsive review" to "Review this TREVOR UI requirement for responsive layout problems:"
                ), accent, onTool
            )
        }
    }
}

@Composable
private fun TrevorToolGrid(
    tools: List<Pair<String, String>>,
    accent: Color,
    onTool: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        tools.chunked(2).forEach { rowTools ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowTools.forEach { (label, prompt) ->
                    Surface(
                        modifier = Modifier.weight(1f)
                            .clickable(role = androidx.compose.ui.semantics.Role.Button) { onTool(prompt) }
                            .heightIn(min = 48.dp),
                        color = Color.White.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.22f))
                    ) {
                        Text(
                            label, Modifier.padding(horizontal = 9.dp, vertical = 8.dp),
                            color = Color(0xFF31515D), fontSize = 10.sp, fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                if (rowTools.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TrevorSettingsScreen(
    context: Context,
    settings: TrevorSettings,
    onChange: (TrevorSettings) -> Unit,
    onBack: () -> Unit,
    onApiKey: () -> Unit,
    onDeveloper: () -> Unit
) {
    TrevorIceScreen {
        TrevorTopBar("Settings", onBack)
        TrevorSettingsSection("AI & NETWORK", settings.accent.color) {
            TrevorSwitch("AI enabled", settings.aiEnabled) { onChange(settings.copy(aiEnabled = it)) }
            TrevorSwitch("Gemini enabled", settings.geminiEnabled) { onChange(settings.copy(geminiEnabled = it)) }
            TrevorSwitch("Offline first", settings.offlineFirst) { onChange(settings.copy(offlineFirst = it)) }
            TrevorButton("Gemini API Key", Icons.Filled.Settings, onApiKey, settings.accent.color)
        }
        TrevorSettingsSection("INTERFACE & ORB", settings.accent.color) {
            TrevorSwitch("Orb enabled", settings.orbEnabled) { onChange(settings.copy(orbEnabled = it)) }
            TrevorSwitch("Orb animations", settings.animations) { onChange(settings.copy(animations = it)) }
            TrevorSwitch("Concise responses", settings.conciseResponses) { onChange(settings.copy(conciseResponses = it)) }
            TrevorSwitch("Technical detail", settings.technicalDetail) { onChange(settings.copy(technicalDetail = it)) }
        }
        TrevorSettingsSection("DEVELOPER", settings.accent.color) {
            TrevorButton("Developer diagnostics", Icons.Filled.Settings, onDeveloper, settings.accent.color)
        }
    }
}

@Composable
private fun TrevorApiKeyScreen(context: Context, onBack: () -> Unit) {
    var key by remember { mutableStateOf(SecureApiKeyStore.load(context) ?: "") }
    var show by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    TrevorIceScreen {
        TrevorTopBar("Gemini API Key", onBack)
        TrevorSettingsSection("SECURE STORAGE", Color(0xFF58D9FF)) {
            Text("Stored locally using Android Keystore + AES/GCM.", fontSize = 12.sp, color = Color(0xFF49636C))
            OutlinedTextField(
                value = key, onValueChange = { key = it; message = "" },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { show = !show }) { Icon(if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, "Show or hide key") }
                }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (key.isBlank()) message = "API key cannot be empty."
                    else { SecureApiKeyStore.save(context, key.trim()); message = "API key saved." }
                }) { Text("Save") }
                OutlinedButton(onClick = { SecureApiKeyStore.clear(context); key = ""; message = "API key removed." }) { Text("Remove") }
            }
            if (message.isNotBlank()) Text(message, color = Color(0xFF32748A), fontSize = 12.sp)
        }
    }
}

@Composable
private fun TrevorDeveloperScreen(context: Context, onBack: () -> Unit) {
    val state by TrevorStateStore.state.collectAsState()
    TrevorIceScreen {
        TrevorTopBar("Diagnostics", onBack)
        TrevorSettingsSection("PHASE 1 FOUNDATION", Color(0xFF58D9FF)) {
            Text("Version: " + TrevorVersion.label(context))
            Text("Orb: native OpenGL ES 2.0 3D")
            Text("Orb state: " + state.orbState)
            Text("Request state: " + state.requestState)
            Text("AI state: " + state.aiState)
            Text("File state: " + state.fileState)
            Text("Mode: " + state.currentMode)
            state.lastError?.let { Text("Last error: " + it, color = Color(0xFF9A3340)) }
            Text("File pipeline: SAF → validation → extraction → attachment → Core")
        }
    }
}

@Composable
private fun TrevorIceScreen(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFFF9FEFF), Color(0xFFE4F5F9), Color(0xFFD2EAF0))))
            .verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content
    )
}

@Composable
private fun TrevorTopBar(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color(0xFF31515D)) }
        Text(title, fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Color(0xFF173A48))
    }
}

@Composable
private fun TrevorSettingsSection(title: String, accent: Color, content: @Composable ColumnScope.() -> Unit) {
    GlassPanel(Modifier.fillMaxWidth(), 0.62f, accent.copy(alpha = 0.3f)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = {
            Text(title, color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            content()
        })
    }
}

@Composable
private fun TrevorSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), color = Color(0xFF2B4A54), fontSize = 14.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun TrevorButton(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, accent: Color) {
    Row(
        Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.46f), RoundedCornerShape(22.dp))
            .border(1.dp, accent.copy(alpha = 0.24f), RoundedCornerShape(22.dp)).clickable(onClick = onClick).padding(11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = accent); Spacer(Modifier.width(9.dp)); Text(title, color = Color(0xFF294A55))
    }
}

@Composable
private fun GlassPanel(modifier: Modifier, alpha: Float, borderColor: Color, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.background(Color.White.copy(alpha = alpha), RoundedCornerShape(26.dp))
            .border(1.dp, borderColor, RoundedCornerShape(26.dp)).padding(12.dp),
        content = content
    )
}
