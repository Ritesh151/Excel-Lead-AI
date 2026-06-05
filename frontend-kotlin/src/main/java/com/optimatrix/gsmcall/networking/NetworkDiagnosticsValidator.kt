package com.optimatrix.gsmcall.networking

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.optimatrix.gsmcall.utils.LogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * NetworkDiagnosticsValidator — Real-time network validation
 *
 * Validates before automation starts:
 *   ✓ WiFi connectivity
 *   ✓ DNS resolution for backend host
 *   ✓ TCP socket connectivity to backend:port
 *   ✓ HTTP /health endpoint reachable
 *   ✓ WebSocket TCP reachable
 *
 * Provides exact failure reasons for debugging.
 */
class NetworkDiagnosticsValidator(
    private val context: Context,
    private val configManager: NetworkConfigManager
) {

    companion object {
        private const val TAG = "NetworkDiagnostics"
        private const val SOCKET_TIMEOUT_MS = 5000
    }

    data class DiagnosticsReport(
        val timestamp: String,
        val wifiConnected: Boolean,
        val dnsResolved: Boolean,
        val resolvedIp: String?,
        val tcpConnectable: Boolean,
        val httpReachable: Boolean,
        val wsReachable: Boolean,
        val isHealthy: Boolean,
        val issues: List<String>,
        val recommendations: List<String>,
    )

    suspend fun validate(): DiagnosticsReport = withContext(Dispatchers.IO) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date())
        val issues = mutableListOf<String>()
        val recommendations = mutableListOf<String>()

        LogStore.log(TAG, "")
        LogStore.log(TAG, "════════════════════════════════════════════════════════")
        LogStore.log(TAG, "NETWORK DIAGNOSTICS VALIDATION")
        LogStore.log(TAG, "════════════════════════════════════════════════════════")

        // 1. WiFi connectivity
        LogStore.log(TAG, "")
        LogStore.log(TAG, "[1/5] Checking WiFi connectivity...")
        val wifiConnected = checkWifiConnectivity()
        if (!wifiConnected) {
            issues.add("WiFi not connected")
            recommendations.add("Connect to same WiFi network as backend PC")
            LogStore.log(TAG, "  ✗ WiFi: NOT CONNECTED")
        } else {
            LogStore.log(TAG, "  ✓ WiFi: CONNECTED")
        }

        // 2. DNS resolution
        LogStore.log(TAG, "")
        LogStore.log(TAG, "[2/5] Checking DNS resolution for ${configManager.getBackendHost()}...")
        val (dnsResolved, resolvedIp) = checkDnsResolution(configManager.getBackendHost())
        if (!dnsResolved) {
            issues.add("Cannot resolve ${configManager.getBackendHost()}")
            recommendations.add("Check backend IP in settings")
            recommendations.add("Try pinging the IP from your PC")
            LogStore.log(TAG, "  ✗ DNS: FAILED")
        } else {
            LogStore.log(TAG, "  ✓ DNS: RESOLVED to $resolvedIp")
        }

        // 3. TCP socket connectivity
        LogStore.log(TAG, "")
        LogStore.log(TAG, "[3/5] Checking TCP socket connectivity...")
        val tcpConnectable = if (dnsResolved && resolvedIp != null) {
            checkTcpConnection(resolvedIp ?: configManager.getBackendHost(), configManager.getBackendPort())
        } else {
            false
        }
        if (!tcpConnectable) {
            issues.add("Cannot connect to ${configManager.getBackendHost()}:${configManager.getBackendPort()}")
            recommendations.add("Backend server may be offline")
            recommendations.add("Check backend is running: npm start")
            recommendations.add("Check firewall allows port ${configManager.getBackendPort()}")
            LogStore.log(TAG, "  ✗ TCP: NOT CONNECTABLE")
        } else {
            LogStore.log(TAG, "  ✓ TCP: CONNECTABLE")
        }

        // 4. HTTP health endpoint
        LogStore.log(TAG, "")
        LogStore.log(TAG, "[4/5] Checking HTTP /health endpoint...")
        val httpReachable = checkHttpHealth(configManager.getBackendHttpUrl())
        if (!httpReachable) {
            issues.add("HTTP /health endpoint not reachable")
            recommendations.add("Backend may be loading or crashed")
            recommendations.add("Check backend logs for errors")
            LogStore.log(TAG, "  ✗ HTTP: NOT REACHABLE")
        } else {
            LogStore.log(TAG, "  ✓ HTTP: REACHABLE")
        }

        // 5. WebSocket reachability
        LogStore.log(TAG, "")
        LogStore.log(TAG, "[5/5] Checking WebSocket reachability...")
        val wsReachable = checkWebSocketReachable(configManager.getBackendWebSocketUrl())
        if (!wsReachable) {
            issues.add("WebSocket not reachable")
            recommendations.add("Backend WebSocket server may not be listening")
            LogStore.log(TAG, "  ✗ WebSocket: NOT REACHABLE")
        } else {
            LogStore.log(TAG, "  ✓ WebSocket: REACHABLE")
        }

        // Final summary
        LogStore.log(TAG, "")
        val isHealthy = wifiConnected && dnsResolved && tcpConnectable && httpReachable && wsReachable
        
        if (isHealthy) {
            LogStore.log(TAG, "════════════════════════════════════════════════════════")
            LogStore.log(TAG, "✅ ALL CHECKS PASSED — READY TO START AUTOMATION")
            LogStore.log(TAG, "════════════════════════════════════════════════════════")
        } else {
            LogStore.log(TAG, "════════════════════════════════════════════════════════")
            LogStore.log(TAG, "❌ ISSUES DETECTED — CANNOT START AUTOMATION")
            LogStore.log(TAG, "════════════════════════════════════════════════════════")
            LogStore.log(TAG, "")
            LogStore.log(TAG, "ISSUES:")
            issues.forEach { LogStore.log(TAG, "  • $it") }
            LogStore.log(TAG, "")
            LogStore.log(TAG, "RECOMMENDATIONS:")
            recommendations.forEach { LogStore.log(TAG, "  • $it") }
        }
        LogStore.log(TAG, "")

        DiagnosticsReport(
            timestamp = timestamp,
            wifiConnected = wifiConnected,
            dnsResolved = dnsResolved,
            resolvedIp = resolvedIp,
            tcpConnectable = tcpConnectable,
            httpReachable = httpReachable,
            wsReachable = wsReachable,
            isHealthy = isHealthy,
            issues = issues,
            recommendations = recommendations,
        )
    }

    private fun checkWifiConnectivity(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            
            val activeNetwork = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
            
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } catch (e: Exception) {
            LogStore.log(TAG, "WiFi check error: ${e.message}")
            false
        }
    }

    private fun checkDnsResolution(hostname: String): Pair<Boolean, String?> {
        return try {
            val address = InetAddress.getByName(hostname)
            Pair(true, address.hostAddress)
        } catch (e: Exception) {
            LogStore.log(TAG, "DNS error: ${e.message}")
            Pair(false, null)
        }
    }

    private fun checkTcpConnection(host: String, port: Int): Boolean {
        return try {
            val socket = Socket()
            socket.connect(java.net.InetSocketAddress(host, port), SOCKET_TIMEOUT_MS)
            socket.close()
            true
        } catch (e: Exception) {
            LogStore.log(TAG, "TCP error: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun checkHttpHealth(baseUrl: String): Boolean {
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url("$baseUrl/health")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val success = response.isSuccessful
            response.close()
            success
        } catch (e: Exception) {
            LogStore.log(TAG, "HTTP error: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun checkWebSocketReachable(wsUrl: String): Boolean {
        return try {
            val uri = java.net.URI(wsUrl)
            val host = uri.host ?: return false
            val port = if (uri.port == -1) 80 else uri.port
            
            val socket = Socket()
            socket.connect(java.net.InetSocketAddress(host, port), SOCKET_TIMEOUT_MS)
            socket.close()
            true
        } catch (e: Exception) {
            LogStore.log(TAG, "WebSocket check error: ${e.message}")
            false
        }
    }
}
