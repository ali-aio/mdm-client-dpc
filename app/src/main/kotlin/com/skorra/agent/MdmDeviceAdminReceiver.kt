package com.skorra.agent

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.util.Log

/**
 * Device Admin / Device Owner receiver. The DO component referenced by:
 *   adb shell dpm set-device-owner com.skorra.agent/.MdmDeviceAdminReceiver
 *
 * Also handles managed-provisioning completion (QR / zero-touch, Phase 6) via
 * [onProfileProvisioningComplete].
 */
class MdmDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "Device admin enabled")
        MdmService.start(context)
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        // QR / zero-touch: the provisioning payload's ADMIN_EXTRAS_BUNDLE carries the
        // server URL + enrollment token; seed config before the service first connects.
        val extras: PersistableBundle? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE,
                PersistableBundle::class.java,
            )
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE)
        }
        if (extras != null) {
            AgentConfig.get(context).seedFromProvisioningExtras(extras)
            Log.i(TAG, "Provisioning extras applied (server + enrollment token)")
        }
        Log.i(TAG, "Provisioning complete — starting agent")
        MdmService.start(context)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.w(TAG, "Device admin disabled")
    }

    companion object {
        private const val TAG = "MdmAdmin"
    }
}
