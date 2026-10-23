package app.orchard.tv.weather

/**
 * The weather feature's data and control types, split out of [Weather] so the pure logic can be
 * compiled and tested on the JVM without Android. [WeatherNotice], [WeatherState] and [FailureKind]
 * have no Android or Compose dependency.
 */

/** What triggered an attempt. Kept only so the panel can say why it last looked. */
enum class Trigger { Launch, Wake, StyleSwitch, Network, Manual, Setup, Tick }

/** Why there is no fresh reading, in terms the panel can explain. */
sealed interface WeatherNotice {
    data object NotConfigured : WeatherNotice
    data object Disabled : WeatherNotice
    data object NoLocation : WeatherNotice
    data class CoolingDown(val minutes: Int) : WeatherNotice
    data class Quota(val monthKey: String) : WeatherNotice
    data class Failed(val kind: FailureKind) : WeatherNotice
}

/**
 * Everything the home screen and the panel read, in one immutable value. [snapshot] is the last
 * reading that actually arrived; it is never invented, and the UI says how old it is.
 */
data class WeatherState(
    val ready: Boolean = false,
    val enabled: Boolean = true,
    val hasKey: Boolean = false,
    val hostValid: Boolean = false,
    val snapshot: WeatherSnapshot? = null,
    val place: Place? = null,
    val busy: Boolean = false,
    val notice: WeatherNotice? = null,
    val trigger: Trigger? = null,
    val monthCount: Int = 0,
) {
    /** A reading older than this is still shown, but marked as out of date. */
    val stale: Boolean
        get() = snapshot != null && System.currentTimeMillis() - snapshot.fetchedAt > STALE_AFTER_MILLIS

    val ageMinutes: Long
        get() = snapshot?.let { ((System.currentTimeMillis() - it.fetchedAt) / 60_000L).coerceAtLeast(0) } ?: 0L

    /**
     * Whether any weather may be shown at all. False unless the switch is on, both host and key are
     * set, and a reading actually arrived. When false, Home and Control Center hide the mark, the
     * capsule content and the weather button entirely — no placeholder, no icon, no request — and a
     * stale cached reading is hidden too, because a cleared key or a switch-off flips this off at
     * once.
     */
    val show: Boolean
        get() = enabled && ready && snapshot != null

    companion object {
        /**
         * Two refresh windows. Below this the value is presented plainly; above it the panel says it
         * is out of date and the home screen dims the number, so an old temperature is never passed
         * off as the current one.
         */
        const val STALE_AFTER_MILLIS = 3 * 60 * 60 * 1000L
    }
}

/** Why a request did not produce a reading. Mapped from QWeather's documented status codes. */
enum class FailureKind {
    /** No usable network right now. */
    Offline,

    /** The configured host is missing or not a `*.qweatherapi.com` address. */
    BadHost,

    /** 401: key or host wrong; QWeather deliberately does not say which. */
    Unauthorized,

    /** 403: no credit, overdue bill, request restriction, or the wrong host. */
    Forbidden,

    /** 400: bad place, unsupported parameters. */
    BadRequest,

    /** 429: too many requests, or the month's plan limit is spent. */
    RateLimited,

    /** 5xx. */
    Server,

    /** A 3xx. Never followed: a redirect could move the key to another host. */
    Redirect,

    /** The reply arrived but did not look like the documented shape. */
    Parse,

    /** The TV has no location and the user has not set one. */
    NoLocation,

    /** Something else, e.g. DNS or TLS. */
    Network,
}

class WeatherException(val kind: FailureKind, message: String, cause: Throwable? = null) :
    Exception(message, cause)
