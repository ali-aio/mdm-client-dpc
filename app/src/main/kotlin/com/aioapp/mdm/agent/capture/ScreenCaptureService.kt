package com.aioapp.mdm.agent.capture

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.aioapp.mdm.agent.AgentApp
import com.aioapp.mdm.agent.AgentBus
import com.aioapp.mdm.agent.R
import okio.ByteString.Companion.toByteString

/**
 * Foreground (mediaProjection) service that mirrors the screen into an H.264 encoder and streams
 * the encoded NAL units as WebSocket binary frames via [AgentBus.acker] — the same wire shape the
 * dashboard already consumes from the system-app client.
 *
 * Unlike the system app, capture requires a one-time MediaProjection consent obtained by
 * [ScreenCaptureConsentActivity]; this service is started with the resulting token.
 */
class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var codec: MediaCodec? = null
    private var drainThread: Thread? = null
    @Volatile private var running = false
    private lateinit var callbackThread: HandlerThread

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground()
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.getParcelableExtraCompat(EXTRA_DATA, Intent::class.java)
        if (resultCode == 0 || data == null) {
            Log.e(TAG, "missing projection token")
            stopSelf()
            return START_NOT_STICKY
        }
        val fps = intent.getIntExtra(EXTRA_FPS, 15).coerceIn(1, 30)
        val bitrate = intent.getIntExtra(EXTRA_BITRATE, 4_000_000).coerceIn(250_000, 20_000_000)
        val scale = intent.getFloatExtra(EXTRA_SCALE, 0.75f).coerceIn(0.1f, 1.0f)

        try {
            startCapture(resultCode, data, fps, bitrate, scale)
        } catch (e: Exception) {
            Log.e(TAG, "capture start failed: ${e.message}", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, data: Intent, fps: Int, bitrate: Int, scale: Float) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(resultCode, data) ?: throw IllegalStateException("no projection")
        projection = proj

        callbackThread = HandlerThread("cap-cb").apply { start() }
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopCapture(); stopSelf()
            }
        }, Handler(callbackThread.looper))

        val metrics = displayMetrics()
        val dpi = metrics.densityDpi
        var w = (metrics.widthPixels * scale).toInt().roundDownEven()
        var h = (metrics.heightPixels * scale).toInt().roundDownEven()
        if (w < 2) w = 2; if (h < 2) h = 2

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface: Surface = enc.createInputSurface()
        enc.start()
        codec = enc

        virtualDisplay = proj.createVirtualDisplay(
            "mdm-capture", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface, null, null,
        )

        running = true
        drainThread = Thread { drainLoop(enc) }.also { it.start() }
        Log.i(TAG, "capture started ${w}x$h @${fps}fps ${bitrate}bps")
    }

    private fun drainLoop(enc: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = try {
                enc.dequeueOutputBuffer(info, 100_000)
            } catch (e: IllegalStateException) {
                break
            }
            if (index >= 0) {
                val buf = enc.getOutputBuffer(index)
                if (buf != null && info.size > 0) {
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val bytes = ByteArray(info.size)
                    buf.get(bytes)
                    AgentBus.acker?.sendBinary(bytes.toByteString())
                }
                enc.releaseOutputBuffer(index, false)
            }
        }
    }

    private fun stopCapture() {
        running = false
        runCatching { drainThread?.join(500) }
        drainThread = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { projection?.stop() }
        projection = null
        if (this::callbackThread.isInitialized) runCatching { callbackThread.quitSafely() }
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun displayMetrics(): DisplayMetrics {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val m = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            m.widthPixels = b.width()
            m.heightPixels = b.height()
            m.densityDpi = resources.configuration.densityDpi
        } else {
            wm.defaultDisplay.getRealMetrics(m)
        }
        return m
    }

    private fun goForeground() {
        val notif = NotificationCompat.Builder(this, AgentApp.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Screen sharing active")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun Int.roundDownEven(): Int = this and 1.inv()

    companion object {
        private const val TAG = "ScreenCapture"
        private const val NOTIF_ID = 1002
        const val ACTION_START = "com.aioapp.mdm.agent.CAPTURE_START"
        const val ACTION_STOP = "com.aioapp.mdm.agent.CAPTURE_STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val EXTRA_CODEC = "codec"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_SCALE = "scale"
        const val EXTRA_FPS = "fps"
        const val EXTRA_BITRATE = "bitrate"

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, ScreenCaptureService::class.java).setAction(ACTION_STOP))
        }
    }
}

private fun <T> Intent.getParcelableExtraCompat(name: String, clazz: Class<T>): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelableExtra(name, clazz)
    else @Suppress("DEPRECATION") getParcelableExtra(name) as? T
