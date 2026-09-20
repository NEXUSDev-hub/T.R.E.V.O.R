@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.trevor.assistant

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

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

    fun load(context: Context): TrevorSettings {

        val prefs = context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )

        return TrevorSettings(
            aiEnabled = prefs.getBoolean(
                "aiEnabled",
                true
            ),

            geminiEnabled = prefs.getBoolean(
                "geminiEnabled",
                true
            ),

            orbEnabled = prefs.getBoolean(
                "orbEnabled",
                true
            ),

            developerMode = prefs.getBoolean(
                "developerMode",
                false
            ),

            debugMode = prefs.getBoolean(
                "debugMode",
                false
            ),

            developerConsole = prefs.getBoolean(
                "developerConsole",
                false
            ),

            animations = prefs.getBoolean(
                "animations",
                true
            ),

            conciseResponses = prefs.getBoolean(
                "conciseResponses",
                true
            ),

            technicalDetail = prefs.getBoolean(
                "technicalDetail",
                true
            ),

            showStatusIndicators = prefs.getBoolean(
                "showStatusIndicators",
                true
            ),

            showQuickActions = prefs.getBoolean(
                "showQuickActions",
                true
            ),

            offlineFirst = prefs.getBoolean(
                "offlineFirst",
                true
            ),

            secureStorage = prefs.getBoolean(
                "secureStorage",
                true
            ),

            localApiKeyEncryption = prefs.getBoolean(
                "localApiKeyEncryption",
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
            .putBoolean(
                "aiEnabled",
                settings.aiEnabled
            )
            .putBoolean(
                "geminiEnabled",
                settings.geminiEnabled
            )
            .putBoolean(
                "orbEnabled",
                settings.orbEnabled
            )
            .putBoolean(
                "developerMode",
                settings.developerMode
            )
            .putBoolean(
                "debugMode",
                settings.debugMode
            )
            .putBoolean(
                "developerConsole",
                settings.developerConsole
            )
            .putBoolean(
                "animations",
                settings.animations
            )
            .putBoolean(
                "conciseResponses",
                settings.conciseResponses
            )
            .putBoolean(
                "technicalDetail",
                settings.technicalDetail
            )
            .putBoolean(
                "showStatusIndicators",
                settings.showStatusIndicators
            )
            .putBoolean(
                "showQuickActions",
                settings.showQuickActions
            )
            .putBoolean(
                "offlineFirst",
                settings.offlineFirst
            )
            .putBoolean(
                "secureStorage",
                settings.secureStorage
            )
            .putBoolean(
                "localApiKeyEncryption",
                settings.localApiKeyEncryption
            )
            .apply()
    }

    fun defaults(context: Context) {
        save(
            context,
            TrevorSettings()
        )
    }
}

class MainActivity : ComponentActivity() {

    private var selectedFileUri: Uri? = null

    private val filePicker =
        registerForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri ->

            selectedFileUri = uri
        }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        setContent {

            TrevorApp(
                context = this,
                filePicker = {
                    filePicker.launch("*/*")
                }
            )
        }
    }
}

