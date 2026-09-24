package com.trevor.assistant

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationManagerCompat
import java.security.MessageDigest

/** Part 17: centralized permission/security gates. */
object TrevorSecurityHardening {
    fun notificationsAllowed(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun microphoneAllowed(context: Context): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    fun overlayAllowed(context: Context): Boolean =
        android.provider.Settings.canDrawOverlays(context)

    fun usageAccessAllowed(context: Context): Boolean =
        runCatching {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            val mode = if (android.os.Build.VERSION.SDK_INT >= 29)
                appOps.unsafeCheckOpNoThrow("android:get_usage_stats", android.os.Process.myUid(), context.packageName)
            else appOps.checkOpNoThrow("android:get_usage_stats", android.os.Process.myUid(), context.packageName)
            mode == android.app.AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)

    fun hashForLog(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    fun safeExternalIntent(context: Context, intent: android.content.Intent): Boolean =
        intent.resolveActivity(context.packageManager) != null
}
