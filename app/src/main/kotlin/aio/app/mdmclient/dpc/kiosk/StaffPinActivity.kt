package aio.app.mdmclient.dpc.kiosk

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import aio.app.mdmclient.dpc.AgentConfig
import aio.app.mdmclient.dpc.DeviceOwner
import aio.app.mdmclient.dpc.device.KioskManager

/**
 * The staff PIN pad (0.10.0), the same words and colours as the tablets'. A right PIN shows
 * "Staff mode on" for a moment, then the device leaves the lock and opens the locked app with
 * the "Back to guest mode" pill. Works by touch and by D-pad (TV boxes): every key is a
 * focusable button, digits on the remote type too.
 */
class StaffPinActivity : Activity() {

    private val code = StringBuilder()
    private lateinit var dots: TextView
    private lateinit var status: TextView
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContentView(pad())
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(text: String, size: Float, bold: Boolean = false, color: Int = Color.WHITE) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

    private fun pad(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(C_BG)
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        root.addView(label("Staff", 13f, true, C_CORAL))
        root.addView(label("Enter the venue PIN", 26f, true), lp(top = 6))
        dots = label("", 30f, true).apply { letterSpacing = 0.4f }
        root.addView(dots, lp(top = 18))
        status = label("", 14f, color = C_MUTED)
        root.addView(status, lp(top = 4))

        val grid = GridLayout(this).apply { columnCount = 3 }
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "OK")
        var first: Button? = null
        for (k in keys) {
            val b = Button(this).apply {
                text = k
                textSize = 22f
                isAllCaps = false
                setTextColor(if (k == "OK") Color.WHITE else C_TEXT)
                background = keyBg(k == "OK")
                isFocusable = true
                contentDescription = when (k) { "⌫" -> "Delete"; "OK" -> "Enter"; else -> k }
                setOnClickListener { press(k) }
            }
            if (first == null) first = b
            grid.addView(b, GridLayout.LayoutParams().apply {
                width = dp(84); height = dp(64)
                setMargins(dp(6), dp(6), dp(6), dp(6))
            })
        }
        root.addView(grid, lp(top = 16))
        root.addView(Button(this).apply {
            text = "Cancel"
            isAllCaps = false
            setTextColor(C_MUTED)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { finish() }
        }, lp(top = 10))
        first?.post { first.requestFocus() }
        refreshLockout()
        return root
    }

    private fun keyBg(primary: Boolean) = android.graphics.drawable.StateListDrawable().apply {
        val focused = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(if (primary) C_CORAL else C_KEY); setStroke(dp(3), Color.WHITE) }
        val normal = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(if (primary) C_CORAL else C_KEY) }
        addState(intArrayOf(android.R.attr.state_focused), focused)
        addState(intArrayOf(android.R.attr.state_pressed), focused)
        addState(intArrayOf(), normal)
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top); gravity = Gravity.CENTER_HORIZONTAL }

    // Digits typed on a remote or a keyboard count as presses too.
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) { press(('0' + (keyCode - KeyEvent.KEYCODE_0)).toString()); return true }
        if (keyCode == KeyEvent.KEYCODE_DEL) { press("⌫"); return true }
        return super.onKeyUp(keyCode, event)
    }

    private fun press(k: String) {
        if (StaffMode.isLockedOut(this)) { refreshLockout(); return }
        when (k) {
            "⌫" -> if (code.isNotEmpty()) code.setLength(code.length - 1)
            "OK" -> { submit(); return }
            else -> if (code.length < 12) code.append(k)
        }
        status.text = ""
        dots.text = "●".repeat(code.length)
    }

    private fun submit() {
        if (code.isEmpty()) return
        val ok = StaffMode.verify(this, code.toString())
        code.setLength(0)
        dots.text = ""
        if (!ok) {
            if (StaffMode.isLockedOut(this)) refreshLockout() else status.text = "Wrong PIN. Try again."
            return
        }
        success()
    }

    private fun refreshLockout() {
        val ms = StaffMode.lockoutRemainingMs(this)
        if (ms <= 0) {
            if (status.text.startsWith("Too many")) status.text = ""
            return
        }
        val s = (ms + 999) / 1000
        status.text = String.format("Too many tries. Try again in %d:%02d", s / 60, s % 60)
        main.postDelayed({ refreshLockout() }, 1000)
    }

    /** "Staff mode on", then out of the lock into the app it was locked to. */
    private fun success() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(C_BG)
        }
        root.addView(label("✓", 54f, true, C_OK))
        root.addView(label("Staff mode on", 26f, true), lp(top = 12))
        root.addView(label("Guest mode is off until you switch it back.", 15f, color = C_MUTED), lp(top = 6))
        setContentView(root)
        val cfg = AgentConfig.get(this)
        val host = if (cfg.kioskMode == "browser") packageName else cfg.kioskPackage
        main.postDelayed({
            StaffMode.enter(this, host)
            KioskManager(this, DeviceOwner(this)).apply(cfg)
            finish()
        }, 1200)
    }

    companion object {
        private val C_BG = Color.parseColor("#1B1530")
        private val C_KEY = Color.parseColor("#2B2440")
        private val C_TEXT = Color.WHITE
        private val C_MUTED = Color.parseColor("#B9B2CC")
        private val C_CORAL = Color.parseColor("#F9674E")
        private val C_OK = Color.parseColor("#3DD68C")
    }
}
