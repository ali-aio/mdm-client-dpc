package aio.app.mdmclient.dpc.net

import android.content.Context
import android.util.Log
import aio.app.mdmclient.dpc.AgentConfig
import aio.app.mdmclient.dpc.DeviceOwner
import aio.app.mdmclient.dpc.Grants
import aio.app.mdmclient.dpc.capture.ScreenCaptureConsentActivity
import aio.app.mdmclient.dpc.device.AgentUpdater
import aio.app.mdmclient.dpc.device.ApkInstaller
import aio.app.mdmclient.dpc.device.KioskManager
import aio.app.mdmclient.dpc.device.ShellSession
import aio.app.mdmclient.dpc.device.AppRestrictionsManager
import aio.app.mdmclient.dpc.device.NetworkProvisioner
import aio.app.mdmclient.dpc.device.UpdatePolicyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Collections

/**
 * Routes a server `command` frame to the right Device Owner action.
 *
 * Implemented (Phase 2): install_apk, app_update, uninstall, reboot, wipe, config (kiosk).
 * Screen/input and diagnostics land in later phases. Unsupported types (update_splash, anything
 * unknown) fail with a clear reason so the dashboard shows why instead of hanging. Firmware OTA
 * (the system partition) is still not here — app_update is this agent updating itself.
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
            "app_update" -> selfUpdate(id, frame, payload)
            "uninstall" -> uninstall(id, payload)
            "reboot" -> reboot(id)
            "wipe" -> wipe(id)
            "config" -> applyConfigCommand(id, payload)
            "screenshot" -> screenshot(id)
            "shell" -> shellCmd(id, frame, payload)

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
        // Installing our own package is an agent update, not an app install: it has to be
        // verified and acknowledged by the version that replaces us.
        if (AgentUpdater.isSelf(ctx, payload.optString("package"))) {
            selfUpdate(id, frame, payload)
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

    /**
     * One frame of the screen, acked as a base64 PNG. Goes through the consent activity
     * like live capture does: with the PROJECT_MEDIA appop granted (tools/enroll-adb.sh)
     * it is silent, otherwise someone confirms the projection prompt once. The ack comes
     * from the capture service, not from here.
     */
    private fun screenshot(id: String) {
        if (!Grants.projectMediaAllowed(ctx)) {
            // Still possible — it just needs the prompt — so this is a note, not a failure.
            Log.i(TAG, "screenshot $id: no PROJECT_MEDIA appop, the device will show the consent prompt")
        }
        try {
            ScreenCaptureConsentActivity.launchStill(ctx, id)
        } catch (e: Exception) {
            acker.ackCommand(id, "failed", output = "could not start capture: ${e.message}")
        }
    }

    /**
     * Agent OTA: install a newer build of this app over the running one. Silent, because a
     * Device Owner installs without a prompt. No terminal ack is sent from here on success —
     * the install kills this process and the new version settles the command at startup.
     */
    private fun selfUpdate(id: String, frame: JSONObject, payload: JSONObject) {
        val url = frame.optString("apk_url").ifBlank { payload.optString("apk_url") }
        scope.launch(Dispatchers.IO) {
            acker.ackCommand(id, "downloading", progress = 0)
            var lastReported = -1
            AgentUpdater.run(
                ctx = ctx,
                installer = installer,
                id = id,
                apkUrl = url,
                payload = payload,
                onProgress = { pct ->
                    if (pct - lastReported >= 5 || pct == 100) {
                        lastReported = pct
                        val status = if (pct >= 100) "installing" else "downloading"
                        acker.ackCommand(id, status, progress = pct)
                    }
                },
            ) { status, output -> acker.ackCommand(id, status, output = output) }
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
