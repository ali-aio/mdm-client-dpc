package com.aioapp.mdm.agent.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.aioapp.mdm.agent.AgentBus
import org.json.JSONObject
import kotlin.math.hypot

/**
 * Remote input via the accessibility gesture API — the DPC-agent replacement for the system app's
 * INJECT_EVENTS. The user must enable this service once (it can't be silently enabled). Touch
 * streams are collapsed into taps/swipes (down..up); keys map to global navigation actions.
 */
class MdmAccessibilityService : AccessibilityService(), AgentBus.InputSink {

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        AgentBus.input = this
        Log.i(TAG, "accessibility input connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (AgentBus.input === this) AgentBus.input = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* not used */ }
    override fun onInterrupt() { /* no-op */ }

    override fun handle(event: JSONObject) {
        when (event.optString("action")) {
            "touch" -> handleTouch(event)
            "key" -> handleKey(event.optInt("keycode", -1))
        }
    }

    private fun handleTouch(e: JSONObject) {
        val m = metrics()
        val x = (e.optDouble("x", 0.0).toFloat()).coerceIn(0f, 1f) * m.widthPixels
        val y = (e.optDouble("y", 0.0).toFloat()).coerceIn(0f, 1f) * m.heightPixels
        when (e.optString("event")) {
            "down" -> { downX = x; downY = y; downTime = System.currentTimeMillis() }
            "move" -> { /* intermediate points ignored; reconstructed as a swipe on up */ }
            "up" -> {
                val moved = hypot((x - downX).toDouble(), (y - downY).toDouble())
                if (moved > TAP_SLOP) swipe(downX, downY, x, y) else tap(x, y)
            }
        }
    }

    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        dispatch(path, 1, 60)
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float) {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val dur = (System.currentTimeMillis() - downTime).coerceIn(50, 1000)
        dispatch(path, 1, dur)
    }

    private fun dispatch(path: Path, start: Long, duration: Long) {
        val stroke = GestureDescription.StrokeDescription(path, start, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    private fun handleKey(keycode: Int) {
        val action = when (keycode) {
            KeyEvent.KEYCODE_BACK -> GLOBAL_ACTION_BACK
            KeyEvent.KEYCODE_HOME -> GLOBAL_ACTION_HOME
            KeyEvent.KEYCODE_APP_SWITCH -> GLOBAL_ACTION_RECENTS
            else -> { Log.d(TAG, "unmapped keycode $keycode"); return }
        }
        performGlobalAction(action)
    }

    @Suppress("DEPRECATION")
    private fun metrics(): DisplayMetrics {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val m = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            m.widthPixels = b.width(); m.heightPixels = b.height()
        } else {
            wm.defaultDisplay.getRealMetrics(m)
        }
        return m
    }

    companion object {
        private const val TAG = "MdmA11y"
        private const val TAP_SLOP = 16.0
    }
}
