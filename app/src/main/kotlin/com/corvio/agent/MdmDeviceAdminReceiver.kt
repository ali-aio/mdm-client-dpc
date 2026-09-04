package com.corvio.agent

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Device Admin / Device Owner receiver. The DO component referenced by:
 *   adb shell dpm set-device-owner com.corvio.agent/.MdmDeviceAdminReceiver
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
