package aio.app.mdmclient.dpc.kiosk

import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
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
import aio.app.mdmclient.dpc.DeviceIdentity
import aio.app.mdmclient.dpc.DeviceOwner
import aio.app.mdmclient.dpc.device.KioskManager

/**
 * The staff PIN pad, U1 · Split (picked 28 Oct 2026, the same design as the firmware client's):
 * on a landscape screen, who and what on the left (the venue, "Staff unlock", what happens, the
 * device) and the keypad on the right; on a portrait screen the two stack. Round keys, one coral
 * key; a wrong PIN shakes the dots red; too many tries greys the keys with a countdown. A right
 * PIN shows "Staff mode on" for a moment, then the device leaves the lock and opens the locked
 * app with the "Back to guest mode" pill.
 *
 * Works by touch and by D-pad (TV boxes): every key is a focusable button with a white ring
 * when focused, and digits on a remote or a keyboard type too.
 */
class StaffPinActivity : Activity() {

    private val code = StringBuilder()
    private lateinit var dots: Dots
    private lateinit var status: TextView
    private val keys = mutableListOf<Button>()
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContentView(pad())
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val landscape get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun label(text: String, size: Float, medium: Boolean = false, color: Int = C_TEXT, alpha: Float = 1f) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            this.alpha = alpha
            typeface = Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        }

    private fun background() = GradientDrawable().apply {
        gradientType = GradientDrawable.RADIAL_GRADIENT
        colors = intArrayOf(Color.parseColor("#2C2350"), Color.parseColor("#161227"), Color.parseColor("#0F0C1C"))
        setGradientCenter(0f, 0f)
        gradientRadius = (maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 1.15f)
    }

    private fun pad(): View {
        val root = LinearLayout(this).apply {
            orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            background = background()
        }
        val info = infoPanel()
        val keypad = keypadPanel()
        if (landscape) {
            root.addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            root.addView(View(this).apply { setBackgroundColor(Color.argb(18, 255, 255, 255)) },
                LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT))
            root.addView(keypad, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        } else {
            root.addView(info, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            root.addView(keypad, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        keys.firstOrNull()?.post { keys.first().requestFocus() }
        refreshLockout()
        return root
    }

    /** The venue, what this screen is, what happens, and which device. */
    private fun infoPanel(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (landscape) Gravity.START else Gravity.CENTER_HORIZONTAL
            setPadding(dp(if (landscape) 48 else 28), dp(if (landscape) 44 else 36), dp(if (landscape) 40 else 28), dp(if (landscape) 36 else 4))
        }
        val venue = StaffMode.venue(this)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(this).apply {
            text = initials(venue)
            textSize = 12f
            setTextColor(C_TEXT)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(C_CORAL, C_VIOLET)).apply { cornerRadius = dp(10).toFloat() }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        row.addView(label(venue.ifEmpty { "AIO" }, 16f, medium = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(12) })
        col.addView(row)

        if (landscape) col.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        col.addView(label("STAFF UNLOCK", 12f, medium = true, color = C_KICKER).apply { letterSpacing = 0.14f },
            margins(top = if (landscape) 0 else 26))
        col.addView(label(if (landscape) "Enter the\nvenue PIN" else "Enter the venue PIN", if (landscape) 36f else 28f, medium = true).apply {
            setLineSpacing(0f, 1.05f)
            if (!landscape) gravity = Gravity.CENTER
        }, margins(top = 6))
        col.addView(label(
            if (landscape) "The device leaves guest mode until you switch it back. Your manager has the PIN."
            else "Leaves guest mode until you switch it back.",
            15f, alpha = 0.62f,
        ).apply { if (!landscape) gravity = Gravity.CENTER; maxWidth = dp(340) }, margins(top = 10))
        if (landscape) {
            col.addView(label("${Build.MODEL} · ${DeviceIdentity.serial(this)}", 11f, alpha = 0.4f).apply { letterSpacing = 0.03f },
                margins(top = 22))
        }
        return col
    }

    private fun keypadPanel(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(16), dp(16), dp(20))
        }
        dots = Dots(this, StaffMode.pin(this).length.coerceIn(4, 8))
        col.addView(dots, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(18)))
        status = label("", 14f, color = C_ERR).apply { gravity = Gravity.CENTER; minHeight = dp(20) }
        col.addView(status, margins(top = 10))

        val grid = GridLayout(this).apply { columnCount = 3 }
        val size = dp(if (landscape) 72 else 76)
        for (k in listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", DEL, "0", GO)) {
            val b = Button(this).apply {
                text = k
                textSize = if (k == DEL) 16f else 24f
                isAllCaps = false
                stateListAnimator = null
                setPadding(0, 0, 0, 0)
                setTextColor(C_TEXT)
                if (k == DEL) alpha = 0.8f
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                background = keyBg(k)
                isFocusable = true
                contentDescription = when (k) { DEL -> "Delete"; GO -> "Unlock"; else -> k }
                setOnClickListener { press(k) }
            }
            keys += b
            grid.addView(b, GridLayout.LayoutParams().apply {
                width = size; height = size
                setMargins(dp(11), dp(7), dp(11), dp(7))
            })
        }
        col.addView(grid, margins(top = 8))
        col.addView(Button(this).apply {
            text = "Cancel"
            textSize = 15f
            isAllCaps = false
            stateListAnimator = null
            setTextColor(C_TEXT)
            alpha = 0.7f
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setStroke(dp(2), Color.WHITE) })
                addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT) })
            }
            setOnClickListener { finish() }
        }, margins(top = 8))
        return col
    }

    private fun keyBg(k: String) = StateListDrawable().apply {
        val fill = when (k) { GO -> C_CORAL; DEL -> Color.TRANSPARENT; else -> C_KEY }
        val pressed = when (k) { GO -> C_CORAL_DN; DEL -> C_KEY; else -> C_KEY_DN }
        fun oval(c: Int, ring: Boolean) = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(c)
            if (ring) setStroke(dp(3), Color.WHITE)
        }
        addState(intArrayOf(android.R.attr.state_pressed), oval(pressed, false))
        addState(intArrayOf(android.R.attr.state_focused), oval(if (k == DEL) C_KEY else fill, true))
        addState(intArrayOf(), oval(fill, false))
    }

    private fun margins(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun initials(venue: String): String {
        val words = venue.split(Regex("\\s+")).filter { it.firstOrNull()?.isLetterOrDigit() == true }
        return when {
            words.isEmpty() -> "AIO"
            words.size == 1 -> words[0].take(2).uppercase()
            else -> (words[0].take(1) + words[1].take(1)).uppercase()
        }
    }

    // Digits typed on a remote or a keyboard count as presses too.
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) { press(('0' + (keyCode - KeyEvent.KEYCODE_0)).toString()); return true }
        if (keyCode == KeyEvent.KEYCODE_DEL) { press(DEL); return true }
        return super.onKeyUp(keyCode, event)
    }

    private fun press(k: String) {
        if (StaffMode.isLockedOut(this)) { refreshLockout(); return }
        when (k) {
            DEL -> if (code.isNotEmpty()) code.setLength(code.length - 1)
            GO -> { submit(); return }
            else -> if (code.length < 12) code.append(k)
        }
        status.text = ""
        dots.set(code.length, false)
        // A PIN of the venue's length goes in by itself, like a phone's lock screen.
        if (k != DEL && code.length == StaffMode.pin(this).length) main.postDelayed({ submit() }, 120)
    }

    private fun submit() {
        if (code.isEmpty()) return
        val ok = StaffMode.verify(this, code.toString())
        code.setLength(0)
        if (!ok) {
            dots.set(dots.slots, true)
            ObjectAnimator.ofFloat(dots, "translationX", 0f, -dp(10).toFloat(), dp(10).toFloat(), -dp(7).toFloat(), dp(7).toFloat(), 0f)
                .setDuration(380).start()
            main.postDelayed({ if (code.isEmpty()) dots.set(0, false) }, 700)
            if (StaffMode.isLockedOut(this)) refreshLockout() else status.text = "Wrong PIN. Try again."
            return
        }
        dots.set(0, false)
        success()
    }

    private fun refreshLockout() {
        val ms = StaffMode.lockoutRemainingMs(this)
        keys.forEach { it.alpha = if (ms > 0) 0.35f else if (it.text == DEL) 0.8f else 1f; it.isEnabled = ms <= 0 }
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
            background = background()
        }
        root.addView(label("✓", 34f, color = C_OK).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(46, 91, 227, 143)) }
        }, LinearLayout.LayoutParams(dp(76), dp(76)))
        root.addView(label("Staff mode on", 28f, medium = true), margins(top = 18))
        val back = if (packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK))
            "Enter the code again for guest mode." else "Back to guest mode from the pill at the top."
        root.addView(label(back, 15f, alpha = 0.62f), margins(top = 6))
        setContentView(root)
        val cfg = AgentConfig.get(this)
        val host = if (cfg.kioskMode == "browser") packageName else cfg.kioskPackage
        main.postDelayed({
            StaffMode.enter(this, host)
            KioskManager(this, DeviceOwner(this)).apply(cfg)
            finish()
        }, 1200)
    }

    /** The PIN's dots: [slots] rings, the first [filled] solid; red after a wrong PIN. */
    private class Dots(ctx: Context, var slots: Int) : View(ctx) {
        private var filled = 0
        private var wrong = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val d = ctx.resources.displayMetrics.density

        fun set(n: Int, wrong: Boolean) {
            if (n > slots) slots = n
            filled = n; this.wrong = wrong
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val r = 7f * d
            val gap = 16f * d
            val total = slots * 2 * r + (slots - 1) * gap
            var x = (width - total) / 2 + r
            val y = height / 2f
            val ink = if (wrong) C_WRONG else Color.WHITE
            for (i in 0 until slots) {
                paint.style = if (i < filled) Paint.Style.FILL else Paint.Style.STROKE
                paint.strokeWidth = 2f * d
                paint.color = if (i < filled) ink else if (wrong) C_WRONG else Color.argb(140, 255, 255, 255)
                canvas.drawCircle(x, y, if (i < filled) r else r - d, paint)
                x += 2 * r + gap
            }
        }
    }

    companion object {
        private const val DEL = "Del"
        private const val GO = "→"
        private val C_TEXT = Color.WHITE
        private val C_KICKER = Color.parseColor("#FFB199")
        private val C_KEY = Color.argb(20, 255, 255, 255)
        private val C_KEY_DN = Color.argb(48, 255, 255, 255)
        private val C_CORAL = Color.parseColor("#F9674E")
        private val C_CORAL_DN = Color.parseColor("#E0533B")
        private val C_VIOLET = Color.parseColor("#7A80F6")
        private val C_ERR = Color.parseColor("#FFC1B4")
        private val C_WRONG = Color.parseColor("#FF8A78")
        private val C_OK = Color.parseColor("#5BE38F")
    }
}
