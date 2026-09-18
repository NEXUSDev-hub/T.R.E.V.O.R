package com.trevor.assistant

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            TREVORApp(
                context = this
            )
        }
    }
}

private enum class Screen {
    DASHBOARD,
    SETTINGS,
    DEVELOPER,
    API_KEY
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
    val localApiKeyEncryption: Boolean = true
)

private object TrevorSettingsStore {

    private const val PREFS = "trevor_settings"

    private const val AI_ENABLED = "ai_enabled"
    private const val GEMINI_ENABLED = "gemini_enabled"
    private const val ORB_ENABLED = "orb_enabled"
    private const val DEVELOPER_MODE = "developer_mode"
    private const val DEBUG_MODE = "debug_mode"
    private const val DEVELOPER_CONSOLE = "developer_console"
    private const val ANIMATIONS = "animations"
    private const val CONCISE = "concise_responses"
    private const val TECHNICAL = "technical_detail"
    private const val STATUS = "show_status"
    private const val QUICK_ACTIONS = "show_quick_actions"
    private const val OFFLINE_FIRST = "offline_first"
    private const val SECURE_STORAGE = "secure_storage"
    private const val API_ENCRYPTION = "api_key_encryption"

    fun load(context: Context): TrevorSettings {

        val prefs = context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )

        return TrevorSettings(
            aiEnabled = prefs.getBoolean(AI_ENABLED, true),
            geminiEnabled = prefs.getBoolean(GEMINI_ENABLED, true),
            orbEnabled = prefs.getBoolean(ORB_ENABLED, true),
            developerMode = prefs.getBoolean(DEVELOPER_MODE, false),
            debugMode = prefs.getBoolean(DEBUG_MODE, false),
            developerConsole = prefs.getBoolean(
                DEVELOPER_CONSOLE,
                false
            ),
            animations = prefs.getBoolean(
                ANIMATIONS,
                true
            ),
            conciseResponses = prefs.getBoolean(
                CONCISE,
                true
            ),
            technicalDetail = prefs.getBoolean(
                TECHNICAL,
                true
            ),
            showStatusIndicators = prefs.getBoolean(
                STATUS,
                true
            ),
            showQuickActions = prefs.getBoolean(
                QUICK_ACTIONS,
                true
            ),
            offlineFirst = prefs.getBoolean(
                OFFLINE_FIRST,
                true
            ),
            secureStorage = prefs.getBoolean(
                SECURE_STORAGE,
                true
            ),
            localApiKeyEncryption = prefs.getBoolean(
                API_ENCRYPTION,
                true
            )
        )
    }

    fun save(
        context: Context,
        settings: TrevorSettings
    ) {

        context
            .getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )
            .edit()
            .apply {

                putBoolean(AI_ENABLED, settings.aiEnabled)
                putBoolean(
                    GEMINI_ENABLED,
                    settings.geminiEnabled
                )
                putBoolean(
                    ORB_ENABLED,
                    settings.orbEnabled
                )
                putBoolean(
                    DEVELOPER_MODE,
                    settings.developerMode
                )
                putBoolean(
                    DEBUG_MODE,
                    settings.debugMode
                )
                putBoolean(
                    DEVELOPER_CONSOLE,
                    settings.developerConsole
                )
                putBoolean(
                    ANIMATIONS,
                    settings.animations
                )
                putBoolean(
                    CONCISE,
                    settings.conciseResponses
                )
                putBoolean(
                    TECHNICAL,
                    settings.technicalDetail
                )
                putBoolean(
                    STATUS,
                    settings.showStatusIndicators
                )
                putBoolean(
                    QUICK_ACTIONS,
                    settings.showQuickActions
                )
                putBoolean(
                    OFFLINE_FIRST,
                    settings.offlineFirst
                )
                putBoolean(
                    SECURE_STORAGE,
                    settings.secureStorage
                )
                putBoolean(
                    API_ENCRYPTION,
                    settings.localApiKeyEncryption
                )

                apply()
            }
    }

    fun restoreDefaults(
        context: Context
    ) {
        save(
            context,
            TrevorSettings()
        )
    }
}

