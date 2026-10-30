package app.lumen.launcher

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

internal fun isSelectKey(code: Int) = code == KeyEvent.KEYCODE_DPAD_CENTER ||
    code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER ||
    code == KeyEvent.KEYCODE_BUTTON_A

internal fun isBackKey(code: Int) = code == KeyEvent.KEYCODE_BACK ||
    code == KeyEvent.KEYCODE_ESCAPE || code == KeyEvent.KEYCODE_BUTTON_B

/** Long-press menu next to a tile: Move, Hide, App info, Uninstall. */
@SuppressLint("ViewConstructor")
class OptionsMenuView(
    context: Context,
    title: String,
    private val items: List<Pair<String, () -> Unit>>,
    private val screenW: Int,
    private val screenH: Int,
    private val onClosed: () -> Unit
) : FrameLayout(context) {

    private val panelW = (screenW * 0.20f).toInt()
    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = screenH * 0.022f
            setColor(Palette.PANEL)
        }
        val p = (screenH * 0.012f).toInt()
        setPadding(p, p, p, p)
        elevation = 24f.dp
    }
    private val rows = ArrayList<TextView>()
    private var selected = 0
    private var selectDownSeen = false
    private var closing = false

    init {
        setBackgroundColor(0x73000000)
        val head = TextView(context).apply {
            text = title
            setTextColor(Palette.TEXT_DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, screenH * 0.021f)
            typeface = Fonts.medium
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            val p = (screenH * 0.012f).toInt()
            setPadding(p * 2, p, p * 2, p)
        }
        panel.addView(head)
        for ((label, _) in items) {
            val row = TextView(context).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_PX, screenH * 0.028f)
                typeface = Fonts.regular
                val v = (screenH * 0.014f).toInt()
                setPadding(v * 2, v, v * 2, v)
            }
            rows += row
            panel.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        addView(panel, LayoutParams(panelW, LayoutParams.WRAP_CONTENT))
        select(0)
        alpha = 0f
        animate().alpha(1f).setDuration(140L).start()
    }

    /** Place the panel beside the tile, flipping sides near the right edge. */
    fun anchorTo(tile: RectF) {
        panel.post {
            val pad = screenW * 0.012f
            val x = if (tile.right + pad + panelW < screenW - pad) tile.right + pad else tile.left - pad - panelW
            val y = tile.top.coerceIn(pad, screenH - panel.height - pad)
            panel.translationX = x
            panel.translationY = y
            panel.scaleX = 0.94f
            panel.scaleY = 0.94f
            panel.pivotX = if (x > tile.left) 0f else panelW.toFloat()
            panel.pivotY = 0f
            panel.animate().scaleX(1f).scaleY(1f).setInterpolator(Springs.focus).setDuration(380L).start()
        }
    }

    private fun select(i: Int) {
        selected = i.coerceIn(0, rows.lastIndex)
        rows.forEachIndexed { idx, tv ->
            val on = idx == selected
            tv.setTextColor(if (on) Palette.PANEL_ROW_TEXT else Palette.TEXT)
            tv.background = if (on) GradientDrawable().apply {
                cornerRadius = screenH * 0.014f
                setColor(Palette.PANEL_ROW)
            } else null
        }
    }

    fun handleKey(e: KeyEvent): Boolean {
        if (closing) return true
        val code = e.keyCode
        if (e.action == KeyEvent.ACTION_DOWN) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> select(selected - 1)
                KeyEvent.KEYCODE_DPAD_DOWN -> select(selected + 1)
                else -> if (isSelectKey(code) && e.repeatCount == 0) selectDownSeen = true
            }
        } else if (e.action == KeyEvent.ACTION_UP) {
            when {
                // Ignore the release of the long-press that opened this menu.
                isSelectKey(code) && selectDownSeen -> {
                    val action = items[selected].second
                    dismiss()
                    action()
                }
                isBackKey(code) || code == KeyEvent.KEYCODE_MENU -> dismiss()
            }
        }
        return true
    }

    fun dismiss() {
        if (closing) return
        closing = true
        animate().alpha(0f).setDuration(120L).withEndAction { onClosed() }.start()
    }
}

