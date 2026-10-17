package aio.app.mdmclient.dpc.lost

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import aio.app.mdmclient.dpc.AgentConfig
import aio.app.mdmclient.dpc.DeviceIdentity
import aio.app.mdmclient.dpc.R
import java.lang.ref.WeakReference

/**
 * The lost screen: the server's message, a phone number to call and the serial, on a dark
 * full-screen page with nothing to tap. Pinned in lock task by [aio.app.mdmclient.dpc.device.LostMode],
 * which also makes it the device's Home, so boot and the Home button both land here.
 *
 * Every key is swallowed except volume and power: a TV box or menu board is driven by a
 * remote, and Back, Home, Menu, the D-pad or a "Settings" key on that remote must not find
 * a way out. Touches are swallowed too. Lock task (no features) already blocks Home and
 * Recents; this covers the keys lock task lets through to the activity.
 */
class LostActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        val cfg = AgentConfig.get(this)
        if (!cfg.lostOn) {
            leave()
            return
        }
        current = WeakReference(this)
        if (!isInLockTask()) {
            runCatching { startLockTask() }.onFailure { Log.w(TAG, "startLockTask failed: ${it.message}") }
        }
        render(cfg)
        hideSystemBars()
        shown = true
    }

    override fun onPause() {
        super.onPause()
        shown = false
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        shown = false
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun render(cfg: AgentConfig) {
        val density = resources.displayMetrics.density
        val wide = resources.configuration.smallestScreenWidthDp >= 600
        val pad = (if (wide) 64 else 28) * density
        val semibold = runCatching { resources.getFont(R.font.poppins_semibold) }.getOrDefault(Typeface.DEFAULT_BOLD)
        val regular = runCatching { resources.getFont(R.font.poppins_regular) }.getOrDefault(Typeface.DEFAULT)

        fun text(s: String, sp: Float, face: Typeface, color: Int, topDp: Int) = TextView(this).apply {
            text = s
            typeface = face
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.25f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (topDp * density).toInt() }
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad.toInt(), pad.toInt(), pad.toInt(), pad.toInt())
        }
        col.addView(text(getString(R.string.lost_title), if (wide) 40f else 28f, semibold, WHITE, 0))
        val message = cfg.lostMessage.ifBlank { getString(R.string.lost_default_message) }
        col.addView(text(message, if (wide) 24f else 18f, regular, SOFT, 20))
        if (cfg.lostPhone.isNotBlank()) {
            col.addView(text(getString(R.string.lost_call, cfg.lostPhone), if (wide) 28f else 20f, semibold, WHITE, 32))
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(BG)
            addView(col, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
            ))
            addView(text(DeviceIdentity.serial(this@LostActivity), 12f, regular, FAINT, 0).apply {
                setPadding(0, 0, 0, (20 * density).toInt())
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM,
            ))
            // Keep the remote's focus here, so no D-pad move lands on anything.
            isFocusable = true
            isFocusableInTouchMode = true
        }
        setContentView(root)
        root.requestFocus()
    }

    // ---- nothing gets out ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = when (event.keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_POWER -> super.dispatchKeyEvent(event)
        else -> true
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean = true

    @Deprecated("Back is swallowed; kept for API levels that still route it here")
    override fun onBackPressed() { /* no way out */ }

    private fun hideSystemBars() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    private fun leave() {
        if (isInLockTask()) runCatching { stopLockTask() }
        finish()
    }

    private fun isInLockTask(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
    }

    companion object {
        private const val TAG = "LostActivity"
        private const val BG = 0xFF16181C.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val SOFT = 0xFFC9CCD3.toInt()
        private const val FAINT = 0xFF7D828C.toInt()

        @Volatile private var current: WeakReference<LostActivity>? = null
        @Volatile private var shown = false

        fun isShown(): Boolean = shown

        /** Lost mode was lifted: leave lock task and close. Main thread. */
        fun release() {
            current?.get()?.leave()
        }
    }
}
