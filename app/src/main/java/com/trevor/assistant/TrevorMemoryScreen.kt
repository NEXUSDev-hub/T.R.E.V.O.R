package com.trevor.assistant

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun TrevorMemoryScreen(context: Context, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var memories by remember { mutableStateOf(emptyList<TrevorLongTermMemory>()) }
    var tasks by remember { mutableStateOf(emptyList<TrevorTask>()) }
    var message by remember { mutableStateOf("") }
    val accent = Color(0xFF58D9FF)

    fun refresh() {
        scope.launch {
            memories = TrevorPersistentMemory.longTermMemory(context)
            tasks = TrevorPersistentMemory.pendingTasks(context)
        }
    }
    LaunchedEffect(Unit) { refresh() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TrevorTopBar("MEMORY + TASKS", onBack)

        FrostPanel(Modifier.fillMaxWidth(), accent) {
            Text("LONG-TERM MEMORY", color = accent, fontSize = 11.sp)
            Text(
                "Only explicit remember commands are stored as long-term memory.",
                color = Color(0xFF83AAB7), fontSize = 10.sp
            )
            if (memories.isEmpty()) {
                Text("No saved memories.", color = Color(0xFFDDF7FF))
            } else {
                memories.forEach { memory ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(memory.content, Modifier.weight(1f), color = Color(0xFFDDF7FF), fontSize = 12.sp)
                        IconButton(onClick = {
                            scope.launch {
                                TrevorPersistentMemory.deleteLongTermMemory(context, memory.id)
                                refresh()
                            }
                        }) {
                            Icon(Icons.Filled.Delete, "Delete memory", tint = Color(0xFFFF7180))
                        }
                    }
                }
            }
        }

        FrostPanel(Modifier.fillMaxWidth(), Color(0xFF72F0D1)) {
            Text("SCHEDULED TASKS", color = Color(0xFF72F0D1), fontSize = 11.sp)
            if (tasks.isEmpty()) {
                Text("No pending tasks.", color = Color(0xFFDDF7FF))
            } else {
                tasks.forEach { task ->
                    Surface(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        color = Color(0xFF081A28),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFF72F0D1).copy(alpha = 0.18f))
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text(task.title, color = Color(0xFFE8FAFF), fontSize = 13.sp)
                            Text(
                                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(task.triggerAt)) +
                                    " • " + task.status,
                                color = Color(0xFF83AAB7), fontSize = 9.sp
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                TextButton(onClick = {
                                    scope.launch {
                                        TrevorTaskEngine.complete(context, task.id)
                                        refresh()
                                    }
                                }) {
                                    Icon(Icons.Filled.Done, null)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Done")
                                }
                                TextButton(onClick = {
                                    scope.launch {
                                        TrevorTaskEngine.snooze(context, task.id)
                                        refresh()
                                    }
                                }) {
                                    Text("Snooze 10m")
                                }
                                TextButton(onClick = {
                                    scope.launch {
                                        TrevorPersistentMemory.deleteTask(context, task.id)
                                        refresh()
                                    }
                                }) {
                                    Icon(Icons.Filled.Delete, null, tint = Color(0xFFFF7180))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Delete", color = Color(0xFFFF7180))
                                }
                            }
                        }
                    }
                }
            }
        }

        FrostPanel(Modifier.fillMaxWidth(), Color(0xFF9D8CFF)) {
            Text("CONVERSATION", color = Color(0xFF9D8CFF), fontSize = 11.sp)
            Text("Clear the current TREVOR conversation without deleting long-term memories or tasks.", color = Color(0xFF83AAB7), fontSize = 10.sp)
            Surface(
                Modifier.fillMaxWidth().heightIn(min = 50.dp).clickable {
                    scope.launch {
                        val prefs = context.getSharedPreferences("trevor_runtime", Context.MODE_PRIVATE)
                        val id = prefs.getString("conversation_id", null)
                        if (!id.isNullOrBlank()) TrevorPersistentMemory.clearConversation(context, id)
                        message = "Current conversation cleared."
                    }
                },
                color = Color(0xFF0B2230),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(Modifier.padding(12.dp)) {
                    Icon(Icons.Filled.History, null, tint = Color(0xFF9D8CFF))
                    Spacer(Modifier.width(8.dp))
                    Text("CLEAR CURRENT CONVERSATION", color = Color(0xFFDDF7FF))
                }
            }
        }

        if (message.isNotBlank()) Text(message, color = Color(0xFF72F0D1), fontSize = 11.sp)
    }
}
