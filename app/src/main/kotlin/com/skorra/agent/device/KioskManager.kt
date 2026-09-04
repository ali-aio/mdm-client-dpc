package com.skorra.agent.device

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.util.Log
import com.skorra.agent.DeviceOwner
import com.skorra.agent.kiosk.KioskHostActivity

/**
 * App-lock (kiosk) control via Device Owner APIs. Real, exit-resistant locking of a third-party
 * app takes actual lock-task (screen pinning), which only an app in the task can start — so we
 * pin our own [KioskHostActivity] (set as Home) and host the target app on top of it:
 *   - setLockTaskPackages     — permit the target app (+ our agent) in lock task
 *   - setLockTaskFeatures(NONE) — hide home/recents/notifications/keyguard while pinned
 *   - setStatusBarDisabled    — kill the pull-down shade + quick settings
 *   - addPersistentPreferredActivity(HOME → KioskHostActivity) — Home button + boot land in our
 *     host, which startLockTask()s (disabling the nav bar) and relaunches the target
 * The host does the pinning; this class just installs the policy and kicks it off.
 */
class KioskManager(private val ctx: Context, private val deviceOwner: DeviceOwner) {

    fun apply(enabled: Boolean, kioskPackage: String) {
        if (!deviceOwner.isDeviceOwner) {
            Log.w(TAG, "not device owner; cannot apply app lock")
            return
        }
        val dpm = deviceOwner.dpm
        val admin = deviceOwner.admin
        val host = ComponentName(ctx, KioskHostActivity::class.java)

        if (enabled && kioskPackage.isNotBlank()) {
            setHostEnabled(host, true) // make our host a valid Home candidate
            dpm.setLockTaskPackages(admin, arrayOf(kioskPackage, ctx.packageName))
            runCatching { dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE) }
            runCatching { dpm.setStatusBarDisabled(admin, true) }
            // Route Home (button + boot) to our pinning host.
            val homeFilter = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            runCatching { dpm.addPersistentPreferredActivity(admin, homeFilter, host) }
                .onFailure { Log.w(TAG, "persistent home failed: ${it.message}") }
            // Kick it off now.
            runCatching {
                ctx.startActivity(Intent(ctx, KioskHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            Log.i(TAG, "app lock ENABLED for $kioskPackage")
        } else {
            // Emptying the allowlist makes the system drop out of lock task automatically.
            dpm.setLockTaskPackages(admin, emptyArray())
            runCatching { dpm.setStatusBarDisabled(admin, false) }
            runCatching { dpm.clearPackagePersistentPreferredActivities(admin, ctx.packageName) }
            // Bring the host forward so it exits lock task + returns to the onboarding screen,
            // then stop it being a Home candidate.
            runCatching {
                ctx.startActivity(Intent(ctx, KioskHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            setHostEnabled(host, false)
            Log.i(TAG, "app lock DISABLED")
        }
    }

    private fun setHostEnabled(host: ComponentName, on: Boolean) {
        val state = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        runCatching {
            ctx.packageManager.setComponentEnabledSetting(host, state, PackageManager.DONT_KILL_APP)
        }.onFailure { Log.w(TAG, "toggle host component failed: ${it.message}") }
    }

    companion object {
        private const val TAG = "KioskManager"
    }
}
