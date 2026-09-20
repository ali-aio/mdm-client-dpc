package aio.app.mdmclient.dpc.provisioning

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.util.Log
import aio.app.mdmclient.dpc.AgentConfig
import aio.app.mdmclient.dpc.MdmService

/**
 * The two activities Android 12+ managed provisioning requires from a DPC before it
 * will finish setting a Device Owner from a QR / zero-touch payload. Without them the
 * setup wizard downloads the APK and then stops with "Something went wrong".
 *
 *  - GET_PROVISIONING_MODE: the DPC says which mode it wants (a fully managed device,
 *    never a work profile) and may read the admin extras early.
 *  - ADMIN_POLICY_COMPLIANCE: called once the DPC is Device Owner; this is where a
 *    DPC would show its own setup UI. We seed config from the extras, start the
 *    agent and return immediately — the enrollment screen on the dashboard is the UI.
 */
private fun adminExtras(intent: Intent): PersistableBundle? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE, PersistableBundle::class.java)
    } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE)
    }

class ProvisioningModeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adminExtras(intent)?.let { AgentConfig.get(this).seedFromProvisioningExtras(it) }
        val result = Intent().putExtra(
            DevicePolicyManager.EXTRA_PROVISIONING_MODE,
            DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE,
        )
        // Hand the extras back so they reach ADMIN_POLICY_COMPLIANCE + PROVISIONING_COMPLETE too.
        adminExtras(intent)?.let { result.putExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE, it) }
        Log.i(TAG, "GET_PROVISIONING_MODE → fully managed device")
        setResult(RESULT_OK, result)
        finish()
    }
    private companion object { const val TAG = "Provisioning" }
}

class PolicyComplianceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adminExtras(intent)?.let { AgentConfig.get(this).seedFromProvisioningExtras(it) }
        Log.i(TAG, "ADMIN_POLICY_COMPLIANCE → starting agent")
        MdmService.start(this)
        setResult(RESULT_OK)
        finish()
    }
    private companion object { const val TAG = "Provisioning" }
}
