package com.trevor.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                TrevorApp()
            }
        }
    }
}

/* ============================================================
   TREVOR APP
   ============================================================ */

@Composable
fun TrevorApp() {

    var showSettings by remember {
        mutableStateOf(false)
    }

    if (showSettings) {
        SettingsScreen(
            onBack = {
                showSettings = false
            }
        )
    } else {
        TrevorDashboard(
            onSettings = {
                showSettings = true
            }
        )
    }
}

/* ============================================================
   DASHBOARD
   ============================================================ */

@Composable
fun TrevorDashboard(
    onSettings: () -> Unit
) {

    var inputText by remember {
        mutableStateOf("")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFFF4F8FC),
                        Color(0xFFEAF2F8)
                    )
                )
            )
            .padding(16.dp)
    ) {

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {

            Column(
                modifier = Modifier.weight(1f)
            ) {

                Text(
                    text = "TREVOR",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF007AFF)
                )

                Text(
                    text = "v0.0.2",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {

                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xFF10B981))
                )

                Spacer(modifier = Modifier.width(6.dp))

                Text(
                    text = "ONLINE",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF10B981)
                )

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = onSettings
                ) {

                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings"
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {

            QuickAction("Analyze File")
            QuickAction("Research Topic")
            QuickAction("Visualize")
        }

        Spacer(modifier = Modifier.height(22.dp))

        TrevorCorePlaceholder(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color.White.copy(alpha = 0.75f)
            )
        ) {

            Column(
                modifier = Modifier.padding(18.dp)
            ) {

                Text(
                    text = "TREVOR CORE",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF007AFF)
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Ready for your command.",
                    color = Color.DarkGray
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White.copy(alpha = 0.8f))
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {

            IconButton(
                onClick = {}
            ) {

                Icon(
                    imageVector = Icons.Default.AttachFile,
                    contentDescription = "Attach"
                )
            }

            OutlinedTextField(
                value = inputText,
                onValueChange = {
                    inputText = it
                },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text("Command TREVOR...")
                },
                singleLine = true
            )

            IconButton(
                onClick = {}
            ) {

                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "Microphone"
                )
            }

            IconButton(
                onClick = {}
            ) {

                Icon(
                    imageVector = Icons.Filled.Send,
                    contentDescription = "Send"
                )
            }
        }
    }
}

/* ============================================================
   QUICK ACTION
   ============================================================ */

@Composable
fun QuickAction(
    text: String
) {

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.8f))
            .padding(
                horizontal = 12.dp,
                vertical = 8.dp
            )
    ) {

        Text(
            text = text,
            fontSize = 12.sp,
            color = Color(0xFF007AFF),
            fontWeight = FontWeight.Medium
        )
    }
}

/* ============================================================
   CURRENT CORE PLACEHOLDER
   ============================================================ */

@Composable
fun TrevorCorePlaceholder(
    modifier: Modifier = Modifier
) {

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {

        Box(
            modifier = Modifier
                .size(190.dp)
                .clip(RoundedCornerShape(100))
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF00D4FF),
                            Color(0xFF007AFF),
                            Color.Transparent
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {

            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                Text(
                    text = "TREVOR CORE",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "IDLE",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 12.sp
                )
            }
        }
    }
}

/* ============================================================
   DATA MODELS
   ============================================================ */

data class TrevorSetting(
    val name: String,
    val description: String,
    val defaultValue: Boolean = true
)

data class TrevorCategory(
    val name: String,
    val description: String,
    val settings: List<TrevorSetting>
)

/* ============================================================
   SETTINGS SCREEN
   ============================================================ */

