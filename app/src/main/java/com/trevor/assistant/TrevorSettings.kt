package com.trevor.assistant

import android.content.Context
import androidx.compose.ui.graphics.Color

enum class TrevorAccent(val label: String, val color: Color) {
    ICE("Ice Cyan", Color(0xFF58D9FF)),
    SKY("Polar Sky", Color(0xFF5B8CFF)),
    VIOLET("Aurora Violet", Color(0xFF9B7BFF));
    companion object { fun from(value: String): TrevorAccent = entries.firstOrNull { it.name == value } ?: ICE }
}

data class TrevorSettings(
    val aiEnabled: Boolean = true,
    val geminiEnabled: Boolean = true,
    val orbEnabled: Boolean = true,
    val animations: Boolean = true,
    val conciseResponses: Boolean = true,
    val technicalDetail: Boolean = true,
    val showStatusIndicators: Boolean = true,
    val showQuickActions: Boolean = true,
    val offlineFirst: Boolean = true,
    val iceFrost: Boolean = true,
    val accent: TrevorAccent = TrevorAccent.ICE
)

object TrevorSettingsStore {
    private const val PREFS = "trevor_settings"
    fun load(context: Context): TrevorSettings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return TrevorSettings(
            aiEnabled = p.getBoolean("aiEnabled", true),
            geminiEnabled = p.getBoolean("geminiEnabled", true),
            orbEnabled = p.getBoolean("orbEnabled", true),
            animations = p.getBoolean("animations", true),
            conciseResponses = p.getBoolean("conciseResponses", true),
            technicalDetail = p.getBoolean("technicalDetail", true),
            showStatusIndicators = p.getBoolean("showStatusIndicators", true),
            showQuickActions = p.getBoolean("showQuickActions", true),
            offlineFirst = p.getBoolean("offlineFirst", true),
            iceFrost = p.getBoolean("iceFrost", true),
            accent = TrevorAccent.from(p.getString("accent", TrevorAccent.ICE.name) ?: TrevorAccent.ICE.name)
        )
    }
    fun save(context: Context, s: TrevorSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("aiEnabled", s.aiEnabled).putBoolean("geminiEnabled", s.geminiEnabled)
            .putBoolean("orbEnabled", s.orbEnabled).putBoolean("animations", s.animations)
            .putBoolean("conciseResponses", s.conciseResponses).putBoolean("technicalDetail", s.technicalDetail)
            .putBoolean("showStatusIndicators", s.showStatusIndicators).putBoolean("showQuickActions", s.showQuickActions)
            .putBoolean("offlineFirst", s.offlineFirst).putBoolean("iceFrost", s.iceFrost)
            .putString("accent", s.accent.name).apply()
    }
}
