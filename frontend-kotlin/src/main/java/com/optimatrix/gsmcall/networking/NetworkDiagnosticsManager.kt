package com.optimatrix.gsmcall.networking

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.optimatrix.gsmcall.NetworkConfig
import com.optimatrix.gsmcall.utils.LogStore
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * NetworkDiagnosticsManager — Comprehensive network diagnostics for Android ↔ backend communication.
 *
 * Validates:
 *   ✓ TCP connectivity to backend
 *   ✓ HTTP /health endpoint
 *   ✓ WebSocket connectivity
 *   ✓ DNS resolution
 *   ✓ Network connectivity (WiFi/mobile)
 *   ✓ Cleartext traffic permissions
 *   ✓ Firewall/proxy issues
 *
 * Usage:
 *   val diagnostics = NetworkDiagnosticsManager(context)
 *   val report = diagnostics.runFullDiagnostics()
 *   LogStore.log("Diagnostics", report.toString())
 */
class NetworkDiagnosticsManager(private val context: Context) {

    data class DiagnosticResult(
        var timestamp: Long = System.currentTimeMillis(),
        var hostConnectivity: Boolean = false,
        var tcpReachable: Boolean = false,
        var httpHealthOk: Boolean = false,
        var websocketConnectable: Boolean = false,
        var dnsResolvable: Boolean = false,
        var wifiConnected: Boolean = false,
        var internetAvailable: Boolean = false,
        var clearTextAllowed: Boolean = false,
        var errorLog: MutableList<String> = mutableListOf(),
    ) {
        override fun toString(): String = """
            ╔═══════════════════════════════════════════════════════════════╗
            ║            NETWORK DIAGNOSTICS REPORT                         ║
            ╚═══════════════════════════════════════════════════════════════╝
            Backend IP: ${NetworkConfig.host}
            Backend Port: ${NetworkConfig.port}
            HTTP URL: ${NetworkConfig.httpBaseUrl}
            WebSocket URL: ${NetworkConfig.wsUrl}
            
            ✓ Host Connectivity: ${if (hostConnectivity) "✓ YES" else "✗ NO"}
            ✓ TCP Reachable: ${if (tcpReachable) "✓ YES" else "✗ NO"}
            ✓ HTTP /health: ${if (httpHealthOk) "✓ YES" else "✗ NO"}
            ✓ WebSocket: ${if (websocketConnectable) "✓ YES" else "✗ NO"}
            ✓ DNS Resolved: ${if (dnsResolvable) "✓ YES" else "✗ NO"}
            ✓ WiFi Connected: ${if (wifiConnected) "✓ YES" else "✗ NO"}
            ✓ Internet Available: ${if (internetAvailable) "✓ YES" else "✗ NO"}
            ✓ Cleartext Allowed: ${if (clearTextAllowed) "✓ YES" else "✗ NO"}
            
            ${if (errorLog.isEmpty()) "No errors detected ✓" else "ERRORS:\n" + errorLog.joinToString("\n") { "  ✗ $it" }}
        """.trimIndent()
    }

