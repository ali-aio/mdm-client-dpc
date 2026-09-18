package com.skorra.agent.device

import android.content.Context
import android.content.pm.PackageManager
import android.os.DropBoxManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Recent crash / ANR / native-tombstone entries from DropBoxManager, as the firmware client
 * reports them (mdm-client MdmService.getRecentCrashEvents): `extra.crash_events` =
 * [{kind,time_ms,summary,trace}]. The server dedupes by (device,kind,time_ms), so the last
 * hour is re-sent on every check-in and a lost send self-recovers.
 *
 * DropBox reads need READ_LOGS plus the GET_USAGE_STATS appop. A Device Owner cannot grant
 * either to itself; tools/enroll-adb.sh grants both over adb. Without them this returns
 * null and the check-in carries no crash field at all (rather than an empty list that
 * would read as "no crashes").
 */
object CrashEvents {

    private const val TAG = "CrashEvents"

    private val CRASH_TAGS = arrayOf(
        "data_app_crash", "data_app_anr", "data_app_native_crash",
        "system_app_crash", "system_app_anr", "system_app_native_crash",
        "system_server_crash", "system_server_anr", "system_server_native_crash",
        "SYSTEM_TOMBSTONE",
    )
    private const val LOOKBACK_MS = 60 * 60 * 1000L
    // Same bounds as the firmware client: the server rejects an oversized `extra`.
    private const val MAX_TRACE_BYTES = 48 * 1024
    private const val MAX_TOTAL_TRACE_BYTES = 128 * 1024
    private const val MAX_ENTRIES = 40

    fun available(ctx: Context): Boolean =
        ctx.checkSelfPermission(android.Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED

    fun recent(ctx: Context): JSONArray? {
        if (!available(ctx)) return null
        val arr = JSONArray()
        try {
            val dbm = ctx.getSystemService(Context.DROPBOX_SERVICE) as? DropBoxManager ?: return null
            val since = System.currentTimeMillis() - LOOKBACK_MS
            var budget = MAX_TOTAL_TRACE_BYTES
            for (tag in CRASH_TAGS) {
                if (arr.length() >= MAX_ENTRIES) break
                var cursor = since
                for (i in 0 until 50) {
                    if (arr.length() >= MAX_ENTRIES) break
                    val e = dbm.getNextEntry(tag, cursor) ?: break
                    e.use {
                        var t = it.timeMillis
                        val o = JSONObject().put("kind", tag).put("time_ms", t)
                        val cap = MAX_TRACE_BYTES.coerceAtMost(budget.coerceAtLeast(0))
                        val txt = readEntry(it, if (cap > 0) cap else 200)
                        if (!txt.isNullOrEmpty()) {
                            o.put("summary", headline(txt))
                            if (cap > 0) { o.put("trace", txt); budget -= txt.length }
                        }
                        arr.put(o)
                        if (t <= cursor) t = cursor + 1 // guard against equal timestamps
                        cursor = t
                    }
                }
            }
        } catch (t: Throwable) {
            // SecurityException here = READ_LOGS granted but the usage-stats appop is not.
            Log.w(TAG, "crash events read failed: ${t.message}")
            return null
        }
        return arr
    }

    /** "<process> — <first body line>": the entry's first line is a useless header. */
    private fun headline(txt: String): String {
        val lines = txt.split('\n')
        var proc: String? = null
        var body = 0
        for ((i, l) in lines.withIndex()) {
            if (proc == null && l.startsWith("Process:")) proc = l.substring(8).trim()
            if (l.isBlank()) { body = i + 1; break }
        }
        var exc = lines.drop(body).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (exc.isEmpty()) exc = lines.firstOrNull()?.trim().orEmpty()
        return when {
            !proc.isNullOrEmpty() && exc.isNotEmpty() -> "$proc — $exc"
            exc.isNotEmpty() -> exc
            else -> proc.orEmpty()
        }
    }

    /** getText() is null for non-text entries (native tombstones): fall back to the stream. */
    private fun readEntry(e: DropBoxManager.Entry, cap: Int): String? {
        e.getText(cap)?.let { return it }
        return runCatching {
            e.inputStream?.use { s ->
                val buf = ByteArray(cap)
                var off = 0
                while (off < cap) {
                    val n = s.read(buf, off, cap - off)
                    if (n <= 0) break
                    off += n
                }
                String(buf, 0, off, Charsets.UTF_8)
            }
        }.getOrNull()
    }
}
