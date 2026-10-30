package app.lumen.launcher

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Process
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * Recent-app history and snapshots. Sources, merged by "last used" time:
 *  1. apps opened from Lumen,
 *  2. apps the helper saw come to the front,
 *  3. Usage access (if granted), which also covers apps opened elsewhere.
 * Snapshots are kept in memory and as small JPEGs in app-private storage.
 */
class RecentsStore private constructor(context: Context) {

    companion object {
        private const val WINDOW_MS = 3L * 24 * 60 * 60 * 1000
        private const val MAX_TRACKED = 30

        @Volatile
        private var instance: RecentsStore? = null

        fun get(context: Context): RecentsStore =
            instance ?: synchronized(this) {
                instance ?: RecentsStore(context.applicationContext).also { instance = it }
            }
    }

    private val app = context.applicationContext
    private val sp = app.getSharedPreferences("lumen_recents", Context.MODE_PRIVATE)
    private val io = Executors.newSingleThreadExecutor()
    private val dir = File(app.filesDir, "snapshots").apply { mkdirs() }

    private val used = HashMap<String, Long>()
    private val dismissed = HashMap<String, Long>()
    private val snapTimes = HashMap<String, Long>()
    private val snaps = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    init {
        load("used", used)
        load("dismissed", dismissed)
    }

    @Synchronized
    fun touch(pkg: String) {
        used[pkg] = System.currentTimeMillis()
        dismissed.remove(pkg)
        trim()
        save()
    }

    /** "Close" in Recents: hide it until it is used again. */
    @Synchronized
    fun dismiss(pkg: String) {
        dismissed[pkg] = System.currentTimeMillis()
        used.remove(pkg)
        snaps.remove(pkg)
        snapTimes.remove(pkg)
        io.execute { File(dir, "$pkg.jpg").delete() }
        save()
    }

    /** Most recent first, limited to [candidates] (apps currently on Home). */
    fun ordered(candidates: Set<String>, usage: Map<String, Long>): List<String> {
        val now = System.currentTimeMillis()
        val merged = HashMap<String, Long>()
        for ((k, v) in usage) if (k in candidates) merged[k] = v
        synchronized(this) {
            for ((k, v) in used) if (k in candidates) merged[k] = maxOf(v, merged[k] ?: 0L)
            for ((k, at) in dismissed) {
                val last = merged[k] ?: continue
                if (last <= at) merged.remove(k)
            }
        }
        return merged.entries
            .filter { now - it.value < WINDOW_MS }
            .sortedByDescending { it.value }
            .map { it.key }
    }

    fun putSnapshot(pkg: String, bmp: Bitmap) {
        val now = System.currentTimeMillis()
        synchronized(this) {
            snaps.put(pkg, bmp)
            snapTimes[pkg] = now
        }
        io.execute {
            runCatching {
                FileOutputStream(File(dir, "$pkg.jpg")).use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            }
        }
    }

    /** Snapshot already in memory, taken after [notBefore] (wall clock ms). Main-thread safe. */
    @Synchronized
    fun memorySnapshot(pkg: String, notBefore: Long): Bitmap? {
        val t = snapTimes[pkg] ?: return null
        if (t < notBefore) return null
        return snaps.get(pkg)
    }

    /** Snapshot from memory or disk. May hit the disk: call off the main thread. */
    fun snapshot(pkg: String): Bitmap? {
        synchronized(this) { snaps.get(pkg)?.let { return it } }
        val f = File(dir, "$pkg.jpg")
        if (!f.exists()) return null
        val bmp = runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull() ?: return null
        synchronized(this) {
            snaps.put(pkg, bmp)
            snapTimes[pkg] = f.lastModified()
        }
        return bmp
    }

    private fun trim() {
        if (used.size <= MAX_TRACKED) return
        used.entries.sortedBy { it.value }.take(used.size - MAX_TRACKED).map { it.key }.forEach { used.remove(it) }
    }

    private fun load(key: String, into: HashMap<String, Long>) {
        val raw = sp.getString(key, "") ?: ""
        for (line in raw.split('\n')) {
            val i = line.lastIndexOf('|')
            if (i <= 0) continue
            val t = line.substring(i + 1).toLongOrNull() ?: continue
            into[line.substring(0, i)] = t
        }
    }

    private fun save() {
        sp.edit()
            .putString("used", used.entries.joinToString("\n") { "${it.key}|${it.value}" })
            .putString("dismissed", dismissed.entries.joinToString("\n") { "${it.key}|${it.value}" })
            .apply()
    }
}

/** "Usage access": lets Recents include apps opened outside Lumen. */
object UsageAccess {
    fun granted(c: Context): Boolean {
        val ops = c.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        } else {
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        }
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            c.checkSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }

    fun lastUsed(c: Context): Map<String, Long> {
        if (!granted(c)) return emptyMap()
        val usm = c.getSystemService(UsageStatsManager::class.java) ?: return emptyMap()
        val now = System.currentTimeMillis()
        return runCatching {
            usm.queryAndAggregateUsageStats(now - 3L * 24 * 60 * 60 * 1000, now)
                .mapValues { it.value.lastTimeUsed }
                .filterValues { it > 0 }
        }.getOrDefault(emptyMap())
    }
}

/** Cached "does this package have a launcher entry?" lookups. Main thread only. */
object Launchables {
    private val cache = HashMap<String, Boolean>()

    fun isLaunchable(c: Context, pkg: String): Boolean = cache.getOrPut(pkg) {
        val pm = c.packageManager
        pm.getLeanbackLaunchIntentForPackage(pkg) != null || pm.getLaunchIntentForPackage(pkg) != null
    }

    fun invalidate() = cache.clear()
}
