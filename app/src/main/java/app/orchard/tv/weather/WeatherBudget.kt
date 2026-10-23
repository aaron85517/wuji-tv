package app.orchard.tv.weather

import java.time.Instant
import java.time.ZoneId

/**
 * What the budget remembers between process restarts. Small and flat, because it is written on every
 * attempt and read once per attempt.
 *
 * @param monthKey the natural month the count belongs to, `yyyy-MM` in the TV's own zone.
 * @param monthCount every real network attempt this month, successful or not.
 * @param weatherAttemptAt last weather request, successful or not. 0 means never.
 */
data class BudgetState(
    val monthKey: String = "",
    val monthCount: Int = 0,
    val weatherAttemptAt: Long = 0L,
)

/**
 * Durable storage for [BudgetState]. [save] must be synchronous and committed before it returns:
 * the whole point is that a request can never leave the device without the count already on disk.
 */
interface BudgetStore {
    fun load(): BudgetState
    fun save(state: BudgetState)
}

/** Which request channel an attempt belongs to. Only the weather channel remains: Geo is not used. */
enum class BudgetChannel { Weather }

sealed interface BudgetDecision {
    /** The count was written; the caller may now make exactly one request. */
    data class Allowed(val state: BudgetState) : BudgetDecision

    /** Less than one interval since the last attempt on this channel, or the clock went backwards. */
    data class CoolingDown(val retryInMillis: Long) : BudgetDecision

    /** The month's allowance is spent. Cached readings may still be shown; nothing may be sent. */
    data class MonthlyCapReached(val monthKey: String, val count: Int) : BudgetDecision
}

/**
 * The one gate every QWeather request goes through.
 *
 * Rules, and why each exists:
 * - **One weather attempt per hour**, whatever triggered it (launch, wake, style switch, network
 *   regained, a foreground periodic tick, a tap on the panel). Failures consume the hour too: a
 *   broken key or an offline TV must not turn into a retry storm, which QWeather's error-code page
 *   explicitly warns reads as abuse.
 * - **50,000 attempts per natural month**, counted before the request is sent, so a device that
 *   crashes mid-request still cannot exceed it.
 * - **A clock that jumps backwards never resets the month.** The stored month is kept and the
 *   cooldown keeps applying; otherwise setting the clock back would buy unlimited requests.
 *
 * This is a per-device ceiling, not an account ceiling: another TV, another app, or a reinstall of
 * this one all keep their own numbers. See docs/WEATHER.md.
 *
 * [acquire] is the only mutator and is serialised on this instance, so two coroutines that reach it
 * at the same moment cannot both be told yes.
 */
class WeatherBudget(
    private val store: BudgetStore,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val monthlyCap: Int = MONTHLY_CAP,
    private val weatherIntervalMillis: Long = WEATHER_INTERVAL_MILLIS,
) {

    private val lock = Any()

    fun state(): BudgetState = synchronized(lock) { normalise(store.load(), clock()) }

    /**
     * Charges one attempt to [channel] and returns whether the caller may make the request. The new
     * state is on disk before this returns, so the count survives a crash between here and the wire.
     */
    fun acquire(channel: BudgetChannel): BudgetDecision = synchronized(lock) {
        val now = clock()
        val current = normalise(store.load(), now)

        // The cap is on the month the state belongs to. A rollback leaves that month in place, so
        // this also covers "moved the clock back to a month already spent".
        if (current.monthCount >= monthlyCap) {
            return@synchronized BudgetDecision.MonthlyCapReached(current.monthKey, current.monthCount)
        }

        val last = current.weatherAttemptAt
        val interval = weatherIntervalMillis
        // A negative elapsed (clock set backwards) fails this test as well, which is what we want.
        val elapsed = now - last
        if (last > 0L && elapsed < interval) {
            return@synchronized BudgetDecision.CoolingDown((interval - elapsed).coerceIn(1L, interval))
        }

        val next = current.copy(
            monthCount = current.monthCount + 1,
            weatherAttemptAt = now,
        )
        store.save(next)
        BudgetDecision.Allowed(next)
    }

    fun monthKey(at: Long): String =
        Instant.ofEpochMilli(at).atZone(zone).let { "%04d-%02d".format(it.year, it.monthValue) }

    /**
     * Rolls the count forward into a new month, and never backwards. An empty key (fresh install)
     * simply takes the current month.
     */
    private fun normalise(state: BudgetState, now: Long): BudgetState {
        val month = monthKey(now)
        return when {
            state.monthKey.isEmpty() -> state.copy(monthKey = month)
            month > state.monthKey -> state.copy(monthKey = month, monthCount = 0)
            else -> state
        }
    }

    companion object {
        /** 50,000 requests per natural month, the ceiling this build will not cross. */
        const val MONTHLY_CAP = 50_000

        /** At most one weather request an hour, for every trigger combined. */
        const val WEATHER_INTERVAL_MILLIS = 60L * 60L * 1000L
    }
}
