package app.orchard.tv.ui.home

import android.os.Build
import android.view.KeyEvent as NativeKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.orchard.tv.HomeViewModel
import app.orchard.tv.Overlay
import app.orchard.tv.R
import app.orchard.tv.apps.AppEntry
import app.orchard.tv.system.NetKind
import app.orchard.tv.system.NetworkStatus
import app.orchard.tv.ui.components.Pressable
import app.orchard.tv.ui.components.isSelect
import app.orchard.tv.ui.theme.Glyph
import app.orchard.tv.ui.theme.GlyphIcon
import app.orchard.tv.ui.theme.InterText
import app.orchard.tv.ui.theme.LocalReducedMotion
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type
import app.orchard.tv.ui.weather.CapsuleWeatherContent
import app.orchard.tv.weather.Weather
import app.orchard.tv.wallpaper.Wallpapers
import java.time.LocalDateTime
import kotlin.math.roundToInt

/** How many apps the large-type list shows before "All Apps". The rest are one step away. */
private const val LIST_APPS = 5
private const val GRID_COLUMNS = 5

/** Key of the fixed last row, so focus can be put back on it after the grid closes. */
private const val ALL_APPS_KEY = "all-apps"

private val ListTop = 96.dp
private val ListWidth = 560.dp
private val RowHeight = 52.dp
private val AllAppsHeight = 44.dp

private val LargeLabel = TextStyle(
    fontFamily = InterText, fontWeight = FontWeight.W400,
    fontSize = 34.sp, lineHeight = 42.sp, letterSpacing = (-0.02).em, color = Color.White,
)

private val AllAppsLabel = TextStyle(
    fontFamily = InterText, fontWeight = FontWeight.W500,
    fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.01).em, color = Color.White,
)

/**
 * The large-type Home: the first five visible apps in a list down the left, with "All Apps" fixed
 * at the end, and the time with the quick-settings entry in a thin capsule at the top right.
 * Focus only brightens, grows and bolds the text; there is no plate, rim or shadow behind it, and
 * nothing moves while the remote is still.
 */
