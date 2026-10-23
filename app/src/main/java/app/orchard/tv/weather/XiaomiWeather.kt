package app.orchard.tv.weather

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast
import app.orchard.tv.R

/**
 * Opens Xiaomi's own weather app on the TV, which is where the user asked the weather entry to go
 * (it is the TV's weather, not this app's reading surface).
 *
 * The component is looked up before anything is launched: the app only opens it when the package is
 * actually installed, enabled and exported, and falls back to a toast otherwise. This is deliberate —
 * `com.xiaomi.tweather` is a firmware app that exists on MiTV but not on every device this launcher
 * might run on, and starting an intent that no package can handle would crash the launch.
 */
object XiaomiWeather {

    private const val PACKAGE = "com.xiaomi.tweather"
    private const val ACTIVITY = "com.xiaomi.tweather.TWeatherActivity"

    /**
     * True when the Xiaomi weather activity is present and startable. The manifest keeps
     * `QUERY_ALL_PACKAGES`, and this component is a firmware app with a launcher entry, so the
     * explicit resolve below is allowed without any extra `<queries>` declaration.
     */
    fun isAvailable(context: Context): Boolean = resolve(context) != null

    /**
     * Opens the TV's own weather app. Returns true when the intent was handed off; the caller shows
     * a toast when it was not, so the remote never lands on a dead button.
     */
    fun open(context: Context): Boolean {
        val component = resolve(context) ?: return false
        val intent = Intent(Intent.ACTION_MAIN)
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    /** Opens the weather app, or tells the user it is not on this TV. */
    fun openOrToast(context: Context) {
        if (!open(context)) {
            Toast.makeText(context, R.string.weather_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun resolve(context: Context): ComponentName? {
        val component = ComponentName(PACKAGE, ACTIVITY)
        val intent = Intent(Intent.ACTION_MAIN).setComponent(component)
        val resolved = runCatching { context.packageManager.resolveActivity(intent, 0) }.getOrNull()
            ?: return null
        if (resolved.activityInfo.packageName != PACKAGE) return null
        val info = resolved.activityInfo
        if (!info.exported || !info.isEnabled || !info.applicationInfo.enabled) return null
        return component
    }
}
