package com.aioapp.mdm.agent

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
    val supported: List<String> = listOf(
        "kiosk",
        "install_apk",
        "uninstall",
        "reboot",
        "wipe",
        "config",
        "telemetry",
        // Phase 3/4 flip these on as handlers land:
        // "screen_capture", "input", "security_log", "bugreport", "shell",           // real persistent sh (unprivileged app UID)
    )

    /** Degraded relative to the system app (needs on-device consent, or reduced scope). */
    val degraded: List<String> = listOf(
        "screen_capture", // MediaProjection consent per session
        "input",          // AccessibilityService, user-enabled; taps/swipes + nav keys
        "logcat",         // app-scoped logs only (no cross-app READ_LOGS)
        "shell",           // real persistent sh (unprivileged app UID)
    )

    /** Not possible without system UID / platform signature. */
    val unsupported: List<String> = listOf(
        "logcat_full",    // whole-system READ_LOGS
        "update_splash",  // init-broker partition write
        "ota",            // deferred for v1
    )
}
