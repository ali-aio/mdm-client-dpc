package com.aioapp.mdm.agent

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService

/**
 * Long-running foreground service that hosts the checkin loop and WebSocket connection.
 *
 * Phase 0: establishes the foreground lifecycle + notification. Phase 1 wires the OkHttp
 * transport (checkin + WS) and command dispatch on top of this.
 */
class MdmService : LifecycleService() {

    private lateinit var config: AgentConfig
    private lateinit var deviceOwner: DeviceOwner

    override fun onCreate() {
        super.onCreate()
        config = AgentConfig.get(this)
        deviceOwner = DeviceOwner(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
        Log.i(TAG, "MdmService started (deviceOwner=${deviceOwner.isDeviceOwner}, configured=${config.isConfigured})")
        // TODO(Phase 1): start checkin loop + WebSocket transport here.
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val text = if (deviceOwner.isDeviceOwner) "Managed device — agent active" else "Agent running (not provisioned)"
        return NotificationCompat.Builder(this, AgentApp.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    companion object {
        private const val TAG = "MdmService"
        private const val NOTIF_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, MdmService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
