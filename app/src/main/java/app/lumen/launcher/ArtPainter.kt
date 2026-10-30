package app.lumen.launcher

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.max
import kotlin.math.min

/**
 * Draws an app's artwork into a rounded rect: the banner (cover-scaled), a snapshot, or,
 * for apps without a banner, the icon on a gradient of its dominant colour.
 * Shared by tiles, the zoom transition, Top Shelf and Recents so they all match.
 */
class ArtPainter {
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private val iconRect = RectF()

    private var shaderBitmap: Bitmap? = null
    private var shader: BitmapShader? = null

    private var gradTint = 0
    private var gradTop = Float.NaN
    private var gradBottom = Float.NaN

    /** Largest on-screen icon size, so small icons are never blown up into mush. */
    var maxIconSize = 180f.dp

    fun draw(c: Canvas, r: RectF, radius: Float, entry: AppEntry, override: Bitmap? = null) {
        val image = override ?: entry.banner
        if (image != null) drawImage(c, r, radius, image)
        else drawIconTile(c, r, radius, entry.tint, entry.icon)
    }

    fun drawImage(c: Canvas, r: RectF, radius: Float, image: Bitmap) {
        if (image !== shaderBitmap) {
            shader = BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            shaderBitmap = image
            imagePaint.shader = shader
        }
        val s = max(r.width() / image.width, r.height() / image.height)
        matrix.setScale(s, s)
        matrix.postTranslate(
            r.left + (r.width() - image.width * s) / 2f,
            r.top + (r.height() - image.height * s) / 2f
        )
        shader?.setLocalMatrix(matrix)
        c.drawRoundRect(r, radius, radius, imagePaint)
    }

    fun drawIconTile(c: Canvas, r: RectF, radius: Float, tint: Int, icon: Bitmap?) {
        if (tint != gradTint || r.top != gradTop || r.bottom != gradBottom) {
            fillPaint.shader = LinearGradient(
                0f, r.top, 0f, r.bottom,
                Colors.lighten(tint, 0.10f), Colors.darken(tint, 0.30f),
                Shader.TileMode.CLAMP
            )
            gradTint = tint
            gradTop = r.top
            gradBottom = r.bottom
        }
        c.drawRoundRect(r, radius, radius, fillPaint)
        if (icon != null) {
            val size = min(min(r.height() * 0.52f, r.width() * 0.30f), maxIconSize)
            iconRect.set(
                r.centerX() - size / 2f, r.centerY() - size / 2f,
                r.centerX() + size / 2f, r.centerY() + size / 2f
            )
            c.drawBitmap(icon, null, iconRect, iconPaint)
        }
    }
}
