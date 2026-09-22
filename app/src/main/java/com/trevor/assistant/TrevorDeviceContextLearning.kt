package com.trevor.assistant

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.min

/**
 * Part 2: local device-context learning.
 *
 * Stores only compact derived context observations. No raw network identifiers,
 * battery history, screen captures, or Bluetooth device data are persisted.
 */
object TrevorDeviceContextLearning {
    private const val PREFS = "trevor_device_context_learning"
    private const val OBSERVATIONS = "context_observations"
    private const val MAX_OBSERVATIONS = 240
    private const val RETENTION_DAYS = 45

    data class ContextSnapshot(
        val hourBucket: Int,
        val weekday: Int,
        val batteryPercent: Int,
        val charging: Boolean,
        val network: String,
        val metered: Boolean,
        val screenInteractive: Boolean,
        val bluetoothEnabled: Boolean?,
        val orientation: String
    )

    data class ContextObservation(
        val packageName: String,
        val hourBucket: Int,
        val weekday: Int,
        val charging: Boolean,
        val batteryBucket: Int,
        val network: String,
        val metered: Boolean,
        val screenInteractive: Boolean,
        val bluetoothEnabled: Boolean?,
        val orientation: String,
        val observations: Int,
        val lastSeen: Long,
        val confidence: Double
    )

    /**
     * Capture a privacy-minimized snapshot of the current device context.
     * Bluetooth is nullable because Android may deny adapter-state access.
     */
    fun snapshot(context: Context): ContextSnapshot {
        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance().apply { timeInMillis = now }
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val batteryPercent = if (level >= 0 && scale > 0) {
            ((level * 100f) / scale).toInt().coerceIn(0, 100)
        } else {
            -1
        }
        val chargingStatus = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = chargingStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
            chargingStatus == BatteryManager.BATTERY_STATUS_FULL

        val power = context.getSystemService(PowerManager::class.java)
        val screenInteractive = power?.isInteractive == true

        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivity?.activeNetwork
            ?.let { connectivity.getNetworkCapabilities(it) }
            ?.let { caps ->
                when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "BLUETOOTH"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                    else -> "OTHER"
                }
            } ?: "OFFLINE"
        val capabilities = connectivity?.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        val metered = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false