/** One card in the app switcher: app name above, snapshot or artwork below. */
@SuppressLint("ViewConstructor")
class RecentCardView(
    context: Context,
    val entry: AppEntry,
    private val cardW: Int,
    private val cardH: Int,
    private val headerH: Int
) : View(context) {
    private val art = ArtPainter().apply { maxIconSize = 200f.dp }
    private val rect = RectF(0f, headerH.toFloat(), cardW.toFloat(), (headerH + cardH).toFloat())
    private val iconRect = RectF()
    private val radius = cardH * 0.045f
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.TEXT
        textSize = headerH * 0.46f
        typeface = Fonts.medium
    }
    private val label = TextUtils.ellipsize(entry.label, labelPaint, cardW * 0.8f, TextUtils.TruncateAt.END).toString()
    var snapshot: Bitmap? = null
        set(v) {
            field = v
            invalidate()
        }

    init {
        pivotX = cardW / 2f
        pivotY = headerH + cardH / 2f
        outlineProvider = null
        cameraDistance = 9000f * resources.displayMetrics.density
        shadow.setShadowLayer(30f.dp, 0f, 16f.dp, 0xB0000000.toInt())
    }

    /** On-screen rect of the artwork, accounting for this view's translation and scale. */
    fun artRectOnScreen(): RectF {
        val s = scaleX
        val cx = x + cardW / 2f
        val cy = y + headerH + cardH / 2f
        return RectF(cx - cardW * s / 2f, cy - cardH * s / 2f, cx + cardW * s / 2f, cy + cardH * s / 2f)
    }

    val cornerRadius: Float get() = radius * scaleX

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(cardW, headerH + cardH)
    }

    override fun onDraw(c: Canvas) {
        val icon = entry.icon
        var textX = 0f
        if (icon != null) {
            val s = headerH * 0.62f
            iconRect.set(0f, (headerH - s) / 2f - headerH * 0.08f, s, (headerH + s) / 2f - headerH * 0.08f)
            c.drawBitmap(icon, null, iconRect, iconPaint)
            textX = s + headerH * 0.25f
        }
        c.drawText(label, textX, headerH * 0.62f, labelPaint)
        c.drawRoundRect(rect, radius, radius, shadow)
        art.draw(c, rect, radius, entry, snapshot)
    }
}

/**
 * App switcher (double-press Home). Horizontal row of large cards, the focused one centred.
 * ◀ ▶ browse · OK open · ▲ close the app · ▼ / Back return.
 */
