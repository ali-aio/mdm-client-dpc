package aio.app.mdmclient.dpc

/**
 * What the agent is doing right now, for the status screen: whether the live socket is up and
 * how the last check-in went. In-process only — [MdmService] writes it, [ui.MainActivity] polls
 * it — so it starts empty after a process restart until the first check-in lands.
 */
object AgentStatus {
    @Volatile var wsConnected = false
    @Volatile var enrolling = false

    /** Time of the last check-in attempt and of the last one the server accepted (0 = none yet). */
    @Volatile var lastAttemptAtMs = 0L
    @Volatile var lastOkAtMs = 0L

    /** Why the last attempt failed ("HTTP 502", "Device key rejected", a network error), or "". */
    @Volatile var lastError = ""

    val lastAttemptOk: Boolean get() = lastAttemptAtMs > 0 && lastAttemptAtMs == lastOkAtMs

    fun checkinResult(ok: Boolean, error: String?) {
        val now = System.currentTimeMillis()
        lastAttemptAtMs = now
        if (ok) {
            lastOkAtMs = now
            lastError = ""
        } else {
            lastError = error.orEmpty().ifBlank { "No response" }
        }
    }
}
