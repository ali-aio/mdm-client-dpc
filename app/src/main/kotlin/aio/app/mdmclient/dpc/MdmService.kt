package aio.app.mdmclient.dpc

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import aio.app.mdmclient.dpc.capture.ScreenCaptureConsentActivity
import aio.app.mdmclient.dpc.capture.ScreenCaptureService
import aio.app.mdmclient.dpc.device.ScreenControl
import aio.app.mdmclient.dpc.device.AgentUpdater
import aio.app.mdmclient.dpc.device.LogcatManager
import aio.app.mdmclient.dpc.net.Acker
import aio.app.mdmclient.dpc.net.ApiClient
import aio.app.mdmclient.dpc.net.CommandExecutor
import aio.app.mdmclient.dpc.net.Telemetry
import aio.app.mdmclient.dpc.net.WsClient
import okio.ByteString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Long-running foreground service: hosts the periodic HTTP checkin loop and the live WebSocket,
 * routes server messages to [CommandExecutor], and acks results.
 *
 * The WS carries realtime push (commands, config, pings); the HTTP checkin runs on an interval as
 * a safety net that also fetches config and reports full telemetry / app inventory.
 */
class MdmService : LifecycleService(), WsClient.Listener, Acker {

    private lateinit var config: AgentConfig
    private lateinit var deviceOwner: DeviceOwner
    private lateinit var api: ApiClient
    private lateinit var ws: WsClient
    private lateinit var executor: CommandExecutor
    private lateinit var logcat: LogcatManager
    private lateinit var serial: String

    @Volatile private var wantConnected = false
    @Volatile private var lastKnownAppsHash: String? = null
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        config = AgentConfig.get(this)
        deviceOwner = DeviceOwner(this)
        api = ApiClient(config).also { it.onUnauthorized = { onUnauthorized() } }
        ws = WsClient(config, this)
        executor = CommandExecutor(this, deviceOwner, this, lifecycleScope)
        deviceOwner.ensureIdentityAccess() // grant READ_PHONE_STATE before resolving the serial
        deviceOwner.ensureAutoTime()
        Grants.ensureAccessibility(this)
        serial = DeviceIdentity.serial(this)
        logcat = LogcatManager(this, serial)

        goForeground()
        Log.i(TAG, "MdmService started (deviceOwner=${deviceOwner.isDeviceOwner}, configured=${config.isConfigured})")

        AgentBus.acker = this

        // An agent update the previous version started: this one says how it went.
        AgentUpdater.settlePending(this) { id, status, output ->
            ackCommand(id, status, output = output)
        }

        // Re-enforce any persisted kiosk state after a (re)boot.
        if (deviceOwner.isDeviceOwner) executor.applyConfig(JSONObject())

