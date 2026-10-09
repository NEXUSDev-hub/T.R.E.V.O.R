package com.trevor.assistant

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun TrevorRobotControlScreen(context: Context, modifier: Modifier = Modifier) {
    val controller = remember(context) { TrevorRobotBleController(context.applicationContext) }
    val devices = remember { mutableStateListOf<TrevorRobotBleController.DeviceItem>() }
    var status by remember { mutableStateOf("Not connected") }
    var connected by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(55f) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) controller.scan()
        else status = "Bluetooth permission denied"
    }

    DisposableEffect(controller) {
        controller.onStatus = { message ->
            status = message
            if (message.startsWith("Robot link ready")) connected = true
            if (message == "Disconnected" || message.startsWith("Disconnected;") ||
                message.startsWith("Connection failed") || message.startsWith("Robot service") ||
                message.startsWith("Could not subscribe")) connected = false
        }
        controller.onDevices = { found ->
            devices.clear()
            devices.addAll(found)
        }
        onDispose {
            controller.stopDrive()
            controller.disconnect()
            controller.onStatus = null
            controller.onDevices = null
            controller.onTelemetry = null
        }
    }

    val cyan = Color(0xFF51E5FF)
    val panel = Color(0xFF0A1928)
    val motorPower = speedToMotor(speed)
    Column(
        modifier = modifier.fillMaxWidth().background(panel, RoundedCornerShape(22.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier.size(44.dp).background(cyan.copy(alpha = 0.14f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Icon(Icons.Filled.Bluetooth, contentDescription = null, tint = cyan)
            }
            Column(Modifier.weight(1f)) {
                Text("ROBOT CONTROL", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                Text("T.R.E.V.O.R. // BLE LINK", color = cyan, style = MaterialTheme.typography.labelSmall)
            }
            Surface(
                color = if (connected) Color(0xFF123D35) else Color(0xFF3B2930),
                shape = RoundedCornerShape(50)
            ) {
                Text(
                    if (connected) "ONLINE" else "OFFLINE",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    color = if (connected) Color(0xFF74F2C2) else Color(0xFFFFA4AE),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Text(status, color = Color(0xFFB7C9D9), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    if (controller.hasPermissions()) controller.scan()
                    else permissionLauncher.launch(controller.requiredPermissions())
                },
                modifier = Modifier.weight(1f)
            ) {
                androidx.compose.material3.Icon(Icons.Filled.Refresh, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text("SCAN DEVICES")
            }
            OutlinedButton(onClick = { controller.stopDrive(); controller.disconnect(); connected = false }) {
                Text("DISCONNECT")
            }
        }

        if (devices.isNotEmpty()) {
            Text("NEARBY DEVICES", color = cyan, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            LazyColumn(modifier = Modifier.fillMaxWidth().height(112.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                items(devices, key = { it.address }) { item ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF14283A)),
                        onClick = { controller.connect(item); connected = false }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(item.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                                Text(item.address, color = Color(0xFF9DB2C5), style = MaterialTheme.typography.labelSmall)
                            }
                            Text("CONNECT", color = cyan, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        Text("MANUAL DRIVE · HOLD TO MOVE", color = cyan, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            DriveButton("▲", "FORWARD", enabled = connected,
                onPress = { controller.startDrive(motorPower, motorPower) },
                onRelease = { controller.stopDrive() })
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                DriveButton("◀", "LEFT", enabled = connected,
                    onPress = { controller.startDrive(-motorPower, motorPower) },
                    onRelease = { controller.stopDrive() })
                FilledTonalButton(
                    onClick = { controller.stopDrive() },
                    enabled = connected,
                    modifier = Modifier.size(width = 108.dp, height = 58.dp),
                    colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color(0xFF8E2638),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    androidx.compose.material3.Icon(Icons.Filled.Stop, contentDescription = null)
                    Spacer(Modifier.size(4.dp))
                    Text("STOP", fontWeight = FontWeight.Black)
                }
                DriveButton("▶", "RIGHT", enabled = connected,
                    onPress = { controller.startDrive(motorPower, -motorPower) },
                    onRelease = { controller.stopDrive() })
            }
            DriveButton("▼", "REVERSE", enabled = connected,
                onPress = { controller.startDrive(-motorPower, -motorPower) },
                onRelease = { controller.stopDrive() })
        }

        Text("Motor power: ${speed.toInt()}% · PWM $motorPower/255", color = Color(0xFFB7C9D9), style = MaterialTheme.typography.bodySmall)
        Slider(value = speed, onValueChange = { speed = it }, valueRange = 20f..100f, enabled = connected)
        Text(
            "Release a direction to stop. Firmware watchdog also stops movement after 500 ms without a command; disconnecting stops the motors.",
            color = Color(0xFFFFD38A),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private fun speedToMotor(speedPercent: Float): Int =
    (speedPercent.coerceIn(20f, 100f) * 255f / 100f).toInt().coerceIn(51, 255)

@Composable
private fun DriveButton(
    label: String,
    description: String,
    enabled: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit
) {
    FilledTonalButton(
        onClick = { },
        enabled = enabled,
        modifier = Modifier
            .size(width = 92.dp, height = 54.dp)
            .pointerInput(enabled, onPress, onRelease) {
                detectTapGestures(
                    onPress = {
                        if (enabled) onPress()
                        tryAwaitRelease()
                        onRelease()
                    }
                )
            },
        shape = RoundedCornerShape(15.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(label, fontWeight = FontWeight.Bold)
            Text(description, style = MaterialTheme.typography.labelSmall)
        }
    }
}
