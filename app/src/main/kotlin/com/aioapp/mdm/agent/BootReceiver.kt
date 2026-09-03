package com.aioapp.mdm.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts the foreground agent service on boot (both LOCKED_BOOT_COMPLETED for Direct Boot and
 * the normal BOOT_COMPLETED), mirroring the original client's boot behavior.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> MdmService.start(context)
        }
    }
}
