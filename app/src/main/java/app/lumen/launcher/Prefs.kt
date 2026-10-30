package app.lumen.launcher

import android.content.Context

/** Small typed wrapper around SharedPreferences. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("lumen", Context.MODE_PRIVATE)

    /** App keys in Home order. The first [HomeActivity.COLS] are the top row (Top Shelf row). */
    var order: List<String>
        get() = (sp.getString("order", "") ?: "").split('\n').filter { it.isNotBlank() }
        set(v) = sp.edit().putString("order", v.joinToString("\n")).apply()

    var hidden: Set<String>
        get() = sp.getStringSet("hidden", emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("hidden", HashSet(v)).apply()

    /** Bring Lumen back when the TV's own home screen appears (needs the helper). */
    var homeRedirect: Boolean
        get() = sp.getBoolean("home_redirect", true)
        set(v) = sp.edit().putBoolean("home_redirect", v).apply()

    var showTopShelf: Boolean
        get() = sp.getBoolean("show_top_shelf", true)
        set(v) = sp.edit().putBoolean("show_top_shelf", v).apply()

    var askedShelfPermission: Boolean
        get() = sp.getBoolean("asked_shelf_permission", false)
        set(v) = sp.edit().putBoolean("asked_shelf_permission", v).apply()

    /** Bumped whenever settings change what Home shows, so Home knows to reload. */
    var layoutVersion: Int
        get() = sp.getInt("layout_version", 0)
        set(v) = sp.edit().putInt("layout_version", v).apply()

    fun bumpLayout() {
        layoutVersion = layoutVersion + 1
    }
}
