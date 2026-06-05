package com.optimatrix.gsmcall.networking

import android.os.Handler
import android.os.Looper
import com.optimatrix.gsmcall.NetworkConfig
import com.optimatrix.gsmcall.utils.LogStore
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit as TU

/**
 * SocketManager — WebSocket client for real-time Android ↔ backend communication.
 *
 * Uses OkHttp3 WebSocket for production-grade reliability:
 *   ✓ Automatic reconnection with exponential backoff
 *   ✓ Heartbeat/ping-pong support
 *   ✓ Android 14+ compatible
 *   ✓ Thread-safe singleton
 *   ✓ Production-grade error handling
 *
 * Connection flow:
 *   1. Connect to ws://backend:3000/
 *   2. Auto-reconnect on disconnect with exponential backoff (1s → 32s)
 *   3. Emit events for connection lifecycle
 *   4. Handle incoming messages from backend
 *
 * Backend (Node.js):
 *   Uses raw 'ws' library with aggressive heartbeat:
 *     - Ping every 25s (keep-alive)
 *     - Disconnects on timeout
 *
 * Protocol:
 *   Send: JSON events from Android app to backend
 *   Receive: JSON events from backend campaigns
 *
 * Usage:
 *   SocketManager.initialize()
 *   SocketManager.connect()
 *   SocketManager.on("event", callback)
 *   SocketManager.emit("event", data)
 */
object SocketManager : WebSocketListener() {
    private var ws: WebSocket? = null
    private var client: OkHttpClient? = null
    private var isConnecting = false
    private var reconnectAttempt = 0
    private val maxReconnectAttempts = 12  // ~5 minutes with exponential backoff
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Event listeners
    private val eventListeners = mutableMapOf<String, MutableList<(JSONObject?) -> Unit>>()
    private val stateListeners = mutableListOf<(Boolean) -> Unit>()

    // ──── Initialization ────────────────────────────────────────────────────

    fun initialize() {
        LogStore.log("SocketManager", "Initializing WebSocket client")
        val wsUrl = "${NetworkConfig.wsUrl}".replace("http://", "ws://")
        LogStore.log("SocketManager", "WebSocket URL: $wsUrl")

        try {
            // Create OkHttp client with connection pool and timeouts
            client = OkHttpClient.Builder()
                .connectTimeout(15, TU.SECONDS)
                .readTimeout(120, TU.SECONDS)
                .writeTimeout(60, TU.SECONDS)
                .pingInterval(25, TU.SECONDS)  // Aggressive heartbeat to match backend
                .retryOnConnectionFailure(true)
                .connectionPool(okhttp3.ConnectionPool(1, 30, TU.SECONDS))
                .build()

            LogStore.log("SocketManager", "OkHttpClient configured with aggressive heartbeat")
        } catch (e: Exception) {
            LogStore.log("SocketManager", "FATAL: WebSocket initialization failed: ${e.message}")
        }
    }

    // ──── Connection Management ────────────────────────────────────────────

    fun connect() {
        if (client == null) {
            LogStore.log("SocketManager", "Client not initialized — call initialize() first")
            return
        }

        if (ws != null && !isConnecting) {
            LogStore.log("SocketManager", "Already connected or connection in progress")
            return
        }

        val wsUrl = "${NetworkConfig.wsUrl}".replace("http://", "ws://")
        LogStore.log("SocketManager", "Connecting to WebSocket: $wsUrl")
        isConnecting = true
        reconnectAttempt = 0

        val request = Request.Builder()
            .url(wsUrl)
            .addHeader("User-Agent", "Android-GSM-AI/3.0 (okhttp-ws)")
            .build()

        try {
            ws = client!!.newWebSocket(request, this)
            LogStore.log("SocketManager", "WebSocket connection request sent")
        } catch (e: Exception) {
            LogStore.log("SocketManager", "Failed to create WebSocket: ${e.message}")
            isConnecting = false
            _scheduleReconnect()
        }
    }

    fun disconnect() {
        LogStore.log("SocketManager", "Disconnecting WebSocket")
        try {
            ws?.close(1000, "User disconnected")
        } catch (e: Exception) {
            LogStore.log("SocketManager", "Error closing WebSocket: ${e.message}")
        }
        ws = null
        isConnecting = false
        reconnectAttempt = 0
    }

    fun isConnected(): Boolean = ws != null && !isConnecting

    fun reconnect() {
        LogStore.log("SocketManager", "Manual reconnect")
        disconnect()
        connect()
    }

    // ──── WebSocketListener Implementation ─────────────────────────────────

    override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
        LogStore.log("SocketManager", "✓ WebSocket CONNECTED")
        isConnecting = false
        reconnectAttempt = 0
        
        // Send initial message identifying as Android client
        val hello = JSONObject().apply {
            put("event", "android_ready")
            put("timestamp", System.currentTimeMillis())
        }
        webSocket.send(hello.toString())
        
