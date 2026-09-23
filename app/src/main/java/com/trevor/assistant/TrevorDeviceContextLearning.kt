package com.trevor.assistant

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.min

/**
 * Part 2: local device-context learning.
 *
 * Stores only compact derived context observations. No raw network identifiers,
 * battery history, screen captures, Bluetooth device data, SSIDs, BSSIDs, IPs,
 * URLs, or page contents are persisted.
 */
object TrevorDeviceContextLearning {
    private const val PREFS = "trevor_device_context_learning"
    private const val OBSERVATIONS = "context_observations"
    private const val TRANSITIONS = "context_transitions"
    private const val SEQUENCES = "context_sequences"

    private const val MAX_OBSERVATIONS = 320
    private const val MAX_TRANSITIONS = 360
    private const val MAX_SEQUENCES = 240
    private const val MAX_SEQUENCE_LENGTH = 4
    private const val RETENTION_DAYS = 45
    private const val USAGE_LOOKBACK_MINUTES = 40L

    private val storageLock = Any()

    data class ContextSnapshot(
        val hourBucket: Int,
        val weekday: Int,
        val batteryPercent: Int,
        val charging: Boolean,
        val network: String,
        val networkTransports: String,
        val networkValidated: Boolean,
        val metered: Boolean,
        val temporaryUnmetered: Boolean,
        val downstreamBandwidthBucket: Int,
        val screenInteractive: Boolean,
        val bluetoothEnabled: Boolean?,
        val orientation: String
    ) {
        fun signature(): String = listOf(
            hourBucket,
            weekday,
            charging,
            batteryBucketOf(batteryPercent),
            network,
            networkTransports,
            networkValidated,
            metered,
            temporaryUnmetered,
            downstreamBandwidthBucket,
            screenInteractive,
            bluetoothEnabled ?: "UNKNOWN",
            orientation
        ).joinToString("|")
    }

    data class ContextObservation(
        val packageName: String,
        val hourBucket: Int,
        val weekday: Int,
        val charging: Boolean,
        val batteryBucket: Int,
        val network: String,
        val networkTransports: String,
        val networkValidated: Boolean,
        val metered: Boolean,
        val temporaryUnmetered: Boolean,
        val downstreamBandwidthBucket: Int,
        val screenInteractive: Boolean,
        val bluetoothEnabled: Boolean?,
        val orientation: String,
        val observations: Int,
        val lastSeen: Long,
        val confidence: Double
    ) {
        fun signature(): String = listOf(
            hourBucket,
            weekday,
            charging,
            batteryBucket,
            network,
            networkTransports,
            networkValidated,
            metered,
            temporaryUnmetered,
            downstreamBandwidthBucket,
            screenInteractive,
            bluetoothEnabled ?: "UNKNOWN",
            orientation
        ).joinToString("|")
    }

    data class ContextTransition(
        val fromSignature: String,
        val toSignature: String,
        val observations: Int,
        val lastSeen: Long,
        val confidence: Double
    )