        val bluetoothEnabled = runCatching {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) null else adapter.isEnabled
        }.getOrNull()

        val orientation = when (context.resources.configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> "LANDSCAPE"
            Configuration.ORIENTATION_PORTRAIT -> "PORTRAIT"
            else -> "UNKNOWN"
        }

        return ContextSnapshot(
            hourBucket = calendar.get(Calendar.HOUR_OF_DAY) / 2,
            weekday = calendar.get(Calendar.DAY_OF_WEEK),
            batteryPercent = batteryPercent,
            charging = charging,
            network = network,
            metered = metered,
            screenInteractive = screenInteractive,
            bluetoothEnabled = bluetoothEnabled,
            orientation = orientation
        )
    }

    /**
     * Record one compact observation for the current context.
     * Battery percentage is deliberately bucketed to avoid retaining fine-grained history.
     */
    fun record(context: Context, packageName: String? = null): ContextObservation {
        val snapshot = snapshot(context)
        val bucketedBattery = if (snapshot.batteryPercent < 0) -1 else (snapshot.batteryPercent / 10) * 10
        val observedPackage = packageName?.takeIf { it.isNotBlank() } ?: "*"
        val key = buildKey(snapshot, packageName)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val observations = read(prefs).toMutableMap()
        val old = observations[key]
        val count = (old?.observations ?: 0) + 1
        val now = System.currentTimeMillis()
        val updated = ContextObservation(
            observedPackage,
            snapshot.hourBucket,
            snapshot.weekday,
            snapshot.charging,
            bucketedBattery,
            snapshot.network,
            snapshot.metered,
            snapshot.screenInteractive,
            snapshot.bluetoothEnabled,
            snapshot.orientation,
            count,
            now,
            confidence(count, now)
        )
        observations[key] = updated
        write(prefs, prune(observations, now))
        return updated
    }

    fun observations(context: Context): List<ContextObservation> =
        read(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
            .values.sortedByDescending { it.confidence }

    fun summary(context: Context): String {
        val current = snapshot(context)
        val top = observations(context).take(5)
        return buildString {
            append("Local device context: ")
            append(current.network)
            append(", ")
            append(if (current.charging) "charging" else "not charging")
            append(", ")
            append(if (current.screenInteractive) "screen active" else "screen inactive")
            append(", ")
            append(current.orientation.lowercase())
            if (top.isNotEmpty()) {
                append(". ")
                append(top.size)
                append(" learned context patterns available.")
            }
        }
    }

    private fun buildKey(snapshot: ContextSnapshot, packageName: String?): String {
        val batteryBucket = if (snapshot.batteryPercent < 0) -1 else (snapshot.batteryPercent / 10) * 10
        return listOf(
            packageName?.takeIf { it.isNotBlank() } ?: "*",
            snapshot.hourBucket,
            snapshot.weekday,
            snapshot.charging,
            batteryBucket,
            snapshot.network,
            snapshot.metered,
            snapshot.screenInteractive,
            snapshot.bluetoothEnabled ?: "UNKNOWN",
            snapshot.orientation
        ).joinToString("|")
    }

    private fun confidence(observations: Int, lastSeen: Long): Double {
        val ageDays = ((System.currentTimeMillis() - lastSeen).coerceAtLeast(0L) / 86_400_000L).toDouble()
        val recency = 1.0 / (1.0 + ageDays / 14.0)
        val repetition = min(1.0, observations / 10.0)
        return (0.25 + 0.75 * repetition) * recency
    }

    private fun prune(input: Map<String, ContextObservation>, now: Long): Map<String, ContextObservation> =
        input.values
            .filter { now - it.lastSeen <= RETENTION_DAYS * 86_400_000L }
            .sortedByDescending { it.confidence }
            .take(MAX_OBSERVATIONS)
            .associateBy {
                listOf(
                    it.packageName, it.hourBucket, it.weekday, it.charging, it.batteryBucket, it.network, it.metered,
                    it.screenInteractive, it.bluetoothEnabled ?: "UNKNOWN", it.orientation
                ).joinToString("|")
            }

    private fun read(prefs: android.content.SharedPreferences): MutableMap<String, ContextObservation> {
        val array = runCatching {
            JSONArray(prefs.getString(OBSERVATIONS, "[]") ?: "[]")
        }.getOrDefault(JSONArray())
        val out = mutableMapOf<String, ContextObservation>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val item = ContextObservation(
                o.optString("packageName", "*"),
                o.optInt("hourBucket"),
                o.optInt("weekday"),
                o.optBoolean("charging"),
                o.optInt("batteryBucket", -1),
                o.optString("network", "OFFLINE"),
                o.optBoolean("metered"),
                o.optBoolean("screenInteractive"),
                if (o.has("bluetoothEnabled") && !o.isNull("bluetoothEnabled")) o.optBoolean("bluetoothEnabled") else null,
                o.optString("orientation", "UNKNOWN"),
                o.optInt("observations"),
                o.optLong("lastSeen"),
                o.optDouble("confidence")
            )
            out[buildReadKey(item)] = item
        }
        return out
    }

    private fun buildReadKey(item: ContextObservation): String =
        listOf(
            item.packageName, item.hourBucket, item.weekday, item.charging, item.batteryBucket, item.network, item.metered,
            item.screenInteractive, item.bluetoothEnabled ?: "UNKNOWN", item.orientation
        ).joinToString("|")

    private fun write(prefs: android.content.SharedPreferences, values: Map<String, ContextObservation>) {
        val array = JSONArray()
        values.values.forEach {
            val objectValue = JSONObject()
                .put("packageName", it.packageName)
                .put("hourBucket", it.hourBucket)
                .put("weekday", it.weekday)
                .put("charging", it.charging)
                .put("batteryBucket", it.batteryBucket)
                .put("network", it.network)
                .put("metered", it.metered)
                .put("screenInteractive", it.screenInteractive)
                .put("observations", it.observations)
                .put("lastSeen", it.lastSeen)
                .put("confidence", it.confidence)
                .put("orientation", it.orientation)
            if (it.bluetoothEnabled == null) {
                objectValue.put("bluetoothEnabled", JSONObject.NULL)
            } else {
                objectValue.put("bluetoothEnabled", it.bluetoothEnabled)
            }
            array.put(objectValue)
        }
        prefs.edit().putString(OBSERVATIONS, array.toString()).apply()
    }
}
