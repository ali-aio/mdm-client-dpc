package com.skorra.agent.net

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import com.skorra.agent.Capabilities
import com.skorra.agent.DeviceIdentity
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
        val batteryIntent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val battery = readBattery(batteryIntent)
        val obj = JSONObject().apply {
            put("serial_number", DeviceIdentity.serial(ctx))
            put("build_id", DeviceIdentity.buildId())
            put("product", DeviceIdentity.product())
            if (battery != null) put("battery_pct", battery)
            put("apps_hash", appsHash(ctx))
            put("extra", buildExtra(ctx, batteryIntent))
        }
        if (includeApps) obj.put("installed_apps", installedApps(ctx))
        return obj
    }

    private fun buildExtra(ctx: Context, batteryIntent: Intent?): JSONObject {
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
            // Same battery-broadcast telemetry the system client reports (MdmService.java):
            // temperature feeds the dashboard's hottest-device/running-hot surfaces, charging
            // feeds the on-charger vital and filters. EXTRA_TEMPERATURE is tenths of a °C.
            val tenths = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            if (tenths != null && tenths != Int.MIN_VALUE) put("battery_temp_c", tenths / 10.0)
            if (batteryIntent != null) put("charging", isCharging(batteryIntent))
            attachLocation(ctx, this)
            attachSecurityPosture(ctx, this)
        }
    }

    /** Compliance signals the server's policy engine evaluates (all best-effort). */
    private fun attachSecurityPosture(ctx: Context, extra: JSONObject) {
        runCatching {
            val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
            extra.put("screen_lock_set", km.isDeviceSecure)
        }
        runCatching {
            val adb = android.provider.Settings.Global.getInt(
                ctx.contentResolver, android.provider.Settings.Global.ADB_ENABLED, 0,
            )
            extra.put("adb_enabled", adb == 1)
        }
        runCatching {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE)
                as android.app.admin.DevicePolicyManager
            extra.put(
                "storage_encrypted",
                dpm.storageEncryptionStatus ==
                    android.app.admin.DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER ||
                    dpm.storageEncryptionStatus ==
                    android.app.admin.DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE,
            )
        }
    }

    /**
     * Attach the freshest last-known fix when the server has enabled location reporting and
     * the DO-granted permission is actually in place. Passive read only — no provider wakeups,
     * so this adds nothing to the checkin's power cost.
     */
    private fun attachLocation(ctx: Context, extra: JSONObject) {
        if (!com.skorra.agent.AgentConfig.get(ctx).locationEnabled) return
        if (ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        runCatching {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val best = lm.allProviders
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time } ?: return
            extra.put("location_lat", best.latitude)
            extra.put("location_lon", best.longitude)
            extra.put("location_acc_m", best.accuracy.toDouble())
            extra.put("location_age_s", ((System.currentTimeMillis() - best.time) / 1000).coerceAtLeast(0))
        }
    }

    private fun isCharging(batteryIntent: Intent): Boolean {
        if (batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0) return true
        val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun installedApps(ctx: Context): JSONArray {
        val pm = ctx.packageManager
        // Packages that have a launcher (drawer) entry. The server's app-lock picker
        // only offers these — pinning to a package with no launch activity can't work.
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launchable = pm.queryIntentActivities(launcherIntent, 0)
            .mapNotNull { it.activityInfo?.packageName }
            .toHashSet()
        val arr = JSONArray()
        for (pkg in pm.getInstalledPackages(0)) {
            val ai = pkg.applicationInfo ?: continue
            val isSystem = (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            val isLaunchable = launchable.contains(pkg.packageName)
            arr.put(JSONObject().apply {
                put("package", pkg.packageName)
                put("name", pm.getApplicationLabel(ai).toString())
                put("version_name", pkg.versionName ?: pkg.longVersionCodeCompat().toString())
                put("is_system", isSystem)
                put("launchable", isLaunchable)
                // Only ship an icon for drawer apps — that's all the picker renders, and
                // it keeps the checkin payload small. Best-effort; skip on any failure.
                if (isLaunchable) launcherIconBase64(pm, ai)?.let { put("icon", it) }
            })
        }
        return arr
    }

    /** Renders a package's launcher icon to a small base64 PNG (48dp) for the app-lock picker. */
    private fun launcherIconBase64(pm: android.content.pm.PackageManager,
                                   ai: android.content.pm.ApplicationInfo): String? = runCatching {
        val drawable = pm.getApplicationIcon(ai)
        val size = 96 // px — crisp at the picker's ~44px tile, still tiny on the wire
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    }.getOrNull()

    private fun readBattery(intent: Intent?): Int? {
        if (intent == null) return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0) (level * 100 / scale) else null
    }

    private fun android.content.pm.PackageInfo.longVersionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode
        else @Suppress("DEPRECATION") versionCode.toLong()
}
