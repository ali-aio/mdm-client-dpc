package aio.app.mdmclient.dpc.net

import aio.app.mdmclient.dpc.AgentConfig
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The device's leg of an adb tunnel (server: internal/adbtunnel). On `adb_tunnel_open` we
 * dial adbd on 127.0.0.1:port and carry it to the server over a second WebSocket as binary
 * frames, one stream per adb host connection; `adb_tunnel_close` drops a session's streams.
 *
 * The standard client has no root, so this only works where adbd already listens on the
 * network (an image with service.adb.tcp.port set, or `adb tcpip` from a USB session) —
 * the dial failing is reported back as a text frame so the page can say so.
 */
class AdbTunnel(private val config: AgentConfig) {
    private val http = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /** Open streams keyed by session id → stream id. */
    private val streams = ConcurrentHashMap<String, ConcurrentHashMap<String, Stream>>()

    fun open(serial: String, msg: JSONObject) {
        val session = msg.optString("session")
        val stream = msg.optString("stream")
        val port = msg.optInt("port", 5555)
        if (session.isBlank() || stream.isBlank()) return
        val url = config.wsUrl(serial)
            .substringBefore("/api/v1/ws") + "/api/v1/adb-tunnel/$session/$stream?serial=${android.net.Uri.encode(serial)}"
        val st = Stream(session, stream, port)
        streams.getOrPut(session) { ConcurrentHashMap() }[stream] = st
        st.start(url)
    }

    fun closeSession(session: String) {
        streams.remove(session)?.values?.forEach { it.close() }
        Log.i(TAG, "session ${session.take(8)} closed")
    }

    private inner class Stream(val session: String, val id: String, val port: Int) {
        @Volatile private var ws: WebSocket? = null
        @Volatile private var adb: Socket? = null

        fun start(url: String) {
            val req = Request.Builder().url(url).header("X-API-Key", config.apiKey).build()
            ws = http.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val s = Socket()
                    try {
                        s.connect(InetSocketAddress("127.0.0.1", port), 3_000)
                        s.tcpNoDelay = true
                    } catch (e: IOException) {
                        webSocket.send("""{"error":"adbd not reachable on 127.0.0.1:$port (${e.message})"}""")
                        webSocket.close(1000, "no adbd")
                        return
                    }
                    adb = s
                    Log.i(TAG, "stream $id open on port $port")
                    Thread({
                        val buf = ByteArray(32 * 1024)
                        try {
                            val input = s.getInputStream()
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                if (!webSocket.send(buf.toByteString(0, n))) break
                            }
                        } catch (_: IOException) {
                        } finally {
                            webSocket.close(1000, "adb closed")
                        }
                    }, "adb-tunnel-up-$id").apply { isDaemon = true }.start()
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    try {
                        adb?.getOutputStream()?.let { it.write(bytes.toByteArray()); it.flush() }
                    } catch (_: IOException) {
                        webSocket.close(1000, "adb write failed")
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(if (code == 1005 || code == 1006) 1000 else code, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = finish()
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "stream $id: ${t.message}")
                    finish()
                }
            })
        }

        fun close() {
            ws?.close(1000, "session closed")
            finish()
        }

        private fun finish() {
            runCatching { adb?.close() }
            adb = null
            streams[session]?.remove(id)
            Log.i(TAG, "stream $id ended")
        }
    }

    companion object {
        private const val TAG = "AdbTunnel"

        /** Whether adbd is listening on the network port right now (for telemetry's adb_tcp). */
        fun adbdListening(port: Int = 5555): Boolean = runCatching {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300); true }
        }.getOrDefault(false)
    }
}
