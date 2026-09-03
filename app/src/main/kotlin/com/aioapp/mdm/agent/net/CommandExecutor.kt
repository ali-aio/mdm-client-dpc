package com.aioapp.mdm.agent.net

import android.content.Context
import android.util.Log
import com.aioapp.mdm.agent.DeviceOwner
import org.json.JSONObject

/**
 * Routes a server `command` frame to the right Device Owner action.
 *
 * Phase 1: recognizes every command type and acks honestly — truly-unsupported types
 * (shell/ota/update_splash) fail immediately with a clear reason; the ones we'll implement next
 * fail with "not implemented yet". Phase 2 replaces the [notYetImplemented] branches with real
 * DevicePolicyManager / PackageInstaller calls.
 */
class CommandExecutor(
    private val ctx: Context,
    private val deviceOwner: DeviceOwner,
    private val acker: Acker,
) {
    /** Handle a server->device `command` frame: {id, command_type, apk_url?, payload?}. */
    fun handleCommand(frame: JSONObject) {
        val id = frame.optString("id")
        val type = frame.optString("command_type").ifBlank { frame.optString("type") }
        if (id.isBlank()) {
            Log.w(TAG, "command frame missing id: $frame")
            return
        }
        Log.i(TAG, "command $id type=$type")
        acker.ackCommand(id, "received")

        when (type) {
            "install_apk" -> notYetImplemented(id, type)
            "uninstall" -> notYetImplemented(id, type)
            "reboot" -> notYetImplemented(id, type)
            "wipe" -> notYetImplemented(id, type)
            "config" -> notYetImplemented(id, type)
            "screenshot" -> notYetImplemented(id, type)

            // Not possible / out of scope for the DPC agent — fail with a clear reason so the
            // dashboard shows why rather than hanging.
            "shell" -> unsupported(id, "arbitrary shell requires system UID; use the safe-command runner (Phase 4)")
            "ota" -> unsupported(id, "OTA deferred for the DPC agent (v1)")
            "update_splash" -> unsupported(id, "boot splash requires system partition access")

            else -> unsupported(id, "unknown command type: $type")
        }
    }

    private fun notYetImplemented(id: String, type: String) {
        acker.ackCommand(id, "failed", output = "'$type' not implemented yet (Phase 2)")
    }

    private fun unsupported(id: String, reason: String) {
        acker.ackCommand(id, "failed", output = "unsupported on DPC agent: $reason")
    }

    companion object {
        private const val TAG = "CommandExec"
    }
}
