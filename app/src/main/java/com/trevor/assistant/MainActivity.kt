package com.trevor.assistant

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Transform
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

private enum class Screen { DASHBOARD, SETTINGS, DEVELOPER, API_KEY }

private enum class Accent(val label: String, val color: Color) {
    ICE("Ice Cyan", Color(0xFF58D9FF)),
    SKY("Polar Sky", Color(0xFF5B8CFF)),
    VIOLET("Aurora Violet", Color(0xFF9B7BFF));
    companion object {
        fun from(value: String): Accent = entries.firstOrNull { it.name == value } ?: ICE
    }
}

private data class TrevorSettings(
    val aiEnabled: Boolean = true,
    val geminiEnabled: Boolean = true,
    val orbEnabled: Boolean = true,
    val developerMode: Boolean = false,
    val debugMode: Boolean = false,
    val developerConsole: Boolean = false,
    val animations: Boolean = true,
    val conciseResponses: Boolean = true,
    val technicalDetail: Boolean = true,
    val showStatusIndicators: Boolean = true,
    val showQuickActions: Boolean = true,
    val offlineFirst: Boolean = true,
    val secureStorage: Boolean = true,
    val localApiKeyEncryption: Boolean = true,
    val iceFrost: Boolean = true,
    val accent: Accent = Accent.ICE
)

private object TrevorSettingsStore {
    private const val PREFS = "trevor_settings"

    fun load(context: Context): TrevorSettings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return TrevorSettings(
            aiEnabled = p.getBoolean("aiEnabled", true),
            geminiEnabled = p.getBoolean("geminiEnabled", true),
            orbEnabled = p.getBoolean("orbEnabled", true),
            developerMode = p.getBoolean("developerMode", false),
            debugMode = p.getBoolean("debugMode", false),
            developerConsole = p.getBoolean("developerConsole", false),
            animations = p.getBoolean("animations", true),
            conciseResponses = p.getBoolean("conciseResponses", true),
            technicalDetail = p.getBoolean("technicalDetail", true),
            showStatusIndicators = p.getBoolean("showStatusIndicators", true),
            showQuickActions = p.getBoolean("showQuickActions", true),
            offlineFirst = p.getBoolean("offlineFirst", true),
            secureStorage = p.getBoolean("secureStorage", true),
            localApiKeyEncryption = p.getBoolean("localApiKeyEncryption", true),
            iceFrost = p.getBoolean("iceFrost", true),
            accent = Accent.from(p.getString("accent", Accent.ICE.name) ?: Accent.ICE.name)
        )
    }

    fun save(context: Context, s: TrevorSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("aiEnabled", s.aiEnabled)
            .putBoolean("geminiEnabled", s.geminiEnabled)
            .putBoolean("orbEnabled", s.orbEnabled)
            .putBoolean("developerMode", s.developerMode)
            .putBoolean("debugMode", s.debugMode)
            .putBoolean("developerConsole", s.developerConsole)
            .putBoolean("animations", s.animations)
            .putBoolean("conciseResponses", s.conciseResponses)
            .putBoolean("technicalDetail", s.technicalDetail)
            .putBoolean("showStatusIndicators", s.showStatusIndicators)
            .putBoolean("showQuickActions", s.showQuickActions)
            .putBoolean("offlineFirst", s.offlineFirst)
            .putBoolean("secureStorage", s.secureStorage)
            .putBoolean("localApiKeyEncryption", s.localApiKeyEncryption)
            .putBoolean("iceFrost", s.iceFrost)
            .putString("accent", s.accent.name)
            .apply()
    }
}

private data class SelectedFile(
    val uri: Uri,
    val name: String,
    val mime: String,
    val size: Long?,
    val extractedText: String?
)

class MainActivity : ComponentActivity() {
    private var selectedFile by mutableStateOf<SelectedFile?>(null)

    private val filePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) selectedFile = inspectFile(this, uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TrevorApp(
                context = this,
                onPickFile = { filePicker.launch(arrayOf("*/*")) },
                selectedFile = selectedFile,
                onRemoveFile = { selectedFile = null; TrevorStateStore.update { it.copy(fileState = TrevorFileState.NONE, orbState = TrevorOrbState.IDLE, lastError = null) } }
            )
        }
    }
}

