package app.orchard.tv

import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.orchard.tv.apps.AppEntry
import app.orchard.tv.guard.GuardSetup
import app.orchard.tv.guard.HomeGuardService
import app.orchard.tv.ui.home.HomeScreen
import app.orchard.tv.ui.theme.LocalReducedMotion
import app.orchard.tv.weather.Trigger
import app.orchard.tv.weather.Weather
import app.orchard.tv.wallpaper.Wallpapers

class MainActivity : ComponentActivity() {

    private val vm by viewModels<HomeViewModel>()

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = vm.reload()
    }

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = vm.refreshSystem()
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = runOnUiThread {
            vm.refreshNetwork()
            // Worth another look only if the hour's attempt has not been spent already; the gate
            // decides, this call cannot bypass it.
            Weather.refresh(Trigger.Network)
        }
        override fun onLost(network: Network) = runOnUiThread { vm.refreshNetwork() }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = runOnUiThread { vm.refreshNetwork() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        Wallpapers.init(this)
        // Reads the saved settings and the last reading; makes no request by itself.
        Weather.init(this)
        val reducedMotion = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

        // The home screen paints every pixel itself; a window background would be one more
        // full-screen fill per frame for the TV's GPU.
        window.setBackgroundDrawable(null)

        setContent {
            // TVs ship at different densities for the same 1080p panel (Xiaomi 320dpi, TCL 240dpi),
            // which would make the same layout bigger on one and smaller on the other. Aerial is
            // designed on a 960×540 canvas, so it scales that canvas to whatever the panel is.
            val metrics = resources.displayMetrics
            val canvasDensity = Density(
                density = minOf(metrics.widthPixels, metrics.heightPixels * 16 / 9) / 960f,
                fontScale = LocalDensity.current.fontScale,
            )
            CompositionLocalProvider(LocalReducedMotion provides reducedMotion, LocalDensity provides canvasDensity) {
                HomeScreen(
                    vm = vm,
                    onLaunch = ::launch,
                    onOpen = ::open,
                    onAppInfo = { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", it, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                    onUninstall = { open(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", it, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                    // Posted so the key-up of the Back press that started it does not end it at once.
                    onStartScreensaver = { idleHandler.postDelayed({ showScreensaver.run() }, 150) },
                )
            }
        }
    }

    // ---- Screensaver: Aerial's own, before the firmware's ad-carrying one can start ----

    private val idleHandler = Handler(Looper.getMainLooper())
    private val showScreensaver = Runnable {
        vm.screensaverVisible = true
        // Holding the screen on keeps the firmware's screensaver from starting over ours. After a
        // long stretch, let go so the TV's own power policy can still turn the panel off.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        idleHandler.postDelayed(releaseScreen, SCREENSAVER_HOLD_MS)
    }
    private val releaseScreen = Runnable { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    private var swallowKeyUp = -1

    private fun restartIdleTimer() {
        idleHandler.removeCallbacks(showScreensaver)
        idleHandler.postDelayed(showScreensaver, IDLE_BEFORE_SCREENSAVER_MS)
    }

    private fun hideScreensaver() {
        vm.screensaverVisible = false
        idleHandler.removeCallbacks(releaseScreen)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        restartIdleTimer()
        // Left / right step through the films or photos and keep the screensaver up.
        if (vm.screensaverVisible && vm.screensaverSkips.onKey(event)) return true
        if (vm.screensaverVisible && event.action == KeyEvent.ACTION_DOWN) {
            // The key that wakes the screen only wakes it; it does not also move focus or open an app.
            hideScreensaver()
            swallowKeyUp = event.keyCode
            return true
        }
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == swallowKeyUp) {
            swallowKeyUp = -1
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_PREVIEW_SCREENSAVER) {
            idleHandler.post { showScreensaver.run() }
            return
        }
        if (intent.hasCategory(Intent.CATEGORY_HOME) || intent.action == Intent.ACTION_MAIN) vm.onHomePressed()
    }

    override fun onStart() {
        super.onStart()
        GuardSetup.ensureEnabled(this)
        idleHandler.postDelayed({ GuardSetup.rebindIfStalled(this) }, 5_000)
        ContextCompat.registerReceiver(
            this, packageReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_EXPORTED,
        )
        ContextCompat.registerReceiver(
            this, volumeReceiver,
            IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
            ContextCompat.RECEIVER_EXPORTED,
        )
        getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(networkCallback)
        // Apps can change while Aerial is in the background without it hearing about it.
        vm.reload()
        Wallpapers.refreshDailyIfStale()
        Weather.refresh(Trigger.Launch)
    }

    override fun onResume() {
        super.onResume()
        restartIdleTimer()
        HomeGuardService.onAerialResumed()
        vm.refreshSystem()
        // Coming back from standby lands here. Neither this nor onStart wakes anything: if the TV is
        // asleep there is no call at all, and if the hour is spent the gate refuses.
        Weather.refresh(Trigger.Wake)
        // The hourly refresh is a foreground tick: it runs while the app is on screen and stops the
        // moment it leaves, so a reading stays fresh during a long evening without ever waking the TV.
        Weather.startForegroundTicker()
    }

    override fun onPause() {
        super.onPause()
        Weather.stopForegroundTicker()
        idleHandler.removeCallbacks(showScreensaver)
        hideScreensaver()
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(packageReceiver)
        unregisterReceiver(volumeReceiver)
        runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(networkCallback) }
    }

    /** Opens an app, growing it out of its tile. */
    private fun launch(app: AppEntry, bounds: Rect?) {
        HomeGuardService.allowStockLauncher(app.packageName)
        val options = bounds?.let {
            ActivityOptions.makeScaleUpAnimation(
                window.decorView, it.left.toInt(), it.top.toInt(), it.width.toInt(), it.height.toInt(),
            ).toBundle()
        }
        try {
            startActivity(app.launchIntent(), options)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.launch_failed, app.label), Toast.LENGTH_SHORT).show()
        }
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        const val IDLE_BEFORE_SCREENSAVER_MS = 5 * 60 * 1000L
        const val SCREENSAVER_HOLD_MS = 4 * 60 * 60 * 1000L

        /** `adb shell am start -a app.orchard.tv.PREVIEW_SCREENSAVER -n app.orchard.tv/.MainActivity` */
        const val ACTION_PREVIEW_SCREENSAVER = "app.orchard.tv.PREVIEW_SCREENSAVER"
    }
}
