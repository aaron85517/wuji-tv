package app.orchard.tv.ui.home

import android.content.Context
import androidx.core.content.edit

/**
 * The two ways Home can be arranged: the tvOS grid of icon tiles, or a short list in large type.
 * Both read the same apps, order and artwork; only the layout differs.
 */
enum class HomeStyle { Icons, Large }

/** Remembers the chosen style across process restarts. Kept apart from LayoutStore: this is look, not order. */
class StyleStore(context: Context) {
    private val prefs = context.getSharedPreferences("home_style", Context.MODE_PRIVATE)

    var style: HomeStyle
        get() = if (prefs.getString(KEY, null) == LARGE) HomeStyle.Large else HomeStyle.Icons
        set(value) = prefs.edit { putString(KEY, if (value == HomeStyle.Large) LARGE else ICONS) }

    private companion object {
        const val KEY = "style"
        const val LARGE = "large"
        const val ICONS = "icons"
    }
}
