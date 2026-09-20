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
            "MDM status",
            NotificationManager.IMPORTANCE_MIN,
        ).apply { description = "Persistent agent status" }

        val updates = NotificationChannel(
            CHANNEL_UPDATES,
            "MDM updates",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "App installs and command results" }

        nm.createNotificationChannel(status)
        nm.createNotificationChannel(updates)
    }

    companion object {
        const val CHANNEL_STATUS = "mdm_status"
        const val CHANNEL_UPDATES = "mdm_updates"
    }
}
