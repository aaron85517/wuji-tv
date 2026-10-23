// Plain JVM tests for the two pure pieces of the weather feature. They are compiled against the
// real production sources, not copies: run.sh compiles
//   app/src/main/java/app/orchard/tv/weather/WeatherBudget.kt
//   app/src/main/java/app/orchard/tv/weather/LocationParser.kt
//   app/src/main/java/app/orchard/tv/weather/WeatherModels.kt
// together with this file, using the Kotlin compiler already in the offline Gradle cache, and runs
// the resulting classes on the JDK. No JUnit: the offline cache has none, and this keeps the check
// runnable on any machine that can build the APK.

import app.orchard.tv.weather.BudgetChannel
import app.orchard.tv.weather.BudgetDecision
import app.orchard.tv.weather.BudgetState
import app.orchard.tv.weather.BudgetStore
import app.orchard.tv.weather.LocationParser
import app.orchard.tv.weather.Sky
import app.orchard.tv.weather.SkyMap
import app.orchard.tv.weather.WeatherBudget
import app.orchard.tv.weather.WeatherSnapshot
import app.orchard.tv.weather.WeatherState
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

private const val HOUR = 60L * 60L * 1000L

/** An in-memory store that survives "restarts": the same object outlives the WeatherBudget. */
private class MemoryStore(var state: BudgetState = BudgetState()) : BudgetStore {
    val writes = AtomicInteger(0)
    override fun load(): BudgetState = synchronized(this) { state }
    override fun save(state: BudgetState) {
        synchronized(this) { this.state = state }
        writes.incrementAndGet()
    }
}

private var failures = 0
private var checks = 0

private fun check(name: String, condition: Boolean, detail: () -> String = { "" }) {
    checks++
    if (condition) {
        println("  ok   $name")
    } else {
        failures++
        println("  FAIL $name ${detail()}")
    }
}

private fun zone(): ZoneId = ZoneId.of("Asia/Shanghai")

/** 2026-10-15 12:00 in Shanghai. */
private fun octoberNoon(): Long = java.time.ZonedDateTime.of(2026, 10, 15, 12, 0, 0, 0, zone()).toInstant().toEpochMilli()

private fun budget(
    store: MemoryStore,
    cap: Int = WeatherBudget.MONTHLY_CAP,
    now: () -> Long,
) = WeatherBudget(store, zone(), now, monthlyCap = cap)

private fun testCapBoundary() {
    println("\n[1] 49999 / 50000 monthly boundary")
    var now = octoberNoon()
    // Nine days of hourly attempts already spent 49,999 of the month's allowance.
    val store = MemoryStore(BudgetState(monthKey = "2026-10", monthCount = 49_999, weatherAttemptAt = now - 2 * HOUR))
    val b = budget(store) { now }

    val last = b.acquire(BudgetChannel.Weather)
    check("49,999 -> allowed", last is BudgetDecision.Allowed, { "got $last" })
    check("49,999 -> counter is 50,000", store.state.monthCount == 50_000, { "count=${store.state.monthCount}" })

    now += 2 * HOUR
    val over = b.acquire(BudgetChannel.Weather)
    check("50,000 -> refused", over is BudgetDecision.MonthlyCapReached, { "got $over" })
    check("refusal does not raise the counter", store.state.monthCount == 50_000, { "count=${store.state.monthCount}" })

    // Cached readings keep showing: the refusal says which month, so the UI can say so.
    val exhausted = MemoryStore(BudgetState(monthKey = "2026-10", monthCount = 50_000))
    val exhaustedBudget = budget(exhausted) { now }
    val straight = exhaustedBudget.acquire(BudgetChannel.Weather)
    check("starting at exactly 50,000 -> refused without a request", straight is BudgetDecision.MonthlyCapReached && exhausted.writes.get() == 0) {
        "got $straight, writes=${exhausted.writes.get()}"
    }
}

