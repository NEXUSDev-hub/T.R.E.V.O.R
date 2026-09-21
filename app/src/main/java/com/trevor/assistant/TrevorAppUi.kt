package com.trevor.assistant

import android.content.Context
import android.os.Bundle
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

private enum class TrevorScreen { DASHBOARD, SETTINGS, API_KEY, DEVELOPER }

private data class ModeVisual(val title: String, val description: String, val accent: Color, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private fun modeVisual(mode: TrevorMode): ModeVisual = when (mode) {
    TrevorMode.NORMAL -> ModeVisual("CORE", "General assistant workspace", Color(0xFF58D9FF), Icons.Filled.AutoAwesome)
    TrevorMode.ANALYSE -> ModeVisual("ANALYSE", "Inspect files, code and information", Color(0xFF7DE7FF), Icons.Filled.Analytics)
    TrevorMode.RESEARCH -> ModeVisual("RESEARCH", "Current answers with Google grounding", Color(0xFF9D8CFF), Icons.Filled.Science)
    TrevorMode.PROJECT -> ModeVisual("PROJECT", "Build, debug and plan without needless rewrites", Color(0xFF6EE7B7), Icons.Filled.Folder)
    TrevorMode.RATIO_SHIFTER -> ModeVisual("RATIO", "Adaptive interface workspace", Color(0xFFFFC66D), Icons.Filled.Transform)
    TrevorMode.TERMINAL -> ModeVisual("TERMINAL", "Controlled Android diagnostics console", Color(0xFF72F0D1), Icons.Filled.Terminal)
}

@Composable
private fun TrevorApp(
    context: Context,
    attachment: TrevorAttachment?,
    onPickFile: () -> Unit,
    onRemoveFile: () -> Unit
) {
    var screen by remember { mutableStateOf(TrevorScreen.DASHBOARD) }
    LaunchedEffect(Unit) { TrevorBackgroundScheduler.ensureScheduled(context) }
    var settings by remember { mutableStateOf(TrevorSettingsStore.load(context)) }
    LaunchedEffect(settings.proactiveEnabled) {
        val intent = android.content.Intent(context, TrevorProactiveService::class.java)
        if (settings.proactiveEnabled) {
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        } else {
            context.stopService(intent)
        }
    }

    fun save(next: TrevorSettings) {
        settings = next
        TrevorSettingsStore.save(context, next)
    }

    BackHandler(enabled = screen != TrevorScreen.DASHBOARD) {
        screen = when (screen) {
            TrevorScreen.API_KEY, TrevorScreen.DEVELOPER -> TrevorScreen.SETTINGS
            else -> TrevorScreen.DASHBOARD
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = settings.accent.color,
        background = Color(0xFF06121E),
        surface = Color(0xFF0B1D2A),
        onSurface = Color(0xFFE8FAFF)
    )) {
        when (screen) {
            TrevorScreen.DASHBOARD -> TrevorDashboard(context, settings, attachment, onPickFile, onRemoveFile) { screen = TrevorScreen.SETTINGS }
            TrevorScreen.SETTINGS -> TrevorSettingsScreen(context, settings, ::save, { screen = TrevorScreen.DASHBOARD }, { screen = TrevorScreen.API_KEY }, { screen = TrevorScreen.DEVELOPER })
            TrevorScreen.API_KEY -> TrevorApiKeyScreen(context) { screen = TrevorScreen.SETTINGS }
            TrevorScreen.DEVELOPER -> TrevorDeveloperScreen(context) { screen = TrevorScreen.SETTINGS }
        }
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
    var mode by remember { mutableStateOf(TrevorMode.NORMAL) }
    var busy by remember { mutableStateOf(false) }
    val state by TrevorStateStore.state.collectAsState()
    val scope = rememberCoroutineScope()
    val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
    val visual = modeVisual(mode)
    var memoryCount by remember { mutableStateOf(0) }
    var taskCount by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        memoryCount = TrevorPersistentMemory.longTermMemory(context).size
        taskCount = TrevorPersistentMemory.pendingTasks(context).size
    }

    fun selectMode(next: TrevorMode) {
        mode = next
        TrevorStateStore.update { it.copy(currentMode = next, orbState = TrevorOrbState.IDLE, lastOutput = null, lastError = null) }
    }

    fun send() {
        val clean = input.trim()
        if (clean.isBlank() || busy) return
        busy = true
        input = ""
        TrevorStateStore.update { it.copy(lastOutput = null, lastError = null, requestState = TrevorRequestState.PROCESSING) }
        scope.launch {
            val prompt = buildString {
                append(clean)
                attachment?.extractedText?.let {
                    append("\n\nAttached text context from ${attachment.name}:\n---\n")
                    append(it)
                    append("\n---")
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
                mode = mode,
                attachment = attachment
            )
            busy = false
            if (result is TrevorCoreResult.Error) {
                // Core has already published the verified error to the shared state.
            }
        }
    }

    Box(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(
                listOf(
                    Color(0xFF04101B), Color(0xFF071B2A), Color(0xFF0A2433),
                    visual.accent.copy(alpha = 0.16f), Color(0xFF06121E)
                )
            )
        )
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("T.R.E.V.O.R", color = Color(0xFFE8FAFF), fontSize = 24.sp, fontWeight = FontWeight.Black)
                        Spacer(Modifier.width(8.dp))
                        StatusPill(visual.title, visual.accent)
                    }
                    Text(TrevorIdentity.FULL_NAME + " • Made by " + TrevorIdentity.CREATORS, color = Color(0xFF9FC4D0), fontSize = 10.sp)
                    Text("${TrevorVersion.label(context)}  •  ${state.orbState.name}", color = visual.accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    Text("Memory $memoryCount  •  Tasks $taskCount  •  Auto-routing ${if (settings.autoProviderSwitch) "ON" else "OFF"}", color = Color(0xFF7FA9B6), fontSize = 9.sp)
                }
                IconButton(onClick = onSettings) {
                    Icon(Icons.Filled.Settings, "Settings", tint = Color(0xFFBCEAF5))
                }
            }

            Spacer(Modifier.height(8.dp))

            if (landscape) {
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TrevorOrbStage(
                        Modifier.weight(1.05f).fillMaxHeight(),
                        settings, mode, state.orbState, visual.accent, ::selectMode
                    )
                    TrevorOutputPanel(
                        Modifier.weight(0.95f).fillMaxHeight(),
                        mode, state.lastOutput, attachment, onRemoveFile, visual.accent, ::selectMode, { prompt -> input = prompt }
                    )
                }
            } else {
                TrevorOrbStage(
                    Modifier.fillMaxWidth().weight(0.88f),
                    settings, mode, state.orbState, visual.accent, ::selectMode
                )
                Spacer(Modifier.height(8.dp))
                TrevorOutputPanel(
                    Modifier.fillMaxWidth().weight(0.48f).heightIn(min = 170.dp),
                    mode, state.lastOutput, attachment, onRemoveFile, visual.accent, ::selectMode, { prompt -> input = prompt }
                )
            }

            Spacer(Modifier.height(9.dp))
            if (attachment != null) AttachmentChip(attachment, onRemoveFile, visual.accent)
            Spacer(Modifier.height(7.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SmallActionButton(Icons.Filled.AttachFile, "Attach", visual.accent, onPickFile, !busy)
                Spacer(Modifier.width(7.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (mode == TrevorMode.TERMINAL) "Enter a terminal command…" else "Ask TREVOR…", color = Color(0xFF7295A2)) },
                    maxLines = 3,
                    shape = RoundedCornerShape(20.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = visual.accent,
                        unfocusedBorderColor = Color(0xFF315465),
                        focusedTextColor = Color(0xFFE8FAFF),
                        unfocusedTextColor = Color(0xFFE8FAFF),
                        cursorColor = visual.accent
                    ),
                    trailingIcon = {
                        if (input.isNotEmpty()) IconButton(onClick = { input = "" }, enabled = !busy) {
                            Icon(Icons.Filled.Close, "Clear", tint = Color(0xFF9FC4D0))
                        }
                    }
                )
                Spacer(Modifier.width(7.dp))
                FilledIconButton(
                    onClick = ::send,
                    enabled = !busy && input.isNotBlank(),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = visual.accent, contentColor = Color(0xFF031018))
                ) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
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
        val density = LocalDensity.current
        val minDimension = minOf(maxWidth.value, maxHeight.value)
        val radius = (minDimension * 0.47f).coerceIn(150f, 255f).dp
        val radiusPx = with(density) { radius.toPx() }
        val angle by rememberInfiniteTransition(label = "orbital").animateFloat(
            0f, 360f,
            infiniteRepeatable(tween(22000, easing = LinearEasing)),
            label = "orbitalAngle"
        )
        val drags = remember { mutableStateMapOf<TrevorMode, Offset>() }

        Box(
            Modifier.align(Alignment.Center)
                .size((minDimension * 0.58f).coerceIn(220f, 300f).dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.15f), Color.Transparent)))
        )

        var orbView by remember { mutableStateOf<TrevorOrbView?>(null) }
        if (settings.orbEnabled) {
            AndroidView(
                modifier = Modifier.size((minDimension * 0.42f).coerceIn(170f, 235f).dp).align(Alignment.Center).semantics { contentDescription = "TREVOR interactive 3D orb. Current state: " + orbState.name },
                factory = { ctx ->
                    TrevorOrbView(ctx).apply {
                        orbView = this
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        setAccent(accent.toArgb())
                        setAnimated(settings.animations)
                        setState(orbState)
                    }
                },
                update = {
                    it.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    it.setAccent(accent.toArgb())
                    it.setAnimated(settings.animations)
                    it.setState(orbState)
                }
            )
        }

        if (settings.orbEnabled) {
            Surface(
                Modifier.align(Alignment.Center).offset(y = 104.dp).clickable { orbView?.resetView() },
                color = Color(0xFF0B2230).copy(alpha = 0.72f),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.35f))
            ) {
                Text("RESET ORB", Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = accent, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            }
        }

        val modes = listOf(TrevorMode.ANALYSE, TrevorMode.RESEARCH, TrevorMode.PROJECT, TrevorMode.TERMINAL)
        modes.forEachIndexed { index, mode ->
            val theta = Math.toRadians((angle + index * 90f - 45f).toDouble())
            val base = Offset((cos(theta) * radiusPx).toFloat(), (sin(theta) * radiusPx).toFloat())
            val drag = drags[mode] ?: Offset.Zero
            val mv = modeVisual(mode)
            Surface(
                Modifier.align(Alignment.Center)
                    .offset { IntOffset((base.x + drag.x).toInt(), (base.y + drag.y).toInt()) }
                    .pointerInput(mode) {
                        detectDragGestures { change, dragAmount ->
                            Unit
                            drags[mode] = (drags[mode] ?: Offset.Zero) + dragAmount
                        }
                    }
                    .clickable { onMode(mode) }
                    .heightIn(min = 50.dp),
                color = Color(0xFF081A28).copy(alpha = if (selectedMode == mode) 0.95f else 0.78f),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, mv.accent.copy(alpha = if (selectedMode == mode) 0.9f else 0.38f)),
                shadowElevation = if (selectedMode == mode) 8.dp else 2.dp
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(mv.icon, null, tint = mv.accent, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(mv.title, color = Color(0xFFDFF9FF), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        val ratio = modeVisual(TrevorMode.RATIO_SHIFTER)
        val ratioDrag = drags[TrevorMode.RATIO_SHIFTER] ?: Offset.Zero
        Surface(
            Modifier.align(Alignment.Center)
                .offset { IntOffset(ratioDrag.x.toInt(), (radiusPx + ratioDrag.y).toInt()) }
                .pointerInput(TrevorMode.RATIO_SHIFTER) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        drags[TrevorMode.RATIO_SHIFTER] = (drags[TrevorMode.RATIO_SHIFTER] ?: Offset.Zero) + dragAmount
                    }
                },
            color = Color(0xFF081A28).copy(alpha = 0.82f),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, ratio.accent.copy(alpha = if (selectedMode == TrevorMode.RATIO_SHIFTER) 0.9f else 0.38f))
        ) {
            Row(
                Modifier.clickable { onMode(TrevorMode.RATIO_SHIFTER) }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(ratio.icon, null, tint = ratio.accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("RATIO SHIFTER", color = Color(0xFFDFF9FF), fontSize = 9.sp, fontWeight = FontWeight.Bold)
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
    onMode: (TrevorMode) -> Unit,
    onTool: (String) -> Unit
) {
    val visual = modeVisual(mode)
    FrostPanel(modifier.animateContentSize(), accent) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(visual.icon, null, tint = visual.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Column(Modifier.weight(1f)) {
                    Text(visual.title + " WORKSPACE", color = Color(0xFFE8FAFF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(visual.description, color = Color(0xFF83AAB7), fontSize = 9.sp)
                }
                if (output != null) StatusPill("OUTPUT", visual.accent)
            }
            Spacer(Modifier.height(10.dp))

            if (output != null) {
                Surface(
                    color = Color(0xFF020A11).copy(alpha = 0.74f),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.22f))
                ) {
                    Text(output, Modifier.fillMaxWidth().padding(13.dp), color = Color(0xFFDDF7FF), fontSize = 12.sp, lineHeight = 18.sp)
                }
                Spacer(Modifier.height(10.dp))
            }

            attachment?.let {
                AttachmentChip(it, onRemoveFile, accent)
                Spacer(Modifier.height(9.dp))
            }

            if (output == null) {
                when (mode) {
                    TrevorMode.TERMINAL -> TerminalWorkspace(accent, onTool)
                    TrevorMode.ANALYSE -> AnalyseWorkspace(attachment, accent, onTool)
                    TrevorMode.RESEARCH -> ResearchWorkspace(accent, onTool)
                    TrevorMode.PROJECT -> ProjectWorkspace(accent, onTool)
                    TrevorMode.RATIO_SHIFTER -> RatioWorkspace(accent, onTool)
                    TrevorMode.NORMAL -> NormalWorkspace(accent, onTool)
                }
            }
        }
    }
}

