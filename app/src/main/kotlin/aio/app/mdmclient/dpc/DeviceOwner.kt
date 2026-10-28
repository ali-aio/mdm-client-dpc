package aio.app.mdmclient.dpc

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context

/**
 * Thin helper around [DevicePolicyManager] + this app's admin component. Phase 2+ command
 * handlers call through here so there's one place that owns the admin ComponentName.
 */
class DeviceOwner(private val ctx: Context) {

    val admin: ComponentName = ComponentName(ctx, MdmDeviceAdminReceiver::class.java)

    val dpm: DevicePolicyManager =
        ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    /** True when this app has been provisioned as Device Owner (via adb or managed provisioning). */
    val isDeviceOwner: Boolean
        get() = dpm.isDeviceOwnerApp(ctx.packageName)

    val isAdminActive: Boolean
        get() = dpm.isAdminActive(admin)

    /**
     * Self-grant READ_PHONE_STATE so Build.getSerial() returns the real hardware serial —
     * the same identifier printed on the device label, and stable across factory resets
     * (ANDROID_ID is not). Called before the first serial resolution at service start.
     */
    fun ensureIdentityAccess() {
        if (!isDeviceOwner) return
        grantSelf(android.Manifest.permission.READ_PHONE_STATE)
    }

    /** Self-grant POST_NOTIFICATIONS (Android 13+), so install notices show without a prompt. */
    fun ensureNotificationAccess() {
        if (!isDeviceOwner || android.os.Build.VERSION.SDK_INT < 33) return
        grantSelf(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * Self-grant location runtime permissions via Device Owner policy (no user prompt),
     * and force location services on. Called when the server enables location reporting.
     */
    fun ensureLocationAccess() {
        if (!isDeviceOwner) return
        for (perm in arrayOf(
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )) grantSelf(perm)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
            if (lm == null || !runCatching { lm.isLocationEnabled }.getOrDefault(false)) {
                runCatching { dpm.setLocationEnabled(admin, true) }
            }
        }
    }

    /**
     * Grant a runtime permission to ourselves by policy, only when it is not already granted
     * that way. Every grant of a location permission makes the permission controller post
     * "AIO MDM has location access" again, with sound; this ran on every config frame and
     * every BLE scan, so POS and KDS units (no way to mute) chimed all day (0.13.2).
     */
    fun grantSelf(perm: String) {
        if (!isDeviceOwner) return
        val pkg = ctx.packageName
        val held = runCatching {
            dpm.getPermissionGrantState(admin, pkg, perm) == DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED &&
                ctx.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (held) return
        runCatching { dpm.setPermissionGrantState(admin, pkg, perm, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED) }
    }

    /**
     * Keep network time on. Boxes without a battery-backed clock boot years in the past, and
     * with a wrong date every TLS handshake fails — the agent cannot even check in to report
     * it. A user with Settings access can turn auto-time off; this turns it back on.
     */
    fun ensureAutoTime() {
        if (!isDeviceOwner) return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            runCatching { if (!dpm.getAutoTimeEnabled(admin)) dpm.setAutoTimeEnabled(admin, true) }
        }
    }
}
