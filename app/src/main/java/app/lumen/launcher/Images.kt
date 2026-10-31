package app.lumen.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object Images {

    /** Draw a drawable into a w×h bitmap, scaled to cover (crop, never stretch). */
    fun renderCover(d: Drawable, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val iw = d.intrinsicWidth
        val ih = d.intrinsicHeight
        if (iw <= 0 || ih <= 0) {
            d.setBounds(0, 0, w, h)
        } else {
            val s = max(w / iw.toFloat(), h / ih.toFloat())
            val dw = (iw * s).roundToInt()
            val dh = (ih * s).roundToInt()
            val l = (w - dw) / 2
            val t = (h - dh) / 2
            d.setBounds(l, t, l + dw, t + dh)
        }
        d.draw(c)
        return bmp
    }

    /** Square render for icons. */
    fun renderSquare(d: Drawable, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, size, size)
        d.draw(c)
        return bmp
    }

    /** A pleasant, slightly muted dominant colour, used behind icons and for fallbacks. */
    fun dominantColor(b: Bitmap?): Int {
        if (b == null) return Palette.FALLBACK_TINT
        val n = 24
        val s = Bitmap.createScaledBitmap(b, n, n, true)
        var r = 0.0
        var g = 0.0
        var bl = 0.0
        var wSum = 0.0
        val hsv = FloatArray(3)
        for (y in 0 until n) for (x in 0 until n) {
            val p = s.getPixel(x, y)
            if (Color.alpha(p) < 128) continue
            Color.colorToHSV(p, hsv)
            val w = 0.12 + hsv[1] * hsv[2]
            r += Color.red(p) * w
            g += Color.green(p) * w
            bl += Color.blue(p) * w
            wSum += w
        }
        if (s !== b) s.recycle()
        if (wSum == 0.0) return Palette.FALLBACK_TINT
        val c = Color.rgb((r / wSum).toInt(), (g / wSum).toInt(), (bl / wSum).toInt())
        Color.colorToHSV(c, hsv)
        hsv[1] = min(hsv[1], 0.75f)
        hsv[2] = hsv[2].coerceIn(0.28f, 0.55f)
        return Color.HSVToColor(hsv)
    }

    /**
     * Small, blurred, darkened version of an app's artwork. Shown full-screen behind the
     * Top Shelf (bilinear upscaling of a tiny blurred image looks like frosted glass).
     */
    fun backdrop(banner: Bitmap?, icon: Bitmap?, tint: Int): Bitmap {
        val w = 128
        val h = 72
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(tint)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        when {
            banner != null -> c.drawBitmap(banner, null, Rect(0, 0, w, h), paint)
            icon != null -> c.drawBitmap(icon, null, Rect(w / 2 - 20, h / 2 - 20, w / 2 + 20, h / 2 + 20), paint)
        }
        blurInPlace(bmp, radius = 4, passes = 3, dim = 0.78f)
        return bmp
    }

    /** Box blur (3 passes ≈ gaussian) on a small mutable bitmap, optionally dimming it. */
    fun blurInPlace(bmp: Bitmap, radius: Int, passes: Int, dim: Float = 1f) {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val tmp = IntArray(px.size)
        repeat(passes) {
            boxPass(px, tmp, w, h, radius, horizontal = true)
            boxPass(tmp, px, w, h, radius, horizontal = false)
        }
        if (dim < 1f) {
            for (i in px.indices) {
                val p = px[i]
                px[i] = (0xFF shl 24) or
                    (((p shr 16 and 0xFF) * dim).toInt() shl 16) or
                    (((p shr 8 and 0xFF) * dim).toInt() shl 8) or
                    ((p and 0xFF) * dim).toInt()
            }
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
    }

    private fun boxPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val div = 2 * r + 1
        for (line in 0 until lines) {
            fun idx(i: Int): Int {
                val k = i.coerceIn(0, len - 1)
                return if (horizontal) line * w + k else k * w + line
            }
            var sr = 0
            var sg = 0
            var sb = 0
            for (i in -r..r) {
                val p = src[idx(i)]
                sr += p shr 16 and 0xFF
                sg += p shr 8 and 0xFF
                sb += p and 0xFF
            }
            for (i in 0 until len) {
                dst[idx(i)] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val pOut = src[idx(i - r)]
                val pIn = src[idx(i + r + 1)]
                sr += (pIn shr 16 and 0xFF) - (pOut shr 16 and 0xFF)
                sg += (pIn shr 8 and 0xFF) - (pOut shr 8 and 0xFF)
                sb += (pIn and 0xFF) - (pOut and 0xFF)
            }
        }
    }

    /** Average brightness (0..1) of the region right of [fromX] and above [toY] (fractions of the size). */
    fun luminance(b: Bitmap, fromX: Float = 0f, toY: Float = 1f): Float {
        val steps = 8
        val x0 = (b.width - 1) * fromX
        val y1 = (b.height - 1) * toY
        var sum = 0f
        for (yi in 0 until steps) for (xi in 0 until steps) {
            val x = (x0 + (b.width - 1 - x0) * xi / (steps - 1)).toInt()
            val y = (y1 * yi / (steps - 1)).toInt()
            val p = runCatching { b.getPixel(x, y) }.getOrDefault(Color.BLACK)
            sum += (Color.red(p) * 0.3f + Color.green(p) * 0.59f + Color.blue(p) * 0.11f) / 255f
        }
        return sum / (steps * steps)
    }

    /** True for an (almost) black frame, e.g. DRM-protected video in a screenshot. */
    fun isMostlyBlack(b: Bitmap): Boolean {
        val steps = 12
        var dark = 0
        var total = 0
        for (yi in 0 until steps) for (xi in 0 until steps) {
            val x = (b.width - 1) * xi / (steps - 1)
            val y = (b.height - 1) * yi / (steps - 1)
            val p = b.getPixel(x, y)
            val lum = (Color.red(p) * 3 + Color.green(p) * 6 + Color.blue(p)) / 10
            if (lum < 14) dark++
            total++
        }
        return dark >= total * 0.94
    }
}
