package com.optimatrix.gsmcall.network

import com.optimatrix.gsmcall.NetworkConfig
import com.optimatrix.gsmcall.utils.LogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class TcpConnectionTester {

    companion object {
        private const val TAG = "TcpTester"
    }

    data class TcpTestResult(
        val host: String,
        val port: Int,
        val success: Boolean,
        val latencyMs: Long,
        val failureReason: String? = null,
        val failureCategory: FailureCategory = FailureCategory.UNKNOWN
    )

    enum class FailureCategory {
        NONE,
        TIMEOUT,
        CONNECTION_REFUSED,
        DNS_FAILURE,
        NO_ROUTE,
        PORT_UNREACHABLE,
        NETWORK_UNREACHABLE,
        UNKNOWN
    }

    suspend fun testConnection(host: String = NetworkConfig.host, port: Int = NetworkConfig.port, timeoutMs: Int = 5000): TcpTestResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        LogStore.log(TAG, "Testing TCP: $host:$port (timeout=${timeoutMs}ms)")

        return@withContext try {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            val latency = System.currentTimeMillis() - startTime
            socket.close()
            LogStore.log(TAG, "TCP success: $host:$port (${latency}ms)")
            TcpTestResult(host, port, true, latency)
        } catch (e: SocketTimeoutException) {
            val elapsed = System.currentTimeMillis() - startTime
            LogStore.log(TAG, "TCP timeout: $host:$port after ${elapsed}ms")
            TcpTestResult(host, port, false, elapsed, "Connection timed out after ${timeoutMs}ms", FailureCategory.TIMEOUT)
        } catch (e: ConnectException) {
            val elapsed = System.currentTimeMillis() - startTime
            LogStore.log(TAG, "TCP refused: $host:$port - ${e.message}")
            TcpTestResult(host, port, false, elapsed, "Connection refused: ${e.message}", FailureCategory.CONNECTION_REFUSED)
        } catch (e: UnknownHostException) {
            val elapsed = System.currentTimeMillis() - startTime
            LogStore.log(TAG, "TCP DNS failed: $host - ${e.message}")
            TcpTestResult(host, port, false, elapsed, "DNS resolution failed: ${e.message}", FailureCategory.DNS_FAILURE)
        } catch (e: NoRouteToHostException) {
            val elapsed = System.currentTimeMillis() - startTime
            LogStore.log(TAG, "TCP no route: $host:$port - ${e.message}")
            TcpTestResult(host, port, false, elapsed, "No route to host: ${e.message}", FailureCategory.NO_ROUTE)
        } catch (e: PortUnreachableException) {
            val elapsed = System.currentTimeMillis() - startTime
            LogStore.log(TAG, "TCP port unreachable: $host:$port - ${e.message}")
            TcpTestResult(host, port, false, elapsed, "Port unreachable: ${e.message}", FailureCategory.PORT_UNREACHABLE)
        } catch (e: IOException) {
            val elapsed = System.currentTimeMillis() - startTime
            LogStore.log(TAG, "TCP IO error: $host:$port - ${e.javaClass.simpleName}: ${e.message}")
            TcpTestResult(host, port, false, elapsed, "${e.javaClass.simpleName}: ${e.message}", FailureCategory.NETWORK_UNREACHABLE)
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            LogStore.log(TAG, "TCP unexpected: $host:$port - ${e.javaClass.simpleName}: ${e.message}")
            TcpTestResult(host, port, false, elapsed, "${e.javaClass.simpleName}: ${e.message}", FailureCategory.UNKNOWN)
        }
    }

    suspend fun testMultiplePorts(host: String = NetworkConfig.host, ports: List<Int> = listOf(3000, 8000, 80), timeoutMs: Int = 3000): List<TcpTestResult> = withContext(Dispatchers.IO) {
        ports.map { port ->
            testConnection(host, port, timeoutMs)
        }
    }

    fun formatResult(result: TcpTestResult): String {
        val icon = if (result.success) "✓" else "✗"
        val reason = if (!result.success && result.failureReason != null) " — ${result.failureReason}" else ""
        val latency = if (result.success) " (${result.latencyMs}ms)" else ""
        return "$icon TCP ${result.host}:${result.port}$latency$reason"
    }
}
