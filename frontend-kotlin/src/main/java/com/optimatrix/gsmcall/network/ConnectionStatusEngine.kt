package com.optimatrix.gsmcall.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import com.optimatrix.gsmcall.NetworkConfig
import com.optimatrix.gsmcall.utils.LogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ConnectionStatusEngine(private val context: Context) {

    companion object {
        private const val TAG = "ConnStatusEngine"
        private const val HEALTH_POLL_INTERVAL_MS = 15_000L
    }

    enum class BackendStatus {
        OFFLINE,
        CONNECTING,
        ONLINE,
        DEGRADED,
        RECONNECTING
    }

    enum class WebSocketStatus {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        RECONNECTING,
        FAILED
    }

    enum class NetworkStatus {
        NO_NETWORK,
        WIFI_CONNECTED,
        CELLULAR_CONNECTED,
        ETHERNET_CONNECTED,
        WIFI_HOTSPOT,
        VPN_ACTIVE
    }

    data class ConnectionState(
        val backendStatus: BackendStatus = BackendStatus.OFFLINE,
        val webSocketStatus: WebSocketStatus = WebSocketStatus.DISCONNECTED,
        val networkStatus: NetworkStatus = NetworkStatus.NO_NETWORK,
        val backendHost: String = NetworkConfig.host,
        val backendPort: Int = NetworkConfig.port,
        val latencyMs: Long = -1,
        val healthResponse: Boolean = false,
        val tcpReachable: Boolean = false,
        val lastError: String? = null,
        val reconnectAttempt: Int = 0,
        val uptimeMs: Long = 0
    )

    interface StatusListener {
        fun onConnectionStateChanged(state: ConnectionState)
        fun onBackendOnline()
        fun onBackendOffline(reason: String)
        fun onNetworkChanged(networkStatus: NetworkStatus)
    }

    private val listeners = mutableListOf<StatusListener>()
    private val handler = Handler(Looper.getMainLooper())
    private val healthCheckRunnable = object : Runnable {
        override fun run() {
            performHealthCheck()
            handler.postDelayed(this, HEALTH_POLL_INTERVAL_MS)
        }
    }

    private var _state = ConnectionState()
    private var healthPolling = AtomicBoolean(false)
    private var startTime = System.currentTimeMillis()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    fun getState(): ConnectionState = _state

    fun addListener(listener: StatusListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: StatusListener) {
        listeners.remove(listener)
    }

    fun startHealthPolling() {
        if (healthPolling.compareAndSet(false, true)) {
            startTime = System.currentTimeMillis()
            handler.post(healthCheckRunnable)
            LogStore.log(TAG, "Health polling started (${HEALTH_POLL_INTERVAL_MS}ms)")
        }
    }

    fun stopHealthPolling() {
        if (healthPolling.compareAndSet(true, false)) {
            handler.removeCallbacks(healthCheckRunnable)
            LogStore.log(TAG, "Health polling stopped")
        }
    }

    suspend fun runFullDiagnostics(): ConnectionState = withContext(Dispatchers.IO) {
        LogStore.log(TAG, "Running full connection diagnostics...")

        val networkStatus = detectNetworkStatus()
        val tcpResult = TcpConnectionTester().testConnection()
        val httpHealthy = if (tcpResult.success) checkHttpHealth() else false

        val backendStatus = when {
            httpHealthy -> BackendStatus.ONLINE
            tcpResult.success -> BackendStatus.DEGRADED
            else -> BackendStatus.OFFLINE
        }

        val newState = _state.copy(
            backendStatus = backendStatus,
            networkStatus = networkStatus,
            tcpReachable = tcpResult.success,
            healthResponse = httpHealthy,
            latencyMs = tcpResult.latencyMs,
            lastError = if (!tcpResult.success) tcpResult.failureReason else null,
            uptimeMs = System.currentTimeMillis() - startTime
        )

        _state = newState
        notifyStateChanged(newState)

        if (httpHealthy) {
            notifyBackendOnline()
        } else {
            notifyBackendOffline(tcpResult.failureReason ?: "Connection failed")
        }

        newState
    }

    fun updateWebSocketStatus(status: WebSocketStatus, error: String? = null) {
        val newState = _state.copy(
            webSocketStatus = status,
            lastError = error
        )
        _state = newState
        notifyStateChanged(newState)

        if (status == WebSocketStatus.CONNECTED) {
            notifyBackendOnline()
        }
    }

    fun updateBackendStatus(status: BackendStatus, error: String? = null) {
        val newState = _state.copy(
            backendStatus = status,
            lastError = error
        )
        _state = newState
        notifyStateChanged(newState)
    }

    fun recordReconnectAttempt(attempt: Int) {
        _state = _state.copy(reconnectAttempt = attempt)
        _state = _state.copy(backendStatus = BackendStatus.RECONNECTING)
        notifyStateChanged(_state)
    }

    private fun performHealthCheck() {
        val tcpTester = TcpConnectionTester()
        try {
            val socket = java.net.Socket()
            socket.connect(
                java.net.InetSocketAddress(_state.backendHost, _state.backendPort),
                5000
            )
            socket.close()

            val wasOffline = !_state.tcpReachable
            _state = _state.copy(
                tcpReachable = true,
                uptimeMs = System.currentTimeMillis() - startTime
            )

            if (wasOffline && _state.webSocketStatus == WebSocketStatus.CONNECTED) {
                _state = _state.copy(backendStatus = BackendStatus.ONLINE)
                notifyStateChanged(_state)
                notifyBackendOnline()
            }
        } catch (e: Exception) {
            if (_state.tcpReachable) {
                LogStore.log(TAG, "Health poll: backend unreachable - ${e.message}")
                _state = _state.copy(
                    tcpReachable = false,
                    backendStatus = BackendStatus.OFFLINE,
                    lastError = e.message
                )
                notifyStateChanged(_state)
                notifyBackendOffline(e.message ?: "Health check failed")
            }
        }
    }

    private suspend fun checkHttpHealth(): Boolean {
        return try {
            val request = Request.Builder()
                .url("${NetworkConfig.httpBaseUrl}/health")
                .get()
                .build()
            val response = httpClient.newCall(request).execute()
            val healthy = response.isSuccessful
            response.close()
            healthy
        } catch (e: Exception) {
            false
        }
    }

    private fun detectNetworkStatus(): NetworkStatus {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return NetworkStatus.NO_NETWORK

            val network = cm.activeNetwork ?: return NetworkStatus.NO_NETWORK
            val caps = cm.getNetworkCapabilities(network) ?: return NetworkStatus.NO_NETWORK

            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
                        NetworkStatus.WIFI_CONNECTED
                    } else {
                        NetworkStatus.WIFI_HOTSPOT
                    }
                }
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                    NetworkStatus.CELLULAR_CONNECTED
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                    NetworkStatus.ETHERNET_CONNECTED
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ->
                    NetworkStatus.WIFI_CONNECTED
                else -> NetworkStatus.VPN_ACTIVE
            }
        } catch (e: Exception) {
            NetworkStatus.NO_NETWORK
        }
    }

    private fun notifyStateChanged(state: ConnectionState) {
        listeners.forEach { it.onConnectionStateChanged(state) }
    }

    private fun notifyBackendOnline() {
        listeners.forEach { it.onBackendOnline() }
    }

    private fun notifyBackendOffline(reason: String) {
        listeners.forEach { it.onBackendOffline(reason) }
    }

    fun destroy() {
        stopHealthPolling()
        listeners.clear()
        LogStore.log(TAG, "ConnectionStatusEngine destroyed")
    }
}
