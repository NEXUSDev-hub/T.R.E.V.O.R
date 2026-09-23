package com.trevor.assistant

/**
 * Part 6: execution safety/reliability policy.
 *
 * This layer decides whether an action can safely be repeated after a transient
 * failure or verification miss. It never grants permissions or bypasses Android
 * security boundaries.
 */
object TrevorAutomationReliability {
    const val MAX_ATTEMPTS_PER_STEP = 3

    private val retrySafeActions = setOf(
        "OPEN_WIFI",
        "OPEN_BLUETOOTH",
        "OPEN_DISPLAY",
        "OPEN_SOUND",
        "OPEN_BATTERY",
        "OPEN_NOTIFICATIONS",
        "OPEN_SETTINGS",
        "OPEN_APP",
        "USAGE_SUMMARY",
        "BATTERY_STATUS",
        "COPY_TEXT"
    )

    fun isRetrySafe(action: String): Boolean = action in retrySafeActions
}
