package aio.app.mdmclient.dpc

import android.app.Notification
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File

/**
 * How the agent's notifications look and read (N2, picked 23 Oct 2026, "our own words"; the
 * same as the Firmware MDM's): the AIO ring in coral, the app's own picture, plain words.
 *
 * One difference from the Firmware MDM: this is an ordinary app, so it cannot take down
 * Android's own "Updated by your admin" (posted by the framework for every Device Owner
 * install, with the installed app's icon). An install that worked therefore leaves that
 * notice and removes ours; one that failed says so in ours.
 */
object Notices {
    private const val TAG = "Notices"
    const val ACCENT = 0xFFCF4529.toInt()
    const val GROUP_UPDATES = "aio.app.mdmclient.dpc.UPDATES"
    private const val FAILED_TIMEOUT_MS = 60 * 60_000L
    const val STATUS_ID = 1001
    const val SELF_UPDATE_ID = 1999

    @Volatile private var lastStatus = ""

    fun base(ctx: Context, channel: String, sub: String): NotificationCompat.Builder =
        NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_notify_aio)
            .setColor(ACCENT)
            .setSubText(sub)

    private fun version(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty()

    /** {title, text} of the status line. */
    private fun statusLine(ctx: Context): Pair<String, String> {
        val v = version(ctx)
        val detail = if (v.isEmpty()) "" else "MDM $v"
        val owner = runCatching {
            ctx.getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(ctx.packageName)
        }.getOrDefault(false)
        val title = when {
            !owner -> return "Not set up yet" to "Enrol it with AIO Enroll"
            AgentStatus.wsConnected || AgentStatus.lastAttemptOk -> "Connected to AIO"
            AgentStatus.lastAttemptAtMs == 0L -> "Connecting to AIO"
            else -> "Offline · trying again"
        }
        return title to detail
    }

    fun status(ctx: Context): Notification {
        val (title, text) = statusLine(ctx)
        lastStatus = "$title\n$text"
        return base(ctx, AgentApp.CHANNEL_STATUS, "Managed device")
            .setContentTitle(title)
            .setContentText(text)
            .setShowWhen(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    /** Re-posts the status line when what it says changed (connection, enrolment). */
    fun refreshStatus(ctx: Context) {
        val (title, text) = statusLine(ctx)
        if ("$title\n$text" == lastStatus) return
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(STATUS_ID, status(ctx)) }
            .onFailure { Log.w(TAG, "status: ${it.message}") }
    }

    /** What an install notice says about the app being installed. */
    class InstallNote {
        var name = ""
        var version = ""
        var icon: Bitmap? = null
        fun what() = name.ifEmpty { "an app" }
    }

    /** Fills [note] from a downloaded APK: label, "2-pos (code 3)", icon. */
    fun describe(ctx: Context, apk: File, note: InstallNote) {
        val pm = ctx.packageManager
        val info: PackageInfo = runCatching { pm.getPackageArchiveInfo(apk.path, 0) }.getOrNull() ?: return
        info.applicationInfo?.let { ai ->
            ai.sourceDir = apk.path
            ai.publicSourceDir = apk.path
            runCatching { ai.loadLabel(pm).toString() }.getOrNull()?.takeIf { it.isNotBlank() }?.let { note.name = it }
            note.icon = runCatching { bitmap(ai.loadIcon(pm)) }.getOrNull()
        }
        val name = info.versionName?.trim().orEmpty()
        val code = info.longVersionCode
        note.version = when {
            name.isEmpty() && code > 0 -> "code $code"
            code > 0 -> "$name (code $code)"
            else -> name
        }
    }

    private fun bitmap(d: Drawable?): Bitmap? {
        if (d == null) return null
        if (d is BitmapDrawable && d.bitmap != null) return d.bitmap
        val b = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, 128, 128)
        d.draw(Canvas(b))
        return b
    }

    /** Download / install progress: [percent] < 0 is indeterminate. */
    fun installProgress(ctx: Context, id: Int, note: InstallNote, downloading: Boolean, text: String?, percent: Int) {
        val b = base(ctx, AgentApp.CHANNEL_UPDATES, "Updates")
            .setGroup(GROUP_UPDATES)
            .setLargeIcon(note.icon)
            .setContentTitle((if (downloading) "Downloading " else "Installing ") + note.what())
            .setContentText(text ?: note.version)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
        if (percent >= 0) b.setProgress(100, percent, false) else b.setProgress(0, 0, true)
        notify(ctx, id, b.build())
    }

    fun installFailed(ctx: Context, id: Int, note: InstallNote) {
        notify(ctx, id, base(ctx, AgentApp.CHANNEL_UPDATES, "Updates")
            .setGroup(GROUP_UPDATES)
            .setLargeIcon(note.icon)
            .setContentTitle("Couldn't install " + note.what())
            .setContentText("We'll try again")
            .setTimeoutAfter(FAILED_TIMEOUT_MS)
            .setOnlyAlertOnce(true)
            .build())
    }

    fun cancel(ctx: Context, id: Int) {
        runCatching { ctx.getSystemService(NotificationManager::class.java).cancel(id) }
    }

    /** At start: an install that was running when the process ended (our own update ends it)
     *  is finished by nobody, so its progress notice would sit there. */
    fun clearStale(ctx: Context) {
        runCatching {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            for (sbn in nm.activeNotifications) {
                val n = sbn.notification
                if (n.group == GROUP_UPDATES && n.flags and Notification.FLAG_ONGOING_EVENT != 0) {
                    nm.cancel(sbn.tag, sbn.id)
                }
            }
        }.onFailure { Log.w(TAG, "stale install notices: ${it.message}") }
    }

    private fun notify(ctx: Context, id: Int, n: Notification) {
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(id, n) }
            .onFailure { Log.w(TAG, "notify: ${it.message}") }
    }
}
