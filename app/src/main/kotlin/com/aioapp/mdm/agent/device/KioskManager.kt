package com.aioapp.mdm.agent.device

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aioapp.mdm.agent.DeviceOwner

/**
 * Kiosk / lock-task control via Device Owner APIs. The allowlist ([setLockTaskPackages]) is the
 * core DO primitive; enabling also launches the kiosk app. Full auto-pin for a third-party app
 * that doesn't call startLockTask itself is a known limitation — see the plan; a "set as Home"
 * option can be layered on later.
 */
class KioskManager(private val ctx: Context, private val deviceOwner: DeviceOwner) {

    fun apply(enabled: Boolean, kioskPackage: String) {
        if (!deviceOwner.isDeviceOwner) {
            Log.w(TAG, "not device owner; cannot apply kiosk")
            return
        }
        val dpm = deviceOwner.dpm
        val admin = deviceOwner.admin

        if (enabled && kioskPackage.isNotBlank()) {
            // Allow our own package too so the agent survives inside lock task.
            dpm.setLockTaskPackages(admin, arrayOf(kioskPackage, ctx.packageName))
            runCatching {
                dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
            }
            launch(kioskPackage)
            Log.i(TAG, "kiosk enabled for $kioskPackage")
        } else {
            dpm.setLockTaskPackages(admin, emptyArray())
            Log.i(TAG, "kiosk disabled")
        }
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