@Composable
private fun TREVORApp(
    context: Context
) {

    var screen by remember {
        mutableStateOf(Screen.DASHBOARD)
    }

    var settings by remember {
        mutableStateOf(
            TrevorSettingsStore.load(context)
        )
    }

    fun updateSettings(
        newSettings: TrevorSettings
    ) {
        settings = newSettings
        TrevorSettingsStore.save(
            context,
            newSettings
        )
    }

    when (screen) {

        Screen.DASHBOARD -> {
            DashboardScreen(
                context = context,
                settings = settings,
                onSettings = {
                    screen = Screen.SETTINGS
                },
                onDeveloper = {
                    screen = Screen.DEVELOPER
                }
            )
        }

        Screen.SETTINGS -> {
            SettingsScreen(
                context = context,
                settings = settings,
                onBack = {
                    screen = Screen.DASHBOARD
                },
                onApiKey = {
                    screen = Screen.API_KEY
                },
                onDeveloper = {
                    screen = Screen.DEVELOPER
                },
                onChange = ::updateSettings,
                onRestoreDefaults = {
                    TrevorSettingsStore.restoreDefaults(
                        context
                    )

                    settings =
                        TrevorSettingsStore.load(context)
                }
            )
        }

        Screen.DEVELOPER -> {
            DeveloperScreen(
                context = context,
                settings = settings,
                onBack = {
                    screen = Screen.SETTINGS
                }
            )
        }

        Screen.API_KEY -> {
            ApiKeyScreen(
                context = context,
                onBack = {
                    screen = Screen.SETTINGS
                }
            )
        }
    }
}

