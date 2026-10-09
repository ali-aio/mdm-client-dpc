package aio.app.mdmclient.dpc

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Process
import android.os.SystemClock
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
import aio.app.mdmclient.dpc.net.AdbTunnel
import aio.app.mdmclient.dpc.net.ApiClient
import aio.app.mdmclient.dpc.net.CommandExecutor
import aio.app.mdmclient.dpc.net.Telemetry
import aio.app.mdmclient.dpc.net.TelemetryDelta
import aio.app.mdmclient.dpc.net.WsClient
import okio.ByteString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random
import org.json.JSONObject

/**
 * Long-running foreground service: hosts the periodic HTTP checkin loop and the live WebSocket,
 * routes server messages to [CommandExecutor], and acks results.
 *
 * The WS carries realtime push (commands, config, pings) and, while it is up, telemetry as
 * deltas ([TelemetryDelta]) — the firmware client's model. The HTTP check-in is the full
 * keyframe and app inventory: every [HTTP_SAFETY_NET_MS] while the socket is live, every
 * tick while it is not.
 */
class MdmService : LifecycleService(), WsClient.Listener, Acker {

    private lateinit var config: AgentConfig
    private lateinit var deviceOwner: DeviceOwner
    private lateinit var api: ApiClient
    private lateinit var ws: WsClient
    private lateinit var executor: CommandExecutor
    private lateinit var adbTunnel: AdbTunnel
    private lateinit var logcat: LogcatManager
    private lateinit var serial: String

