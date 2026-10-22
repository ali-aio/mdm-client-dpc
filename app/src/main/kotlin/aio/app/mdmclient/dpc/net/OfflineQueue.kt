package aio.app.mdmclient.dpc.net

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Readings kept while the device cannot reach the server, sent once it can (0.5.0; the
 * user: "i also want offline syncing when live in standard mdm"). The same queue the
 * firmware client has (OfflineQueue.java) and the same endpoint, POST
 * /api/v1/checkins/backfill, so the server stores them as late samples and the device page
 * draws them dashed as "sent later".
 *
 * One JSON reading per line in device-protected storage, so it survives a reboot (even
 * before unlock) and an agent update. Capped at a week of 5-minute readings and 2 MB; the
 * oldest go first and are counted, so the server can say how many were lost.
 */
object OfflineQueue {
    private const val TAG = "OfflineQueue"
    private const val FILE = "offline_queue.jsonl"
    private const val PREFS = "offline_queue"
    private const val K_DROPPED = "dropped"
    private const val K_LAST_CRASH = "last_crash_ms"
    const val MAX_READINGS = 7 * 24 * 12
    private const val MAX_BYTES = 2L * 1024 * 1024

    private fun dir(ctx: Context): Context = ctx.createDeviceProtectedStorageContext()
    private fun file(ctx: Context) = File(dir(ctx).filesDir, FILE)
    private fun prefs(ctx: Context) = dir(ctx).getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun add(ctx: Context, reading: JSONObject) {
        val lines = read(ctx).toMutableList()
        lines.add(reading.toString())
        var dropped = 0
        while (lines.size > MAX_READINGS || lines.sumOf { it.length + 1L } > MAX_BYTES) {
            lines.removeAt(0); dropped++
        }
        if (dropped > 0) prefs(ctx).edit().putInt(K_DROPPED, dropped(ctx) + dropped).apply()
        write(ctx, lines)
    }

    @Synchronized
    fun peek(ctx: Context, n: Int): List<JSONObject> =
        read(ctx).take(n).mapNotNull { runCatching { JSONObject(it) }.getOrNull() }

    @Synchronized
    fun removeFirst(ctx: Context, n: Int) {
        val lines = read(ctx)
        write(ctx, if (n >= lines.size) emptyList() else lines.drop(n))
    }

    @Synchronized
    fun size(ctx: Context): Int = read(ctx).size

    fun dropped(ctx: Context): Int = prefs(ctx).getInt(K_DROPPED, 0)
    fun clearDropped(ctx: Context) { prefs(ctx).edit().putInt(K_DROPPED, 0).apply() }

    /** Crashes newer than the last ones a reading carried, so none is sent twice. */
    fun newCrashes(ctx: Context, crashes: JSONArray?): JSONArray? {
        if (crashes == null || crashes.length() == 0) return null
        val last = prefs(ctx).getLong(K_LAST_CRASH, 0L)
        val out = JSONArray()
        var newest = last
        for (i in 0 until crashes.length()) {
            val c = crashes.optJSONObject(i) ?: continue
            val t = c.optLong("time_ms", 0L)
            if (t <= last) continue
            out.put(c)
            if (t > newest) newest = t
        }
        if (newest > last) prefs(ctx).edit().putLong(K_LAST_CRASH, newest).apply()
        return if (out.length() > 0) out else null
    }

    private fun read(ctx: Context): List<String> = try {
        val f = file(ctx)
        if (f.exists()) f.readLines().filter { it.isNotBlank() } else emptyList()
    } catch (e: Exception) {
        Log.w(TAG, "read failed: ${e.message}"); emptyList()
    }

    private fun write(ctx: Context, lines: List<String>) {
        try {
            val f = file(ctx)
            val tmp = File(f.parentFile, "$FILE.tmp")
            tmp.writeText(if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n"))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (e: Exception) {
            Log.w(TAG, "write failed: ${e.message}")
        }
    }
}
