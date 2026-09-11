package com.skorra.agent.net

import android.util.Log
import com.skorra.agent.AgentConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WebSocket to /api/v1/ws?serial=... with the shared X-API-Key header. OkHttp handles RFC-6455
 * framing, masking, and ping keepalive, so this is far smaller than the AOSP client's hand-rolled
 * MdmWebSocketClient. Reconnect/backoff is driven by [MdmService] via the [Listener] callbacks.
 */
class WsClient(
    private val config: AgentConfig,
    private val listener: Listener,
) {
    interface Listener {
        fun onOpen()
        fun onMessage(msg: JSONObject)
        fun onClosed(reason: String)
    }

    private val http = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // long-lived
        .build()

    @Volatile private var webSocket: WebSocket? = null
    @Volatile var isOpen: Boolean = false
        private set

    fun connect(serial: String) {
        val req = Request.Builder()
            .url(config.wsUrl(serial))
            .header("X-API-Key", config.apiKey)
            .build()
        webSocket = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                isOpen = true
                Log.i(TAG, "WS open")
                listener.onOpen()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    listener.onMessage(JSONObject(text))
                } catch (e: Exception) {
                    Log.w(TAG, "Bad WS message: ${e.message}")
                }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                // A peer that closes without a status code surfaces here as 1005, which is
                // reserved and throws if handed back to close() — OkHttp then reports a
                // failure, and the service reconnects a second later, forever. Echo a
                // normal close instead.
                ws.close(if (code == 1005 || code == 1006) 1000 else code, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                isOpen = false
                listener.onClosed("closed $code $reason")
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                isOpen = false
                Log.w(TAG, "WS failure: ${t.message}")
                listener.onClosed("failure ${t.message}")
            }
        })
    }

    fun send(obj: JSONObject): Boolean = webSocket?.send(obj.toString()) ?: false

    fun sendBinary(bytes: ByteString): Boolean = webSocket?.send(bytes) ?: false

    fun close() {
        isOpen = false
        webSocket?.close(1000, "client closing")
        webSocket = null
    }

    companion object {
        private const val TAG = "WsClient"
    }
}