        maybeStart()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // A restart (e.g. after Save & connect, or provisioning extras landing) may have added config.
        if (!wantConnected) maybeStart()
        return START_STICKY
    }

    /** Connect if configured; if we only hold an enrollment token, exchange it first. */
    private fun maybeStart() {
        when {
            config.isConfigured -> startTransport()
            config.needsEnrollment -> enrollThenStart()
        }
    }

    /**
     * The server rejected our device key. A key is per-device and never comes back, so
     * the only way forward is to enroll again — and this device can, whenever it still
     * knows the token it enrolled with. Without this the agent sits on a dead key and
     * repeats a 401 forever, which is exactly what it did after the onboarding screen
     * cleared a working key.
     */
    fun onUnauthorized() {
        val token = config.enrollToken.ifBlank { config.lastEnrollToken }
        if (token.isBlank()) return
        Log.w(TAG, "Device key rejected — re-enrolling with the token this device came with")
        config.apiKey = ""
        config.enrollToken = token
        enrollThenStart()
    }

    @Volatile private var enrolling = false

    private fun enrollThenStart() {
        if (enrolling) return
        enrolling = true
        lifecycleScope.launch(Dispatchers.IO) {
            var attempt = 0
            while (isActive && config.needsEnrollment) {
                val identity = JSONObject()
                    .put("serial", serial)
                    .put("product", DeviceIdentity.product())
                    .put("model", Build.MODEL)
                    .put("os_version", Build.VERSION.RELEASE ?: "")
                val resp = api.enroll(config.enrollToken, identity)
                val key = resp?.optString("device_key").orEmpty()
                if (key.isNotBlank()) {
                    config.apiKey = key
                    config.enrollToken = ""
                    // Where the device landed, per the server (profile intent): class, site,
                    // group. Empty fields mean the profile did not set them.
                    val r = resp ?: JSONObject() // non-null here: the key came out of it
                    val parts = mutableListOf(if (r.optBoolean("re_enrolled")) "Re-enrolled" else "Enrolled")
                    r.optString("device_class").takeIf { it.isNotBlank() }?.let { parts += it.uppercase() }
                    r.optString("site").takeIf { it.isNotBlank() }?.let { parts += it }
                    r.optString("group").takeIf { it.isNotBlank() }?.let { parts += it }
                    r.optString("profile").takeIf { it.isNotBlank() }?.let { parts += "via $it" }
                    if (!r.optBoolean("onboarded", true)) parts += "awaiting a site on the dashboard"
                    config.enrollSummary = parts.joinToString(" · ")
                    Log.i(TAG, "Enrolled — device key issued (${config.enrollSummary})")
                    startTransport()
                    break
                }
                // Server unreachable or token rejected: back off and retry (a revoked token
                // keeps failing here, visible in the onboarding screen's status).
                attempt++
                delay((30_000L * attempt).coerceAtMost(300_000L))
            }
            enrolling = false
        }
    }

    // ---- Transport lifecycle ----

    private fun startTransport() {
        wantConnected = true
        connectWs()
        startCheckinLoop()
    }

    private fun connectWs() {
        if (!wantConnected) return
        Log.i(TAG, "Connecting WS...")
        ws.connect(serial)
    }

    private fun startCheckinLoop() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive && wantConnected) {
                doCheckin()
                delay(config.checkinIntervalSeconds * 1000L)
            }
        }
    }

    private fun doCheckin() {
        // Send the app inventory only when the hash changed (or the server last asked for it).
        val currentHash = Telemetry.appsHash(this)
        val includeApps = currentHash != lastKnownAppsHash
        val payload = Telemetry.buildCheckin(this, includeApps)
        val resp = api.checkin(payload) ?: return
        lastKnownAppsHash = currentHash
        if (resp.optBoolean("send_apps", false) && !includeApps) {
            // Server wants the full list next time regardless of hash.
            lastKnownAppsHash = null
        }
        resp.optJSONObject("config")?.let { executor.applyConfig(it) }
    }

    // ---- WsClient.Listener ----

    override fun onOpen() {
        reconnectAttempt = 0
        sendTelemetry(includeApps = false)
    }

    override fun onMessage(msg: JSONObject) {
        when (msg.optString("type")) {
            "command" -> executor.handleCommand(msg)
            "config" -> executor.applyConfig(msg)
            "telemetry_request" -> sendTelemetry(includeApps = false)
            "ping_request" -> sendWs(JSONObject().apply {
                put("type", "pong_response")
                put("nonce", msg.opt("nonce"))
            })
            "checkin_now" -> lifecycleScope.launch(Dispatchers.IO) { doCheckin() }
            "cancel_command" -> executor.cancel(msg.optString("id"))
            // The server sends this on an operator's wake (remote control), and on its own
            // when kiosk is switched on — a device that wakes to a black locked screen is
            // the same as one that never woke.
            "wake_screen" -> ScreenControl.wake(this)
            "start_capture" -> startCapture(msg)
            "stop_capture" -> ScreenCaptureService.stop(this)
            "input_event" -> AgentBus.input?.handle(msg)
                ?: Log.w(TAG, "input_event ignored — accessibility service not enabled")
            "logcat_request" -> lifecycleScope.launch(Dispatchers.IO) { logcat.oneShot(msg) }
            "start_logcat_stream" -> logcat.startStream(msg)
            "stop_logcat_stream" -> logcat.stopStream(msg.optString("request_id"))
            else -> Log.d(TAG, "unhandled WS type=${msg.optString("type")}")
        }
    }

    override fun onClosed(reason: String) {
        if (!wantConnected) return
        val delayMs = BACKOFF_MS[reconnectAttempt.coerceIn(0, BACKOFF_MS.lastIndex)]
        reconnectAttempt++
        Log.i(TAG, "WS closed ($reason); reconnecting in ${delayMs}ms")
        reconnectJob?.cancel()
        reconnectJob = lifecycleScope.launch {
            delay(delayMs)
            connectWs()
        }
    }

    // ---- Acker ----

    override fun sendWs(msg: JSONObject): Boolean = ws.send(msg)

    override fun sendBinary(bytes: ByteString): Boolean = ws.sendBinary(bytes)

    private fun startCapture(msg: JSONObject) {
        // A capture of a sleeping device is a black rectangle, and the operator cannot tell
        // that from a broken stream. Wake first — the system-app client does the same at
        // session start.
        ScreenControl.wake(this)
        ScreenCaptureConsentActivity.launch(
            ctx = this,
            codec = msg.optString("codec", "h264"),
            quality = msg.optInt("quality", 70),
            scale = msg.optDouble("scale", 0.75).toFloat(),
            fps = msg.optInt("max_fps", 15),
            bitrate = msg.optInt("bitrate", 4_000_000),
        )
    }

    override fun ackCommand(commandId: String, status: String, output: String?, progress: Int?, pkg: String?) {
        if (ws.isOpen) {
            val frame = JSONObject().apply {
                put("type", "command_ack")
                put("command_id", commandId)
                put("serial_number", serial)
                put("status", status)
                output?.let { put("output", it) }
                progress?.let { put("progress", it) }
                pkg?.let { put("package", it) }
            }
            if (ws.send(frame)) return
        }
        // HTTP fallback (endpoint doesn't want the type/command_id-in-body; id is in the path).
        val body = JSONObject().apply {
            put("serial_number", serial)
            put("status", status)
            output?.let { put("output", it) }
            progress?.let { put("progress", it) }
            pkg?.let { put("package", it) }
        }
        lifecycleScope.launch(Dispatchers.IO) { api.ackCommand(commandId, body) }
    }

    private fun sendTelemetry(includeApps: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            val payload = Telemetry.buildCheckin(this@MdmService, includeApps)
            payload.put("type", "telemetry")
            ws.send(payload)
        }
    }

    override fun onDestroy() {
        wantConnected = false
        reconnectJob?.cancel()
        logcat.stopAll()
        ws.close()
        if (AgentBus.acker === this) AgentBus.acker = null
        super.onDestroy()
    }

    // ---- Foreground notification ----

    private fun goForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val text = if (deviceOwner.isDeviceOwner) "Managed device — agent active" else "Agent running (not provisioned)"
        return NotificationCompat.Builder(this, AgentApp.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    companion object {
        private const val TAG = "MdmService"
        private const val NOTIF_ID = 1001
        private val BACKOFF_MS = longArrayOf(1000, 2000, 4000, 8000, 30000)

        fun start(context: Context) {
            val intent = Intent(context, MdmService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
