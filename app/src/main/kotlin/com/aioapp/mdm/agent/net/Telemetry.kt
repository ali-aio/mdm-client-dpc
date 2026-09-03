package com.aioapp.mdm.agent.net

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import com.aioapp.mdm.agent.Capabilities
import com.aioapp.mdm.agent.DeviceIdentity
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone
import java.util.zip.CRC32

/**
 * Builds the checkin / telemetry payload the server expects
 * (see server internal/api/handlers.go checkinRequest).
 *
 * `installed_apps` is only attached when [includeApps] is true — the server tells us via the
 * `send_apps` response flag (or we send when the [appsHash] changed), matching the original
 * client's hash-gated app inventory to keep checkins cheap.
 */
object Telemetry {

    /** Stable hash of the installed-app set so the server can detect changes without the full list. */
    fun appsHash(ctx: Context): String {
        val pm = ctx.packageManager
        val entries = pm.getInstalledPackages(0)
            .map { "${it.packageName}:${it.versionName ?: it.longVersionCodeCompat()}" }
            .sorted()
        val crc = CRC32()
        crc.update(entries.joinToString("\n").toByteArray())
        return crc.value.toString(16)
    }

    fun buildCheckin(ctx: Context, includeApps: Boolean): JSONObject {
        val battery = readBattery(ctx)
        val obj = JSONObject().apply {
            put("serial_number", DeviceIdentity.serial(ctx))
            put("build_id", DeviceIdentity.buildId())
            put("product", DeviceIdentity.product())
            if (battery != null) put("battery_pct", battery)
            put("apps_hash", appsHash(ctx))
            put("extra", buildExtra(ctx))
        }
        if (includeApps) obj.put("installed_apps", installedApps(ctx))
        return obj
    }

    private fun buildExtra(ctx: Context): JSONObject {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val stat = StatFs(Environment.getDataDirectory().path)
        return JSONObject().apply {
            put("agent_type", Capabilities.AGENT_TYPE)
            put("capabilities", JSONArray(Capabilities.supported))
            put("model", Build.MODEL)
            put("manufacturer", Build.MANUFACTURER)
            put("android_release", Build.VERSION.RELEASE)
            put("sdk_int", Build.VERSION.SDK_INT)
            put("uptime_seconds", SystemClock.elapsedRealtime() / 1000)
            put("timezone", TimeZone.getDefault().id)
            put("storage_free_bytes", stat.availableBytes)
            put("storage_total_bytes", stat.totalBytes)
            put("ram_free_bytes", mem.availMem)
            put("ram_total_bytes", mem.totalMem)
        }
    }

    private fun installedApps(ctx: Context): JSONArray {
        val pm = ctx.packageManager
        val arr = JSONArray()
        for (pkg in pm.getInstalledPackages(0)) {
            val ai = pkg.applicationInfo ?: continue
            val isSystem = (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            arr.put(JSONObject().apply {
                put("package", pkg.packageName)
                put("name", pm.getApplicationLabel(ai).toString())
                put("version_name", pkg.versionName ?: pkg.longVersionCodeCompat().toString())
                put("is_system", isSystem)
            })
        }
        return arr
    }

    private fun readBattery(ctx: Context): Int? {
        val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0) (level * 100 / scale) else null
    }

    private fun android.content.pm.PackageInfo.longVersionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode
        else @Suppress("DEPRECATION") versionCode.toLong()
}
