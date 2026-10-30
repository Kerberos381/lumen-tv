package app.lumen.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.provider.Settings

/** One tile on Home. */
class AppEntry(
    /** Stable key used for ordering and hiding (the package name, or a pseudo key). */
    val key: String,
    val label: String,
    val packageName: String,
    val launchIntent: Intent,
    /** 16:9 artwork if the app ships an Android TV banner. */
    val banner: Bitmap?,
    val icon: Bitmap?,
    /** Muted dominant colour of the artwork. */
    val tint: Int,
    /** Tiny blurred image for the full-screen backdrop. */
    val backdrop: Bitmap,
    val isSystem: Boolean,
    val isPseudo: Boolean = false
)

/** Loads launchable apps from PackageManager and applies the saved order. Call off the main thread. */
class AppRepository(private val context: Context) {
    private val pm: PackageManager = context.packageManager
    private val prefs = Prefs(context)

    companion object {
        const val KEY_TV_SETTINGS = "lumen:tv-settings"
        const val KEY_LUMEN_SETTINGS = "lumen:settings"
        const val BANNER_W = 400
        const val BANNER_H = 225
        const val ICON_SIZE = 160

        /** Streaming apps that make a sensible first row when there is no saved layout yet. */
        private val PREFERRED = listOf(
            "com.netflix.ninja",
            "com.google.android.youtube.tv",
            "com.disney.disneyplus",
            "com.amazon.amazonvideo.livingroom",
            "com.wbd.stream",
            "com.hbo.hbonow",
            "com.apple.atve.androidtv.appletv",
            "com.skyshowtime.skyshowtime",
            "com.spotify.tv.android",
            "com.plexapp.android",
            "org.jellyfin.androidtv",
            "tv.twitch.android.app",
            "com.google.android.apps.youtube.music.pwa",
            "com.google.android.youtube.tvkids"
        )
    }

    /** All visible tiles in Home order. */
    fun loadOrdered(): List<AppEntry> {
        val all = loadAll()
        val hidden = prefs.hidden
        val visible = all.filter { it.key !in hidden }
        val byKey = visible.associateBy { it.key }
        val saved = prefs.order
        val result = ArrayList<AppEntry>(visible.size)
        val used = HashSet<String>()

        if (saved.isNotEmpty()) {
            for (k in saved) byKey[k]?.let { if (used.add(k)) result += it }
        } else {
            for (k in PREFERRED) byKey[k]?.let { if (used.add(k)) result += it }
        }
        // New or not-yet-ordered apps: alphabetical, pseudo tiles last.
        visible.filter { it.key !in used && !it.isPseudo }
            .sortedBy { it.label.lowercase() }
            .forEach { used.add(it.key); result += it }
        visible.filter { it.key !in used }.forEach { result += it }
        return result
    }

    /** Every launchable app (hidden ones included), for the settings screen. */
    fun loadAll(): List<AppEntry> {
        val byPkg = LinkedHashMap<String, Pair<ResolveInfo, Boolean>>()
        val leanback = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        for (ri in pm.queryIntentActivities(leanback, 0)) {
            byPkg.putIfAbsent(ri.activityInfo.packageName, ri to true)
        }
        val phone = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        for (ri in pm.queryIntentActivities(phone, 0)) {
            byPkg.putIfAbsent(ri.activityInfo.packageName, ri to false)
        }
        byPkg.remove(context.packageName)

        val out = ArrayList<AppEntry>(byPkg.size + 2)
        for ((pkg, pair) in byPkg) {
            runCatching { build(pkg, pair.first, pair.second) }.getOrNull()?.let { out += it }
        }
        out += pseudoTiles(hasTvSettingsTile = byPkg.keys.any { it == "com.android.tv.settings" })
        return out
    }

    private fun build(pkg: String, ri: ResolveInfo, isLeanback: Boolean): AppEntry {
        val ai = ri.activityInfo
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(if (isLeanback) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(pkg, ai.name))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val label = ri.loadLabel(pm).toString().ifBlank { pkg }
        val bannerDrawable = runCatching { ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm) }.getOrNull()
        val iconDrawable = runCatching { ai.loadIcon(pm) }.getOrNull()
        val banner = bannerDrawable?.let { Images.renderCover(it, BANNER_W, BANNER_H) }
        val icon = iconDrawable?.let { Images.renderSquare(it, ICON_SIZE) }
        val tint = Images.dominantColor(banner ?: icon)
        val isSystem = (ai.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        return AppEntry(
            key = pkg,
            label = label,
            packageName = pkg,
            launchIntent = intent,
            banner = banner,
            icon = icon,
            tint = tint,
            backdrop = Images.backdrop(banner, icon, tint),
            isSystem = isSystem
        )
    }

    /** Tiles that are not apps: TV settings (the stock launcher's gear is gone) and Lumen settings. */
    private fun pseudoTiles(hasTvSettingsTile: Boolean): List<AppEntry> {
        val out = ArrayList<AppEntry>(2)
        if (!hasTvSettingsTile) {
            val gear = context.getDrawable(R.drawable.ic_gear)
            val icon = gear?.let { Images.renderSquare(it, ICON_SIZE) }
            val tint = 0xFF3A3A3F.toInt()
            out += AppEntry(
                key = KEY_TV_SETTINGS,
                label = context.getString(R.string.tile_tv_settings),
                packageName = KEY_TV_SETTINGS,
                launchIntent = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                banner = null,
                icon = icon,
                tint = tint,
                backdrop = Images.backdrop(null, icon, tint),
                isSystem = true,
                isPseudo = true
            )
        }
        val mark = context.getDrawable(R.drawable.ic_lumen_mark)
        val markIcon = mark?.let { Images.renderSquare(it, ICON_SIZE) }
        val lumenTint = 0xFF1B2A6B.toInt()
        out += AppEntry(
            key = KEY_LUMEN_SETTINGS,
            label = context.getString(R.string.tile_lumen),
            packageName = KEY_LUMEN_SETTINGS,
            launchIntent = Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            banner = null,
            icon = markIcon,
            tint = lumenTint,
            backdrop = Images.backdrop(null, markIcon, lumenTint),
            isSystem = true,
            isPseudo = true
        )
        return out
    }
}
