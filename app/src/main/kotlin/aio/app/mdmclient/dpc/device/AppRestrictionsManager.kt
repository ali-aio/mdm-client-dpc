package aio.app.mdmclient.dpc.device

import android.os.Bundle
import android.util.Log
import aio.app.mdmclient.dpc.DeviceOwner
import org.json.JSONArray
import org.json.JSONObject

/**
 * Managed app configurations: pushes each entry of the server's `app_restrictions` array
 * ([{"package": "...", "restrictions": {key: value, ...}}]) into
 * [android.app.admin.DevicePolicyManager.setApplicationRestrictions].
 *
 * Values map JSON → Bundle: bool, int/long, double→String (platform restriction bundles
 * have no double slot apps commonly read), string, array-of-string. An empty/missing
 * restrictions object clears that package's managed config.
 */
object AppRestrictionsManager {

    private const val TAG = "AppRestrictions"

    fun apply(deviceOwner: DeviceOwner, json: String) {
        if (json.isBlank() || !deviceOwner.isDeviceOwner) return
        val arr = try { JSONArray(json) } catch (e: Exception) { return }
        for (i in 0 until arr.length()) {
            val entry = arr.optJSONObject(i) ?: continue
            val pkg = entry.optString("package")
            if (pkg.isBlank()) continue
            runCatching {
                val bundle = toBundle(entry.optJSONObject("restrictions") ?: JSONObject())
                deviceOwner.dpm.setApplicationRestrictions(deviceOwner.admin, pkg, bundle)
                Log.i(TAG, "restrictions applied to $pkg (${bundle.size()} keys)")
            }.onFailure { Log.w(TAG, "restrictions for $pkg failed: ${it.message}") }
        }
    }

    private fun toBundle(o: JSONObject): Bundle {
        val b = Bundle()
        for (key in o.keys()) {
            when (val v = o.get(key)) {
                is Boolean -> b.putBoolean(key, v)
                is Int -> b.putInt(key, v)
                is Long -> b.putLong(key, v)
                is JSONArray -> b.putStringArray(
                    key,
                    (0 until v.length()).map { v.optString(it) }.toTypedArray(),
                )
                is JSONObject -> b.putBundle(key, toBundle(v))
                else -> b.putString(key, v.toString())
            }
        }
        return b
    }
}