private fun inspectFile(context: Context, uri: Uri): SelectedFile {
    val resolver = context.contentResolver
    var name = "Selected file"
    var size: Long? = null

    resolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
        null,
        null,
        null
    )?.use { c ->
        if (c.moveToFirst()) {
            val nameIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = c.getColumnIndex(OpenableColumns.SIZE)
            if (nameIndex >= 0) name = c.getString(nameIndex) ?: name
            if (sizeIndex >= 0 && !c.isNull(sizeIndex)) size = c.getLong(sizeIndex)
        }
    }

    val mime = resolver.getType(uri) ?: "application/octet-stream"
    val textLike = mime.startsWith("text/") ||
        mime.contains("json") ||
        mime.contains("xml") ||
        name.endsWith(".kt", true) ||
        name.endsWith(".java", true) ||
        name.endsWith(".py", true) ||
        name.endsWith(".md", true) ||
        name.endsWith(".csv", true)

    TrevorStateStore.update { it.copy(fileState = TrevorFileState.VALIDATING) }
    val extracted = if (textLike) {
        TrevorStateStore.update { it.copy(fileState = TrevorFileState.EXTRACTING, orbState = TrevorOrbState.PROCESSING_FILE) }
        runCatching {
            resolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                val out = StringBuilder()
                val buffer = CharArray(8192)
                while (out.length < 50_000) {
                    val remaining = 50_000 - out.length
                    val read = reader.read(buffer, 0, minOf(buffer.size, remaining))
                    if (read <= 0) break
                    out.append(buffer, 0, read)
                }
                out.toString()
            }
        }.getOrNull()
    } else {
        null
    }
    TrevorStateStore.update {
        it.copy(
            fileState = if (!textLike || extracted != null) TrevorFileState.READY else TrevorFileState.ERROR,
            orbState = if (textLike && extracted == null) TrevorOrbState.ERROR else TrevorOrbState.IDLE,
            lastError = if (textLike && extracted == null) "Unable to extract text from the selected file." else null
        )
    }
    return SelectedFile(uri, name, mime, size, extracted)
}

@Composable
private fun TrevorApp(
    context: Context,
    onPickFile: () -> Unit,
    selectedFile: SelectedFile?,
    onRemoveFile: () -> Unit
) {
    var screen by remember { mutableStateOf(Screen.DASHBOARD) }
    var settings by remember { mutableStateOf(TrevorSettingsStore.load(context)) }

    BackHandler(enabled = screen != Screen.DASHBOARD) {
        screen = if (screen == Screen.API_KEY) Screen.SETTINGS else Screen.DASHBOARD
    }

    fun updateSettings(value: TrevorSettings) {
        settings = value
        TrevorSettingsStore.save(context, value)
    }

    when (screen) {
        Screen.DASHBOARD -> DashboardScreen(
            context = context,
            settings = settings,
            onPickFile = onPickFile,
            selectedFile = selectedFile,
            onRemoveFile = onRemoveFile,
            onSettings = { screen = Screen.SETTINGS },
            onDeveloper = { screen = Screen.DEVELOPER }
        )
        Screen.SETTINGS -> SettingsScreen(
            settings = settings,
            onChange = ::updateSettings,
            onBack = { screen = Screen.DASHBOARD },
            onApiKey = { screen = Screen.API_KEY },
            onDeveloper = { screen = Screen.DEVELOPER }
        )
        Screen.DEVELOPER -> DeveloperScreen(context, settings) { screen = Screen.DASHBOARD }
        Screen.API_KEY -> ApiKeyScreen(context) { screen = Screen.SETTINGS }
    }
}

