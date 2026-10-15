package com.trevor.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * TREVOR's terminal is deliberately restricted to a small read-only diagnostics
 * allowlist. It must not start /system/bin/sh or execute arbitrary user input.
 */
object TrevorTerminalService {
    suspend fun execute(context: Context, command: String, state: TrevorState): String =
        withContext(Dispatchers.IO) {
            val clean = command.trim()
            when {
                clean.isBlank() -> "Enter a diagnostic command. Type help for the allowlist."
                clean.equals("help", true) -> """
                    TREVOR SAFE DIAGNOSTICS
                    ----------------------
                    pwd       App working directory
                    whoami    App process identity
                    date      Device date/time
                    uname     Kernel/platform information
                    uptime    System uptime (if available)
                    df        Filesystem capacity
                    free      Memory information (if available)
                    ls        List the app's private files directory
                    ls files  List TREVOR's private files
                    ls cache  List TREVOR's private cache
                    
                    Shell syntax, pipes, redirection, scripts, and arbitrary commands
                    are disabled. This console cannot control Android or run commands
                    outside the allowlist.
                """.trimIndent()
                clean.equals("clear", true) -> ""
                clean.equals("status", true) ->
                    "mode=${state.currentMode}\norb=${state.orbState}\nai=${state.aiState}\nfile=${state.fileState}\nrequest=${state.requestState}"
                clean.equals("exit", true) -> "There is no persistent shell session to close."
                else -> TrevorTerminalSandbox.execute(context, clean)
                    .getOrElse { "Blocked/failed: ${it.message ?: "diagnostic command failed"}" }
            }
        }
}
