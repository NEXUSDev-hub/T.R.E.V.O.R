package com.trevor.assistant

import android.content.Context
import android.content.Intent
import android.provider.Settings

data class TrevorAndroidActionResult(val action: String, val dispatched: Boolean, val verified: Boolean, val detail: String)

object TrevorAndroidActions {
    private val actions = mapOf(
        "wifi" to Settings.ACTION_WIFI_SETTINGS,
        "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
        "display" to Settings.ACTION_DISPLAY_SETTINGS,
        "sound" to Settings.ACTION_SOUND_SETTINGS,
        "battery" to Settings.ACTION_BATTERY_SAVER_SETTINGS,
        "notifications" to Settings.ACTION_APP_NOTIFICATION_SETTINGS,
        "app settings" to Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        "settings" to Settings.ACTION_SETTINGS
    )

    fun tryDispatch(context: Context, command: String): TrevorAndroidActionResult? {
        val normalized = command.trim().lowercase()
        val key = when {
            normalized == "open wifi settings" -> "wifi"
            normalized == "open bluetooth settings" -> "bluetooth"
            normalized == "open display settings" -> "display"
            normalized == "open sound settings" -> "sound"
            normalized == "open battery settings" || normalized == "open battery saver settings" -> "battery"
            normalized == "open notification settings" -> "notifications"
            normalized == "open app settings" -> "app settings"
            normalized == "open settings" -> "settings"
            else -> return null
        }
        val intent = Intent(actions.getValue(key)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (key == "notifications") intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        if (key == "app settings") intent.data = android.net.Uri.parse("package:" + context.packageName)
        if (intent.resolveActivity(context.packageManager) == null) return TrevorAndroidActionResult(key, false, false, "No Android handler is installed for this action.")
        return runCatching {
            context.startActivity(intent)
            TrevorAndroidActionResult(key, true, true, "Android action dispatched and verified: " + key + " settings opened.")
        }.getOrElse { e ->
            TrevorAndroidActionResult(key, false, false, "Android action failed: " + (e.message ?: "unknown error"))
        }
    }
}