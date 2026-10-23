package app.orchard.tv.weather

/**
 * The condition classes the home screen and the weather panel can draw. QWeather returns a numeric
 * `condition.code`; [SkyMap] folds those codes into these few shapes, so an unknown code still draws
 * something sensible instead of nothing.
 *
 * The new v1 current-weather response (GET /weather/v1/current/{lat}/{lon}) has no `icon` field and
 * no day/night flag — see docs/WEATHER.md. Night is therefore inferred from the device clock.
 */
enum class Sky { Clear, PartlyCloudy, Overcast, Rain, Snow, Thunder, Fog, Unknown }

/** A reading as it was received, ready to be shown or persisted. Times are epoch milliseconds. */
data class WeatherSnapshot(
    val city: String,
    val text: String,
    val code: Int,
    val tempC: Int,
    val feelsLikeC: Int?,
    val humidityPercent: Int?,
    val windCompass: String?,
    val windScale: Int?,
    val fetchedAt: Long,
    val latitude: Double,
    val longitude: Double,
)

/**
 * Where a position came from, for the panel's "来源" line and for troubleshooting. There is no
 * lookup source: this build resolves a place only from the TV's own coordinates or the manual
 * place, and never spends a Geo request to turn a name into coordinates.
 */
enum class LocationSource { Manual, SystemCoordinates }

/** A place resolved far enough to ask for weather at. */
data class Place(
    val label: String,
    val latitude: Double,
    val longitude: Double,
    val source: LocationSource,
)

object SkyMap {

    /** QWeather condition codes, per https://dev.qweather.com/docs/api/weather/weather-conditions/ */
    fun of(code: Int): Sky = when (code) {
        100 -> Sky.Clear
        101, 102, 103 -> Sky.PartlyCloudy
        104 -> Sky.Overcast
        302, 303, 304 -> Sky.Thunder
        in 300..399 -> Sky.Rain
        in 400..499 -> Sky.Snow
        501, 509, 514, 515 -> Sky.Fog
        // Haze, dust and sand read as a low-contrast grey sky in the same way fog does.
        500, 502, 511, 512, 513, 503, 504, 507, 508 -> Sky.Fog
        else -> Sky.Unknown
    }

    /**
     * v1 sends no day/night flag, so the night variant is decided by the TV's own clock. 19:00–06:00
     * covers the dark half of the year in most of China without a sunrise lookup.
     */
    fun isNight(hourOfDay: Int): Boolean = hourOfDay >= 19 || hourOfDay < 6
}
