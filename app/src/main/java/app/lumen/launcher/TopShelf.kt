package app.lumen.launcher

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.tv.TvContract
import android.net.Uri
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** One "Up Next" item an app published to the TV provider. */
class ShelfItem(
    val title: String,
    val subtitle: String?,
    val image: Bitmap?,
    val intent: Intent?,
    /** 0..1 watched, or 0 when unknown. */
    val progress: Float
)

/**
 * Reads the rows apps publish for Android TV home screens ("Watch Next" and preview
 * channels). Whether other launchers may read them is up to the TV's system software;
 * when nothing comes back, Top Shelf shows the app's artwork instead.
 * Only on-device images are loaded (Lumen has no internet permission).
 */
object TopShelfSource {
    const val PERMISSION = "com.android.providers.tv.permission.READ_EPG_DATA"
    private const val MAX_ITEMS = 8
    private const val IMAGE_W = 480

    private val PROGRAM_COLUMNS = arrayOf(
        "package_name", "title", "episode_title", "poster_art_uri", "thumbnail_uri",
        "intent_uri", "last_playback_position_millis", "duration_millis"
    )

    fun hasPermission(c: Context) = c.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Blocking; call off the main thread. */
    fun load(c: Context, pkg: String): List<ShelfItem> {
        if (!hasPermission(c)) return emptyList()
        val out = ArrayList<ShelfItem>()
        runCatching { readPrograms(c, TvContract.WatchNextPrograms.CONTENT_URI, pkg, out) }
        if (out.size < MAX_ITEMS) {
            runCatching {
                for (channelId in previewChannels(c, pkg)) {
                    if (out.size >= MAX_ITEMS) break
                    readPrograms(c, TvContract.buildPreviewProgramsUriForChannel(channelId), pkg, out)
                }
            }
        }
        return out.distinctBy { it.title + "\u0000" + (it.subtitle ?: "") }.take(MAX_ITEMS)
    }

    private fun previewChannels(c: Context, pkg: String): List<Long> {
        val ids = ArrayList<Long>()
        val proj = arrayOf("_id", "package_name", "type", "browsable")
        val cursor = runCatching {
            c.contentResolver.query(
                TvContract.Channels.CONTENT_URI, proj, "type = ?",
                arrayOf(TvContract.Channels.TYPE_PREVIEW), null
            )
        }.getOrNull() ?: return ids
        cursor.use { cur ->
            val iId = cur.getColumnIndex("_id")
            val iPkg = cur.getColumnIndex("package_name")
            val iBrowsable = cur.getColumnIndex("browsable")
            while (cur.moveToNext()) {
                if (iId < 0) break
                if (iPkg >= 0 && cur.getString(iPkg) != pkg) continue
                if (iBrowsable >= 0 && cur.getInt(iBrowsable) == 0) continue
                ids += cur.getLong(iId)
            }
        }
        return ids
    }

    private fun readPrograms(c: Context, uri: Uri, pkg: String, out: MutableList<ShelfItem>) {
        val cursor = c.contentResolver.query(uri, PROGRAM_COLUMNS, null, null, null) ?: return
        cursor.use { cur ->
            val iPkg = cur.getColumnIndex("package_name")
            val iTitle = cur.getColumnIndex("title")
            val iEpisode = cur.getColumnIndex("episode_title")
            val iPoster = cur.getColumnIndex("poster_art_uri")
            val iThumb = cur.getColumnIndex("thumbnail_uri")
            val iIntent = cur.getColumnIndex("intent_uri")
            val iPos = cur.getColumnIndex("last_playback_position_millis")
            val iDur = cur.getColumnIndex("duration_millis")
            while (cur.moveToNext() && out.size < MAX_ITEMS) {
                if (iPkg >= 0 && cur.getString(iPkg) != pkg) continue
                val title = str(cur, iTitle) ?: continue
                val pos = long(cur, iPos)
                val dur = long(cur, iDur)
                out += ShelfItem(
                    title = title,
                    subtitle = str(cur, iEpisode),
                    image = loadImage(c, str(cur, iThumb) ?: str(cur, iPoster)),
                    intent = safeIntent(str(cur, iIntent), pkg),
                    progress = if (pos > 0 && dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
                )
            }
        }
    }

    private fun str(cur: Cursor, i: Int): String? =
        if (i < 0 || cur.isNull(i)) null else cur.getString(i)?.takeIf { it.isNotBlank() }

    private fun long(cur: Cursor, i: Int): Long = if (i < 0 || cur.isNull(i)) 0L else cur.getLong(i)

    /** Only accept intents that stay inside the publishing app. */
    private fun safeIntent(uri: String?, pkg: String): Intent? {
        if (uri == null) return null
        val intent = runCatching { Intent.parseUri(uri, Intent.URI_INTENT_SCHEME) }.getOrNull() ?: return null
        intent.selector = null
        val component = intent.component
        if (component != null && component.packageName != pkg) return null
        if (component == null) intent.setPackage(pkg)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }

    private fun loadImage(c: Context, uri: String?): Bitmap? {
        if (uri == null) return null
        val u = Uri.parse(uri)
        val scheme = u.scheme ?: return null
        if (scheme != "content" && scheme != "android.resource" && scheme != "file") return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            c.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth > 0 && bounds.outWidth / (sample * 2) >= IMAGE_W) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            c.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull()
    }
}