@Composable
fun SettingsScreen(
    onBack: () -> Unit
) {

    var showWarning by remember {
        mutableStateOf(true)
    }

    var showResetDialog by remember {
        mutableStateOf(false)
    }

    /*
     * TVA EASTER EGG
     *
     * Tap the version at the bottom exactly five times.
     */

    var versionTapCount by remember {
        mutableStateOf(0)
    }

    var showTimeSlip by remember {
        mutableStateOf(false)
    }

    val categories = remember {

        listOf(

            TrevorCategory(
                "AI Engine",
                "Controls TREVOR's intelligence engine.",
                listOf(
                    TrevorSetting(
                        "AI Engine Enabled",
                        "Enable the AI engine."
                    ),
                    TrevorSetting(
                        "Automatic Model Selection",
                        "Allow TREVOR to select a suitable model."
                    ),
                    TrevorSetting(
                        "Reasoning Mode",
                        "Allow deeper reasoning when required."
                    ),
                    TrevorSetting(
                        "Context Awareness",
                        "Use previous conversation context."
                    ),
                    TrevorSetting(
                        "Long Context",
                        "Allow larger context windows."
                    ),
                    TrevorSetting(
                        "Response Streaming",
                        "Display responses while they are generated."
                    ),
                    TrevorSetting(
                        "Automatic Retry",
                        "Retry temporary AI failures."
                    ),
                    TrevorSetting(
                        "Connection Fallback",
                        "Use fallback connection methods."
                    )
                )
            ),

            TrevorCategory(
                "Responses",
                "Controls how TREVOR communicates.",
                listOf(
                    TrevorSetting(
                        "Concise Responses",
                        "Prefer shorter responses."
                    ),
                    TrevorSetting(
                        "Technical Detail",
                        "Allow more technical explanations."
                    ),
                    TrevorSetting(
                        "Automatic Formatting",
                        "Format lists and structured responses."
                    ),
                    TrevorSetting(
                        "Code Formatting",
                        "Use code blocks for programming output."
                    ),
                    TrevorSetting(
                        "Confirmation Requests",
                        "Ask before sensitive actions."
                    ),
                    TrevorSetting(
                        "Automatic Summaries",
                        "Summarize very long responses."
                    ),
                    TrevorSetting(
                        "Response History",
                        "Keep generated responses visible."
                    ),
                    TrevorSetting(
                        "Error Explanations",
                        "Explain failed operations."
                    )
                )
            ),

            TrevorCategory(
                "Core / Orb",
                "Controls the visual TREVOR core.",
                listOf(
                    TrevorSetting(
                        "Orb Enabled",
                        "Display the TREVOR core."
                    ),
                    TrevorSetting(
                        "Orb Animation",
                        "Animate the core."
                    ),
                    TrevorSetting(
                        "Orb Rotation",
                        "Allow orbital rotation."
                    ),
                    TrevorSetting(
                        "Orb Glow",
                        "Enable energy glow."
                    ),
                    TrevorSetting(
                        "Orb Particles",
                        "Display energy particles."
                    ),
                    TrevorSetting(
                        "Orb Rings",
                        "Display orbital rings."
                    ),
                    TrevorSetting(
                        "State Animations",
                        "Change visuals based on TREVOR state."
                    ),
                    TrevorSetting(
                        "Auto Performance Mode",
                        "Automatically reduce visual load when needed."
                    )
                )
            ),

            TrevorCategory(
                "Display",
                "Controls the application interface.",
                listOf(
                    TrevorSetting(
                        "Light Theme",
                        "Use the default light interface."
                    ),
                    TrevorSetting(
                        "Glass UI",
                        "Enable translucent interface elements."
                    ),
                    TrevorSetting(
                        "UI Animations",
                        "Animate interface transitions."
                    ),
                    TrevorSetting(
                        "Smooth Scrolling",
                        "Use smoother scrolling."
                    ),
                    TrevorSetting(
                        "Compact Cards",
                        "Use smaller information cards."
                    ),
                    TrevorSetting(
                        "Status Indicators",
                        "Display system status indicators."
                    ),
                    TrevorSetting(
                        "Quick Actions",
                        "Display dashboard quick actions."
                    ),
                    TrevorSetting(
                        "Fullscreen Interface",
                        "Allow immersive interface mode."
                    )
                )
            ),

            TrevorCategory(
                "Voice",
                "Controls voice-related features.",
                listOf(
                    TrevorSetting(
                        "Voice Input",
                        "Allow voice commands."
                    ),
                    TrevorSetting(
                        "Voice Output",
                        "Allow spoken responses."
                    ),
                    TrevorSetting(
                        "Wake Detection",
                        "Allow wake-word detection."
                    ),
                    TrevorSetting(
                        "Voice Confirmation",
                        "Confirm important voice commands."
                    ),
                    TrevorSetting(
                        "Automatic Listening",
                        "Allow listening after activation."
                    ),
                    TrevorSetting(
                        "Noise Filtering",
                        "Filter background noise."
                    ),
                    TrevorSetting(
                        "Voice Feedback",
                        "Provide voice status feedback."
                    )
                )
            ),

            TrevorCategory(
                "Memory",
                "Controls local TREVOR memory.",
                listOf(
                    TrevorSetting(
                        "Memory System",
                        "Enable persistent memory."
                    ),
                    TrevorSetting(
                        "Conversation Memory",
                        "Remember conversation context."
                    ),
                    TrevorSetting(
                        "Automatic Memory",
                        "Automatically save useful information."
                    ),
                    TrevorSetting(
                        "Memory Retrieval",
                        "Retrieve relevant stored information."
                    ),
                    TrevorSetting(
                        "Memory Confirmation",
                        "Ask before saving certain information."
                    ),
                    TrevorSetting(
                        "Memory Cleanup",
                        "Automatically remove temporary memory."
                    ),
                    TrevorSetting(
                        "Memory Limits",
                        "Prevent unlimited memory growth."
                    )
                )
            ),

            TrevorCategory(
                "Storage",
                "Controls local files and cache.",
                listOf(
                    TrevorSetting(
                        "Local Cache",
                        "Use local application cache."
                    ),
                    TrevorSetting(
                        "Automatic Cache Cleanup",
                        "Clean old cache data."
                    ),
                    TrevorSetting(
                        "Temporary Files",
                        "Allow temporary working files."
                    ),
                    TrevorSetting(
                        "Attachment Cache",
                        "Cache recently used attachments."
                    ),
                    TrevorSetting(
                        "Storage Monitoring",
                        "Monitor available storage."
                    ),
                    TrevorSetting(
                        "Low Storage Protection",
                        "Reduce caching when storage is low."
                    )
                )
            ),

            TrevorCategory(
                "Network",
                "Controls network behavior.",
                listOf(
                    TrevorSetting(
                        "Network Access",
                        "Allow network-connected features."
                    ),
                    TrevorSetting(
                        "Automatic Reconnect",
                        "Reconnect after temporary failures."
                    ),
                    TrevorSetting(
                        "Request Timeout Protection",
                        "Stop stalled requests."
                    ),
                    TrevorSetting(
                        "Retry Failed Requests",
                        "Retry temporary failures."
                    ),
                    TrevorSetting(
                        "Wi-Fi Preference",
                        "Prefer Wi-Fi for large operations."
                    ),
                    TrevorSetting(
                        "Data Usage Protection",
                        "Reduce unnecessary network traffic."
                    ),
                    TrevorSetting(
                        "Offline Mode",
                        "Allow limited offline operation."
                    )
                )
            ),

            TrevorCategory(
                "Performance",
                "Controls resource usage.",
                listOf(
                    TrevorSetting(
                        "Automatic Performance Mode",
                        "Automatically optimize performance."
                    ),
                    TrevorSetting(
                        "Battery Optimization",
                        "Reduce unnecessary background work."
                    ),
                    TrevorSetting(
                        "Background Processing",
                        "Allow background tasks."
                    ),
                    TrevorSetting(
                        "Animation Optimization",
                        "Optimize animations for performance."
                    ),
                    TrevorSetting(
                        "Memory Optimization",
                        "Reduce unnecessary memory usage."
                    ),
                    TrevorSetting(
                        "CPU Protection",
                        "Prevent excessive CPU usage."
                    ),
                    TrevorSetting(
                        "Thermal Protection",
                        "Reduce workload during high temperatures."
                    )
                )
            ),

            TrevorCategory(
                "Security",
                "Controls TREVOR security behavior.",
                listOf(
                    TrevorSetting(
                        "Permission Checks",
                        "Check Android permissions before actions."
                    ),
                    TrevorSetting(
                        "Action Confirmation",
                        "Confirm potentially important actions."
                    ),
                    TrevorSetting(
                        "Sensitive Action Protection",
                        "Require extra confirmation for sensitive operations."
                    ),
                    TrevorSetting(
                        "Secure Storage",
                        "Protect locally stored sensitive data."
                    ),
                    TrevorSetting(
                        "Session Protection",
                        "Protect active TREVOR sessions."
                    ),
                    TrevorSetting(
                        "Unknown Command Protection",
                        "Reject unsupported commands."
                    ),
                    TrevorSetting(
                        "Verification Required",
                        "Verify actions before reporting success."
                    )
                )
            ),

            TrevorCategory(
                "Developer",
                "Advanced development and diagnostics.",
                listOf(
                    TrevorSetting(
                        "Debug Mode",
                        "Enable development diagnostics.",
                        false
                    ),
                    TrevorSetting(
                        "Debug Logs",
                        "Save detailed diagnostic logs.",
                        false
                    ),
                    TrevorSetting(
                        "Performance Overlay",
                        "Display performance information.",
                        false
                    ),
                    TrevorSetting(
                        "Experimental Features",
                        "Enable experimental features.",
                        false
                    ),
                    TrevorSetting(
                        "Developer Console",
                        "Enable developer tools.",
                        false
                    ),
                    TrevorSetting(
                        "Diagnostic Mode",
                        "Enable advanced diagnostics.",
                        false
                    )
                )
            )
        )
    }

    var settingsState by remember {

        mutableStateOf(
            categories
                .flatMap {
                    it.settings
                }
                .associate {
                    it.name to it.defaultValue
                }
        )
    }

    var modifiedSettings by remember {
        mutableStateOf(setOf<String>())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFFF4F8FC),
                        Color(0xFFEAF2F8)
                    )
                )
            )
    ) {

        /* ====================================================
           SETTINGS HEADER
           ==================================================== */

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {

            IconButton(
                onClick = onBack
            ) {

                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = "Back"
                )
            }

            Column(
                modifier = Modifier.weight(1f)
            ) {

                Text(
                    text = "TREVOR SETTINGS",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF007AFF)
                )

                Text(
                    text = "${categories.sumOf { it.settings.size }} settings",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }

            IconButton(
                onClick = {
                    showResetDialog = true
                }
            ) {

                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Reset"
                )
            }
        }

        /* ====================================================
           SETTINGS LIST
           ==================================================== */

        LazyColumn(
            modifier = Modifier.fillMaxSize(),

            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                bottom = 30.dp
            ),

            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            item {

                AdvancedWarningCard()
            }

            item {

                DefaultStatusCard(
                    modifiedCount = modifiedSettings.size
                )
            }

            categories.forEach { category ->

                item {

                    SettingsCategoryHeader(
                        category = category
                    )
                }

                items(
                    items = category.settings,
                    key = {
                        it.name
                    }
                ) { setting ->

                    val enabled =
                        settingsState[setting.name]
                            ?: setting.defaultValue

                    SettingRow(
                        setting = setting,
                        enabled = enabled,
                        modified = modifiedSettings.contains(
                            setting.name
                        ),
                        onToggle = {

                            val newValue = !enabled

                            settingsState =
                                settingsState.toMutableMap().apply {
                                    this[setting.name] = newValue
                                }

                            modifiedSettings =
                                modifiedSettings.toMutableSet().apply {

                                    if (
                                        newValue ==
                                        setting.defaultValue
                                    ) {
                                        remove(setting.name)
                                    } else {
                                        add(setting.name)
                                    }
                                }
                        }
                    )
                }
            }

            /* ====================================================
               BOTTOM AREA + EASTER EGG
               ==================================================== */

            item {

                Spacer(
                    modifier = Modifier.height(20.dp)
                )

                OutlinedButton(
                    onClick = {
                        showResetDialog = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {

                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null
                    )

                    Spacer(
                        modifier = Modifier.width(8.dp)
                    )

                    Text(
                        text = "Restore All Default Settings"
                    )
                }

                Spacer(
                    modifier = Modifier.height(20.dp)
                )

                /*
                 * =================================================
                 * TVA EASTER EGG
                 *
                 * TAP THIS VERSION TEXT FIVE TIMES.
                 * =================================================
                 */

                Text(
                    text = "TREVOR • v0.0.2",
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {

                            versionTapCount++

                            if (versionTapCount >= 5) {

                                versionTapCount = 0
                                showTimeSlip = true
                            }
                        },
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }
        }
    }

    /* ============================================================
       ADVANCED SETTINGS WARNING
       ============================================================ */

    if (showWarning) {

        AlertDialog(

            onDismissRequest = {
                showWarning = false
            },

            icon = {

                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = Color(0xFFFF9800)
                )
            },

            title = {

                Text(
                    text = "Advanced Settings Warning"
                )
            },

            text = {

                Text(
                    text =
                        "TREVOR's default settings are recommended " +
                        "for normal operation.\n\n" +

                        "Changing advanced settings may increase " +
                        "battery, memory, CPU, storage, or network " +
                        "usage and can reduce performance on some " +
                        "devices or apps.\n\n" +

                        "Incorrect configurations may cause TREVOR " +
                        "features to malfunction or become unstable.\n\n" +

                        "Only change a setting when you understand " +
                        "what it does. You can restore the default " +
                        "configuration at any time."
                )
            },

            confirmButton = {

                TextButton(
                    onClick = {
                        showWarning = false
                    }
                ) {

                    Text(
                        text = "I Understand"
                    )
                }
            }
        )
    }

    /* ============================================================
       RESET DIALOG
       ============================================================ */

    if (showResetDialog) {

        AlertDialog(

            onDismissRequest = {
                showResetDialog = false
            },

            title = {

                Text(
                    text = "Restore Defaults?"
                )
            },

            text = {

                Text(
                    text =
                        "All TREVOR settings will be returned " +
                        "to their recommended default values."
                )
            },

            confirmButton = {

                TextButton(

                    onClick = {

                        settingsState =
                            categories
                                .flatMap {
                                    it.settings
                                }
                                .associate {
                                    it.name to it.defaultValue
                                }

                        modifiedSettings = emptySet()

                        showResetDialog = false
                    }
                ) {

                    Text(
                        text = "Restore"
                    )
                }
            },

            dismissButton = {

                TextButton(
                    onClick = {
                        showResetDialog = false
                    }
                ) {

                    Text(
                        text = "Cancel"
                    )
                }
            }
        )
    }

    /* ============================================================
       TVA TIME-SLIP EASTER EGG
       ============================================================ */

    if (showTimeSlip) {

        AlertDialog(

            onDismissRequest = {
                showTimeSlip = false
            },

            title = {

                Text(
                    text = "TVA INCOMING",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFF9800)
                )
            },

            text = {

                Text(
                    text = "You are about to time slip."
                )
            },

            confirmButton = {

                TextButton(
                    onClick = {
                        showTimeSlip = false
                    }
                ) {

                    Text(
                        text = "UNDERSTOOD"
                    )
                }
            }
        )
    }
}

