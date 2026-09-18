package com.skorra.agent.net

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import com.skorra.agent.Capabilities
import com.skorra.agent.DeviceIdentity
import com.skorra.agent.device.CrashEvents
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
        // A TV box / dongle has no battery: its broadcast says present=false and carries
        // 0% and 0 °C, which the server would show as a flat battery at freezing point.
        // Without a pack, send no battery fields at all.
        val batteryIntent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.takeIf { it.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true) }
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
            put("capabilities", JSONArray(Capabilities.supported(ctx)))
            // Degraded = works with limits (consent, reduced scope). The dashboard shows
            // these as "limited" instead of hiding them.
            put("capabilities_degraded", JSONArray(Capabilities.degraded(ctx)))
            put("model", Build.MODEL)
            put("manufacturer", Build.MANUFACTURER)
            put("android_release", Build.VERSION.RELEASE)
            put("sdk_int", Build.VERSION.SDK_INT)
            put("uptime_seconds", SystemClock.elapsedRealtime() / 1000)
            put("timezone", TimeZone.getDefault().id)
            // Same keys and units as the firmware client (MdmService.java): the server's
            // RAM and storage surfaces read ram_usage_mb {total,available,used} and
            // storage_free_gb, and showed nothing for DPC devices while we sent bytes.
            put("storage_free_gb", Math.round(stat.availableBytes / GB * 10.0) / 10.0)
            put("storage_total_bytes", stat.totalBytes)
            put("ram_usage_mb", JSONObject().apply {
                put("total", mem.totalMem / MB)
                put("available", mem.availMem / MB)
                put("used", (mem.totalMem - mem.availMem) / MB)
            })
            // Same battery-broadcast telemetry the system client reports (MdmService.java):
            // temperature feeds the dashboard's hottest-device/running-hot surfaces, charging
            // feeds the on-charger vital and filters. EXTRA_TEMPERATURE is tenths of a °C.
            val tenths = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            if (tenths != null && tenths != Int.MIN_VALUE) put("battery_temp_c", tenths / 10.0)
            if (batteryIntent != null) put("charging", isCharging(batteryIntent))
            // SoC temperature. The only reading a battery-less box has; the dashboard
            // shows it with CPU thresholds, not the battery's.
            cpuTempC(ctx)?.let { put("cpu_temp_c", it) }
            put("leanback", ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK))
            attachNetwork(ctx, this)
            CrashEvents.recent(ctx)?.let { put("crash_events", it) }
            attachLocation(ctx, this)
            attachSecurityPosture(ctx, this)
        }
    }

    /**
     * ip_address / wifi / wifi_rssi, the keys the firmware client reports. The SSID reads as
     * "<unknown ssid>" unless location is granted; that is reported as null, not as a name.
     */
    private fun attachNetwork(ctx: Context, extra: JSONObject) {
        runCatching {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork
            val ip = cm.getLinkProperties(net)?.linkAddresses
                ?.firstOrNull { it.address is java.net.Inet4Address && !it.address.isLoopbackAddress }
                ?.address?.hostAddress
            extra.put("ip_address", ip ?: JSONObject.NULL)
            val caps = cm.getNetworkCapabilities(net)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                @Suppress("DEPRECATION")
                val info = (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo
                val ssid = info?.ssid?.takeIf { it.isNotBlank() && it != WifiManager.UNKNOWN_SSID }
                extra.put("wifi", ssid ?: JSONObject.NULL)
                extra.put("wifi_rssi", info?.rssi ?: JSONObject.NULL)
            } else {
                extra.put("wifi", JSONObject.NULL)
                extra.put("wifi_rssi", JSONObject.NULL)
            }
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
     * the DO-granted permission is actually in place. Reads are passive, but when no fix
     * exists (or it's stale) we request ONE fresh fix asynchronously — on a dedicated
     * device nothing else ever activates the providers, so a passive-only read would
     * never produce a location at all. The fresh fix lands in a later checkin.
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
                .maxByOrNull { it.time }
            val ageMs = best?.let { System.currentTimeMillis() - it.time } ?: Long.MAX_VALUE
            if (best == null || ageMs > STALE_FIX_MS) nudgeLocation(ctx, lm)
            if (best == null) return
            extra.put("location_lat", best.latitude)
            extra.put("location_lon", best.longitude)
            extra.put("location_acc_m", best.accuracy.toDouble())
            extra.put("location_age_s", (ageMs / 1000).coerceAtLeast(0))
        }
    }

    private const val STALE_FIX_MS = 10 * 60_000L
    @Volatile private var locationRequestInFlight = false

    /** Request one fresh fix (GPS, falling back to network) without blocking the checkin. */
    private fun nudgeLocation(ctx: Context, lm: android.location.LocationManager) {
        if (locationRequestInFlight) return
        locationRequestInFlight = true
        runCatching {
            val provider = when {
                lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ->
                    android.location.LocationManager.GPS_PROVIDER
                lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) ->
                    android.location.LocationManager.NETWORK_PROVIDER
                else -> { locationRequestInFlight = false; return }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                lm.getCurrentLocation(provider, null, ctx.mainExecutor) {
                    locationRequestInFlight = false // fix (or null) cached by the provider
                }
            } else {
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(provider, { locationRequestInFlight = false }, null)
            }
        }.onFailure { locationRequestInFlight = false }
    }

    private const val MB = 1024L * 1024L
    private const val GB = 1024.0 * 1024.0 * 1024.0

    /**
     * Hottest CPU core, in °C, from the thermal HAL via HardwarePropertiesManager (open to
     * the Device Owner). Falls back to the first sysfs thermal zone where the SELinux
     * policy lets an app read it. null when neither gives a plausible reading.
     */
    private fun cpuTempC(ctx: Context): Double? {
        val hal = runCatching {
            val hpm = ctx.getSystemService(Context.HARDWARE_PROPERTIES_SERVICE) as android.os.HardwarePropertiesManager
            hpm.getDeviceTemperatures(
                android.os.HardwarePropertiesManager.DEVICE_TEMPERATURE_CPU,
                android.os.HardwarePropertiesManager.TEMPERATURE_CURRENT,
            ).filter { it.isFinite() && it > 0f }.maxOrNull()?.toDouble()
        }.getOrNull()
        val t = hal ?: runCatching {
            java.io.File("/sys/class/thermal/thermal_zone0/temp").readText().trim().toDouble() / 1000.0
        }.getOrNull()
        return t?.takeIf { it in 1.0..150.0 }?.let { Math.round(it * 10.0) / 10.0 }
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