private fun testConcurrency() {
    println("\n[2] concurrency: one hour, many callers")
    val now = octoberNoon()
    val store = MemoryStore(BudgetState(monthKey = "2026-10", monthCount = 0, weatherAttemptAt = now - 2 * HOUR))
    val b = budget(store) { now }
    val threads = 16
    val allowed = AtomicInteger(0)
    val cooling = AtomicInteger(0)
    val start = CountDownLatch(1)
    val done = CountDownLatch(threads)
    repeat(threads) {
        Thread {
            start.await()
            when (b.acquire(BudgetChannel.Weather)) {
                is BudgetDecision.Allowed -> allowed.incrementAndGet()
                is BudgetDecision.CoolingDown -> cooling.incrementAndGet()
                is BudgetDecision.MonthlyCapReached -> {}
            }
            done.countDown()
        }.start()
    }
    start.countDown()
    done.await()
    check("exactly one of 16 concurrent callers is allowed", allowed.get() == 1, { "allowed=${allowed.get()}" })
    check("the other 15 are told to wait", cooling.get() == threads - 1, { "cooling=${cooling.get()}" })
    check("the counter moved by exactly one", store.state.monthCount == 1, { "count=${store.state.monthCount}" })

    // The same, at the cap boundary: no overshoot above the ceiling.
    val edgeStore = MemoryStore(BudgetState(monthKey = "2026-10", monthCount = 49_999, weatherAttemptAt = now - 2 * HOUR))
    val edge = budget(edgeStore) { now }
    val allowedEdge = AtomicInteger(0)
    val startEdge = CountDownLatch(1)
    val doneEdge = CountDownLatch(8)
    repeat(8) {
        Thread {
            startEdge.await()
            if (edge.acquire(BudgetChannel.Weather) is BudgetDecision.Allowed) allowedEdge.incrementAndGet()
            doneEdge.countDown()
        }.start()
    }
    startEdge.countDown()
    doneEdge.await()
    check("at 49,999 only one of 8 gets through", allowedEdge.get() == 1, { "allowed=${allowedEdge.get()}" })
    check("never above the cap", edgeStore.state.monthCount == 50_000, { "count=${edgeStore.state.monthCount}" })
}

private fun testRestart() {
    println("\n[3] restart persistence")
    var now = octoberNoon()
    val store = MemoryStore(BudgetState(monthKey = "2026-10"))
    val first = budget(store) { now }
    check("first attempt allowed", first.acquire(BudgetChannel.Weather) is BudgetDecision.Allowed)

    // A new process: new WeatherBudget, same on-disk state.
    val afterRestart = budget(store) { now + 30 * 60 * 1000L }
    val denied = afterRestart.acquire(BudgetChannel.Weather)
    check("30 min after a restart it is still cooling down", denied is BudgetDecision.CoolingDown, { "got $denied" })
    check("cooldown reports the rest of the hour", (denied as? BudgetDecision.CoolingDown)?.retryInMillis == 30 * 60 * 1000L) {
        "retryIn=${(denied as? BudgetDecision.CoolingDown)?.retryInMillis}"
    }

    now += HOUR
    check("a full hour after a restart is allowed", budget(store) { now }.acquire(BudgetChannel.Weather) is BudgetDecision.Allowed)

    // A failed attempt is written before the request, so a crash mid-request still holds the hour.
    val crashStore = MemoryStore(BudgetState(monthKey = "2026-10"))
    budget(crashStore) { now }.acquire(BudgetChannel.Weather)
    check("the count is on disk before any request could be sent", crashStore.writes.get() == 1 && crashStore.state.monthCount == 1) {
        "writes=${crashStore.writes.get()} count=${crashStore.state.monthCount}"
    }
}

private fun testFailureHoldsTheHour() {
    println("\n[4] a failed request still owns the hour")
    var now = octoberNoon()
    val store = MemoryStore(BudgetState(monthKey = "2026-10"))
    val b = budget(store) { now }

    val attempt = b.acquire(BudgetChannel.Weather) as BudgetDecision.Allowed
    // The request then fails (401, timeout, offline). Nothing refunds it.
    check("after a failure the count stands", attempt.state.monthCount == 1)
    val retryAfterOneSecond = budget(store) { now + 1000 }.acquire(BudgetChannel.Weather)
    check("an immediate retry is refused", retryAfterOneSecond is BudgetDecision.CoolingDown, { "got $retryAfterOneSecond" })

    // Wake, style switch and a tap all arrive inside the same hour.
    now += 20 * 60 * 1000L
    repeat(3) {
        check("another trigger within the hour refused", budget(store) { now }.acquire(BudgetChannel.Weather) is BudgetDecision.CoolingDown)
        now += 5 * 60 * 1000L
    }
    check("still only one attempt charged", store.state.monthCount == 1, { "count=${store.state.monthCount}" })
}

