package app.lumen.launcher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.Executors

/**
 * Optional "Lumen helper" (an accessibility service the user turns on).
 *
 *  - Home pressed inside an app → takes one small snapshot of that app (Android 11+) for Recents.
 *  - Home pressed twice quickly → opens Recents, wherever you are.
 *  - The TV's own home screen appears → brings Lumen back (can be turned off in settings).
 *
 * It never consumes key presses and never reads text or controls in other apps.
 */
class HelperService : AccessibilityService() {

    companion object {
        const val DOUBLE_PRESS_MS = 500L
        private const val TAG = "LumenHelper"

        @Volatile
        var instance: HelperService? = null
            private set

        fun isEnabled(c: Context): Boolean {
            if (instance != null) return true
            val enabled = Settings.Secure.getString(
                c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val me = ComponentName(c, HelperService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }

        /** True on Android 11+, where the helper can take snapshots. */
        val canSnapshot: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    }

    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var prefs: Prefs
    private var homePackages: Set<String> = emptySet()
    /** Activity classes that ARE the stock home screen (not other screens in the same package). */
    private var homeActivities: Set<String> = emptySet()
    private var foreground: String? = null
    private var lastHomeDown = 0L
    private var lastRedirect = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        prefs = Prefs(this)
        refreshHomeActivities()
        Log.i(TAG, "connected, snapshots=$canSnapshot")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        worker.shutdown()
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        when {
            pkg == packageName -> foreground = pkg
            pkg in homePackages -> {
                foreground = pkg
                val cls = e.className?.toString()
                if (cls != null && cls in homeActivities) maybeRedirect()
            }
            Launchables.isLaunchable(this, pkg) -> {
                if (foreground != pkg) RecentsStore.get(this).touch(pkg)
                foreground = pkg
            }
        }
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        val e = event ?: return false
        if (e.keyCode != KeyEvent.KEYCODE_HOME || e.action != KeyEvent.ACTION_DOWN || e.repeatCount != 0) {
            return false
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastHomeDown < DOUBLE_PRESS_MS) {
            lastHomeDown = 0L
            openRecents()
        } else {
            lastHomeDown = now
            val fg = foreground
            Log.i(TAG, "Home in $fg")
            if (fg != null && fg != packageName && fg !in homePackages) {
                RecentsStore.get(this).touch(fg)
                capture(fg)
            }
        }
        return false // never swallow Home; the system still handles it
    }

    /** The system acts on Home when the key is released, so the app is still on screen here. */
    private fun capture(pkg: String) {
        if (!canSnapshot) return
        runCatching {
            takeScreenshot(Display.DEFAULT_DISPLAY, worker, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    try {
                        val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: return
                        val sw = hw.copy(Bitmap.Config.ARGB_8888, false)
                        hw.recycle()
                        if (sw == null) return
                        // Wide enough to fill Home's background without looking blocky.
                        val w = 1280
                        val h = (w.toFloat() * sw.height / sw.width).toInt().coerceAtLeast(1)
                        val small = Bitmap.createScaledBitmap(sw, w, h, true)
                        if (small !== sw) sw.recycle()
                        // Protected video (Netflix etc.) captures as black: fall back to artwork.
                        if (Images.isMostlyBlack(small)) {
                            Log.i(TAG, "snapshot of $pkg is black (protected video), skipped")
                            return
                        }
                        RecentsStore.get(this@HelperService).putSnapshot(pkg, small)
                        Log.i(TAG, "snapshot of $pkg saved")
                    } catch (t: Throwable) {
                        // A missing snapshot only means Recents shows the app's artwork.
                        Log.w(TAG, "snapshot of $pkg failed", t)
                    } finally {
                        buffer.close()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "screenshot of $pkg failed, code $errorCode")
                }
            })
        }
    }

    private fun openRecents() {
        val intent = Intent(this, HomeActivity::class.java)
            .setAction(HomeActivity.ACTION_SHOW_RECENTS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Let the system finish its own Home handling first.
        ui.postDelayed({ runCatching { startActivity(intent) } }, 120L)
    }

    private fun maybeRedirect() {
        if (!prefs.homeRedirect) return
        val now = SystemClock.uptimeMillis()
        if (now - lastRedirect < 700L) return
        lastRedirect = now
        val intent = Intent(this, HomeActivity::class.java)
            .putExtra(HomeActivity.EXTRA_REDIRECT, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
    }

    /** Other home screens (the TV's stock launcher), excluding Settings/setup fallbacks. */
    private fun refreshHomeActivities() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val infos = packageManager.queryIntentActivities(intent, 0)
            .map { it.activityInfo }
            .filter {
                val p = it.packageName
                p != packageName && p != "android" &&
                    !p.contains("settings", ignoreCase = true) &&
                    !p.contains("setupwizard", ignoreCase = true) &&
                    !p.contains("provision", ignoreCase = true)
            }
        homePackages = infos.map { it.packageName }.toSet()
        // Window events report the activity class; aliases report their target activity.
        homeActivities = infos.flatMap { listOfNotNull(it.name, it.targetActivity) }.toSet()
    }
}
