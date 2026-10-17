package aio.app.mdmclient.dpc.device

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import aio.app.mdmclient.dpc.DeviceOwner
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Compliance signals for the check-in, in the firmware client's vocabulary
 * (SecurityPosture.java), so the server's checks read one set of keys across the fleet:
 *
 *   security_patch, verified_boot, selinux_enforcing, device_owner, dev_options_enabled,
 *   build_type, build_tags, unknown_sources, unknown_source_apps, su_present, play_protect
 *
 * This agent is an app UID, not system, and SELinux closes some of what the firmware client
 * reads. A key it cannot read is left out — absent reads as "unknown" on the server — rather
 * than sent as a guess: a false "off" or "enforcing" would pass a check nobody measured.
 * adb_enabled, adb_tcp and storage_encrypted stay in Telemetry, where they already were.
 */
object SecurityPosture {

    private const val TAG = "SecurityPosture"

    // Where su binaries are dropped by rooted images and root kits (same list as the firmware client).
    private val SU_PATHS = listOf(
        "/system/xbin/su", "/system/bin/su", "/sbin/su", "/system/sbin/su",
        "/vendor/bin/su", "/su/bin/su", "/debug_ramdisk/su", "/data/local/xbin/su",
        "/data/local/bin/su",
    )
    private const val APP_SCAN_TTL_MS = 5 * 60_000L
    private const val OP_REQUEST_INSTALL_PACKAGES = "android:request_install_packages"
    private const val PLAY_STORE = "com.android.vending"

    @Volatile private var cachedInstallers: JSONArray? = null
    @Volatile private var installersReadable = false
    @Volatile private var installersAtMs = 0L

    fun put(ctx: Context, deviceOwner: DeviceOwner, extra: JSONObject) {
        Build.VERSION.SECURITY_PATCH?.takeIf { it.isNotBlank() }?.let { extra.put("security_patch", it) }
        verifiedBoot()?.let { extra.put("verified_boot", it) }
        selinuxEnforcing()?.let { extra.put("selinux_enforcing", it) }
        runCatching { extra.put("device_owner", deviceOwner.isDeviceOwner) }
        runCatching {
            extra.put("dev_options_enabled",
                Settings.Global.getInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1)
        }
        extra.put("build_type", Build.TYPE)
        extra.put("build_tags", Build.TAGS)
        unknownSources(ctx)?.let { (on, apps) ->
            extra.put("unknown_sources", on)
            extra.put("unknown_source_apps", apps)
        }
        extra.put("su_present", SU_PATHS.any { File(it).exists() })
        // Play Protect only exists where the image has Google Play; elsewhere the setting's
        // default would report a verifier that is not there.
        if (installed(ctx, PLAY_STORE)) {
            runCatching {
                extra.put("play_protect",
                    Settings.Global.getInt(ctx.contentResolver, "package_verifier_enable", 1) != 0)
            }
        }
    }

    /** ro.boot.verifiedbootstate (green/yellow/orange/red), or null when the property is closed to apps. */
    private fun verifiedBoot(): String? = sysprop("ro.boot.verifiedbootstate")
        ?.lowercase()?.takeIf { it in setOf("green", "yellow", "orange", "red") }

    /**
     * /sys/fs/selinux/enforce when it can be read. SELinux.isSELinuxEnforced() is only taken
     * when it says true: it returns false for "could not read" as well as for permissive, so
     * its false proves nothing.
     */
    private fun selinuxEnforcing(): Boolean? {
        runCatching { File("/sys/fs/selinux/enforce").readText().trim() }.getOrNull()?.let {
            when (it) {
                "1" -> return true
                "0" -> return false
            }
        }
        val hidden = runCatching {
            Class.forName("android.os.SELinux").getMethod("isSELinuxEnforced").invoke(null) as Boolean
        }.getOrNull()
        return if (hidden == true) true else null
    }

    /**
     * (on, apps) — whether any app may install other apps. Certain when Device Owner has set
     * the global restriction (none can). Otherwise read per app from the REQUEST_INSTALL_PACKAGES
     * app-op, as the firmware client does; reading another app's op needs a permission this
     * agent may not hold, and then the answer is unknown unless the legacy switch says on.
     */
    private fun unknownSources(ctx: Context): Pair<Boolean, JSONArray>? {
        val um = ctx.getSystemService(Context.USER_SERVICE) as UserManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            um.hasUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY)
        ) return false to JSONArray()
        @Suppress("DEPRECATION")
        val legacy = runCatching {
            Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS, 0)
        }.getOrDefault(0) == 1
        val apps = installers(ctx)
        return when {
            apps != null -> (legacy || apps.length() > 0) to apps
            legacy -> true to JSONArray()
            else -> null
        }
    }

    @Synchronized
    private fun installers(ctx: Context): JSONArray? {
        val now = SystemClock.elapsedRealtime()
        if (installersAtMs != 0L && now - installersAtMs < APP_SCAN_TTL_MS) {
            return if (installersReadable) cachedInstallers else null
        }
        installersAtMs = now
        val out = mutableListOf<String>()
        installersReadable = try {
            val pm = ctx.packageManager
            val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            for (pi in pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)) {
                val ai = pi.applicationInfo ?: continue
                if (pi.requestedPermissions?.contains("android.permission.REQUEST_INSTALL_PACKAGES") != true) continue
                if (pi.packageName == ctx.packageName) continue // the agent installs; that is the MDM
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ops.unsafeCheckOpNoThrow(OP_REQUEST_INSTALL_PACKAGES, ai.uid, pi.packageName)
                } else {
                    @Suppress("DEPRECATION")
                    ops.checkOpNoThrow(OP_REQUEST_INSTALL_PACKAGES, ai.uid, pi.packageName)
                }
                if (mode == AppOpsManager.MODE_ALLOWED) out.add(pi.packageName)
            }
            true
        } catch (t: Throwable) {
            Log.i(TAG, "unknown-sources scan not readable: ${t.message}")
            false
        }
        out.sort()
        cachedInstallers = JSONArray(out)
        return if (installersReadable) cachedInstallers else null
    }

    private fun installed(ctx: Context, pkg: String): Boolean =
        runCatching { ctx.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    /** android.os.SystemProperties.get, or null when hidden-API or SELinux keeps it closed. */
    private fun sysprop(key: String): String? = runCatching {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
            .invoke(null, key) as String
    }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
}