@Composable
private fun NormalWorkspace(accent: Color, onTool: (String) -> Unit) {
    WorkspaceHeader("GENERAL TOOLS", "Fast local tools first; Gemini when needed.", accent)
    ToolGrid(listOf(
        "Explain" to "Explain this clearly:",
        "Solve" to "Solve this step by step:",
        "Rewrite" to "Rewrite this:",
        "Ask anything" to ""
    ), accent, onTool)
}

@Composable
private fun AnalyseWorkspace(attachment: TrevorAttachment?, accent: Color, onTool: (String) -> Unit) {
    WorkspaceHeader("ANALYSIS TOOLKIT", if (attachment != null) "Attached context is ready." else "Attach a file or analyse pasted information.", accent)
    ToolGrid(listOf(
        "Summarize" to "Summarize the supplied material and extract the key points.",
        "Find issues" to "Analyse the supplied material and identify errors, inconsistencies, or weak assumptions.",
        "Explain code" to "Analyse the supplied code and explain its architecture and important data flow.",
        "Extract facts" to "Extract the important facts, decisions, risks, and open questions."
    ), accent, onTool)
}

@Composable
private fun ResearchWorkspace(accent: Color, onTool: (String) -> Unit) {
    WorkspaceHeader("LIVE RESEARCH", "Gemini 3.8 Flash uses Google Search grounding in Research mode.", accent)
    ToolGrid(listOf(
        "Latest" to "Find the latest verified information about:",
        "Compare sources" to "Research and compare reliable sources about:",
        "Check a claim" to "Check this claim against current web sources and state what is verified:",
        "Deep research" to "Research this topic thoroughly using current web sources:"
    ), accent, onTool)
}

