package app.orchard.tv.ui.weather

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.orchard.tv.R
import app.orchard.tv.ui.components.Pressable
import app.orchard.tv.ui.theme.InterDisplay
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type
import app.orchard.tv.weather.FailureKind
import app.orchard.tv.weather.Sky
import app.orchard.tv.weather.SkyMap
import app.orchard.tv.weather.Trigger
import app.orchard.tv.weather.WeatherNotice
import app.orchard.tv.weather.WeatherState
import app.orchard.tv.weather.Weather
import app.orchard.tv.wallpaper.PhoneServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The degree sign, kept separate so the number stays a number. */
private const val DEGREE = "\u00B0"

/** The sky this state would draw, with night decided from the clock (v1 sends no day/night flag). */
@Composable
private fun skyOf(state: WeatherState): Pair<Sky, Boolean> {
    val night = SkyMap.isNight(LocalDateTime.now().hour)
    val sky = state.snapshot?.let { SkyMap.of(it.code) } ?: Sky.Unknown
    return sky to night
}

/**
 * Icon plus number, the whole of what Home shows. No card, no plate, no condition text: the clock is
 * the loudest thing in the corner and the weather is a second line of information next to it.
 *
 * With no reading there is no number to show, and none is invented: the mark alone, dimmed. The
 * panel is where "not set up yet" is spelled out.
 */
@Composable
fun WeatherMark(
    state: WeatherState,
    color: Color,
    iconSize: Dp,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    val (sky, night) = skyOf(state)
    val snapshot = state.snapshot
    val faded = snapshot == null || state.stale
    Row(
        modifier.alpha(if (faded) 0.62f else 1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        WeatherIcon(sky, night, color, iconSize)
        if (snapshot != null) {
            BasicText("${snapshot.tempC}$DEGREE", style = textStyle.copy(color = color))
        }
    }
}

/**
 * Icon Home: to the right of the big clock, on its baseline. Focusing it with the remote and
 * confirming opens the TV's own Xiaomi weather app; it never moves the clock or the grid, and it
 * takes no space the layout had not already left empty.
 */
@Composable
fun ShelfWeather(
    state: WeatherState,
    enabled: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    onFocused: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Pressable(
        onClick = onClick,
        enabled = enabled,
        focusRequester = focusRequester,
        focusedScale = 1f,
        modifier = modifier,
    ) { press ->
        LaunchedEffect(press.focused) { onFocused(press.focused) }
        val bg by animateColorAsState(
            if (press.focused) Color.White.copy(alpha = 0.14f) else Color.Transparent,
            tween(160, easing = Motion.EaseOut),
            label = "shelf-weather-bg",
        )
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(bg)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            WeatherMark(
                state = state,
                color = Color.White.copy(alpha = 0.88f),
                iconSize = 30.dp,
                textStyle = TextStyle(
                    fontFamily = InterDisplay,
                    fontWeight = FontWeight.W300,
                    fontSize = 30.sp,
                    letterSpacing = (-0.02).em,
                    color = Color.White,
                ),
            )
        }
    }
}

/**
 * Large-type Home: the weather sits inside the top-right capsule, immediately after the time, as a
 * display-only mark (the capsule as a whole opens Control Center). Kept small: 16dp mark and the
 * capsule's own type.
 */
@Composable
fun CapsuleWeatherContent(
    state: WeatherState,
    color: Color,
    iconSize: Dp = 16.dp,
    style: TextStyle = Type.Headline.copy(fontSize = 15.sp, fontWeight = FontWeight.W500),
) {
    WeatherMark(state, color, iconSize, style)
}

/** One label/value line of the panel. Values wrap rather than clip; this is the reading surface. */
@Composable
private fun DetailRow(label: String, value: String, valueColor: Color = Palette.Label) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        BasicText(
            label,
            style = Type.Callout.copy(color = Palette.SecondaryLabel),
            modifier = Modifier.width(120.dp),
        )
        BasicText(value, style = Type.Body.copy(color = valueColor), modifier = Modifier.weight(1f))
    }
}

/**
 * The weather settings panel: the one place that says where the reading came from, when it was
 * taken, and why there is no newer one. It is opened from Control Center's "Weather settings" row,
 * and is the way into setup — the QR code is the same token-protected phone page the wallpaper
 * panel uses, which is where the API Host and key are typed.
 */