@Composable
private fun DashboardScreen(
    context: Context,
    settings: TrevorSettings,
    onPickFile: () -> Unit,
    selectedFile: SelectedFile?,
    onRemoveFile: () -> Unit,
    onSettings: () -> Unit,
    onDeveloper: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(TrevorMode.NORMAL) }
    var status by remember { mutableStateOf("READY") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val palette = settings.accent.color

    fun send() {
        val clean = input.trim()
        if (clean.isBlank() || busy) return
        busy = true
        status = "PROCESSING • " + mode.name
        scope.launch {
            val injected = selectedFile?.extractedText?.let {
                clean + "\n\nAttached file: " + selectedFile.name + "\n" + it
            } ?: selectedFile?.let {
                clean + "\n\nAttached file metadata: " + it.name + " (" + it.mime + ")"
            } ?: clean
            val result = TrevorCore.process(
                context = context,
                command = injected,
                aiEnabled = settings.aiEnabled,
                geminiEnabled = settings.geminiEnabled,
                conciseResponses = settings.conciseResponses,
                technicalDetail = settings.technicalDetail,
                    mode = mode.takeUnless { it == TrevorMode.NORMAL }
            )
            status = when (result) {
                is TrevorCoreResult.Answer -> "READY • " + mode.name
                is TrevorCoreResult.Error -> "ERROR • " + result.message.lines().firstOrNull().orEmpty()
            }
            busy = false
        }
    }

    val infinite = rememberInfiniteTransition(label = "orbit")
    val orbitAngle by infinite.animateFloat(
        0f,
        360f,
        infiniteRepeatable(tween(24_000, easing = LinearEasing)),
        label = "orbitAngle"
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(
                if (settings.iceFrost) {
                    Brush.radialGradient(listOf(Color(0xFFF9FEFF), Color(0xFFE8F7FB), Color(0xFFD7EEF4), Color(0xFFC7E3EA)))
                } else {
                    Brush.radialGradient(listOf(Color.White, Color(0xFFF4F6F7)))
                }
            )
    ) {
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "T.R.E.V.O.R",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF173A48)
                    )
                    Text(
                        "The Really Efficient Virtual Operation Robot",
                        fontSize = 11.sp,
                        color = Color(0xFF55727D)
                    )
                    if (settings.showStatusIndicators) {
                        Text(
                            TrevorVersion.label(context) + "  •  " + status,
                            fontSize = 10.sp,
                            color = palette,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                if (settings.developerMode || settings.debugMode) {
                    IconButton(onClick = onDeveloper) {
                        Icon(Icons.Filled.DeveloperMode, "Developer mode", tint = Color(0xFF31515D))
                    }
                }
                IconButton(onClick = onSettings) {
                    Icon(Icons.Filled.Settings, "Settings", tint = Color(0xFF31515D))
                }
            }

            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(38.dp))
            ) {
                if (settings.orbEnabled) {
                    GlassPanel(
                        Modifier.align(Alignment.Center).size(220.dp),
                        alpha = 0.22f
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            var orbView by remember { mutableStateOf<TrevorOrbView?>(null) }
                            val lifecycleOwner = LocalLifecycleOwner.current
                            DisposableEffect(lifecycleOwner, orbView) {
                                val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                                    when (event) {
                                        androidx.lifecycle.Lifecycle.Event.ON_RESUME -> orbView?.onResume()
                                        androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> orbView?.onPause()
                                        else -> Unit
                                    }
                                }
                                lifecycleOwner.lifecycle.addObserver(observer)
                                onDispose { lifecycleOwner.lifecycle.removeObserver(observer); orbView?.onPause() }
                            }
                            AndroidView(
                                modifier = Modifier.fillMaxSize().padding(10.dp).clip(CircleShape),
                                factory = { ctx ->
                                    TrevorOrbView(ctx).also { orbView = it }.apply {
                                        setAccent(palette.toArgb())
                                        setAnimated(settings.animations)
                                    }
                                },
                                update = {
                                    orbView = it
                                    it.setAccent(palette.toArgb())
                                    it.setAnimated(settings.animations)
                                    it.setState(if (busy) TrevorOrbState.THINKING else TrevorOrbState.IDLE)
                                }
                            )
                            IconButton(
                                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(42.dp),
                                onClick = { orbView?.resetView() }
                            ) {
                                Icon(Icons.Filled.Refresh, "Reset Orb", tint = palette)
                            }
                        }
                    }

                    OrbitMode("ANALYSE", Icons.Filled.Analytics, TrevorMode.ANALYSE, orbitAngle, 45f, palette, mode == TrevorMode.ANALYSE) { mode = TrevorMode.ANALYSE }
                    OrbitMode("RESEARCH", Icons.Filled.Science, TrevorMode.RESEARCH, orbitAngle, 135f, palette, mode == TrevorMode.RESEARCH) { mode = TrevorMode.RESEARCH }
                    OrbitMode("PROJECT", Icons.Filled.Folder, TrevorMode.PROJECT, orbitAngle, 225f, palette, mode == TrevorMode.PROJECT) { mode = TrevorMode.PROJECT }
                    OrbitMode("RATIO SHIFTER", Icons.Filled.Transform, TrevorMode.RATIO_SHIFTER, orbitAngle, 315f, palette, mode == TrevorMode.RATIO_SHIFTER) { mode = TrevorMode.RATIO_SHIFTER }
                }

                GlassPanel(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 6.dp),
                    alpha = 0.44f
                ) {
                    Text(modeDescription(mode), color = Color(0xFF294A55), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }

            selectedFile?.let { file ->
                GlassPanel(Modifier.fillMaxWidth().padding(bottom = 8.dp), alpha = 0.55f) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.AttachFile, null, tint = palette)
                        Column(Modifier.weight(1f)) {
                            Text(file.name, fontWeight = FontWeight.SemiBold, color = Color(0xFF183944), maxLines = 1)
                            Text(
                                if (file.extractedText != null) "Validated • text extracted" else "Validated • attachment ready",
                                fontSize = 10.sp,
                                color = Color(0xFF5B737B)
                            )
                        }
                        IconButton(onClick = onRemoveFile) {
                            Icon(Icons.Filled.Close, "Remove file", tint = Color(0xFF526B73))
                        }
                    }
                }
            }

            GlassPanel(Modifier.fillMaxWidth(), alpha = 0.70f) {
                Row(verticalAlignment = Alignment.Bottom) {
                    IconButton(onClick = onPickFile, enabled = !busy) {
                        Icon(Icons.Filled.AttachFile, "Inject file", tint = palette)
                    }
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Command TREVOR…", color = Color(0xFF69818A)) },
                        maxLines = 4,
                        shape = RoundedCornerShape(28.dp)
                    )
                    IconButton(onClick = { input = "" }, enabled = input.isNotEmpty()) {
                        Icon(Icons.Filled.Refresh, "Clear input", tint = Color(0xFF58727B))
                    }
                    IconButton(onClick = { send() }, enabled = input.isNotBlank() && !busy) {
                        Icon(Icons.Filled.Send, "Send", tint = palette)
                    }
                }
            }
        }
    }
}

