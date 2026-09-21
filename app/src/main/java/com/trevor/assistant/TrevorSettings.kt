package com.trevor.assistant

import android.content.Context
import androidx.compose.ui.graphics.Color

enum class TrevorAccent(val label: String, val color: Color) {
    ICE("Ice Cyan", Color(0xFF58D9FF)),
    SKY("Polar Sky", Color(0xFF5B8CFF)),
    VIOLET("Aurora Violet", Color(0xFF9B7BFF));
    companion object { fun from(value: String): TrevorAccent = entries.firstOrNull { it.name == value } ?: ICE }
}

enum class TrevorPersonality(val label: String, val description: String) {
    PROFESSIONAL("Professional", "Calm, precise, proactive only when useful."),
    CHAOTIC("Chaotic", "Frequent jokes and spontaneous assistant messages."),
    DEADPOOL("Deadpool", "Playful banter and occasional unsolicited messages."),
    FOR_YOU("For You, Yes You 👉 🥸 👈", "Customizable personal mode with optional absurdity.")
}

data class TrevorSettings(
    val aiEnabled: Boolean = true,
    val geminiEnabled: Boolean = true,
    val autoProviderSwitch: Boolean = true,
    val preferredProvider: TrevorProviderId = TrevorProviderId.GEMINI,
    val proactiveNotifications: Boolean = true,
    val quietHours: Boolean = false,
    val orbEnabled: Boolean = true,
    val animations: Boolean = true,
    val conciseResponses: Boolean = true,
    val technicalDetail: Boolean = true,
    val showStatusIndicators: Boolean = true,
    val showQuickActions: Boolean = true,
    val offlineFirst: Boolean = true,
    val iceFrost: Boolean = true,
    val accent: TrevorAccent = TrevorAccent.ICE,
    val personality: TrevorPersonality = TrevorPersonality.PROFESSIONAL,
    val proactiveEnabled: Boolean = true,
    val backgroundNotifications: Boolean = true,
    val rubbishMode: Boolean = false,
    val rubbishIntervalSeconds: Int = 30,
    val spontaneousFrequencySeconds: Int = 180
)

object TrevorSettingsStore {
    private const val PREFS = "trevor_settings"

    fun load(context: Context): TrevorSettings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return TrevorSettings(
            aiEnabled = p.getBoolean("aiEnabled", true),
            geminiEnabled = p.getBoolean("geminiEnabled", true),
            autoProviderSwitch = p.getBoolean("autoProviderSwitch", true),
            preferredProvider = runCatching { TrevorProviderId.valueOf(p.getString("preferredProvider", TrevorProviderId.GEMINI.name) ?: TrevorProviderId.GEMINI.name) }.getOrDefault(TrevorProviderId.GEMINI),
            proactiveNotifications = p.getBoolean("proactiveNotifications", true),
            quietHours = p.getBoolean("quietHours", false),
            orbEnabled = p.getBoolean("orbEnabled", true),
            animations = p.getBoolean("animations", true),
            conciseResponses = p.getBoolean("conciseResponses", true),
            technicalDetail = p.getBoolean("technicalDetail", true),
            showStatusIndicators = p.getBoolean("showStatusIndicators", true),
            showQuickActions = p.getBoolean("showQuickActions", true),
            offlineFirst = p.getBoolean("offlineFirst", true),
            iceFrost = p.getBoolean("iceFrost", true),
            accent = TrevorAccent.from(p.getString("accent", TrevorAccent.ICE.name) ?: TrevorAccent.ICE.name),
            personality = runCatching {
                TrevorPersonality.valueOf(p.getString("personality", TrevorPersonality.PROFESSIONAL.name) ?: TrevorPersonality.PROFESSIONAL.name)
            }.getOrDefault(TrevorPersonality.PROFESSIONAL),
            proactiveEnabled = p.getBoolean("proactiveEnabled", true),
            backgroundNotifications = p.getBoolean("backgroundNotifications", true),
            rubbishMode = p.getBoolean("rubbishMode", false),
            rubbishIntervalSeconds = p.getInt("rubbishIntervalSeconds", 30).coerceIn(30, 600),
            spontaneousFrequencySeconds = p.getInt("spontaneousFrequencySeconds", 180).coerceIn(60, 1800)
        )
    }

    fun save(context: Context, s: TrevorSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("aiEnabled", s.aiEnabled)
            .putBoolean("geminiEnabled", s.geminiEnabled)
            .putBoolean("autoProviderSwitch", s.autoProviderSwitch)
            .putString("preferredProvider", s.preferredProvider.name)
            .putBoolean("proactiveNotifications", s.proactiveNotifications)
            .putBoolean("quietHours", s.quietHours)
            .putBoolean("orbEnabled", s.orbEnabled)
            .putBoolean("animations", s.animations)
            .putBoolean("conciseResponses", s.conciseResponses)
            .putBoolean("technicalDetail", s.technicalDetail)
            .putBoolean("showStatusIndicators", s.showStatusIndicators)
            .putBoolean("showQuickActions", s.showQuickActions)
            .putBoolean("offlineFirst", s.offlineFirst)
            .putBoolean("iceFrost", s.iceFrost)
            .putString("accent", s.accent.name)
            .putString("personality", s.personality.name)
            .putBoolean("proactiveEnabled", s.proactiveEnabled)
            .putBoolean("backgroundNotifications", s.backgroundNotifications)
            .putBoolean("rubbishMode", s.rubbishMode)
            .putInt("rubbishIntervalSeconds", s.rubbishIntervalSeconds)
            .putInt("spontaneousFrequencySeconds", s.spontaneousFrequencySeconds)
            .apply()
    }
}
