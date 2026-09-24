package aio.app.mdmclient.dpc.net

import android.util.Log
import aio.app.mdmclient.dpc.AgentConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * HTTP device API client (checkin + command ack + logcat), speaking the same contract as
 * the AOSP client's MdmApiService. Auth is the shared `X-API-Key` header.
 *
 * Retries transient failures a few times; a 401 aborts immediately (bad key).
 */
class ApiClient(private val config: AgentConfig) {

    /** Called when the server rejects our key, so the service can enroll again. */
    var onUnauthorized: (() -> Unit)? = null

    /** Why the last request failed, for the status screen ("HTTP 502", a network error). */
    @Volatile var lastError: String = ""
        private set

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** POST /api/v1/checkin — returns parsed response ({status, config?, send_apps?}) or null. */
    fun checkin(payload: JSONObject): JSONObject? =
        postJson("/api/v1/checkin", payload)

    /**
     * POST /api/v1/enroll — exchange a one-per-profile enrollment token for this device's own
     * API key. Unauthenticated by design (the token IS the credential); no X-API-Key header.
     * Returns the response ({device_key, ...}) or null on failure. A 401 means the token is
     * invalid/revoked — the caller should surface that rather than retry forever.
     */
    fun enroll(token: String, identity: JSONObject): JSONObject? {
        val payload = JSONObject(identity.toString()).put("token", token)
        try {
            val req = Request.Builder()
                .url(config.apiUrl("/api/v1/enroll"))
                .post(payload.toString().toRequestBody(JSON))
                .build()
            http.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val text = resp.body?.string().orEmpty()
                    return if (text.isBlank()) JSONObject() else JSONObject(text)
                }
                Log.w(TAG, "enroll failed: HTTP ${resp.code}")
                lastError = if (resp.code == 401) "Enrollment token rejected" else "Enrollment failed: HTTP ${resp.code}"
            }
        } catch (e: Exception) {
            Log.w(TAG, "enroll failed: ${e.message}")
            lastError = "Enrollment failed: ${e.message ?: e.javaClass.simpleName}"
        }
        return null
    }

    /** POST /api/v1/commands/{id}/ack */
    fun ackCommand(commandId: String, body: JSONObject): Boolean =
        postJson("/api/v1/commands/$commandId/ack", body) != null

    /** POST /api/v1/logcat */
    fun postLogcat(body: JSONObject): Boolean =
        postJson("/api/v1/logcat", body) != null

    private fun postJson(path: String, payload: JSONObject, attempts: Int = 3): JSONObject? {
        val url = config.apiUrl(path)
        var lastErr: String? = null
        repeat(attempts) { i ->
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("X-API-Key", config.apiKey)
                    .post(payload.toString().toRequestBody(JSON))
                    .build()
                http.newCall(req).execute().use { resp ->
                    when {
                        resp.isSuccessful -> {
                            val text = resp.body?.string().orEmpty()
                            return if (text.isBlank()) JSONObject() else JSONObject(text)
                        }
                        resp.code == 401 -> {
                            Log.e(TAG, "401 unauthorized for $path — device key rejected")
                            lastError = "Device key rejected"
                            onUnauthorized?.invoke()
                            return null
                        }
                        else -> lastErr = "HTTP ${resp.code}"
                    }
                }
            } catch (e: Exception) {
                lastErr = e.message ?: e.javaClass.simpleName
            }
            // linear-ish backoff with a little growth
            try { Thread.sleep(500L * (i + 1)) } catch (_: InterruptedException) { return null }
        }
        Log.w(TAG, "POST $path failed after $attempts attempts: $lastErr")
        lastError = lastErr.orEmpty()
        return null
    }

    companion object {
        private const val TAG = "ApiClient"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
