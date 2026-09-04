package com.skorra.agent

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context

/**
 * Thin helper around [DevicePolicyManager] + this app's admin component. Phase 2+ command
 * handlers call through here so there's one place that owns the admin ComponentName.
 */
class DeviceOwner(private val ctx: Context) {

    val admin: ComponentName = ComponentName(ctx, MdmDeviceAdminReceiver::class.java)

    val dpm: DevicePolicyManager =
        ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    /** True when this app has been provisioned as Device Owner (via adb or managed provisioning). */
    val isDeviceOwner: Boolean
        get() = dpm.isDeviceOwnerApp(ctx.packageName)

    val isAdminActive: Boolean
        get() = dpm.isAdminActive(admin)
}
