package app.orchard.tv.weather

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Locale
import java.util.zip.GZIPInputStream
import javax.net.ssl.HttpsURLConnection

/**
 * The one QWeather call this build makes, against the new v1 API (v7 is being retired):
 *
 * - `GET /weather/v1/current/{latitude}/{longitude}` — docs/api/weather/weather-current
 *
 * There is no city lookup: a place is resolved from the TV's own coordinates or the manual place,
 * never from a name.
 *
 * Authentication is the API KEY in the `X-QW-Api-Key` header. The docs also accept `?key=`, which is
 * not used: a key in a URL ends up in logs and in anything that records URLs. JWT would be the
 * preferred method overall (docs/configuration/authentication), but it needs an Ed25519 signature
 * that `java.security` only offers from API 33, and this app runs on API 26; API KEY is the
 * documented, supported alternative for the API (not the SDK) and is what the personal build uses.
 *
 * The key is held for the length of one request and never written to a log, a URL, or an exception
 * message.
 */
class QWeatherClient(
    private val host: String,
    private val apiKey: String,
    private val language: String = "zh",
) {

    private companion object {
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 8_000

        /** These replies are a few kilobytes. Anything larger is not one of ours. */
        const val MAX_BODY_BYTES = 256 * 1024
    }

    fun current(latitude: Double, longitude: Double, cityLabel: String): WeatherSnapshot {
        val path = "/weather/v1/current/${coordinate(latitude)}/${coordinate(longitude)}?lang=$language"
        val json = get(path)
        val condition = json.optJSONObject("condition")
            ?: throw WeatherException(FailureKind.Parse, "no condition in the reply")
        val code = condition.optString("code").toIntOrNull()
            ?: throw WeatherException(FailureKind.Parse, "no condition code in the reply")
        val temperature = json.optJSONObject("temperature")?.optDouble("value", Double.NaN) ?: Double.NaN
        if (temperature.isNaN()) throw WeatherException(FailureKind.Parse, "no temperature in the reply")

        val humidity = json.optDouble("humidity", Double.NaN)
        val wind = json.optJSONObject("wind")
        return WeatherSnapshot(
            city = cityLabel,
            text = condition.optString("text"),
            code = code,
            tempC = Math.round(temperature).toInt(),
            feelsLikeC = json.optJSONObject("feelsLike")?.optDouble("value", Double.NaN)
                ?.takeUnless { it.isNaN() }?.let { Math.round(it).toInt() },
            // The API sends 0..1; the panel shows a percentage.
            humidityPercent = humidity.takeUnless { it.isNaN() }?.let { Math.round(it * 100).toInt() },
            windCompass = wind?.optJSONObject("direction")?.optString("compass")?.takeIf { it.isNotBlank() },
            windScale = wind?.optInt("scale", -1)?.takeIf { it >= 0 },
            // v1 current carries no observation time (only metadata.tag), so the moment the reply
            // arrived is the most it can honestly claim. The UI labels it as "updated".
            fetchedAt = System.currentTimeMillis(),
            latitude = latitude,
            longitude = longitude,
        )
    }

    private fun coordinate(value: Double): String = String.format(Locale.US, "%.2f", value)

    private fun get(path: String): JSONObject {
        // Defence in depth: ApiHost has already vetted the host, and this checks the URL that would
        // actually be opened, not the string it was built from.
        val url = URL(ApiHost.url(host, path))
        if (url.protocol != "https" || ApiHost.normalize(url.host) != host) {
            throw WeatherException(FailureKind.BadHost, "refusing to open a URL that is not the configured https API Host")
        }

        val connection = (url.openConnection() as? HttpsURLConnection)
            ?: throw WeatherException(FailureKind.BadHost, "not an https connection")
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("X-QW-Api-Key", apiKey)
            connection.setRequestProperty("Accept", "application/json")
            // Ask for compression and decode it ourselves if the platform passes it through.
            connection.setRequestProperty("Accept-Encoding", "gzip")

            val status = try {
                connection.responseCode
            } catch (e: SocketTimeoutException) {
                throw WeatherException(FailureKind.Offline, "timed out", e)
            } catch (e: IOException) {
                throw WeatherException(FailureKind.Offline, "no route to the API host", e)
            }

            when {
                status in 300..399 -> throw WeatherException(FailureKind.Redirect, "HTTP $status redirect refused")
                status == HttpURLConnection.HTTP_OK -> {}
                status == 401 -> throw WeatherException(FailureKind.Unauthorized, "HTTP 401")
                status == 403 -> throw WeatherException(FailureKind.Forbidden, "HTTP 403")
                status == 400 -> throw WeatherException(
                    FailureKind.BadRequest,
                    "HTTP 400 ${problemDetail(connection.errorStream)}",
                )
                status == 429 -> throw WeatherException(FailureKind.RateLimited, "HTTP 429")
                status in 500..599 -> throw WeatherException(FailureKind.Server, "HTTP $status")
                else -> throw WeatherException(FailureKind.Network, "HTTP $status")
            }

            val body = readBody(connection)
            return try {
                JSONObject(body)
            } catch (e: Exception) {
                throw WeatherException(FailureKind.Parse, "reply was not JSON", e)
            }
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    /**
     * Reads the body, decompressing when it arrives as gzip. The header is not trusted alone: the
     * two magic bytes settle it, so a platform that already decoded the stream is not decoded twice.
     */
    private fun readBody(connection: HttpsURLConnection): String {
        val raw = connection.inputStream
        val buffered = raw.buffered()
        buffered.mark(2)
        val b0 = buffered.read()
        val b1 = buffered.read()
        buffered.reset()
        val stream: InputStream = if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(buffered) else buffered

        stream.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_BODY_BYTES) throw WeatherException(FailureKind.Parse, "reply too large")
                out.write(buffer, 0, read)
            }
            return out.toString("UTF-8")
        }
    }

    /** QWeather's errors are RFC 7807 `application/problem+json`; the detail names the bad parameter. */
    private fun problemDetail(stream: InputStream?): String = runCatching {
        stream?.bufferedReader()?.use { it.readText() }?.let { JSONObject(it) }
            ?.optJSONObject("error")?.optString("detail").orEmpty()
    }.getOrDefault("")
}