/* ============================================================
   WARNING CARD
   ============================================================ */

@Composable
fun AdvancedWarningCard() {

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFFFF4E5)
        )
    ) {

        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {

            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = Color(0xFFFF9800)
            )

            Spacer(
                modifier = Modifier.width(12.dp)
            )

            Column {

                Text(
                    text = "Advanced Settings",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE65100)
                )

                Spacer(
                    modifier = Modifier.height(5.dp)
                )

                Text(
                    text =
                        "Default settings are recommended. " +
                        "Changing advanced options can affect " +
                        "performance, battery usage, memory usage, " +
                        "storage, network usage, or TREVOR stability.",
                    fontSize = 13.sp,
                    color = Color.DarkGray
                )
            }
        }
    }
}

/* ============================================================
   DEFAULT STATUS CARD
   ============================================================ */

@Composable
fun DefaultStatusCard(
    modifiedCount: Int
) {

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White.copy(
                alpha = 0.8f
            )
        )
    ) {

        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {

            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = Color(0xFF10B981)
            )

            Spacer(
                modifier = Modifier.width(10.dp)
            )

            Column(
                modifier = Modifier.weight(1f)
            ) {

                Text(
                    text =
                        if (modifiedCount == 0)
                            "DEFAULT CONFIGURATION"
                        else
                            "$modifiedCount SETTINGS MODIFIED",

                    fontWeight = FontWeight.Bold,

                    color =
                        if (modifiedCount == 0)
                            Color(0xFF10B981)
                        else
                            Color(0xFFFF9800)
                )

                Text(
                    text =
                        if (modifiedCount == 0)
                            "TREVOR is using the recommended configuration."
                        else
                            "Some settings differ from the recommended configuration.",

                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
        }
    }
}

