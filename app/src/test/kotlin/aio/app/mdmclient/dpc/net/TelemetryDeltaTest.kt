package aio.app.mdmclient.dpc.net

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A wrong "nothing changed" leaves the dashboard showing a stale value for as long as the
 * next HTTP keyframe takes; a wrong "changed" is only traffic. Each case is one of the first.
 */
class TelemetryDeltaTest {

    private fun payload(battery: Int? = 80, extra: JSONObject.() -> Unit = {}) = JSONObject().apply {
        put("serial_number", "SN1")
        put("build_id", "b1")
        put("product", "dongle")
        put("apps_hash", "abc")
        battery?.let { put("battery_pct", it) }
        put("extra", JSONObject().apply {
            put("agent_type", "dpc")
            put("uptime_seconds", 100)
            put("charging", true)
            put("storage_free_gb", 3.2)
            put("cpu_temp_c", 58.0)
            extra()
        })
    }

    private fun primed(first: JSONObject = payload()) = TelemetryDelta().also {
        assertNotNull(it.frameFor(first))
        it.remember(first)
    }

    @Test fun firstFrameIsTheWholePayload() {
        val full = payload()
        val frame = TelemetryDelta().frameFor(full)!!
        assertEquals(full.toString(), frame.toString())
    }

    @Test fun nothingChangedSendsNothing() {
        val d = primed()
        assertNull(d.frameFor(payload { put("uptime_seconds", 160); put("cpu_temp_c", 59.0) }))
    }

    @Test fun gatedChangeSendsOnlyWhatChangedPlusVolatileAndIdentity() {
        val d = primed()
        val frame = d.frameFor(payload { put("charging", false); put("uptime_seconds", 160) })!!
        val extra = frame.getJSONObject("extra")
        assertEquals(false, extra.get("charging"))
        assertEquals(160, extra.get("uptime_seconds"))
        assertEquals("dpc", extra.get("agent_type"))
        assertFalse(extra.has("storage_free_gb"))
        assertEquals("SN1", frame.get("serial_number"))
        assertEquals("b1", frame.get("build_id"))
        assertFalse(frame.has("battery_pct"))
        assertFalse(frame.has("apps_hash"))
    }

    @Test fun anUnlistedKeyIsGated() {
        val d = primed()
        val frame = d.frameFor(payload { put("screen_on", false) })!!
        assertEquals(false, frame.getJSONObject("extra").get("screen_on"))
    }

    @Test fun nestedValueChangeIsSeen() {
        val d = primed(payload { put("capabilities", org.json.JSONArray(listOf("shell"))) })
        assertNotNull(d.frameFor(payload { put("capabilities", org.json.JSONArray(listOf("shell", "screen"))) }))
    }

    @Test fun batteryChangeIsSent() {
        val d = primed()
        assertEquals(79, d.frameFor(payload(battery = 79))!!.get("battery_pct"))
    }

    @Test fun droppedKeyIsSentAsNull() {
        val d = primed(payload { put("wifi", "Resto") })
        val extra = d.frameFor(payload())!!.getJSONObject("extra")
        assertTrue(extra.has("wifi"))
        assertEquals(JSONObject.NULL, extra.get("wifi"))
    }

    @Test fun cpuTemperatureCrossingABandIsSent() {
        val d = primed(payload { put("cpu_temp_c", 68.0) })
        assertNull(d.frameFor(payload { put("cpu_temp_c", 69.5) }))
        assertNotNull(d.frameFor(payload { put("cpu_temp_c", 70.5) }))
    }

    @Test fun forcedKeyframeResendsEverything() {
        val d = primed()
        d.forceKeyframe = true
        val frame = d.frameFor(payload())!!
        assertEquals(3.2, frame.getJSONObject("extra").getDouble("storage_free_gb"), 0.0)
        assertEquals("abc", frame.get("apps_hash"))
    }

    @Test fun rememberedFrameIsTheNewBaseline() {
        val d = primed()
        val next = payload { put("charging", false) }
        assertNotNull(d.frameFor(next))
        d.remember(next)
        assertNull(d.frameFor(next))
    }
}
