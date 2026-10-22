package aio.app.mdmclient.dpc.device

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-app usage for the Standard client (0.6.0; dashboard Resource use card, A1, 22 Oct
 * 2026). What a Device Owner can see of other apps: foreground time and launches (usage
 * access, granted by the adb enrolment's GET_USAGE_STATS appop) and Wi-Fi bytes per app
 * (a Device Owner gets device-wide network stats). Memory, CPU and battery of other apps
 * are closed to a normal app, so they are left out and the dashboard says so.
 *
 * Window [last report, now], moved only on a 200, capped at a day; POST /api/v1/app-usage.
 */
object AppUsageReport {
    private const val TAG = "AppUsageReport"
    private const val PREFS = "app_usage"
    private const val K_LAST = "last_to_ms"
    const val INTERVAL_MS = 15 * 60 * 1000L
    private const val MAX_WINDOW_MS = 24 * 60 * 60 * 1000L

    /** The window's summary, and its end to remember once the server took it. */
    fun build(ctx: Context, serial: String): Pair<JSONObject, Long> {
        val p = ctx.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        var from = p.getLong(K_LAST, now - INTERVAL_MS)
        if (from >= now) from = now - INTERVAL_MS
        if (now - from > MAX_WINDOW_MS) from = now - MAX_WINDOW_MS
        val apps = HashMap<String, JSONObject>()
        runCatching { screenTime(ctx, from, now, apps) }.onFailure { Log.w(TAG, "screen time: ${it.message}") }
        runCatching { network(ctx, from, now, apps) }.onFailure { Log.w(TAG, "network: ${it.message}") }
        val pm = ctx.packageManager
        val arr = JSONArray()
        for ((pkg, a) in apps) {
            if (arr.length() >= 400) break
            a.put("package", pkg)
            a.put("system", isSystem(pm, pkg))
            arr.put(a)
        }
        val body = JSONObject().put("serial", serial).put("from_ms", from).put("to_ms", now).put("apps", arr)
        return body to now
    }

    fun sent(ctx: Context, to: Long) {
        ctx.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(K_LAST, to).apply()
    }

    private fun app(apps: HashMap<String, JSONObject>, pkg: String) = apps.getOrPut(pkg) { JSONObject() }
    private fun add(a: JSONObject, k: String, v: Long) { a.put(k, a.optLong(k, 0) + v) }

    private fun screenTime(ctx: Context, from: Long, to: Long, apps: HashMap<String, JSONObject>) {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return
        val ev = usm.queryEvents(from - 3 * 60 * 60 * 1000L, to)
        val e = UsageEvents.Event()
        var fg: String? = null
        var since = 0L
        fun close(at: Long) {
            val pkg = fg ?: return
            val s = maxOf(since, from); val t = minOf(at, to)
            if (t > s) add(app(apps, pkg), "fg_ms", t - s)
        }
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    val pkg = e.packageName
                    if (pkg != fg) {
                        close(e.timeStamp)
                        if (e.timeStamp >= from) add(app(apps, pkg), "launches", 1)
                        fg = pkg; since = e.timeStamp
                    }
                }
                UsageEvents.Event.ACTIVITY_PAUSED -> if (e.packageName == fg) { close(e.timeStamp); fg = null }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> { close(e.timeStamp); fg = null }
            }
        }
        close(to)
    }

    private fun network(ctx: Context, from: Long, to: Long, apps: HashMap<String, JSONObject>) {
        val nsm = ctx.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager ?: return
        val pm = ctx.packageManager
        val byUid = HashMap<Int, LongArray>()
        nsm.querySummary(ConnectivityManager.TYPE_WIFI, null, from, to).use { ns ->
            val b = NetworkStats.Bucket()
            while (ns.hasNextBucket()) {
                ns.getNextBucket(b)
                val v = byUid.getOrPut(b.uid) { LongArray(2) }
                v[0] += b.rxBytes; v[1] += b.txBytes
            }
        }
        for ((uid, v) in byUid) {
            if (v[0] + v[1] == 0L) continue
            val pkg = when {
                uid == 1000 -> "android"
                uid == 0 -> "root"
                uid < 0 -> null
                else -> pm.getPackagesForUid(uid)?.firstOrNull() ?: pm.getNameForUid(uid)
            } ?: continue
            val a = app(apps, pkg)
            add(a, "rx_bytes", v[0]); add(a, "tx_bytes", v[1])
        }
    }

    private fun isSystem(pm: PackageManager, pkg: String): Boolean {
        if (pkg == "android" || pkg == "root") return true
        return runCatching {
            val ai = pm.getApplicationInfo(pkg, 0)
            ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        }.getOrDefault(false)
    }
}
