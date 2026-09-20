package com.trevor.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object TrevorTerminalService {
    private val allowed = setOf("help", "pwd", "ls", "whoami", "uname", "version", "status", "date", "clear")

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
                    help      Show commands
                    pwd       Show TREVOR app workspace
                    ls        List app workspace entries
                    whoami    Show TREVOR identity
                    uname     Show Android runtime
                    version   Show TREVOR version
                    status    Show current mode/orb/AI/file state
                    date      Show device time
                    clear     Clear terminal output
                """.trimIndent()
                "pwd" -> context.filesDir.absolutePath
                "ls" -> context.filesDir.listFiles()?.joinToString("\n") { it.name }.ifNullOrEmpty("(empty)")
                "whoami" -> "trevor@android"
                "uname" -> "Android ${android.os.Build.VERSION.RELEASE} (${android.os.Build.MODEL})"
                "version" -> TrevorVersion.label(context)
                "status" -> "mode=${state.currentMode}\norb=${state.orbState}\nai=${state.aiState}\nfile=${state.fileState}\nrequest=${state.requestState}"
                "date" -> java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", java.util.Locale.US).format(java.util.Date())
                "clear" -> ""
                else -> ""
            }
        }

    private fun String?.ifNullOrEmpty(fallback: String): String =
        if (this.isNullOrEmpty()) fallback else this
}
