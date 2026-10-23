package app.orchard.tv.weather

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The weather feature's single entry point: settings, the last reading, and the one place allowed to
 * make a request.
 *
 * Every trigger — launch, waking from standby, switching home style, regaining the network, tapping
 * the panel — calls [refresh], and every one of them goes through the same gate in [WeatherBudget].
 * Nothing here wakes the TV, schedules an alarm, or registers background work: an attempt only
 * happens while the app is being used, and the gate decides whether it may reach the network.
 */
object Weather {

    private var appContext: Context? = null
    private var config: WeatherConfig? = null
    private var store: WeatherStore? = null
    private var budget: WeatherBudget? = null
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var inFlight = false

    /**
     * The once-a-minute tick that keeps the reading fresh while the app is on screen. It is cheap:
     * every call goes through [WeatherBudget], which refuses everything but the one allowed attempt
     * per hour, so the network is touched at most hourly no matter how often this fires.
     */
    private val foregroundTicker = object : Runnable {
        override fun run() {
            // Only ask while we are actually foregrounded; stopForegroundTicker cancels the loop the
            // moment the app leaves the screen, so this is a belt-and-braces check.
            refresh(Trigger.Tick)
            main.postDelayed(this, TICK_INTERVAL_MILLIS)
        }
    }

    var state by mutableStateOf(WeatherState())
        private set

    /** Called once from the activity; brings the last reading back after a restart. */
    fun init(context: Context) {
        if (appContext != null) return
        val ctx = context.applicationContext
        appContext = ctx
        config = WeatherConfig(ctx)
        store = WeatherStore(ctx)
        budget = WeatherBudget(PrefsBudgetStore(ctx))
        state = readState(busy = false, notice = null, trigger = null, place = null)
    }

    /**
     * Asks for a reading if the gate allows one. Safe to call from anywhere, as often as you like:
     * it is a no-op while another attempt is running, when the switch is off, when there are no
     * credentials, or when the hour's attempt is already spent.
     */
    fun refresh(trigger: Trigger) {
        main.post { attempt(trigger) }
    }

    /** For the phone page, which runs on a server thread and wants the outcome, not just a fire. */
    fun refreshAndReport(trigger: Trigger, onDone: (WeatherState) -> Unit) {
        main.post { attempt(trigger, onDone) }
    }

    /**
     * Starts the foreground tick the hourly refresh relies on. Called from the activity when the app
     * is on screen and stopped when it leaves: nothing here wakes the TV or runs while it is asleep.
     */
    fun startForegroundTicker() {
        main.removeCallbacks(foregroundTicker)
        main.postDelayed(foregroundTicker, TICK_INTERVAL_MILLIS)
    }

    fun stopForegroundTicker() {
        main.removeCallbacks(foregroundTicker)
    }

    /** The last reading is kept in memory only as long as it is current; this drops it. */
    fun forget() {
        store?.snapshot = null
        state = readState(busy = false, notice = null, trigger = null, place = state.place)
    }

