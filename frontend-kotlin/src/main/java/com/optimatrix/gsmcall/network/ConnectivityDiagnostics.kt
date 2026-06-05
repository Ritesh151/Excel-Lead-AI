package com.optimatrix.gsmcall.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.optimatrix.gsmcall.BuildConfig
import com.optimatrix.gsmcall.NetworkConfig
import com.optimatrix.gsmcall.utils.LogStore
import java.net.InetAddress
import java.net.Socket

/**
 * ConnectivityDiagnostics — debug network connectivity issues.
 *
 * Features:
 *   - Check Android network connectivity (WiFi/cellular)
 *   - Validate DNS resolution for backend host
 *   - Test raw socket connection to backend:port
 *   - Provide human-readable diagnostics
 *
 * Usage:
 *   val diag = ConnectivityDiagnostics(context)
 *   val report = diag.generateReport()
 *   LogStore.log("Diagnostics", report)
 */
class ConnectivityDiagnostics(private val context: Context) {

    data class DiagnosticsReport(
        val timestamp: String,
        val backend: String,
        val backendHost: String,
        val backendPort: Int,
        val androidNetworkConnected: Boolean,
        val networkType: String,
        val dnsResolved: Boolean,
        val resolvedIp: String?,
        val tcpConnectable: Boolean,
        val httpReachable: Boolean,
        val wsReachable: Boolean,
        val issues: List<String>,
        val summary: String,
    )

    /**
     * Generate a complete connectivity diagnostics report.
     */
    fun generateReport(): DiagnosticsReport {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date())
        val backend = NetworkConfig.httpBaseUrl
        val backendHost = NetworkConfig.host
        val backendPort = NetworkConfig.port
        val issues = mutableListOf<String>()

        // Check Android network connectivity
        val (androidConnected, networkType) = checkAndroidNetwork()
        if (!androidConnected) issues.add("Android: No network connection")

        // Try DNS resolution
        val (dnsResolved, resolvedIp) = checkDnsResolution(backendHost)
        if (!dnsResolved) issues.add("DNS: Cannot resolve $backendHost")

        // Try TCP socket connection
        val tcpConnectable = if (dnsResolved && resolvedIp != null) {
            checkTcpConnection(resolvedIp ?: backendHost, backendPort)
        } else {
            false
        }
        if (!tcpConnectable) issues.add("TCP: Cannot connect to $backendHost:$backendPort")

        // Try HTTP request
        val httpReachable = checkHttpReachability(backend)
        if (!httpReachable) issues.add("HTTP: Cannot reach $backend/health")

        // Try WebSocket connection
        val wsReachable = checkWebSocketReachability(NetworkConfig.wsUrl)
        if (!wsReachable) issues.add("WebSocket: Cannot reach ${NetworkConfig.wsUrl}")

        val summary = when {
            issues.isEmpty() -> "✓ All connectivity checks passed"
            issues.size == 1 -> "⚠ ${issues[0]}"
            else -> "✗ ${issues.size} issues detected"
        }

