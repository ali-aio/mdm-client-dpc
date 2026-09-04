package com.corvio.agent.device

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.corvio.agent.DeviceOwner

/**
 * App-lock (kiosk) control via Device Owner APIs. Locking a third-party app so the user can't
 * casually leave it takes more than the lock-task allowlist:
 *   - setLockTaskPackages    — permit the app (+ our agent) in lock task
 *   - setLockTaskFeatures(NONE) — hide home / recents / notifications / status while pinned
 *   - setStatusBarDisabled   — kill the pull-down shade + quick settings
 *   - addPersistentPreferredActivity(HOME → the app) — Home button and boot both land back in
 *     the app, so there's no way out to a launcher
 * Together these make the app the device's only surface until an admin turns the lock off.
 */
class KioskManager(private val ctx: Context, private val deviceOwner: DeviceOwner) {

    fun apply(enabled: Boolean, kioskPackage: String) {
        if (!deviceOwner.isDeviceOwner) {
            Log.w(TAG, "not device owner; cannot apply app lock")
            return
        }
        val dpm = deviceOwner.dpm
        val admin = deviceOwner.admin

        if (enabled && kioskPackage.isNotBlank()) {
            dpm.setLockTaskPackages(admin, arrayOf(kioskPackage, ctx.packageName))
            runCatching { dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE) }
            runCatching { dpm.setStatusBarDisabled(admin, true) }
            setPersistentHome(kioskPackage)
            launch(kioskPackage)
            Log.i(TAG, "app lock ENABLED for $kioskPackage")
        } else {
            // Fully release: drop the Home override, re-enable the status bar, clear the allowlist.
            if (kioskPackage.isNotBlank()) {
                runCatching { dpm.clearPackagePersistentPreferredActivities(admin, kioskPackage) }
            }
            runCatching { dpm.setStatusBarDisabled(admin, false) }
            dpm.setLockTaskPackages(admin, emptyArray())
            Log.i(TAG, "app lock DISABLED")
        }
    }

    /** Make [pkg]'s launcher activity the persistent Home so Home/Back can't leave it. */
    private fun setPersistentHome(pkg: String) {
        val comp = ctx.packageManager.getLaunchIntentForPackage(pkg)?.component ?: run {
            Log.w(TAG, "no launcher activity for $pkg; skipping persistent home")
            return
        }
        val filter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        runCatching { deviceOwner.dpm.addPersistentPreferredActivity(deviceOwner.admin, filter, comp) }
            .onFailure { Log.w(TAG, "persistent home failed: ${it.message}") }
    }

    private fun launch(pkg: String) {
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(intent) }
            .onFailure { Log.w(TAG, "failed to launch kiosk app: ${it.message}") }
    }

    companion object {
        private const val TAG = "KioskManager"
    }
}
