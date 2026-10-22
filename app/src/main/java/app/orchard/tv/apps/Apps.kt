package app.orchard.tv.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

data class AppEntry(
    val packageName: String,
    val label: String,
    val component: ComponentName,
    /** Launched with CATEGORY_LEANBACK_LAUNCHER when the app has a TV entry, else CATEGORY_LAUNCHER. */
    val leanback: Boolean,
    /** False for apps on the system image, which can be disabled but not uninstalled. */
    val removable: Boolean,
    val art: TileArt,
) {
    val key: String get() = packageName

    fun launchIntent(): Intent = Intent(Intent.ACTION_MAIN)
        .addCategory(if (leanback) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
        .setComponent(component)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
}

object AppCatalog {
    /** Xiaomi's app store declares only this category, not CATEGORY_LAUNCHER (MiTV-MFTR0). */
    private const val CATEGORY_APPSTORE_LAUNCHER = "android.intent.category.APPSTORE_LAUNCHER"

    /**
     * Firmware apps that the maker's own launcher shows but that declare no launcher category at
     * all, so no query finds them. Verified on Xiaomi MiTV-MFTR0.
     */
    private val UNLISTED_ENTRIES = listOf(
        ComponentName("com.xiaomi.mitv.mediaexplorer", "com.xiaomi.mitv.mediaexplorer.NewScraperMainEntryActivity"), // 高清播放器
        ComponentName("com.xiaomi.smarthome.tv", "com.xiaomi.smarthome.tv.ui.MainActivity"), // 米家
    )

    private class Cached(val stamp: Long, val label: String, val art: TileArt)
    private val cache = HashMap<String, Cached>()

    /**
     * Every app with a TV entry, plus apps that only have a phone-style entry. China-market firmwares
     * (Xiaomi's settings, gallery, weather...) expose many of their own apps with CATEGORY_LAUNCHER only,
     * and a few with no launcher category at all (see [UNLISTED_ENTRIES]).
     * One tile per package; the TV entry wins.
     */
    suspend fun load(context: Context): List<AppEntry> = withContext(Dispatchers.Default) {
        val pm = context.packageManager
        val found = LinkedHashMap<String, Pair<ActivityInfo, Boolean>>()
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
            .forEach { found.putIfAbsent(it.activityInfo.packageName, it.activityInfo to true) }
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .forEach { found.putIfAbsent(it.activityInfo.packageName, it.activityInfo to false) }
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(CATEGORY_APPSTORE_LAUNCHER), 0)
            .forEach { found.putIfAbsent(it.activityInfo.packageName, it.activityInfo to false) }
        for (component in UNLISTED_ENTRIES) {
            val info = runCatching { pm.getActivityInfo(component, 0) }.getOrNull() ?: continue
            if (info.exported && info.isEnabled && info.applicationInfo.enabled) found.putIfAbsent(info.packageName, info to false)
        }
        found.remove(context.packageName)

        coroutineScope {
            found.values.map { (info, leanback) ->
                async { runCatching { entry(context, info, leanback) }.getOrNull() }
            }.awaitAll().filterNotNull()
        }
    }

    private fun entry(context: Context, info: ActivityInfo, leanback: Boolean): AppEntry {
        val pm = context.packageManager
        val pkg = info.packageName
        val stamp = runCatching { pm.getPackageInfo(pkg, 0).lastUpdateTime }.getOrDefault(0L)
        val cached = synchronized(cache) { cache[pkg] }?.takeIf { it.stamp == stamp }
        val label: String
        val art: TileArt
        if (cached != null) {
            label = cached.label
            art = cached.art
        } else {
            label = info.loadLabel(pm).toString().trim().ifEmpty { pkg }
            art = cardArt(context, pm, info)
            synchronized(cache) { cache[pkg] = Cached(stamp, label, art) }
        }
        val removable = (info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0
        return AppEntry(pkg, label, ComponentName(pkg, info.name), leanback, removable, art)
    }

    /**
     * Card art for one app, in the order the design fixed: the package's own card, else the icon
     * the TV shows, else the generic card, else a bare plate. Every step is guarded, so a broken
     * asset costs that app its picture at worst — never its tile.
     *
     * The app's TV banner is deliberately not used: it is a 16:9 promo image with text in it, and
     * the two fallbacks above are what the user approved instead.
     */
    private fun cardArt(context: Context, pm: PackageManager, info: ActivityInfo): TileArt {
        ArtAssets.card(context, info.packageName)?.let { card ->
            runCatching { TileArtFactory.fromBanner(card) }.getOrNull()?.let { return it }
        }
        runCatching { info.loadIcon(pm) }.getOrNull()?.let { icon ->
            runCatching { TileArtFactory.fromIconTile(icon) }.getOrNull()?.let { return it }
        }
        ArtAssets.generic(context)?.let { generic ->
            runCatching { TileArtFactory.fromBanner(generic) }.getOrNull()?.let { return it }
        }
        return TileArtFactory.fromPlainPlate()
    }
}

/**
 * The card art shipped in `assets/icons`: one 960×540 PNG per app, named with the exact package
 * name (`com.cibn.tv.png`), plus `generic-app.png` for everything else.
 *
 * Kept as an asset lookup rather than a table in code: the directory listing is the register, so
 * adding or dropping a card is a file operation, and only an exact package-name match can pick up
 * a card (a renamed or region-variant package falls through to the icon fallback).
 */
private object ArtAssets {
    private const val DIR = "icons"
    private const val EXT = ".png"
    private const val GENERIC = "$DIR/generic-app$EXT"

    @Volatile
    private var cards: Set<String>? = null

    /** Package names that have a card. Read once; the assets cannot change while we run. */
    private fun names(context: Context): Set<String> = cards ?: synchronized(this) {
        cards ?: runCatching {
            context.assets.list(DIR).orEmpty()
                .filter { it.endsWith(EXT) }
                .mapTo(HashSet()) { it.dropLast(EXT.length) }
        }.getOrDefault(emptySet()).also { cards = it }
    }

    fun card(context: Context, pkg: String): Drawable? =
        if (pkg in names(context)) drawable(context, "$DIR/$pkg$EXT") else null

    fun generic(context: Context): Drawable? = drawable(context, GENERIC)

    private fun drawable(context: Context, path: String): Drawable? = runCatching {
        context.assets.open(path).use { BitmapFactory.decodeStream(it) }
    }.getOrNull()?.let { BitmapDrawable(context.resources, it) }
}
