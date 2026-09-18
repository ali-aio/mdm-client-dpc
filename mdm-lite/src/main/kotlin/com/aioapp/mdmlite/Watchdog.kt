package com.aioapp.mdmlite

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Detects a frozen main thread. Android only records an ANR (ApplicationExitInfo) when
 * the process is killed for it; a screen that freezes and recovers, or freezes with no
 * input arriving to trigger an ANR at all (a signage screen nobody touches), leaves no
 * trace. This posts a no-op to the main thread every second and reports `app_freeze`,
 * with the main thread's stack at the moment it was stuck, once per freeze.
 */
internal class Watchdog(private val store: Store) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var lastBeat = SystemClock.uptimeMillis()
    private var reported = false

    fun start() {
        Thread({
            while (true) {
                main.post { lastBeat = SystemClock.uptimeMillis() }
                try { Thread.sleep(TICK_MS) } catch (_: InterruptedException) { return@Thread }
                val stuck = SystemClock.uptimeMillis() - lastBeat
                if (stuck >= FREEZE_MS && !reported) {
                    reported = true
                    val stack = Looper.getMainLooper().thread.stackTrace.joinToString("\n") { "  at $it" }
                    val top = Looper.getMainLooper().thread.stackTrace.firstOrNull()?.toString().orEmpty()
                    store.addEvent("app_freeze", System.currentTimeMillis(),
                        "Main thread blocked ${stuck / 1000}s+ at $top".take(300), "main thread:\n$stack")
                } else if (stuck < TICK_MS * 2) {
                    reported = false // recovered; the next freeze is a new event
                }
            }
        }, "aio-mdm-watchdog").apply { isDaemon = true; start() }
    }

    companion object {
        private const val TICK_MS = 1_000L
        private const val FREEZE_MS = 5_000L
    }
}
