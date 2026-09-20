package aio.app.mdmclient.dpc.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.media.AudioManager
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import aio.app.mdmclient.dpc.AgentBus
import org.json.JSONObject
import kotlin.math.hypot

/**
 * Remote input via the accessibility gesture API — the DPC-agent replacement for the system app's
 * INJECT_EVENTS. Enabled once, by the user or — when adb granted WRITE_SECURE_SETTINGS at
 * enrollment — by the agent itself (see Grants.ensureAccessibility). Touch
 * streams are collapsed into taps/swipes (down..up); keys map to global navigation actions
 * (incl. D-pad on Android 13+) and volume.
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
            // The dashboard sends a down and an up per key press; a global action is one
            // shot, so act on the up only (or on a bare key event with no phase).
            "key" -> if (event.optString("event", "up") == "up") handleKey(event.optInt("keycode", -1))
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
        // Volume is not a global action; adjust the music stream directly (TV boxes have
        // no hardware volume keys the operator could otherwise reach).
        val dir = when (keycode) {
            KeyEvent.KEYCODE_VOLUME_UP -> AudioManager.ADJUST_RAISE
            KeyEvent.KEYCODE_VOLUME_DOWN -> AudioManager.ADJUST_LOWER
            KeyEvent.KEYCODE_VOLUME_MUTE -> AudioManager.ADJUST_TOGGLE_MUTE
            else -> null
        }
        if (dir != null) {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
            return
        }
        val action = when (keycode) {
            KeyEvent.KEYCODE_BACK -> GLOBAL_ACTION_BACK
            KeyEvent.KEYCODE_HOME -> GLOBAL_ACTION_HOME
            KeyEvent.KEYCODE_APP_SWITCH -> GLOBAL_ACTION_RECENTS
            else -> dpadAction(keycode) ?: run { Log.d(TAG, "unmapped keycode $keycode"); return }
        }
        performGlobalAction(action)
    }

    /**
     * D-pad for remote-driven devices (TV boxes / dongles have no touchscreen, so taps do
     * nothing useful there). The dashboard sends arrows as DPAD_* and Enter as ENTER.
     * The DPAD global actions exist from Android 13; older devices get nothing.
     */
    private fun dpadAction(keycode: Int): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return when (keycode) {
            KeyEvent.KEYCODE_DPAD_UP -> GLOBAL_ACTION_DPAD_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> GLOBAL_ACTION_DPAD_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> GLOBAL_ACTION_DPAD_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> GLOBAL_ACTION_DPAD_RIGHT
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> GLOBAL_ACTION_DPAD_CENTER
            else -> null
        }
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