@Composable
private fun ProjectWorkspace(accent: Color, onTool: (String) -> Unit) {
    WorkspaceHeader("PROJECT TOOLKIT", "Preserve working pieces and change only what is necessary.", accent)
    ToolGrid(listOf(
        "Plan" to "Create a practical plan for this project:",
        "Debug" to "Debug this project and identify the most likely root causes:",
        "Architecture" to "Review this architecture and propose only necessary improvements:",
        "Checklist" to "Create a development checklist for this project:"
    ), accent, onTool)
}

@Composable
private fun RatioWorkspace(accent: Color, onTool: (String) -> Unit) {
    WorkspaceHeader("RATIO SHIFTER", "Live layout adapts to portrait, landscape and available space.", accent)
    ToolGrid(listOf(
        "Portrait review" to "Review this interface for compact portrait layout:",
        "Landscape review" to "Review this interface for a wide landscape layout:",
        "Spacing review" to "Find spacing and clipping risks in this responsive UI:",
        "Explain adaptation" to "Explain how this interface should adapt to this screen:"
    ), accent, onTool)
}

@Composable
private fun TerminalWorkspace(accent: Color, onTool: (String) -> Unit) {
    WorkspaceHeader("TREVOR TERMINAL", "Controlled read-only Android diagnostics. Type help to begin.", accent)
    Surface(
        color = Color(0xFF01070B).copy(alpha = 0.92f),
        shape = RoundedCornerShape(15.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.28f))
    ) {
        Text(
            "trevor@android:~\$ help",
            Modifier.fillMaxWidth().padding(13.dp),
            color = accent, fontSize = 12.sp, fontFamily = FontFamily.Monospace
        )
    }
    Spacer(Modifier.height(8.dp))
    ToolGrid(listOf(
        "help" to "help",
        "status" to "status",
        "system" to "uname",
        "workspace" to "pwd"
    ), accent, onTool)
}

