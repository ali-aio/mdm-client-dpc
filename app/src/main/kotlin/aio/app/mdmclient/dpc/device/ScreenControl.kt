package aio.app.mdmclient.dpc.device

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import aio.app.mdmclient.dpc.DeviceOwner
import aio.app.mdmclient.dpc.ui.WakeActivity

/**
 * Turning the display on and off from the server.
 *
 * The system-app client just calls the hidden `PowerManager.wakeUp()`, which needs the
 * system UID — this agent has neither that nor the platform signature, and the hidden-API
 * blocklist would reject the reflection anyway. What a normal app is *given* instead is an
 * activity that declares it wants the screen on: `setTurnScreenOn` + `setShowWhenLocked`
 * wake the display and show over the keyguard, which is the supported replacement and the
 * only one that works on A15. [WakeActivity] is that activity, and it finishes immediately.
 *
 * A `SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP` lock is taken alongside it, briefly.
 * It is deprecated and ignored on some builds, which is exactly why it is not the primary
 * path — but where it does work it wakes the screen a beat sooner than the activity, and
 * where it does not it costs nothing. Both are fired; whichever the device honours wins.
 */
object ScreenControl {

    private const val TAG = "ScreenControl"

    /** How long the fallback wake lock is held — long enough to wake, short enough to not pin. */
    private const val WAKE_LOCK_MS = 3_000L

    /**
     * Force the display on. Safe to call when it is already on (the activity finishes
     * straight away and the lock expires on its own).
     */
    fun wake(ctx: Context) {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null && pm.isInteractive) {
            // Already awake. The keyguard may still be up, and remote control through a
            // lock screen is useless, so go through the activity anyway to dismiss it.
            Log.d(TAG, "wake: display already on, dismissing keyguard only")
        }
        acquireWakeLock(pm)
        WakeActivity.launch(ctx)
    }

    /**
     * Put the device to sleep and lock it. `lockNow()` is the Device Owner equivalent of
     * the power button: it is allowed without the system UID, unlike `goToSleep()`.
     */
    fun sleep(deviceOwner: DeviceOwner) {
        runCatching { deviceOwner.dpm.lockNow() }
            .onFailure { Log.w(TAG, "lockNow failed: ${it.message}") }
    }

    /**
     * True when the display is on. Reported in telemetry so the dashboard can say whether a
     * wake did anything, rather than leaving the operator guessing at a black stream.
     */
    fun isScreenOn(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isInteractive
    }

    /** True when a keyguard stands between the operator and the screen they are driving. */
    fun isLocked(ctx: Context): Boolean {
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return false
        return km.isKeyguardLocked
    }

    @Suppress("DEPRECATION") // see the class comment: deliberate second-best, not the only path
    private fun acquireWakeLock(pm: PowerManager?) {
        if (pm == null) return
        runCatching {
            val flags = PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP
            val lock = pm.newWakeLock(flags, "aio-mdm:wake")
            lock.setReferenceCounted(false)
            // Timed acquire: the lock releases itself even if this process dies first, so a
            // failed wake can never leave the screen pinned on and the battery draining.
            lock.acquire(WAKE_LOCK_MS)
        }.onFailure {
            Log.w(TAG, "wake lock unavailable (${Build.MANUFACTURER}): ${it.message}")
        }
    }
}
