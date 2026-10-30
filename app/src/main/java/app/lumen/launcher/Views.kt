package app.lumen.launcher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/** Frosted glass panel behind the top row. The backdrop under it is already blurred. */
class GlassView(context: Context) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1CFFFFFF }
    private val highlight = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f.dp
        color = 0x30FFFFFF
    }
    private val r = RectF()
    private val er = RectF()
    var radius = 30f.dp

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        r.set(0f, 0f, w.toFloat(), h.toFloat())
        val inset = edge.strokeWidth / 2f
        er.set(inset, inset, w - inset, h - inset)
        highlight.shader = LinearGradient(
            0f, 0f, 0f, h * 0.55f, 0x26FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(c: Canvas) {
        c.drawRoundRect(r, radius, radius, fill)
        c.drawRoundRect(r, radius, radius, highlight)
        c.drawRoundRect(er, radius, radius, edge)
    }
}

/** Plain artwork with a soft shadow (Top Shelf hero). */
class ArtView(context: Context) : View(context) {
    private val art = ArtPainter()
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val r = RectF()
    var entry: AppEntry? = null
        set(v) {
            field = v
            invalidate()
        }

    init {
        setLayerType(LAYER_TYPE_NONE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        r.set(0f, 0f, w.toFloat(), h.toFloat())
        shadow.setShadowLayer(28f.dp, 0f, 14f.dp, 0xA0000000.toInt())
    }

    override fun onDraw(c: Canvas) {
        val e = entry ?: return
        val radius = height * 0.07f
        c.drawRoundRect(r, radius, radius, shadow)
        art.draw(c, r, radius, e)
    }
}

/**
 * Full-window overlay for the "tile grows into the app" and "app shrinks back into its
 * tile" transitions. Draws the same artwork as the tile, or a fresh snapshot.
 */
class ZoomView(context: Context) : View(context) {
    private val art = ArtPainter().apply { maxIconSize = 260f.dp }
    private var entry: AppEntry? = null
    private var image: Bitmap? = null
    private val from = RectF()
    private val to = RectF()
    private val cur = RectF()
    private var fromRadius = 0f
    private var toRadius = 0f
    private var radius = 0f
    private var animator: ValueAnimator? = null

    val isAnimating: Boolean get() = animator?.isRunning == true
    val isShowing: Boolean get() = visibility == VISIBLE

    init {
        visibility = GONE
    }

    /** Grow from [start] to [end], then call [onEnd]. */
    fun playOpen(e: AppEntry, img: Bitmap?, start: RectF, startRadius: Float, end: RectF, onEnd: () -> Unit) {
        entry = e
        image = img
        from.set(start)
        to.set(end)
        fromRadius = startRadius
        toRadius = 0f
        cur.set(start)
        radius = startRadius
        animate().cancel()
        alpha = 1f
        visibility = VISIBLE
        run(Springs.smooth, 320L, onEnd)
    }

    /** Show the app's art full-screen right away (first frame when returning Home). */
    fun showFull(e: AppEntry, img: Bitmap?, full: RectF) {
        animator?.cancel()
        animate().cancel()
        entry = e
        image = img
        cur.set(full)
        radius = 0f
        alpha = 1f
        visibility = VISIBLE
        invalidate()
    }

    fun updateImage(img: Bitmap?) {
        if (img != null) {
            image = img
            invalidate()
        }
    }

    /** Shrink from the current rect into [target], then fade out. */
    fun playClose(target: RectF, targetRadius: Float) {
        if (entry == null) {
            hide()
            return
        }
        from.set(cur)
        to.set(target)
        fromRadius = radius
        toRadius = targetRadius
        run(Springs.move, 440L) {
            animate().alpha(0f).setDuration(110L).withEndAction { hide() }.start()
        }
    }

    fun hide() {
        animator?.cancel()
        animator = null
        animate().cancel()
        visibility = GONE
        alpha = 1f
        entry = null
        image = null
    }

    private fun run(interp: TimeInterpolator, dur: Long, onEnd: () -> Unit) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = dur
            interpolator = interp
            addUpdateListener {
                val p = it.animatedValue as Float
                cur.set(
                    from.left + (to.left - from.left) * p,
                    from.top + (to.top - from.top) * p,
                    from.right + (to.right - from.right) * p,
                    from.bottom + (to.bottom - from.bottom) * p
                )
                radius = (fromRadius + (toRadius - fromRadius) * p).coerceAtLeast(0f)
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) onEnd()
                }
            })
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        val e = entry ?: return
        art.draw(c, cur, radius, e, image)
    }
}
