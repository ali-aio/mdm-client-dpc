package aio.app.mdmclient.dpc.device

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import aio.app.mdmclient.dpc.DeviceOwner
import org.json.JSONArray
import org.json.JSONObject

/**
 * Saved Wi-Fi networks, for the `wifi_config` command and the `network` config's
 * wifi_networks. Device Owner is exempt from the Android 10+ rule that makes
 * addNetwork/updateNetwork/removeNetwork fail for apps, and from the empty
 * getConfiguredNetworks — but that call also needs ACCESS_FINE_LOCATION, which the agent
 * grants itself here (the permission only; location stays off unless the server asks).
 *
 * The one rule that matters: never drop the network the device is on. A venue adds its new
 * network first and changes the router second, so an update of the current network is done
 * in place (updateNetwork), never as remove-and-add, and removing it is refused.
 */
class WifiConfigurator(private val ctx: Context, private val deviceOwner: DeviceOwner) {

    class Result(val ok: Boolean, val error: String = "")

    private val wm: WifiManager
        get() = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    /** Add [ssid], or update it in place when it is already saved. */
    @Suppress("DEPRECATION")
    fun addOrUpdate(ssid: String, security: String, password: String, hidden: Boolean): Result {
        if (!deviceOwner.isDeviceOwner) return Result(false, "not device owner")
        if (ssid.isBlank() || ssid.length > 32) return Result(false, "ssid must be 1-32 characters")
        val sec = security.ifBlank { "wpa2" }
        if (sec !in SECURITIES) return Result(false, "unknown security: $sec")
        if (sec != "open" && password.length !in 8..63) return Result(false, "password must be 8-63 characters")
        // SAE needs setSecurityParams (Android 11): before it, PMF can't be required from
        // the public API and a half-configured SAE network never joins.
        if (sec == "wpa3" && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return Result(false, "wpa3 needs Android 11")
        }
        if (sec == "wpa3" && !wm.isWpa3SaeSupported) return Result(false, "wpa3 not supported on this device")
        ensureListAccess()
        return runCatching {
            val wc = WifiConfiguration().apply {
                SSID = quoted(ssid)
                hiddenSSID = hidden
                when (sec) {
                    "open" -> setSecurity(this, WifiConfiguration.SECURITY_TYPE_OPEN, WifiConfiguration.KeyMgmt.NONE)
                    "wpa3" -> {
                        setSecurity(this, WifiConfiguration.SECURITY_TYPE_SAE, WifiConfiguration.KeyMgmt.SAE)
                        preSharedKey = quoted(password)
                    }
                    else -> {
                        setSecurity(this, WifiConfiguration.SECURITY_TYPE_PSK, WifiConfiguration.KeyMgmt.WPA_PSK)
                        preSharedKey = quoted(password)
                    }
                }
            }
            val existing = saved()?.firstOrNull { it.SSID == wc.SSID }
            if (existing != null) {
                wc.networkId = existing.networkId
                if (wm.updateNetwork(wc) >= 0) {
                    Log.i(TAG, "wifi '$ssid' updated")
                    return@runCatching Result(true)
                }
                // Saved by someone else (Settings, setup wizard) and not ours to edit. Replacing
                // it is fine only when the device is not on it right now.
                if (existing.networkId == currentNetworkId()) {
                    return@runCatching Result(false, "connected network could not be updated in place")
                }
                wm.removeNetwork(existing.networkId)
            }
            val id = wm.addNetwork(wc)
            if (id < 0) return@runCatching Result(false, "rejected by WifiManager")
            // Allow auto-join without forcing a switch away from the current network.
            wm.enableNetwork(id, false)
            Log.i(TAG, "wifi '$ssid' added")
            Result(true)
        }.getOrElse { Result(false, it.message ?: it.javaClass.simpleName) }
    }

    /** Forget [ssid]; refused while the device is connected to it. */
    @Suppress("DEPRECATION")
    fun remove(ssid: String): Result {
        if (!deviceOwner.isDeviceOwner) return Result(false, "not device owner")
        if (ssid.isBlank()) return Result(false, "missing ssid")
        ensureListAccess()
        return runCatching {
            val list = saved() ?: return@runCatching Result(false, "saved networks not readable")
            val matches = list.filter { it.SSID == quoted(ssid) }
            if (matches.isEmpty()) return@runCatching Result(true) // already gone
            val current = currentNetworkId()
            if (matches.any { it.networkId == current }) return@runCatching Result(false, "connected")
            matches.forEach { wm.removeNetwork(it.networkId) }
            Log.i(TAG, "wifi '$ssid' removed")
            Result(true)
        }.getOrElse { Result(false, it.message ?: it.javaClass.simpleName) }
    }

    /**
     * `extra.wifi_networks`: every saved network, never a password. null when the list can't
     * be read (not Device Owner, or the location permission is missing) — an empty list would
     * claim the device has no saved networks.
     */
    @Suppress("DEPRECATION")
    fun report(): JSONArray? {
        if (!deviceOwner.isDeviceOwner) return null
        ensureListAccess()
        val list = runCatching { saved() }.getOrNull() ?: return null
        val current = currentNetworkId()
        val out = JSONArray()
        for (wc in list.sortedBy { it.SSID }) {
            out.put(JSONObject().apply {
                put("ssid", unquoted(wc.SSID))
                put("security", securityOf(wc))
                put("connected", current >= 0 && wc.networkId == current)
            })
        }
        return out
    }