@Composable
fun WeatherPanel(visible: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state = Weather.state
    val first = remember { FocusRequester() }
    var phoneUrl by remember { mutableStateOf<String?>(null) }
    var qr by remember { mutableStateOf<ImageBitmap?>(null) }

    BackHandler(enabled = visible) { onDismiss() }

    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }

    // Only while the panel is up, and only while there is nothing configured to show instead.
    LaunchedEffect(visible, state.ready) {
        if (!visible || state.ready) {
            phoneUrl = null; qr = null
            return@LaunchedEffect
        }
        val url = withContext(Dispatchers.IO) { PhoneServer.start(context) }
        phoneUrl = url
        qr = url?.let { withContext(Dispatchers.Default) { PhoneServer.qr(it, scale = 5).asImageBitmap() } }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)) + scaleIn(tween(300, easing = Motion.Drawer), initialScale = 0.94f),
        exit = fadeOut(tween(150)) + scaleOut(tween(150, easing = Motion.EaseOut), targetScale = 0.97f),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .width(600.dp)
                    .clip(RoundedCornerShape(28.dp))
                    // Solid, not translucent: nothing is blurred behind this panel, so ghosted
                    // clock digits would be the only thing showing through.
                    .background(Palette.GlassSolid)
                    .border(1.dp, Palette.Hairline, RoundedCornerShape(28.dp))
                    .padding(28.dp),
            ) {
                PanelBody(state, qr, phoneUrl)

                Spacer(Modifier.height(18.dp))
                Pressable(onClick = onDismiss, focusRequester = first, focusedScale = 1.04f) { press ->
                    val bg by animateColorAsState(
                        if (press.focused) Palette.Focus else Palette.FillStrong,
                        tween(140, easing = Motion.EaseOut), label = "weather-close",
                    )
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(bg).height(38.dp).padding(horizontal = 22.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicText(
                            stringResource(R.string.weather_close),
                            style = Type.Body.copy(color = if (press.focused) Palette.OnFocus else Palette.Label),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelBody(state: WeatherState, qr: ImageBitmap?, phoneUrl: String?) {
    val (sky, night) = skyOf(state)
    val snapshot = state.snapshot

    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText(stringResource(R.string.weather_title), style = Type.LargeTitle, modifier = Modifier.weight(1f))
        val status = if (state.ready) R.string.weather_status_ready else R.string.weather_status_pending
        val dot = if (state.ready) Palette.Green else Palette.Orange
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(dot))
            BasicText(stringResource(status), style = Type.Caption.copy(color = Palette.SecondaryLabel))
        }
    }

    Spacer(Modifier.height(14.dp))
    Row(verticalAlignment = Alignment.Bottom) {
        WeatherIcon(sky, night, Palette.Label, 46.dp)
        Spacer(Modifier.width(14.dp))
        Column {
            if (snapshot != null) {
                Row(verticalAlignment = Alignment.Bottom) {
                    BasicText(
                        "${snapshot.tempC}$DEGREE",
                        style = TextStyle(
                            fontFamily = InterDisplay, fontWeight = FontWeight.W300,
                            fontSize = 46.sp, letterSpacing = (-0.03).em, color = Palette.Label,
                        ),
                    )
                    Spacer(Modifier.width(12.dp))
                    BasicText(
                        snapshot.text.ifBlank { state.place?.label.orEmpty() },
                        style = Type.Title.copy(color = Palette.SecondaryLabel),
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                val extras = buildList {
                    snapshot.feelsLikeC?.let { add(stringResource(R.string.weather_feels, it)) }
                    snapshot.humidityPercent?.let { add(stringResource(R.string.weather_humidity, it)) }
                    snapshot.windScale?.let { add(stringResource(R.string.weather_wind, it)) }
                }
                if (extras.isNotEmpty()) {
                    BasicText(
                        extras.joinToString(" · "),
                        style = Type.Caption,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            } else {
                BasicText(
                    stringResource(R.string.weather_status_pending),
                    style = Type.Title.copy(color = Palette.SecondaryLabel),
                )
            }
        }
    }

    Spacer(Modifier.height(14.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Hairline))
    Spacer(Modifier.height(6.dp))

    DetailRow(stringResource(R.string.weather_city), cityLine(state))
    if (snapshot != null) {
        DetailRow(
            stringResource(R.string.weather_updated),
            updatedLine(snapshot.fetchedAt),
            valueColor = if (state.stale) Palette.Orange else Palette.Label,
        )
    }
    DetailRow(stringResource(R.string.weather_source), stringResource(R.string.weather_source_value))
    DetailRow(stringResource(R.string.weather_requests), stringResource(R.string.weather_requests_value, state.monthCount))

    val notice = noticeText(state)
    if (notice != null) {
        Spacer(Modifier.height(4.dp))
        BasicText(
            notice,
            style = Type.Callout.copy(color = if (state.notice is WeatherNotice.Quota || state.notice is WeatherNotice.Failed) Palette.Orange else Palette.SecondaryLabel),
        )
    }
    if (state.stale) {
        Spacer(Modifier.height(6.dp))
        BasicText(
            stringResource(R.string.weather_stale_hint, ageText(state)),
            style = Type.Caption.copy(color = Palette.Orange),
        )
    }
    if (state.ready && state.trigger != null) {
        Spacer(Modifier.height(4.dp))
        BasicText(
            stringResource(R.string.weather_last_try, triggerText(state.trigger)),
            style = Type.Caption.copy(color = Palette.TertiaryLabel),
        )
    }

    Spacer(Modifier.height(10.dp))
    BasicText(stringResource(R.string.weather_attribution), style = Type.Caption.copy(color = Palette.TertiaryLabel))

    if (!state.ready) {
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(132.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(10.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (qr != null) {
                    Image(qr, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                } else {
                    BasicText(
                        stringResource(R.string.weather_phone_offline),
                        style = Type.Caption.copy(color = Color.Black.copy(alpha = 0.5f)),
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                BasicText(stringResource(R.string.weather_phone_title), style = Type.Headline)
                Spacer(Modifier.height(4.dp))
                BasicText(stringResource(R.string.weather_phone_body), style = Type.Caption)
                if (phoneUrl != null) {
                    Spacer(Modifier.height(4.dp))
                    BasicText(
                        phoneUrl.removePrefix("http://"),
                        style = Type.Caption.copy(color = Palette.TertiaryLabel),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun cityLine(state: WeatherState): String {
    val place = state.place
    if (place == null) return stringResource(R.string.weather_no_location)
    val source = when (place.source) {
        app.orchard.tv.weather.LocationSource.Manual -> stringResource(R.string.weather_place_manual)
        app.orchard.tv.weather.LocationSource.SystemCoordinates -> stringResource(R.string.weather_place_system)
    }
    return if (place.label.isBlank()) source else "${place.label} · $source"
}

/** `18:13 · 3 分钟前`, so the panel shows both the moment and the distance from now. */
@Composable
private fun updatedLine(fetchedAt: Long): String {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val time = Instant.ofEpochMilli(fetchedAt).atZone(zone).toLocalDateTime()
    val pattern = if (DateFormat.is24HourFormat(context)) "H:mm" else "h:mm"
    val clock = time.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
    val minutes = ((System.currentTimeMillis() - fetchedAt) / 60_000L).coerceAtLeast(0)
    val ago = when {
        minutes < 1 -> stringResource(R.string.weather_updated_just_now)
        minutes < 60 -> stringResource(R.string.weather_updated_minutes, minutes.toInt())
        else -> stringResource(R.string.weather_updated_hours, (minutes / 60).toInt())
    }
    return "$clock · $ago"
}

@Composable
private fun ageText(state: WeatherState): String = when {
    state.ageMinutes < 60 -> stringResource(R.string.weather_updated_minutes, state.ageMinutes.toInt())
    else -> stringResource(R.string.weather_updated_hours, (state.ageMinutes / 60).toInt())
}

@Composable
private fun triggerText(trigger: Trigger): String = stringResource(
    when (trigger) {
        Trigger.Launch -> R.string.weather_trigger_launch
        Trigger.Wake -> R.string.weather_trigger_wake
        Trigger.StyleSwitch -> R.string.weather_trigger_style
        Trigger.Network -> R.string.weather_trigger_network
        Trigger.Manual -> R.string.weather_trigger_manual
        Trigger.Setup -> R.string.weather_trigger_setup
        Trigger.Tick -> R.string.weather_trigger_tick
    },
)

/**
 * One line for whatever stopped a fresh reading. Silence is not an option: every refusal here has a
 * reason the user can act on, and it is never dressed up as a weather state.
 */
@Composable
private fun noticeText(state: WeatherState): String? = when (val notice = state.notice) {
    null -> null
    WeatherNotice.NotConfigured -> stringResource(R.string.weather_not_configured)
    WeatherNotice.Disabled -> stringResource(R.string.weather_disabled)
    WeatherNotice.NoLocation -> stringResource(R.string.weather_no_location)
    is WeatherNotice.CoolingDown -> stringResource(R.string.weather_cooldown, notice.minutes)
    is WeatherNotice.Quota -> stringResource(R.string.weather_quota, notice.monthKey)
    is WeatherNotice.Failed -> when (notice.kind) {
        FailureKind.Unauthorized -> stringResource(R.string.weather_failed_auth)
        FailureKind.Forbidden, FailureKind.BadHost -> stringResource(R.string.weather_failed_forbidden)
        FailureKind.RateLimited -> stringResource(R.string.weather_failed_rate)
        FailureKind.Server -> stringResource(R.string.weather_failed_server)
        FailureKind.Parse -> stringResource(R.string.weather_failed_parse)
        else -> stringResource(R.string.weather_failed_network)
    }
}
