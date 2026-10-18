package aio.app.mdmclient.dpc.device

import android.Manifest
import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import aio.app.mdmclient.dpc.DeviceOwner

/**
 * The Bluetooth groundwork the lost beacon and the neighbour listener share: does the device
 * have LE at all, granting ourselves the runtime permissions (we are Device Owner), and
 * switching the radio — and, on Android 11 and older, location — on for a moment and back off.
 *
 * Rule from the protocol: never leave Bluetooth on if it was off. Whoever turns it on through
 * [ensureOn] gets back a token saying so, and hands it to [restore] when done.
 */
class BleRadio(private val ctx: Context, private val owner: DeviceOwner) {

    val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /** The hardware has Bluetooth LE. */
    val hasLe: Boolean
        get() = adapter != null && ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

    /**
     * Whether this device can advertise. Only knowable with the radio on; off, we trust the LE
     * feature and let the beacon report a failure if the chip turns out not to advertise.
     */
    val canAdvertise: Boolean
        @SuppressLint("MissingPermission")
        get() {
            if (!hasLe) return false
            val a = adapter ?: return false
            return runCatching { !a.isEnabled || a.isMultipleAdvertisementSupported }.getOrDefault(true)
        }

    /** Grant the Bluetooth (and, ≤ Android 11, location) runtime permissions to ourselves. */
    fun grantPermissions() {
        if (!owner.isDeviceOwner) return
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            // Without neverForLocation (beacons need it off), scan results still need location.
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        for (p in perms) {
            runCatching {
                owner.dpm.setPermissionGrantState(owner.admin, ctx.packageName, p,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            }
        }
    }

    fun granted(perm: String): Boolean =
        ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    /** What is missing to scan, or null when nothing is. */
    fun scanBlocker(): String? {
        if (!hasLe) return "no Bluetooth LE on this device"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!granted(Manifest.permission.BLUETOOTH_SCAN)) return "BLUETOOTH_SCAN not granted"
        }
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) return "location permission not granted"
        return null
    }

    /** What is missing to advertise, or null when nothing is. */
    fun advertiseBlocker(): String? {
        if (!hasLe) return "no Bluetooth LE on this device"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !granted(Manifest.permission.BLUETOOTH_ADVERTISE)) return "BLUETOOTH_ADVERTISE not granted"
        return null
    }

    /**
     * Make sure the radio is on. Returns (on, weTurnedItOn). As Device Owner the agent is one of
     * the callers Android 13+ still lets switch Bluetooth; it may still refuse (some boxes have a
     * user restriction), in which case on=false.
     */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun ensureOn(timeoutMs: Long = 6_000): Pair<Boolean, Boolean> {
        val a = adapter ?: return false to false
        if (runCatching { a.isEnabled }.getOrDefault(false)) return true to false
        val asked = runCatching { a.enable() }.getOrDefault(false)
        if (!asked) return false to false
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < until) {
            if (runCatching { a.state == BluetoothAdapter.STATE_ON }.getOrDefault(false)) return true to true
            SystemClock.sleep(150)
        }
        return (runCatching { a.isEnabled }.getOrDefault(false)) to true
    }

    /** Switch the radio back off if [weTurnedItOn]. */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun restore(weTurnedItOn: Boolean) {
        if (!weTurnedItOn) return
        runCatching { adapter?.disable() }.onFailure { Log.w(TAG, "bluetooth off: ${it.message}") }
    }

    /**
     * Android 11 and older only return LE scan results with location services on. Turn them on
     * for the scan when we can (Device Owner, API 30); returns whether we did, for [restoreLocation].
     */
    fun ensureLocationForScan(): Boolean {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        if (runCatching { lm.isLocationEnabled }.getOrDefault(true)) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !owner.isDeviceOwner) return false
        return runCatching { owner.dpm.setLocationEnabled(owner.admin, true); true }.getOrDefault(false)
    }

    fun restoreLocation(weTurnedItOn: Boolean) {
        if (!weTurnedItOn || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching { owner.dpm.setLocationEnabled(owner.admin, false) }
    }

    /** Location services are on (or the platform doesn't say). */
    fun locationOn(): Boolean {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return true
        return runCatching { lm.isLocationEnabled }.getOrDefault(true)
    }

    companion object { private const val TAG = "BleRadio" }
}