@Composable
fun LargeHome(
    vm: HomeViewModel,
    now: LocalDateTime,
    onLaunch: (AppEntry, Rect?) -> Unit,
    onStartScreensaver: () -> Unit,
) {
    val apps = vm.apps.take(LIST_APPS)
    val overlay = vm.overlay
    val gridOpen = vm.allAppsOpen

    val requesters = remember { HashMap<String, FocusRequester>() }
    fun requester(key: String) = requesters.getOrPut(key) { FocusRequester() }
    val capsuleRequester = remember { FocusRequester() }
    val rows = remember(apps) { apps.map { it.key } + ALL_APPS_KEY }
    val firstRow = rows.first()

    var focusedRow by remember { mutableStateOf<String?>(null) }
    var capsuleFocused by remember { mutableStateOf(false) }

    // In "Follow App" mode the light comes from the row under focus. Before the first focus lands,
    // or while the row is "All Apps" / a vanished app, it falls back to the first of the five, so
    // the backdrop is never null (black) as long as there is an app to light it.
    val focusBackdrop = (apps.firstOrNull { it.key == focusedRow } ?: apps.firstOrNull())?.art?.backdrop

    fun focusRow(key: String?) {
        val target = key?.takeIf { it in rows } ?: firstRow
        runCatching { requester(target).requestFocus() }
    }

    // First focus once the apps are in; afterwards put focus back where it was when a panel closes.
    LaunchedEffect(vm.loaded, overlay, gridOpen) {
        if (!vm.loaded || overlay != Overlay.None || gridOpen) return@LaunchedEffect
        withFrameNanos { }
        if (capsuleFocused) runCatching { capsuleRequester.requestFocus() } else focusRow(focusedRow)
    }
    // An app vanished from under the focus (uninstalled or hidden): take the first row.
    LaunchedEffect(apps) {
        if (!gridOpen && overlay == Overlay.None && focusedRow != null && focusedRow !in rows) {
            withFrameNanos { }
            focusRow(null)
        }
    }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(windowFocused, vm.homeResetPending, vm.loaded) {
        if (windowFocused && vm.homeResetPending && vm.loaded) {
            withFrameNanos { }
            capsuleFocused = false
            focusRow(null)
            vm.homeResetPending = false
        }
    }

    // tvOS: Back on Home starts the screensaver, as on the icon grid.
    BackHandler(enabled = overlay == Overlay.None && !gridOpen) { onStartScreensaver() }

    Box(Modifier.fillMaxSize()) {
        val backdropBlur by animateDpAsState(
            if (overlay is Overlay.ControlCenter || overlay is Overlay.HiddenApps) 30.dp else 0.dp,
            tween(320, easing = Motion.EaseOut), label = "large-blur",
        )
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val r = backdropBlur.toPx()
                    renderEffect = if (r > 0.5f && Build.VERSION.SDK_INT >= 31) BlurEffect(r, r, TileMode.Clamp) else null
                },
        ) {
            AmbientBackground(
                // The chosen wallpaper; in "Follow App" mode, light from the app under focus.
                backdrop = Wallpapers.backdrop ?: focusBackdrop,
                dim = { 0f },
                leftShade = true,
            )

            Column(
                Modifier
                    .padding(start = 56.dp, top = ListTop)
                    .width(ListWidth)
                    // The grid draws its own full-screen scrim; the list stays composed (its rows
                    // keep their focus nodes and disabled state) but draws nothing, so no label
                    // shows through behind the tiles. The wallpaper above is untouched.
                    .graphicsLayer { alpha = if (gridOpen) 0f else 1f },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                apps.forEachIndexed { index, app ->
                    key(app.key) {
                        LargeRow(
                            label = app.label,
                            style = LargeLabel,
                            height = RowHeight,
                            focusRequester = requester(app.key),
                            enabled = overlay == Overlay.None && !gridOpen && vm.movingKey == null,
                            onClick = { bounds -> onLaunch(app, bounds) },
                            onFocused = { focusedRow = app.key; capsuleFocused = false },
                            onRight = { runCatching { capsuleRequester.requestFocus() } },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                LargeRow(
                    label = stringResource(R.string.all_apps),
                    style = AllAppsLabel,
                    height = AllAppsHeight,
                    focusRequester = requester(ALL_APPS_KEY),
                    enabled = overlay == Overlay.None && !gridOpen && vm.movingKey == null,
                    onClick = {
                        if (vm.apps.isNotEmpty()) {
                            focusedRow = ALL_APPS_KEY
                            vm.allAppsOpen = true
                        }
                    },
                    onFocused = { focusedRow = ALL_APPS_KEY; capsuleFocused = false },
                    onRight = { runCatching { capsuleRequester.requestFocus() } },
                )
            }
        }

        LargeStatusCapsule(
            now = now,
            network = vm.network,
            enabled = overlay == Overlay.None && !gridOpen && vm.movingKey == null,
            focusRequester = capsuleRequester,
            onFocused = { gained ->
                if (gained) {
                    // The window hands its first focus to the top-most control; the list is Home.
                    if (focusedRow == null) focusRow(null) else capsuleFocused = true
                } else {
                    capsuleFocused = false
                }
            },
            onExit = {
                capsuleFocused = false
                focusRow(focusedRow)
            },
            onClick = { vm.overlay = Overlay.ControlCenter() },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 28.dp, end = 56.dp)
                .graphicsLayer {
                    alpha = if (overlay is Overlay.ControlCenter || gridOpen) 0f else 1f
                },
        )

        if (gridOpen) {
            AllAppsOverlay(
                vm = vm,
                onLaunch = onLaunch,
                onClose = {
                    focusedRow = ALL_APPS_KEY
                    vm.allAppsOpen = false
                },
            )
        }
    }
}

/**
 * One row of the large-type list. Text only: focus lifts the type to 115%, bolds it and takes it
 * from 52% white to white, growing from its left edge so it stays aligned with its neighbours.
 */
@Composable
private fun LargeRow(
    label: String,
    style: TextStyle,
    height: Dp,
    focusRequester: FocusRequester,
    enabled: Boolean,
    onClick: (Rect?) -> Unit,
    onFocused: () -> Unit,
    onRight: () -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    var bounds by remember { mutableStateOf<Rect?>(null) }

    val scale by animateFloatAsState(
        targetValue = if (focused && !reduced) 1.15f else 1f,
        // A spring, so a second press retargets mid-flight instead of restarting.
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 520f),
        label = "large-row-scale",
    )
    val color by animateColorAsState(
        if (focused) Color.White else Color.White.copy(alpha = 0.52f),
        tween(160, easing = Motion.EaseOut), label = "large-row-color",
    )

    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .onGloballyPositioned { bounds = it.boundsInWindow() }
            .focusRequester(focusRequester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused() else pressed = false
            }
            .onKeyEvent { e ->
                val code = e.nativeKeyEvent.keyCode
                val down = e.type == KeyEventType.KeyDown
                when (code) {
                    // The list is the left edge of Home; Right is the only way out, to the capsule.
                    NativeKeyEvent.KEYCODE_DPAD_LEFT -> true
                    NativeKeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (down) onRight()
                        true
                    }
                    NativeKeyEvent.KEYCODE_DPAD_UP -> {
                        if (down) focusManager.moveFocus(FocusDirection.Up)
                        true
                    }
                    NativeKeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (down) focusManager.moveFocus(FocusDirection.Down)
                        true
                    }
                    else -> {
                        if (!e.isSelect()) return@onKeyEvent false
                        when (e.type) {
                            KeyEventType.KeyDown -> {
                                if (e.nativeKeyEvent.repeatCount == 0) pressed = true
                                true
                            }
                            KeyEventType.KeyUp -> {
                                val was = pressed
                                pressed = false
                                if (was) onClick(bounds)
                                true
                            }
                            else -> false
                        }
                    }
                }
            }
            .focusable(enabled),
    ) {
        BasicText(
            text = label,
            style = style.copy(
                color = color,
                // Weight is not animatable; the type simply settles a step heavier when focused.
                fontWeight = if (focused) FontWeight.W700 else style.fontWeight,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    // Grow from the left edge, so the list keeps one straight margin.
                    transformOrigin = TransformOrigin(0f, 0.5f)
                },
        )
    }
}