@Composable
private fun DashboardScreen(
    context: Context,
    settings: TrevorSettings,
    onSettings: () -> Unit,
    onDeveloper: () -> Unit
) {

    var input by remember {
        mutableStateOf("")
    }

    var response by remember {
        mutableStateOf("Ready for your command.")
    }

    var processing by remember {
        mutableStateOf(false)
    }

    val scope = rememberCoroutineScope()

    val filePicker =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->

            if (uri != null) {
                response =
                    "File selected:\n${uri.lastPathSegment}"
            }
        }

    fun submitCommand() {

        val command = input.trim()

        if (command.isBlank()) {
            return
        }

        processing = true
        response = "Processing..."

        scope.launch {

            when (
                val result =
                    TrevorLocalEngine.processCommand(command)
            ) {

                is TrevorEngineResult.Answer -> {
                    response = result.text
                }

                is TrevorEngineResult.Error -> {
                    response = "Error: ${result.message}"
                }

                is TrevorEngineResult.NeedAI -> {

                    if (!settings.aiEnabled) {

                        response =
                            "AI is disabled. Enable AI in Settings."

                    } else if (!settings.geminiEnabled) {

                        response =
                            "Gemini AI is disabled."

                    } else {

                        val apiKey =
                            SecureApiKeyStore.load(context)

                        if (apiKey.isNullOrBlank()) {

                            response =
                                "Gemini API key is not configured.\n\n" +
                                        "Open Settings → Gemini API Key."

                        } else {

                            val aiResult =
                                GeminiAiProvider.ask(
                                    apiKey = apiKey,
                                    prompt = result.prompt
                                )

                            response =
                                if (aiResult.isSuccess) {

                                    aiResult.getOrNull()
                                        ?: "Gemini returned an empty response."

                                } else {

                                    "AI Error: " +
                                            (
                                                    aiResult.exceptionOrNull()
                                                        ?.message
                                                        ?: "Unknown error."
                                                    )
                                }
                        }
                    }
                }
            }

            processing = false
        }
    }

    val statusText =
        if (processing) "THINKING" else "ONLINE"

    Scaffold(
        topBar = {

            TopAppBar(
                title = {
                    Column {

                        Text(
                            text = "TREVOR v0.0.2",
                            fontWeight = FontWeight.Bold
                        )

                        if (settings.showStatusIndicators) {

                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                },
                actions = {

                    IconButton(
                        onClick = onDeveloper
                    ) {
                        Icon(
                            imageVector =
                                Icons.Filled.DeveloperMode,
                            contentDescription =
                                "Developer Mode"
                        )
                    }

                    IconButton(
                        onClick = onSettings
                    ) {
                        Icon(
                            imageVector =
                                Icons.Filled.Settings,
                            contentDescription =
                                "Settings"
                        )
                    }
                }
            )
        }
    ) { padding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement =
                Arrangement.spacedBy(12.dp)
        ) {

            if (settings.showQuickActions) {

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.spacedBy(8.dp)
                ) {

                    Button(
                        modifier =
                            Modifier.weight(1f),
                        onClick = {
                            filePicker.launch(
                                arrayOf("*/*")
                            )
                        }
                    ) {
                        Icon(
                            Icons.Filled.AttachFile,
                            contentDescription = null
                        )

                        Spacer(
                            Modifier.width(4.dp)
                        )

                        Text("Analyze File")
                    }

                    Button(
                        modifier =
                            Modifier.weight(1f),
                        onClick = {
                            input =
                                "Research "
                        }
                    ) {
                        Text("Research")
                    }

                    Button(
                        modifier =
                            Modifier.weight(1f),
                        onClick = {
                            input =
                                "Visualize "
                        }
                    ) {
                        Text("Visualize")
                    }
                }
            }

            if (settings.orbEnabled) {

                TrevorOrb(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(230.dp),
                    state =
                        if (processing) {
                            "THINKING"
                        } else {
                            "IDLE"
                        }
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor =
                        MaterialTheme.colorScheme.surfaceVariant
                )
            ) {

                Text(
                    text = response,
                    modifier = Modifier.padding(16.dp)
                )
            }

            Spacer(
                modifier = Modifier.weight(1f)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                IconButton(
                    onClick = {
                        filePicker.launch(
                            arrayOf("*/*")
                        )
                    }
                ) {
                    Icon(
                        Icons.Filled.AttachFile,
                        contentDescription = "Attach"
                    )
                }

                OutlinedTextField(
                    modifier =
                        Modifier.weight(1f),
                    value = input,
                    onValueChange = {
                        input = it
                    },
                    enabled = !processing,
                    placeholder = {
                        Text("Ask TREVOR...")
                    },
                    singleLine = true
                )

                IconButton(
                    onClick = {
                        Toast.makeText(
                            context,
                            "Voice input is not implemented yet.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                ) {
                    Icon(
                        Icons.Filled.Mic,
                        contentDescription = "Voice"
                    )
                }

                IconButton(
                    enabled = !processing,
                    onClick = {
                        submitCommand()
                    }
                ) {
                    Icon(
                        Icons.Filled.Send,
                        contentDescription = "Send"
                    )
                }
            }
        }
    }
}

@Composable
private fun TrevorOrb(
    modifier: Modifier,
    state: String
) {

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.radialGradient(
                    listOf(
                        Color(0xFF1565C0),
                        Color(0xFF0D1B2A),
                        Color.Black
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {

        Column(
            horizontalAlignment =
                Alignment.CenterHorizontally
        ) {

            Box(
                modifier = Modifier
                    .size(130.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                Color(0xFF80D8FF),
                                Color(0xFF0288D1),
                                Color(0xFF001B2E)
                            )
                        )
                    ),
                contentAlignment =
                    Alignment.Center
            ) {

                Text(
                    text = "T",
                    color = Color.White,
                    style =
                        MaterialTheme.typography.displayMedium,
                    fontWeight =
                        FontWeight.Bold
                )
            }

            Spacer(
                modifier = Modifier.height(10.dp)
            )

            Text(
                text = "TREVOR CORE • $state",
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    context: Context,
    settings: TrevorSettings,
    onBack: () -> Unit,
    onApiKey: () -> Unit,
    onDeveloper: () -> Unit,
    onChange: (TrevorSettings) -> Unit,
    onRestoreDefaults: () -> Unit
) {

    Scaffold(
        topBar = {

            TopAppBar(
                title = {
                    Text("Settings")
                },
                navigationIcon = {

                    IconButton(
                        onClick = onBack
                    ) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {

            item {
                SettingsSectionTitle("AI ENGINE")
            }

            item {
                SettingSwitch(
                    title = "AI Enabled",
                    checked = settings.aiEnabled,
                    onCheckedChange = {
                        onChange(
                            settings.copy(
                                aiEnabled = it
                            )
                        )
                    }
                )
            }

            item {
                SettingSwitch(
                    title = "Gemini AI",
                    checked = settings.geminiEnabled,
                    onCheckedChange = {
                        onChange(
                            settings.copy(
                                geminiEnabled = it
                            )
                        )
                    }
                )
            }

            item {

                SettingButton(
                    title = "Gemini API Key",
                    subtitle =
                        if (
                            SecureApiKeyStore.exists(context)
                        ) {
                            "API key configured"
                        } else {
                            "No API key configured"
                        },
                    onClick = onApiKey
                )
            }

            item {
                SettingsSectionTitle("CORE / ORB")
            }

            item {
                SettingSwitch(
                    title = "Orb Enabled",
                    checked = settings.orbEnabled,
                    onCheckedChange = {
                        onChange(
                            settings.copy(
                                orbEnabled = it
                            )
                        )
                    }
                )
            }

            item {
                SettingsSectionTitle("DISPLAY")
            }

            item {
                SettingSwitch(
                    title = "Animations",
                    checked = settings.animations,
                    onCheckedChange = {
                        onChange(
                            settings.copy(
                                animations = it
                            )
                        )
                    }
                )
            }

            item {
                SettingSwitch(
                    title = "Show Status Indicators",
                    checked =
                        settings.showStatusIndicators,
                    onCheckedChange = {
                        onChange(
                            settings.copy(
                                showStatusIndicators = it
                            )
                        )
                    }
                )
            }

            item {
                SettingSwitch(
                    title = "Show Quick Actions",
                    checked =
                        settings.showQuickActions,
                    onCheckedChange = {
                        onChange(
                            settings.copy(
                                showQuickActions = it
                            )
                        )
                    }
                )
            }

            item {
                SettingsSectionTitle("RESPONSES")
            }

            item {
                SettingSwitch(
                    title = "Concise Responses",
                    checked =
                        settings.conciseResponses,
                    onCheckedChange = {
                        onChange(
                            settings.copy(
                                conciseResponses = it
                            )
                        )
                    }
                )
            }

            item {
                SettingSwitch(
                    title = "Technical Detail",
                    checked =
                        settings.technicalDetail,
                    onCheckedChange = {
                        onChange(
                            settings.copy(
                                technicalDetail = it
                            )
                        )
                    }
                )
            }

            item {
                SettingsSectionTitle("DEVELOPER")
            }

            item {
                SettingSwitch(
                    title = "Developer Mode",
                    checked =
                        settings.developerMode,
                    onCheckedChange = {
                        val updated =
                            settings.copy(
                                developerMode = it
                            )

                        onChange(updated)

                        if (it) {
                            onDeveloper()
                        }
                    }
                )
            }

            item {
                SettingSwitch(
                    title = "Debug Mode",
                    checked =
                        settings.debugMode,
                    onCheckedChange = {
                        val updated =
                            settings.copy(
                                debugMode = it
                            )

                        onChange(updated)

                        if (it) {
                            onDeveloper()
                        }
                    }
                )
            }

            item {
                SettingSwitch(
                    title = "Developer Console",
                    checked =
                        settings.developerConsole,
                    onCheckedChange = {
                        onChange(
                            settings.copy(
                                developerConsole = it
                            )
                        )
                    }
                )
            }

            item {
                Spacer(
                    Modifier.height(20.dp)
                )
            }

            item {

                Button(
                    modifier =
                        Modifier.fillMaxWidth(),
                    onClick = onRestoreDefaults
                ) {

                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = null
                    )

                    Spacer(
                        Modifier.width(8.dp)
                    )

                    Text("Restore Defaults")
                }
            }

            item {
                Spacer(
                    Modifier.height(32.dp)
                )
            }
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment =
            Alignment.CenterVertically
    ) {

        Text(
            text = title,
            modifier =
                Modifier.weight(1f)
        )

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun SettingButton(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
    ) {

        Text(
            text = title,
            fontWeight = FontWeight.Medium
        )

        Text(
            text = subtitle,
            style =
                MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun SettingsSectionTitle(
    title: String
) {

    Text(
        text = title,
        modifier = Modifier.padding(
            top = 20.dp,
            bottom = 8.dp
        ),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold
    )

    Divider()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApiKeyScreen(
    context: Context,
    onBack: () -> Unit
) {

    var apiKey by remember {
        mutableStateOf(
            SecureApiKeyStore.load(context) ?: ""
        )
    }

    var saved by remember {
        mutableStateOf(false)
    }

    Scaffold(
        topBar = {

            TopAppBar(
                title = {
                    Text("Gemini API Key")
                },
                navigationIcon = {

                    IconButton(
                        onClick = onBack
                    ) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement =
                Arrangement.spacedBy(16.dp)
        ) {

            Text(
                text =
                    "Your API key is encrypted locally using Android Keystore."
            )

            OutlinedTextField(
                modifier =
                    Modifier.fillMaxWidth(),
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    saved = false
                },
                label = {
                    Text("Gemini API Key")
                },
                singleLine = true
            )

            Button(
                modifier =
                    Modifier.fillMaxWidth(),
                onClick = {

                    if (apiKey.isBlank()) {

                        Toast.makeText(
                            context,
                            "API key cannot be empty.",
                            Toast.LENGTH_SHORT
                        ).show()

                    } else {

                        SecureApiKeyStore.save(
                            context,
                            apiKey.trim()
                        )

                        saved = true

                        Toast.makeText(
                            context,
                            "API key saved securely.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            ) {
                Text("Save API Key")
            }

            if (saved) {

                Text(
                    text = "✓ API key saved",
                    color =
                        MaterialTheme.colorScheme.primary,
                    fontWeight =
                        FontWeight.Bold
                )
            }

            Button(
                modifier =
                    Modifier.fillMaxWidth(),
                onClick = {

                    SecureApiKeyStore.clear(
                        context
                    )

                    apiKey = ""
                    saved = false

                    Toast.makeText(
                        context,
                        "API key removed.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            ) {
                Text("Remove API Key")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeveloperScreen(
    context: Context,
    settings: TrevorSettings,
    onBack: () -> Unit
) {

    Scaffold(
        topBar = {

            TopAppBar(
                title = {
                    Text("Developer Mode")
                },
                navigationIcon = {

                    IconButton(
                        onClick = onBack
                    ) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement =
                Arrangement.spacedBy(10.dp)
        ) {

            item {
                Text(
                    text = "TREVOR Developer Console",
                    style =
                        MaterialTheme.typography.headlineSmall,
                    fontWeight =
                        FontWeight.Bold
                )
            }

            item {
                Text(
                    text = "Safe diagnostics and runtime configuration."
                )
            }

            item {
                DiagnosticCard(
                    "Application",
                    "TREVOR"
                )
            }

            item {
                DiagnosticCard(
                    "Version",
                    "0.0.2"
                )
            }

            item {
                DiagnosticCard(
                    "Package",
                    "com.trevor.assistant"
                )
            }

            item {
                DiagnosticCard(
                    "AI Enabled",
                    settings.aiEnabled.toString()
                )
            }

            item {
                DiagnosticCard(
                    "Gemini Enabled",
                    settings.geminiEnabled.toString()
                )
            }

            item {
                DiagnosticCard(
                    "Gemini API Key",
                    if (
                        SecureApiKeyStore.exists(context)
                    ) {
                        "CONFIGURED"
                    } else {
                        "NOT CONFIGURED"
                    }
                )
            }

            item {
                DiagnosticCard(
                    "Orb Enabled",
                    settings.orbEnabled.toString()
                )
            }

            item {
                DiagnosticCard(
                    "Developer Mode",
                    settings.developerMode.toString()
                )
            }

            item {
                DiagnosticCard(
                    "Debug Mode",
                    settings.debugMode.toString()
                )
            }

            item {
                DiagnosticCard(
                    "Offline First",
                    settings.offlineFirst.toString()
                )
            }
        }
    }
}

@Composable
private fun DiagnosticCard(
    title: String,
    value: String
) {

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement =
                Arrangement.SpaceBetween
        ) {

            Text(
                text = title,
                fontWeight = FontWeight.Medium
            )

            Text(
                text = value
            )
        }
    }
}
