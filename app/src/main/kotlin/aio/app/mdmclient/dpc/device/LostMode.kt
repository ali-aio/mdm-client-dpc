package aio.app.mdmclient.dpc.device

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import aio.app.mdmclient.dpc.AgentConfig
import aio.app.mdmclient.dpc.DeviceOwner
import aio.app.mdmclient.dpc.kiosk.KioskHostActivity
import aio.app.mdmclient.dpc.lost.LostActivity
import org.json.JSONObject

/**
 * Lost mode (the `lost_mode` command): the device shows only [LostActivity] — a message, a
 * phone number and its serial — pinned in lock task until the server lifts it.
 *
 * It sits on top of the app lock without touching it. The kiosk keys in [AgentConfig] are
 * left as the server last set them, so lifting lost mode is just "apply the lock again"
 * ([KioskManager.apply]) and the device goes back exactly as it was — locked to its app,
 * or not locked at all. While lost, a server config push still updates the stored lock but
 * must not re-apply it (see CommandExecutor.applyConfig), or the lock would replace the
 * lost screen.
 *
 * Pinning works the way the lock does: only this package in the lock-task allowlist, no
 * lock-task features (no home, recents, notifications or global actions), the status bar
 * off, and Home (button and boot) routed to [LostActivity], which starts lock task itself.
 * The kiosk host stops being a Home candidate meanwhile, so nothing else can answer Home.
 *
 * This agent has no device-side unlock code, so only the server's `{"on": false}` lifts it.
 */
class LostMode(private val ctx: Context, private val deviceOwner: DeviceOwner, private val kiosk: KioskManager) {

    private val config = AgentConfig.get(ctx)

    /** Persist and show. Returns false when the agent is not Device Owner (nothing pinned). */
    fun enter(message: String, phone: String): Boolean {
        if (!deviceOwner.isDeviceOwner) return false
        config.setLost(true, message, phone)
        // A lost device should be findable: grant location to ourselves and turn location
        // on, so the check-in can carry a Wi-Fi scan (and a fix) for the server to place it.
        deviceOwner.ensureLocationAccess()
        pin()
        return true
    }

    /** Lift lost mode and put the lock back as the server last set it. */
    fun exit() {
        config.setLost(false)
        if (!deviceOwner.isDeviceOwner) return
        val dpm = deviceOwner.dpm
        val admin = deviceOwner.admin
        runCatching { dpm.clearPackagePersistentPreferredActivities(admin, ctx.packageName) }
        setEnabled(ComponentName(ctx, LostActivity::class.java), false)
        // Leave lock task before the lock re-applies, so an unlocked device does not stay
        // pinned to an activity that no longer exists.
        main.post { LostActivity.release() }
        main.postDelayed({
            if (config.kioskEnabled) {
                kiosk.apply(config)
            } else {
                kiosk.apply(enabled = false, allowedPackages = emptyList())
                runCatching {
                    ctx.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            Log.i(TAG, "lost mode lifted (lock ${if (config.kioskEnabled) "restored" else "off"})")
        }, 300)
    }

    /** Re-assert after boot, process death or a config push. No-op when not lost. */
    fun reassert() {
        if (config.lostOn && deviceOwner.isDeviceOwner) pin()
    }

    /** Wait (up to [timeoutMs]) for the lost screen to be on top; true when it is. */
    fun awaitShown(timeoutMs: Long): Boolean {
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < until) {
            if (LostActivity.isShown()) return true
            SystemClock.sleep(200)
        }
        return LostActivity.isShown()
    }

    private fun pin() {
        val dpm = deviceOwner.dpm
        val admin = deviceOwner.admin
        val lost = ComponentName(ctx, LostActivity::class.java)
        setEnabled(lost, true)
        // The kiosk host must not answer Home while lost; KioskManager.apply re-enables it.
        setEnabled(ComponentName(ctx, KioskHostActivity::class.java), false)
        // Only this package may be pinned: the locked app drops out of lock task.
        runCatching { dpm.setLockTaskPackages(admin, arrayOf(ctx.packageName)) }
            .onFailure { Log.w(TAG, "lock task packages: ${it.message}") }
        runCatching { dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE) }
        runCatching { dpm.setStatusBarDisabled(admin, true) }
        runCatching { dpm.clearPackagePersistentPreferredActivities(admin, ctx.packageName) }
        val home = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        runCatching { dpm.addPersistentPreferredActivity(admin, home, lost) }
            .onFailure { Log.w(TAG, "persistent home: ${it.message}") }
        runCatching {
            ctx.startActivity(Intent(ctx, LostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w(TAG, "start lost screen: ${it.message}") }
        Log.i(TAG, "lost mode on")
    }

    private fun setEnabled(c: ComponentName, on: Boolean) {
        val state = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        runCatching { ctx.packageManager.setComponentEnabledSetting(c, state, PackageManager.DONT_KILL_APP) }
            .onFailure { Log.w(TAG, "toggle ${c.shortClassName}: ${it.message}") }
    }

    companion object {
        private const val TAG = "LostMode"
        private val main = Handler(Looper.getMainLooper())

        /** `extra.lost_mode`: {"on": true, "since_ms", "message"} or {"on": false}. */
        fun report(ctx: Context): JSONObject {
            val cfg = AgentConfig.get(ctx)
            return JSONObject().apply {
                put("on", cfg.lostOn)
                if (cfg.lostOn) {
                    put("since_ms", cfg.lostSinceMs)
                    put("message", cfg.lostMessage)
                }
            }
        }
    }
}