@Composable
private fun TrevorApp(
    context: Context,
    filePicker: () -> Unit
) {

    var screen by remember {
        mutableStateOf(
            Screen.DASHBOARD
        )
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
                },

                onFilePicker = filePicker
            )
        }

        Screen.SETTINGS -> {

            SettingsScreen(
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

                    TrevorSettingsStore.defaults(
                        context
                    )

                    settings = TrevorSettings()
                }
            )
        }

        Screen.DEVELOPER -> {

            DeveloperScreen(
                context = context,
                settings = settings,

                onBack = {
                    screen = Screen.DASHBOARD
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
    onDeveloper: () -> Unit,
    onFilePicker: () -> Unit
) {

    var input by remember {
        mutableStateOf("")
    }

    var response by remember {
        mutableStateOf(
            "TREVOR online.\nAsk me something."
        )
    }

    var busy by remember {
        mutableStateOf(false)
    }

    val scope = rememberCoroutineScope()

    fun sendCommand(command: String = input) {
        if (busy) return
        val clean = command.trim()
        if (clean.isBlank()) {
            response = "Please enter a command."
            return
        }

        busy = true
        response = "Processing..."

        scope.launch {
            when (val result = TrevorCore.process(
                context = context,
                command = clean,
                aiEnabled = settings.aiEnabled,
                geminiEnabled = settings.geminiEnabled,
                conciseResponses = settings.conciseResponses,
                technicalDetail = settings.technicalDetail
            )) {
                is TrevorCoreResult.Answer -> response = result.text
                is TrevorCoreResult.Error -> response = result.message
            }
            busy = false
        }
    }

    Scaffold(

        topBar = {

            TopAppBar(

                title = {

                    Column {

                        Text(
                            text = "T.R.E.V.O.R",
                            fontWeight =
                                FontWeight.Bold,
                            color = Color(0xFF73DFFF)
                        )

                        if (
                            settings.showStatusIndicators
                        ) {

                            Text(
                                text =
                                    TrevorVersion.label(context) + " • " +
                                        if (busy) { "THINKING" } else { "ONLINE" },
                                fontSize = 11.sp,
                                color = Color(0xFF91AEBB)
                            )
                        }
                    }
                },

                actions = {

                    if (
                        settings.developerMode ||
                        settings.debugMode
                    ) {

                        IconButton(
                            onClick =
                                onDeveloper
                        ) {

                            Icon(
                                imageVector =
                                    Icons.Filled.DeveloperMode,
                                contentDescription =
                                    "Developer Mode"
                            )
                        }
                    }

                    IconButton(
                        onClick =
                            onSettings
                    ) {

                        Icon(
                            imageVector =
                                Icons.Filled.Settings,
                            contentDescription =
                                "Settings"
                        )
                    }
                },

                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xCC071A26)
                    )
            )
        }

    ) { padding ->

        Column(

            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0xFF04131D),
                                Color(0xFF082333),
                                Color(0xFF04131D)
                            )
                        )
                    )
                    .padding(padding)
                    .padding(16.dp),

            horizontalAlignment =
                Alignment.CenterHorizontally
        ) {

            if (settings.orbEnabled) {

                TrevorOrb(
                    modifier =
                        Modifier
                            .size(190.dp)
                            .padding(8.dp)
                )

                Spacer(
                    modifier =
                        Modifier.height(12.dp)
                )
            }

            if (settings.showQuickActions) {

                Row(

                    modifier =
                        Modifier.fillMaxWidth(),

                    horizontalArrangement =
                        Arrangement.spacedBy(8.dp)
                ) {

                    QuickButton(
                        modifier =
                            Modifier.weight(1f),
                        text = "Calculate",
                        icon =
                            Icons.Filled.Calculate
                    ) {

                        input =
                            "calculate "
                    }

                    QuickButton(
                        modifier =
                            Modifier.weight(1f),
                        text = "Define",
                        icon =
                            Icons.Filled.Info
                    ) {

                        input =
                            "define "
                    }

                    QuickButton(
                        modifier =
                            Modifier.weight(1f),
                        text = "File",
                        icon =
                            Icons.Filled.AttachFile
                    ) {

                        onFilePicker()
                    }
                }

                Spacer(
                    modifier =
                        Modifier.height(12.dp)
                )
            }

            Card(

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),

                colors =
                    CardDefaults.cardColors(
                        containerColor =
                            MaterialTheme
                                .colorScheme
                                .surfaceVariant
                    )
            ) {

                LazyColumn(

                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                ) {

                    item {

                        Text(
                            text = "TREVOR",
                            fontWeight =
                                FontWeight.Bold,
                            color =
                                MaterialTheme
                                    .colorScheme
                                    .primary
                        )

                        Spacer(
                            modifier =
                                Modifier.height(8.dp)
                        )

                        Text(
                            text = response,
                            fontSize = 16.sp
                        )
                    }
                }
            }

            Spacer(
                modifier =
                    Modifier.height(12.dp)
            )

            Row(

                modifier =
                    Modifier.fillMaxWidth(),

                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                OutlinedTextField(

                    value = input,

                    onValueChange = {
                        input = it
                    },

                    modifier =
                        Modifier.weight(1f),

                    placeholder = {
                        Text("Ask TREVOR...")
                    },

                    singleLine = false
                )

                Spacer(
                    modifier =
                        Modifier.width(8.dp)
                )

                IconButton(

                    onClick = {
                        sendCommand()
                    },

                    enabled = !busy
                ) {

                    Icon(
                        imageVector =
                            Icons.Filled.Send,
                        contentDescription =
                            "Send"
                    )
                }
            }
        }
    }
}

