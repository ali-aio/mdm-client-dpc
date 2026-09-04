package com.skorra.agent.capture

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import java.io.ByteArrayOutputStream
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.skorra.agent.AgentApp
import com.skorra.agent.AgentBus
import com.skorra.agent.R
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
    private var configBytes: ByteArray? = null // H.264 SPS/PPS, prepended to each key frame
    private var imageReader: ImageReader? = null
    private var drainThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var lastFrameMs = 0L
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
        val codecName = intent.getStringExtra(EXTRA_CODEC) ?: "h264"
        val quality = intent.getIntExtra(EXTRA_QUALITY, 70).coerceIn(10, 100)

        try {
            startCapture(resultCode, data, fps, bitrate, scale, codecName, quality)
        } catch (e: Exception) {
            Log.e(TAG, "capture start failed: ${e.message}", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, data: Intent, fps: Int, bitrate: Int, scale: Float, codecName: String, quality: Int) {
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

        running = true
        if (codecName == "jpeg") startJpeg(proj, w, h, dpi, fps, quality)
        else startH264(proj, w, h, dpi, fps, bitrate)
        Log.i(TAG, "capture started ${w}x$h @${fps}fps codec=$codecName")
    }

    /** Hardware H.264 (best on real devices) — VirtualDisplay -> MediaCodec input Surface. */
    private fun startH264(proj: MediaProjection, w: Int, h: Int, dpi: Int, fps: Int, bitrate: Int) {
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
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, inputSurface, null, null,
        )
        drainThread = Thread { drainLoop(enc) }.also { it.start() }
    }

    /** JPEG stills via ImageReader — robust fallback that works where H.264 encode/decode
     *  doesn't (e.g. software-GPU emulators). Throttled to the requested fps. */
    private fun startJpeg(proj: MediaProjection, w: Int, h: Int, dpi: Int, fps: Int, quality: Int) {
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        val minIntervalMs = (1000L / fps).coerceAtLeast(33L)
        val handler = Handler(callbackThread.looper)
        reader.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                if (!running) return@setOnImageAvailableListener
                val now = System.currentTimeMillis()
                if (now - lastFrameMs < minIntervalMs) return@setOnImageAvailableListener
                lastFrameMs = now
                imageToJpeg(img, w, h, quality)?.let { AgentBus.acker?.sendBinary(it.toByteString()) }
            } finally {
                img.close()
            }
        }, handler)
        virtualDisplay = proj.createVirtualDisplay(
            "mdm-capture-jpeg", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler,
        )
    }

    private fun imageToJpeg(img: Image, w: Int, h: Int, quality: Int): ByteArray? = try {
        val plane = img.planes[0]
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * w
        val bmpW = w + rowPadding / pixelStride
        var bmp = Bitmap.createBitmap(bmpW, h, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(plane.buffer)
        if (bmpW != w) { val cropped = Bitmap.createBitmap(bmp, 0, 0, w, h); bmp.recycle(); bmp = cropped }
        val out = ByteArrayOutputStream(64 * 1024)
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        bmp.recycle()
        out.toByteArray()
    } catch (e: Exception) {
        Log.w(TAG, "jpeg encode failed: ${e.message}"); null
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
                    // Wire format the dashboard expects: [1-byte type][payload].
                    //   type 1 = key frame — payload = SPS/PPS + IDR (so the decoder can start)
                    //   type 0 = delta
                    // Codec-config buffers (SPS/PPS) are cached, not sent on their own.
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        configBytes = bytes
                    } else {
                        val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                        val cfg = if (isKey) (configBytes ?: ByteArray(0)) else ByteArray(0)
                        val frame = ByteArray(1 + cfg.size + bytes.size)
                        frame[0] = if (isKey) 1 else 0
                        System.arraycopy(cfg, 0, frame, 1, cfg.size)
                        System.arraycopy(bytes, 0, frame, 1 + cfg.size, bytes.size)
                        AgentBus.acker?.sendBinary(frame.toByteString())
                    }
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
        runCatching { imageReader?.close() }
        imageReader = null
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
        const val ACTION_START = "com.skorra.agent.CAPTURE_START"
        const val ACTION_STOP = "com.skorra.agent.CAPTURE_STOP"
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
