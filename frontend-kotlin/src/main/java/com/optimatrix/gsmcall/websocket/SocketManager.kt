package com.optimatrix.gsmcall.websocket

import android.os.Handler
import android.os.Looper
import com.optimatrix.gsmcall.utils.LogStore
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * SocketManager — Production WebSocket client with auto-reconnect.
 *
 * Connects to: ws://192.168.1.100:3000/   (backend-node WebSocketServer)
 *
 * Incoming events handled from backend:
 *   connected, call_started, call_connected, greeting_playing, greeting_played,
 *   recording_started, recording_saved, transcription_done, intent_detected,
 *   call_completed, call_failed, campaign_progress, campaign_done,
 *   device_status, log
 *
 * Outgoing events sent to backend:
 *   android_ready       — app connected
 *   call_state          — telephony state change
 *   playback_result     — greeting play result
 *   recording_saved     — recording file saved
 *   watchdog            — 30s heartbeat
 */
class SocketManager(private val hostUrl: String) {

    companion object {
        private const val TAG = "SocketManager"
        private const val RECONNECT_DELAY_BASE_MS = 3_000L
        private const val MAX_RECONNECT_ATTEMPTS = 15
        private const val PING_INTERVAL_MS = 30_000L
    }

    interface EventListener {
        fun onCallStarted(phone: String, name: String)
        fun onCallConnected(phone: String)
        fun onGreetingPlayed(phone: String, success: Boolean)
        fun onTranscriptionDone(phone: String, transcription: String, intent: String, confidence: Double)
        fun onCallCompleted(phone: String, intent: String, transcription: String)
        fun onCallFailed(phone: String, error: String)
        fun onCampaignProgress(campaignId: String, processed: Int, total: Int, yesCount: Int, noCount: Int)
        fun onLog(level: String, message: String)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)       // no timeout for WS
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
        .build()

    private var socket: WebSocket? = null
    private val isConnected = AtomicBoolean(false)
    private val reconnectAttempts = AtomicInteger(0)
    private val handler = Handler(Looper.getMainLooper())
    private var eventListener: EventListener? = null
    private var _onMessageRaw: ((String) -> Unit)? = null

    // ── Public API ────────────────────────────────────────────────────────────

    fun connect() {
        LogStore.log(TAG, "Connecting to $hostUrl")
        connectInternal()
    }

    fun setEventListener(listener: EventListener) {
        eventListener = listener
    }

    /** Raw JSON message listener (for ViewModel/UI updates) */
    fun setOnMessage(listener: (String) -> Unit) {
        _onMessageRaw = listener
    }

    val connected: Boolean get() = isConnected.get()

    // ── Outgoing events ───────────────────────────────────────────────────────

    /** Tell backend the Android app is ready */
    fun sendReady() = sendJson(mapOf(
        "event" to "android_ready",
        "ts" to System.currentTimeMillis(),
    ))

    /** Report telephony state change */
    fun sendCallState(state: String, phone: String?, flowId: Int = 0) = sendJson(mapOf(
        "event" to "call_state",
        "state" to state,
        "phone" to (phone ?: ""),
        "flowId" to flowId,
        "ts" to System.currentTimeMillis(),
    ))

    /** Report greeting playback result */
    fun sendPlaybackResult(fileName: String, strategy: String, success: Boolean) = sendJson(mapOf(
        "event" to "playback_result",
        "file" to fileName,
        "strategy" to strategy,
        "success" to success,
        "ts" to System.currentTimeMillis(),
    ))

    /** Report recording file saved */
    fun sendRecordingSaved(filePath: String, durationSec: Int) = sendJson(mapOf(
        "event" to "recording_saved",
        "path" to filePath,
        "duration" to durationSec,
        "ts" to System.currentTimeMillis(),
    ))

    /** Heartbeat */
    fun sendWatchdog() = sendJson(mapOf(
        "event" to "watchdog",
        "ts" to System.currentTimeMillis(),
    ))

