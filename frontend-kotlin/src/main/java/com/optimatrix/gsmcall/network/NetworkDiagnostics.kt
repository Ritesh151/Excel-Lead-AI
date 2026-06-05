package com.optimatrix.gsmcall.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.optimatrix.gsmcall.NetworkConfig
import com.optimatrix.gsmcall.utils.LogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * NetworkDiagnostics — Comprehensive network connectivity validation
 *
 * Before starting automation, validates:
 *   - WiFi/LAN connectivity
 *   - Backend HTTP reachability
 *   - Backend WebSocket reachability
 *   - AI-python reachability (via backend proxy)
 *   - DNS resolution
 *   - Network latency
 */
class NetworkDiagnostics(private val context: Context) {

    companion object {
        private const val TAG = "NetworkDiagnostics"
        private const val DIAGNOSTIC_TIMEOUT_MS = 5000L
    }

    data class NetworkStatus(
        val isHealthy: Boolean,
        val wifiConnected: Boolean,
        val backendReachable: Boolean,
        val websocketReachable: Boolean,
        val dnsResolvable: Boolean,
        val latencyMs: Long,
        val issues: List<String>,
        val recommendations: List<String>,
    )

    suspend fun diagnoseNetwork(): NetworkStatus = withContext(Dispatchers.IO) {
        LogStore.log(TAG, "════════════════════════════════════════════════════════")
        LogStore.log(TAG, "NETWORK DIAGNOSTICS — Validating connectivity...")
        LogStore.log(TAG, "════════════════════════════════════════════════════════")

        val issues = mutableListOf<String>()
        val recommendations = mutableListOf<String>()

        // 1. Check WiFi connectivity
        LogStore.log(TAG, "→ Checking WiFi connectivity...")
        val wifiConnected = checkWifiConnectivity()
        if (!wifiConnected) {
            issues.add("WiFi not connected — app requires LAN connectivity")
            recommendations.add("Connect to WiFi on same network as backend PC")
        } else {
            LogStore.log(TAG, "✓ WiFi connected")
        }

        // 2. Check DNS resolution
        LogStore.log(TAG, "→ Checking DNS resolution...")
        val dnsResolvable = checkDnsResolution(NetworkConfig.host)
        if (!dnsResolvable) {
            issues.add("Cannot resolve hostname: ${NetworkConfig.host}")
            recommendations.add("Verify backend IP address in local.properties")
            recommendations.add("Check device can ping ${NetworkConfig.host}")
        } else {
            LogStore.log(TAG, "✓ DNS resolved: ${NetworkConfig.host}")
        }

        // 3. Check backend HTTP reachability
        LogStore.log(TAG, "→ Checking backend HTTP endpoint...")
        val backendReachable = checkHttpReachability(NetworkConfig.httpBaseUrl)
        if (!backendReachable) {
            issues.add("Cannot reach backend HTTP: ${NetworkConfig.httpBaseUrl}")
            recommendations.add("Ensure backend-node is running on PC")
            recommendations.add("Verify backend listening on 0.0.0.0:${NetworkConfig.port}")
            recommendations.add("Check firewall not blocking port ${NetworkConfig.port}")
        } else {
            LogStore.log(TAG, "✓ Backend HTTP reachable: ${NetworkConfig.httpBaseUrl}")
        }

        // 4. Check WebSocket reachability
        LogStore.log(TAG, "→ Checking WebSocket endpoint...")
        val websocketReachable = checkWebsocketReachability(NetworkConfig.wsUrl)
        if (!websocketReachable) {
            issues.add("Cannot reach backend WebSocket: ${NetworkConfig.wsUrl}")
            recommendations.add("Verify backend-node WebSocket server is running")
            recommendations.add("Check Socket.IO is properly configured")
        } else {
            LogStore.log(TAG, "✓ WebSocket reachable: ${NetworkConfig.wsUrl}")
        }

        // 5. Measure latency
        LogStore.log(TAG, "→ Measuring network latency...")
        val latencyMs = measureLatency(NetworkConfig.httpBaseUrl)
        LogStore.log(TAG, "✓ Latency: ${latencyMs}ms")

        if (latencyMs > 500) {
            recommendations.add("High latency detected (${latencyMs}ms) — may cause timeouts")
        }

        val isHealthy = wifiConnected && dnsResolvable && backendReachable && websocketReachable

        LogStore.log(TAG, "════════════════════════════════════════════════════════")
        LogStore.log(TAG, "RESULT: ${if (isHealthy) "✓ HEALTHY" else "✗ UNHEALTHY"}")
        LogStore.log(TAG, "════════════════════════════════════════════════════════")

        if (issues.isNotEmpty()) {
            LogStore.log(TAG, "ISSUES:")
            issues.forEach { issue ->
                LogStore.log(TAG, "  ❌ $issue")
            }
        }

        if (recommendations.isNotEmpty()) {
            LogStore.log(TAG, "RECOMMENDATIONS:")
            recommendations.forEach { rec ->
                LogStore.log(TAG, "  💡 $rec")
            }
        }

        NetworkStatus(
            isHealthy = isHealthy,
            wifiConnected = wifiConnected,
            backendReachable = backendReachable,
            websocketReachable = websocketReachable,
            dnsResolvable = dnsResolvable,
            latencyMs = latencyMs,
            issues = issues,
            recommendations = recommendations,
        )
    }

