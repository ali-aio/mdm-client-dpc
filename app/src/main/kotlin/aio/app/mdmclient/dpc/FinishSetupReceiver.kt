package aio.app.mdmclient.dpc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * The last step of an AIO Enroll setup (user, 28 Oct 2026: "after mdm is enrolled can we auto
 * disable dev options and restart the device"). The app sends this over adb once the device has
 * checked in; we answer at once, then turn off wireless and USB debugging and Developer options
 * and restart the device.
 *
 * It runs here and not as adb shell commands: switching debugging off stops adbd, and Android
 * kills what adbd started, so a restart queued behind it over adb could die first. The manifest
 * only lets a sender holding WRITE_SECURE_SETTINGS (the adb shell, the system) send it, and we
 * were granted that permission at enrollment for exactly these settings.
 */
class FinishSetupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        resultData = "closing"
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                // Let the enroll app read the answer before its connection goes.
                Thread.sleep(1500)
                close(app)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun close(ctx: Context) {
        val cr = ctx.contentResolver
        for (key in listOf("adb_wifi_enabled", Settings.Global.ADB_ENABLED, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED)) {
            runCatching { Settings.Global.putInt(cr, key, 0) }
                .onFailure { Log.w(TAG, "turn off $key: ${it.message}") }
        }
        val owner = DeviceOwner(ctx)
        if (!owner.isDeviceOwner) return
        // Same as the reboot command: dpm.reboot() needs no activity in the foreground, and the
        // settings provider gets a moment to write the values above to disk.
        runCatching {
            ctx.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        Thread.sleep(1500)
        runCatching { owner.dpm.reboot(owner.admin) }.onFailure { Log.w(TAG, "reboot: ${it.message}") }
    }

    companion object {
        const val ACTION = "aio.app.mdmclient.dpc.FINISH_SETUP"
        private const val TAG = "FinishSetup"
    }
}
