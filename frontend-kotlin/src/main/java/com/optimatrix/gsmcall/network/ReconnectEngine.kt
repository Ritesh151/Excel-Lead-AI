package com.optimatrix.gsmcall.network

import android.os.Handler
import android.os.Looper
import com.optimatrix.gsmcall.NetworkConfig
import com.optimatrix.gsmcall.utils.LogStore
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ReconnectEngine {

    companion object {
        private const val TAG = "ReconnectEngine"
        private const val BASE_DELAY_MS = 2_000L
        private const val MAX_DELAY_MS = 120_000L
        private const val MAX_ATTEMPTS = 50
        private const val PING_INTERVAL_MS = 25_000L
        private const val STALE_TIMEOUT_MS = 60_000L
    }

    interface ReconnectListener {
        fun onConnected()
        fun onDisconnected(code: Int, reason: String)
        fun onFailure(error: String)
        fun onReconnecting(attempt: Int, delayMs: Long)
        fun onReconnectFailed(maxAttempts: Int)
        fun onHeartbeatTimeout()
        fun onStaleSocketCleaned()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val reconnectAttempts = AtomicInteger(0)
    private val isConnected = AtomicBoolean(false)
    private val isReconnecting = AtomicBoolean(false)
    private val shouldStop = AtomicBoolean(false)
    private val isInHeavyBackoff = AtomicBoolean(false)

    private var webSocket: WebSocket? = null
    private var lastPongTime = System.currentTimeMillis()
    private var connectionStartTime = 0L
    private var listener: ReconnectListener? = null
    private var httpClient: OkHttpClient? = null
    private var pingRunnable: Runnable? = null
    private var staleRunnable: Runnable? = null

    private val wsListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            isConnected.set(true)
            isReconnecting.set(false)
            reconnectAttempts.set(0)
            isInHeavyBackoff.set(false)
            shouldStop.set(false)
            connectionStartTime = System.currentTimeMillis()
            lastPongTime = System.currentTimeMillis()
            LogStore.log(TAG, "WebSocket connected (HTTP ${response.code})")
            handler.post { listener?.onConnected() }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            lastPongTime = System.currentTimeMillis()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            LogStore.log(TAG, "WebSocket closing: code=$code reason=$reason")
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            isConnected.set(false)
            LogStore.log(TAG, "WebSocket closed: code=$code reason=$reason")
            handler.post { listener?.onDisconnected(code, reason) }
            if (code != 1000 && !shouldStop.get()) {
                scheduleReconnect()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            isConnected.set(false)
            val errorMsg = "${t.javaClass.simpleName}: ${t.message}"
            LogStore.log(TAG, "WebSocket failure: $errorMsg")
            handler.post { listener?.onFailure(errorMsg) }
            if (!shouldStop.get()) {
                scheduleReconnect()
            }
        }
    }

    fun setListener(listener: ReconnectListener) {
        this.listener = listener
    }

    fun connect(wsUrl: String = NetworkConfig.wsUrl) {
        shouldStop.set(false)
        isConnected.set(false)
        reconnectAttempts.set(0)
        isInHeavyBackoff.set(false)

        httpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .pingInterval(PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()

        performConnect(wsUrl)
        startPingMonitor()
        startStaleSocketMonitor()
    }

    fun disconnect() {
        shouldStop.set(true)
        isConnected.set(false)
        isReconnecting.set(false)
        handler.removeCallbacksAndMessages(null)
        webSocket?.close(1000, "Client shutdown")
        webSocket = null
        stopPingMonitor()
        stopStaleSocketMonitor()
        LogStore.log(TAG, "ReconnectEngine disconnected")
    }

    fun forceReconnect() {
        LogStore.log(TAG, "Force reconnect requested")
        isInHeavyBackoff.set(false)
        reconnectAttempts.set(0)
        shouldStop.set(false)
        webSocket?.close(1001, "Force reconnect")
        webSocket = null
        performConnect(NetworkConfig.wsUrl)
    }

    fun isCurrentlyConnected(): Boolean = isConnected.get()

    fun getReconnectAttempts(): Int = reconnectAttempts.get()

    fun getUptimeMs(): Long = if (isConnected.get() && connectionStartTime > 0) {
        System.currentTimeMillis() - connectionStartTime
    } else 0

    private fun performConnect(wsUrl: String) {
        if (shouldStop.get()) return
        if (isConnected.get()) return

        LogStore.log(TAG, "Connecting to $wsUrl")
        try {
            val request = Request.Builder()
                .url(wsUrl)
                .addHeader("User-Agent", "Android-GSMCall/3.0 (reconnect-engine)")
                .build()
            webSocket = httpClient?.newWebSocket(request, wsListener)
        } catch (e: Exception) {
            LogStore.log(TAG, "Connect failed: ${e.message}")
            if (!shouldStop.get()) scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (shouldStop.get()) return
        if (isReconnecting.compareAndSet(false, true)) {
            val attempt = reconnectAttempts.incrementAndGet()

            if (attempt > MAX_ATTEMPTS) {
                if (isInHeavyBackoff.compareAndSet(false, true)) {
                    LogStore.log(TAG, "Max reconnect attempts ($MAX_ATTEMPTS) reached. Entering 5-minute backoff.")
                    handler.post { listener?.onReconnectFailed(MAX_ATTEMPTS) }
                    handler.postDelayed({
                        isInHeavyBackoff.set(false)
                        reconnectAttempts.set(0)
                        isReconnecting.set(false)
                        performConnect(NetworkConfig.wsUrl)
                    }, 300_000L)
                }
                return
            }

            val delayMs = calculateBackoff(attempt)
            LogStore.log(TAG, "Reconnect attempt $attempt/$MAX_ATTEMPTS in ${delayMs}ms")
            handler.post { listener?.onReconnecting(attempt, delayMs) }

            handler.postDelayed({
                isReconnecting.set(false)
                performConnect(NetworkConfig.wsUrl)
            }, delayMs)
        }
    }

    private fun calculateBackoff(attempt: Int): Long {
        val expDelay = BASE_DELAY_MS * (1L shl minOf(attempt - 1, 6))
        val jitter = (Math.random() * 0.5 * expDelay).toLong()
        return minOf(expDelay + jitter, MAX_DELAY_MS)
    }

    private fun startPingMonitor() {
        stopPingMonitor()
        pingRunnable = Runnable {
            if (isConnected.get()) {
                val elapsed = System.currentTimeMillis() - lastPongTime
                if (elapsed > PING_INTERVAL_MS * 3) {
                    LogStore.log(TAG, "Heartbeat timeout: ${elapsed}ms since last pong")
                    handler.post { listener?.onHeartbeatTimeout() }
                    webSocket?.close(1002, "Heartbeat timeout")
                    performConnect(NetworkConfig.wsUrl)
                }
                if (!shouldStop.get()) {
                    handler.postDelayed(pingRunnable!!, PING_INTERVAL_MS)
                }
            }
        }
        handler.postDelayed(pingRunnable!!, PING_INTERVAL_MS)
    }

    private fun stopPingMonitor() {
        pingRunnable?.let { handler.removeCallbacks(it) }
        pingRunnable = null
    }

    private fun startStaleSocketMonitor() {
        stopStaleSocketMonitor()
        staleRunnable = Runnable {
            val ws = webSocket
            if (ws != null && !isConnected.get()) {
                val elapsed = connectionStartTime.let { if (it > 0) System.currentTimeMillis() - it else 0L }
                if (elapsed > STALE_TIMEOUT_MS) {
                    LogStore.log(TAG, "Cleaning stale socket (${elapsed}ms old)")
                    ws.close(1006, "Stale socket")
                    webSocket = null
                    handler.post { listener?.onStaleSocketCleaned() }
                }
            }
            if (!shouldStop.get()) {
                handler.postDelayed(staleRunnable!!, STALE_TIMEOUT_MS)
            }
        }
        handler.postDelayed(staleRunnable!!, STALE_TIMEOUT_MS)
    }

    private fun stopStaleSocketMonitor() {
        staleRunnable?.let { handler.removeCallbacks(it) }
        staleRunnable = null
    }
}
