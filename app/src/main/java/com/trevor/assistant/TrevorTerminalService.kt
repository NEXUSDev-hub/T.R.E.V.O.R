package com.trevor.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID

/**
 * Real Android app-sandbox shell session.
 * Uses the device's /system/bin/sh process; Android still constrains it to
 * TREVOR's app UID/filesystem and it does not bypass Android permissions.
 */
object TrevorTerminalService {
    private const val MARKER_PREFIX = "__TREVOR_CMD_DONE__"
    private var process: Process? = null
    private var reader: BufferedReader? = null
    private var writer: java.io.OutputStream? = null

    @Synchronized
    private fun ensureShell(context: Context) {
        if (process?.isAlive == true && reader != null && writer != null) return
        process?.destroy()
        process = ProcessBuilder("/system/bin/sh")
            .directory(context.filesDir)
            .redirectErrorStream(true)
            .start()
        reader = BufferedReader(InputStreamReader(process!!.inputStream))
        writer = process!!.outputStream
        sendRaw("export HOME='" + context.filesDir.absolutePath + "'")
        sendRaw("export PATH='/system/bin:/system/xbin:\\$PATH'")
    }

    suspend fun execute(context: Context, command: String, state: TrevorState): String =
        withContext(Dispatchers.IO) {
            val clean = command.trim()
            if (clean.isBlank()) return@withContext ""

            if (clean.equals("help", true)) {
                return@withContext """
                    TREVOR TERMINAL
                    --------------
                    Real /system/bin/sh session inside the Android app sandbox.
                    help       Show terminal help
                    exit       Close the current shell session
                    pwd        Print working directory
                    clear      Clear TREVOR terminal output
                    status     Show TREVOR runtime state
                    Any other command is passed to the native shell.
                    Pipes, redirection, environment variables and shell syntax
                    are supported by Android's available shell/user-space tools.
                """.trimIndent()
            }

            if (clean.equals("exit", true)) {
                close()
                return@withContext "Shell session closed."
            }

            if (clean.equals("status", true)) {
                return@withContext "mode=" + state.currentMode +
                    "\norb=" + state.orbState +
                    "\nai=" + state.aiState +
                    "\nfile=" + state.fileState +
                    "\nrequest=" + state.requestState
            }

            synchronized(this@TrevorTerminalService) {
                ensureShell(context)
                val marker = MARKER_PREFIX + UUID.randomUUID().toString().replace("-", "")
                sendRaw(clean)
                sendRaw("printf '\\n" + marker + ":$?\\n'")
                val output = StringBuilder()
                while (true) {
                    val line = reader?.readLine() ?: break
                    if (line.startsWith(marker + ":")) {
                        val code = line.substringAfter(':').toIntOrNull() ?: 0
                        if (code != 0) output.append("\n[exit ").append(code).append(']')
                        break
                    }
                    if (output.isNotEmpty()) output.append('\n')
                    output.append(line)
                    if (output.length > 64_000) {
                        output.append("\n[output truncated at 64 KB]")
                        break
                    }
                }
                if (output.isEmpty()) "(command completed with no output)" else output.toString()
            }
        }

    @Synchronized
    private fun sendRaw(command: String) {
        writer?.apply {
            write((command + "\n").toByteArray(Charsets.UTF_8))
            flush()
        }
    }

    @Synchronized
    fun close() {
        runCatching { writer?.write("exit\n".toByteArray(Charsets.UTF_8)); writer?.flush() }
        runCatching { process?.destroy() }
        reader?.close()
        writer?.close()
        process = null
        reader = null
        writer = null
    }
}
