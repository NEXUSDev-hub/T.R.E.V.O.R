package com.trevor.assistant

/**
 * Primary TREVOR operating modes.
 *
 * These modes are intentionally independent from the UI so the future
 * orbital interface can select them without changing Core routing.
 */
enum class TrevorMode {
    ANALYSE,
    RESEARCH,
    PROJECT,
    RATIO_SHIFTER
}
