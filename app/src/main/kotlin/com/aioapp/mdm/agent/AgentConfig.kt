package com.aioapp.mdm.agent

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

    /** True once the user has saved a server URL + key (onboarding complete enough to connect). */
    val isConfigured: Boolean
        get() = serverUrl.isNotBlank() && apiKey.isNotBlank()

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
