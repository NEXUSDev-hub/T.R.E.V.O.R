package com.trevor.assistant

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Allowlisted, non-shell diagnostics. Never passes user text to a shell.
 * Android's per-app UID remains the security boundary.
 */
object TrevorTerminalSandbox {
    private val noArgCommands = setOf("pwd", "whoami", "date", "uname", "uptime", "df", "free")
    private val allowedLsPaths = setOf(".", "files", "cache")

    fun execute(context: Context, command: String): Result<String> {
        val parts = command.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (parts.isEmpty()) return Result.failure(IllegalArgumentException("Empty command."))
        val executable = parts.first()
        if (executable !in noArgCommands && executable != "ls") {
            return Result.failure(IllegalArgumentException("Blocked: only read-only diagnostic commands are available."))
        }
        if (executable in noArgCommands && parts.size != 1) {
            return Result.failure(IllegalArgumentException("Arguments are not allowed for $executable."))
        }
        if (executable == "ls" && (parts.size > 2 || (parts.size == 2 && parts[1] !in allowedLsPaths))) {
            return Result.failure(IllegalArgumentException("ls accepts only '.', 'files', or 'cache'."))
        }

        val args = if (executable == "ls" && parts.size == 2) {
            val target = when (parts[1]) {
                "files" -> context.filesDir.absolutePath
                "cache" -> context.cacheDir.absolutePath
                else -> "."
            }
            listOf("ls", target)
        } else parts

        return runCatching {
            val process = ProcessBuilder(args)
                .directory(context.filesDir)
                .redirectErrorStream(true)
                .start()
            val completed = process.waitFor(3, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                throw IllegalStateException("Diagnostic command timed out after 3 seconds.")
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }.take(8000)
            if (process.exitValue() != 0) {
                throw IllegalStateException(output.ifBlank { "Command failed with exit code ${process.exitValue()}." })
            }
            output.ifBlank { "(no output)" }
        }
    }
}
