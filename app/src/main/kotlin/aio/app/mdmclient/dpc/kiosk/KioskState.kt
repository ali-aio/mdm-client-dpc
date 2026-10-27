package aio.app.mdmclient.dpc.kiosk

import android.app.ActivityManager
import android.content.Context

/** Whether the device is really in lock task (0.12.1): reported as kiosk_active, and the lock guard's check. */
object KioskState {
    fun inLockTask(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
    }
}
