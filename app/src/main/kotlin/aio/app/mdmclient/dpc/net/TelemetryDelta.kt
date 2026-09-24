package aio.app.mdmclient.dpc.net

import org.json.JSONObject

/**
 * Delta telemetry over the WebSocket, the same model as the firmware client
 * (MdmService.java buildDeltaPayload / gatedChanged / rememberSent).
 *
 * The baseline is what the server was last told. A WS frame carries only the gated keys that
 * changed since then, plus the volatile set; the server merges it into latest_extra. When no
 * gated key, battery_pct or temperature band moved, nothing is sent at all. A keyframe (the
 * whole payload) goes out on WS (re)connect, when the server asks for telemetry, and after a
 * failed send; the periodic HTTP check-in is the keyframe that replaces latest_extra outright
 * and clears keys the device stopped reporting.
 *
 * Unlike the firmware client, every key not listed as volatile is gated, rather than a
 * hand-kept gated list: this agent's extra grows with its capabilities, and a new key
 * should reach the server when it changes without anyone remembering to list it here.
 */
class TelemetryDelta {

    private val lock = Any()
    private var lastExtra: JSONObject? = null // guarded by lock
    private var lastBattery: Int? = null      // guarded by lock
    @Volatile var forceKeyframe = true

    /**
     * The frame to send for [full] (a complete check-in payload from [Telemetry.buildCheckin]),
     * or null when the server already knows everything that matters in it.
     */
    fun frameFor(full: JSONObject): JSONObject? = synchronized(lock) {
        val base = lastExtra
        if (forceKeyframe || base == null) return JSONObject(full.toString())
        val cur = full.optJSONObject("extra") ?: JSONObject()
        val battery = full.batteryOrNull()
        val batteryChanged = battery != lastBattery
        if (!batteryChanged && !gatedChanged(base, cur) && !tempTrigger(base, cur)) return null

        val extra = JSONObject()
        for (k in IDENTITY_EXTRA_KEYS) if (cur.has(k)) extra.put(k, cur.get(k))
        for (k in VOLATILE_EXTRA_KEYS) if (cur.has(k)) extra.put(k, cur.get(k))
        for (k in gatedKeys(base, cur)) {
            if (same(base, cur, k)) continue
            // A gated key the device stopped reporting is sent as null: a merge can't
            // delete it, and leaving the old value would keep showing it as current.
            extra.put(k, if (cur.has(k)) cur.get(k) else JSONObject.NULL)
        }
        JSONObject().apply {
            for (k in IDENTITY_KEYS) if (full.has(k)) put(k, full.get(k))
            if (batteryChanged && battery != null) put("battery_pct", battery)
            put("extra", extra)
        }
    }

    /** Record [full] as what the server now knows, after a send that went out. */
    fun remember(full: JSONObject) = synchronized(lock) {
        lastExtra = full.optJSONObject("extra")?.let { JSONObject(it.toString()) }
        lastBattery = full.batteryOrNull()
        forceKeyframe = false
    }

    private fun gatedKeys(base: JSONObject, cur: JSONObject): Set<String> =
        (base.keys().asSequence() + cur.keys().asSequence())
            .filter { it !in VOLATILE_EXTRA_KEYS && it !in IDENTITY_EXTRA_KEYS }
            .toSet()

    private fun gatedChanged(base: JSONObject, cur: JSONObject): Boolean =
        gatedKeys(base, cur).any { !same(base, cur, it) }

    private fun same(base: JSONObject, cur: JSONObject, k: String): Boolean =
        base.opt(k)?.toString() == cur.opt(k)?.toString()

    /** Temperature moved enough to push out of band, so the running-hot surfaces stay fresh. */
    private fun tempTrigger(base: JSONObject, cur: JSONObject): Boolean =
        moved(base, cur, "battery_temp_c", step = 2.0, warn = 38.0, danger = 45.0) ||
            // An SoC wanders several degrees at idle; the bands are the server's CPU
            // thresholds (db.CPUTempWarn and its danger level).
            moved(base, cur, "cpu_temp_c", step = 5.0, warn = 70.0, danger = 85.0)

    private fun moved(base: JSONObject, cur: JSONObject, k: String, step: Double, warn: Double, danger: Double): Boolean {
        if (!cur.has(k)) return false
        if (!base.has(k)) return true
        val c = cur.optDouble(k)
        val p = base.optDouble(k)
        fun band(t: Double) = if (t >= danger) 2 else if (t >= warn) 1 else 0
        return Math.abs(c - p) >= step || band(c) != band(p)
    }

    private fun JSONObject.batteryOrNull(): Int? = if (has("battery_pct")) optInt("battery_pct") else null

    companion object {
        /** Top-level fields every frame carries: the server rejects a frame without the first two. */
        private val IDENTITY_KEYS = listOf("serial_number", "build_id", "product")

        /** Always carried: the server's DPC gate and agent_kind both read agent_type per frame. */
        private val IDENTITY_EXTRA_KEYS = setOf("agent_type")

        /** Ride along in any frame that is sent, but never cause one by changing. */
        val VOLATILE_EXTRA_KEYS = setOf(
            "uptime_seconds", "ram_usage_mb", "wifi_rssi",
            "battery_temp_c", "cpu_temp_c", "location_age_s",
        )
    }
}
