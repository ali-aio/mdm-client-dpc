package com.aioapp.mdm.agent.net

import android.util.Log
import com.aioapp.mdm.agent.AgentConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * HTTP device API client (checkin + command ack + logcat + ota), speaking the same contract as
 * the AOSP client's MdmApiService. Auth is the shared `X-API-Key` header.
 *
 * Retries transient failures a few times; a 401 aborts immediately (bad key).
 */
class ApiClient(private val config: AgentConfig) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** POST /api/v1/checkin — returns parsed response ({status, config?, send_apps?}) or null. */
    fun checkin(payload: JSONObject): JSONObject? =
        postJson("/api/v1/checkin", payload)

    /** POST /api/v1/commands/{id}/ack */
    fun ackCommand(commandId: String, body: JSONObject): Boolean =
        postJson("/api/v1/commands/$commandId/ack", body) != null

    /** POST /api/v1/logcat */
    fun postLogcat(body: JSONObject): Boolean =
        postJson("/api/v1/logcat", body) != null

    /** POST /api/v1/ota/status */
    fun postOtaStatus(body: JSONObject): Boolean =
        postJson("/api/v1/ota/status", body) != null

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
                            Log.e(TAG, "401 unauthorized for $path — check DEVICE_API_KEY")
                            return null
                        }
                        else -> lastErr = "HTTP ${resp.code}"
                    }
                }
            } catch (e: Exception) {
                lastErr = e.message
            }
            // linear-ish backoff with a little growth
            try { Thread.sleep(500L * (i + 1)) } catch (_: InterruptedException) { return null }
        }
        Log.w(TAG, "POST $path failed after $attempts attempts: $lastErr")
        return null
    }

    companion object {
        private const val TAG = "ApiClient"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
