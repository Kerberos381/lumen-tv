package app.lumen.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import java.util.Random
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * Home backgrounds. "art" is the focused app's blurred artwork (the default); the others are
 * scenes drawn on the TV, so they need no network and no storage.
 */
object Backgrounds {
    const val ART = "art"
    val keys = listOf(ART, "aurora", "dusk", "desert", "dark")

    private const val W = 960
    private const val H = 540
    private val cache = HashMap<String, Bitmap>()

    fun label(context: Context, key: String): String = context.getString(
        when (key) {
            "aurora" -> R.string.bg_aurora
            "dusk" -> R.string.bg_dusk
            "desert" -> R.string.bg_desert
            "dark" -> R.string.bg_dark
            else -> R.string.bg_art
        }
    )

    fun next(key: String, dir: Int): String {
        val i = keys.indexOf(key).coerceAtLeast(0)
        return keys[(i + dir + keys.size) % keys.size]
    }

    /** The scene bitmap, or null for [ART]. Rendered once, then cached. */
    fun scene(key: String): Bitmap? {
        if (key == ART || key !in keys) return null
        cache[key]?.let { return it }
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val rnd = Random(key.hashCode().toLong())
        when (key) {
            "aurora" -> aurora(c, rnd)
            "dusk" -> dusk(c, rnd)
            "desert" -> desert(c, rnd)
            else -> dark(c)
        }
        cache[key] = bmp
        return bmp
    }

    // ---------------------------------------------------------------- scenes

    private fun aurora(c: Canvas, rnd: Random) {
        // Sky and light bands on a small canvas, blurred, then scaled up so the bands glow.
        val lw = 240
        val lh = 135
        val low = Bitmap.createBitmap(lw, lh, Bitmap.Config.ARGB_8888)
        val lc = Canvas(low)
        sky(lc, lw, lh, intArrayOf(0xFF02040C.toInt(), 0xFF061629.toInt(), 0xFF0B2A3A.toInt()), floatArrayOf(0f, 0.6f, 1f))
        val bands = intArrayOf(0xFF3CF0A8.toInt(), 0xFF2BC4D8.toInt(), 0xFF8E6BFF.toInt())
        for ((n, color) in bands.withIndex()) {
            val base = lh * (0.22f + n * 0.1f)
            val amp = lh * (0.05f + rnd.nextFloat() * 0.05f)
            val freq = 1.4f + rnd.nextFloat() * 1.2f
            val phase = rnd.nextFloat() * 6f
            val thick = lh * (0.22f + rnd.nextFloat() * 0.12f)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            for (x in 0 until lw) {
                val t = x / lw.toFloat()
                val top = base + amp * sin(t * freq * 2 * PI.toFloat() + phase)
                val strength = 0.35f + 0.65f * (0.5f + 0.5f * sin(t * 5.3f + phase * 1.7f))
                paint.shader = LinearGradient(
                    0f, top, 0f, top + thick,
                    intArrayOf(Colors.withAlpha(color, 0f), Colors.withAlpha(color, 0.75f * strength), Colors.withAlpha(color, 0f)),
                    floatArrayOf(0f, 0.25f, 1f), Shader.TileMode.CLAMP
                )
                lc.drawRect(x.toFloat(), top, x + 1f, top + thick, paint)
            }
        }
        Images.blurInPlace(low, radius = 3, passes = 2)
        c.drawBitmap(low, null, Rect(0, 0, W, H), Paint(Paint.FILTER_BITMAP_FLAG))
        low.recycle()
        stars(c, rnd, count = 180, maxY = H * 0.75f)
        ridge(c, rnd, base = H * 0.80f, amp = H * 0.09f, color = 0xFF03060A.toInt(), jagged = true)
    }