/**
 * Top right of the large-type Home: the time with the weather mark, then the network state and the
 * Control Center entry, all in one thin capsule that opens Control Center as a whole. The weather
 * mark is display only here — it is not its own focus stop — because the weather entry lives as a
 * row inside Control Center (and as a focused mark on the icon Home).
 */
@Composable
private fun LargeStatusCapsule(
    now: LocalDateTime,
    network: NetworkStatus,
    enabled: Boolean,
    focusRequester: FocusRequester,
    onFocused: (Boolean) -> Unit,
    onExit: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Pressable(
        onClick = onClick,
        enabled = enabled,
        focusRequester = focusRequester,
        focusedScale = 1.04f,
        modifier = modifier,
        onKey = { e ->
            val code = e.nativeKeyEvent.keyCode
            val down = e.type == KeyEventType.KeyDown
            if (code == NativeKeyEvent.KEYCODE_DPAD_LEFT || code == NativeKeyEvent.KEYCODE_DPAD_DOWN || code == NativeKeyEvent.KEYCODE_BACK) {
                if (down) onExit()
                true
            } else {
                false
            }
        },
    ) { state ->
        LaunchedEffect(state.focused) { onFocused(state.focused) }
        val bg by animateColorAsState(
            if (state.focused) Palette.PillFocused else Palette.Pill,
            tween(160, easing = Motion.EaseOut), label = "capsule-bg",
        )
        val fg = if (state.focused) Color.White else Color.White.copy(alpha = 0.9f)
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(bg)
                .height(38.dp)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BasicText(
                timeText(now),
                style = Type.Headline.copy(fontSize = 17.sp, fontWeight = FontWeight.W500, color = fg),
            )
            // Display only: the mark and degrees, no separate focus, no click of its own. Hidden
            // entirely until the weather is configured and has a reading.
            if (Weather.state.show) {
                CapsuleWeatherContent(
                    state = Weather.state,
                    color = fg,
                    iconSize = 16.dp,
                    style = Type.Headline.copy(fontSize = 15.sp, fontWeight = FontWeight.W500, color = fg),
                )
            }
            GlyphIcon(
                when (network.kind) {
                    NetKind.Wifi -> Glyph.Wifi
                    NetKind.Ethernet -> Glyph.Ethernet
                    NetKind.Offline -> Glyph.Offline
                },
                fg, 16.dp, level = network.level,
            )
            GlyphIcon(Glyph.Switches, fg, 16.dp)
        }
    }
}


/**
 * Every visible app as a grid of the same tiles the icon Home uses, reached from "All Apps".
 * Long press opens the same menu; Sort moves a tile with the arrows and saves each step, so the
 * list's first five and the icon grid follow at once. Back ends sorting first, then goes Home.
 */