    /**
     * Switch to a saved network, reversibly (0.7.0; device page Wi-Fi card, W1, 22 Oct
     * 2026). Joins [ssid] and keeps it only if the MDM answers from there within 90 s,
     * otherwise rejoins the network it was on. A Device Owner may still use
     * enableNetwork/reconnect. Blocks; call off the main thread. Returns the command output.
     */
    @Suppress("DEPRECATION")
    fun connect(ssid: String): JSONObject {
        ensureListAccess()
        val list = saved() ?: return JSONObject().put("error", "switch_failed").put("reason", "cannot read saved networks")
        val target = list.firstOrNull { unquoted(it.SSID) == ssid }
            ?: return JSONObject().put("error", "switch_failed").put("reason", "unknown_network")
        val prevId = currentNetworkId()
        val prevSsid = list.firstOrNull { it.networkId == prevId }?.let { unquoted(it.SSID) }.orEmpty()
        if (prevId == target.networkId) return JSONObject().put("ok", true).put("after_s", 0)
        var authFailed = false
        val rx = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: android.content.Intent) {
                if (i.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR, -1) == WifiManager.ERROR_AUTHENTICATING) authFailed = true
            }
        }
        ctx.applicationContext.registerReceiver(rx, android.content.IntentFilter(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION))
        val seen = inRange(ssid)
        var joined = false
        val start = android.os.SystemClock.elapsedRealtime()
        try {
            Log.i(TAG, "switching $prevSsid -> $ssid")
            wm.enableNetwork(target.networkId, true)
            wm.reconnect()
            while (android.os.SystemClock.elapsedRealtime() - start < SWITCH_MS && !authFailed) {
                Thread.sleep(1000)
                if (currentNetworkId() == target.networkId) {
                    joined = true
                    if (mdmAnswers()) {
                        reenableAll(list)
                        return JSONObject().put("ok", true).put("ssid", ssid)
                            .put("after_s", (android.os.SystemClock.elapsedRealtime() - start) / 1000)
                    }
                }
            }
        } finally {
            runCatching { ctx.applicationContext.unregisterReceiver(rx) }
        }
        val reason = when {
            authFailed -> "auth_failed"
            joined -> "no_internet"
            !seen && !inRange(ssid) -> "not_in_range"
            else -> "timeout"
        }
        Log.w(TAG, "switch to $ssid failed ($reason) — back to $prevSsid")
        if (prevId != -1) { wm.enableNetwork(prevId, true); wm.reconnect() }
        reenableAll(list)
        return JSONObject().put("error", "switch_failed").put("reason", reason).put("ssid", ssid).put("reverted_to", prevSsid)
    }

    /** enableNetwork(id, true) may leave the others disabled on older releases: undo that. */
    @Suppress("DEPRECATION")
    private fun reenableAll(list: List<WifiConfiguration>) {
        val cur = currentNetworkId()
        for (c in list) if (c.networkId != cur) runCatching { wm.enableNetwork(c.networkId, false) }
    }

    @Suppress("DEPRECATION")
    private fun inRange(ssid: String): Boolean = runCatching { wm.scanResults.any { it.SSID == ssid } }.getOrDefault(false)

    private fun mdmAnswers(): Boolean = runCatching {
        val c = java.net.URL(aio.app.mdmclient.dpc.AgentConfig.get(ctx).apiUrl("/health")).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 5000
        try { c.responseCode == 200 } finally { c.disconnect() }
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun saved(): List<WifiConfiguration>? {
        if (ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return null
        return wm.configuredNetworks
    }

    @Suppress("DEPRECATION")
    private fun currentNetworkId(): Int = runCatching { wm.connectionInfo?.networkId ?: -1 }.getOrDefault(-1)

    private fun ensureListAccess() = deviceOwner.grantSelf(android.Manifest.permission.ACCESS_FINE_LOCATION)

    @Suppress("DEPRECATION")
    private fun setSecurity(wc: WifiConfiguration, type: Int, legacyKeyMgmt: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wc.setSecurityParams(type)
        } else {
            wc.allowedKeyManagement.set(legacyKeyMgmt)
        }
    }

    @Suppress("DEPRECATION")
    private fun securityOf(wc: WifiConfiguration): String {
        val km = wc.allowedKeyManagement
        return when {
            km.get(WifiConfiguration.KeyMgmt.SAE) -> "wpa3"
            km.get(WifiConfiguration.KeyMgmt.WPA_PSK) -> "wpa2"
            km.get(WifiConfiguration.KeyMgmt.NONE) && wc.wepKeys.all { it.isNullOrEmpty() } -> "open"
            else -> "other"
        }
    }

    companion object {
        private const val SWITCH_MS = 90_000L
        private const val TAG = "WifiConfig"
        private val SECURITIES = setOf("open", "wpa2", "wpa3")

        private fun quoted(s: String) = "\"" + s + "\""
        private fun unquoted(s: String?) = s.orEmpty().removeSurrounding("\"")
    }
}
