package app.orchard.tv.weather

/**
 * The only hosts this build will talk to: the account's own QWeather API Host, which is always a
 * subdomain of `qweatherapi.com` (https://dev.qweather.com/docs/configuration/api-host/).
 *
 * The host is part of the credential — QWeather rejects requests made through someone else's host —
 * so it is checked here before a URL is ever built. A typo, a proxy, or a redirect to anywhere else
 * fails locally instead of sending the key to the wrong place.
 */
object ApiHost {

    private const val SUFFIX = ".qweatherapi.com"

    /** Letters, digits and hyphens per label, at least one label before the suffix, no port or path. */
    private val SHAPE = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*\\.qweatherapi\\.com$")

    /**
     * Accepts what a person would paste into the phone page (`abc123.qweatherapi.com`, with or
     * without `https://`, with or without a trailing slash) and returns the bare host, or null.
     * `http://` is refused rather than upgraded: the key must never travel in clear text, even for
     * one request.
     */
    fun normalize(raw: String?): String? {
        var text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (text.startsWith("http://", ignoreCase = true)) return null
        text = text.removePrefix("https://").removePrefix("HTTPS://").removeSuffix("/").lowercase()
        if (text.contains('/') || text.contains(':') || text.contains('?') || text.contains('@')) return null
        return text.takeIf { SHAPE.matches(it) && it.length <= 253 }
    }

    fun url(host: String, path: String): String = "https://$host$path"
}