private fun buildPrompt(
    input: String,
    settings: TrevorSettings
): String {

    val responseStyle =
        if (settings.conciseResponses) {

            "Keep responses concise while still answering correctly."

        } else {

            "Give a reasonably detailed response."
        }

    val technicalStyle =
        if (settings.technicalDetail) {

            "Technical details are welcome when useful."

        } else {

            "Prefer simple explanations and avoid unnecessary technical detail."
        }

    return """
        You are TREVOR, The Riteshified Efficient Virtual Operation Robot.

        User request:
        $input

        $responseStyle
        $technicalStyle

        Never claim that an action was performed unless it was actually performed and verified.
    """.trimIndent()
}

@Composable
private fun QuickButton(
    modifier: Modifier,
    text: String,
    icon: ImageVector,
    onClick: () -> Unit
) {

    OutlinedButton(

        modifier = modifier,

        onClick = onClick
    ) {

        Icon(
            imageVector = icon,
            contentDescription = text
        )

        Spacer(
            modifier =
                Modifier.width(4.dp)
        )

        Text(text)
    }
}

@Composable
private fun TrevorOrb(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(
                            Color.White,
                            Color(0xFF73DFFF),
                            Color(0xFF0A4058),
                            Color(0xFF04131D)
                        )
                    )
                )
                .border(2.dp, Color(0xFF73DFFF), CircleShape)
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp)
                .border(1.dp, Color(0x6673DFFF), CircleShape)
        )
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(Color(0xFF9BEFFF))
                .border(2.dp, Color.White, CircleShape)
        )
        Text(
            text = "CORE",
            color = Color(0xFF04131D),
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun SettingsScreen(
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
                        onClick =
                            onBack
                    ) {

                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription =
                                "Back"
                        )
                    }
                }
            )
        }

    ) { padding ->

        LazyColumn(

            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),

            verticalArrangement =
                Arrangement.spacedBy(8.dp)
        ) {

            item {

                Text(
                    text = "AI ENGINE",
                    fontWeight =
                        FontWeight.Bold
                )
            }

            item {

                SettingSwitch(
                    title = "AI Enabled",
                    checked =
                        settings.aiEnabled
                ) {

                    onChange(
                        settings.copy(
                            aiEnabled = it
                        )
                    )
                }
            }

            item {

                SettingSwitch(
                    title = "Gemini AI",
                    checked =
                        settings.geminiEnabled
                ) {

                    onChange(
                        settings.copy(
                            geminiEnabled = it
                        )
                    )
                }
            }

            item {

                SettingButton(
                    title =
                        "Gemini API Key",
                    icon =
                        Icons.Filled.SmartToy,
                    onClick =
                        onApiKey
                )
            }

            item {
                HorizontalDivider()
            }

            item {

                Text(
                    text =
                        "ORBITAL CORE",
                    fontWeight =
                        FontWeight.Bold
                )
            }

            item {

                SettingSwitch(
                    title =
                        "Orb Enabled",
                    checked =
                        settings.orbEnabled
                ) {

                    onChange(
                        settings.copy(
                            orbEnabled = it
                        )
                    )
                }
            }

            item {

                SettingSwitch(
                    title =
                        "Animations",
                    checked =
                        settings.animations
                ) {

                    onChange(
                        settings.copy(
                            animations = it
                        )
                    )
                }
            }

            item {
                HorizontalDivider()
            }

            item {

                Text(
                    text = "DISPLAY",
                    fontWeight =
                        FontWeight.Bold
                )
            }

            item {

                SettingSwitch(
                    title =
                        "Show Status Indicators",
                    checked =
                        settings.showStatusIndicators
                ) {

                    onChange(
                        settings.copy(
                            showStatusIndicators = it
                        )
                    )
                }
            }

            item {

                SettingSwitch(
                    title =
                        "Show Quick Actions",
                    checked =
                        settings.showQuickActions
                ) {

                    onChange(
                        settings.copy(
                            showQuickActions = it
                        )
                    )
                }
            }

            item {
                HorizontalDivider()
            }

            item {

                Text(
                    text =
                        "RESPONSES",
                    fontWeight =
                        FontWeight.Bold
                )
            }

            item {

                SettingSwitch(
                    title =
                        "Concise Responses",
                    checked =
                        settings.conciseResponses
                ) {

                    onChange(
                        settings.copy(
                            conciseResponses = it
                        )
                    )
                }
            }

            item {

                SettingSwitch(
                    title =
                        "Technical Detail",
                    checked =
                        settings.technicalDetail
                ) {

                    onChange(
                        settings.copy(
                            technicalDetail = it
                        )
                    )
                }
            }

            item {
                HorizontalDivider()
            }

            item {

                Text(
                    text =
                        "DEVELOPER",
                    fontWeight =
                        FontWeight.Bold
                )
            }

            item {

                SettingSwitch(
                    title =
                        "Developer Mode",
                    checked =
                        settings.developerMode
                ) {

                    val updated =
                        settings.copy(
                            developerMode = it
                        )

                    onChange(updated)

                    if (it) {
                        onDeveloper()
                    }
                }
            }

            item {

                SettingSwitch(
                    title =
                        "Debug Mode",
                    checked =
                        settings.debugMode
                ) {

                    val updated =
                        settings.copy(
                            debugMode = it
                        )

                    onChange(updated)

                    if (it) {
                        onDeveloper()
                    }
                }
            }

            item {

                SettingSwitch(
                    title =
                        "Developer Console",
                    checked =
                        settings.developerConsole
                ) {

                    onChange(
                        settings.copy(
                            developerConsole = it
                        )
                    )
                }
            }

            item {
                HorizontalDivider()
            }

            item {

                Text(
                    text =
                        "STORAGE & NETWORK",
                    fontWeight =
                        FontWeight.Bold
                )
            }

            item {

                SettingSwitch(
                    title =
                        "Offline First",
                    checked =
                        settings.offlineFirst
                ) {

                    onChange(
                        settings.copy(
                            offlineFirst = it
                        )
                    )
                }
            }

            item {

                SettingSwitch(
                    title =
                        "Secure Storage",
                    checked =
                        settings.secureStorage
                ) {

                    onChange(
                        settings.copy(
                            secureStorage = it
                        )
                    )
                }
            }

            item {

                SettingSwitch(
                    title =
                        "Local API Key Encryption",
                    checked =
                        settings.localApiKeyEncryption
                ) {

                    onChange(
                        settings.copy(
                            localApiKeyEncryption = it
                        )
                    )
                }
            }

            item {

                Spacer(
                    modifier =
                        Modifier.height(8.dp)
                )

                Button(

                    modifier =
                        Modifier.fillMaxWidth(),

                    onClick =
                        onRestoreDefaults
                ) {

                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription =
                            null
                    )

                    Spacer(
                        Modifier.width(8.dp)
                    )

                    Text(
                        "Restore Defaults"
                    )
                }
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

    Card(

        modifier =
            Modifier.fillMaxWidth()
    ) {

        Row(

            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(14.dp),

            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Text(
                text = title,
                modifier =
                    Modifier.weight(1f)
            )

            Switch(
                checked =
                    checked,
                onCheckedChange =
                    onCheckedChange
            )
        }
    }
}

