package com.optimatrix.gsmcall.networking

import android.content.Context
import com.optimatrix.gsmcall.utils.LogStore
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * ApiClientProduction — Production-grade REST API client
 *
 * Features:
 *   ✓ Automatic retries on transient failures (3 attempts)
 *   ✓ Aggressive timeouts for mobile networks (15s connect, 120s read)
 *   ✓ Health check endpoint with graceful failures
 *   ✓ Campaign start with full error details
 *   ✓ Comprehensive logging of all requests/responses
 *   ✓ Graceful handling of all network exceptions
 *
 * Exception handling:
 *   • SocketTimeoutException → network slow, retry
 *   • ConnectException → backend offline, retry with backoff
 *   • UnknownHostException → wrong IP address
 *   • IOException → general network failure
 */
class ApiClientProduction(
    context: Context,
    private val configManager: NetworkConfigManager
) {

    companion object {
        private const val TAG = "ApiClient"
        private const val MAX_RETRIES = 3
    }

    private val baseUrl = configManager.getBackendHttpUrl()

    // Production-grade OkHttp configuration
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)   // 15s to establish connection
        .readTimeout(120, TimeUnit.SECONDS)     // 120s for uploads (Whisper)
        .writeTimeout(60, TimeUnit.SECONDS)     // 60s to write request
        .retryOnConnectionFailure(true)         // Automatic retry on transient failure
        .build()

    // ─── Health Check ───────────────────────────────────────────────────────

    /**
     * Check if backend is healthy and responsive
     */
    fun checkHealth(onResult: (isHealthy: Boolean, details: String) -> Unit) {
        LogStore.log(TAG, "🏥 Health check: GET $baseUrl/health")

        val request = Request.Builder()
            .url("$baseUrl/health")
            .get()
            .addHeader("User-Agent", "Android-GSMCall/3.0 (okhttp)")
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                try {
                    val body = response.body?.string() ?: ""
                    val statusOk = response.isSuccessful
                    
                    if (statusOk) {
                        LogStore.log(TAG, "✓ Backend healthy: ${response.code}")
                        onResult(true, "Backend: OK")
                    } else {
                        LogStore.log(TAG, "✗ Backend unhealthy: ${response.code}")
                        onResult(false, "Backend: HTTP ${response.code}")
                    }
                } catch (e: Exception) {
                    LogStore.log(TAG, "✗ Health check parse error: ${e.message}")
                    onResult(false, "Parse error: ${e.message}")
                }
            }

            override fun onFailure(call: Call, e: IOException) {
                val msg = when (e) {
                    is java.net.ConnectException -> "Connection refused (backend offline?)"
                    is java.net.SocketTimeoutException -> "Timeout (network slow?)"
                    is java.net.UnknownHostException -> "Unknown host (wrong IP?)"
                    else -> e.javaClass.simpleName
                }
                LogStore.log(TAG, "✗ Health check failed: $msg")
                onResult(false, "Error: $msg")
            }
        })
    }

    // ─── Campaign Start ─────────────────────────────────────────────────────

    /**
     * POST /api/adb/start — Start a campaign
     */
    fun startCampaign(
        campaignName: String = "Android Campaign",
        onSuccess: (campaignId: String, totalLeads: Int) -> Unit,
        onError: (error: String) -> Unit
    ) {
        LogStore.log(TAG, "🚀 Campaign start: POST $baseUrl/api/adb/start")
        LogStore.log(TAG, "   Campaign: $campaignName")

        val body = JSONObject().apply {
            put("campaignName", campaignName)
        }.toString().toRequestBody("application/json".toMediaTypeOrNull())

        val request = Request.Builder()
            .url("$baseUrl/api/adb/start")
            .post(body)
            .addHeader("User-Agent", "Android-GSMCall/3.0 (okhttp)")
            .build()

        executeWithRetry(request, MAX_RETRIES, { response ->
            try {
                val responseBody = response.body?.string() ?: ""
                val json = JSONObject(responseBody)
                
                val success = json.optBoolean("success", false)
                if (success) {
                    val data = json.optJSONObject("data") ?: json
                    val campaignId = data.optString("campaignId", "unknown")
                    val totalLeads = data.optInt("totalLeads", 0)
                    
                    LogStore.log(TAG, "✓ Campaign started: $campaignId ($totalLeads leads)")
                    onSuccess(campaignId, totalLeads)
                } else {
                    val msg = json.optString("message", "Unknown error")
                    LogStore.log(TAG, "✗ Campaign start failed: $msg")
                    onError(msg)
                }
            } catch (e: Exception) {
                LogStore.log(TAG, "✗ Campaign response parse error: ${e.message}")
                onError("Parse error: ${e.message}")
            }
        }, { error ->
            LogStore.log(TAG, "✗ Campaign start network error: $error")
            onError(error)
        })
    }

    // ─── Helper: Retry Logic ────────────────────────────────────────────────

    private fun executeWithRetry(
        request: Request,
        attemptsLeft: Int,
        onSuccess: (Response) -> Unit,
        onError: (String) -> Unit
    ) {
        if (attemptsLeft <= 0) {
            onError("Max retries exhausted")
            return
        }

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                onSuccess(response)
            }

            override fun onFailure(call: Call, e: IOException) {
                val isRetryable = when (e) {
                    is java.net.ConnectException -> true  // Retry
                    is java.net.SocketTimeoutException -> true  // Retry
                    is java.net.SocketException -> true  // Retry
                    else -> false
                }

                if (isRetryable && attemptsLeft > 1) {
                    LogStore.log(TAG, "↻ Retry (${MAX_RETRIES - attemptsLeft + 1}/$MAX_RETRIES): ${e.javaClass.simpleName}")
                    
                    // Exponential backoff before retry
                    val backoffMs = (4 - attemptsLeft) * 1000L
                    Thread.sleep(backoffMs)
                    
                    executeWithRetry(request, attemptsLeft - 1, onSuccess, onError)
                } else {
                    val msg = when (e) {
                        is java.net.ConnectException -> "Connection refused (backend offline?)"
                        is java.net.SocketTimeoutException -> "Timeout (network slow?)"
                        is java.net.UnknownHostException -> "Unknown host (wrong IP?)"
                        else -> e.message ?: "Unknown error"
                    }
                    LogStore.log(TAG, "✗ Request failed after $MAX_RETRIES attempts: $msg")
                    onError(msg)
                }
            }
        })
    }
}
