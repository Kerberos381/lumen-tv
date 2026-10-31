package app.lumen.launcher

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Lumen's home screen.
 *
 * Layout (top to bottom): Top Shelf for the focused top-row app, the top row on frosted
 * glass, then the rest of the apps in a 5-column grid. Focus is handled here (not by
 * Android's focus search) so movement, scrolling and springs stay predictable on a remote.
 */
class HomeActivity : Activity() {

    companion object {
        const val ACTION_SHOW_RECENTS = "app.lumen.launcher.action.SHOW_RECENTS"
        const val EXTRA_REDIRECT = "app.lumen.launcher.extra.REDIRECT"
        const val COLS = 5
        private const val REQ_SHELF = 41
        private const val LONG_PRESS_MS = 550L
        private const val DOUBLE_HOME_MS = 500L
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private lateinit var prefs: Prefs
    private lateinit var repo: AppRepository
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    // Geometry, in px.
    private var screenW = 0
    private var screenH = 0
    private var marginX = 0
    private var gap = 0
    private var tileW = 0
    private var tileH = 0
    private var labelH = 0
    private var pitch = 0
    private var dockTop = 0
    private var dockExtra = 0

    // Views.
    private lateinit var root: FrameLayout
    private lateinit var backA: ImageView
    private lateinit var backB: ImageView
    private var frontIsA = true
    private lateinit var heroScrim: View
    private lateinit var page: FrameLayout
    private lateinit var shelf: TopShelfView
    private lateinit var tileLayer: FrameLayout
    private lateinit var clock: LinearLayout
    private lateinit var clockFill: GradientDrawable
    private lateinit var moveHint: TextView
    private lateinit var zoom: ZoomView
    private lateinit var recents: RecentsView
    private var menu: OptionsMenuView? = null
    private var controlCenter: ControlCenterView? = null

    // State.
    private var apps = ArrayList<AppEntry>()
    private val tiles = ArrayList<TileView>()
    private var focus = 0
    private var currentRow = -1
    private var inShelf = false
    private var moving = false
    private var backdropKey: String? = null
    private var backdropLevel = 1f
    private var shelfToken = 0
    private val shelfCache = HashMap<String, List<ShelfItem>>()
    private var seenLayoutVersion = -1

    // Select key: short press opens, long press shows options.
    private var selectDown = false
    private var longFired = false
    private val longPress = Runnable {
        longFired = true
        showOptions()
    }

    // Coming back from an app.
    private var pendingReturnKey: String? = null
    private var pendingReturnSince = 0L
    private var returnIndex = -1
    private var stopped = false
    private var lastHomeIntentAt = 0L
    private var recentsOpenedAt = 0L

    private val shelfRefresh = Runnable { refreshShelf() }
    private val reloadRunnable = Runnable { reloadApps() }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Launchables.invalidate()
            ui.removeCallbacks(reloadRunnable)
            ui.postDelayed(reloadRunnable, 600L)
        }
    }

    private val recentsListener = object : RecentsView.Listener {
        override fun onOpen(entry: AppEntry, from: RectF, radius: Float, snapshot: Bitmap?) {
            recents.hide(animate = false)
            zoom.playOpen(entry, snapshot, from, radius, fullRect()) { launch(entry, null) }
        }

        override fun onDismiss(entry: AppEntry) {
            RecentsStore.get(this@HomeActivity).dismiss(entry.packageName)
            runCatching {
                getSystemService(ActivityManager::class.java)?.killBackgroundProcesses(entry.packageName)
            }
        }

        override fun onClosed() = Unit
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        repo = AppRepository(this)
        computeGeometry()
        buildViews()
        setContentView(root)
        seenLayoutVersion = prefs.layoutVersion
        reloadApps()
        registerPackageReceiver()
        if (!prefs.askedShelfPermission && !TopShelfSource.hasPermission(this)) {
            ui.postDelayed({
                prefs.askedShelfPermission = true
                runCatching { requestPermissions(arrayOf(TopShelfSource.PERMISSION), REQ_SHELF) }
            }, 1500L)
        }
    }

    override fun onStart() {
        super.onStart()
        // Returning from an app we opened: start with its art full-screen, shrink it in onResume.
        val key = pendingReturnKey ?: return
        val idx = apps.indexOfFirst { it.key == key }
        if (idx < 0 || recents.isShowing) {
            pendingReturnKey = null
            return
        }
        if (inShelf) {
            inShelf = false
            shelf.clearCardFocus()
        }
        setFocus(idx, animate = false)
        returnIndex = idx
        val e = apps[idx]
        zoom.showFull(e, RecentsStore.get(this).memorySnapshot(e.packageName, pendingReturnSince), fullRect())
    }

    override fun onResume() {
        super.onResume()
        stopped = false
        if (prefs.layoutVersion != seenLayoutVersion) {
            seenLayoutVersion = prefs.layoutVersion
            shelfCache.clear()
            reloadApps()
        }
        val i = returnIndex
        if (i >= 0) {
            returnIndex = -1
            val key = pendingReturnKey
            val since = pendingReturnSince
            pendingReturnKey = null
            // A short wait lets the helper's snapshot (taken on the Home press) arrive.
            ui.postDelayed({
                if (i !in tiles.indices || tiles[i].entry.key != key) {
                    zoom.hide()
                    return@postDelayed
                }
                val tile = tiles[i]
                zoom.updateImage(RecentsStore.get(this).memorySnapshot(tile.entry.packageName, since))
                zoom.playClose(tileRect(i), tile.radius * tile.scaleX)
                // Pick up the fresh last-screen snapshot for the background.
                scheduleShelfRefresh(600L)
            }, 110L)
        }
    }

    override fun onStop() {
        super.onStop()
        stopped = true
        ui.removeCallbacks(longPress)
        if (returnIndex < 0) zoom.hide()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(packageReceiver) }
        ui.removeCallbacksAndMessages(null)
        io.shutdown()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val now = SystemClock.uptimeMillis()
        when {
            intent.action == ACTION_SHOW_RECENTS -> showRecents()
            intent.getBooleanExtra(EXTRA_REDIRECT, false) -> Unit
            intent.hasCategory(Intent.CATEGORY_HOME) -> {
                if (now - recentsOpenedAt < 900L) return
                val isDouble = now - lastHomeIntentAt < DOUBLE_HOME_MS
                lastHomeIntentAt = now
                if (isDouble) showRecents() else if (!stopped) goToTop()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_SHELF) {
            shelfCache.clear()
            scheduleShelfRefresh(0L)
        }
    }

    // ---------------------------------------------------------------- layout

    private fun computeGeometry() {
        val dm = resources.displayMetrics
        screenW = max(dm.widthPixels, dm.heightPixels)
        screenH = min(dm.widthPixels, dm.heightPixels)
        marginX = (screenW * 0.06f).roundToInt()
        gap = (screenW * 0.024f).roundToInt()
        tileW = (screenW - 2 * marginX - (COLS - 1) * gap) / COLS
        tileH = tileW * 9 / 16
        labelH = (screenH * 0.045f).roundToInt()
        pitch = tileH + labelH + (screenH * 0.032f).roundToInt()
        dockTop = (screenH * 0.62f).roundToInt()
        dockExtra = (screenH * 0.035f).roundToInt()
    }

    private fun rowTop(r: Int): Float = (dockTop + r * pitch + (if (r >= 1) dockExtra else 0)).toFloat()

    private fun rowOf(i: Int) = i / COLS

    private fun fullRect() = RectF(0f, 0f, screenW.toFloat(), screenH.toFloat())

    private fun buildViews() {
        root = FrameLayout(this).apply {
            setBackgroundColor(Palette.BG)
            clipChildren = false
        }
        backA = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0f
        }
        backB = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0f
        }
        root.addView(backA, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(backB, FrameLayout.LayoutParams(MATCH, MATCH))
        val scrim = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x1A000000, 0x40000000, 0xCC000000.toInt())
            )
        }
        root.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))
        // Behind an app's last screen: darken the left so the app name stays readable.
        heroScrim = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xCC000000.toInt(), 0x66000000, 0x00000000)
            )
            alpha = 0f
        }
        root.addView(heroScrim, FrameLayout.LayoutParams(MATCH, MATCH))

        page = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }
        root.addView(page, FrameLayout.LayoutParams(MATCH, screenH * 3))

        shelf = TopShelfView(this, screenW, screenH, dockTop, marginX, gap)
        page.addView(shelf, FrameLayout.LayoutParams(MATCH, dockTop))

        val padX = (gap * 0.9f).roundToInt()
        val padTop = (tileH * 0.17f).roundToInt()
        val glass = GlassView(this).apply { radius = tileH * 0.22f }
        page.addView(glass, FrameLayout.LayoutParams(screenW - 2 * (marginX - padX), padTop + tileH + labelH + padTop / 2).apply {
            leftMargin = marginX - padX
            topMargin = dockTop - padTop
        })

        tileLayer = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }
        page.addView(tileLayer, FrameLayout.LayoutParams(MATCH, MATCH))

        clock = buildClockPill()
        root.addView(clock, FrameLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = (screenH * 0.04f).roundToInt()
            rightMargin = marginX
        })

        moveHint = TextView(this).apply {
            text = getString(R.string.move_hint)
            setTextColor(Palette.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, screenH * 0.024f)
            typeface = Fonts.medium
            val ph = (screenH * 0.03f).roundToInt()
            val pv = (screenH * 0.014f).roundToInt()
            setPadding(ph, pv, ph, pv)
            background = GradientDrawable().apply {
                cornerRadius = screenH * 0.04f
                setColor(Palette.PANEL)
            }
            visibility = View.GONE
        }
        root.addView(moveHint, FrameLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = (screenH * 0.06f).roundToInt()
        })

        zoom = ZoomView(this)
        root.addView(zoom, FrameLayout.LayoutParams(MATCH, MATCH))

        recents = RecentsView(this, screenW, screenH).apply { listener = recentsListener }
        root.addView(recents, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    /** Date and time on a glass pill; its tint follows the backdrop behind it. */
    private fun buildClockPill(): LinearLayout {
        clockFill = GradientDrawable().apply {
            cornerRadius = screenH * 0.04f
            setColor(0x33FFFFFF)
            setStroke(1.dp, 0x30FFFFFF)
        }
        val padH = (screenH * 0.026f).roundToInt()
        val padV = (screenH * 0.01f).roundToInt()
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = clockFill
            setPadding(padH, padV, padH, padV)
        }
        val date = TextClock(this).apply {
            format12Hour = "EEE d MMM"
            format24Hour = "EEE d MMM"
            setTextColor(Palette.TEXT_DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, screenH * 0.024f)
            typeface = Fonts.medium
        }
        val time = TextClock(this).apply {
            format12Hour = "h:mm"
            format24Hour = "H:mm"
            setTextColor(Palette.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, screenH * 0.032f)
            typeface = Fonts.regular
        }
        pill.addView(date)
        pill.addView(time, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = (screenH * 0.018f).roundToInt() })
        return pill
    }

    /** Darker glass over bright backdrops so the white text stays readable. */
    private fun tintClockPill(backdrop: Bitmap) {
        val lum = Images.luminance(backdrop, fromX = 0.6f, toY = 0.3f)
        clockFill.setColor(if (lum > 0.45f) 0x66000000 else 0x33FFFFFF)
    }

    private fun reloadApps() {
        io.execute {
            val list = runCatching { repo.loadOrdered() }.getOrDefault(emptyList())
            ui.post { if (!isDestroyed) applyApps(list) }
        }
    }

    private fun applyApps(list: List<AppEntry>) {
        val keepKey = apps.getOrNull(focus)?.key
        if (moving) {
            moving = false
            moveHint.visibility = View.GONE
        }
        tiles.forEach { it.setWiggle(false) }
        apps = ArrayList(list)
        tileLayer.removeAllViews()
        tiles.clear()
        for (e in apps) {
            val t = TileView(this, e, tileW, tileH, labelH)
            tileLayer.addView(t, FrameLayout.LayoutParams(tileW, tileH + labelH))
            tiles += t
        }
        layoutTiles(animate = false)
        val rows = (apps.size + COLS - 1) / COLS
        page.layoutParams = page.layoutParams.apply { height = (rowTop(rows) + screenH).toInt() }

        var idx = apps.indexOfFirst { it.key == keepKey }
        if (idx < 0) idx = focus.coerceIn(0, max(0, apps.lastIndex))
        focus = idx
        currentRow = -1
        if (inShelf) {
            inShelf = false
            shelf.clearCardFocus()
        }
        if (tiles.isNotEmpty()) {
            tiles[focus].setFocused(true, animate = false)
            scrollToRow(rowOf(focus), animate = false)
        }
        backdropKey = null
        scheduleShelfRefresh(0L)
    }

    private fun layoutTiles(animate: Boolean) {
        for ((i, t) in tiles.withIndex()) {
            val x = (marginX + (i % COLS) * (tileW + gap)).toFloat()
            val y = rowTop(rowOf(i))
            if (animate) {
                t.animate().translationX(x).translationY(y)
                    .setInterpolator(Springs.move).setDuration(440L).start()
            } else {
                t.animate().cancel()
                t.translationX = x
                t.translationY = y
            }
        }
    }

    /** Visual on-screen rect of a tile's artwork (includes focus scale and scroll). */
    private fun tileRect(i: Int): RectF {
        val t = tiles[i]
        val s = t.scaleX
        val cx = t.x + tileW / 2f
        val cy = t.y + tileH / 2f + page.translationY
        return RectF(cx - s * tileW / 2f, cy - s * tileH / 2f, cx + s * tileW / 2f, cy + s * tileH / 2f)
    }

    // ---------------------------------------------------------------- focus

    private fun setFocus(index: Int, animate: Boolean = true) {
        if (tiles.isEmpty()) return
        val next = index.coerceIn(0, tiles.lastIndex)
        if (next != focus && focus in tiles.indices) tiles[focus].setFocused(false, animate)
        focus = next
        tiles[focus].setFocused(true, animate)
        scrollToRow(rowOf(focus), animate)
        scheduleShelfRefresh(if (animate) 150L else 0L)
    }

    private fun scrollToRow(row: Int, animate: Boolean) {
        if (row == currentRow && animate) return
        currentRow = row
        val atTop = row == 0
        val target = if (atTop) 0f else -(rowTop(row) - screenH * 0.34f)
        backdropLevel = if (atTop) 1f else 0.42f
        val shelfAlpha = if (atTop && prefs.showTopShelf) 1f else 0f
        val clockAlpha = if (atTop) 1f else 0f
        if (animate) {
            page.animate().translationY(target).setInterpolator(Springs.move).setDuration(560L).start()
            shelf.animate().alpha(shelfAlpha).setDuration(240L).start()
            clock.animate().alpha(clockAlpha).setDuration(240L).start()
            frontBackdrop().animate().alpha(backdropLevel).setDuration(320L).start()
        } else {
            page.animate().cancel()
            page.translationY = target
            shelf.alpha = shelfAlpha
            clock.alpha = clockAlpha
            frontBackdrop().alpha = backdropLevel
        }
    }

    private fun frontBackdrop(): ImageView = if (frontIsA) backA else backB

    /** The app's last screen, shown full-screen behind the shelf when the background is its artwork. */
    private fun heroSnapshot(e: AppEntry): Bitmap? =
        if (e.isPseudo || prefs.background != Backgrounds.ART) null
        else RecentsStore.get(this).cachedSnapshot(e.packageName)

    private fun setBackdrop(e: AppEntry, snap: Bitmap? = null) {
        val scene = Backgrounds.scene(prefs.background)
        val key = when {
            scene != null -> "scene:" + prefs.background
            snap != null -> e.key + "#" + System.identityHashCode(snap)
            else -> e.key
        }
        if (backdropKey == key) return
        backdropKey = key
        val image = scene ?: snap ?: e.backdrop
        tintClockPill(image)
        val fullBleed = scene == null && snap != null
        heroScrim.animate().alpha(if (fullBleed) 1f else 0f).setDuration(420L).start()
        val incoming = if (frontIsA) backB else backA
        val outgoing = frontBackdrop()
        frontIsA = !frontIsA
        incoming.animate().cancel()
        incoming.setImageBitmap(image)
        // A real screen is much brighter than blurred artwork: dim it to sit behind Home.
        if (fullBleed) incoming.setColorFilter(0x59000000, android.graphics.PorterDuff.Mode.SRC_ATOP)
        else incoming.clearColorFilter()
        incoming.alpha = 0f
        incoming.animate().alpha(backdropLevel).setDuration(420L).start()
        outgoing.animate().alpha(0f).setDuration(420L).start()
    }

    private fun scheduleShelfRefresh(delay: Long) {
        ui.removeCallbacks(shelfRefresh)
        ui.postDelayed(shelfRefresh, delay)
    }

    private fun refreshShelf() {
        if (inShelf || moving) return
        val e = apps.getOrNull(focus) ?: return
        val snap = heroSnapshot(e)
        setBackdrop(e, snap)
        val showShelf = rowOf(focus) == 0 && prefs.showTopShelf
        if (showShelf) {
            val cached = shelfCache[e.key]
            if (!cached.isNullOrEmpty()) shelf.showItems(e, cached) else showHero(e, snap != null)
        }
        if (e.isPseudo) return

        val token = ++shelfToken
        val ctx = applicationContext
        // Snapshots not yet in memory are read from disk here.
        val wantSnap = snap == null && prefs.background == Backgrounds.ART
        io.execute {
            val diskSnap = if (wantSnap) RecentsStore.get(ctx).snapshot(e.packageName) else null
            val items = if (showShelf) TopShelfSource.load(ctx, e.packageName) else emptyList()
            ui.post {
                if (token != shelfToken || isDestroyed || inShelf) return@post
                val old = shelfCache[e.key]
                if (showShelf) shelfCache[e.key] = items
                if (apps.getOrNull(focus)?.key != e.key) return@post
                if (diskSnap != null) setBackdrop(e, diskSnap)
                if (!showShelf) return@post
                when {
                    items.isNotEmpty() && !sameItems(old, items) -> shelf.showItems(e, items)
                    items.isEmpty() && (!old.isNullOrEmpty() || diskSnap != null) -> showHero(e, snap != null || diskSnap != null)
                    else -> Unit
                }
            }
        }
    }

    private fun showHero(e: AppEntry, fullBleed: Boolean) =
        shelf.showHero(e, if (fullBleed) getString(R.string.hint_resume) else heroSubtitle(e), fullBleed)

    private fun sameItems(a: List<ShelfItem>?, b: List<ShelfItem>): Boolean =
        a != null && a.size == b.size && a.indices.all { a[it].title == b[it].title && a[it].subtitle == b[it].subtitle }

    private fun heroSubtitle(e: AppEntry): String =
        getString(if (e.key == AppRepository.KEY_LUMEN_SETTINGS) R.string.hint_open_settings else R.string.hint_open)

    // ---------------------------------------------------------------- keys

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (recents.isShowing) return recents.handleKey(event)
        controlCenter?.let { return it.handleKey(event) }
        menu?.let { return it.handleKey(event) }
        val code = event.keyCode
        val isNav = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN ||
            code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
        if (zoom.isAnimating && (isNav || isSelectKey(code))) return true
        when {
            isNav -> {
                if (event.action == KeyEvent.ACTION_DOWN) onDirection(code)
                return true
            }
            isSelectKey(code) -> {
                onSelectKey(event)
                return true
            }
            isBackKey(code) -> {
                if (event.action == KeyEvent.ACTION_UP) onBack()
                return true
            }
            code == KeyEvent.KEYCODE_MENU -> {
                if (event.action == KeyEvent.ACTION_UP) showControlCenter()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun onDirection(code: Int) {
        if (tiles.isEmpty()) return
        if (moving) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> moveBy(-1, 0)
                KeyEvent.KEYCODE_DPAD_DOWN -> moveBy(1, 0)
                KeyEvent.KEYCODE_DPAD_LEFT -> moveBy(0, -1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> moveBy(0, 1)
            }
            return
        }
        if (inShelf) {
            val i = shelf.focusedCard
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> if (i > 0) shelf.focusCard(i - 1) else shelf.nudgeFocused()
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (i < shelf.itemCount - 1) shelf.focusCard(i + 1) else shelf.nudgeFocused()
                KeyEvent.KEYCODE_DPAD_UP -> shelf.nudgeFocused()
                KeyEvent.KEYCODE_DPAD_DOWN -> leaveShelf()
            }
            return
        }
        val r = rowOf(focus)
        val c = focus % COLS
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT ->
                if (c > 0) setFocus(focus - 1) else tiles[focus].nudge(-1, 0)
            KeyEvent.KEYCODE_DPAD_RIGHT ->
                if (c < COLS - 1 && focus + 1 <= tiles.lastIndex) setFocus(focus + 1) else tiles[focus].nudge(1, 0)
            KeyEvent.KEYCODE_DPAD_UP -> when {
                r > 0 -> setFocus(focus - COLS)
                prefs.showTopShelf && shelf.itemCount > 0 -> enterShelf()
                else -> tiles[focus].nudge(0, -1)
            }
            KeyEvent.KEYCODE_DPAD_DOWN ->
                if ((r + 1) * COLS <= tiles.lastIndex) setFocus(min(focus + COLS, tiles.lastIndex))
                else tiles[focus].nudge(0, 1)
        }
    }

    private fun onSelectKey(e: KeyEvent) {
        if (e.action == KeyEvent.ACTION_DOWN) {
            if (e.repeatCount == 0) {
                selectDown = true
                longFired = false
                if (!moving && !inShelf) ui.postDelayed(longPress, LONG_PRESS_MS)
            }
        } else if (e.action == KeyEvent.ACTION_UP) {
            ui.removeCallbacks(longPress)
            val wasDown = selectDown
            selectDown = false
            if (!wasDown || longFired) {
                longFired = false
                return
            }
            if (moving) endMove() else openFocused()
        }
    }

    private fun onBack() {
        when {
            moving -> endMove()
            inShelf -> leaveShelf()
            rowOf(focus) > 0 -> setFocus((focus % COLS).coerceAtMost(tiles.lastIndex))
        }
    }

    private fun goToTop() {
        menu?.dismiss()
        controlCenter?.dismiss()
        if (recents.isShowing) recents.hide(animate = true)
        if (moving) endMove()
        if (inShelf) leaveShelf()
        if (tiles.isNotEmpty() && rowOf(focus) != 0) setFocus((focus % COLS).coerceAtMost(tiles.lastIndex))
    }

    private fun enterShelf() {
        inShelf = true
        tiles[focus].setFocused(false, animate = true)
        shelf.focusCard(0)
    }

    private fun leaveShelf() {
        inShelf = false
        shelf.clearCardFocus()
        if (focus in tiles.indices) tiles[focus].setFocused(true, animate = true)
    }

    // ---------------------------------------------------------------- opening apps

    private fun openFocused() {
        if (inShelf) {
            openShelfItem()
            return
        }
        val e = apps.getOrNull(focus) ?: return
        if (e.key == AppRepository.KEY_LUMEN_SETTINGS) {
            openLumenSettings()
            return
        }
        val t = tiles[focus]
        zoom.playOpen(e, null, tileRect(focus), t.radius * t.scaleX, fullRect()) { launch(e, null) }
    }

    private fun openShelfItem() {
        val e = apps.getOrNull(focus) ?: return
        val item = shelf.focusedItem() ?: return
        val intent = item.intent
        if (intent != null) {
            try {
                startActivity(intent, ActivityOptions.makeCustomAnimation(this, R.anim.app_enter, R.anim.home_hold).toBundle())
                RecentsStore.get(this).touch(e.packageName)
                return
            } catch (ex: Exception) {
                // Fall through to opening the app itself.
            }
        }
        launch(e, null)
    }

    private fun launch(e: AppEntry, intentOverride: Intent?) {
        val intent = intentOverride ?: e.launchIntent
        val options = ActivityOptions.makeCustomAnimation(this, R.anim.app_enter, R.anim.home_hold).toBundle()
        try {
            startActivity(intent, options)
            if (!e.isPseudo) {
                RecentsStore.get(this).touch(e.packageName)
                pendingReturnKey = e.key
                pendingReturnSince = System.currentTimeMillis()
            }
        } catch (ex: Exception) {
            zoom.hide()
            Toast.makeText(this, getString(R.string.cant_open, e.label), Toast.LENGTH_SHORT).show()
        }
    }

    private fun openLumenSettings() {
        runCatching { startActivity(Intent(this, SettingsActivity::class.java)) }
    }

    // ---------------------------------------------------------------- recents

    private fun showRecents() {
        recentsOpenedAt = SystemClock.uptimeMillis()
        if (recents.isShowing) return
        menu?.dismiss()
        controlCenter?.let { closeControlCenter() }
        if (moving) endMove()
        zoom.hide()
        returnIndex = -1
        pendingReturnKey = null

        val byPkg = LinkedHashMap<String, AppEntry>()
        for (e in apps) if (!e.isPseudo) byPkg[e.packageName] = e
        val store = RecentsStore.get(this)
        val entries = store.ordered(byPkg.keys, UsageAccess.lastUsed(this))
            .mapNotNull { byPkg[it] }
            .take(10)
        recents.show(entries)
        io.execute {
            for (e in entries) {
                val bmp = store.snapshot(e.packageName) ?: continue
                ui.post { if (recents.isShowing) recents.setSnapshot(e.packageName, bmp) }
            }
        }
    }

    // ---------------------------------------------------------------- control center

    private val controlActions = object : ControlCenterView.Actions {
        override fun showRecents() = this@HomeActivity.showRecents()
        override fun openLumenSettings() = this@HomeActivity.openLumenSettings()
        override fun backgroundChanged() {
            backdropKey = null
            apps.getOrNull(focus)?.let { setBackdrop(it) }
        }
    }

    private fun showControlCenter() {
        if (controlCenter != null || recents.isShowing || zoom.isAnimating) return
        menu?.dismiss()
        if (moving) endMove()
        val cc = ControlCenterView(this, screenW, screenH, controlActions) { closeControlCenter() }
        controlCenter = cc
        root.addView(cc, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun closeControlCenter() {
        controlCenter?.let { root.removeView(it) }
        controlCenter = null
    }

    // ---------------------------------------------------------------- long-press menu

    private fun showOptions() {
        if (inShelf || moving || menu != null || recents.isShowing) return
        val e = apps.getOrNull(focus) ?: return
        val items = ArrayList<Pair<String, () -> Unit>>()
        fun add(labelRes: Int, action: () -> Unit) {
            items.add(Pair(getString(labelRes), action))
        }
        add(R.string.opt_move) { startMove() }
        if (!e.isPseudo) {
            add(R.string.opt_hide) { hideApp(e) }
            add(R.string.opt_info) { openAppInfo(e) }
            if (!e.isSystem) add(R.string.opt_uninstall) { uninstall(e) }
        }
        val m = OptionsMenuView(this, e.label, items, screenW, screenH) { dismissMenu() }
        menu = m
        root.addView(m, FrameLayout.LayoutParams(MATCH, MATCH))
        m.anchorTo(tileRect(focus))
    }

    private fun dismissMenu() {
        menu?.let { root.removeView(it) }
        menu = null
    }

    private fun startMove() {
        if (tiles.isEmpty()) return
        moving = true
        tiles[focus].setWiggle(true)
        moveHint.visibility = View.VISIBLE
        moveHint.alpha = 0f
        moveHint.animate().alpha(1f).setDuration(200L).start()
    }

    private fun moveBy(dRow: Int, dCol: Int) {
        val r = rowOf(focus)
        val target = if (dCol != 0) focus + dCol else focus + dRow * COLS
        if (dCol != 0 && (target < 0 || target > apps.lastIndex || rowOf(target) != r)) {
            tiles[focus].nudge(dCol, 0)
            return
        }
        if (dRow != 0 && (target < 0 || rowOf(target) > rowOf(apps.lastIndex))) {
            tiles[focus].nudge(0, dRow)
            return
        }
        val dest = target.coerceIn(0, apps.lastIndex)
        if (dest == focus) {
            tiles[focus].nudge(dCol, dRow)
            return
        }
        apps.add(dest, apps.removeAt(focus))
        tiles.add(dest, tiles.removeAt(focus))
        focus = dest
        layoutTiles(animate = true)
        scrollToRow(rowOf(focus), animate = true)
    }

    private fun endMove() {
        moving = false
        if (focus in tiles.indices) tiles[focus].setWiggle(false)
        moveHint.animate().alpha(0f).setDuration(160L).withEndAction { moveHint.visibility = View.GONE }.start()
        prefs.order = apps.map { it.key }
        backdropKey = null
        scheduleShelfRefresh(0L)
    }

    private fun hideApp(e: AppEntry) {
        prefs.hidden = prefs.hidden + e.key
        prefs.order = apps.filter { it.key != e.key }.map { it.key }
        reloadApps()
        Toast.makeText(this, getString(R.string.hidden_toast, e.label), Toast.LENGTH_SHORT).show()
    }

    private fun openAppInfo(e: AppEntry) {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", e.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun uninstall(e: AppEntry) {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_DELETE, Uri.fromParts("package", e.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun registerPackageReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(packageReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(packageReceiver, filter)
        }
    }
}
