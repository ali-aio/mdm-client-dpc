package com.aioapp.mdm.agent.device

import android.util.Log
import com.aioapp.mdm.agent.net.Acker
import org.json.JSONObject
import java.io.BufferedReader
import java.util.concurrent.ConcurrentHashMap

/**
 * Logcat capture for the DPC agent. A non-system app can only read **its own** log entries (since
 * Android 4.1 dropped cross-app READ_LOGS), so this is app-scoped — honest about the system-app's
 * whole-device logcat no longer being available. Supports the server's one-shot logcat_request and
 * live start/stop_logcat_stream shapes.
 */
class LogcatManager(private val acker: Acker, private val serial: String) {

    private data class Stream(val process: Process, val thread: Thread)
    private val streams = ConcurrentHashMap<String, Stream>()

    /** logcat_request {request_id, level, lines, tag} -> logcat_result {request_id, content}. */
    fun oneShot(req: JSONObject) {
        val requestId = req.optString("request_id").ifBlank { req.optString("id") }
        val level = normalizeLevel(req.optString("level", "V"))
        val lines = req.optInt("lines", 500).coerceIn(1, 20000)
        val tag = req.optString("tag").trim()
        val argv = mutableListOf("logcat", "-d", "-v", "threadtime", "-t", lines.toString())
        argv.addFilter(tag, level)
        val content = runCatching {
            ProcessBuilder(argv).redirectErrorStream(true).start()
                .inputStream.bufferedReader().use(BufferedReader::readText)
        }.getOrElse { "logcat error: ${it.message}" }.take(5_000_000)

        acker.sendWs(JSONObject().apply {
            put("type", "logcat_result")
            put("request_id", requestId)
            put("serial_number", serial)
            put("content", content)
        })
    }

    /** start_logcat_stream {request_id, buffer, level, tag, grep, tail}. */
    fun startStream(req: JSONObject) {
        val requestId = req.optString("request_id")
        if (requestId.isBlank()) return
        if (streams.size >= MAX_STREAMS) {
            endStream(requestId, "rejected: too many concurrent streams")
            return
        }
        if (streams.containsKey(requestId)) return

        val level = normalizeLevel(req.optString("level", "V"))
        val tag = req.optString("tag").trim()
        val grep = req.optString("grep").trim().takeIf { it.isNotEmpty() }?.let { runCatching { Regex(it) }.getOrNull() }
        val buffer = req.optString("buffer", "main").takeIf { it != "all" }

        val argv = mutableListOf("logcat", "-v", "threadtime")
        buffer?.let { argv.add("-b"); argv.add(it) }
        argv.addFilter(tag, level)

        try {
            val proc = ProcessBuilder(argv).redirectErrorStream(true).start()
            val thread = Thread { pump(requestId, proc, grep) }.apply { isDaemon = true; start() }
            streams[requestId] = Stream(proc, thread)
        } catch (e: Exception) {
            endStream(requestId, "start error: ${e.message}")
        }
    }

    fun stopStream(requestId: String) {
        streams.remove(requestId)?.let {
            runCatching { it.process.destroyForcibly() }
            endStream(requestId, "stopped")
        }
    }

    fun stopAll() {
        streams.keys.toList().forEach { stopStream(it) }
    }

    private fun pump(requestId: String, proc: Process, grep: Regex?) {
        val reader = proc.inputStream.bufferedReader()
        val batch = StringBuilder()
        var lastFlush = 0L
        try {
            reader.useLines { seq ->
                for (line in seq) {
                    if (!streams.containsKey(requestId)) break
                    if (grep != null && !grep.containsMatchIn(line)) continue
                    batch.append(line).append('\n')
                    val now = System.nanoTime() / 1_000_000
                    if (batch.length >= BATCH_BYTES || now - lastFlush >= BATCH_MS) {
                        flush(requestId, batch); lastFlush = now
                    }
                }
            }
            if (batch.isNotEmpty()) flush(requestId, batch)
        } catch (e: Exception) {
            Log.w(TAG, "stream $requestId error: ${e.message}")
        } finally {
            if (streams.remove(requestId) != null) endStream(requestId, "eof")
        }
    }

    private fun flush(requestId: String, batch: StringBuilder) {
        if (batch.isEmpty()) return
        acker.sendWs(JSONObject().apply {
            put("type", "logcat_stream")
            put("request_id", requestId)
            put("chunk", batch.toString())
        })
        batch.setLength(0)
    }

    private fun endStream(requestId: String, reason: String) {
        acker.sendWs(JSONObject().apply {
            put("type", "logcat_stream_end")
            put("request_id", requestId)
            put("reason", reason)
        })
    }

    private fun MutableList<String>.addFilter(tag: String, level: String) {
        if (tag.isNotEmpty()) { add("-s"); add("$tag:$level") } else { add("*:$level") }
    }

    private fun normalizeLevel(l: String): String =
        l.trim().uppercase().firstOrNull()?.toString()?.takeIf { it in setOf("V", "D", "I", "W", "E", "F") } ?: "V"

    companion object {
        private const val TAG = "LogcatManager"
        private const val MAX_STREAMS = 3
        private const val BATCH_BYTES = 12 * 1024
        private const val BATCH_MS = 250L
    }
}
