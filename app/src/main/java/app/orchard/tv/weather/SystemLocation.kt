package app.orchard.tv.weather

import android.content.Context
import android.provider.Settings

/**
 * The TV's own idea of where it is, as the firmware stores it in `Settings.System`.
 *
 * Both keys are read without any permission (a plain system setting, not a location provider) and
 * both are optional. `mitv_user_location` is the one the household sets in the TV's settings;
 * `mitv_location` is the one the firmware fills in itself, and it is the older value. Whichever has
 * something usable first wins, matching how the TV's own weather app behaves.
 *
 * Deliberately **not** used: the built-in weather app's data provider (`com.miui.weather2` and
 * friends). It answers only to a short whitelist of system package names, so a third-party launcher
 * cannot read it, and pretending to be one of those packages to get in is not something this build
 * does. The city id that firmware keeps (`cityid`) is Xiaomi's own internal code, not a QWeather
 * LocationID, and is never sent to QWeather.
 */
object SystemLocation {

    /** In priority order: what the household chose, then what the firmware detected. */
    private val KEYS = listOf("mitv_user_location", "mitv_location")

    fun read(context: Context): RawLocation? {
        for (key in KEYS) {
            val raw = runCatching { Settings.System.getString(context.contentResolver, key) }.getOrNull()
            val parsed = LocationParser.parse(raw) ?: continue
            if (parsed.hasCoordinates || parsed.hasNames) return parsed
        }
        return null
    }
}
