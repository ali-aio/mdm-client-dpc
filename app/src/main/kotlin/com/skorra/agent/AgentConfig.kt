package com.skorra.agent

import android.content.Context

/**
 * Prefs-backed runtime config: server URL + device API key, overridable from the onboarding
 * screen. Build-time defaults come from [BuildConfig]. Mirrors the system-app client's
 * `persist.sys.mdm.*` overrides, but a normal app can't read system props, so we persist here.
 *
 * Stored in device-protected storage so it survives Direct Boot (before the user unlocks),
 * matching the boot-time checkin behavior of the original client.
 */
class AgentConfig private constructor(private val ctx: Context) {

    private val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, BuildConfig.DEFAULT_SERVER_URL)!!
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value.trim().trimEnd('/')).apply()

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, BuildConfig.DEFAULT_API_KEY)!!
        set(value) = prefs.edit().putString(KEY_API_KEY, value.trim()).apply()

    /**
     * Pending enrollment token from a QR/zero-touch provisioning payload (or manual entry).
     * Exchanged once at POST /api/v1/enroll for a per-device key, then cleared — so a stolen
     * QR code can be revoked server-side without rotating the whole fleet's credentials.
     */
    var enrollToken: String
        get() = prefs.getString(KEY_ENROLL_TOKEN, "")!!
        set(value) = prefs.edit().putString(KEY_ENROLL_TOKEN, value.trim()).apply()

    /** True once the user has saved a server URL + key (onboarding complete enough to connect). */
    val isConfigured: Boolean
        get() = serverUrl.isNotBlank() && apiKey.isNotBlank()

    /** True when we have a server + enrollment token but no device key yet: enroll first. */
    val needsEnrollment: Boolean
        get() = serverUrl.isNotBlank() && apiKey.isBlank() && enrollToken.isNotBlank()

    /**
     * Seed config from a managed-provisioning admin-extras bundle (QR / zero-touch).
     * Recognised keys: `server_url`, `enroll_token`, and `api_key` (direct shared-key
     * fallback for closed setups without enrollment profiles). Ignores blanks so a
     * partial bundle can't wipe out already-working config.
     */
    fun seedFromProvisioningExtras(extras: android.os.PersistableBundle) {
        extras.getString("server_url")?.takeIf { it.isNotBlank() }?.let { serverUrl = it }
        extras.getString("enroll_token")?.takeIf { it.isNotBlank() }?.let { enrollToken = it }
        extras.getString("api_key")?.takeIf { it.isNotBlank() }?.let { apiKey = it }
    }

    // ---- Server-pushed config (checkin `config` object / WS `config` frame) ----

    var checkinIntervalSeconds: Int
        get() = prefs.getInt(KEY_CHECKIN_INTERVAL, DEFAULT_CHECKIN_INTERVAL)
        set(value) = prefs.edit().putInt(KEY_CHECKIN_INTERVAL, value.coerceIn(10, 3600)).apply()

    var kioskEnabled: Boolean
        get() = prefs.getBoolean(KEY_KIOSK_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_KIOSK_ENABLED, value).apply()

    var kioskPackage: String
        get() = prefs.getString(KEY_KIOSK_PACKAGE, "")!!
        set(value) = prefs.edit().putString(KEY_KIOSK_PACKAGE, value).apply()

    /**
     * Absorb a server `config` object (from the checkin response or a WS `config` frame). Kiosk
     * enforcement itself happens in the service (Phase 2); here we just persist the desired state.
     * Returns true if the checkin interval changed (so the caller can reschedule).
     */
    fun applyServerConfig(cfg: org.json.JSONObject): Boolean {
        var intervalChanged = false
        if (cfg.has("checkin_interval_seconds")) {
            val newInterval = cfg.optInt("checkin_interval_seconds", checkinIntervalSeconds)
            if (newInterval != checkinIntervalSeconds) {
                checkinIntervalSeconds = newInterval
                intervalChanged = true
            }
        }
        if (cfg.has("kiosk_enabled")) kioskEnabled = cfg.optBoolean("kiosk_enabled", false)
        if (cfg.has("kiosk_package")) kioskPackage = cfg.optString("kiosk_package", "")
        return intervalChanged
    }

    /** Derived WebSocket URL: http(s) -> ws(s), path /api/v1/ws?serial=... */
    fun wsUrl(serial: String): String {
        val base = serverUrl
        val wsBase = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            else -> base
        }
        return "$wsBase/api/v1/ws?serial=${android.net.Uri.encode(serial)}"
    }

    fun apiUrl(path: String): String = serverUrl + path

    companion object {
        private const val PREFS = "mdm_agent"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_ENROLL_TOKEN = "enroll_token"
        private const val KEY_CHECKIN_INTERVAL = "checkin_interval"
        private const val KEY_KIOSK_ENABLED = "kiosk_enabled"
        private const val KEY_KIOSK_PACKAGE = "kiosk_package"
        private const val DEFAULT_CHECKIN_INTERVAL = 30

        @Volatile private var instance: AgentConfig? = null

        fun get(context: Context): AgentConfig =
            instance ?: synchronized(this) {
                // Use device-protected storage context so config is readable pre-unlock at boot.
                val ctx = context.applicationContext
                val storageCtx = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    ctx.createDeviceProtectedStorageContext()
                } else ctx
                instance ?: AgentConfig(storageCtx).also { instance = it }
            }
    }
}