@Composable
private fun WorkspaceHeader(title: String, subtitle: String, accent: Color) {
    Text(title, color = accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    Text(subtitle, color = Color(0xFF86AAB6), fontSize = 10.sp)
    Spacer(Modifier.height(7.dp))
}

@Composable
private fun ToolGrid(
    tools: List<Pair<String, String>>,
    accent: Color,
    onTool: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        tools.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                row.forEach { (label, prompt) ->
                    Surface(
                        Modifier.weight(1f).heightIn(min = 48.dp).clickable { onTool(prompt) },
                        color = Color(0xFF0B2230).copy(alpha = 0.78f),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, accent.copy(alpha = 0.20f))
                    ) {
                        Text(label, Modifier.padding(horizontal = 10.dp, vertical = 9.dp), color = Color(0xFFDDF7FF), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AttachmentChip(file: TrevorAttachment, onRemove: () -> Unit, accent: Color) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF0B2230).copy(alpha = 0.82f), RoundedCornerShape(14.dp))
            .border(1.dp, accent.copy(alpha = 0.22f), RoundedCornerShape(14.dp)).padding(start = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.AttachFile, null, tint = accent, modifier = Modifier.size(17.dp))
        Column(Modifier.weight(1f).padding(vertical = 7.dp)) {
            Text(file.name, color = Color(0xFFE8FAFF), fontSize = 10.sp, maxLines = 1)
            Text(file.mimeType + if (file.extractable) " • extracted" else " • Gemini media input", color = Color(0xFF7FA7B4), fontSize = 8.sp)
        }
        IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, "Remove attachment", tint = Color(0xFF9FC4D0)) }
    }
}