    /**
     * Re-reads the configuration and republishes the state, without touching the network and without
     * the in-flight gate. Called after the phone page saves settings (or clears the key, or flips the
     * switch) so that switching the feature off hides every trace of it at once — even while a request
     * is still running, which [attempt] would otherwise let finish on the old credentials.
     *
     * When called from the main thread the update happens before this returns, so a caller that wants
     * the fresh state immediately (the phone page building its reply) can read [state] right after.
     */
    fun reconfigure() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            republishState()
        } else {
            main.post { republishState() }
        }
    }

    /** The actual republish, always on the main thread. */
    private fun republishState() {
        val notice = when {
            config?.enabled == false -> WeatherNotice.Disabled
            config?.ready == false -> WeatherNotice.NotConfigured
            else -> state.notice
        }
        state = readState(busy = state.busy, notice = notice, trigger = state.trigger, place = state.place)
    }

    private fun attempt(trigger: Trigger, report: ((WeatherState) -> Unit)? = null) {
        val config = config ?: return
        val store = store ?: return
        val budget = budget ?: return
        if (inFlight) {
            report?.invoke(state)
            return
        }
        if (!config.enabled) {
            state = readState(busy = false, notice = WeatherNotice.Disabled, trigger = trigger, place = state.place)
            report?.invoke(state)
            return
        }
        if (!config.ready) {
            state = readState(busy = false, notice = WeatherNotice.NotConfigured, trigger = trigger, place = state.place)
            report?.invoke(state)
            return
        }

        inFlight = true
        state = readState(busy = true, notice = null, trigger = trigger, place = state.place)
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { runAttempt(config, store, budget) }
            inFlight = false
            state = readState(
                busy = false,
                notice = outcome.notice,
                trigger = trigger,
                place = outcome.place ?: state.place,
            )
            report?.invoke(state)
        }
    }

    private class Outcome(val notice: WeatherNotice?, val place: Place?)

    /**
     * The whole attempt, off the main thread. The budget is charged before anything is sent, so a
     * refusal here means no request leaves the device.
     */
    private fun runAttempt(config: WeatherConfig, store: WeatherStore, budget: WeatherBudget): Outcome {
        when (val gate = budget.acquire(BudgetChannel.Weather)) {
            is BudgetDecision.CoolingDown ->
                return Outcome(WeatherNotice.CoolingDown(((gate.retryInMillis + 59_999L) / 60_000L).toInt()), null)
            is BudgetDecision.MonthlyCapReached -> return Outcome(WeatherNotice.Quota(gate.monthKey), null)
            is BudgetDecision.Allowed -> Unit
        }

        val host = config.apiHost ?: return Outcome(WeatherNotice.NotConfigured, null)
        val key = config.apiKey ?: return Outcome(WeatherNotice.NotConfigured, null)
        val client = QWeatherClient(host, key, language())

        return try {
            val place = resolvePlace(config) ?: return Outcome(WeatherNotice.NoLocation, null)
            val snapshot = client.current(place.latitude, place.longitude, place.label)
            store.snapshot = snapshot
            Outcome(null, place)
        } catch (e: WeatherException) {
            Outcome(WeatherNotice.Failed(e.kind), null)
        } catch (e: Exception) {
            Outcome(WeatherNotice.Failed(FailureKind.Network), null)
        }
    }

    /**
     * Where to ask about. The TV's own coordinates come first; when they are absent (including the
     * case where the system setting only carries a city name and no usable coordinates), the manual
     * place — coordinates typed by hand, since there is no city lookup in this build — is the
     * fallback.
     */
    private fun resolvePlace(config: WeatherConfig): Place? {
        val ctx = appContext ?: return null
        val system = SystemLocation.read(ctx)

        if (system != null && system.hasCoordinates) {
            val label = system.label ?: config.manualPlace.orEmpty()
            return Place(label, system.latitude!!, system.longitude!!, LocationSource.SystemCoordinates)
        }

        val manualRaw = config.manualPlace
        val manual = manualRaw?.let { LocationParser.parse(it) }
        // Coordinates typed by hand need no lookup at all. Used whenever the system has no usable
        // coordinates, whether the setting is empty or only names a city.
        if (manual != null && manual.hasCoordinates) {
            return Place(manual.label ?: manualRaw, manual.latitude!!, manual.longitude!!, LocationSource.Manual)
        }

        return null
    }

    private fun readState(
        busy: Boolean,
        notice: WeatherNotice?,
        trigger: Trigger?,
        place: Place?,
    ): WeatherState {
        val config = config
        val enabled = config?.enabled ?: true
        val ready = config?.ready ?: false
        // A cached reading is only published while the feature is on and configured. Switch it off,
        // clear the key, or clear the host, and the snapshot disappears from the state at once, so no
        // UI and no status page can show a stale temperature.
        val snapshot = if (enabled && ready) store?.snapshot else null
        return WeatherState(
            ready = ready,
            enabled = enabled,
            hasKey = config?.hasKey ?: false,
            hostValid = config?.hasHost ?: false,
            snapshot = snapshot,
            place = place,
            busy = busy,
            notice = notice,
            trigger = trigger,
            monthCount = budget?.state()?.monthCount ?: 0,
        )
    }

    /**
     * The reply's own language, so the condition text matches the interface. The panel's wording
     * comes from resources; only `condition.text` is QWeather's.
     */
    private fun language(): String = if (Locale.getDefault().language == "zh") "zh" else "en"

    /** The foreground tick checks once a minute; the hourly gate turns that into at most one request. */
    private const val TICK_INTERVAL_MILLIS = 60_000L
}
