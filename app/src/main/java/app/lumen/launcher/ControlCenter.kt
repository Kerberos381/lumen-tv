package app.lumen.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Control Center, opened with the Menu button: volume, play/pause, Recents, background and
 * shortcuts to the TV's own settings pages. A glass panel that slides in from the right.
 */
@SuppressLint("ViewConstructor")
class ControlCenterView(
    context: Context,
    private val screenW: Int,
    private val screenH: Int,
    private val actions: Actions,
    private val onClosed: () -> Unit
) : FrameLayout(context) {

    interface Actions {
        fun showRecents()
        fun openLumenSettings()
        fun backgroundChanged()
    }

    /** [onAdjust] makes ◀ ▶ change the value in place; [closes] = false keeps the panel open on OK. */
    private class Item(
        val title: String,
        val value: () -> String,
        val onSelect: () -> Unit,
        val onAdjust: ((Int) -> Unit)? = null,
        val closes: Boolean = true
    )

    private class Row(val box: LinearLayout, val title: TextView, val value: TextView)

    private val audio = context.getSystemService(AudioManager::class.java)
    private val prefs = Prefs(context)
    private val panelW = (screenW * 0.28f).roundToInt()
    private val margin = (screenH * 0.04f).roundToInt()
    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = screenH * 0.03f
            setColor(Palette.PANEL)
            setStroke(1.dp, 0x30FFFFFF)
        }
        val p = (screenH * 0.018f).roundToInt()
        setPadding(p, p, p, p)
        elevation = 24f.dp
    }
    private val items = buildItems()
    private val rows = ArrayList<Row>()
    private var selected = 0
    private var closing = false
    private var selectDownSeen = false

    init {
        setBackgroundColor(0x59000000)
        panel.addView(header())
        for (item in items) {
            val row = rowView(item)
            rows += row
            panel.addView(row.box, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        panel.addView(TextView(context).apply {
            text = context.getString(R.string.cc_hint)
            setTextColor(Palette.TEXT_FAINT)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, screenH * 0.018f)
            typeface = Fonts.regular
            gravity = Gravity.CENTER
            val p = (screenH * 0.012f).roundToInt()
            setPadding(p, p * 2, p, p / 2)
        })
        addView(panel, LayoutParams(panelW, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            rightMargin = margin
        })
        select(0)
        alpha = 0f
        animate().alpha(1f).setDuration(160L).start()
        panel.translationX = panelW * 0.6f
        panel.animate().translationX(0f).setInterpolator(Springs.move).setDuration(460L).start()
    }

    private fun header(): LinearLayout {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = (screenH * 0.014f).roundToInt()
            setPadding(p * 2, p / 2, p * 2, p * 2)
        }
        box.addView(TextClock(context).apply {
            format12Hour = "h:mm"
            format24Hour = "H:mm"
            setTextColor(Palette.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, screenH * 0.06f)
            typeface = Fonts.light
        })
        box.addView(TextView(context).apply {
            text = context.getString(R.string.cc_title)
            setTextColor(Palette.TEXT_DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, screenH * 0.022f)
            typeface = Fonts.medium
        })
        return box
    }

    private fun buildItems(): List<Item> {
        val ctx = context
        val list = ArrayList<Item>()
        if (audio != null) {
            list += Item(
                ctx.getString(R.string.cc_volume),
                value = { volumeText() },
                onSelect = { adjustVolume(AudioManager.ADJUST_TOGGLE_MUTE) },
                onAdjust = { d -> adjustVolume(if (d > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER) },
                closes = false
            )
            list += Item(
                ctx.getString(R.string.cc_play_pause),
                value = { if (audio.isMusicActive) ctx.getString(R.string.cc_playing) else "" },
                onSelect = { playPause() },
                closes = false
            )
        }
        list += Item(ctx.getString(R.string.cc_recents), { "" }, { actions.showRecents() })
        list += Item(
            ctx.getString(R.string.cc_background),
            value = { Backgrounds.label(ctx, prefs.background) },
            onSelect = { changeBackground(1) },
            onAdjust = { d -> changeBackground(d) },
            closes = false
        )
        list += Item(ctx.getString(R.string.cc_network), { "" }, { openSettings(Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS) })
        list += Item(ctx.getString(R.string.cc_bluetooth), { "" }, { openSettings(Settings.ACTION_BLUETOOTH_SETTINGS) })
        list += Item(ctx.getString(R.string.cc_picture), { "" }, { openSettings(Settings.ACTION_DISPLAY_SETTINGS) })
        list += Item(ctx.getString(R.string.cc_sound), { "" }, { openSettings(Settings.ACTION_SOUND_SETTINGS) })
        list += Item(ctx.getString(R.string.cc_lumen), { "" }, { actions.openLumenSettings() })
        list += Item(ctx.getString(R.string.cc_tv_settings), { "" }, { openSettings(Settings.ACTION_SETTINGS) })
        return list
    }

    private fun rowView(item: Item): Row {
        val pad = (screenH * 0.014f).roundToInt()
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad * 2, pad, pad * 2, pad)
        }
        val title = TextView(context).apply {
            text = item.title
            setTextSize(TypedValue.COMPLEX_UNIT_PX, screenH * 0.027f)
            typeface = Fonts.regular
            maxLines = 1
        }
        val value = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, screenH * 0.024f)
            typeface = Fonts.regular
            maxLines = 1
            gravity = Gravity.END
        }
        box.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(value, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return Row(box, title, value)
    }

    private fun select(i: Int) {
        selected = i.coerceIn(0, rows.lastIndex)
        rows.forEachIndexed { idx, _ -> paint(idx) }
    }

    private fun paint(idx: Int) {
        val row = rows[idx]
        val item = items[idx]
        val on = idx == selected
        row.title.setTextColor(if (on) Palette.PANEL_ROW_TEXT else Palette.TEXT)
        row.value.setTextColor(if (on) Palette.PANEL_ROW_TEXT else Palette.TEXT_DIM)
        row.box.background = if (on) GradientDrawable().apply {
            cornerRadius = screenH * 0.016f
            setColor(Palette.PANEL_ROW)
        } else null
        val v = item.value()
        row.value.text = if (on && item.onAdjust != null) "‹  $v  ›" else v
    }

    fun handleKey(e: KeyEvent): Boolean {
        if (closing) return true
        val code = e.keyCode
        if (e.action == KeyEvent.ACTION_DOWN) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> select(selected - 1)
                KeyEvent.KEYCODE_DPAD_DOWN -> select(selected + 1)
                KeyEvent.KEYCODE_DPAD_LEFT -> adjust(-1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> adjust(1)
                else -> if (isSelectKey(code) && e.repeatCount == 0) selectDownSeen = true
            }
        } else if (e.action == KeyEvent.ACTION_UP) {
            when {
                isSelectKey(code) && selectDownSeen -> {
                    selectDownSeen = false
                    val item = items[selected]
                    if (item.closes) {
                        dismiss()
                        item.onSelect()
                    } else {
                        item.onSelect()
                        paint(selected)
                    }
                }
                isBackKey(code) || code == KeyEvent.KEYCODE_MENU -> dismiss()
            }
        }
        return true
    }

    private fun adjust(dir: Int) {
        val item = items[selected]
        val a = item.onAdjust ?: return
        a(dir)
        paint(selected)
    }

    fun dismiss() {
        if (closing) return
        closing = true
        panel.animate().translationX(panelW * 0.4f).setDuration(160L).start()
        animate().alpha(0f).setDuration(160L).withEndAction { onClosed() }.start()
    }

    // ---------------------------------------------------------------- actions

    private fun volumeText(): String {
        val a = audio ?: return ""
        if (a.isStreamMute(AudioManager.STREAM_MUSIC)) return context.getString(R.string.cc_muted)
        val max = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return "${a.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max}%"
    }

    private fun adjustVolume(direction: Int) {
        val a = audio ?: return
        // On TVs whose volume lives in the TV or a soundbar (HDMI-CEC), the system's own
        // volume bar is the only feedback, so show it there.
        val flags = if (a.isVolumeFixed) AudioManager.FLAG_SHOW_UI else 0
        runCatching { a.adjustSuggestedStreamVolume(direction, AudioManager.USE_DEFAULT_STREAM_TYPE, flags) }
    }

    private fun playPause() {
        val a = audio ?: return
        val t = SystemClock.uptimeMillis()
        a.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0))
        a.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0))
        // The playing state settles a moment later.
        val idx = selected
        postDelayed({ if (!closing && idx in rows.indices) paint(idx) }, 400L)
    }

    private fun changeBackground(dir: Int) {
        prefs.background = Backgrounds.next(prefs.background, dir)
        actions.backgroundChanged()
    }

    private fun openSettings(action: String, fallback: String = Settings.ACTION_SETTINGS) {
        for (a in listOf(action, fallback)) {
            val ok = runCatching {
                context.startActivity(Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (ok) return
        }
    }
}
