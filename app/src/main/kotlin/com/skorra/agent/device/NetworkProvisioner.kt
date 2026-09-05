package com.skorra.agent.device

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.util.Base64
import android.util.Log
import com.skorra.agent.DeviceOwner
import org.json.JSONObject

/**
 * Applies the server's `network` config object via Device Owner APIs:
 *
 *   {"ca_certs":      [{"name": "corp-root", "pem_b64": "<base64 DER or PEM>"}],
 *    "wifi_networks": [{"ssid": "Shop", "security": "wpa2"|"open", "psk": "..."}],
 *    "vpn":           {"package": "com.corp.vpn", "lockdown": true}}      // "" pkg = clear
 *
 * Everything is idempotent: certs are content-addressed by the platform (re-install is a
 * no-op), Wi-Fi replaces any existing config for the same SSID, VPN sets/clears the
 * always-on package. Failures log per-item and never take the agent down.
 */
object NetworkProvisioner {

    private const val TAG = "NetProvision"

    fun apply(ctx: Context, deviceOwner: DeviceOwner, json: String) {
        if (json.isBlank() || !deviceOwner.isDeviceOwner) return
        val cfg = try { JSONObject(json) } catch (e: Exception) { return }
        cfg.optJSONArray("ca_certs")?.let { certs ->
            for (i in 0 until certs.length()) {
                val c = certs.optJSONObject(i) ?: continue
                installCaCert(deviceOwner, c.optString("name"), c.optString("pem_b64"))
            }
        }
        cfg.optJSONArray("wifi_networks")?.let { nets ->
            for (i in 0 until nets.length()) {
                val n = nets.optJSONObject(i) ?: continue
                addWifi(ctx, n)
            }
        }
        cfg.optJSONObject("vpn")?.let { vpn -> applyVpn(deviceOwner, vpn) }
    }

    private fun installCaCert(deviceOwner: DeviceOwner, name: String, pemB64: String) {
        if (pemB64.isBlank()) return
        runCatching {
            val bytes = Base64.decode(pemB64, Base64.DEFAULT)
            val ok = deviceOwner.dpm.installCaCert(deviceOwner.admin, bytes)
            Log.i(TAG, "CA cert '$name' install: $ok")
        }.onFailure { Log.w(TAG, "CA cert '$name' failed: ${it.message}") }
    }

    @Suppress("DEPRECATION")
    private fun addWifi(ctx: Context, n: JSONObject) {
        val ssid = n.optString("ssid")
        if (ssid.isBlank()) return
        runCatching {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            // WifiConfiguration is deprecated but remains the only API that lets a Device
            // Owner add a network the device auto-joins (suggestions need user approval).
            val wc = WifiConfiguration().apply {
                SSID = "\"" + ssid + "\""
                when (n.optString("security", "wpa2")) {
                    "open" -> allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                    else -> preSharedKey = "\"" + n.optString("psk") + "\""
                }
            }
            // Replace an existing config for this SSID so PSK rotations apply.
            wm.configuredNetworks?.firstOrNull { it.SSID == wc.SSID }?.let { wm.removeNetwork(it.networkId) }
            val id = wm.addNetwork(wc)
            if (id >= 0) {
                wm.enableNetwork(id, false)
                Log.i(TAG, "wifi '$ssid' provisioned")
            } else {
                Log.w(TAG, "wifi '$ssid' rejected by WifiManager")
            }
        }.onFailure { Log.w(TAG, "wifi '$ssid' failed: ${it.message}") }
    }

    private fun applyVpn(deviceOwner: DeviceOwner, vpn: JSONObject) {
        val pkg = vpn.optString("package")
        runCatching {
            deviceOwner.dpm.setAlwaysOnVpnPackage(
                deviceOwner.admin,
                pkg.ifBlank { null },
                vpn.optBoolean("lockdown", false),
            )
            Log.i(TAG, "always-on VPN: ${pkg.ifBlank { "cleared" }}")
        }.onFailure { Log.w(TAG, "always-on VPN failed: ${it.message}") }
    }
}
