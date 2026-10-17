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

    /**
     * Launch an installed app from ordinary phrasing such as "open YouTube",
     * "launch WhatsApp", "start Chrome", or "open app YouTube".
     * Returns null when the input is not an app-launch command.
     */
    fun tryLaunchApp(context: Context, command: String): TrevorAndroidActionResult? {
        val raw = command.trim()
        val match = Regex(
            """^(?:(?:hey\s+)?(?:trevor[, ]+\s*)?|(?:can|could|would)\s+you\s+(?:please\s+)?|please\s+)?(?:please\s+)?(?:open|launch|start)\s+(?:the\s+)?(.+?)(?:\s+please)?[.!?]*$""",
            RegexOption.IGNORE_CASE
        ).find(raw) ?: return null
        var requested = match.groupValues[1].trim()
        requested = requested.replace(Regex("""^app\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+app$""", RegexOption.IGNORE_CASE), "")
            .trim()
        if (requested.isBlank()) return null

        // Keep system settings on the dedicated action path.
        if (requested.lowercase() in setOf("settings", "wifi settings", "wi-fi settings",
                "bluetooth settings", "display settings", "sound settings",
                "battery settings", "battery saver settings", "notification settings",
                "app settings")) return null

        val packageManager = context.packageManager
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val installed = runCatching {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(launchIntent, 0)
        }.getOrDefault(emptyList())

        val target = installed.firstOrNull {
            it.loadLabel(packageManager).toString().equals(requested, ignoreCase = true)
        } ?: installed.firstOrNull {
            it.loadLabel(packageManager).toString().equals(
                requested.removeSuffix(" app").trim(), ignoreCase = true
            )
        }

        if (target == null) {
            return TrevorAndroidActionResult(
                "open_app", false, false,
                "I couldn't find a launchable installed app named '$requested'. Check the app name or install it first."
            )
        }

        val label = target.loadLabel(packageManager).toString()
        val intent = packageManager.getLaunchIntentForPackage(target.activityInfo.packageName)
            ?: return TrevorAndroidActionResult(
                "open_app", false, false,
                "'$label' is installed but Android did not provide a launch activity."
            )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            TrevorAndroidActionResult("open_app", true, false, "Opening $label.")
        }.getOrElse {
            TrevorAndroidActionResult(
                "open_app", false, false,
                "Android couldn't open $label. Check whether the app is disabled or restricted."
            )
        }
    }

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
            TrevorAndroidActionResult(key, true, false, "Android action dispatched to the system handler: " + key + " settings.")
        }.getOrElse { e ->
            TrevorAndroidActionResult(key, false, false, "Android action failed: " + (e.message ?: "unknown error"))
        }
    }
}