private fun testMonthRollover() {
    println("\n[5] month rollover")
    // Last attempt at 22:30 on the 31st, so 00:10 on the 1st is more than an hour later.
    val octoberEnd = java.time.ZonedDateTime.of(2026, 10, 31, 22, 30, 0, 0, zone()).toInstant().toEpochMilli()
    val store = MemoryStore(BudgetState(monthKey = "2026-10", monthCount = 50_000, weatherAttemptAt = octoberEnd))
    val november = java.time.ZonedDateTime.of(2026, 11, 1, 0, 10, 0, 0, zone()).toInstant().toEpochMilli()
    val decision = budget(store) { november }.acquire(BudgetChannel.Weather)
    check("a new month is allowed again", decision is BudgetDecision.Allowed, { "got $decision" })
    check("the count restarts at one", store.state.monthCount == 1, { "count=${store.state.monthCount}" })
    check("the state names the new month", store.state.monthKey == "2026-11", { "month=${store.state.monthKey}" })

    // The hour is not reset by the month: an attempt at 23:30 still blocks 00:10.
    val lateAttempt = java.time.ZonedDateTime.of(2026, 10, 31, 23, 30, 0, 0, zone()).toInstant().toEpochMilli()
    val lateStore = MemoryStore(BudgetState(monthKey = "2026-10", monthCount = 10, weatherAttemptAt = lateAttempt))
    val acrossMidnight = budget(lateStore) { november }.acquire(BudgetChannel.Weather)
    check("the hourly rule carries across the month boundary", acrossMidnight is BudgetDecision.CoolingDown, { "got $acrossMidnight" })

    // December: a 31-day month, and the largest number of hourly attempts a device can make.
    val dec = java.time.ZonedDateTime.of(2026, 12, 1, 0, 0, 0, 0, zone()).toInstant().toEpochMilli()
    val decemberStore = MemoryStore(BudgetState(monthKey = "2026-11", monthCount = 744, weatherAttemptAt = dec - HOUR))
    budget(decemberStore) { dec }.acquire(BudgetChannel.Weather)
    check("744 attempts in 31 days leaves the cap untouched", decemberStore.state.monthCount == 1 && decemberStore.state.monthKey == "2026-12")
}

private fun testClockRollback() {
    println("\n[6] clock moved backwards")
    val now = java.time.ZonedDateTime.of(2026, 10, 15, 12, 0, 0, 0, zone()).toInstant().toEpochMilli()
    val store = MemoryStore(BudgetState(monthKey = "2026-10", monthCount = 50_000, weatherAttemptAt = now))
    // The TV is set back two months. The spent month must not be wiped, so nothing is sent.
    val september = java.time.ZonedDateTime.of(2026, 8, 15, 12, 0, 0, 0, zone()).toInstant().toEpochMilli()
    val rolledBack = budget(store) { september }.acquire(BudgetChannel.Weather)
    check("a rollback does not reset a spent month", rolledBack is BudgetDecision.MonthlyCapReached, { "got $rolledBack" })
    check("the stored month is unchanged", store.state.monthKey == "2026-10", { "month=${store.state.monthKey}" })
    check("the stored count is unchanged", store.state.monthCount == 50_000, { "count=${store.state.monthCount}" })

    // A rollback inside the hour: elapsed is negative, which must read as "not yet".
    val freshStore = MemoryStore(BudgetState(monthKey = "2026-10", monthCount = 1, weatherAttemptAt = now))
    val earlier = now - 3 * HOUR
    val decision = budget(freshStore) { earlier }.acquire(BudgetChannel.Weather)
    check("a rollback inside the hour is still a cooldown", decision is BudgetDecision.CoolingDown, { "got $decision" })
    check("it cannot be used to buy an extra request", freshStore.state.monthCount == 1, { "count=${freshStore.state.monthCount}" })

    // And forward again: the original month must not be treated as new.
    val forward = budget(store) { now + 2 * HOUR }.acquire(BudgetChannel.Weather)
    check("moving the clock forward to the same month keeps the cap", forward is BudgetDecision.MonthlyCapReached, { "got $forward" })
}

private fun testBudgetConfigurability() {
    println("\n[7] the interval is the documented hour, not something shorter")
    val now = octoberNoon()
    val store = MemoryStore(BudgetState(monthKey = "2026-10"))
    val b = budget(store) { now }
    b.acquire(BudgetChannel.Weather)
    val justBefore = budget(store) { now + HOUR - 1 }.acquire(BudgetChannel.Weather)
    check("one millisecond short of an hour is still refused", justBefore is BudgetDecision.CoolingDown, { "got $justBefore" })
    val exactly = budget(store) { now + HOUR }.acquire(BudgetChannel.Weather)
    check("exactly one hour later is allowed", exactly is BudgetDecision.Allowed, { "got $exactly" })
    check("constants are the documented ones", WeatherBudget.WEATHER_INTERVAL_MILLIS == HOUR && WeatherBudget.MONTHLY_CAP == 50_000)
}

