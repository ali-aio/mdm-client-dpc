package aio.app.mdmclient.dpc

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

/**
 * Application entry point. Registers notification channels used by the foreground service.
 */
class AgentApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)

        val status = NotificationChannel(
            CHANNEL_STATUS,
            "Device status",
            NotificationManager.IMPORTANCE_MIN,
        ).apply { description = "Shows that this device is managed by AIO and connected." }

        val updates = NotificationChannel(
            CHANNEL_UPDATES,
            "Updates",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "Progress and problems for app installs and agent updates." }

        nm.createNotificationChannel(status)
        nm.createNotificationChannel(updates)
    }

    companion object {
        const val CHANNEL_STATUS = "mdm_status"
        const val CHANNEL_UPDATES = "mdm_updates"
    }
}
