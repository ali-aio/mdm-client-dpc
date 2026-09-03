package com.aioapp.mdm.agent.net

import org.json.JSONObject

/**
 * Sink for device->server messages. Prefers the live WebSocket; falls back to HTTP for command
 * acks so results aren't lost when the socket is down (mirrors the AOSP client's pending-ack queue).
 */
interface Acker {
    /** Send an arbitrary device->server WS frame (telemetry, command_output, pong, ...). */
    fun sendWs(msg: JSONObject): Boolean

    /** Report command status. Uses WS command_ack when connected, else HTTP /commands/{id}/ack. */
    fun ackCommand(
        commandId: String,
        status: String,
        output: String? = null,
        progress: Int? = null,
        pkg: String? = null,
    )
}
