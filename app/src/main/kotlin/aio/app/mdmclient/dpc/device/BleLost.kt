package aio.app.mdmclient.dpc.device

import android.annotation.SuppressLint
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import aio.app.mdmclient.dpc.DeviceOwner
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections

/**
 * The lost device's beacon (protocol B): while lost, advertise this session's token as service
 * data under [BleProtocol.SERVICE_UUID] — non-connectable, low power, no name, no TX power, so
 * the whole advertisement is the token (29 of 31 bytes). Started by [LostMode.enter] and on
 * re-assert after boot; stopped when lost mode lifts, and Bluetooth goes back off if we
 * turned it on for this.
 */
object BleBeacon {
    private const val TAG = "BleBeacon"

    @Volatile private var callback: AdvertiseCallback? = null
    @Volatile private var turnedOn = false
    @Volatile private var token: String? = null
    @Volatile private var status: JSONObject? = null

    /** The token being advertised, so this device never reports hearing itself. */
    val currentToken: String? get() = token

    /** `extra.lost_beacon`, or null when no beacon was asked for. */
    fun report(): JSONObject? = status

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(ctx: Context, owner: DeviceOwner, tokenHex: String?) {
        val bytes = BleProtocol.parseToken(tokenHex)
        if (bytes == null) { stop(ctx, owner); return } // no beacon asked for (or a bad one)
        val hex = BleProtocol.tokenHex(bytes)!!
        if (callback != null && hex == token) return // already advertising this token
        stopAdvertising(ctx, owner)
        token = hex
        val radio = BleRadio(ctx, owner)
        radio.grantPermissions()
        radio.advertiseBlocker()?.let { fail(it); return }
        val (on, weTurned) = radio.ensureOn()
        turnedOn = turnedOn || weTurned
        if (!on) { fail("Bluetooth is off and could not be switched on"); return }
        val adv = radio.adapter?.bluetoothLeAdvertiser
        if (adv == null || runCatching { !radio.adapter!!.isMultipleAdvertisementSupported }.getOrDefault(false)) {
            fail("this Bluetooth chip cannot advertise"); return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(false)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceData(ParcelUuid(BleProtocol.SERVICE_UUID), bytes)
            .build()
        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                status = BleProtocol.status(true)
                Log.i(TAG, "advertising lost beacon")
            }
            override fun onStartFailure(errorCode: Int) {
                callback = null
                fail("advertising failed (error $errorCode)")
            }
        }
        callback = cb
        status = BleProtocol.status(false, "starting")
        runCatching { adv.startAdvertising(settings, data, cb) }
            .onFailure { callback = null; fail("advertising failed: ${it.message}") }
    }

    @Synchronized
    fun stop(ctx: Context, owner: DeviceOwner) {
        stopAdvertising(ctx, owner)
        token = null
        status = null
        if (turnedOn) {
            BleRadio(ctx, owner).restore(true)
            turnedOn = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertising(ctx: Context, owner: DeviceOwner) {
        val cb = callback ?: return
        callback = null
        runCatching { BleRadio(ctx, owner).adapter?.bluetoothLeAdvertiser?.stopAdvertising(cb) }
    }

    private fun fail(why: String) {
        status = BleProtocol.status(false, why)
        Log.w(TAG, why)
    }
}

/**
 * Neighbour listener (protocol B): while the server names lost devices' tokens in
 * `lost_listen`, scan for a few seconds every couple of minutes and keep what was heard for the
 * next report. Nothing runs while the key is absent. Bluetooth (and, on Android ≤ 11, location)
 * is switched on only for the scan and back off afterwards if it was off.
 */
object BleListener {
    private const val TAG = "BleListener"

    @Volatile private var sightings: JSONArray? = null
    @Volatile private var sightingsAt = 0L       // elapsedRealtime of the scan behind [sightings]
    @Volatile private var scanStatus: JSONObject? = null
    @Volatile private var validForMs = 0L

    /** `extra.ble_sightings`, while the last scan's hits are still current. */
    fun sightingsReport(): JSONArray? {
        val s = sightings ?: return null
        return if (SystemClock.elapsedRealtime() - sightingsAt <= validForMs) s else null
    }

    /** `extra.ble_scan` — only when scanning was asked for and could not run. */
    fun scanReport(): JSONObject? = scanStatus

    /** Nothing to listen for: drop everything so no stale key is reported. */
    fun idle() {
        sightings = null
        scanStatus = null
    }

    /**
     * One scan for [cfg]. Blocking (runs on an IO thread for [BleProtocol.ListenConfig.scanS]
     * seconds). Returns true when it heard something, so the caller can report at once.
     */
    @SuppressLint("MissingPermission")
    fun scanOnce(ctx: Context, owner: DeviceOwner, cfg: BleProtocol.ListenConfig): Boolean {
        val radio = BleRadio(ctx, owner)
        radio.grantPermissions()
        radio.scanBlocker()?.let { return failed(it) }
        val (on, weTurnedBt) = radio.ensureOn()
        if (!on) return failed("Bluetooth is off and could not be switched on")
        val weTurnedLoc = radio.ensureLocationForScan()
        try {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S && !radio.locationOn()) {
                return failed("location is off (Android ≤ 11 needs it for Bluetooth scans)")
            }
            val scanner = radio.adapter?.bluetoothLeScanner ?: return failed("no LE scanner")
            val hits = Collections.synchronizedList(ArrayList<Pair<String, Int>>())
            val uuid = ParcelUuid(BleProtocol.SERVICE_UUID)
            val cb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult?) {
                    val tok = BleProtocol.tokenHex(result?.scanRecord?.getServiceData(uuid)) ?: return
                    hits.add(tok to result!!.rssi)
                }
                override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                    results?.forEach { onScanResult(0, it) }
                }
                override fun onScanFailed(errorCode: Int) {
                    Log.w(TAG, "scan failed: $errorCode")
                }
            }
            // A filter is what keeps a screen-off scan alive (unfiltered scans are paused when
            // the screen is off); results are matched in software too, since some chips' filters
            // are loose.
            val filter = ScanFilter.Builder().setServiceData(uuid, ByteArray(0)).build()
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            val started = runCatching { scanner.startScan(listOf(filter), settings, cb); true }
                .getOrElse { return failed("scan could not start: ${it.message}") }
            if (started) SystemClock.sleep(cfg.scanS * 1000L)
            runCatching { scanner.stopScan(cb) }
            val best = BleProtocol.strongest(ArrayList(hits), cfg.beacons, own = BleBeacon.currentToken)
            sightings = BleProtocol.sightingsJson(best, System.currentTimeMillis())
            sightingsAt = SystemClock.elapsedRealtime()
            validForMs = cfg.everyS * 1000L + 30_000L
            scanStatus = null
            if (best.isNotEmpty()) Log.i(TAG, "heard ${best.size} lost beacon(s)")
            return best.isNotEmpty()
        } finally {
            radio.restoreLocation(weTurnedLoc)
            // A beacon on this device needs the radio it may have found on.
            radio.restore(weTurnedBt && BleBeacon.currentToken == null)
        }
    }

    private fun failed(why: String): Boolean {
        scanStatus = BleProtocol.status(false, why)
        sightings = null
        Log.w(TAG, why)
        return false
    }
}