@Composable
private fun StatusPill(text: String, accent: Color) {
    Surface(color = accent.copy(alpha = 0.12f), shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, accent.copy(alpha = 0.30f))) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = accent, fontSize = 8.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SmallActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, accent: Color, onClick: () -> Unit, enabled: Boolean) {
    Surface(
        Modifier.height(50.dp).clickable(enabled = enabled, onClick = onClick),
        color = Color(0xFF0B2230).copy(alpha = 0.82f),
        shape = RoundedCornerShape(17.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.25f))
    ) {
        Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, label, tint = accent, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, color = Color(0xFFDDF7FF), fontSize = 8.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun FrostPanel(modifier: Modifier, accent: Color, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.background(Color(0xFF0A1C29).copy(alpha = 0.82f), RoundedCornerShape(24.dp))
            .border(1.dp, accent.copy(alpha = 0.25f), RoundedCornerShape(24.dp)).padding(13.dp),
        content = content
    )
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
        TrevorTopBar("SETTINGS", onBack)
        TrevorSettingsSection("AI + NETWORK", settings.accent.color) {
            TrevorSwitch("AI enabled", settings.aiEnabled) { onChange(settings.copy(aiEnabled = it)) }
            TrevorSwitch("Gemini 3.8 Flash", settings.geminiEnabled) { onChange(settings.copy(geminiEnabled = it)) }
            TrevorSwitch("Offline first", settings.offlineFirst) { onChange(settings.copy(offlineFirst = it)) }
            TrevorSwitch("Automatic provider failover", settings.autoProviderSwitch) { onChange(settings.copy(autoProviderSwitch = it)) }
            TrevorSwitch("Proactive notifications", settings.proactiveNotifications) { onChange(settings.copy(proactiveNotifications = it)) }
            TrevorButton("Gemini API Key", Icons.Filled.Key, onApiKey, settings.accent.color)
        }
        TrevorSettingsSection("INTERFACE", settings.accent.color) {
            TrevorSwitch("Orb enabled", settings.orbEnabled) { onChange(settings.copy(orbEnabled = it)) }
            TrevorSwitch("Orb animations", settings.animations) { onChange(settings.copy(animations = it)) }
            TrevorSwitch("Concise responses", settings.conciseResponses) { onChange(settings.copy(conciseResponses = it)) }
            TrevorSwitch("Technical detail", settings.technicalDetail) { onChange(settings.copy(technicalDetail = it)) }
        }
        TrevorSettingsSection("PROACTIVE PERSONALITY", settings.accent.color) {
            TrevorPersonalityMenu(settings, onChange)
            TrevorSwitch("Proactive behaviour", settings.proactiveEnabled) { onChange(settings.copy(proactiveEnabled = it)) }
            TrevorSwitch("Background notifications", settings.backgroundNotifications) { onChange(settings.copy(backgroundNotifications = it)) }
            if (settings.personality == TrevorPersonality.FOR_YOU) {
                TrevorSwitch("Rubbish mode", settings.rubbishMode) { onChange(settings.copy(rubbishMode = it)) }
                Text("Rubbish mode runs every ${settings.rubbishIntervalSeconds}s while proactive mode is active.", color = Color(0xFF83AAB7), fontSize = 10.sp)
            }
            Text("Professional stays quiet unless useful. Chaotic and Deadpool can initiate assistant messages. Background behaviour is optional and uses an Android foreground service.", color = Color(0xFF83AAB7), fontSize = 10.sp)
        }
        TrevorSettingsSection("DIAGNOSTICS", settings.accent.color) {
            TrevorButton("Developer diagnostics", Icons.Filled.Terminal, onDeveloper, settings.accent.color)
        }
    }
}

