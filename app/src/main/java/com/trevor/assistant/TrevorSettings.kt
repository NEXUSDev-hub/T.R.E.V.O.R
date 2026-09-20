package com.trevor.assistant

import android.content.Context

data class TrevorSettings(
    val aiEnabled: Boolean = true,
    val geminiEnabled: Boolean = true,
    val orbEnabled: Boolean = true,
    val developerMode: Boolean = false,
    val debugMode: Boolean = false,
    val developerConsole: Boolean = false,
    val animations: Boolean = true,
    val conciseResponses: Boolean = true,
    val technicalDetail: Boolean = true,
    val showStatusIndicators: Boolean = true,
    val showQuickActions: Boolean = true,
    val offlineFirst: Boolean = true,
    val secureStorage: Boolean = true,
    val localApiKeyEncryption: Boolean = true
)

object TrevorSettingsStore {
    private const val PREFS = "trevor_settings"

    fun load(context: Context): TrevorSettings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return TrevorSettings(
            aiEnabled = p.getBoolean("aiEnabled", true),
            geminiEnabled = p.getBoolean("geminiEnabled", true),
            orbEnabled = p.getBoolean("orbEnabled", true),
            developerMode = p.getBoolean("developerMode", false),
            debugMode = p.getBoolean("debugMode", false),
            developerConsole = p.getBoolean("developerConsole", false),
            animations = p.getBoolean("animations", true),
            conciseResponses = p.getBoolean("conciseResponses", true),
            technicalDetail = p.getBoolean("technicalDetail", true),
            showStatusIndicators = p.getBoolean("showStatusIndicators", true),
            showQuickActions = p.getBoolean("showQuickActions", true),
            offlineFirst = p.getBoolean("offlineFirst", true),
            secureStorage = p.getBoolean("secureStorage", true),
            localApiKeyEncryption = p.getBoolean("localApiKeyEncryption", true)
        )
    }

    fun save(context: Context, s: TrevorSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("aiEnabled", s.aiEnabled)
            .putBoolean("geminiEnabled", s.geminiEnabled)
            .putBoolean("orbEnabled", s.orbEnabled)
            .putBoolean("developerMode", s.developerMode)
            .putBoolean("debugMode", s.debugMode)
            .putBoolean("developerConsole", s.developerConsole)
            .putBoolean("animations", s.animations)
            .putBoolean("conciseResponses", s.conciseResponses)
            .putBoolean("technicalDetail", s.technicalDetail)
            .putBoolean("showStatusIndicators", s.showStatusIndicators)
            .putBoolean("showQuickActions", s.showQuickActions)
            .putBoolean("offlineFirst", s.offlineFirst)
            .putBoolean("secureStorage", s.secureStorage)
            .putBoolean("localApiKeyEncryption", s.localApiKeyEncryption)
            .apply()
    }

    fun reset(context: Context) = save(context, TrevorSettings())
}
