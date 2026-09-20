package com.trevor.assistant

/**
 * Routes an intent to the correct TREVOR operating mode.
 *
 * This is deliberately small in v0.0.3. The goal is to establish a stable
 * routing contract before each mode receives its full intelligence system.
 */
object TrevorModeRouter {

    fun route(mode: TrevorMode?, command: String): TrevorMode {
        if (mode != null) return mode

        val normalized = command.trim().lowercase()

        return when {
            normalized.startsWith("analyse ") ||
                normalized.startsWith("analyze ") ||
                normalized == "analyse" ||
                normalized == "analyze" -> TrevorMode.ANALYSE

            normalized.startsWith("research ") ||
                normalized == "research" -> TrevorMode.RESEARCH

            normalized.startsWith("project ") ||
                normalized == "project" -> TrevorMode.PROJECT

            normalized.startsWith("ratio ") ||
                normalized.startsWith("ratio shifter") ||
                normalized == "ratio" -> TrevorMode.RATIO_SHIFTER

            else -> TrevorMode.PROJECT
        }
    }
}
