package com.optimatrix.gsmcall.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.optimatrix.gsmcall.NetworkConfig
import com.optimatrix.gsmcall.api.ApiClient
import com.optimatrix.gsmcall.audio.AudioRoutingManager
import com.optimatrix.gsmcall.audio.CallAudioPlayer
import com.optimatrix.gsmcall.recording.RecordingManager
import com.optimatrix.gsmcall.telephony.CallSession
import com.optimatrix.gsmcall.telephony.CallSessionTracker
import com.optimatrix.gsmcall.telephony.TelephonyController
import com.optimatrix.gsmcall.ui.MainActivity
import com.optimatrix.gsmcall.utils.LogStore
import com.optimatrix.gsmcall.websocket.SocketManager
import com.optimatrix.gsmcall.workers.SyncWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * CallAutomationService — Production foreground service.
 *
 * Responsibilities:
 *   1. Survive screen-off, battery optimization, background kills (START_STICKY + PARTIAL_WAKE_LOCK)
 *   2. Monitor GSM call state via TelephonyController (PhoneStateListener)
 *   3. On CONNECTED: stabilize (1 200 ms) → route audio → play greeting.wav → record 18 s → upload
 *   4. Broadcast every state change to MainActivity via LocalBroadcast
 *   5. Emit every state change to backend-node via WebSocket (SocketManager)
 *   6. Report playback strategy result to backend
 *   7. Report recording saved to backend
 *   8. End call cleanly and reset state
 *   9. Watchdog: re-connect WebSocket every 30 s if dead
 *
 * WebSocket target: ws://192.168.1.100:3000/  (same host as ApiClient.BASE_URL)
 */
class CallAutomationService : Service(), CallSessionTracker.Listener {

    companion object {
        const val CHANNEL_ID   = "call_automation_v3"
        const val NOTIFICATION_ID = 19791203

        // LocalBroadcast action constants (received by MainActivity)
        const val ACTION_STATUS_UPDATE = "com.optimatrix.gsmcall.ACTION_STATUS_UPDATE"
        const val ACTION_LOG_UPDATE    = "com.optimatrix.gsmcall.ACTION_LOG_UPDATE"
        const val ACTION_WS_UPDATE     = "com.optimatrix.gsmcall.ACTION_WS_UPDATE"
        const val EXTRA_CURRENT_STATUS = "extra_current_status"
        const val EXTRA_LOG_MESSAGE    = "extra_log_message"
        const val EXTRA_WS_JSON        = "extra_ws_json"

        // Service control actions
        const val ACTION_START = "com.optimatrix.gsmcall.START_AUTOMATION"
        const val ACTION_STOP  = "com.optimatrix.gsmcall.STOP_AUTOMATION"

        // Timing
        private const val CALL_ANSWER_SETTLE_MS    = 1_200L
        private const val RECORDING_DURATION_SEC   = 18
        private const val WAKELOCK_TIMEOUT_MS       = 5 * 60 * 1_000L   // 5 min
        private const val WATCHDOG_INTERVAL_MS      = 30_000L

        fun startService(context: Context) {
            val i = Intent(context, CallAutomationService::class.java).apply { action = ACTION_START }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stopService(context: Context) {
            context.startService(Intent(context, CallAutomationService::class.java).apply { action = ACTION_STOP })
        }
    }

    // ── Dependencies ──────────────────────────────────────────────────────────
    private val binder          = LocalBinder()
    private val serviceScope    = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var telephonyController : TelephonyController
    private lateinit var audioRoutingManager : AudioRoutingManager
    private lateinit var callAudioPlayer     : CallAudioPlayer
    private lateinit var recordingManager    : RecordingManager
    private lateinit var apiClient           : ApiClient
    private lateinit var powerManager        : PowerManager
    private lateinit var wakeLock            : PowerManager.WakeLock
    private lateinit var notificationManager : NotificationManager

    // ── State ─────────────────────────────────────────────────────────────────
    private val sessionRunning      = AtomicBoolean(false)
    private val flowCounter         = AtomicInteger(0)
    private var socketManager       : SocketManager? = null
    private var watchdogJob         : Job? = null
    private var lastSession         : CallSession? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        LogStore.log("Service", "onCreate")

        notificationManager = getSystemService(NotificationManager::class.java)
        powerManager        = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock            = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GSMCallAI::Wake")

        audioRoutingManager = AudioRoutingManager(applicationContext)
        callAudioPlayer     = CallAudioPlayer(applicationContext)
        recordingManager    = RecordingManager(applicationContext)
        apiClient           = ApiClient(applicationContext)

        telephonyController = TelephonyController(applicationContext, this)
        telephonyController.startListening()

        createNotificationChannel()
        connectWebSocket()
        startWatchdog()

        broadcastStatus("initialized")
        LogStore.log("Service", "Ready — monitoring calls")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LogStore.log("Service", "onStartCommand action=${intent?.action}")
        return when (intent?.action) {
            ACTION_STOP -> { stopSelf(); START_NOT_STICKY }
            else        -> { startForeground("Monitoring GSM calls…"); START_STICKY }
        }
    }

