package aio.app.mdmclient.dpc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts the foreground agent service on boot (both LOCKED_BOOT_COMPLETED for Direct Boot and
 * the normal BOOT_COMPLETED), and re-starts it after an app update (MY_PACKAGE_REPLACED) — an
 * update kills the process, so without this the WebSocket stays down until the next reboot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> MdmService.start(context)
        }
    }
}