@Composable
private fun SettingButton(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit
) {

    Card(

        modifier =
            Modifier
                .fillMaxWidth()
                .clickable {
                    onClick()
                }
    ) {

        Row(

            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(14.dp),

            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Icon(
                imageVector =
                    icon,
                contentDescription =
                    null
            )

            Spacer(
                modifier =
                    Modifier.width(12.dp)
            )

            Text(
                text = title,
                modifier =
                    Modifier.weight(1f)
            )
        }
    }
}

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
                        onClick =
                            onBack
                    ) {

                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription =
                                "Back"
                        )
                    }
                }
            )
        }

    ) { padding ->

        LazyColumn(

            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),

            verticalArrangement =
                Arrangement.spacedBy(10.dp)
        ) {

            item {

                DiagnosticCard(
                    "Application",
                    "TREVOR"
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
                    "Version",
                    TrevorVersion.label(context)
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
                    "Secure Storage",
                    settings.secureStorage.toString()
                )
            }

            item {

                DiagnosticCard(
                    "Offline First",
                    settings.offlineFirst.toString()
                )
            }

            item {

                DiagnosticCard(
                    "API Key Encryption",
                    settings.localApiKeyEncryption.toString()
                )
            }
        }
    }
}

@Composable
private fun DiagnosticCard(
    name: String,
    value: String
) {

    Card(

        modifier =
            Modifier.fillMaxWidth()
    ) {

        Row(

            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(14.dp),

            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Text(

                text = name,

                modifier =
                    Modifier.weight(1f),

                fontWeight =
                    FontWeight.Medium
            )

            Text(
                text = value
            )
        }
    }
}

