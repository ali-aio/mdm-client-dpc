package aio.app.mdmclient.dpc.device

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * The lost-device Bluetooth LE contract shared with the firmware client and the server
 * (protocol "Lost-device reporting", B). Pure functions — no Android Bluetooth types — so the
 * parsing and filtering are unit-tested off-device.
 *
 * A lost device advertises the 8-byte token the server made for this lost session as service
 * data under [SERVICE_UUID]. Every other device at the venue is told which tokens to listen for
 * (config `lost_listen`) and reports what it heard as `extra.ble_sightings`.
 */
object BleProtocol {

    val SERVICE_UUID: UUID = UUID.fromString("6a1f0001-a10c-4d4d-9c5e-4c0574000001")

    const val TOKEN_BYTES = 8

    /** A token as the server writes it: 16 hex chars → 8 bytes, or null when malformed. */
    fun parseToken(hex: String?): ByteArray? {
        val h = hex?.trim()?.lowercase() ?: return null
        if (h.length != TOKEN_BYTES * 2 || !h.all { it in '0'..'9' || it in 'a'..'f' }) return null
        return ByteArray(TOKEN_BYTES) { i -> h.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    /** Service data as heard → the token's hex, or null when it isn't one of ours. */
    fun tokenHex(data: ByteArray?): String? {
        if (data == null || data.size < TOKEN_BYTES) return null
        return data.copyOfRange(0, TOKEN_BYTES).joinToString("") { "%02x".format(it) }
    }

    /** The `lost_listen` config: which tokens, how long to scan, how often. */
    data class ListenConfig(val beacons: Set<String>, val scanS: Int, val everyS: Int)

    /**
     * Parse `lost_listen`. Null (don't scan) when the key is absent, not an object, or names no
     * valid token. Durations are clamped so a bad push can't keep the radio busy: a scan of
     * 2–30 s, at most every 30 s and at least every 15 min.
     */
    fun parseListen(json: String?): ListenConfig? {
        if (json.isNullOrBlank()) return null
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val arr = o.optJSONArray("beacons") ?: return null
        val beacons = (0 until arr.length())
            .mapNotNull { parseToken(arr.optString(it))?.let { b -> tokenHex(b) } }
            .toSet()
        if (beacons.isEmpty()) return null
        val scanS = o.optInt("scan_s", 8).coerceIn(2, 30)
        val everyS = o.optInt("every_s", 120).coerceIn(30, 900).coerceAtLeast(scanS + 5)
        return ListenConfig(beacons, scanS, everyS)
    }

    /**
     * One scan's hits (token hex, rssi) → the strongest reading per wanted token. Tokens the
     * server didn't ask for are dropped, and so is [own] (a device never reports itself).
     */
    fun strongest(hits: List<Pair<String, Int>>, wanted: Set<String>, own: String? = null): Map<String, Int> {
        val out = HashMap<String, Int>()
        for ((tok, rssi) in hits) {
            if (tok !in wanted || tok == own) continue
            val cur = out[tok]
            if (cur == null || rssi > cur) out[tok] = rssi
        }
        return out
    }

    /** `extra.ble_sightings`: [{"beacon", "rssi", "at_ms"}], strongest first; null when empty. */
    fun sightingsJson(best: Map<String, Int>, atMs: Long): JSONArray? {
        if (best.isEmpty()) return null
        val arr = JSONArray()
        for ((tok, rssi) in best.entries.sortedByDescending { it.value }) {
            arr.put(JSONObject().put("beacon", tok).put("rssi", rssi).put("at_ms", atMs))
        }
        return arr
    }

    /** `extra.ble_scan` / `extra.lost_beacon`: {"ok": true} or {"ok": false, "why": "…"}. */
    fun status(ok: Boolean, why: String? = null): JSONObject =
        JSONObject().put("ok", ok).apply { if (!ok && why != null) put("why", why) }
}
