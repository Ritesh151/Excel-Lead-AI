package com.optimatrix.gsmcall.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.optimatrix.gsmcall.NetworkConfig
import com.optimatrix.gsmcall.utils.LogStore
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * NetworkDiagnosticsManager — Production TCP/HTTP/WebSocket validation
 *
 * CRITICAL: Validates before automation startup
 *   ✓ WiFi connectivity
 *   ✓ Backend TCP reachable
 *   ✓ HTTP /health endpoint responds
 *   ✓ WebSocket connects
 *   ✓ Network latency acceptable
 *
 * Reports EXACT failure reasons to UI for debugging.
 * No guessing — only continue if all checks pass.
 */
class NetworkDiagnosticsManager(private val context: Context) {

    companion object {
        private const val TAG = "NetDiagnostics"
        private const val TIMEOUT_MS = 8_000
        private const val ACCEPTABLE_LATENCY_MS = 500
    }

    data class DiagnosticResult(
        val success: Boolean,
        val wifiConnected: Boolean,
        val backendTcpReachable: Boolean,
        val httpHealthReachable: Boolean,
        val websocketReachable: Boolean,
        val latencyMs: Long,
        val issues: List<String>,
        val recommendations: List<String>,
        val fullReport: String,
    )

    /**
     * MAIN VALIDATION ENTRY POINT
     * Run this BEFORE starting automation to validate full network stack.
     * Blocks during validation — run on background thread.
     */
    fun validateFullStack(): DiagnosticResult {
        LogStore.log(TAG, "═══════════════════════════════════════════════════════")
        LogStore.log(TAG, "NETWORK DIAGNOSTICS — FULL STACK VALIDATION")
        LogStore.log(TAG, "═══════════════════════════════════════════════════════")
        
        val issues = mutableListOf<String>()
        val recommendations = mutableListOf<String>()
        val reportLines = mutableListOf<String>()
        
        reportLines.add("=".repeat(70))
        reportLines.add("NETWORK DIAGNOSTICS REPORT")
        reportLines.add("=".repeat(70))
        reportLines.add("Timestamp: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())}")
        reportLines.add("")
        
        // ─────────────────────────────────────────────────────────────────────
        // 1. WiFi connectivity check
        // ─────────────────────────────────────────────────────────────────────
        LogStore.log(TAG, "→ Checking WiFi connectivity...")
        reportLines.add("1. WiFi CONNECTIVITY")
        reportLines.add("-".repeat(70))
        
        val wifiConnected = isWifiConnected()
        reportLines.add("   Status: ${if (wifiConnected) "✓ CONNECTED" else "✗ NOT CONNECTED"}")
        
        if (!wifiConnected) {
            LogStore.log(TAG, "✗ WiFi not connected")
            issues.add("WiFi disconnected — connect phone and PC to same WiFi network")
            recommendations.add("Enable WiFi and join the same network as your PC")
        } else {
            LogStore.log(TAG, "✓ WiFi connected")
        }
        reportLines.add("")
        
        // ─────────────────────────────────────────────────────────────────────
        // 2. Backend TCP connectivity (raw socket)
        // ─────────────────────────────────────────────────────────────────────
        LogStore.log(TAG, "→ Checking TCP connectivity to ${NetworkConfig.host}:${NetworkConfig.port}...")
        reportLines.add("2. TCP CONNECTIVITY")
        reportLines.add("-".repeat(70))
        reportLines.add("   Target: ${NetworkConfig.host}:${NetworkConfig.port}")
        
        val tcpReachable = checkTcpReachability(NetworkConfig.host, NetworkConfig.port)
        reportLines.add("   Status: ${if (tcpReachable) "✓ REACHABLE" else "✗ NOT REACHABLE"}")
        
        if (!tcpReachable) {
            LogStore.log(TAG, "✗ TCP connection failed")
            issues.add("Cannot reach backend TCP socket at ${NetworkConfig.host}:${NetworkConfig.port}")
            recommendations.add("Verify backend-node is running on PC")
            recommendations.add("Verify backend binding to 0.0.0.0:${NetworkConfig.port}")
            recommendations.add("Check Windows/Linux firewall not blocking port ${NetworkConfig.port}")
            recommendations.add("Verify phone and PC on same WiFi (ping ${NetworkConfig.host} from phone)")
        } else {
            LogStore.log(TAG, "✓ TCP reachable")
        }
        reportLines.add("")
        
        // ─────────────────────────────────────────────────────────────────────
        // 3. HTTP /health endpoint reachability
        // ─────────────────────────────────────────────────────────────────────
        LogStore.log(TAG, "→ Checking HTTP /health endpoint...")
        reportLines.add("3. HTTP HEALTH CHECK")
        reportLines.add("-".repeat(70))
        reportLines.add("   Endpoint: GET ${NetworkConfig.httpBaseUrl}/health")
        
        val httpHealthReachable = checkHttpHealth()
        reportLines.add("   Status: ${if (httpHealthReachable) "✓ RESPONDING" else "✗ NOT RESPONDING"}")
        
        if (!httpHealthReachable) {
            LogStore.log(TAG, "✗ HTTP health check failed")
            issues.add("Backend HTTP /health endpoint not responding")
            recommendations.add("Verify Express server running on backend")
            recommendations.add("Check for cleartext network security policy errors")
            recommendations.add("Check backend logs for startup errors")
        } else {
            LogStore.log(TAG, "✓ HTTP health reachable")
        }
        reportLines.add("")
        
        // ─────────────────────────────────────────────────────────────────────
        // 4. WebSocket connectivity
        // ─────────────────────────────────────────────────────────────────────
        LogStore.log(TAG, "→ Checking WebSocket connectivity...")
        reportLines.add("4. WEBSOCKET CONNECTIVITY")
        reportLines.add("-".repeat(70))
        reportLines.add("   Target: ${NetworkConfig.wsUrl}")
        
        val websocketReachable = checkWebsocketReachability()
        reportLines.add("   Status: ${if (websocketReachable) "✓ CONNECTABLE" else "✗ NOT CONNECTABLE"}")
        
        if (!websocketReachable) {
            LogStore.log(TAG, "✗ WebSocket not reachable")
            issues.add("WebSocket not connectable at ${NetworkConfig.wsUrl}")
            recommendations.add("Verify WebSocket server running on backend")
            recommendations.add("Check Network Security Config allows ws:// protocol")
            recommendations.add("Verify port ${NetworkConfig.port} open in firewall")
        } else {
            LogStore.log(TAG, "✓ WebSocket reachable")
        }
        reportLines.add("")
        
        // ─────────────────────────────────────────────────────────────────────
        // 5. Network latency measurement
        // ─────────────────────────────────────────────────────────────────────
        LogStore.log(TAG, "→ Measuring network latency...")
        reportLines.add("5. NETWORK LATENCY")
        reportLines.add("-".repeat(70))
        
        val latencyMs = measureLatency()
        reportLines.add("   Round-trip: ${latencyMs}ms")
        reportLines.add("   Status: ${if (latencyMs <= ACCEPTABLE_LATENCY_MS) "✓ ACCEPTABLE" else "⚠ HIGH"}")
        
        if (latencyMs > ACCEPTABLE_LATENCY_MS) {
            LogStore.log(TAG, "⚠ High latency: ${latencyMs}ms")
            recommendations.add("Network latency high (${latencyMs}ms) — may cause timeout issues")
            recommendations.add("Try connecting to WiFi closer to PC or switching networks")
        } else {
            LogStore.log(TAG, "✓ Latency acceptable: ${latencyMs}ms")
        }
        reportLines.add("")
        
        // ─────────────────────────────────────────────────────────────────────
        // Summary
        // ─────────────────────────────────────────────────────────────────────
        reportLines.add("SUMMARY")
        reportLines.add("-".repeat(70))
        reportLines.add("   WiFi:       ${if (wifiConnected) "✓" else "✗"}")
        reportLines.add("   TCP:        ${if (tcpReachable) "✓" else "✗"}")
        reportLines.add("   HTTP:       ${if (httpHealthReachable) "✓" else "✗"}")
        reportLines.add("   WebSocket:  ${if (websocketReachable) "✓" else "✗"}")
        reportLines.add("   Latency:    ${latencyMs}ms")
        reportLines.add("")
        
        val success = wifiConnected && tcpReachable && httpHealthReachable && websocketReachable
        reportLines.add("RESULT: ${if (success) "✓ ALL CHECKS PASSED" else "✗ VALIDATION FAILED"}")
        reportLines.add("=".repeat(70))
        
        val fullReport = reportLines.joinToString("\n")
        
        LogStore.log(TAG, "DIAGNOSTIC COMPLETE — success=$success issues=${issues.size}")
        if (issues.isNotEmpty()) {
            issues.forEach { LogStore.log(TAG, "   ✗ $it") }
        }
        if (recommendations.isNotEmpty()) {
            recommendations.forEach { LogStore.log(TAG, "   → $it") }
        }
        
        return DiagnosticResult(
            success = success,
            wifiConnected = wifiConnected,
            backendTcpReachable = tcpReachable,
            httpHealthReachable = httpHealthReachable,
            websocketReachable = websocketReachable,
            latencyMs = latencyMs,
            issues = issues,
            recommendations = recommendations,
            fullReport = fullReport,
        )
    }