        return DiagnosticsReport(
            timestamp          = timestamp,
            backend            = backend,
            backendHost        = backendHost,
            backendPort        = backendPort,
            androidNetworkConnected = androidConnected,
            networkType        = networkType,
            dnsResolved        = dnsResolved,
            resolvedIp         = resolvedIp,
            tcpConnectable     = tcpConnectable,
            httpReachable      = httpReachable,
            wsReachable        = wsReachable,
            issues             = issues,
            summary            = summary,
        )
    }

    /**
     * Check if Android device has network connectivity.
     */
    private fun checkAndroidNetwork(): Pair<Boolean, String> {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager? ?: return Pair(false, "unknown")

        val activeNetwork = connectivityManager.activeNetwork ?: return Pair(false, "none")
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return Pair(false, "unknown")

        val networkType = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Other"
        }

        return Pair(true, networkType)
    }

    /**
     * Check if backend host resolves via DNS.
     */
    private fun checkDnsResolution(host: String): Pair<Boolean, String?> {
        return try {
            val address = InetAddress.getByName(host)
            Pair(true, address.hostAddress)
        } catch (ex: Exception) {
            LogStore.log("Diagnostics", "DNS resolution failed for $host: ${ex.message}")
            Pair(false, null)
        }
    }

    /**
     * Check if TCP socket can connect to backend:port.
     */
    private fun checkTcpConnection(host: String, port: Int): Boolean {
        return try {
            val socket = Socket()
            socket.connect(java.net.InetSocketAddress(host, port), 5000) // 5s timeout
            socket.close()
            true
        } catch (ex: Exception) {
            LogStore.log("Diagnostics", "TCP connection failed to $host:$port: ${ex.javaClass.simpleName}")
            false
        }
    }

    /**
     * Check if HTTP health endpoint is reachable.
     */
    private fun checkHttpReachability(baseUrl: String): Boolean {
        return try {
            val url = java.net.URL("$baseUrl/health")
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "GET"
            val code = connection.responseCode
            connection.disconnect()
            code in 200..299
        } catch (ex: Exception) {
            LogStore.log("Diagnostics", "HTTP health check failed: ${ex.javaClass.simpleName}")
            false
        }
    }

    /**
     * Check if WebSocket is reachable (basic TCP check since WS requires upgrade).
     */
    private fun checkWebSocketReachability(wsUrl: String): Boolean {
        return try {
            // Extract host and port from ws://host:port/
            val uri = java.net.URI(wsUrl)
            val host = uri.host ?: return false
            val port = if (uri.port == -1) 80 else uri.port
            checkTcpConnection(host, port)
        } catch (ex: Exception) {
            LogStore.log("Diagnostics", "WebSocket reachability check failed: ${ex.message}")
            false
        }
    }

    /**
     * Format diagnostics report as readable text.
     */
    companion object {
        fun formatReport(report: DiagnosticsReport): String {
            return buildString {
                appendLine("═══════════════════════════════════════════")
                appendLine("  CONNECTIVITY DIAGNOSTICS")
                appendLine("═══════════════════════════════════════════")
                appendLine("Timestamp      : ${report.timestamp}")
                appendLine("Backend        : ${report.backend}")
                appendLine("Backend Host   : ${report.backendHost}")
                appendLine("Backend Port   : ${report.backendPort}")
                appendLine()
                appendLine("─────────────────────────────────────────")
                appendLine("  ANDROID NETWORK")
                appendLine("─────────────────────────────────────────")
                appendLine("Connected      : ${if (report.androidNetworkConnected) "✓ Yes" else "✗ No"}")
                appendLine("Type           : ${report.networkType}")
                appendLine()
                appendLine("─────────────────────────────────────────")
                appendLine("  BACKEND CONNECTIVITY")
                appendLine("─────────────────────────────────────────")
                appendLine("DNS            : ${if (report.dnsResolved) "✓ ${report.resolvedIp}" else "✗ Cannot resolve"}")
                appendLine("TCP Socket     : ${if (report.tcpConnectable) "✓ Connectable" else "✗ Not connectable"}")
                appendLine("HTTP Health    : ${if (report.httpReachable) "✓ Reachable" else "✗ Not reachable"}")
                appendLine("WebSocket      : ${if (report.wsReachable) "✓ Reachable" else "✗ Not reachable"}")
                appendLine()
                appendLine("─────────────────────────────────────────")
                appendLine("  ISSUES")
                appendLine("─────────────────────────────────────────")
                if (report.issues.isEmpty()) {
                    appendLine("None — all systems operational ✓")
                } else {
                    report.issues.forEach { appendLine("  • $it") }
                }
                appendLine()
                appendLine("${report.summary}")
                appendLine("═══════════════════════════════════════════")
            }
        }
    }
}