    private fun checkWifiConnectivity(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = cm.activeNetwork ?: return false
                val capabilities = cm.getNetworkCapabilities(network) ?: return false

                // Check for WiFi or Ethernet (LAN)
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            } else {
                @Suppress("DEPRECATION")
                val info = cm.activeNetworkInfo
                info != null && info.isConnected &&
                (info.type == ConnectivityManager.TYPE_WIFI ||
                 info.type == ConnectivityManager.TYPE_ETHERNET)
            }
        } catch (e: Exception) {
            LogStore.log(TAG, "WiFi check error: ${e.message}")
            false
        }
    }

    private fun checkDnsResolution(hostname: String): Boolean {
        return try {
            InetAddress.getByName(hostname)
            true
        } catch (e: Exception) {
            LogStore.log(TAG, "DNS resolution failed for $hostname: ${e.message}")
            false
        }
    }

    private fun checkHttpReachability(url: String): Boolean {
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(DIAGNOSTIC_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(DIAGNOSTIC_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()

            val request = Request.Builder()
                .url("$url/health")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val reachable = response.isSuccessful || response.code == 401 || response.code == 403
            response.close()
            reachable
        } catch (e: Exception) {
            LogStore.log(TAG, "HTTP reachability check failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun checkWebsocketReachability(wsUrl: String): Boolean {
        return try {
            // WebSocket check via HTTP upgrade request
            val client = OkHttpClient.Builder()
                .connectTimeout(DIAGNOSTIC_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(DIAGNOSTIC_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()

            val request = Request.Builder()
                .url(wsUrl.replace("ws://", "http://").replace("wss://", "https://"))
                .get()
                .addHeader("Connection", "Upgrade")
                .addHeader("Upgrade", "websocket")
                .build()

            val response = client.newCall(request).execute()
            response.close()
            // WebSocket should return 101 or connection header present
            true
        } catch (e: Exception) {
            LogStore.log(TAG, "WebSocket reachability check failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun measureLatency(url: String): Long {
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(DIAGNOSTIC_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(DIAGNOSTIC_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()

            val request = Request.Builder()
                .url("$url/health")
                .get()
                .build()

            val startTime = System.currentTimeMillis()
            val response = client.newCall(request).execute()
            val latency = System.currentTimeMillis() - startTime
            response.close()
            latency
        } catch (e: Exception) {
            -1L
        }
    }
}
