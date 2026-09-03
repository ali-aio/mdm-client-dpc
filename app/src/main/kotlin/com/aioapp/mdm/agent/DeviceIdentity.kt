package com.aioapp.mdm.agent

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * Resolves the stable identifiers the server keys devices on. A Device Owner is permitted to read
 * the hardware serial via [Build.getSerial]; if that ever fails we fall back to ANDROID_ID so a
 * device still enrolls with a stable id.
 */
object DeviceIdentity {

    @SuppressLint("HardwareIds")
    fun serial(ctx: Context): String {
        val hw = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Build.getSerial() else @Suppress("DEPRECATION") Build.SERIAL
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
        if (!hw.isNullOrBlank() && hw != Build.UNKNOWN) return hw

        val androidId = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
        return if (!androidId.isNullOrBlank()) "android-$androidId" else "unknown-${Build.MODEL}"
    }

    /** Server `build_id` field — the OS build fingerprint id. */
    fun buildId(): String = Build.DISPLAY.ifBlank { Build.ID }

    /**
     * Server `product` field (hardware category). For a generic DPC agent we report the device
     * model/product rather than the T7/kiosk categories the AOSP client used.
     */
    fun product(): String = Build.PRODUCT.ifBlank { Build.MODEL }
}