        _notifyStateChange(true)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        try {
            val json = JSONObject(text)
            val event = json.optString("event", "unknown")
            LogStore.log("SocketManager", "← $event")
            
            // Dispatch to listeners
            mainHandler.post {
                eventListeners[event]?.forEach { listener ->
                    try {
                        listener(json)
                    } catch (e: Exception) {
                        LogStore.log("SocketManager", "Listener error for $event: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            LogStore.log("SocketManager", "Failed to parse message: ${e.message}")
        }
    }

    override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
        LogStore.log("SocketManager", "Received binary message (${bytes.size} bytes)")
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        LogStore.log("SocketManager", "WebSocket closing: code=$code reason=$reason")
        webSocket.close(1000, null)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        LogStore.log("SocketManager", "✗ WebSocket DISCONNECTED: code=$code reason=$reason")
        if (webSocket === ws) {
            ws = null
        }
        _notifyStateChange(false)
        _scheduleReconnect()
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
        val error = t.message ?: "Unknown error"
        LogStore.log("SocketManager", "⚠ WebSocket failure: ${t.javaClass.simpleName}: $error")
        
        when (t) {
            is java.net.ConnectException -> {
                LogStore.log("SocketManager", "Connection refused — backend not reachable")
            }
            is java.net.UnknownHostException -> {
                LogStore.log("SocketManager", "DNS failed — cannot resolve ${NetworkConfig.host}")
            }
            is java.net.SocketTimeoutException -> {
                LogStore.log("SocketManager", "Connection timeout — backend not responding")
            }
            else -> {
                LogStore.log("SocketManager", "WebSocket error: ${t.javaClass.simpleName}")
            }
        }
        
        if (webSocket === ws) {
            ws = null
        }
        _notifyStateChange(false)
        _scheduleReconnect()
    }

    // ──── Event Management ─────────────────────────────────────────────────

    fun on(event: String, callback: (JSONObject?) -> Unit) {
        if (!eventListeners.containsKey(event)) {
            eventListeners[event] = mutableListOf()
        }
        eventListeners[event]!!.add(callback)
        LogStore.log("SocketManager", "Listener registered for $event")
    }

    fun emit(event: String, data: JSONObject) {
        if (ws == null) {
            LogStore.log("SocketManager", "Cannot emit $event — not connected")
            return
        }
        try {
            data.put("event", event)
            data.put("timestamp", System.currentTimeMillis())
            ws!!.send(data.toString())
            LogStore.log("SocketManager", "→ $event")
        } catch (e: Exception) {
            LogStore.log("SocketManager", "Emit failed for $event: ${e.message}")
        }
    }

    fun emit(event: String, data: String) {
        if (ws == null) {
            LogStore.log("SocketManager", "Cannot emit $event — not connected")
            return
        }
        try {
            ws!!.send(data)
            LogStore.log("SocketManager", "→ $event")
        } catch (e: Exception) {
            LogStore.log("SocketManager", "Emit failed for $event: ${e.message}")
        }
    }

    fun onConnectionStateChanged(listener: (Boolean) -> Unit) {
        stateListeners.add(listener)
    }

    // ──── Private Helpers ──────────────────────────────────────────────────

    private fun _scheduleReconnect() {
        if (reconnectAttempt >= maxReconnectAttempts) {
            LogStore.log("SocketManager", "Max reconnection attempts reached ($maxReconnectAttempts)")
            return
        }

        reconnectAttempt++
        // Exponential backoff: 1s, 2s, 4s, 8s, 16s, 32s (max)
        val delayMs = (1000 * Math.pow(2.0, (reconnectAttempt - 1).toDouble()))
            .toLong()
            .coerceAtMost(32000)

        LogStore.log("SocketManager", "Scheduling reconnect attempt #$reconnectAttempt in ${delayMs}ms")
        mainHandler.postDelayed({
            if (ws == null && reconnectAttempt < maxReconnectAttempts) {
                connect()
            }
        }, delayMs)
    }

    private fun _notifyStateChange(connected: Boolean) {
        mainHandler.post {
            stateListeners.forEach { it(connected) }
        }
    }

    // ──── Diagnostics ──────────────────────────────────────────────────────

    fun getStatus(): String {
        val state = when {
            ws == null && !isConnecting -> "DISCONNECTED"
            isConnecting -> "CONNECTING"
            ws != null -> "CONNECTED"
            else -> "UNKNOWN"
        }
        return state
    }

    fun getUrl(): String = NetworkConfig.wsUrl

    fun dump(): String {
        return """
            SocketManager Status:
              State: ${getStatus()}
              URL: ${getUrl()}
              Reconnect attempts: $reconnectAttempt/$maxReconnectAttempts
              State listeners: ${stateListeners.size}
              Event listeners: ${eventListeners.size}
        """.trimIndent()
    }
}
