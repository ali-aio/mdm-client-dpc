package aio.app.mdmclient.dpc.device

import android.content.Context
import android.util.Base64
import android.util.Log
import aio.app.mdmclient.dpc.DeviceOwner
import org.json.JSONObject

/**
 * Applies the server's `network` config object via Device Owner APIs:
 *
 *   {"ca_certs":      [{"name": "corp-root", "pem_b64": "<base64 DER or PEM>"}],
 *    "wifi_networks": [{"ssid": "Shop", "security": "wpa2"|"open", "psk": "..."}],
 *    "vpn":           {"package": "com.corp.vpn", "lockdown": true}}      // "" pkg = clear
 *
 * Everything is idempotent: certs are content-addressed by the platform (re-install is a
 * no-op), Wi-Fi updates any existing config for the same SSID in place, VPN sets/clears the
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
                addWifi(ctx, deviceOwner, n)
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

    // Same path as the wifi_config command: updates a saved SSID in place, so a PSK rotation
    // no longer removes (and drops) the network the device is on.
    private fun addWifi(ctx: Context, deviceOwner: DeviceOwner, n: JSONObject) {
        val ssid = n.optString("ssid")
        if (ssid.isBlank()) return
        val r = WifiConfigurator(ctx, deviceOwner).addOrUpdate(
            ssid, n.optString("security", "wpa2"), n.optString("psk"), n.optBoolean("hidden", false),
        )
        if (r.ok) Log.i(TAG, "wifi '$ssid' provisioned") else Log.w(TAG, "wifi '$ssid' failed: ${r.error}")
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