    private fun dusk(c: Canvas, rnd: Random) {
        val horizon = H * 0.62f
        val sky = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, horizon,
                intArrayOf(0xFF16113A.toInt(), 0xFF5A2A5E.toInt(), 0xFFD9744A.toInt(), 0xFFF4A15C.toInt()),
                floatArrayOf(0f, 0.5f, 0.88f, 1f), Shader.TileMode.CLAMP
            )
        }
        c.drawRect(0f, 0f, W.toFloat(), horizon, sky)
        stars(c, rnd, count = 40, maxY = horizon * 0.35f)
        val sunX = W * 0.62f
        glow(c, sunX, horizon, H * 0.55f, 0x66FFB070)
        c.save()
        c.clipRect(0f, 0f, W.toFloat(), horizon)
        c.drawCircle(sunX, horizon + H * 0.01f, H * 0.065f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFDDA6.toInt() })
        c.restore()
        val sea = Paint().apply {
            shader = LinearGradient(
                0f, horizon, 0f, H.toFloat(),
                intArrayOf(0xFF4A2A4E.toInt(), 0xFF1C1430.toInt(), 0xFF0A0816.toInt()),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
            )
        }
        c.drawRect(0f, horizon, W.toFloat(), H.toFloat(), sea)
        // Sun glints on the water, wider and fainter towards the viewer.
        val glint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until 70) {
            val t = (i / 70f).pow(1.6f)
            val y = horizon + 2f + (H - horizon) * t
            val half = W * (0.02f + 0.16f * t) * (0.4f + rnd.nextFloat())
            val x = sunX + (rnd.nextFloat() - 0.5f) * W * 0.1f * (1f + 2f * t)
            glint.color = Colors.withAlpha(0xFFFFC890.toInt(), 0.55f * (1f - t) + 0.05f)
            c.drawRoundRect(x - half, y, x + half, y + 1.2f + 2.5f * t, 2f, 2f, glint)
        }
    }

    private fun desert(c: Canvas, rnd: Random) {
        sky(c, W, H, intArrayOf(0xFF03040D.toInt(), 0xFF0C1330.toInt(), 0xFF2A2352.toInt()), floatArrayOf(0f, 0.55f, 0.8f))
        stars(c, rnd, count = 260, maxY = H * 0.7f)
        val mx = W * 0.78f
        val my = H * 0.2f
        glow(c, mx, my, H * 0.22f, 0x33E8E4FF)
        c.drawCircle(mx, my, H * 0.045f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF2EEDC.toInt() })
        ridge(c, rnd, base = H * 0.70f, amp = H * 0.035f, color = 0xFF221B3E.toInt(), jagged = false)
        ridge(c, rnd, base = H * 0.78f, amp = H * 0.045f, color = 0xFF151129.toInt(), jagged = false)
        ridge(c, rnd, base = H * 0.88f, amp = H * 0.05f, color = 0xFF0A0816.toInt(), jagged = false)
    }

    private fun dark(c: Canvas) {
        sky(c, W, H, intArrayOf(0xFF111319.toInt(), 0xFF07080B.toInt()), null)
        glow(c, W * 0.28f, H * 0.18f, H * 0.9f, 0x1A4A6CFF)
        glow(c, W * 0.85f, H * 0.95f, H * 0.7f, 0x14B04AFF)
    }

    // ---------------------------------------------------------------- helpers

    private fun sky(c: Canvas, w: Int, h: Int, colors: IntArray, positions: FloatArray?) {
        val p = Paint().apply { shader = LinearGradient(0f, 0f, 0f, h.toFloat(), colors, positions, Shader.TileMode.CLAMP) }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    }

    private fun stars(c: Canvas, rnd: Random, count: Int, maxY: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        repeat(count) {
            val r = 0.5f + rnd.nextFloat().pow(3f) * 1.6f
            p.color = Color.argb(40 + rnd.nextInt(190), 255, 255, 255)
            c.drawCircle(rnd.nextFloat() * W, rnd.nextFloat().pow(1.3f) * maxY, r, p)
        }
    }

    private fun glow(c: Canvas, x: Float, y: Float, r: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(x, y, r, color, color and 0x00FFFFFF, Shader.TileMode.CLAMP)
        }
        c.drawCircle(x, y, r, p)
    }

    /** A hill or mountain line across the full width, filled down to the bottom. */
    private fun ridge(c: Canvas, rnd: Random, base: Float, amp: Float, color: Int, jagged: Boolean) {
        val f1 = 1f + rnd.nextFloat() * 1.5f
        val f2 = 3f + rnd.nextFloat() * 3f
        val p1 = rnd.nextFloat() * 6f
        val p2 = rnd.nextFloat() * 6f
        val path = Path().apply { moveTo(0f, H.toFloat()) }
        val step = if (jagged) 6 else 8
        var x = 0
        while (x <= W) {
            val t = x / W.toFloat() * 2 * PI.toFloat()
            var y = base - amp * (sin(t * f1 + p1) + 0.45f * sin(t * f2 + p2))
            if (jagged) y -= (rnd.nextFloat() - 0.5f) * amp * 0.25f
            path.lineTo(x.toFloat(), y)
            x += step
        }
        path.lineTo(W.toFloat(), H.toFloat())
        path.close()
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
    }
}
