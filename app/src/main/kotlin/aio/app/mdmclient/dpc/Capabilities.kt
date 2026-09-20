package aio.app.mdmclient.dpc

import android.content.Context

/**
 * What this DPC agent can and cannot do, reported to the server on checkin as
 * `extra.agent_type` + `extra.capabilities` so the dashboard can gray out unsupported actions
 * instead of letting operators fire commands that will only fail.
 *
 * A Device Owner on a stock (non-platform-signed) device loses a handful of the system-app
 * client's powers — see the capability matrix in the plan. Keep this list honest: only advertise
 * a capability once its handler is actually implemented.
 */
object Capabilities {

    const val AGENT_TYPE = "dpc"

    /** Fully supported via DevicePolicyManager / PackageInstaller. */
    private val base: List<String> = listOf(
        "kiosk",
        "install_apk",
        "uninstall",
        // Agent OTA: install a newer build of this app over itself (AgentUpdater).
        "self_update",
        "reboot",
        "wipe",
        "config",
        "telemetry",
    )

    /**
     * Degraded relative to the system app — unless the adb grants from tools/enroll-adb.sh
     * are in place, which lift each one to full support without root:
     *  - screen_capture: MediaProjection consent per session; PROJECT_MEDIA appop = silent
     *  - input: AccessibilityService, taps/swipes + nav/D-pad keys; full once it is enabled
     *  - logcat: app-scoped only; READ_LOGS = whole-device logcat
     *  - shell: real persistent sh, always the unprivileged app UID
     */
    private fun upgraded(ctx: Context): Map<String, Boolean> = mapOf(
        "screen_capture" to Grants.projectMediaAllowed(ctx),
        "input" to Grants.accessibilityEnabled(ctx),
        "logcat" to Grants.readLogs(ctx),
        "shell" to false,
    )

    fun supported(ctx: Context): List<String> = base + upgraded(ctx).filterValues { it }.keys

    fun degraded(ctx: Context): List<String> = upgraded(ctx).filterValues { !it }.keys.toList()

    /** Not possible without system UID / platform signature. */
    val unsupported: List<String> = listOf(
        "logcat_full",    // whole-system READ_LOGS
        "update_splash",  // init-broker partition write
    )
}