    fun sendEvent(event: String) {
        if (isConnected.get()) {
            socket?.send(event)
        } else {
            LogStore.log(TAG, "Not connected — dropped: ${event.take(80)}")
        }
    }

    fun close() {
        handler.removeCallbacksAndMessages(null)
        socket?.close(1000, "Client closed")
        socket = null
        isConnected.set(false)
        LogStore.log(TAG, "Closed")
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun connectInternal() {
        try {
            val request = Request.Builder()
                .url(hostUrl)
                .addHeader("User-Agent", "Android-GSM-AI/3.0 (okhttp)")
                .build()
            socket = client.newWebSocket(request, listener)
        } catch (ex: Exception) {
            LogStore.log(TAG, "Connect exception: ${ex.message}")
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        val attempt = reconnectAttempts.incrementAndGet()
        if (attempt > MAX_RECONNECT_ATTEMPTS) {
            LogStore.log(TAG, "Max reconnect attempts ($MAX_RECONNECT_ATTEMPTS) reached — stopping")
            return
        }
        val delay = RECONNECT_DELAY_BASE_MS * minOf(attempt, 5).toLong()
        LogStore.log(TAG, "Reconnecting in ${delay}ms (attempt $attempt)")
        handler.postDelayed({ connectInternal() }, delay)
    }

    private fun sendJson(map: Map<String, Any?>) {
        if (!isConnected.get()) {
            LogStore.log(TAG, "Not connected — dropping ${map["event"]}")
            return
        }
        try {
            val obj = JSONObject()
            map.forEach { (k, v) -> obj.put(k, v) }
            val sent = socket?.send(obj.toString()) ?: false
            if (!sent) LogStore.log(TAG, "send() returned false for ${map["event"]}")
        } catch (ex: Exception) {
            LogStore.log(TAG, "sendJson exception: ${ex.message}")
        }
    }

    private fun dispatchEvent(json: JSONObject) {
        val event = json.optString("event", "")
        _onMessageRaw?.invoke(json.toString())

        when (event) {
            "connected" -> LogStore.log(TAG, "Backend acknowledged: ${json.optString("message")}")

            "call_started" -> eventListener?.onCallStarted(
                json.optString("phone"), json.optString("name")
            )
            "call_connected" -> eventListener?.onCallConnected(json.optString("phone"))

            "greeting_played" -> eventListener?.onGreetingPlayed(
                json.optString("phone"), json.optBoolean("success", true)
            )
            "transcription_done" -> eventListener?.onTranscriptionDone(
                json.optString("phone"),
                json.optString("transcription"),
                json.optString("intent"),
                json.optDouble("confidence", 0.0),
            )
            "call_completed" -> eventListener?.onCallCompleted(
                json.optString("phone"),
                json.optString("intent"),
                json.optString("transcription"),
            )
            "call_failed" -> eventListener?.onCallFailed(
                json.optString("phone"), json.optString("error")
            )
            "campaign_progress" -> eventListener?.onCampaignProgress(
                json.optString("campaignId"),
                json.optInt("processed"),
                json.optInt("total"),
                json.optInt("yesCount"),
                json.optInt("noCount"),
            )
            "log" -> eventListener?.onLog(json.optString("level", "info"), json.optString("message"))
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            isConnected.set(true)
            reconnectAttempts.set(0)
            LogStore.log(TAG, "Connected to $hostUrl")
            sendReady()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            LogStore.log(TAG, "← ${text.take(200)}")
            try {
                dispatchEvent(JSONObject(text))
            } catch (ex: Exception) {
                LogStore.log(TAG, "Message parse error: ${ex.message}")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            isConnected.set(false)
            LogStore.log(TAG, "Failure: ${t.message} — scheduling reconnect")
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            isConnected.set(false)
            LogStore.log(TAG, "Closed: code=$code reason=$reason")
            if (code != 1000) scheduleReconnect()
        }
    }
}