@Composable
private fun AllAppsOverlay(
    vm: HomeViewModel,
    onLaunch: (AppEntry, Rect?) -> Unit,
    onClose: () -> Unit,
) {
    val apps = vm.apps
    val overlay = vm.overlay
    val moving = vm.movingKey
    val reduced = LocalReducedMotion.current
    val density = LocalDensity.current

    val requesters = remember { HashMap<String, FocusRequester>() }
    fun requester(key: String) = requesters.getOrPut(key) { FocusRequester() }
    var focusedKey by remember { mutableStateOf<String?>(null) }
    var lastIndex by remember { mutableIntStateOf(0) }

    val focusedIndex = apps.indexOfFirst { it.key == focusedKey }
    val focusedRow = if (focusedIndex < 0) 0 else focusedIndex / GRID_COLUMNS

    BackHandler(enabled = true) {
        // Sorting owns Back while a tile is being moved; the tile itself ends the move.
        if (vm.movingKey != null) vm.endMove() else onClose()
    }

    // Nothing left to show (everything hidden or uninstalled): go back to the list.
    LaunchedEffect(apps.isEmpty()) { if (apps.isEmpty()) onClose() }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        apps.getOrNull(0)?.let { runCatching { requester(it.key).requestFocus() } }
    }
    // Focus has to come back when the long-press menu closes, and follow a tile being sorted.
    LaunchedEffect(overlay, moving) {
        if (overlay != Overlay.None) return@LaunchedEffect
        withFrameNanos { }
        // A remembered key can be gone by now (hidden or uninstalled), and asking for a tile that no
        // longer exists would leave the grid without focus: only take a key that is still present.
        val fallbackIndex = lastIndex.coerceIn(0, (apps.size - 1).coerceAtLeast(0))
        val key = listOfNotNull(moving, focusedKey).firstOrNull { k -> apps.any { it.key == k } }
            ?: apps.getOrNull(fallbackIndex)?.key
            ?: return@LaunchedEffect
        runCatching { requester(key).requestFocus() }
    }
    LaunchedEffect(apps) {
        if (focusedKey != null && apps.none { it.key == focusedKey }) {
            withFrameNanos { }
            val index = lastIndex.coerceIn(0, (apps.size - 1).coerceAtLeast(0))
            apps.getOrNull(index)?.let { runCatching { requester(it.key).requestFocus() } }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val metrics = remember(maxWidth, maxHeight) { AllAppsGridMetrics(maxWidth, maxHeight) }
        val rows = (apps.size + GRID_COLUMNS - 1) / GRID_COLUMNS
        val scroll = remember { Animatable(0f) }
        val target = with(density) { metrics.scrollFor(focusedRow, rows).toPx() }
        LaunchedEffect(target) {
            if (reduced) scroll.snapTo(target)
            // A spring keeps its velocity when a quick second press retargets it mid-flight.
            else scroll.animateTo(target, spring(dampingRatio = 1f, stiffness = 170f))
        }

        // A flat scrim: no blur here, the panel is read, not looked through.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.62f)))

        Box(Modifier.fillMaxSize().offset { IntOffset(0, -scroll.value.roundToInt()) }) {
            BasicText(
                stringResource(R.string.all_apps),
                style = Type.Title,
                modifier = Modifier.padding(start = 56.dp, top = 34.dp),
            )
            apps.forEachIndexed { index, app ->
                key(app.key) {
                    val (x, y) = metrics.slot(index)
                    val slotPx = with(density) { IntOffset(x.roundToPx(), y.roundToPx()) }
                    val slot = animateIntOffsetAsState(
                        slotPx,
                        spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow),
                        label = "all-grid-slot",
                    )
                    AppTile(
                        app = app,
                        width = metrics.tileW,
                        height = metrics.tileH,
                        focusRequester = requester(app.key),
                        enabled = overlay == Overlay.None,
                        deemphasized = overlay == Overlay.None && focusedKey != null && focusedKey != app.key,
                        moving = moving == app.key,
                        columns = GRID_COLUMNS,
                        appearDelayMs = 0,
                        onFocused = {
                            focusedKey = app.key
                            lastIndex = index
                        },
                        onClick = { bounds -> onLaunch(app, bounds) },
                        onLongPress = { bounds -> vm.overlay = Overlay.AppMenu(app.key, bounds ?: Rect.Zero) },
                        onMove = vm::moveBy,
                        onMoveDone = vm::endMove,
                        modifier = Modifier.offset { slot.value },
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = moving != null,
            enter = fadeIn(tween(200)) + slideInVertically(tween(260, easing = Motion.EaseOut)) { it / 2 },
            exit = fadeOut(tween(150)) + slideOutVertically(tween(150, easing = Motion.EaseOut)) { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
        ) {
            BasicText(
                stringResource(R.string.move_hint),
                style = Type.Body,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Palette.Glass)
                    .height(40.dp)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
    }
}

/**
 * Grid geometry for the full app page: same tiles, margins and gaps as the icon Home, but the
 * first row starts under the page title so a whole screen is usable. Same 960×540 canvas.
 */
private class AllAppsGridMetrics(screenW: Dp, screenH: Dp) {
    val margin = 56.dp
    val gap = 28.dp
    val tileW = (screenW - margin * 2 - gap * (GRID_COLUMNS - 1)) / GRID_COLUMNS
    val tileH = tileW * 9f / 16f
    val pitchX = tileW + gap
    val pitchY = tileH + 46.dp

    /** Clear of the title; a focused row settles here once the page scrolls. */
    val topRowY = 100.dp
    private val screenH = screenH

    fun slot(index: Int) = Pair(margin + pitchX * (index % GRID_COLUMNS), topRowY + pitchY * (index / GRID_COLUMNS))

    fun scrollFor(row: Int, rows: Int): Dp {
        if (row <= 0) return 0.dp
        val wanted = pitchY * row
        val contentBottom = topRowY + pitchY * (rows - 1) + tileH + 28.dp
        val maxScroll = maxOf(contentBottom - screenH, 0.dp)
        return minOf(wanted, maxScroll)
    }
}
