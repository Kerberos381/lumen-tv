package app.lumen.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Lumen settings: the helper, permissions, Top Shelf and layout. Plain focusable rows for the remote. */
class SettingsActivity : Activity() {

    companion object {
        private const val REQ_SHELF = 42
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private class Row(val title: String, val subtitle: String, val value: String?, val onClick: (() -> Unit)?)

    private lateinit var prefs: Prefs
    private lateinit var list: LinearLayout
    private var screenH = 0
    private var focusedIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val dm = resources.displayMetrics
        val screenW = max(dm.widthPixels, dm.heightPixels)
        screenH = min(dm.widthPixels, dm.heightPixels)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Palette.BG)
            isVerticalScrollBarEnabled = false
            isFillViewport = true
        }
        val padX = (screenW * 0.17f).roundToInt()
        val padY = (screenH * 0.07f).roundToInt()
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padX, padY, padX, padY)
            clipChildren = false
            clipToPadding = false
        }
        val title = TextView(this).apply {
            text = getString(R.string.settings_title)
            setTextColor(Palette.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, screenH * 0.052f)
            typeface = Fonts.light
            setPadding(0, 0, 0, (screenH * 0.03f).roundToInt())
        }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        outer.addView(title)
        outer.addView(list, LinearLayout.LayoutParams(MATCH, WRAP))
        scroll.addView(outer, ViewGroup.LayoutParams(MATCH, WRAP))
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_SHELF) return
        render()
        prefs.bumpLayout()
        if (!TopShelfSource.hasPermission(this) && !shouldShowRequestPermissionRationale(TopShelfSource.PERMISSION)) {
            adbDialog(
                getString(R.string.s_shelf_perm),
                getString(R.string.adb_shelf_intro),
                "adb shell pm grant $packageName ${TopShelfSource.PERMISSION}"
            )
        }
    }

    // ---------------------------------------------------------------- rows

    private fun rows(): List<Row> {
        val on = getString(R.string.v_on)
        val off = getString(R.string.v_off)
        val allowed = getString(R.string.v_allowed)
        val notAllowed = getString(R.string.v_not_allowed)
        val helperSub = if (HelperService.canSnapshot) getString(R.string.s_helper_sub)
        else getString(R.string.s_helper_sub) + " " + getString(R.string.s_helper_old_android)
        val hiddenCount = prefs.hidden.size

        return listOf(
            Row(
                getString(R.string.s_helper), helperSub,
                if (HelperService.isEnabled(this)) on else off
            ) { openAccessibilitySettings() },
            Row(
                getString(R.string.s_redirect), getString(R.string.s_redirect_sub),
                if (prefs.homeRedirect) on else off
            ) {
                prefs.homeRedirect = !prefs.homeRedirect
                render()
            },
            Row(
                getString(R.string.s_default_home), getString(R.string.s_default_home_sub),
                getString(if (isDefaultHome()) R.string.v_lumen else R.string.v_other)
            ) { openHomeSettings() },
            Row(
                getString(R.string.s_usage), getString(R.string.s_usage_sub),
                if (UsageAccess.granted(this)) allowed else notAllowed
            ) { openUsageAccess() },
            Row(
                getString(R.string.s_shelf_perm), getString(R.string.s_shelf_perm_sub),
                if (TopShelfSource.hasPermission(this)) allowed else notAllowed
            ) { requestShelfPermission() },
            Row(
                getString(R.string.s_shelf_show), getString(R.string.s_shelf_show_sub),
                if (prefs.showTopShelf) on else off
            ) {
                prefs.showTopShelf = !prefs.showTopShelf
                prefs.bumpLayout()
                render()
            },
            Row(
                getString(R.string.s_hidden), getString(R.string.s_hidden_sub),
                hiddenCount.toString()
            ) { if (hiddenCount > 0) confirmUnhide(hiddenCount) },
            Row(getString(R.string.s_reset), getString(R.string.s_reset_sub), null) { confirmReset() },
            Row(getString(R.string.s_tv_settings), getString(R.string.s_tv_settings_sub), null) {
                runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
            },
            Row(getString(R.string.s_about), getString(R.string.s_about_sub), versionName(), null)
        )
    }

    private fun render() {
        list.removeAllViews()
        val rows = rows()
        rows.forEachIndexed { i, r -> list.addView(rowView(i, r)) }
        list.getChildAt(focusedIndex.coerceIn(0, rows.lastIndex))?.requestFocus()
    }

    private fun rowView(index: Int, r: Row): View {
        val pad = (screenH * 0.022f).roundToInt()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(pad * 2, pad, pad * 2, pad)
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(this).apply {
            text = r.title
            setTextSize(TypedValue.COMPLEX_UNIT_PX, screenH * 0.031f)
            typeface = Fonts.medium
        }
        val sub = TextView(this).apply {
            text = r.subtitle
            setTextSize(TypedValue.COMPLEX_UNIT_PX, screenH * 0.021f)
            typeface = Fonts.regular
            setPadding(0, (screenH * 0.004f).roundToInt(), 0, 0)
        }
        texts.addView(title)
        texts.addView(sub)
        row.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
        val value = TextView(this).apply {
            text = r.value ?: ""
            setTextSize(TypedValue.COMPLEX_UNIT_PX, screenH * 0.027f)
            typeface = Fonts.regular
            setPadding(pad * 2, 0, 0, 0)
        }
        row.addView(value, LinearLayout.LayoutParams(WRAP, WRAP))

        val radius = screenH * 0.018f
        fun paint(focused: Boolean) {
            row.background = GradientDrawable().apply {
                cornerRadius = radius
                setColor(if (focused) Palette.PANEL_ROW else 0x0DFFFFFF)
            }
            title.setTextColor(if (focused) Palette.PANEL_ROW_TEXT else Palette.TEXT)
            sub.setTextColor(if (focused) 0xFF5A5A60.toInt() else Palette.TEXT_DIM)
            value.setTextColor(if (focused) Palette.PANEL_ROW_TEXT else Palette.TEXT_DIM)
            val s = if (focused) 1.02f else 1f
            row.animate().scaleX(s).scaleY(s).setInterpolator(Springs.focus).setDuration(320L).start()
        }
        paint(false)
        row.setOnFocusChangeListener { _, hasFocus ->
            paint(hasFocus)
            if (hasFocus) focusedIndex = index
        }
        row.setOnClickListener { r.onClick?.invoke() }
        row.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
            bottomMargin = (screenH * 0.012f).roundToInt()
        }
        return row
    }

    // ---------------------------------------------------------------- actions

    private fun openAccessibilitySettings() {
        val opened = runCatching {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, R.string.toast_find_helper, Toast.LENGTH_LONG).show()
        }.isSuccess
        if (!opened) {
            adbDialog(
                getString(R.string.s_helper),
                getString(R.string.adb_helper_intro),
                "adb shell settings put secure enabled_accessibility_services $packageName/$packageName.HelperService\n" +
                    "adb shell settings put secure accessibility_enabled 1"
            )
        }
    }

    private fun openHomeSettings() {
        val opened = runCatching { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }.isSuccess
        if (!opened) {
            adbDialog(
                getString(R.string.s_default_home),
                getString(R.string.adb_home_intro),
                "adb shell cmd package set-home-activity $packageName/.HomeActivity"
            )
        }
    }

    private fun openUsageAccess() {
        val opened = runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }.isSuccess
        if (!opened) {
            adbDialog(
                getString(R.string.s_usage),
                getString(R.string.adb_usage_intro),
                "adb shell appops set $packageName GET_USAGE_STATS allow"
            )
        }
    }

    private fun requestShelfPermission() {
        if (TopShelfSource.hasPermission(this)) {
            Toast.makeText(this, R.string.toast_already_allowed, Toast.LENGTH_SHORT).show()
            return
        }
        requestPermissions(arrayOf(TopShelfSource.PERMISSION), REQ_SHELF)
    }

    private fun confirmUnhide(count: Int) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setMessage(getString(R.string.confirm_unhide, count))
            .setPositiveButton(R.string.action_show) { _, _ ->
                prefs.hidden = emptySet()
                prefs.bumpLayout()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setMessage(R.string.confirm_reset)
            .setPositiveButton(R.string.action_reset) { _, _ ->
                prefs.order = emptyList()
                prefs.bumpLayout()
                Toast.makeText(this, R.string.toast_reset, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun adbDialog(title: String, intro: String, commands: String) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setMessage(intro + "\n\n" + commands)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun isDefaultHome(): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val ri = packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
        return ri?.activityInfo?.packageName == packageName
    }

    private fun versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
}