@Composable
private fun ApiKeyScreen(
    context: Context,
    onBack: () -> Unit
) {

    var apiKey by remember {

        mutableStateOf(
            SecureApiKeyStore.load(
                context
            ) ?: ""
        )
    }

    var showKey by remember {
        mutableStateOf(false)
    }

    var message by remember {
        mutableStateOf("")
    }

    Scaffold(

        topBar = {

            TopAppBar(

                title = {
                    Text("Gemini API Key")
                },

                navigationIcon = {

                    IconButton(
                        onClick =
                            onBack
                    ) {

                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription =
                                "Back"
                        )
                    }
                }
            )
        }

    ) { padding ->

        Column(

            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
        ) {

            Text(
                text =
                    "Your key is stored locally using TREVOR's secure storage."
            )

            Spacer(
                modifier =
                    Modifier.height(16.dp)
            )

            OutlinedTextField(

                value = apiKey,

                onValueChange = {

                    apiKey = it
                    message = ""
                },

                modifier =
                    Modifier.fillMaxWidth(),

                label = {
                    Text("Gemini API Key")
                },

                singleLine = true,

                visualTransformation =
                    if (showKey) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },

                trailingIcon = {

                    IconButton(

                        onClick = {
                            showKey = !showKey
                        }

                    ) {

                        Icon(

                            imageVector =
                                if (showKey) {
                                    Icons.Filled.VisibilityOff
                                } else {
                                    Icons.Filled.Visibility
                                },

                            contentDescription =
                                "Show or hide key"
                        )
                    }
                }
            )

            Spacer(
                modifier =
                    Modifier.height(12.dp)
            )

            Button(

                modifier =
                    Modifier.fillMaxWidth(),

                onClick = {

                    if (apiKey.isBlank()) {

                        message =
                            "API key cannot be empty."

                    } else {

                        SecureApiKeyStore.save(
                            context,
                            apiKey.trim()
                        )

                        message =
                            "API key saved."
                    }
                }
            ) {

                Icon(
                    Icons.Filled.Check,
                    contentDescription =
                        null
                )

                Spacer(
                    Modifier.width(8.dp)
                )

                Text(
                    "Save API Key"
                )
            }

            Spacer(
                modifier =
                    Modifier.height(8.dp)
            )

            OutlinedButton(

                modifier =
                    Modifier.fillMaxWidth(),

                onClick = {

                    SecureApiKeyStore.clear(
                        context
                    )

                    apiKey = ""

                    message =
                        "API key removed."
                }
            ) {

                Text(
                    "Remove API Key"
                )
            }

            if (message.isNotBlank()) {

                Spacer(
                    modifier =
                        Modifier.height(12.dp)
                )

                Text(

                    text = message,

                    color =
                        MaterialTheme
                            .colorScheme
                            .primary
                )
            }
        }
    }
}