    override fun onDestroy() {
        LogStore.log("Service", "onDestroy")
        serviceScope.cancel()
        watchdogJob?.cancel()
        telephonyController.stopListening()
        audioRoutingManager.restoreAudioMode()
        callAudioPlayer.release()
        socketManager?.close()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder
    inner class LocalBinder : Binder() { fun getService() = this@CallAutomationService }

    // ─────────────────────────────────────────────────────────────────────────
    // TelephonyController.Listener
    // ─────────────────────────────────────────────────────────────────────────

    override fun onSessionUpdated(session: CallSession) {
        LogStore.log("Telephony", "Phase=${session.phase} out=${session.outgoing} num=${session.remoteNumber}")
        lastSession = session
        val phase = session.phase.name.lowercase()
        broadcastStatus(phase)

        // Tell backend about the state change
        socketManager?.sendCallState(phase, session.remoteNumber)

        when (session.phase) {
            CallSession.Phase.CONNECTED -> {
                if (sessionRunning.compareAndSet(false, true)) {
                    LogStore.log("Telephony", "CONNECTED → launching flow #${flowCounter.incrementAndGet()}")
                    serviceScope.launch { runAutomationFlow(session) }
                }
            }
            CallSession.Phase.ENDED -> {
                sessionRunning.set(false)
                audioRoutingManager.restoreAudioMode()
                releaseWakeLock()
                broadcastStatus("call_ended")
                updateNotification("Call ended — monitoring…")
            }
            CallSession.Phase.RINGING -> {
                broadcastLog("Ringing…")
                updateNotification("Outgoing call ringing…")
                socketManager?.let {
                    it.sendCallState("ringing", session.remoteNumber)
                }
            }
            CallSession.Phase.DIALING -> {
                broadcastLog("Dialing…")
                updateNotification("Dialing customer…")
            }
            else -> Unit
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Core automation flow
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun runAutomationFlow(session: CallSession) {
        val fid = flowCounter.get()
        LogStore.log("Flow", "[$fid] START number=${session.remoteNumber}")
        withContext(Dispatchers.Main) { acquireWakeLock() }

        try {
            // ── Stabilize ─────────────────────────────────────────────────
            broadcastStatus("call_connected")
            broadcastLog("[$fid] Connected — stabilizing ${CALL_ANSWER_SETTLE_MS}ms…")
            socketManager?.sendCallState("connected", session.remoteNumber, fid)
            delay(CALL_ANSWER_SETTLE_MS)

            // ── Audio routing ─────────────────────────────────────────────
            broadcastStatus("routing_audio")
            broadcastLog("[$fid] Setting up audio routing…")
            withContext(Dispatchers.Main) { audioRoutingManager.prepareInCallRouting() }
            broadcastStatus("routing_ready")
            broadcastLog("[$fid] Audio routing ready")

            // ── Get greeting ──────────────────────────────────────────────
            val greeting = withContext(Dispatchers.IO) { callAudioPlayer.getAudioFile("greeting.wav") }
            if (greeting == null) {
                val msg = "[$fid] greeting.wav not found — aborting flow"
                LogStore.log("Flow", msg)
                broadcastLog(msg)
                broadcastStatus("error_missing_audio")
                socketManager?.sendCallState("error_missing_audio", session.remoteNumber, fid)
                endCallSafely()
                return
            }
            LogStore.log("Flow", "[$fid] greeting.wav: ${greeting.length()} bytes")

            // ── Play greeting ─────────────────────────────────────────────
            broadcastStatus("playing_greeting")
            broadcastLog("[$fid] Playing greeting.wav…")
            socketManager?.sendPlaybackResult("greeting.wav", "starting", true)
            updateNotification("Playing greeting…")

            val greetingOk = withContext(Dispatchers.IO) {
                callAudioPlayer.playAudioFile(greeting, AudioManager.STREAM_VOICE_CALL)
            }

            LogStore.log("Flow", "[$fid] Greeting result: ok=$greetingOk")
            socketManager?.sendPlaybackResult("greeting.wav", "all_strategies", greetingOk)

            if (!greetingOk) {
                broadcastLog("[$fid] Primary playback failed — applying speaker fallback")
                withContext(Dispatchers.Main) { audioRoutingManager.applyFallbackRouting() }
                delay(200)
                withContext(Dispatchers.IO) {
                    callAudioPlayer.playAudioFile(greeting, AudioManager.STREAM_MUSIC)
                }
                socketManager?.sendPlaybackResult("greeting.wav", "speaker_fallback", true)
            }

            broadcastStatus("greeting_played")
            broadcastLog("[$fid] Greeting played — recording customer response…")

            // ── Record ────────────────────────────────────────────────────
            broadcastStatus("recording")
            updateNotification("Recording customer response…")
            socketManager?.sendCallState("recording", session.remoteNumber, fid)
            LogStore.log("Flow", "[$fid] Recording ${RECORDING_DURATION_SEC}s")

            val responseFile = withContext(Dispatchers.IO) {
                recordingManager.recordResponse(RECORDING_DURATION_SEC)
            }

            if (responseFile == null) {
                val msg = "[$fid] Recording failed"
                LogStore.log("Flow", msg)
                broadcastLog(msg)
                broadcastStatus("recording_failed")
                socketManager?.sendCallState("recording_failed", session.remoteNumber, fid)
                endCallSafely()
                return
            }

            LogStore.log("Flow", "[$fid] Recording saved: ${responseFile.name} ${responseFile.length()} bytes")
            broadcastLog("[$fid] Recording: ${responseFile.name}")
            broadcastStatus("uploading")
            updateNotification("Transcribing response…")
            socketManager?.sendRecordingSaved(responseFile.absolutePath, RECORDING_DURATION_SEC)

            // ── Upload + transcribe ───────────────────────────────────────
            broadcastLog("[$fid] Uploading to backend for transcription…")
            val uploadResult = withContext(Dispatchers.IO) {
                apiClient.uploadRecording(responseFile, session)
            }

            val intentStr = uploadResult.intent.name   // "YES", "NO", "UNKNOWN"
            val transcript = uploadResult.transcription ?: ""
            LogStore.log("Flow", "[$fid] Intent=$intentStr transcription=\"${transcript.take(80)}\"")
            broadcastLog("[$fid] Intent: $intentStr | ${transcript.take(120)}")
            broadcastStatus("transcribed_$intentStr")

            if (!uploadResult.success && responseFile.exists()) {
                enqueueUploadRetry(responseFile.absolutePath, session.remoteNumber ?: "")
            }

            // WebSocket broadcast of full result
            socketManager?.sendEvent(
                buildJsonEvent("call_result", mapOf(
                    "intent"        to intentStr,
                    "transcription" to transcript.take(200),
                    "phone"         to (session.remoteNumber ?: ""),
                    "flowId"        to fid,
                    "success"       to uploadResult.success,
                ))
            )

            // ── Post-intent action ────────────────────────────────────────
            when (uploadResult.intent) {
                ApiClient.CallerIntent.YES -> {
                    broadcastStatus("playing_thank_you")
                    updateNotification("YES — playing response…")
                    broadcastLog("[$fid] YES — playing thank_you.wav")
                    val thankYou = withContext(Dispatchers.IO) { callAudioPlayer.getAudioFile("thank_you.wav") }
                    if (thankYou != null) {
                        withContext(Dispatchers.IO) {
                            callAudioPlayer.playAudioFile(thankYou, AudioManager.STREAM_VOICE_CALL)
                        }
                    }
                }
                ApiClient.CallerIntent.NO -> {
                    broadcastLog("[$fid] NO — ending call")
                }
                ApiClient.CallerIntent.UNKNOWN -> {
                    broadcastLog("[$fid] UNKNOWN intent — ending call")
                }
            }

            // ── End call ──────────────────────────────────────────────────
            broadcastStatus("ending_call")
            broadcastLog("[$fid] Ending call")
            delay(400)
            endCallSafely()

            broadcastStatus("completed")
            updateNotification("Call complete — monitoring…")
            broadcastLog("[$fid] FLOW COMPLETE ✓")
            LogStore.log("Flow", "[$fid] COMPLETE")

        } catch (ex: Exception) {
            LogStore.log("Flow", "[$fid] EXCEPTION: ${ex.javaClass.simpleName}: ${ex.message}")
            broadcastLog("[$fid] ERROR: ${ex.message}")
            broadcastStatus("error")
            socketManager?.sendCallState("error", session.remoteNumber, fid)
            endCallSafely()
        } finally {
            sessionRunning.set(false)
            withContext(Dispatchers.Main) {
                audioRoutingManager.restoreAudioMode()
                releaseWakeLock()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WebSocket
    // ─────────────────────────────────────────────────────────────────────────

    private fun connectWebSocket() {
        val wsUrl = NetworkConfig.wsUrl
        LogStore.log("Service", "WebSocket → $wsUrl")
        try {
            socketManager = SocketManager(wsUrl).apply {
                setOnMessage { json -> broadcastWebSocket(json) }
                connect()
            }
        } catch (ex: Exception) {
            LogStore.log("Service", "WebSocket init failed: ${ex.message}")
        }
    }

    private fun broadcastWebSocket(json: String) {
        sendBroadcast(
            Intent(ACTION_WS_UPDATE).putExtra(EXTRA_WS_JSON, json)
        )
    }

    private fun enqueueUploadRetry(filePath: String, remoteNumber: String) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(
                workDataOf(
                    SyncWorker.KEY_RECORDING_PATH to filePath,
                    "key_remote_number" to remoteNumber,
                )
            )
            .build()
        WorkManager.getInstance(applicationContext).enqueue(request)
        LogStore.log("Service", "Enqueued SyncWorker retry for $filePath")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Watchdog
    // ─────────────────────────────────────────────────────────────────────────

    private fun startWatchdog() {
        watchdogJob = serviceScope.launch {
            while (true) {
                delay(WATCHDOG_INTERVAL_MS)
                val wsOk = socketManager?.connected == true
                LogStore.log("Watchdog", "alive sessionRunning=${sessionRunning.get()} ws=$wsOk wl=${wakeLock.isHeld}")

                if (!wsOk) {
                    LogStore.log("Watchdog", "WebSocket dead — reconnecting")
                    try {
                        socketManager?.close()
                        connectWebSocket()
                    } catch (_: Exception) {}
                } else {
                    socketManager?.sendWatchdog()
                }

                // Broadcast backend status to UI
                broadcastStatus(if (wsOk) "ws_connected" else "ws_disconnected")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun endCallSafely() {
        try { telephonyController.endCall() } catch (_: Exception) {}
    }

    private fun buildJsonEvent(event: String, fields: Map<String, Any?>): String {
        val sb = StringBuilder("{\"event\":\"$event\"")
        fields.forEach { (k, v) ->
            sb.append(",\"$k\":")
            when (v) {
                is String  -> sb.append("\"${v.replace("\"", "\\\"")}\"")
                is Boolean -> sb.append(v)
                is Number  -> sb.append(v)
                null       -> sb.append("null")
                else       -> sb.append("\"$v\"")
            }
        }
        sb.append(",\"ts\":${System.currentTimeMillis()}}")
        return sb.toString()
    }

    private fun acquireWakeLock() {
        if (!wakeLock.isHeld) {
            wakeLock.acquire(WAKELOCK_TIMEOUT_MS)
            LogStore.log("Service", "WakeLock acquired")
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock.isHeld) { wakeLock.release(); LogStore.log("Service", "WakeLock released") }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notification
    // ─────────────────────────────────────────────────────────────────────────

    private fun startForeground(content: String) {
        startForeground(NOTIFICATION_ID, buildNotification(content))
    }

    private fun updateNotification(content: String) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(content))
    }

    private fun buildNotification(content: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GSM Call AI")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "GSM Call AI", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "GSM call automation monitoring"
                    setShowBadge(false)
                }
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Broadcast to MainActivity
    // ─────────────────────────────────────────────────────────────────────────

    private fun broadcastStatus(status: String) {
        sendBroadcast(Intent(ACTION_STATUS_UPDATE).putExtra(EXTRA_CURRENT_STATUS, status))
    }

    private fun broadcastLog(message: String) {
        LogStore.log("Broadcast", message)
        val ts = System.currentTimeMillis() % 100_000
        sendBroadcast(Intent(ACTION_LOG_UPDATE).putExtra(EXTRA_LOG_MESSAGE, "[$ts] $message"))
    }
}