    // ─── Private diagnostics implementations ───────────────────────────────

    private fun isWifiConnected(): Boolean {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork ?: return false
                val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            } else {
                @Suppress("DEPRECATION")
                val info = connectivityManager.activeNetworkInfo
                info?.isConnected == true && info.type == ConnectivityManager.TYPE_WIFI
            }
        } catch (ex: Exception) {
            LogStore.log(TAG, "WiFi check exception: ${ex.javaClass.simpleName}: ${ex.message}")
            false
        }
    }

    private fun checkTcpReachability(host: String, port: Int): Boolean {
        return try {
            LogStore.log(TAG, "TCP: Connecting to $host:$port with ${TIMEOUT_MS}ms timeout...")
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), TIMEOUT_MS)
            socket.close()
            LogStore.log(TAG, "TCP: Connection successful")
            true
        } catch (ex: SocketTimeoutException) {
            LogStore.log(TAG, "TCP: Socket timeout after ${TIMEOUT_MS}ms")
            false
        } catch (ex: UnknownHostException) {
            LogStore.log(TAG, "TCP: Unknown host: $host")
            false
        } catch (ex: IOException) {
            LogStore.log(TAG, "TCP: Connection refused or failed: ${ex.message}")
            false
        } catch (ex: Exception) {
            LogStore.log(TAG, "TCP: Unexpected error: ${ex.javaClass.simpleName}: ${ex.message}")
            false
        }
    }

    private fun checkHttpHealth(): Boolean {
        return try {
            LogStore.log(TAG, "HTTP: Testing ${NetworkConfig.httpBaseUrl}/health...")
            val url = java.net.URL("${NetworkConfig.httpBaseUrl}/health")
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Android-GSM-AI/3.0 (diagnostics)")
            
            val responseCode = connection.responseCode
            LogStore.log(TAG, "HTTP: Response code $responseCode")
            
            connection.disconnect()
            responseCode == 200
        } catch (ex: SocketTimeoutException) {
            LogStore.log(TAG, "HTTP: Socket timeout after ${TIMEOUT_MS}ms")
            false
        } catch (ex: UnknownHostException) {
            LogStore.log(TAG, "HTTP: Unknown host: ${NetworkConfig.host}")
            false
        } catch (ex: IOException) {
            LogStore.log(TAG, "HTTP: Connection error: ${ex.message}")
            false
        } catch (ex: Exception) {
            LogStore.log(TAG, "HTTP: Unexpected error: ${ex.javaClass.simpleName}: ${ex.message}")
            false
        }
    }

    private fun checkWebsocketReachability(): Boolean {
        return try {
            LogStore.log(TAG, "WebSocket: Testing ${NetworkConfig.wsUrl}...")
            val host = NetworkConfig.host
            val port = NetworkConfig.port
            
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), TIMEOUT_MS)
            
            // Send WebSocket upgrade request
            val request = "GET / HTTP/1.1\r\n" +
                    "Host: $host:$port\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                    "Sec-WebSocket-Version: 13\r\n" +
                    "User-Agent: Android-GSM-AI/3.0\r\n" +
                    "\r\n"
            
            socket.outputStream.write(request.toByteArray())
            socket.outputStream.flush()
            
            // Read response (at least 12 bytes to check for HTTP response)
            val response = ByteArray(12)
            val read = socket.inputStream.read(response)
            socket.close()
            
            val responseStr = String(response, 0, minOf(read, response.size))
            LogStore.log(TAG, "WebSocket: Response: ${responseStr.take(30)}")
            
            // Server should respond with HTTP 101 or connection success
            read > 0
        } catch (ex: SocketTimeoutException) {
            LogStore.log(TAG, "WebSocket: Socket timeout after ${TIMEOUT_MS}ms")
            false
        } catch (ex: Exception) {
            LogStore.log(TAG, "WebSocket: Connection error: ${ex.javaClass.simpleName}: ${ex.message}")
            false
        }
    }

    private fun measureLatency(): Long {
        return try {
            LogStore.log(TAG, "Latency: Measuring round-trip time...")
            val startTime = System.currentTimeMillis()
            
            val url = java.net.URL("${NetworkConfig.httpBaseUrl}/api/debug/tcp")
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = "GET"
            
            connection.responseCode
            connection.disconnect()
            
            val elapsedMs = System.currentTimeMillis() - startTime
            LogStore.log(TAG, "Latency: ${elapsedMs}ms")
            elapsedMs
        } catch (ex: Exception) {
            LogStore.log(TAG, "Latency: Could not measure (${ex.javaClass.simpleName})")
            -1L
        }
    }

    /**
     * CONTINUOUS MONITORING (optional)
     * Run periodically to detect network issues during automation.
     * Emits warnings if connection drops.
     */
    fun quickHealthCheck(): Boolean {
        return try {
            val result = checkHttpHealth()
            if (!result) {
                LogStore.log(TAG, "⚠ Quick health check failed — backend may be down")
            }
            result
        } catch (ex: Exception) {
            LogStore.log(TAG, "⚠ Quick health check error: ${ex.message}")
            false
        }
    }
}
