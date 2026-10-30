package app.lumen.launcher

import android.content.res.Resources
import android.graphics.Color
import android.graphics.Typeface

/** dp → px using the display density. */
val Int.dp: Int get() = (this * Resources.getSystem().displayMetrics.density + 0.5f).toInt()
val Float.dp: Float get() = this * Resources.getSystem().displayMetrics.density

object Palette {
    const val BG = 0xFF0B0C10.toInt()
    const val TEXT = 0xFFF5F5F7.toInt()
    const val TEXT_DIM = 0xA6F5F5F7.toInt()
    const val TEXT_FAINT = 0x66F5F5F7
    const val PANEL = 0xF22A2B30.toInt()
    const val PANEL_ROW = 0xFFF5F5F7.toInt()
    const val PANEL_ROW_TEXT = 0xFF111114.toInt()
    const val ACCENT = 0xFF0A84FF.toInt()
    const val FALLBACK_TINT = 0xFF2C2C2E.toInt()
}

object Fonts {
    val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
}

object Colors {
    /** Linear blend between two opaque colours, t in 0..1. */
    fun blend(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt()
        )
    }

    fun lighten(c: Int, t: Float) = blend(c, Color.WHITE, t)
    fun darken(c: Int, t: Float) = blend(c, Color.BLACK, t)

    fun withAlpha(c: Int, alpha: Float): Int =
        Color.argb((alpha.coerceIn(0f, 1f) * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))
}