private fun modeDescription(mode: TrevorMode): String = when (mode) {
    TrevorMode.NORMAL -> "NORMAL • conversational fallback"
    TrevorMode.ANALYSE -> "ANALYSE • inspect maths, science, code, data and files"
    TrevorMode.RESEARCH -> "RESEARCH • investigate information, sources and comparisons"
    TrevorMode.PROJECT -> "PROJECT • plan, build, organise and track work"
    TrevorMode.RATIO_SHIFTER -> "RATIO SHIFTER • adapt layout and transform proportional values"
}

@Composable
private fun BoxScope.OrbitMode(
    name: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    mode: TrevorMode,
    angle: Float,
    phase: Float,
    accent: Color,
    selected: Boolean,
    onClick: () -> Unit
) {
    val radians = Math.toRadians((angle + phase).toDouble())
    val radius = 150f
    val x = (cos(radians) * radius).toInt()
    val y = (sin(radians) * radius).toInt()

    GlassPanel(
        Modifier
            .align(Alignment.Center)
            .offset { IntOffset(x, y) }
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick),
        alpha = if (selected) 0.82f else 0.58f,
        borderColor = if (selected) accent else Color.White.copy(alpha = 0.65f)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(6.dp))
            Text(name, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF24444E))
        }
    }
}

@Composable
private fun GlassPanel(
    modifier: Modifier = Modifier,
    alpha: Float = 0.5f,
    borderColor: Color = Color.White.copy(alpha = 0.8f),
    content: @Composable () -> Unit
) {
    Box(
        modifier
            .clip(RoundedCornerShape(30.dp))
            .background(Color.White.copy(alpha = alpha))
            .border(1.dp, borderColor, RoundedCornerShape(30.dp))
            .padding(10.dp)
    ) {
        content()
    }
}

@Composable
private fun SettingsScreen(
    settings: TrevorSettings,
    onChange: (TrevorSettings) -> Unit,
    onBack: () -> Unit,
    onApiKey: () -> Unit,
    onDeveloper: () -> Unit
) {
    val palette = settings.accent.color
    IceScreen {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, "Back", tint = Color(0xFF31515D))
            }
            Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF173A48))
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SettingsSection("INTERFACE", palette) {
                SettingSwitch("Ice-Frost holographic theme", settings.iceFrost, { onChange(settings.copy(iceFrost = it)) }, palette)
                Text("Accent colour", color = Color(0xFF42606A), fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Accent.entries.forEach { accent ->
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(accent.color)
                                .border(
                                    if (accent == settings.accent) 4.dp else 1.dp,
                                    Color.White,
                                    CircleShape
                                )
                                .clickable { onChange(settings.copy(accent = accent)) }
                        )
                    }
                }
                Text(settings.accent.label + " selected", color = palette, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            }

            SettingsSection("ORBIT", palette) {
                SettingSwitch("3D Orb enabled", settings.orbEnabled, { onChange(settings.copy(orbEnabled = it)) }, palette)
                SettingSwitch("Orb animation", settings.animations, { onChange(settings.copy(animations = it)) }, palette)
            }

            SettingsSection("AI", palette) {
                SettingSwitch("AI enabled", settings.aiEnabled, { onChange(settings.copy(aiEnabled = it)) }, palette)
                SettingSwitch("Gemini enabled", settings.geminiEnabled, { onChange(settings.copy(geminiEnabled = it)) }, palette)
                SettingButton("Gemini API Key", Icons.Filled.SmartToy, onApiKey, palette)
            }

            SettingsSection("DISPLAY", palette) {
                SettingSwitch("Status indicators", settings.showStatusIndicators, { onChange(settings.copy(showStatusIndicators = it)) }, palette)
            }

            SettingsSection("RESPONSES", palette) {
                SettingSwitch("Concise responses", settings.conciseResponses, { onChange(settings.copy(conciseResponses = it)) }, palette)
                SettingSwitch("Technical detail", settings.technicalDetail, { onChange(settings.copy(technicalDetail = it)) }, palette)
            }

            SettingsSection("SECURITY & STORAGE", palette) {
                SettingSwitch("Offline first", settings.offlineFirst, { onChange(settings.copy(offlineFirst = it)) }, palette)
                SettingSwitch("Secure storage", settings.secureStorage, { onChange(settings.copy(secureStorage = it)) }, palette)
                SettingSwitch("Local API-key encryption", settings.localApiKeyEncryption, { onChange(settings.copy(localApiKeyEncryption = it)) }, palette)
            }

            SettingsSection("DEVELOPER", palette) {
                SettingSwitch("Developer mode", settings.developerMode, { onChange(settings.copy(developerMode = it)); if (it) onDeveloper() }, palette)
                SettingSwitch("Debug mode", settings.debugMode, { onChange(settings.copy(debugMode = it)); if (it) onDeveloper() }, palette)
                SettingSwitch("Developer console", settings.developerConsole, { onChange(settings.copy(developerConsole = it)) }, palette)
            }
        }
    }
}