@Composable
private fun TrevorApiKeyScreen(context: Context, onBack: () -> Unit) {
    TrevorProviderSetupScreen(context, onBack)
}

@Composable
private fun TrevorDeveloperScreen(context: Context, onBack: () -> Unit) {
    var unlocked by remember { mutableStateOf(!TrevorDeveloperAuth.isConfigured(context)) }
    var pin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    val state by TrevorStateStore.state.collectAsState()
    TrevorIceScreen {
        TrevorTopBar("DIAGNOSTICS", onBack)
        if (!unlocked) {
            TrevorSettingsSection("DEVELOPER LOCK", Color(0xFFFFC66D)) {
                Text("Developer diagnostics are protected by a local PIN.", color = Color(0xFFBFDCE5))
                OutlinedTextField(value = pin, onValueChange = { pin = it }, label = { Text("PIN") }, visualTransformation = PasswordVisualTransformation())
                TrevorButton("Unlock", Icons.Filled.LockOpen, {
                    if (TrevorDeveloperAuth.verify(context, pin)) { unlocked = true; pin = "" }
                    else message = "Incorrect PIN."
                }, Color(0xFFFFC66D))
                if (message.isNotBlank()) Text(message, color = Color(0xFFFF7180))
            }
        } else {
            TrevorSettingsSection("TREVOR FOUNDATION", Color(0xFF58D9FF)) {
                Text("Version: " + TrevorVersion.label(context))
                Text("Identity: " + TrevorIdentity.FULL_NAME)
                Text("Creators: " + TrevorIdentity.CREATORS)
                Text("Gemini model: " + GeminiAiProvider.MODEL)
                Text("Orb: OpenGL ES 2.0 crystal renderer")
                Text("Orb state: " + state.orbState)
                Text("Request: " + state.requestState)
                Text("AI: " + state.aiState)
                Text("File: " + state.fileState)
                Text("Mode: " + state.currentMode)
                Text("Research: Google Search grounding + URL reachability verification")
                Text("Document pipeline: text + PDF OCR + DOCX XML + legacy DOC recovery + image OCR")
                Spacer(Modifier.height(6.dp))
                TrevorButton("Show diagnostics", Icons.Filled.Info, {
                    TrevorStateStore.update { it.copy(lastOutput = TrevorDiagnostics.snapshot(context)) }
                }, Color(0xFF58D9FF))
                TrevorButton("Share diagnostics", Icons.Filled.Share, {
                    TrevorAndroidBridge.shareText(context, TrevorDiagnostics.snapshot(context))
                }, Color(0xFF58D9FF))
                Spacer(Modifier.height(6.dp))
                Text("Set / replace developer PIN", color = Color(0xFF58D9FF), fontWeight = FontWeight.Bold)
                OutlinedTextField(value = newPin, onValueChange = { newPin = it }, label = { Text("New PIN (4+ digits)") }, visualTransformation = PasswordVisualTransformation())
                TrevorButton("Save PIN", Icons.Filled.Password, {
                    message = if (TrevorDeveloperAuth.setPin(context, newPin)) "Developer PIN saved." else "PIN must contain at least 4 characters."
                    newPin = ""
                }, Color(0xFF58D9FF))
                TrevorButton("Lock", Icons.Filled.Lock, { unlocked = false }, Color(0xFFFFC66D))
                if (message.isNotBlank()) Text(message, color = Color(0xFFBFDCE5))
            }
        }
    }
}