private fun testLocationParsing() {
    println("\n[8] location setting parsing")
    val json = LocationParser.parse("""{"province":"湖南省","city":"长沙市","district":"岳麓区"}""")
    check("JSON object with names", json?.city == "长沙市" && json.province == "湖南省" && json.district == "岳麓区" && !json.hasCoordinates) {
        "got $json"
    }
    val jsonCoords = LocationParser.parse("""{"province":"湖南省","city":"长沙市","latitude":28.19,"longitude":112.98}""")
    check("JSON object with coordinates", jsonCoords?.hasCoordinates == true && jsonCoords.latitude == 28.19 && jsonCoords.longitude == 112.98) {
        "got $jsonCoords"
    }
    val flat = LocationParser.parse("湖南省，长沙市，岳麓区")
    check("comma separated names", flat?.city == "长沙市" && flat.district == "岳麓区", { "got $flat" })
    val spaces = LocationParser.parse("广东省 深圳市 南山区")
    check("space separated names", spaces?.city == "深圳市", { "got $spaces" })
    check("bare city", LocationParser.parse("长沙")?.city == "长沙")
    val latLon = LocationParser.parse("28.19,112.98")
    check("latitude,longitude pair", latLon?.latitude == 28.19 && latLon.longitude == 112.98, { "got $latLon" })
    val lonLat = LocationParser.parse("112.98,28.19")
    check("longitude,latitude pair recognised by range", lonLat?.latitude == 28.19 && lonLat.longitude == 112.98) { "got $lonLat" }
    check("empty is null", LocationParser.parse("") == null)
    check("blank is null", LocationParser.parse("   ") == null)
    check("placeholder is null", LocationParser.parse("自动定位") == null)
    check("null is null", LocationParser.parse(null) == null)
    check("label prefers the city", flat?.label == "长沙市")
    check("province only still usable for a lookup", LocationParser.parse("湖南省")?.label == "湖南省")
}

private fun testSky() {
    println("\n[9] condition codes to glyphs")
    check("100 is clear", SkyMap.of(100) == Sky.Clear)
    check("101-103 are partly cloudy", SkyMap.of(103) == Sky.PartlyCloudy)
    check("104 is overcast", SkyMap.of(104) == Sky.Overcast)
    check("302 is thunder", SkyMap.of(302) == Sky.Thunder)
    check("305 is rain", SkyMap.of(305) == Sky.Rain)
    check("400 is snow", SkyMap.of(400) == Sky.Snow)
    check("501 is fog", SkyMap.of(501) == Sky.Fog)
    check("502 haze reads as fog", SkyMap.of(502) == Sky.Fog)
    check("unknown code has a fallback", SkyMap.of(999) == Sky.Unknown)
    check("19:00 is night", SkyMap.isNight(19))
    check("05:59 is night", SkyMap.isNight(5))
    check("06:00 is day", !SkyMap.isNight(6))
    check("18:59 is day", !SkyMap.isNight(18))
}

/**
 * The one rule that hides weather everywhere: it may be shown only when the switch is on, the host
 * and key are both set, and a reading actually arrived. A stale cached snapshot must not leak through
 * when the feature is turned off or the key is cleared — the `show` gate is what Home, the capsule
 * and Control Center all consult.
 */
private fun testShowGating() {
    println("\n[10] the weather is shown only when on, configured and fed")
    fun snapshot() = WeatherSnapshot(
        city = "长沙", text = "多云", code = 101, tempC = 16, feelsLikeC = 15,
        humidityPercent = 60, windCompass = "北风", windScale = 2,
        fetchedAt = System.currentTimeMillis(), latitude = 28.19, longitude = 112.98,
    )

    val live = WeatherState(enabled = true, ready = true, hasKey = true, hostValid = true, snapshot = snapshot())
    check("configured + reading -> shown", live.show, { "show=${live.show}" })

    val notConfigured = WeatherState(enabled = true, ready = false, hasKey = false, hostValid = false, snapshot = snapshot())
    check("no host/key, cached reading still present -> hidden", !notConfigured.show, { "show=${notConfigured.show}" })

    val switchedOff = WeatherState(enabled = false, ready = true, hasKey = true, hostValid = true, snapshot = snapshot())
    check("switch off, cached reading still present -> hidden", !switchedOff.show, { "show=${switchedOff.show}" })

    val keyCleared = WeatherState(enabled = true, ready = false, hasKey = false, hostValid = true, snapshot = snapshot())
    check("key cleared, cached reading still present -> hidden", !keyCleared.show, { "show=${keyCleared.show}" })

    val noReading = WeatherState(enabled = true, ready = true, hasKey = true, hostValid = true, snapshot = null)
    check("configured but no reading yet -> hidden", !noReading.show, { "show=${noReading.show}" })

    val bothOffAndUnfed = WeatherState(enabled = false, ready = false, hasKey = false, hostValid = false, snapshot = snapshot())
    check("off and unconfigured with a stale snapshot -> hidden", !bothOffAndUnfed.show, { "show=${bothOffAndUnfed.show}" })
}

fun main() {
    println("weather-budget tests — ${java.time.ZonedDateTime.now(zone())}")
    testCapBoundary()
    testConcurrency()
    testRestart()
    testFailureHoldsTheHour()
    testMonthRollover()
    testClockRollback()
    testBudgetConfigurability()
    testLocationParsing()
    testSky()
    testShowGating()
    println("\n$checks checks, $failures failed")
    if (failures > 0) System.exit(1)
}
