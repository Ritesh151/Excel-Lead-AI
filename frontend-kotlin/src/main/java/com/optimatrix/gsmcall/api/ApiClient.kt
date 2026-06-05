package com.optimatrix.gsmcall.api

import android.content.Context
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.optimatrix.gsmcall.NetworkConfig
import com.optimatrix.gsmcall.telephony.CallSession
import com.optimatrix.gsmcall.utils.LogStore
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * ApiClient — HTTP communication with backend-node.
 *
 * Endpoints:
 *   POST /api/calls/recording   — upload WAV recording for transcription
 *   POST /api/adb/call          — trigger a single ADB test call
 *   GET  /health                — backend health check
 *
 * BASE_URL is read from BuildConfig so it can be overridden at build time.
 * Default: http://10.0.2.2:3000 (Android emulator → localhost)
 *          http://192.168.x.x:3000 (real device on same WiFi)
 *
 * The backend-node receives the recording, saves it locally, then proxies
 * it to ai-python /api/calls/recording for Whisper transcription.
 * The response contains { intent, transcription } from Whisper.
 */
class ApiClient(private val context: Context) {

    companion object {
        /** Override in gradle.properties: BACKEND_HOST / BACKEND_PORT */
        private val BASE_URL = NetworkConfig.httpBaseUrl

        /** Recording upload — backend-node proxies this to ai-python */
        private const val RECORDING_ENDPOINT = "/api/calls/recording"

        /** Health check */
        private const val HEALTH_ENDPOINT = "/health"

        /** Campaign status */
        private const val CAMPAIGN_STATUS_ENDPOINT = "/api/adb/status"
    }

    enum class CallerIntent { YES, NO, UNKNOWN }

    data class UploadResult(
        val intent: CallerIntent,
        val transcription: String?,
        val success: Boolean = true,
        val errorMessage: String? = null,
    )

    data class HealthResult(
        val online: Boolean,
        val websocketClients: Int = 0,
        val androidConnected: Boolean = false,
    )

