package aio.app.mdmclient.dpc.device

import android.util.Log
import aio.app.mdmclient.dpc.net.Acker
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A real, **persistent** interactive shell for the device.
 *
 * Unlike the old fixed-allowlist runner, this keeps ONE long-lived `sh` process alive and feeds
 * each server `shell` command to its stdin, so working directory, environment and shell state
 * persist across commands (`cd`, `export`, `VAR=…` all stick). Output streams back live via
 * `command_output`; completion + exit code are detected with a per-command sentinel and reported
 * via `command_done`.
 *
 * The shell runs as the app's (unprivileged) UID — full `sh` semantics (pipes, redirection,
 * globbing, cd), but only what that UID may access. `ls /data` etc. return a real
 * "Permission denied", not a synthetic block. Rooted/system access is not available on stock
 * devices.
 */
class ShellSession(private val acker: Acker) {

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var readerThread: Thread? = null
    private val alive = AtomicBoolean(false)

    // The command currently in flight (its output lines route to this id until the sentinel).
    @Volatile private var currentId: String? = null

    // One command that never returns (a sysfs read that blocks, a dumpsys that waits) used to
    // hold the shell for ever, and every later command queued behind it (Sunmi D2s KDS,
    // 27 Oct 2026). Past its deadline the command is failed and the shell starts afresh.
    private val deadlines = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "shell-deadline").apply { isDaemon = true }
    }

    @Synchronized
    fun run(commandId: String, cmd: String) {
        if (cmd.isBlank()) {
            done(commandId, 0)
            return
        }
        try {
            ensureStarted()
        } catch (e: Exception) {
            output(commandId, "shell unavailable: ${e.message}\n")
            done(commandId, 127)
            return
        }
        currentId = commandId
        val sentinel = "__CDONE_${commandId}__"
        try {
            writer?.apply {
                // Run the user command, then print a unique sentinel + the exit code on its own line.
                write(cmd); newLine()
                write("printf '\\n$sentinel%d\\n' \$?"); newLine()
                flush()
            }
            deadlines.schedule({ expire(commandId) }, TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: Exception) {
            output(commandId, "write failed: ${e.message}\n")
            done(commandId, 1)
            reset()
        }
    }

    /** The command is still running at its deadline: fail it and start the shell afresh. */
    @Synchronized
    private fun expire(commandId: String) {
        if (currentId != commandId) return
        Log.w(TAG, "shell command $commandId still running after ${TIMEOUT_S}s: restarting the shell")
        output(commandId, "\n[no answer after ${TIMEOUT_S} s: stopped, and the shell was restarted]\n")
        done(commandId, 124)
        reset()
    }

    /** Reset the shell (drops cd/env state) — used on stop or if the session wedges. */
    @Synchronized
    fun reset() {
        alive.set(false)
        runCatching { writer?.close() }
        runCatching { process?.destroyForcibly() }
        runCatching { readerThread?.interrupt() }
        writer = null
        process = null
        readerThread = null
        currentId = null
    }

    private fun ensureStarted() {
        if (alive.get() && process?.isAlive == true) return
        reset()
        val p = ProcessBuilder("sh")
            .redirectErrorStream(true) // merge stderr into stdout so it streams in order
            .start()
        process = p
        writer = BufferedWriter(OutputStreamWriter(p.outputStream))
        alive.set(true)
        readerThread = Thread { pump(p, p.inputStream.bufferedReader()) }
            .apply { isDaemon = true; name = "shell-pump"; start() }
        Log.i(TAG, "shell session started")
    }

    private fun pump(p: Process, reader: BufferedReader) {
        try {
            while (alive.get()) {
                val line = reader.readLine() ?: break
                val id = currentId
                val sentinelIdx = if (id != null) line.indexOf("__CDONE_${id}__") else -1
                if (id != null && sentinelIdx >= 0) {
                    // Any text before the sentinel on this line is real output.
                    if (sentinelIdx > 0) output(id, line.substring(0, sentinelIdx))
                    val code = line.substring(sentinelIdx + "__CDONE_${id}__".length).trim().toIntOrNull() ?: 0
                    currentId = null
                    done(id, code)
                } else if (id != null) {
                    output(id, line + "\n")
                }
                // Lines that arrive with no current command (shouldn't happen) are dropped.
            }
        } catch (e: Exception) {
            Log.w(TAG, "shell pump ended: ${e.message}")
        } finally {
            // If the shell died mid-command, close out the in-flight command. A shell that was
            // replaced (reset, then a new one started) must not mark its successor dead.
            synchronized(this) {
                if (process === p) {
                    currentId?.let { done(it, 137) }
                    alive.set(false)
                }
            }
        }
    }

    private fun output(id: String, text: String) {
        // The server relays `chunk` verbatim to the terminal's SSE (no base64 decode on the
        // server/browser side), so send RAW text — JSON handles escaping.
        acker.sendWs(JSONObject().apply {
            put("type", "command_output")
            put("command_id", id)
            put("chunk", text)
        })
    }

    private fun done(id: String, exitCode: Int) {
        acker.sendWs(JSONObject().apply {
            put("type", "command_done")
            put("command_id", id)
            put("exit_code", exitCode)
        })
    }

    companion object {
        private const val TAG = "ShellSession"
        private const val TIMEOUT_S = 280L // just under the server's 5 min shell sweep
    }
}