/** A card in the Top Shelf "Up Next" row. */
@SuppressLint("ViewConstructor")
class ShelfCardView(
    context: Context,
    val item: ShelfItem,
    private val tint: Int,
    private val cardW: Int,
    private val cardH: Int,
    private val textH: Int
) : View(context) {
    private val art = ArtPainter()
    private val rect = RectF(0f, 0f, cardW.toFloat(), cardH.toFloat())
    private val radius = cardH * 0.08f
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFFFFF }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val inner = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.TEXT
        textSize = cardH * 0.13f
        typeface = Fonts.medium
    }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.TEXT
        textSize = textH * 0.42f
        typeface = Fonts.medium
    }
    private val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.TEXT_DIM
        textSize = textH * 0.34f
        typeface = Fonts.regular
    }
    private val title = TextUtils.ellipsize(item.title, titlePaint, cardW.toFloat(), TextUtils.TruncateAt.END).toString()
    private val sub = item.subtitle?.let {
        TextUtils.ellipsize(it, subPaint, cardW.toFloat(), TextUtils.TruncateAt.END).toString()
    }
    private val innerTitle = TextUtils.ellipsize(item.title, inner, cardW * 0.84f, TextUtils.TruncateAt.END).toString()
    private var amount = 0f
    private var anim: ValueAnimator? = null

    init {
        pivotX = cardW / 2f
        pivotY = cardH / 2f
        outlineProvider = null
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(cardW, cardH + textH)
    }

    fun setFocused(focused: Boolean) {
        translationZ = if (focused) 10f else 0f
        anim?.cancel()
        anim = ValueAnimator.ofFloat(amount, if (focused) 1f else 0f).apply {
            duration = if (focused) 460L else 300L
            interpolator = if (focused) Springs.focus else Springs.smooth
            addUpdateListener {
                amount = it.animatedValue as Float
                val s = 1f + 0.10f * amount
                scaleX = s
                scaleY = s
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        val k = amount.coerceIn(0f, 1f)
        if (k > 0.01f) {
            shadow.setShadowLayer((20f.dp * k).coerceAtLeast(1f), 0f, 10f.dp * k, Color.argb((160 * k).toInt(), 0, 0, 0))
            c.drawRoundRect(rect, radius, radius, shadow)
        }
        val img = item.image
        if (img != null) {
            art.drawImage(c, rect, radius, img)
        } else {
            art.drawIconTile(c, rect, radius, tint, null)
            c.drawText(innerTitle, cardW * 0.08f, cardH * 0.86f, inner)
        }
        if (item.progress > 0.01f) {
            val left = cardW * 0.06f
            val right = cardW * 0.94f
            val y = cardH - cardH * 0.07f
            val h = 3f.dp
            c.drawRoundRect(left, y - h, right, y, h, h, track)
            c.drawRoundRect(left, y - h, left + (right - left) * item.progress, y, h, h, bar)
        }
        titlePaint.alpha = (150 + 105 * k).toInt()
        c.drawText(title, 0f, cardH + textH * 0.48f, titlePaint)
        sub?.let { c.drawText(it, 0f, cardH + textH * 0.88f, subPaint) }
    }
}

/**
 * The area above the top row. Shows the focused app's "Up Next" rows when available,
 * otherwise a hero with the app's artwork and name.
 */
@SuppressLint("ViewConstructor")
class TopShelfView(
    context: Context,
    private val screenW: Int,
    private val screenH: Int,
    shelfH: Int,
    private val marginX: Int,
    private val gap: Int
) : FrameLayout(context) {

    private val content = FrameLayout(context).apply { clipChildren = false }
    private val hero = FrameLayout(context).apply { clipChildren = false }
    private val heroArt = ArtView(context)
    private val heroText = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val heroTitle = TextView(context)
    private val heroSub = TextView(context)
    private val cardsGroup = FrameLayout(context).apply { clipChildren = false }
    private val cardsLabel = TextView(context)
    private val cardsLayer = FrameLayout(context).apply { clipChildren = false }

    private val topPad = (screenH * 0.11f).toInt()
    private val artW = (screenW * 0.30f).toInt()
    private val artH = artW * 9 / 16
    private val cardW = (screenW * 0.205f).toInt()
    private val cardH = cardW * 9 / 16
    private val cardTextH = (screenH * 0.075f).toInt()

    /** What the shelf is showing, or about to show once the cross-fade finishes. */
    private var targetKey: String? = null
    private var targetIsItems = false
    private var targetItems: List<ShelfItem>? = null
    private var showingItems = false
    private val cards = ArrayList<ShelfCardView>()
    var focusedCard = -1
        private set

    val itemCount: Int get() = if (showingItems) cards.size else 0

    init {
        clipChildren = false
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, shelfH))

        // Hero: artwork on the left, name on the right, vertically centred in the shelf.
        val artTop = topPad + (shelfH - topPad - artH) / 2 - (screenH * 0.015f).toInt()
        hero.addView(heroArt, LayoutParams(artW, artH).apply {
            leftMargin = marginX
            topMargin = artTop
        })
        heroTitle.apply {
            setTextColor(Palette.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, screenH * 0.058f)
            typeface = Fonts.light
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        heroSub.apply {
            setTextColor(Palette.TEXT_DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, screenH * 0.024f)
            typeface = Fonts.regular
            setPadding(0, (screenH * 0.012f).toInt(), 0, 0)
        }
        heroText.addView(heroTitle)
        heroText.addView(heroSub)
        val textLeft = marginX + artW + (screenW * 0.035f).toInt()
        hero.addView(heroText, LayoutParams(screenW - textLeft - marginX, artH).apply {
            leftMargin = textLeft
            topMargin = artTop
        })
        heroText.gravity = Gravity.CENTER_VERTICAL
        content.addView(hero, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Up Next row.
        cardsLabel.apply {
            setTextColor(Palette.TEXT_DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, screenH * 0.022f)
            typeface = Fonts.medium
            letterSpacing = 0.08f
            setAllCaps(true)
            text = context.getString(R.string.up_next)
        }
        cardsGroup.addView(cardsLabel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = marginX
            topMargin = topPad
        })
        cardsGroup.addView(cardsLayer, LayoutParams(LayoutParams.MATCH_PARENT, cardH + cardTextH).apply {
            topMargin = topPad + (screenH * 0.045f).toInt()
        })
        cardsGroup.visibility = GONE
        content.addView(cardsGroup, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun showHero(e: AppEntry, subtitle: String) {
        if (!targetIsItems && targetKey == e.key) return
        targetKey = e.key
        targetIsItems = false
        swap {
            showingItems = false
            focusedCard = -1
            heroArt.entry = e
            heroTitle.text = e.label
            heroSub.text = subtitle
            hero.visibility = VISIBLE
            cardsGroup.visibility = GONE
            cardsLayer.removeAllViews()
            cards.clear()
        }
    }

    fun showItems(e: AppEntry, items: List<ShelfItem>) {
        if (targetIsItems && targetKey == e.key && targetItems === items) return
        targetItems = items
        targetKey = e.key
        targetIsItems = true
        swap {
            showingItems = true
            focusedCard = -1
            cardsLayer.removeAllViews()
            cards.clear()
            cardsLayer.translationX = 0f
            items.forEachIndexed { i, item ->
                val v = ShelfCardView(context, item, e.tint, cardW, cardH, cardTextH)
                v.translationX = (marginX + i * (cardW + gap)).toFloat()
                cardsLayer.addView(v, LayoutParams(cardW, cardH + cardTextH))
                cards += v
            }
            hero.visibility = GONE
            cardsGroup.visibility = VISIBLE
        }
    }

    fun focusCard(i: Int) {
        if (cards.isEmpty()) return
        val idx = i.coerceIn(0, cards.lastIndex)
        if (focusedCard in cards.indices && focusedCard != idx) cards[focusedCard].setFocused(false)
        focusedCard = idx
        cards[idx].setFocused(true)
        // Keep the focused card in view: scroll once it passes the 3rd visible slot.
        val shift = (idx - 2).coerceAtLeast(0) * (cardW + gap)
        cardsLayer.animate().translationX(-shift.toFloat()).setInterpolator(Springs.move).setDuration(480L).start()
    }

    fun clearCardFocus() {
        if (focusedCard in cards.indices) cards[focusedCard].setFocused(false)
        focusedCard = -1
        cardsLayer.animate().translationX(0f).setInterpolator(Springs.move).setDuration(480L).start()
    }

    fun focusedItem(): ShelfItem? = cards.getOrNull(focusedCard)?.item

    fun nudgeFocused() {
        cards.getOrNull(focusedCard)?.let { v ->
            v.animate().translationY(-8f.dp).setDuration(90L).withEndAction {
                v.animate().translationY(0f).setInterpolator(Springs.focus).setDuration(260L).start()
            }.start()
        }
    }

    private fun swap(change: () -> Unit) {
        content.animate().cancel()
        content.animate().alpha(0f).setDuration(90L).withEndAction {
            change()
            content.animate().alpha(1f).setDuration(180L).start()
        }.start()
    }
}