@Composable
private fun TrevorPersonalityMenu(settings: TrevorSettings, onChange: (TrevorSettings) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Surface(
            Modifier.fillMaxWidth().clickable { expanded = true },
            color = Color(0xFF0B2230).copy(alpha = 0.82f),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, settings.accent.color.copy(alpha = 0.32f))
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("Personality", color = settings.accent.color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(settings.personality.label, color = Color(0xFFDDF7FF), fontSize = 14.sp)
                Text(settings.personality.description, color = Color(0xFF83AAB7), fontSize = 10.sp)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TrevorPersonality.entries.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.label) },
                    onClick = { onChange(settings.copy(personality = p)); expanded = false }
                )
            }
        }
    }
}

@Composable
private fun TrevorIceScreen(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF061522), Color(0xFF0A2230), Color(0xFF071521)))
        ).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

@Composable
private fun TrevorTopBar(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color(0xFFBCEAF5)) }
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Black, color = Color(0xFFE8FAFF))
    }
}

@Composable
private fun TrevorSettingsSection(title: String, accent: Color, content: @Composable ColumnScope.() -> Unit) {
    FrostPanel(Modifier.fillMaxWidth(), accent) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun TrevorSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), color = Color(0xFFDDF7FF), fontSize = 14.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun TrevorButton(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, accent: Color) {
    Surface(
        Modifier.fillMaxWidth().heightIn(min = 50.dp).clickable(onClick = onClick),
        color = Color(0xFF0B2230).copy(alpha = 0.82f),
        shape = RoundedCornerShape(17.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.25f))
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = accent)
            Spacer(Modifier.width(9.dp))
            Text(title, color = Color(0xFFDDF7FF))
        }
    }
}
