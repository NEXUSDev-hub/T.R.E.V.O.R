package com.trevor.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TrevorTerminalService {
    private val allowed = setOf(
        "help", "pwd", "ls", "whoami", "uname", "version", "status", "date",
        "clear", "calc", "sci", "memory", "tasks", "providers", "identity",
        "battery", "storage"
    )

    suspend fun execute(context: Context, command: String, state: TrevorState): String =
        withContext(Dispatchers.IO) {
            val clean = command.trim()
            val verb = clean.substringBefore(' ').lowercase()
            if (clean.isBlank()) return@withContext ""
            if (verb !in allowed) {
                return@withContext "TREVOR TERMINAL\nCommand not available in the controlled terminal.\nType 'help' for available commands."
            }

            when (verb) {
                "help" -> """
                    TREVOR TERMINAL
                    ----------------
                    help       Show commands
                    pwd        Show TREVOR app workspace
                    ls         List app workspace entries
                    whoami     Show TREVOR identity
                    identity   Show immutable TREVOR creator identity
                    uname      Show Android runtime
                    version    Show TREVOR version
                    status     Show current mode/orb/AI/file/request state
                    date       Show device time
                    battery    Show battery status
                    storage    Show app storage information
                    memory     Show recent approved local memories
                    tasks      Show stored task count/details
                    providers  Show configured free-provider status
                    calc EXPR  Offline scientific calculation
                    sci EXPR   Alias for calc
                    clear      Clear terminal output
                    """.trimIndent()

                "pwd" -> context.filesDir.absolutePath
                "ls" -> context.filesDir.listFiles()?.joinToString("\n") { it.name }.ifNullOrEmpty("(empty)")
                "whoami", "identity" -> TrevorIdentity.NAME + "\n" + TrevorIdentity.FULL_NAME + "\nCreated by " + TrevorIdentity.CREATORS
                "uname" -> "Android " + android.os.Build.VERSION.RELEASE + " (" + android.os.Build.MODEL + ")"
                "version" -> TrevorVersion.label(context)
                "status" -> "mode=" + state.currentMode + "\norb=" + state.orbState + "\nai=" + state.aiState +
                    "\nfile=" + state.fileState + "\nrequest=" + state.requestState
                "date" -> SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
                "battery" -> {
                    val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
                    "battery=" + bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) + "%"
                }
                "storage" -> {
                    val stat = android.os.StatFs(context.filesDir.absolutePath)
                    val free = stat.availableBytes / (1024 * 1024)
                    val total = stat.totalBytes / (1024 * 1024)
                    "app filesystem: " + free + " MB free / " + total + " MB total"
                }
                "memory" -> TrevorPersistentMemory.longTermMemory(context)
                    .takeLast(10).joinToString("\n") { "• " + it.content }.ifNullOrEmpty("(no approved long-term memories)")
                "tasks" -> "Task storage is available through TREVOR's task/memory system. Use the task controls in the main UI."
                "providers" -> TrevorProviderRegistry.providers.joinToString("\n") { spec ->
                    spec.displayName + " • genuinely free tier • models=" + spec.models.joinToString(",") { it.id }
                }
                "calc", "sci" -> {
                    val expr = clean.substringAfter(' ', "").trim()
                    if (expr.isBlank()) "Usage: calc 2*(3+4) or sci sin(30)" else TrevorScientificCalculator.evaluate(expr)
                }
                "clear" -> ""
                else -> ""
            }
        }

    private fun String?.ifNullOrEmpty(fallback: String): String =
        if (this.isNullOrEmpty()) fallback else this
}
