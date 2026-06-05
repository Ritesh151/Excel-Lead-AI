package com.optimatrix.gsmcall.networking

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.optimatrix.gsmcall.utils.LogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * LanConnectivityValidator — Real-time validation of Android ↔ Backend connection
 *
 * Purpose:
 *   Before starting campaigns, validate that:
 *   1. WiFi is connected
 *   2. TCP socket can connect to backend
 *   3. HTTP /health endpoint responds
 *   4. WebSocket TCP is reachable
 *
 * Result: Clear diagnosis if connection fails
 */
class LanConnectivityValidator(private val context: Context) {

    companion object {
        private const val TAG = "LanConnectivityValidator"
        private const val TCP_TIMEOUT_MS = 5000
        private const val HTTP_TIMEOUT_MS = 10000
    }

    data class ValidationResult(
        val isHealthy: Boolean,
        val wifiConnected: Boolean,
        val backendTcpReachable: Boolean,
        val backendHttpHealthy: Boolean,
        val backendWebSocketReachable: Boolean,
        val issues: List<String> = emptyList(),
        val recommendations: List<String> = emptyList(),
        val diagnosticsText: String = ""
    )

    /**
     * Run complete connectivity validation (blocking — use in coroutine)
     */
    suspend fun validate(): ValidationResult = withContext(Dispatchers.IO) {
        val issues = mutableListOf<String>()
        val recommendations = mutableListOf<String>()
        val diagnostics = StringBuilder()

        diagnostics.append("═".repeat(70) + "\n")
        diagnostics.append("🔍 LAN CONNECTIVITY VALIDATION\n")
        diagnostics.append("═".repeat(70) + "\n")

        // Get backend config
        val config = NetworkConfigManager(context)
        val backendHost = config.getBackendHost()
        val backendPort = config.getBackendPort()

        diagnostics.append("\n📱 Configuration:\n")
        diagnostics.append("  Backend Host: $backendHost\n")
        diagnostics.append("  Backend Port: $backendPort\n")

        // Check 1: WiFi Connected
        diagnostics.append("\n1️⃣  WiFi Connectivity:\n")
        val wifiConnected = isWifiConnected()
        if (wifiConnected) {
            diagnostics.append("  ✅ WiFi connected\n")
        } else {
            diagnostics.append("  ❌ WiFi NOT connected\n")
            issues.add("WiFi is not connected")
            recommendations.add("Connect to WiFi: Settings → WiFi → Select network")
        }

        // Check 2: TCP Connection
        diagnostics.append("\n2️⃣  TCP Socket Connection (${backendHost}:${backendPort}):\n")
        val tcpReachable = isBackendTcpReachable(backendHost, backendPort)
        if (tcpReachable) {
            diagnostics.append("  ✅ TCP connection successful\n")
        } else {
            diagnostics.append("  ❌ TCP connection FAILED\n")
            issues.add("Cannot establish TCP connection to backend")
            recommendations.add("1. Check backend is running: npm start")
            recommendations.add("2. Verify IP address is correct: $backendHost")
            recommendations.add("3. Check firewall allows port $backendPort")
            recommendations.add("4. Verify phone and PC on same WiFi")
        }

        // Check 3: HTTP Health Endpoint
        diagnostics.append("\n3️⃣  HTTP /health Endpoint:\n")
        val httpHealthy = if (tcpReachable) isBackendHttpHealthy(backendHost, backendPort) else false
        if (httpHealthy) {
            diagnostics.append("  ✅ HTTP /health responding\n")
        } else {
            diagnostics.append("  ❌ HTTP /health NOT responding\n")
            if (tcpReachable) {
                issues.add("Backend HTTP /health endpoint not responding")
                recommendations.add("Backend may be stuck or crashed")
                recommendations.add("Restart backend: Kill process and npm start")
            }
        }

        // Check 4: WebSocket TCP
        diagnostics.append("\n4️⃣  WebSocket TCP Reachability:\n")
        val wsReachable = if (tcpReachable) isBackendTcpReachable(backendHost, backendPort) else false
        if (wsReachable) {
            diagnostics.append("  ✅ WebSocket TCP reachable\n")
        } else {
            diagnostics.append("  ❌ WebSocket TCP NOT reachable\n")
            issues.add("WebSocket TCP connection not possible")
            recommendations.add("Check same root cause as TCP connection")
        }

        // Summary
        diagnostics.append("\n" + "═".repeat(70) + "\n")
        val allHealthy = wifiConnected && tcpReachable && httpHealthy && wsReachable
        if (allHealthy) {
            diagnostics.append("✅ ALL CHECKS PASSED — Ready to start campaign\n")
        } else {
            diagnostics.append("❌ ISSUES DETECTED — Fix above and retry\n")
        }
        diagnostics.append("═".repeat(70) + "\n")

        LogStore.log(TAG, diagnostics.toString())

        return@withContext ValidationResult(
            isHealthy = allHealthy,
            wifiConnected = wifiConnected,
            backendTcpReachable = tcpReachable,
            backendHttpHealthy = httpHealthy,
            backendWebSocketReachable = wsReachable,
            issues = issues,
            recommendations = recommendations,
            diagnosticsText = diagnostics.toString()
        )
    }

    /**
     * Check if WiFi is connected
     */
    private fun isWifiConnected(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * Test TCP socket connection to backend
     */
    private fun isBackendTcpReachable(host: String, port: Int): Boolean {
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), TCP_TIMEOUT_MS)
            socket.close()
            true
        } catch (e: Exception) {
            LogStore.log(TAG, "TCP connection failed: ${e.message}")
            false
        }
    }

    /**
     * Test HTTP /health endpoint
     */
    private fun isBackendHttpHealthy(host: String, port: Int): Boolean {
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), HTTP_TIMEOUT_MS)

            // Send HTTP GET request
            val out = socket.getOutputStream()
            val request = "GET /health HTTP/1.1\r\n" +
                    "Host: $host:$port\r\n" +
                    "Connection: close\r\n" +
                    "\r\n"
            out.write(request.toByteArray())
            out.flush()

            // Read response
            val input = BufferedReader(InputStreamReader(socket.getInputStream()))
            val statusLine = input.readLine() ?: return false

            socket.close()

            // Check for 200 OK
            statusLine.contains("200")
        } catch (e: Exception) {
            LogStore.log(TAG, "HTTP health check failed: ${e.message}")
            false
        }
    }
}
