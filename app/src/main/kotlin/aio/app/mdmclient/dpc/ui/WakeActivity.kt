package aio.app.mdmclient.dpc.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager

/**
 * An activity whose whole job is to exist for a moment with the screen on.
 *
 * `setTurnScreenOn` + `setShowWhenLocked` are the supported way for an app without the
 * system UID to wake a display, and on A15 they are the only way — see [ScreenControl].
 * The activity is fully transparent and finishes as soon as it has resumed, so the operator
 * sees the device's own screen come on, not ours.
 *
 * Nothing here is conditional on being Device Owner: a DPC is exempt from the background
 * activity-start restrictions, which is what lets this be launched from the WebSocket
 * handler while the app has no UI in front.
 */
class WakeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        // Remote control through a lock screen is useless, so ask for it to go. On a device
        // with no secure lock (every kiosk and menu board) this dismisses it outright; on one
        // with a PIN the system prompts the user, which is the most we are allowed to do.
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (km?.isKeyguardLocked == true) {
            runCatching { km.requestDismissKeyguard(this, null) }
                .onFailure { Log.w(TAG, "requestDismissKeyguard failed: ${it.message}") }
        }
    }

    override fun onResume() {
        super.onResume()
        // Finish on the next loop rather than here: finishing inside onResume can cancel the
        // turn-screen-on before the window has actually been shown, which is the difference
        // between a screen that wakes and one that flickers and goes back to black.
        Handler(Looper.getMainLooper()).postDelayed({ if (!isFinishing) finish() }, FINISH_DELAY_MS)
    }

    companion object {
        private const val TAG = "WakeActivity"
        private const val FINISH_DELAY_MS = 400L

        fun launch(ctx: Context) {
            val intent = Intent(ctx, WakeActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_NO_HISTORY or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
            }
            runCatching { ctx.startActivity(intent) }
                .onFailure { Log.w(TAG, "could not start wake activity: ${it.message}") }
        }
    }
}