    data class ContextSequence(
        val states: List<String>,
        val observations: Int,
        val lastSeen: Long,
        val confidence: Double
    )

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
        val capabilities = connectivity?.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }

        val transports = capabilities?.let { caps ->
            buildList {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("WIFI")
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("CELLULAR")
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ETHERNET")
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("BLUETOOTH")
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                ) add("WIFI_AWARE")
            }.sorted().joinToString("+")
        }.orEmpty()

        val network = when {
            capabilities == null -> "OFFLINE"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "BLUETOOTH"
            else -> "OTHER"
        }

        val validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val permanentlyUnmetered =
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
        val temporarilyUnmetered =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                capabilities?.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED
                ) == true
        val metered = capabilities != null && !permanentlyUnmetered && !temporarilyUnmetered
        val bandwidthBucket = capabilities?.let {
            bandwidthBucket(it.getLinkDownstreamBandwidthKbps())
        } ?: 0

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
            networkTransports = transports.ifBlank { "NONE" },
            networkValidated = validated,
            metered = metered,
            temporaryUnmetered = temporarilyUnmetered,
            downstreamBandwidthBucket = bandwidthBucket,
            screenInteractive = screenInteractive,
            bluetoothEnabled = bluetoothEnabled,
            orientation = orientation
        )
    }

    fun record(context: Context, packageName: String? = null): ContextObservation {
        val snapshot = snapshot(context)
        val batteryBucket = batteryBucketOf(snapshot.batteryPercent)
        val observedPackage = packageName?.takeIf { it.isNotBlank() } ?: "*"
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val updated: ContextObservation

        synchronized(storageLock) {
            val observations = readObservations(prefs).toMutableMap()
            val old = observations[buildKey(snapshot, observedPackage)]
            val count = (old?.observations ?: 0) + 1
            updated = ContextObservation(
                observedPackage,
                snapshot.hourBucket,
                snapshot.weekday,
                snapshot.charging,
                batteryBucket,
                snapshot.network,
                snapshot.networkTransports,
                snapshot.networkValidated,
                snapshot.metered,
                snapshot.temporaryUnmetered,
                snapshot.downstreamBandwidthBucket,
                snapshot.screenInteractive,
                snapshot.bluetoothEnabled,
                snapshot.orientation,
                count,
                now,
                confidence(count, now)
            )
            observations[buildKey(snapshot, observedPackage)] = updated

            val stateSignature = snapshot.signature()
            val previous = prefs.getString("last_context_signature", null)
            if (previous != null && previous != stateSignature) {
                recordTransitionLocked(prefs, previous, stateSignature, now)
            }
            val rolling = readStringList(prefs, "rolling_context_sequence").toMutableList()
            if (rolling.lastOrNull() != stateSignature) {
                rolling.add(stateSignature)
                while (rolling.size > MAX_SEQUENCE_LENGTH) rolling.removeAt(0)
                if (rolling.size >= 2) recordSequenceSuffixesLocked(prefs, rolling, now)
            }

            writeObservations(prefs, pruneObservations(observations, now))
            prefs.edit()
                .putString("last_context_signature", stateSignature)
                .putLong("last_context_seen", now)
                .putString("rolling_context_sequence", JSONArray(rolling).toString())
                .apply()
        }
        return updated
    }

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        return runCatching {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            ) == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
    }

    fun recordCurrentForegroundContext(
        context: Context,
        lookbackMinutes: Long = USAGE_LOOKBACK_MINUTES
    ): ContextObservation? {
        if (!hasUsageAccess(context)) return null
        val manager = context.getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        var latestResumedPackage: String? = null
        if (manager != null) {
            val events = runCatching {
                manager.queryEvents(
                    now - lookbackMinutes.coerceAtLeast(1L) * 60L * 1000L,
                    now
                )
            }.getOrNull()
            val event = UsageEvents.Event()
            if (events != null) {
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED &&
                        !event.packageName.isNullOrBlank()
                    ) {
                        latestResumedPackage = event.packageName
                    }
                }
            }
        }
        val packageName = latestResumedPackage?.takeIf { it != context.packageName } ?: return null
        return record(context, packageName)
    }

    fun observations(context: Context): List<ContextObservation> {
        val now = System.currentTimeMillis()
        return readObservations(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
            .values
            .filter { now - it.lastSeen <= RETENTION_DAYS * 86_400_000L }
            .map { it.copy(confidence = confidence(it.observations, it.lastSeen, now)) }
            .sortedByDescending { it.confidence }
    }

    fun transitions(context: Context): List<ContextTransition> {
        val now = System.currentTimeMillis()
        return readTransitions(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
            .values
            .filter { now - it.lastSeen <= RETENTION_DAYS * 86_400_000L }
            .map { it.copy(confidence = confidence(it.observations, it.lastSeen, now)) }
            .sortedByDescending { it.confidence }
    }

    fun sequences(context: Context): List<ContextSequence> {
        val now = System.currentTimeMillis()
        return readSequences(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
            .values
            .filter { now - it.lastSeen <= RETENTION_DAYS * 86_400_000L }
            .map { it.copy(confidence = confidence(it.observations, it.lastSeen, now)) }
            .sortedByDescending { it.confidence }
    }

    fun summary(context: Context): String {
        val current = snapshot(context)
        val top = observations(context).take(5)
        val transitionCount = transitions(context).size
        val sequenceCount = sequences(context).size
        return buildString {
            append("Local device context: ")
            append(current.network)
            append(" [")
            append(current.networkTransports)
            append(if (current.networkValidated) ", validated" else ", not validated")
            append(if (current.metered) ", metered" else ", unmetered")
            append("], ")
            append(if (current.charging) "charging" else "not charging")
            append(", battery ")
            append(if (current.batteryPercent < 0) "unknown" else "${current.batteryPercent}%")
            append(", ")
            append(if (current.screenInteractive) "screen active" else "screen inactive")
            append(", ")
            append(current.orientation.lowercase())
            if (top.isNotEmpty()) {
                append(". ")
                append(top.size)
                append(" top context patterns, ")
                append(transitionCount)
                append(" transitions, ")
                append(sequenceCount)
                append(" context sequences learned.")
            }
        }
    }

    private fun recordTransitionLocked(
        prefs: android.content.SharedPreferences,
        from: String,
        to: String,
        now: Long
    ) {
        val values = readTransitions(prefs).toMutableMap()
        val key = "$from→$to"
        val old = values[key]
        val count = (old?.observations ?: 0) + 1
        values[key] = ContextTransition(from, to, count, now, confidence(count, now))
        writeTransitions(prefs, pruneTransitions(values, now))
    }

    private fun recordSequenceSuffixesLocked(
        prefs: android.content.SharedPreferences,
        rolling: List<String>,
        now: Long
    ) {
        val values = readSequences(prefs).toMutableMap()
        val start = maxOf(0, rolling.size - MAX_SEQUENCE_LENGTH)
        val sequence = rolling.subList(start, rolling.size)
        val key = sequence.joinToString("→")
        val old = values[key]
        val count = (old?.observations ?: 0) + 1
        values[key] = ContextSequence(sequence.toList(), count, now, confidence(count, now))
        writeSequences(prefs, pruneSequences(values, now))
    }

    private fun buildKey(snapshot: ContextSnapshot, packageName: String): String =
        listOf(packageName, snapshot.signature()).joinToString("|")

    private fun confidence(
        observations: Int,
        lastSeen: Long,
        now: Long = System.currentTimeMillis()
    ): Double {
        val ageDays = ((now - lastSeen).coerceAtLeast(0L) / 86_400_000L).toDouble()
        val recency = 1.0 / (1.0 + ageDays / 14.0)
        val repetition = min(1.0, observations / 10.0)
        return (0.25 + 0.75 * repetition) * recency
    }

    private fun pruneObservations(
        input: Map<String, ContextObservation>,
        now: Long
    ): Map<String, ContextObservation> =
        input.values
            .filter { now - it.lastSeen <= RETENTION_DAYS * 86_400_000L }
            .sortedByDescending { confidence(it.observations, it.lastSeen, now) }
            .take(MAX_OBSERVATIONS)
            .associateBy { buildKey(it) }

    private fun pruneTransitions(
        input: Map<String, ContextTransition>,
        now: Long
    ): Map<String, ContextTransition> =
        input.values
            .filter { now - it.lastSeen <= RETENTION_DAYS * 86_400_000L }
            .sortedByDescending { confidence(it.observations, it.lastSeen, now) }
            .take(MAX_TRANSITIONS)
            .associateBy { "${it.fromSignature}→${it.toSignature}" }

    private fun pruneSequences(
        input: Map<String, ContextSequence>,
        now: Long
    ): Map<String, ContextSequence> =
        input.values
            .filter { now - it.lastSeen <= RETENTION_DAYS * 86_400_000L }
            .sortedByDescending { confidence(it.observations, it.lastSeen, now) }
            .take(MAX_SEQUENCES)
            .associateBy { it.states.joinToString("→") }

    private fun buildKey(item: ContextObservation): String =
        listOf(item.packageName, item.signature()).joinToString("|")

    private fun readObservations(
        prefs: android.content.SharedPreferences
    ): MutableMap<String, ContextObservation> {
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
                o.optString("networkTransports", "NONE"),
                o.optBoolean("networkValidated"),
                o.optBoolean("metered"),
                o.optBoolean("temporaryUnmetered"),
                o.optInt("downstreamBandwidthBucket", 0),
                o.optBoolean("screenInteractive"),
                if (o.has("bluetoothEnabled") && !o.isNull("bluetoothEnabled")) o.optBoolean("bluetoothEnabled") else null,
                o.optString("orientation", "UNKNOWN"),
                o.optInt("observations"),
                o.optLong("lastSeen"),
                o.optDouble("confidence")
            )
            out[buildKey(item)] = item
        }
        return out
    }

    private fun readTransitions(
        prefs: android.content.SharedPreferences
    ): MutableMap<String, ContextTransition> {
        val array = runCatching {
            JSONArray(prefs.getString(TRANSITIONS, "[]") ?: "[]")
        }.getOrDefault(JSONArray())
        val out = mutableMapOf<String, ContextTransition>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val item = ContextTransition(
                o.optString("fromSignature"),
                o.optString("toSignature"),
                o.optInt("observations"),
                o.optLong("lastSeen"),
                o.optDouble("confidence")
            )
            out["${item.fromSignature}→${item.toSignature}"] = item
        }
        return out
    }

    private fun readSequences(
        prefs: android.content.SharedPreferences
    ): MutableMap<String, ContextSequence> {
        val array = runCatching {
            JSONArray(prefs.getString(SEQUENCES, "[]") ?: "[]")
        }.getOrDefault(JSONArray())
        val out = mutableMapOf<String, ContextSequence>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val statesArray = o.optJSONArray("states") ?: JSONArray()
            val states = buildList {
                for (index in 0 until statesArray.length()) add(statesArray.optString(index))
            }
            if (states.size < 2) continue
            val item = ContextSequence(
                states,
                o.optInt("observations"),
                o.optLong("lastSeen"),
                o.optDouble("confidence")
            )
            out[states.joinToString("→")] = item
        }
        return out
    }

    private fun readStringList(
        prefs: android.content.SharedPreferences,
        key: String
    ): List<String> {
        val array = runCatching {
            JSONArray(prefs.getString(key, "[]") ?: "[]")
        }.getOrDefault(JSONArray())
        return buildList {
            for (i in 0 until array.length()) add(array.optString(i))
        }
    }

    private fun writeObservations(
        prefs: android.content.SharedPreferences,
        values: Map<String, ContextObservation>
    ) {
        val array = JSONArray()
        values.values.forEach {
            array.put(
                JSONObject()
                    .put("packageName", it.packageName)
                    .put("hourBucket", it.hourBucket)
                    .put("weekday", it.weekday)
                    .put("charging", it.charging)
                    .put("batteryBucket", it.batteryBucket)
                    .put("network", it.network)
                    .put("networkTransports", it.networkTransports)
                    .put("networkValidated", it.networkValidated)
                    .put("metered", it.metered)
                    .put("temporaryUnmetered", it.temporaryUnmetered)
                    .put("downstreamBandwidthBucket", it.downstreamBandwidthBucket)
                    .put("screenInteractive", it.screenInteractive)
                    .put("observations", it.observations)
                    .put("lastSeen", it.lastSeen)
                    .put("confidence", it.confidence)
                    .put("orientation", it.orientation)
                    .put(
                        "bluetoothEnabled",
                        it.bluetoothEnabled ?: JSONObject.NULL
                    )
            )
        }
        prefs.edit().putString(OBSERVATIONS, array.toString()).apply()
    }

    private fun writeTransitions(
        prefs: android.content.SharedPreferences,
        values: Map<String, ContextTransition>
    ) {
        val array = JSONArray()
        values.values.forEach {
            array.put(
                JSONObject()
                    .put("fromSignature", it.fromSignature)
                    .put("toSignature", it.toSignature)
                    .put("observations", it.observations)
                    .put("lastSeen", it.lastSeen)
                    .put("confidence", it.confidence)
            )
        }
        prefs.edit().putString(TRANSITIONS, array.toString()).apply()
    }

    private fun writeSequences(
        prefs: android.content.SharedPreferences,
        values: Map<String, ContextSequence>
    ) {
        val array = JSONArray()
        values.values.forEach {
            val states = JSONArray()
            it.states.forEach(states::put)
            array.put(
                JSONObject()
                    .put("states", states)
                    .put("observations", it.observations)
                    .put("lastSeen", it.lastSeen)
                    .put("confidence", it.confidence)
            )
        }
        prefs.edit().putString(SEQUENCES, array.toString()).apply()
    }

    private fun batteryBucketOf(percent: Int): Int =
        if (percent < 0) -1 else (percent / 5) * 5

    private fun bandwidthBucket(kbps: Int): Int = when {
        kbps <= 0 -> 0
        kbps < 256 -> 1
        kbps < 1_000 -> 2
        kbps < 5_000 -> 3
        kbps < 20_000 -> 4
        kbps < 100_000 -> 5
        else -> 6
    }
}
