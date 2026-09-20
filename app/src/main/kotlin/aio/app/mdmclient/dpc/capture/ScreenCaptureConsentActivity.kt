package aio.app.mdmclient.dpc.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log

/**
 * Transparent trampoline that obtains one-time MediaProjection consent, then hands the token to
 * [ScreenCaptureService]. This on-device tap is the DPC-agent's unavoidable degradation vs. the
 * system app's silent capture.
 */
class ScreenCaptureConsentActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ && resultCode == RESULT_OK && data != null) {
            val svc = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureService.EXTRA_DATA, data)
                putExtra(ScreenCaptureService.EXTRA_CODEC, intent.getStringExtra(ScreenCaptureService.EXTRA_CODEC))
                putExtra(ScreenCaptureService.EXTRA_QUALITY, intent.getIntExtra(ScreenCaptureService.EXTRA_QUALITY, 70))
                putExtra(ScreenCaptureService.EXTRA_SCALE, intent.getFloatExtra(ScreenCaptureService.EXTRA_SCALE, 0.75f))
                putExtra(ScreenCaptureService.EXTRA_FPS, intent.getIntExtra(ScreenCaptureService.EXTRA_FPS, 15))
                putExtra(ScreenCaptureService.EXTRA_BITRATE, intent.getIntExtra(ScreenCaptureService.EXTRA_BITRATE, 4_000_000))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)
        } else {
            Log.w(TAG, "screen capture consent denied")
        }
        finish()
    }

    companion object {
        private const val TAG = "CaptureConsent"
        private const val REQ = 7001

        /** Params forwarded from the server's start_capture frame. */
        fun launch(ctx: Context, codec: String, quality: Int, scale: Float, fps: Int, bitrate: Int) {
            val i = Intent(ctx, ScreenCaptureConsentActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(ScreenCaptureService.EXTRA_CODEC, codec)
                putExtra(ScreenCaptureService.EXTRA_QUALITY, quality)
                putExtra(ScreenCaptureService.EXTRA_SCALE, scale)
                putExtra(ScreenCaptureService.EXTRA_FPS, fps)
                putExtra(ScreenCaptureService.EXTRA_BITRATE, bitrate)
            }
            ctx.startActivity(i)
        }
    }
}
