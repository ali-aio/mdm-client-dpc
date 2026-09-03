package com.aioapp.mdm.agent.device

import android.content.Context
import java.io.BufferedReader
import java.util.concurrent.TimeUnit

/**
 * Device-Owner-safe replacement for the system app's arbitrary `Runtime.exec("sh -c ...")`.
 *
 * A non-system app can't run privileged shell, so instead of a shell interpreter we execute a
 * fixed allowlist of read-only diagnostic binaries directly (no `sh -c`, so no pipes / chaining /
 * injection). Anything outside the allowlist is refused with exit 127 and a clear message.
 */
object SafeShell {

    data class Output(val text: String, val exitCode: Int)

    private val ALLOWED = setOf(
        "getprop", "id", "whoami", "uname", "ps", "ip", "ping",
        "df", "uptime", "date", "pm", "dumpsys", "cat",
    )

    // dumpsys/cat are powerful; restrict them to harmless read-only targets.
    private val DUMPSYS_OK = setOf("battery", "wifi", "connectivity", "meminfo", "cpuinfo", "power")
    private val CAT_PREFIX_OK = listOf("/proc/", "/sys/")

    fun run(ctx: Context, cmdLine: String): Output {
        val tokens = cmdLine.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return Output("empty command", 2)
        val bin = tokens[0]
        if (bin !in ALLOWED) {
            return Output("blocked: '$bin' not in safe-command allowlist (${ALLOWED.sorted().joinToString(", ")})", 127)
        }
        if (bin == "dumpsys" && (tokens.size < 2 || tokens[1] !in DUMPSYS_OK)) {
            return Output("blocked: dumpsys limited to ${DUMPSYS_OK.joinToString(", ")}", 127)
        }
        if (bin == "cat" && !(tokens.size == 2 && CAT_PREFIX_OK.any { tokens[1].startsWith(it) })) {
            return Output("blocked: cat limited to /proc/ and /sys/ paths", 127)
        }
        return exec(tokens)
    }

    private fun exec(argv: List<String>): Output {
        return try {
            val proc = ProcessBuilder(argv).redirectErrorStream(true).start()
            val text = proc.inputStream.bufferedReader().use(BufferedReader::readText)
            val finished = proc.waitFor(15, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                Output(text + "\n[timed out after 15s]", 124)
            } else {
                Output(text.take(1_000_000), proc.exitValue())
            }
        } catch (e: Exception) {
            Output("exec error: ${e.message}", 1)
        }
    }
}
