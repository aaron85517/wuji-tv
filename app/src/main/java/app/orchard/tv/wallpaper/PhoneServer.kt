package app.orchard.tv.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.os.Build
import androidx.core.graphics.createBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import app.orchard.tv.weather.ApiHost
import app.orchard.tv.weather.FailureKind
import app.orchard.tv.weather.Trigger
import app.orchard.tv.weather.Weather
import app.orchard.tv.weather.WeatherConfig
import app.orchard.tv.weather.WeatherNotice
import app.orchard.tv.weather.WeatherState
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Inet4Address
import java.security.SecureRandom

/**
 * The page a phone opens after scanning the TV's QR code: choose a wallpaper and the screensaver,
 * or send photos from the phone. Choosing pictures with a remote is slow; with a phone it is not.
 *
 * It listens on the local network only while the wallpaper sheet is open on the TV (and a few
 * minutes after, so an upload can finish), and every address carries a random token shown only in
 * the QR code, so another device on the network cannot use it by guessing.
 */
class PhoneServer private constructor(private val context: Context, private val token: String) : NanoHTTPD(PORT) {

    override fun serve(session: IHTTPSession): Response {
        val prefix = "/$token"
        val uri = session.uri
        if (!uri.startsWith(prefix)) return text(Response.Status.NOT_FOUND, "Not found")
        val path = uri.removePrefix(prefix).ifEmpty { "/" }
        return runCatching {
            when {
                path == "/" -> newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", page())
                path == "/api/state" -> json(state())
                path.startsWith("/thumb/") -> thumb(path.removePrefix("/thumb/"))
                session.method == Method.POST && path == "/api/wallpaper" -> {
                    val id = form(session)["id"].orEmpty()
                    onMain { Wallpapers.select(id) }
                    json(state())
                }
                session.method == Method.POST && path == "/api/screensaver" -> {
                    val mode = ScreensaverMode.of(form(session)["mode"])
                    onMain { Wallpapers.chooseScreensaver(mode) }
                    json(state())
                }
                session.method == Method.POST && path == "/api/delete" -> {
                    val id = form(session)["id"].orEmpty()
                    onMain { Wallpapers.deletePhoto(id) }
                    json(state())
                }
                session.method == Method.POST && path == "/api/upload" -> upload(session)
                path == "/api/weather" -> weather(session)
                session.method == Method.POST && path == "/api/weather/refresh" -> {
                    Weather.refresh(Trigger.Manual)
                    json(weatherJson(WeatherConfig(context)))
                }
                else -> text(Response.Status.NOT_FOUND, "Not found")
            }
        }.getOrElse { text(Response.Status.INTERNAL_ERROR, it.message ?: "error") }
    }

    private fun page(): String = context.assets.open("web/index.html").bufferedReader().use { it.readText() }
        .replace("{{DEVICE}}", Build.MODEL.replace("_", " "))

    private fun state(): JSONObject {
        val list = JSONArray()
        val selected = Wallpapers.selectedId
        Wallpapers.items.forEach { w ->
            list.put(JSONObject().put("id", w.id).put("kind", w.kind.name.lowercase()).put("title", w.title).put("caption", w.caption ?: ""))
        }
        return JSONObject()
            .put("selected", selected)
            .put("latestDaily", Wallpapers.LATEST_DAILY)
            .put("screensaver", Wallpapers.screensaver.key)
            .put("wallpapers", list)
    }

