package app.orchard.tv.weather

/**
 * What a TV's own location setting said, before anything is resolved on the network.
 * Any field may be null: firmwares write different subsets.
 */
data class RawLocation(
    val province: String? = null,
    val city: String? = null,
    val district: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
) {
    val hasCoordinates: Boolean get() = latitude != null && longitude != null
    val hasNames: Boolean get() = !city.isNullOrBlank() || !province.isNullOrBlank() || !district.isNullOrBlank()

    /** The shortest honest name for the reading: the city where there is one. */
    val label: String? get() = listOf(city, province, district).firstOrNull { !it.isNullOrBlank() }
}

/**
 * Reads the location a Xiaomi TV keeps in `Settings.System`, in whichever shape it happens to be.
 *
 * Verified shapes on the two reference TVs could not be collected for this build (no device access),
 * so this accepts everything plausible rather than one guessed format:
 * - a JSON object, e.g. `{"province":"湖南省","city":"长沙市","district":"岳麓区"}`, with the
 *   Chinese and English key spellings both seen in vendor code;
 * - a flat string, e.g. `湖南省,长沙市,岳麓区`, `湖南省 长沙市 岳麓区`;
 * - a bare city name, or a bare coordinate pair.
 *
 * It never invents a city: an empty or unrecognisable value returns null, and the caller falls back
 * to the manual location in the phone settings page.
 */
object LocationParser {

    private val PROVINCE_KEYS = listOf("province", "prov", "provinceName", "省")
    private val CITY_KEYS = listOf("city", "cityName", "市")
    private val DISTRICT_KEYS = listOf("district", "county", "area", "districtName", "区", "县")
    private val LAT_KEYS = listOf("latitude", "lat", "纬度")
    private val LON_KEYS = listOf("longitude", "lon", "lng", "long", "经度")

    /** Values that mean "not chosen yet" rather than a place. */
    private val PLACEHOLDERS = setOf("", "null", "none", "unknown", "自动", "自动定位", "定位", "中国", "china")

    fun parse(raw: String?): RawLocation? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text.lowercase() in PLACEHOLDERS) return null
        val location = if (text.startsWith("{") || text.startsWith("[")) parseObject(text) else parseFlat(text)
        return location?.takeIf { it.hasCoordinates || it.hasNames }
    }

    /**
     * Pulls `"key": value` pairs out of a small JSON object. A tolerant regex rather than a JSON
     * parser: these values are place names, they are never escaped, and the shape varies by firmware.
     */
    private fun parseObject(text: String): RawLocation? {
        fun str(keys: List<String>): String? {
            for (key in keys) {
                val m = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(text) ?: continue
                val v = m.groupValues[1].trim()
                if (v.isNotEmpty()) return v
            }
            return null
        }
        fun num(keys: List<String>): Double? {
            for (key in keys) {
                val m = Regex("\"$key\"\\s*:\\s*\"?(-?\\d+(?:\\.\\d+)?)\"?").find(text) ?: continue
                m.groupValues[1].toDoubleOrNull()?.let { return it }
            }
            return null
        }
        val lat = num(LAT_KEYS)
        val lon = num(LON_KEYS)
        return RawLocation(
            province = str(PROVINCE_KEYS),
            city = str(CITY_KEYS),
            district = str(DISTRICT_KEYS),
            latitude = lat?.takeIf { it in -90.0..90.0 },
            longitude = lon?.takeIf { it in -180.0..180.0 },
        )
    }

    /** `湖南省,长沙市,岳麓区` and friends. */
    private fun parseFlat(text: String): RawLocation? {
        val parts = text
            .replace(Regex("[\\[\\]{}\"]"), " ")
            .split(Regex("[,，;|/\\s]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.lowercase() !in PLACEHOLDERS }
        if (parts.isEmpty()) return null

        val numbers = parts.mapNotNull { it.toDoubleOrNull() }
        if (parts.size == 2 && numbers.size == 2) {
            val (a, b) = numbers
            // Latitude first is the common order; in China a longitude-first pair is unmistakable
            // because the first number would exceed 90.
            return when {
                a in -90.0..90.0 && b in -180.0..180.0 && a !in 70.0..140.0 -> RawLocation(latitude = a, longitude = b)
                a in 70.0..140.0 && b in 0.0..60.0 -> RawLocation(latitude = b, longitude = a)
                else -> null
            }
        }
        return when (parts.size) {
            1 -> RawLocation(city = parts[0])
            2 -> RawLocation(province = parts[0], city = parts[1])
            else -> RawLocation(province = parts[0], city = parts[1], district = parts[2])
        }
    }
}
