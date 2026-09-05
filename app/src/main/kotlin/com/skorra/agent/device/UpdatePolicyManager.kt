package com.skorra.agent.device

import android.app.admin.FreezePeriod
import android.app.admin.SystemUpdatePolicy
import android.util.Log
import com.skorra.agent.DeviceOwner
import org.json.JSONObject
import java.time.MonthDay

/**
 * Maps the server's `update_policy` config object onto
 * [android.app.admin.DevicePolicyManager.setSystemUpdatePolicy].
 *
 * Contract (all fields optional except mode):
 *   {"mode": "automatic" | "windowed" | "postpone" | "default",
 *    "window_start": <minutes after midnight>, "window_end": <minutes>,   // windowed only
 *    "freeze_periods": [{"start": "MM-DD", "end": "MM-DD"}, ...]}
 *
 * "default" (or an empty/absent object) clears the policy back to user-controlled updates.
 */
object UpdatePolicyManager {

    private const val TAG = "UpdatePolicy"

    fun apply(deviceOwner: DeviceOwner, json: String) {
        if (!deviceOwner.isDeviceOwner) return
        try {
            val cfg = if (json.isBlank()) JSONObject() else JSONObject(json)
            val policy = build(cfg)
            deviceOwner.dpm.setSystemUpdatePolicy(deviceOwner.admin, policy)
            Log.i(TAG, "system update policy applied: ${cfg.optString("mode", "default")}")
        } catch (e: Exception) {
            // Invalid freeze periods (too long / too close together) throw here; keep the
            // agent alive and leave the previous policy in place.
            Log.w(TAG, "failed to apply update policy: ${e.message}")
        }
    }

    private fun build(cfg: JSONObject): SystemUpdatePolicy? {
        val policy = when (cfg.optString("mode", "default")) {
            "automatic" -> SystemUpdatePolicy.createAutomaticInstallPolicy()
            "windowed" -> SystemUpdatePolicy.createWindowedInstallPolicy(
                cfg.optInt("window_start", 0),
                cfg.optInt("window_end", 240),
            )
            "postpone" -> SystemUpdatePolicy.createPostponeInstallPolicy()
            else -> return null // "default": clear the policy
        }
        val periods = cfg.optJSONArray("freeze_periods") ?: return policy
        val freezes = mutableListOf<FreezePeriod>()
        for (i in 0 until periods.length()) {
            val p = periods.optJSONObject(i) ?: continue
            val start = parseMonthDay(p.optString("start")) ?: continue
            val end = parseMonthDay(p.optString("end")) ?: continue
            freezes.add(FreezePeriod(start, end))
        }
        if (freezes.isNotEmpty()) policy.freezePeriods = freezes
        return policy
    }

    /** "MM-DD" → MonthDay (returns null on garbage so one bad row doesn't kill the policy). */
    private fun parseMonthDay(s: String): MonthDay? = try {
        val (m, d) = s.split("-").map { it.toInt() }
        MonthDay.of(m, d)
    } catch (e: Exception) {
        null
    }
}