    @Volatile private var wantConnected = false
    @Volatile private var lastKnownAppsHash: String? = null
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null
    private val delta = TelemetryDelta()
    @Volatile private var lastHttpCheckinAt = 0L
    /** Set by a server "wake" frame so the next poll tick runs even with a healthy socket. */
    private val pollCommandsNow = java.util.concurrent.atomic.AtomicBoolean(false)
    // Touched on every sign the service's checkin loop and WS reader are actually making
    // progress (loop tick, inbound WS message). A shell command starting a persistent `sh`
    // process via ProcessBuilder forks this process — on at least two of the device families
    // tested (RBOX, Sunmi KDS), that intermittently wedges the whole process with no
    // exception or crash, so nothing else here can detect it from the inside. The watchdog
    // below is the backstop: if nothing has ticked in watchdogTimeoutMs, the process is
    // killed outright so Android restarts it (START_STICKY) rather than leaving a device
    // silently dark until someone finds it and reboots it by hand.
    @Volatile private var lastProgressAtMs = SystemClock.elapsedRealtime()
    private var watchdogJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        config = AgentConfig.get(this)
        deviceOwner = DeviceOwner(this)
        api = ApiClient(config).also { it.onUnauthorized = { onUnauthorized() } }
        ws = WsClient(config, this)
        executor = CommandExecutor(this, deviceOwner, this, lifecycleScope)
        adbTunnel = AdbTunnel(config)
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
        // Handed back by an unenroll command. Whatever restarted us — boot, the alarm, the
        // launcher — this device is not managed any more and must not start reporting again.
        if (config.unenrolled) {
            Log.i(TAG, "unenrolled: not connecting")
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CHECKIN_NOW && wantConnected) {
            lifecycleScope.launch(Dispatchers.IO) { doCheckin() }
        }
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
        AgentStatus.enrolling = true
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
                AgentStatus.checkinResult(false, api.lastError)
                attempt++
                delay((30_000L * attempt).coerceAtMost(300_000L))
            }
            enrolling = false
            AgentStatus.enrolling = false
        }
    }

    // ---- Transport lifecycle ----

    private fun startTransport() {
        wantConnected = true
        connectWs()
        startCheckinLoop()
        startWatchdog()
        startCommandPollLoop()
    }

    /** Self-heal a wedged process — see [lastProgressAtMs]. */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive && wantConnected) {
                delay(WATCHDOG_CHECK_MS)
                val stuckFor = SystemClock.elapsedRealtime() - lastProgressAtMs
                if (stuckFor >= WATCHDOG_TIMEOUT_MS) {
                    Log.e(TAG, "watchdog: no progress in ${stuckFor}ms, killing process to recover")
                    Process.killProcess(Process.myPid())
                }
            }
        }
    }

    private fun connectWs() {
        if (!wantConnected) return
        Log.i(TAG, "Connecting WS...")
        ws.connect(serial)
    }

    /**
     * Ask the server for queued commands on its own cadence, independent of telemetry.
     *
     * Deliberately not the check-in schedule: command latency and telemetry volume are
     * different problems, and making check-ins minutely to carry commands would multiply
     * this fleet's telemetry writes to fix a delivery bug. While the socket is healthy this
     * does nothing — the server sends a content-free "wake" frame when there is work, which
     * is instant. The loop covers the case where that frame can never arrive: a dead
     * socket, a refused upgrade, a proxy that drops WebSockets. The watchdog can restart a
     * wedged process, but it cannot unblock a path that is blocked, which is why polling is
     * the thing that makes this agent reachable rather than merely restartable.
     */
    private fun startCommandPollLoop() {
        lifecycleScope.launch(Dispatchers.IO) {
            delay(15_000L) // let enrolment and the first check-in settle
            while (isActive && wantConnected) {
                try {
                    val nudged = pollCommandsNow.getAndSet(false)
                    if (!ws.isOpen || nudged) {
                        api.fetchPendingCommands(serial)?.let { runHttpCommands(it) }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "command poll tick: ${e.message}")
                }
                delay(COMMAND_POLL_MS)
            }
        }
    }

    /**
     * Commands handed back by a poll or a check-in response. Everything downstream is the
     * code a pushed frame uses: the executor acks receipt, dedups by id and runs it, and
     * Acker already prefers HTTP when the socket is down — so a command delivered this way
     * behaves exactly like a pushed one, only later.
     */
    private fun runHttpCommands(resp: JSONObject) {
        val cmds = resp.optJSONArray("commands") ?: return
        if (cmds.length() == 0) return
        Log.i(TAG, "Took ${cmds.length()} command(s) over HTTP")
        for (i in 0 until cmds.length()) {
            val cmd = cmds.optJSONObject(i) ?: continue
            try {
                executor.handleCommand(cmd)
            } catch (e: Exception) {
                Log.e(TAG, "HTTP command ${cmd.optString("id")} failed: ${e.message}")
            }
        }
    }

    private fun startCheckinLoop() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive && wantConnected) {
                lastProgressAtMs = SystemClock.elapsedRealtime()
                val httpDue = SystemClock.elapsedRealtime() - lastHttpCheckinAt >= HTTP_SAFETY_NET_MS
                if (!ws.isOpen || httpDue || lastHttpCheckinAt == 0L) doCheckin() else pushTelemetry()
                delay(config.checkinIntervalSeconds * 1000L)
            }
        }
    }

    private fun doCheckin() {
        // Send the app inventory only when the hash changed (or the server last asked for it).
        val currentHash = Telemetry.appsHash(this)
        val includeApps = currentHash != lastKnownAppsHash
        val payload = Telemetry.buildCheckin(this, includeApps)
        lastHttpCheckinAt = SystemClock.elapsedRealtime()
        val resp = api.checkin(payload)
        AgentStatus.checkinResult(resp != null, api.lastError)
        if (resp == null) return
        // The server replaced latest_extra with this snapshot: it is the new delta baseline.
        delta.remember(payload)
        lastKnownAppsHash = currentHash
        if (resp.optBoolean("send_apps", false) && !includeApps) {
            // Server wants the full list next time regardless of hash.
            lastKnownAppsHash = null
        }
        resp.optJSONObject("config")?.let { executor.applyConfig(it) }
        // Free backup channel: if the response already carries queued work, run it rather
        // than waiting for the next poll.
        runHttpCommands(resp)
    }

    // ---- WsClient.Listener ----

    override fun onOpen() {
        AgentStatus.wsConnected = true
        reconnectAttempt = 0
        sendKeyframe()
    }

    override fun onMessage(msg: JSONObject) {
        lastProgressAtMs = SystemClock.elapsedRealtime()
        when (msg.optString("type")) {
            "command" -> executor.handleCommand(msg)
            "config" -> executor.applyConfig(msg)
            // The server asks on every check-in interval, so this is a delta tick like the
            // firmware client's, not a keyframe — a keyframe here was a full snapshot every 30 s.
            "telemetry_request" -> lifecycleScope.launch(Dispatchers.IO) { pushTelemetry() }
            "ping_request" -> sendWs(JSONObject().apply {
                put("type", "pong_response")
                put("nonce", msg.opt("nonce"))
            })
            // "There is work, come and get it" — no payload, so a frame lost on a
            // half-open socket costs one poll interval instead of a command.
            "wake" -> {
                Log.i(TAG, "Wake from server (${msg.optString("reason")}) — polling for commands")
                pollCommandsNow.set(true)
                lifecycleScope.launch(Dispatchers.IO) {
                    api.fetchPendingCommands(serial)?.let { runHttpCommands(it) }
                }
            }
            "checkin_now" -> lifecycleScope.launch(Dispatchers.IO) { doCheckin() }
            "cancel_command" -> executor.cancel(msg.optString("id"))
            // The server sends this on an operator's wake (remote control), and on its own
            // when kiosk is switched on — a device that wakes to a black locked screen is
            // the same as one that never woke.
            "wake_screen" -> ScreenControl.wake(this)
            "start_capture" -> startCapture(msg)
            // An admin's `adb connect` reached the server: carry adbd to it (AdbTunnel).
            "adb_tunnel_open" -> adbTunnel.open(serial, msg)
            "adb_tunnel_close" -> adbTunnel.closeSession(msg.optString("session"))
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
        AgentStatus.wsConnected = false
        if (!wantConnected) return
        // Equal jitter, as the firmware client does: a server restart drops the whole fleet
        // at once, and without it every device retries at 1s, 2s, 4s… in lockstep.
        val base = BACKOFF_MS[reconnectAttempt.coerceIn(0, BACKOFF_MS.lastIndex)]
        val delayMs = base / 2 + Random.nextLong(base / 2 + 1)
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

    /** The whole snapshot over the socket, on (re)connect: it re-establishes the baseline. */
    private fun sendKeyframe() {
        delta.forceKeyframe = true
        lifecycleScope.launch(Dispatchers.IO) { pushTelemetry() }
    }

    /** One telemetry tick over the WS: a delta, or nothing when nothing changed. */
    @Synchronized
    private fun pushTelemetry() {
        if (!ws.isOpen) return
        val full = Telemetry.buildCheckin(this, includeApps = false)
        val frame = delta.frameFor(full)
        if (frame != null) {
            frame.put("type", "telemetry")
            if (!ws.send(frame)) {
                delta.forceKeyframe = true // the next send re-establishes the baseline
                return
            }
            delta.remember(full)
        }
        // Sent, or skipped because the server already has it: either way the device is
        // reporting over a live link, which is what the status screen's check-in row means.
        AgentStatus.checkinResult(true, null)
    }

    override fun onDestroy() {
        wantConnected = false
        AgentStatus.wsConnected = false
        reconnectJob?.cancel()
        watchdogJob?.cancel()
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
        /** Full HTTP keyframe cadence while the WS is live (the firmware client's safety net). */
        private const val HTTP_SAFETY_NET_MS = 5 * 60_000L
        // Command poll cadence while there is no usable socket. Telemetry keeps its own
        // five-minute keyframe: this dial is command latency, nothing else.
        private const val COMMAND_POLL_MS = 60_000L
        /** Watchdog poll cadence. */
        private const val WATCHDOG_CHECK_MS = 30_000L
        /** No checkin-loop tick and no inbound WS message in this long = treat as wedged. */
        private const val WATCHDOG_TIMEOUT_MS = 3 * 60_000L

        /** Status screen's "Check in now": one check-in right away, outside the interval. */
        const val ACTION_CHECKIN_NOW = "aio.app.mdmclient.dpc.CHECKIN_NOW"

        fun start(context: Context, action: String? = null) {
            val intent = Intent(context, MdmService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