@SuppressLint("ViewConstructor")
class RecentsView(
    context: Context,
    private val screenW: Int,
    private val screenH: Int
) : FrameLayout(context) {

    interface Listener {
        fun onOpen(entry: AppEntry, from: RectF, radius: Float, snapshot: Bitmap?)
        fun onDismiss(entry: AppEntry)
        fun onClosed()
    }

    var listener: Listener? = null

    private val cardW = (screenW * 0.46f).toInt()
    private val cardH = cardW * 9 / 16
    private val headerH = (screenH * 0.06f).toInt()
    private val spacing = cardW + (screenW * 0.03f).toInt()
    private val cards = ArrayList<RecentCardView>()
    private val layer = FrameLayout(context).apply { clipChildren = false }
    private val title = TextView(context)
    private val hint = TextView(context)
    private val empty = TextView(context)
    private var selected = 0
    private var selectDownSeen = false
    private var dismissing = false

    val isShowing: Boolean get() = visibility == VISIBLE

    init {
        visibility = GONE
        clipChildren = false
        setBackgroundColor(0xE6050507.toInt())
        addView(layer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        title.apply {
            text = context.getString(R.string.recents_title)
            setTextColor(Palette.TEXT_DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, screenH * 0.026f)
            typeface = Fonts.medium
            letterSpacing = 0.06f
        }
        addView(title, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = (screenH * 0.08f).toInt()
        })
        hint.apply {
            text = context.getString(R.string.recents_hint)
            setTextColor(Palette.TEXT_FAINT)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, screenH * 0.021f)
            typeface = Fonts.regular
        }
        addView(hint, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = (screenH * 0.08f).toInt()
        })
        empty.apply {
            text = context.getString(R.string.recents_empty)
            setTextColor(Palette.TEXT_DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, screenH * 0.032f)
            typeface = Fonts.light
            visibility = GONE
        }
        addView(empty, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })
    }

    fun show(entries: List<AppEntry>) {
        layer.removeAllViews()
        cards.clear()
        selected = 0
        selectDownSeen = false
        dismissing = false
        for (e in entries) {
            val card = RecentCardView(context, e, cardW, cardH, headerH)
            layer.addView(card, LayoutParams(cardW, headerH + cardH))
            cards += card
        }
        // Most recent first: index 0 is usually the app you just left.
        empty.visibility = if (cards.isEmpty()) VISIBLE else GONE
        hint.visibility = if (cards.isEmpty()) GONE else VISIBLE
        layoutCards(animate = false)
        cards.forEach {
            it.alpha = 0f
            it.animate().alpha(targetAlpha(cards.indexOf(it))).setDuration(220L).start()
        }
        alpha = 0f
        visibility = VISIBLE
        animate().alpha(1f).setDuration(180L).start()
    }

    fun setSnapshot(pkg: String, bmp: Bitmap) {
        cards.firstOrNull { it.entry.packageName == pkg }?.snapshot = bmp
    }

    fun hide(animate: Boolean) {
        if (!isShowing) return
        if (!animate) {
            visibility = GONE
            listener?.onClosed()
            return
        }
        animate().alpha(0f).setDuration(160L).withEndAction {
            visibility = GONE
            listener?.onClosed()
        }.start()
    }

    private fun targetAlpha(i: Int) = if (i == selected) 1f else 0.55f

    private fun layoutCards(animate: Boolean) {
        val top = (screenH - (headerH + cardH)) / 2f
        cards.forEachIndexed { i, card ->
            val d = i - selected
            val x = screenW / 2f - cardW / 2f + d * spacing
            val s = if (d == 0) 1f else 0.86f
            val rot = -8f * d.coerceIn(-1, 1)
            if (animate) {
                card.animate().translationX(x).translationY(top).scaleX(s).scaleY(s).rotationY(rot)
                    .alpha(targetAlpha(i)).setInterpolator(Springs.move).setDuration(520L).start()
            } else {
                card.translationX = x
                card.translationY = top
                card.scaleX = s
                card.scaleY = s
                card.rotationY = rot
            }
            card.translationZ = if (d == 0) 10f else 0f
        }
    }

    private fun move(delta: Int) {
        if (cards.isEmpty()) return
        val next = (selected + delta).coerceIn(0, cards.lastIndex)
        if (next == selected) {
            val c = cards[selected]
            val base = c.translationX
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 280L
                interpolator = Springs.bump
                addUpdateListener { c.translationX = base + delta * 14f.dp * (it.animatedValue as Float) }
                start()
            }
            return
        }
        selected = next
        layoutCards(animate = true)
    }

    private fun dismissSelected() {
        if (cards.isEmpty() || dismissing) return
        dismissing = true
        val card = cards[selected]
        card.animate().translationY(card.translationY - screenH * 0.55f).alpha(0f)
            .setInterpolator(Springs.smooth).setDuration(260L).withEndAction {
                layer.removeView(card)
                cards.remove(card)
                listener?.onDismiss(card.entry)
                selected = selected.coerceAtMost((cards.size - 1).coerceAtLeast(0))
                dismissing = false
                if (cards.isEmpty()) {
                    empty.visibility = VISIBLE
                    hint.visibility = GONE
                } else {
                    layoutCards(animate = true)
                }
            }.start()
    }

    fun handleKey(e: KeyEvent): Boolean {
        val code = e.keyCode
        if (e.action == KeyEvent.ACTION_DOWN) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> move(-1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> move(1)
                KeyEvent.KEYCODE_DPAD_UP -> if (e.repeatCount == 0) dismissSelected()
                KeyEvent.KEYCODE_DPAD_DOWN -> if (e.repeatCount == 0) hide(animate = true)
                else -> if (isSelectKey(code) && e.repeatCount == 0) selectDownSeen = true
            }
        } else if (e.action == KeyEvent.ACTION_UP) {
            when {
                isSelectKey(code) && selectDownSeen -> {
                    selectDownSeen = false
                    val card = cards.getOrNull(selected)
                    if (card != null && !dismissing) {
                        listener?.onOpen(card.entry, card.artRectOnScreen(), card.cornerRadius, card.snapshot)
                    }
                }
                isBackKey(code) -> hide(animate = true)
            }
        }
        return true
    }
}