    private val client = OkHttpClient.Builder()
        .callTimeout(120, TimeUnit.SECONDS)   // 2 minutes — Whisper takes time
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)       // Retry on transient failures
        .build()

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val intentResponseAdapter: JsonAdapter<IntentResponse> =
        moshi.adapter(IntentResponse::class.java)

    // ── Recording upload ───────────────────────────────────────────────────────

    /**
     * Upload a WAV recording to backend-node for Whisper transcription.
     *
     * Multipart form fields:
     *   file         — WAV file
     *   callType     — "outgoing" or "incoming"
     *   remoteNumber — customer phone number
     *   timestamp    — Unix millis as string
     *
     * Returns the Whisper intent + transcription parsed from JSON response.
     */
    fun uploadRecording(recording: File, session: CallSession): UploadResult {
        LogStore.log("ApiClient", "Uploading ${recording.name} (${recording.length()} bytes) → $BASE_URL$RECORDING_ENDPOINT")

        if (!recording.exists() || recording.length() < 44) {
            LogStore.log("ApiClient", "Recording file invalid — skip upload")
            return UploadResult(CallerIntent.UNKNOWN, null, false, "Invalid recording file")
        }

        return try {
            val fileBody  = recording.asRequestBody("audio/wav".toMediaTypeOrNull())
            val formData  = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", recording.name, fileBody)
                .addFormDataPart("callType", if (session.outgoing) "outgoing" else "incoming")
                .addFormDataPart("remoteNumber", session.remoteNumber ?: "unknown")
                .addFormDataPart("timestamp", System.currentTimeMillis().toString())
                .build()

            val request = Request.Builder()
                .url(BASE_URL + RECORDING_ENDPOINT)
                .post(formData)
                .addHeader("User-Agent", "Android-GSM-AI/3.0 (okhttp)")
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                LogStore.log("ApiClient", "Upload response: status=${response.code} body=${bodyStr.take(200)}")

                if (!response.isSuccessful) {
                    LogStore.log("ApiClient", "Upload failed status=${response.code}")
                    return UploadResult(CallerIntent.UNKNOWN, null, false, "HTTP ${response.code}")
                }

                val parsed = intentResponseAdapter.fromJson(bodyStr)
                val intent = when (parsed?.intent?.lowercase()) {
                    "yes" -> CallerIntent.YES
                    "no"  -> CallerIntent.NO
                    else  -> CallerIntent.UNKNOWN
                }

                LogStore.log("ApiClient", "Transcription result: intent=${parsed?.intent} text=${parsed?.transcription?.take(80)}")
                UploadResult(intent, parsed?.transcription, true)
            }
        } catch (ex: Exception) {
            LogStore.log("ApiClient", "Upload exception: ${ex.javaClass.simpleName}: ${ex.message}")
            UploadResult(CallerIntent.UNKNOWN, null, false, ex.message)
        }
    }

    // ── Health check ──────────────────────────────────────────────────────────

    /**
     * Check if backend-node is reachable.
     * Returns HealthResult with connectivity status.
     */
    fun checkHealth(): HealthResult {
        return try {
            LogStore.log("ApiClient", "Health check → GET $BASE_URL$HEALTH_ENDPOINT")
            val request = Request.Builder()
                .url(BASE_URL + HEALTH_ENDPOINT)
                .get()
                .addHeader("User-Agent", "Android-GSM-AI/3.0 (okhttp)")
                .build()
            client.newCall(request).execute().use { response ->
                LogStore.log("ApiClient", "Health response: ${response.code}")
                if (!response.isSuccessful) {
                    LogStore.log("ApiClient", "Health check failed: HTTP ${response.code}")
                    return HealthResult(false)
                }
                val body = response.body?.string() ?: return HealthResult(false)
                LogStore.log("ApiClient", "Health body: ${body.take(200)}")
                val json = JSONObject(body)
                val ws = json.optJSONObject("websocket")
                HealthResult(
                    online           = true,
                    websocketClients = ws?.optInt("clients", 0) ?: 0,
                    androidConnected = ws?.optBoolean("android", false) ?: false,
                )
            }
        } catch (ex: Exception) {
            LogStore.log("ApiClient", "Health check exception: ${ex.javaClass.simpleName}: ${ex.message}")
            LogStore.log("ApiClient", "Health check failed — backend-node unreachable at $BASE_URL")
            HealthResult(false)
        }
    }

    // ── Campaign status ───────────────────────────────────────────────────────

    /**
     * Fetch current ADB campaign status from backend-node.
     */
    fun fetchCampaignStatus(): CampaignStatus? {
        return try {
            val request = Request.Builder()
                .url(BASE_URL + CAMPAIGN_STATUS_ENDPOINT)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val root = JSONObject(body)
                val json = root.optJSONObject("data") ?: root
                CampaignStatus(
                    isRunning       = json.optBoolean("isRunning", false),
                    campaignId      = json.optString("campaignId"),
                    totalLeads      = json.optInt("totalLeads", 0),
                    processedLeads  = json.optInt("processedLeads", 0),
                    yesCount        = json.optInt("yesCount", 0),
                    noCount         = json.optInt("noCount", 0),
                    failedCalls     = json.optInt("failedCalls", 0),
                    progressPercent = json.optInt("progressPercent", 0),
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    // ── Campaign control ──────────────────────────────────────────────────────

    data class StartCampaignResult(
        val success: Boolean,
        val campaignId: String?,
        val totalLeads: Int,
        val message: String?,
        val errorMessage: String? = null,
    )

    /**
     * POST /api/adb/start — trigger ADB campaign on backend-node.
     * Backend reads Excel, builds queue, starts dialing via ai-python.
     * Returns immediately (campaign runs async on backend).
     */
    fun startCampaign(campaignName: String = "Android Campaign"): StartCampaignResult {
        LogStore.log("ApiClient", "Starting campaign at POST $BASE_URL/api/adb/start (name=$campaignName)")
        return try {
            val body = "{\"campaignName\":\"$campaignName\"}"
                .toRequestBody("application/json".toMediaTypeOrNull())
            val request = Request.Builder()
                .url("$BASE_URL/api/adb/start")
                .post(body)
                .addHeader("User-Agent", "Android-GSM-AI/3.0 (okhttp)")
                .build()
            LogStore.log("ApiClient", "Campaign request URL: $BASE_URL/api/adb/start")
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                LogStore.log("ApiClient", "Campaign response: HTTP ${response.code}")
                LogStore.log("ApiClient", "Campaign response body: ${bodyStr.take(300)}")
                if (!response.isSuccessful) {
                    val msg = "HTTP ${response.code}: $bodyStr"
                    LogStore.log("ApiClient", "Campaign start failed: $msg")
                    return StartCampaignResult(
                        success = false,
                        campaignId = null,
                        totalLeads = 0,
                        message = null,
                        errorMessage = msg,
                    )
                }
                val json = JSONObject(bodyStr)
                val data = json.optJSONObject("data") ?: json
                val result = StartCampaignResult(
                    success    = json.optBoolean("success", true),
                    campaignId = data.optString("campaignId").takeIf { it.isNotBlank() },
                    totalLeads = data.optInt("totalLeads", 0),
                    message    = data.optString("message").takeIf { it.isNotBlank() },
                )
                LogStore.log("ApiClient", "Campaign started: ${result.campaignId} (${result.totalLeads} leads)")
                result
            }
        } catch (ex: Exception) {
            val msg = "${ex.javaClass.simpleName}: ${ex.message}"
            LogStore.log("ApiClient", "Campaign start exception: $msg")
            LogStore.log("ApiClient", "NETWORK ERROR — cannot reach $BASE_URL/api/adb/start")
            StartCampaignResult(
                success      = false,
                campaignId   = null,
                totalLeads   = 0,
                message      = null,
                errorMessage = msg,
            )
        }
    }

    /**
     * POST /api/adb/stop — stop the running campaign after the current call.
     */
    fun stopCampaign(): Boolean {
        LogStore.log("ApiClient", "Stopping campaign at POST $BASE_URL/api/adb/stop")
        return try {
            val request = Request.Builder()
                .url("$BASE_URL/api/adb/stop")
                .post("{}".toRequestBody("application/json".toMediaTypeOrNull()))
                .addHeader("User-Agent", "Android-GSM-AI/3.0 (okhttp)")
                .build()
            client.newCall(request).execute().use { response ->
                LogStore.log("ApiClient", "Stop campaign response: HTTP ${response.code}")
                response.isSuccessful
            }
        } catch (ex: Exception) {
            LogStore.log("ApiClient", "Stop campaign exception: ${ex.javaClass.simpleName}: ${ex.message}")
            false
        }
    }

    // ── Data models ──────────────────────────────────────────────────────────

    data class IntentResponse(
        val intent: String?,
        val transcription: String?,
        val success: Boolean? = null,
        val db_id: String? = null,
    )

    data class CampaignStatus(
        val isRunning: Boolean,
        val campaignId: String?,
        val totalLeads: Int,
        val processedLeads: Int,
        val yesCount: Int,
        val noCount: Int,
        val failedCalls: Int,
        val progressPercent: Int,
    )
}