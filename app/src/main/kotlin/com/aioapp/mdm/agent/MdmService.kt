package com.aioapp.mdm.agent

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.aioapp.mdm.agent.net.Acker
import com.aioapp.mdm.agent.net.ApiClient
import com.aioapp.mdm.agent.net.CommandExecutor
import com.aioapp.mdm.agent.net.Telemetry
import com.aioapp.mdm.agent.net.WsClient
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
    private lateinit var serial: String

    @Volatile private var wantConnected = false
    @Volatile private var lastKnownAppsHash: String? = null
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        config = AgentConfig.get(this)
        deviceOwner = DeviceOwner(this)
        api = ApiClient(config)
        ws = WsClient(config, this)
        executor = CommandExecutor(this, deviceOwner, this, lifecycleScope)
        serial = DeviceIdentity.serial(this)

        goForeground()
        Log.i(TAG, "MdmService started (deviceOwner=${deviceOwner.isDeviceOwner}, configured=${config.isConfigured})")

        // Re-enforce any persisted kiosk state after a (re)boot.
        if (deviceOwner.isDeviceOwner) executor.applyConfig(JSONObject())

        if (config.isConfigured) startTransport()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // A restart (e.g. after Save & connect) may have added config.
        if (config.isConfigured && !wantConnected) startTransport()
        return START_STICKY
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
            // Phase 3/4: logcat_request, start/stop_logcat_stream, start/stop_capture, input_event,
            // cancel_command. Logged for now.
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
        ws.close()
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
            .setSmallIcon(R.drawable.ic_launcher)
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