/* ============================================================
   CATEGORY HEADER
   ============================================================ */

@Composable
fun SettingsCategoryHeader(
    category: TrevorCategory
) {

    Column(
        modifier = Modifier.padding(
            top = 10.dp,
            start = 4.dp,
            bottom = 2.dp
        )
    ) {

        Text(
            text = category.name,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF007AFF)
        )

        Text(
            text = category.description,
            fontSize = 12.sp,
            color = Color.Gray
        )
    }
}

/* ============================================================
   SETTING ROW
   ============================================================ */

@Composable
fun SettingRow(
    setting: TrevorSetting,
    enabled: Boolean,
    modified: Boolean,
    onToggle: () -> Unit
) {

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                onToggle()
            },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White.copy(
                alpha = 0.78f
            )
        )
    ) {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 16.dp,
                    vertical = 12.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {

            Column(
                modifier = Modifier.weight(1f)
            ) {

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {

                    Text(
                        text = setting.name,
                        fontWeight = FontWeight.Medium
                    )

                    if (modified) {

                        Spacer(
                            modifier = Modifier.width(6.dp)
                        )

                        Text(
                            text = "MODIFIED",
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFF9800)
                        )
                    }
                }

                Spacer(
                    modifier = Modifier.height(3.dp)
                )

                Text(
                    text = setting.description,
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }

            Switch(
                checked = enabled,
                onCheckedChange = {
                    onToggle()
                }
            )
        }
    }
}