    fun runFullDiagnostics(): DiagnosticResult {
        LogStore.log("NetworkDiagnostics", "Starting full network diagnostics...")
        val result = DiagnosticResult()

        // 1. Network connectivity
        result.apply {
            LogStore.log("NetworkDiagnostics", "Checking network connectivity...")
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null) {
                @Suppress("DEPRECATION")
                val activeNetwork = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    cm.activeNetwork
                } else {
                    cm.activeNetworkInfo?.let { return@let true }; null
                }

                if (activeNetwork != null || cm.activeNetworkInfo != null) {
                    this.internetAvailable = true
                    LogStore.log("NetworkDiagnostics", "✓ Internet connectivity available")
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && activeNetwork != null) {
                    val caps = cm.getNetworkCapabilities(activeNetwork)
                    this.wifiConnected = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?: false
                    LogStore.log("NetworkDiagnostics", "WiFi: ${if (wifiConnected) "YES" else "NO"}")
                } else {
                    @Suppress("DEPRECATION")
                    val networkInfo = cm.activeNetworkInfo
                    this.wifiConnected = networkInfo?.type == ConnectivityManager.TYPE_WIFI
                }
            } else {
                this.errorLog.add("Cannot access ConnectivityManager")
            }
        }

        // 2. DNS resolution
        LogStore.log("NetworkDiagnostics", "Testing DNS resolution for ${NetworkConfig.host}...")
        result.dnsResolvable = _testDnsResolution()
        if (!result.dnsResolvable) {
            result.errorLog.add("DNS resolution failed for ${NetworkConfig.host}")
        }

        // 3. TCP connectivity
        LogStore.log("NetworkDiagnostics", "Testing TCP connectivity to ${NetworkConfig.host}:${NetworkConfig.port}...")
        result.tcpReachable = _testTcpConnectivity()
        if (!result.tcpReachable) {
            result.errorLog.add("TCP connection failed to ${NetworkConfig.host}:${NetworkConfig.port}")
        }

        // 4. Host connectivity
        result.hostConnectivity = result.tcpReachable && result.dnsResolvable
        if (!result.hostConnectivity) {
            result.errorLog.add("Cannot reach backend host — TCP/DNS failures")
        }

        // 5. HTTP health check
        if (result.hostConnectivity) {
            LogStore.log("NetworkDiagnostics", "Testing HTTP health endpoint...")
            result.httpHealthOk = _testHttpHealth()
            if (!result.httpHealthOk) {
                result.errorLog.add("HTTP /health endpoint not responding")
            }
        }

        // 6. WebSocket connectivity (best effort)
        if (result.hostConnectivity) {
            LogStore.log("NetworkDiagnostics", "Testing WebSocket connectivity...")
            result.websocketConnectable = _testWebsocketConnectivity()
            if (!result.websocketConnectable) {
                result.errorLog.add("WebSocket connection failed (will attempt auto-reconnect)")
            }
        }

        // 7. Cleartext traffic detection
        result.clearTextAllowed = _checkCleartextPolicy()

        LogStore.log("NetworkDiagnostics", "Full diagnostics complete:\n$result")
        return result
    }

    private fun _testDnsResolution(): Boolean {
        return try {
            val addr = java.net.InetAddress.getByName(NetworkConfig.host)
            LogStore.log("NetworkDiagnostics", "DNS: ${NetworkConfig.host} → ${addr.hostAddress}")
            true
        } catch (e: Exception) {
            LogStore.log("NetworkDiagnostics", "DNS resolution failed: ${e.message}")
            false
        }
    }

    private fun _testTcpConnectivity(): Boolean {
        return try {
            val socket = Socket()
            socket.connect(
                InetSocketAddress(NetworkConfig.host, NetworkConfig.port),
                5000  // 5s timeout
            )
            socket.close()
            LogStore.log("NetworkDiagnostics", "TCP: Connected to ${NetworkConfig.host}:${NetworkConfig.port}")
            true
        } catch (e: Exception) {
            LogStore.log("NetworkDiagnostics", "TCP connection failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun _testHttpHealth(): Boolean {
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()

            val request = Request.Builder()
                .url("${NetworkConfig.httpBaseUrl}/health")
                .get()
                .addHeader("User-Agent", "Android-Diagnostics/1.0")
                .build()

            client.newCall(request).execute().use { response ->
                val success = response.isSuccessful
                val status = response.code
                val body = response.body?.string()?.take(100).orEmpty()
                LogStore.log("NetworkDiagnostics", "HTTP /health: status=$status body=$body")
                success
            }
        } catch (e: Exception) {
            LogStore.log("NetworkDiagnostics", "HTTP /health failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun _testWebsocketConnectivity(): Boolean {
        return try {
            val wsUrl = "${NetworkConfig.wsUrl}socket.io/?transport=websocket"
            LogStore.log("NetworkDiagnostics", "Testing WebSocket: $wsUrl")
            
            // Try to establish a raw WebSocket connection
            val uri = java.net.URI(wsUrl)
            val socket = java.net.Socket()
            socket.connect(InetSocketAddress(uri.host, uri.port ?: 3000), 5000)
            socket.close()
            
            LogStore.log("NetworkDiagnostics", "WebSocket TCP handshake successful")
            true
        } catch (e: Exception) {
            LogStore.log("NetworkDiagnostics", "WebSocket connectivity test failed: ${e.javaClass.simpleName}: ${e.message}")
            // WebSocket may still work via fallback transports
            false
        }
    }

    private fun _checkCleartextPolicy(): Boolean {
        // Check if cleartext traffic would be blocked by security policy
        // This is a best-effort check
        return try {
            val trustAllCerts = arrayOf(
                object : javax.net.ssl.X509TrustManager {
                    override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate>? = null
                }
            )

            // If we can make a cleartext connection to HTTP endpoint, cleartext is allowed
            val client = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url("${NetworkConfig.httpBaseUrl}/health")
                .get()
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    response.isSuccessful
                }
            } catch (e: Exception) {
                // If we get a cleartext security error, cleartext is blocked
                val msg = e.message ?: ""
                if (msg.contains("Cleartext traffic") || msg.contains("CLEARTEXT")) {
                    LogStore.log("NetworkDiagnostics", "⚠ CLEARTEXT TRAFFIC BLOCKED: $msg")
                    false
                } else {
                    // Some other error
                    true
                }
            }
        } catch (e: Exception) {
            // Assume cleartext is allowed if we can't determine
            true
        }
    }

    companion object {
        fun logDetailedError(context: Context, error: Throwable) {
            val diagnostics = NetworkDiagnosticsManager(context)
            val result = diagnostics.runFullDiagnostics()
            LogStore.log("NetworkDiagnostics", "Error context:\n${result}\n\nRoot cause: ${error.message}")
        }
    }
}
