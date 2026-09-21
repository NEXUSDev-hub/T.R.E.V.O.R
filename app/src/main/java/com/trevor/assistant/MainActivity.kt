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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    private var attachment by mutableStateOf<TrevorAttachment?>(null)

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        lifecycleScope.launch {
            val results = TrevorMultiFileService.inspectAll(this@MainActivity, uris)
            val good = results.mapNotNull { it.getOrNull() }
            val failed = results.mapNotNull { it.exceptionOrNull()?.message }
            if (good.isEmpty()) {
                attachment = null
                TrevorStateStore.update {
                    it.copy(fileState = TrevorFileState.ERROR, orbState = TrevorOrbState.ERROR, lastError = failed.firstOrNull() ?: "No readable files selected.")
                }
            } else if (good.size == 1) {
                attachment = good.first()
            } else {
                val combined = good.joinToString("\n\n") { file ->
                    "===== " + file.name + " =====\n" + (file.extractedText ?: "[No extracted text; original media is not merged into this text context.]")
                }
                attachment = TrevorAttachment(
                    uri = good.first().uri,
                    name = good.size.toString() + " files",
                    mimeType = "text/plain",
                    sizeBytes = good.sumOf { it.sizeBytes ?: 0L },
                    extractedText = combined.take(120000),
                    extractable = true
                )
            }
            if (failed.isNotEmpty()) {
                TrevorStateStore.update { it.copy(lastError = failed.joinToString("; ")) }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (isFinishing && Settings.canDrawOverlays(this)) {
            val intent = Intent(this, TrevorProactiveService::class.java).setAction(TrevorProactiveService.ACTION_SHOW_OVERLAY)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TrevorBackgroundScheduler.ensureScheduled(this)
        val backgroundIntent = Intent(this, TrevorProactiveService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(backgroundIntent) else startService(backgroundIntent)
        window.statusBarColor = android.graphics.Color.rgb(5, 15, 26)
        window.navigationBarColor = android.graphics.Color.rgb(5, 15, 26)
        window.decorView.systemUiVisibility = 0
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 730)
        }
        setContent {
            TrevorApp(
                this,
                attachment,
                { picker.launch(arrayOf("*/*")) },
                {
                    attachment = null
                    TrevorStateStore.update { it.copy(fileState = TrevorFileState.NONE, orbState = TrevorOrbState.IDLE, lastError = null) }
                }
            )
        }
    }
}

