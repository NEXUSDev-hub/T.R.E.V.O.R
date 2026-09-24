package com.trevor.assistant

import android.content.Context
import java.io.File

/** Part 12: controlled terminal. Only explicit diagnostic commands are accepted. */
object TrevorTerminalSandbox {
    private val allowed = setOf("pwd", "whoami", "date", "uname", "uptime", "ls", "df", "free")
    fun execute(context: Context, command: String): Result<String> {
        val parts = command.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (parts.isEmpty()) return Result.failure(IllegalArgumentException("Empty command."))
        if (parts.first() !in allowed) return Result.failure(IllegalArgumentException("Command blocked by TREVOR sandbox."))
        return runCatching {
            val process = ProcessBuilder(parts)
                .directory(context.filesDir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }.take(12000)
            process.waitFor()
            output.ifBlank { "(no output)" }
        }
    }
}