    private fun thumb(id: String): Response {
        val bmp = Wallpapers.thumbnail(java.net.URLDecoder.decode(id, "UTF-8")) ?: return text(Response.Status.NOT_FOUND, "")
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
        val bytes = out.toByteArray()
        return newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(bytes), bytes.size.toLong()).apply {
            addHeader("Cache-Control", "max-age=600")
        }
    }

    private fun upload(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)
        var added = 0
        // NanoHTTPD stores each part in a temp file; several photos arrive as photo, photo1, photo2...
        files.values.forEach { path ->
            val f = File(path)
            if (f.exists() && f.length() > 0) {
                val id = f.inputStream().use { input -> runBlocking(Dispatchers.Main) { Wallpapers.addPhoto(input) } }
                if (id != null) added++
            }
        }
        return json(state().put("added", added))
    }

    /**
     * The weather settings, over the same token-protected page as the wallpapers.
     *
     * GET reports the state of the setup and is deliberately unable to report the key: only whether
     * one is stored. POST saves what was typed; the key travels in the request body, never in a URL,
     * and a blank key field means "keep the one already there" so the page never has to echo it.
     */
    private fun weather(session: IHTTPSession): Response {
        val config = WeatherConfig(context)
        if (session.method == Method.POST) {
            val form = form(session)
            // Validate the host before touching any stored value: a bad host must not leave a
            // half-written config (e.g. the switch already flipped) with no reconfigure behind it.
            val hostRaw = form["host"]?.orEmpty()?.trim()
            if (hostRaw != null && hostRaw.isNotEmpty() && ApiHost.normalize(hostRaw) == null) {
                return json(weatherJson(config).put("error", "host"))
            }
            if (form["enabled"] != null) config.enabled = form["enabled"] == "1"
            if (form["host"] != null) {
                config.apiHost = if (hostRaw.isNullOrEmpty()) null else hostRaw
            }
            // An explicit clear, or a new value; an absent/blank field leaves the stored key alone.
            if (form["clear_key"] == "1") config.clearKey()
            form["key"]?.trim()?.takeIf { it.isNotEmpty() }?.let { config.apiKey = it }
            form["manual_place"]?.let { config.manualPlace = it.trim().ifEmpty { null } }
            // The saved config must reach the live Weather state at once, whether or not a refresh
            // was asked for: switching the feature off (or clearing the key) has to hide the mark
            // immediately, not after a request that may be cooling down or still in flight.
            onMain { Weather.reconfigure() }
            if (form["refresh"] == "1") Weather.refresh(Trigger.Setup)
            return json(weatherJson(config))
        }
        return json(weatherJson(config))
    }

    /**
     * What the page may see about the weather. Note what is missing: the key. This object is the only
     * shape the server ever renders, so there is no code path that could serialise the credential.
     */
    private fun weatherJson(config: WeatherConfig): JSONObject {
        val state: WeatherState = Weather.state
        val out = JSONObject()
        config.redacted().forEach { (k, v) -> out.put(k, v) }
        out.put("monthCount", state.monthCount)
        // A reading is only reported when the freshly-saved config is ready AND the live state agrees
        // it should be shown. Otherwise the page must not see a stale temperature the TV has already
        // stopped showing (e.g. the key was just cleared while a request was in flight).
        if (config.ready && state.show) {
            state.snapshot?.let { s ->
                out.put("city", s.city)
                out.put("text", s.text)
                out.put("temp", s.tempC)
                out.put("code", s.code)
                out.put("fetchedAt", s.fetchedAt)
                out.put("stale", state.stale)
            }
        }
        out.put("place", state.place?.label ?: JSONObject.NULL)
        out.put("busy", state.busy)
        out.put("notice", noticeKey(state.notice))
        (state.notice as? WeatherNotice.CoolingDown)?.let { out.put("cooldownMinutes", it.minutes) }
        (state.notice as? WeatherNotice.Quota)?.let { out.put("quotaMonth", it.monthKey) }
        return out
    }

    private fun noticeKey(notice: WeatherNotice?): Any = when (notice) {
        null -> JSONObject.NULL
        WeatherNotice.NotConfigured -> "not_configured"
        WeatherNotice.Disabled -> "disabled"
        WeatherNotice.NoLocation -> "no_location"
        is WeatherNotice.CoolingDown -> "cooldown"
        is WeatherNotice.Quota -> "quota"
        is WeatherNotice.Failed -> when (notice.kind) {
            FailureKind.Unauthorized -> "auth"
            FailureKind.Forbidden, FailureKind.BadHost -> "forbidden"
            FailureKind.RateLimited -> "rate"
            FailureKind.Server -> "server"
            FailureKind.Parse -> "parse"
            else -> "network"
        }
    }

    private fun form(session: IHTTPSession): Map<String, String> {
        session.parseBody(HashMap())
        return session.parameters.mapValues { it.value.firstOrNull().orEmpty() }
    }

    private fun onMain(block: () -> Unit) = runBlocking(Dispatchers.Main) { block() }

    private fun json(o: JSONObject) = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", o.toString())

    private fun text(status: Response.Status, msg: String) = newFixedLengthResponse(status, "text/plain; charset=utf-8", msg)

    companion object {
        const val PORT = 8790

        @Volatile
        private var running: PhoneServer? = null

        /** Starts the server if needed and returns the address to put in the QR code, or null offline. */
        @Synchronized
        fun start(context: Context): String? {
            val ip = lanAddress(context) ?: return null
            val server = running ?: PhoneServer(context.applicationContext, newToken()).also {
                runCatching { it.start(SOCKET_READ_TIMEOUT, true) }.onFailure { return null }
                running = it
            }
            return "http://$ip:$PORT/${server.token}/"
        }

        @Synchronized
        fun stop() {
            running?.stop()
            running = null
        }

        private fun newToken(): String {
            val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
            val rnd = SecureRandom()
            return (1..8).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
        }

        private fun lanAddress(context: Context): String? {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
            val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
            return props.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
        }

        /** A QR code, dark modules on white, each module [scale] pixels square. */
        fun qr(text: String, scale: Int = 8): Bitmap {
            val matrix = QRCodeWriter().encode(
                text, BarcodeFormat.QR_CODE, 0, 0,
                mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
            )
            val n = matrix.width
            val bmp = createBitmap(n * scale, n * scale)
            for (y in 0 until n) for (x in 0 until n) {
                if (matrix[x, y]) for (dy in 0 until scale) for (dx in 0 until scale) bmp.setPixel(x * scale + dx, y * scale + dy, Color.BLACK)
                else for (dy in 0 until scale) for (dx in 0 until scale) bmp.setPixel(x * scale + dx, y * scale + dy, Color.WHITE)
            }
            return bmp
        }
    }
}