@Composable
private fun IceScreen(iceFrost: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(if (iceFrost) Brush.verticalGradient(listOf(Color(0xFFF9FEFF), Color(0xFFE4F5F9), Color(0xFFD2EAF0))) else Brush.verticalGradient(listOf(Color.White, Color(0xFFF4F6F7))))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

@Composable
private fun SettingsSection(
    title: String,
    accent: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    GlassPanel(
        Modifier.fillMaxWidth(),
        alpha = 0.66f,
        borderColor = accent.copy(alpha = 0.28f)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    accent: Color
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), color = Color(0xFF2B4A54), fontSize = 14.sp)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingButton(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    accent: Color
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White.copy(alpha = 0.5f))
            .border(1.dp, accent.copy(alpha = 0.25f), RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = accent)
        Spacer(Modifier.width(10.dp))
        Text(title, color = Color(0xFF294A55))
    }
}

@Composable
private fun DeveloperScreen(context: Context, settings: TrevorSettings, onBack: () -> Unit) {
    IceScreen {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, "Back", tint = Color(0xFF31515D))
            }
            Text("Diagnostics", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF173A48))
        }
        GlassPanel(Modifier.fillMaxWidth(), alpha = 0.66f) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("PHASE 1 FOUNDATION", color = settings.accent.color, fontWeight = FontWeight.Bold)
                Text("Version: " + TrevorVersion.label(context))
                Text("Orb: native OpenGL ES 2.0 3D renderer")
                Text("Modes: NORMAL • ANALYSE • RESEARCH • PROJECT • RATIO SHIFTER")
                Text("File injector: Android OpenDocument + validation/extraction")
                Text("Verification principle: TREVOR does not claim actions it did not perform.")
            }
        }
    }
}

@Composable
private fun ApiKeyScreen(context: Context, onBack: () -> Unit) {
    var key by remember { mutableStateOf(SecureApiKeyStore.load(context) ?: "") }
    var show by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    IceScreen {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, "Back", tint = Color(0xFF31515D))
            }
            Text("Gemini API Key", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF173A48))
        }

        GlassPanel(Modifier.fillMaxWidth(), alpha = 0.66f) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Stored locally with TREVOR secure storage.", color = Color(0xFF49636C))
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it; message = "" },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { show = !show }) {
                            Icon(
                                if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                "Show or hide key"
                            )
                        }
                    },
                    shape = RoundedCornerShape(26.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(
                        onClick = {
                            if (key.isBlank()) {
                                message = "API key cannot be empty."
                            } else {
                                SecureApiKeyStore.save(context, key.trim())
                                message = "API key saved."
                            }
                        }
                    ) {
                        Icon(Icons.Filled.Check, "Save")
                    }
                    IconButton(
                        onClick = {
                            SecureApiKeyStore.clear(context)
                            key = ""
                            message = "API key removed."
                        }
                    ) {
                        Icon(Icons.Filled.Close, "Remove")
                    }
                }
                if (message.isNotBlank()) Text(message, color = Color(0xFF32748A))
            }
        }
    }
}
