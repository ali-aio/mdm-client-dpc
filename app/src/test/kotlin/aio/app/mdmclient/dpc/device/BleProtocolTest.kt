package aio.app.mdmclient.dpc.device

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The lost-device Bluetooth contract (protocol B): what is heard, what is reported. */
class BleProtocolTest {

    @Test fun tokenRoundTrip() {
        val b = BleProtocol.parseToken("0a1B2c3D4e5F6071")!!
        assertArrayEquals(byteArrayOf(0x0a, 0x1b, 0x2c, 0x3d, 0x4e, 0x5f, 0x60, 0x71), b)
        assertEquals("0a1b2c3d4e5f6071", BleProtocol.tokenHex(b))
    }

    @Test fun badTokensAreRejected() {
        for (bad in listOf(null, "", "abc", "0a1b2c3d4e5f607", "0a1b2c3d4e5f60711", "zz1b2c3d4e5f6071")) {
            assertNull("token $bad", BleProtocol.parseToken(bad))
        }
        assertNull(BleProtocol.tokenHex(byteArrayOf(1, 2, 3)))
        assertNull(BleProtocol.tokenHex(null))
    }

    @Test fun longerServiceDataUsesTheFirstEightBytes() {
        val data = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        assertEquals("0102030405060708", BleProtocol.tokenHex(data))
    }

    @Test fun listenConfigParsesAndClamps() {
        val c = BleProtocol.parseListen("""{"beacons":["0A1B2C3D4E5F6071","bad"],"scan_s":1,"every_s":5}""")!!
        assertEquals(setOf("0a1b2c3d4e5f6071"), c.beacons)
        assertEquals(2, c.scanS)
        assertEquals(30, c.everyS)
        val d = BleProtocol.parseListen("""{"beacons":["0a1b2c3d4e5f6071"]}""")!!
        assertEquals(8, d.scanS)
        assertEquals(120, d.everyS)
        val e = BleProtocol.parseListen("""{"beacons":["0a1b2c3d4e5f6071"],"scan_s":99,"every_s":99999}""")!!
        assertEquals(30, e.scanS)
        assertEquals(900, e.everyS)
    }

    @Test fun nothingToListenForMeansNoScan() {
        assertNull(BleProtocol.parseListen(null))
        assertNull(BleProtocol.parseListen(""))
        assertNull(BleProtocol.parseListen("not json"))
        assertNull(BleProtocol.parseListen("""{"beacons":[]}"""))
        assertNull(BleProtocol.parseListen("""{"beacons":["nope"]}"""))
        assertNull(BleProtocol.parseListen("""{"scan_s":8}"""))
    }

    @Test fun strongestPerWantedTokenOnly() {
        val a = "0a1b2c3d4e5f6071"; val b = "1111111111111111"; val other = "2222222222222222"
        val best = BleProtocol.strongest(
            listOf(a to -80, a to -61, b to -90, other to -40, a to -70),
            wanted = setOf(a, b),
        )
        assertEquals(mapOf(a to -61, b to -90), best)
    }

    @Test fun aDeviceNeverReportsItself() {
        val mine = "0a1b2c3d4e5f6071"
        val best = BleProtocol.strongest(listOf(mine to -30), wanted = setOf(mine), own = mine)
        assertTrue(best.isEmpty())
        assertNull(BleProtocol.sightingsJson(best, 1L))
    }

    @Test fun sightingsShape() {
        val arr = BleProtocol.sightingsJson(mapOf("1111111111111111" to -90, "0a1b2c3d4e5f6071" to -61), 1760000000000L)
        assertNotNull(arr)
        assertEquals(2, arr!!.length())
        val first = arr.getJSONObject(0)
        assertEquals("0a1b2c3d4e5f6071", first.getString("beacon")) // strongest first
        assertEquals(-61, first.getInt("rssi"))
        assertEquals(1760000000000L, first.getLong("at_ms"))
    }

    @Test fun statusShape() {
        assertTrue(BleProtocol.status(true).getBoolean("ok"))
        assertFalse(BleProtocol.status(true).has("why"))
        val f = BleProtocol.status(false, "no Bluetooth LE on this device")
        assertFalse(f.getBoolean("ok"))
        assertEquals("no Bluetooth LE on this device", f.getString("why"))
    }
}
