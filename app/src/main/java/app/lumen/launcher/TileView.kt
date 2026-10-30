package app.lumen.launcher

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint
import android.text.TextUtils
import android.view.View

/** One app tile: artwork, focus lift with spring, shadow, sheen and label. */
@SuppressLint("ViewConstructor")
class TileView(
    context: Context,
    val entry: AppEntry,
    private val tileW: Int,
    private val tileH: Int,
    private val labelH: Int
) : View(context) {

    companion object {
        const val FOCUS_SCALE = 0.14f
    }

    private val art = ArtPainter()
    private val rect = RectF(0f, 0f, tileW.toFloat(), tileH.toFloat())
    private val edgeRect = RectF(0.75f, 0.75f, tileW - 0.75f, tileH - 0.75f)
    val radius = tileH * 0.085f

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            0f, 0f, tileW * 0.75f, tileH.toFloat(),
            intArrayOf(0x40FFFFFF, 0x0DFFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = 0x24FFFFFF
    }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.TEXT
        textSize = labelH * 0.50f
        typeface = Fonts.medium
        textAlign = Paint.Align.CENTER
    }
    private val label: String =
        TextUtils.ellipsize(entry.label, labelPaint, tileW * 1.1f, TextUtils.TruncateAt.END).toString()

    var focusAmount = 0f
        private set
    var isTileFocused = false
        private set

    private var focusAnim: ValueAnimator? = null
    private var nudgeAnim: ValueAnimator? = null
    private var wiggleAnim: ObjectAnimator? = null
    private var nudgeX = 0f
    private var nudgeY = 0f

    init {
        pivotX = tileW / 2f
        pivotY = tileH / 2f
        outlineProvider = null
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(tileW, tileH + labelH)
    }

    fun setFocused(focused: Boolean, animate: Boolean) {
        isTileFocused = focused
        translationZ = if (focused) 10f else 0f
        focusAnim?.cancel()
        val target = if (focused) 1f else 0f
        if (!animate) {
            applyFocus(target)
            return
        }
        focusAnim = ValueAnimator.ofFloat(focusAmount, target).apply {
            duration = if (focused) 480L else 320L
            interpolator = if (focused) Springs.focus else Springs.smooth
            addUpdateListener { applyFocus(it.animatedValue as Float) }
            start()
        }
    }

    private fun applyFocus(v: Float) {
        focusAmount = v
        val s = 1f + FOCUS_SCALE * v
        scaleX = s
        scaleY = s
        invalidate()
    }

    /** Small bounce towards a direction you cannot move in. */
    fun nudge(dx: Int, dy: Int) {
        nudgeAnim?.cancel()
        val dist = 10f.dp
        nudgeAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300L
            interpolator = Springs.bump
            addUpdateListener {
                val v = it.animatedValue as Float
                nudgeX = dx * dist * v
                nudgeY = dy * dist * v
                invalidate()
            }
            start()
        }
    }

    /** Wiggle while the tile is being moved. */
    fun setWiggle(on: Boolean) {
        wiggleAnim?.cancel()
        wiggleAnim = null
        rotation = 0f
        if (on) {
            wiggleAnim = ObjectAnimator.ofFloat(this, View.ROTATION, -1.4f, 1.4f).apply {
                duration = 140L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                start()
            }
        }
    }

    override fun onDraw(c: Canvas) {
        val a = focusAmount.coerceIn(0f, 1.2f)
        val save = c.save()
        c.translate(nudgeX, nudgeY)
        if (a > 0.01f) {
            val k = a.coerceAtMost(1f)
            shadowPaint.setShadowLayer(
                (22f.dp * k).coerceAtLeast(1f), 0f, 12f.dp * k,
                Color.argb((175 * k).toInt(), 0, 0, 0)
            )
            c.drawRoundRect(rect, radius, radius, shadowPaint)
        }
        art.draw(c, rect, radius, entry)
        if (a > 0.01f) {
            sheenPaint.alpha = (255 * a.coerceAtMost(1f)).toInt()
            c.drawRoundRect(rect, radius, radius, sheenPaint)
        }
        c.drawRoundRect(edgeRect, radius, radius, edgePaint)
        if (a > 0.03f) {
            labelPaint.alpha = (255 * a.coerceAtMost(1f)).toInt()
            c.drawText(label, tileW / 2f, tileH + labelH * 0.80f, labelPaint)
        }
        c.restoreToCount(save)
    }
}
