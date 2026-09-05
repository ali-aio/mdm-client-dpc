package com.skorra.agent.net

import android.content.Context
import android.util.Log
import com.skorra.agent.AgentConfig
import com.skorra.agent.DeviceOwner
import com.skorra.agent.device.ApkInstaller
import com.skorra.agent.device.KioskManager
import com.skorra.agent.device.ShellSession
import com.skorra.agent.device.AppRestrictionsManager
import com.skorra.agent.device.NetworkProvisioner
import com.skorra.agent.device.UpdatePolicyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Collections

/**
 * Routes a server `command` frame to the right Device Owner action.
 *
 * Implemented (Phase 2): install_apk, uninstall, reboot, wipe, config (kiosk). Screen/input and
 * diagnostics land in later phases. Truly-unsupported types (shell/ota/update_splash) fail with a
 * clear reason so the dashboard shows why instead of hanging.
 */
class CommandExecutor(
    private val ctx: Context,
    private val deviceOwner: DeviceOwner,
    private val acker: Acker,
    private val scope: CoroutineScope,
) {
    private val config = AgentConfig.get(ctx)
    private val installer = ApkInstaller(ctx, config)
    private val kiosk = KioskManager(ctx, deviceOwner)
    private val shell = ShellSession(acker)
    private val cancelled = Collections.synchronizedSet(mutableSetOf<String>())

    fun handleCommand(frame: JSONObject) {
        val id = frame.optString("id")
        val type = frame.optString("command_type").ifBlank { frame.optString("type") }
        if (id.isBlank()) {
            Log.w(TAG, "command frame missing id: $frame")
            return
        }
        val payload = frame.optJSONObject("payload") ?: JSONObject()
        Log.i(TAG, "command $id type=$type")
        acker.ackCommand(id, "received")

        when (type) {
            "install_apk" -> installApk(id, frame, payload)
            "uninstall" -> uninstall(id, payload)
            "reboot" -> reboot(id)
            "wipe" -> wipe(id)
            "config" -> applyConfigCommand(id, payload)
            "screenshot" -> notYetImplemented(id, type) // covered by live screen capture instead
            "shell" -> shellCmd(id, frame, payload)

            "ota" -> unsupported(id, "OTA deferred for the DPC agent (v1)")
            "update_splash" -> unsupported(id, "boot splash requires system partition access")
            else -> unsupported(id, "unknown command type: $type")
        }
    }

    /** Apply a server `config` frame/payload (kiosk etc.). Shared by WS config messages. */
    fun applyConfig(cfg: JSONObject) {
        config.applyServerConfig(cfg)
        kiosk.apply(config)
        UpdatePolicyManager.apply(deviceOwner, config.updatePolicyJson)
        if (config.locationEnabled) deviceOwner.ensureLocationAccess()
        NetworkProvisioner.apply(ctx, deviceOwner, config.networkConfigJson)
        AppRestrictionsManager.apply(deviceOwner, config.appRestrictionsJson)
    }

    fun cancel(commandId: String) {
        cancelled.add(commandId)
    }

    // ---- handlers ----

    private fun installApk(id: String, frame: JSONObject, payload: JSONObject) {
        val url = frame.optString("apk_url").ifBlank { payload.optString("apk_url") }
        if (url.isBlank()) {
            acker.ackCommand(id, "failed", output = "missing apk_url")
            return
        }
        scope.launch(Dispatchers.IO) {
            acker.ackCommand(id, "downloading", progress = 0)
            var lastReported = -1
            val result = installer.install(url) { pct ->
                if (cancelled.contains(id)) return@install
                if (pct - lastReported >= 5 || pct == 100) {
                    lastReported = pct
                    val status = if (pct >= 100) "installing" else "downloading"
                    acker.ackCommand(id, status, progress = pct)
                }
            }
            when {
                cancelled.remove(id) -> acker.ackCommand(id, "cancelled")
                result.success -> acker.ackCommand(id, "installed", output = "ok", pkg = result.pkg)
                else -> acker.ackCommand(id, "failed", output = result.message)
            }
        }
    }

    private fun uninstall(id: String, payload: JSONObject) {
        val pkg = payload.optString("package")
        if (pkg.isBlank()) {
            acker.ackCommand(id, "failed", output = "missing package")
            return
        }
        scope.launch(Dispatchers.IO) {
            val result = installer.uninstall(pkg)
            if (result.success) acker.ackCommand(id, "completed", output = "uninstalled $pkg", pkg = pkg)
            else acker.ackCommand(id, "failed", output = result.message, pkg = pkg)
        }
    }

    private fun reboot(id: String) {
        if (!deviceOwner.isDeviceOwner) {
            acker.ackCommand(id, "failed", output = "not device owner")
            return
        }
        // Ack before rebooting; the server confirms completion on the device's reconnect.
        acker.ackCommand(id, "completed", output = "rebooting")
        try {
            deviceOwner.dpm.reboot(deviceOwner.admin)
        } catch (e: Exception) {
            acker.ackCommand(id, "failed", output = "reboot rejected: ${e.message}")
        }
    }

    private fun wipe(id: String) {
        if (!deviceOwner.isDeviceOwner) {
            acker.ackCommand(id, "failed", output = "not device owner")
            return
        }
        acker.ackCommand(id, "completed", output = "wiping")
        try {
            deviceOwner.dpm.wipeData(0)
        } catch (e: Exception) {
            acker.ackCommand(id, "failed", output = "wipe rejected: ${e.message}")
        }
    }

    /**
     * "shell" command routed to the persistent [ShellSession] — a real long-lived `sh` where
     * cd/env/state persist across commands. Runs as the app's unprivileged UID. Output streams
     * live via command_output; completion + exit code via command_done (both emitted by the
     * session).
     */
    private fun shellCmd(id: String, frame: JSONObject, payload: JSONObject) {
        val cmd = payload.optString("cmd").ifBlank { payload.optString("command") }
            .ifBlank { frame.optString("cmd") }
        scope.launch(Dispatchers.IO) { shell.run(id, cmd) }
    }

    private fun applyConfigCommand(id: String, payload: JSONObject) {
        try {
            applyConfig(payload)
            acker.ackCommand(id, "completed", output = "config applied")
        } catch (e: Exception) {
            acker.ackCommand(id, "failed", output = "config error: ${e.message}")
        }
    }

    private fun notYetImplemented(id: String, type: String) {
        acker.ackCommand(id, "failed", output = "'$type' not implemented yet")
    }

    private fun unsupported(id: String, reason: String) {
        acker.ackCommand(id, "failed", output = "unsupported on DPC agent: $reason")
    }

    companion object {
        private const val TAG = "CommandExec"
    }
}
