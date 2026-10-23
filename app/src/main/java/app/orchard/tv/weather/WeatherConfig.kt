package app.orchard.tv.weather

import android.content.Context
import androidx.core.content.edit

/**
 * Everything the personal build needs to know to ask QWeather for a reading, stored on the TV only.
 *
 * Nothing here is compiled into the APK and nothing here is written to a file under version control:
 * the host and key are typed into the phone settings page after the user creates a QWeather account,
 * and live in this app's private preferences from then on. `allowBackup` is false in the manifest, so
 * they are not copied off the device by Android's backup either.
 *
 * The key is never logged and never handed out: [redacted] is what any status page may show.
 */
class WeatherConfig(context: Context) {

    private val prefs = context.getSharedPreferences("weather", Context.MODE_PRIVATE)

    /** Off means no request at all, regardless of credentials. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

    /** The account's API Host, normalised, or null when nothing usable has been saved. */
    var apiHost: String?
        get() = ApiHost.normalize(prefs.getString(KEY_HOST, null))
        set(value) = prefs.edit { putString(KEY_HOST, ApiHost.normalize(value)) }

    /** The API key. Read for a request and nowhere else. */
    var apiKey: String?
        get() = prefs.getString(KEY_KEY, null)?.trim()?.takeIf { it.isNotEmpty() }
        set(value) = prefs.edit { putString(KEY_KEY, value?.trim()?.takeIf { it.isNotEmpty() }) }

    /**
     * A fallback position for TVs whose own location setting is empty or unreadable. Always
     * coordinates here (e.g. `28.19,112.98`): this build does not do a city lookup, so a bare
     * name cannot be turned into a position.
     */
    var manualPlace: String?
        get() = prefs.getString(KEY_MANUAL, null)?.trim()?.takeIf { it.isNotEmpty() }
        set(value) = prefs.edit { putString(KEY_MANUAL, value?.trim()?.takeIf { it.isNotEmpty() }) }

    val hasKey: Boolean get() = apiKey != null
    val hasHost: Boolean get() = apiHost != null

    /** Both halves present and the switch on. Without this, no request is made at all. */
    val ready: Boolean get() = enabled && hasHost && hasKey

    fun clearKey() = prefs.edit { remove(KEY_KEY) }

    /** What a status page may say about this configuration. Never includes the key. */
    fun redacted(): Map<String, Any?> = mapOf(
        "enabled" to enabled,
        "host" to (apiHost ?: ""),
        "hostValid" to hasHost,
        "hasKey" to hasKey,
        "manualPlace" to (manualPlace ?: ""),
        "ready" to ready,
    )

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_HOST = "host"
        const val KEY_KEY = "api_key"
        const val KEY_MANUAL = "manual_place"
    }
}

/**
 * The last reading, kept so the home screen still shows something when a request is cooling down,
 * refused, or the network is down. Times are epoch milliseconds, and the UI says how old the value
 * is rather than presenting it as live.
 */
class WeatherStore(context: Context) {

    private val prefs = context.getSharedPreferences("weather_cache", Context.MODE_PRIVATE)

    var snapshot: WeatherSnapshot?
        get() = prefs.getString(KEY_SNAPSHOT, null)?.let { decodeSnapshot(it) }
        set(value) = prefs.edit {
            if (value == null) remove(KEY_SNAPSHOT) else putString(KEY_SNAPSHOT, encodeSnapshot(value))
        }

    private fun encodeSnapshot(s: WeatherSnapshot): String = listOf(
        s.city, s.text, s.code.toString(), s.tempC.toString(), s.feelsLikeC?.toString() ?: "",
        s.humidityPercent?.toString() ?: "", s.windCompass ?: "", s.windScale?.toString() ?: "",
        s.fetchedAt.toString(), s.latitude.toString(), s.longitude.toString(),
    ).joinToString("\u0001")

    private fun decodeSnapshot(raw: String): WeatherSnapshot? {
        val f = raw.split("\u0001")
        if (f.size != 11) return null
        return runCatching {
            WeatherSnapshot(
                city = f[0],
                text = f[1],
                code = f[2].toInt(),
                tempC = f[3].toInt(),
                feelsLikeC = f[4].toIntOrNull(),
                humidityPercent = f[5].toIntOrNull(),
                windCompass = f[6].ifBlank { null },
                windScale = f[7].toIntOrNull(),
                fetchedAt = f[8].toLong(),
                latitude = f[9].toDouble(),
                longitude = f[10].toDouble(),
            )
        }.getOrNull()
    }

    private companion object {
        const val KEY_SNAPSHOT = "snapshot"
    }
}

/**
 * [BudgetStore] on SharedPreferences. [save] uses `commit`, not `apply`: the count has to be on disk
 * before the request leaves, and `apply` only queues the write. A failed commit throws, so the caller
 * stops instead of sending a request it could not account for.
 */
class PrefsBudgetStore(context: Context) : BudgetStore {

    private val prefs = context.getSharedPreferences("weather_budget", Context.MODE_PRIVATE)

    override fun load(): BudgetState = BudgetState(
        monthKey = prefs.getString(KEY_MONTH, "").orEmpty(),
        monthCount = prefs.getInt(KEY_COUNT, 0),
        weatherAttemptAt = prefs.getLong(KEY_WEATHER, 0L),
    )

    override fun save(state: BudgetState) {
        val ok = prefs.edit()
            .putString(KEY_MONTH, state.monthKey)
            .putInt(KEY_COUNT, state.monthCount)
            .putLong(KEY_WEATHER, state.weatherAttemptAt)
            .commit()
        check(ok) { "could not persist the weather request budget" }
    }

    private companion object {
        const val KEY_MONTH = "month"
        const val KEY_COUNT = "count"
        const val KEY_WEATHER = "weather_at"
    }
}
