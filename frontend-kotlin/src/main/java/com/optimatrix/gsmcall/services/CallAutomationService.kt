package com.optimatrix.gsmcall.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.optimatrix.gsmcall.api.ApiClient
import com.optimatrix.gsmcall.audio.AudioRoutingManager
import com.optimatrix.gsmcall.audio.CallAudioPlayer
import com.optimatrix.gsmcall.recording.RecordingManager
import com.optimatrix.gsmcall.telephony.CallSession
import com.optimatrix.gsmcall.telephony.CallSessionTracker
import com.optimatrix.gsmcall.telephony.TelephonyController
import com.optimatrix.gsmcall.utils.LogStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class CallAutomationService : Service(), CallSessionTracker.Listener {
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Job() + Dispatchers.Default)
    private lateinit var telephonyController: TelephonyController
    private lateinit var audioRoutingManager: AudioRoutingManager
    private lateinit var callAudioPlayer: CallAudioPlayer
    private lateinit var recordingManager: RecordingManager
    private lateinit var apiClient: ApiClient
    private lateinit var powerManager: PowerManager
    private lateinit var wakeLock: PowerManager.WakeLock
    private var sessionRunning = AtomicBoolean(false)

    companion object {
        const val CHANNEL_ID = "call_automation_channel"
        const val NOTIFICATION_ID = 19791203
        const val ACTION_STATUS_UPDATE = "com.optimatrix.gsmcall.ACTION_STATUS_UPDATE"
        const val ACTION_LOG_UPDATE = "com.optimatrix.gsmcall.ACTION_LOG_UPDATE"
        const val EXTRA_CURRENT_STATUS = "extra_current_status"
        const val EXTRA_LOG_MESSAGE = "extra_log_message"
        const val ACTION_START = "com.optimatrix.gsmcall.START_AUTOMATION"
        const val ACTION_STOP = "com.optimatrix.gsmcall.STOP_AUTOMATION"

        fun startService(context: Context) {
            val intent = Intent(context, CallAutomationService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, CallAutomationService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioRoutingManager = AudioRoutingManager(applicationContext)
        callAudioPlayer = CallAudioPlayer(applicationContext)
        recordingManager = RecordingManager(applicationContext)
        apiClient = ApiClient(applicationContext)
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GSMCallAutomation:WakeLock")
        telephonyController = TelephonyController(applicationContext, this)
        telephonyController.startListening()
        createNotificationChannel()
        LogStore.log("Service", "CallAutomationService created")
        broadcastStatus("initialized")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startForegroundServiceWithNotification()
            ACTION_STOP -> stopSelf()
            else -> startForegroundServiceWithNotification()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        telephonyController.stopListening()
        audioRoutingManager.restoreAudioMode()
        callAudioPlayer.release()
        if (wakeLock.isHeld) wakeLock.release()
        LogStore.log("Service", "CallAutomationService destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    inner class LocalBinder : Binder() {
        fun getService(): CallAutomationService = this@CallAutomationService
    }

    override fun onSessionUpdated(session: CallSession) {
        LogStore.log("Telephony", "Session updated ${session.phase} outgoing=${session.outgoing}")
        broadcastStatus(session.phase.name.lowercase())
        if (session.phase == CallSession.Phase.CONNECTED) {
            if (sessionRunning.compareAndSet(false, true)) {
                serviceScope.launch { runAutomationFlow(session) }
            }
        }
        if (session.phase == CallSession.Phase.ENDED) {
            sessionRunning.set(false)
            audioRoutingManager.restoreAudioMode()
        }
    }

    private suspend fun runAutomationFlow(session: CallSession) {
        withContext(Dispatchers.Main) {
            if (!wakeLock.isHeld) {
                wakeLock.acquire(60_000L)
            }
        }
        broadcastStatus("call_connected")
        audioRoutingManager.prepareInCallRouting()
        broadcastStatus("routing_ready")
        LogStore.log("Automation", "Starting greeting playback")

        val greeting = callAudioPlayer.getAudioFile("greeting.wav")
        if (greeting == null) {
            LogStore.log("Automation", "Missing greeting.wav")
            broadcastLog("Missing greeting.wav")
            return
        }

        val greetingResult = callAudioPlayer.playAudioFile(greeting, streamType = AudioManager.STREAM_VOICE_CALL)
        if (!greetingResult) {
            LogStore.log("Automation", "Greeting playback failed, retrying with media stream")
            audioRoutingManager.applyFallbackRouting()
            callAudioPlayer.playAudioFile(greeting, streamType = AudioManager.STREAM_MUSIC)
        }

        broadcastStatus("recording_response")
        LogStore.log("Automation", "Recording caller response")
        val responseFile = recordingManager.recordResponse(durationSeconds = 18)
        if (responseFile == null) {
            LogStore.log("Automation", "Recording failed")
            return
        }

        broadcastStatus("uploading_recording")
        LogStore.log("Automation", "Uploading response to backend")
        val intentResult = apiClient.uploadRecording(responseFile, session)
        val callerIntent = intentResult.intent
        LogStore.log("Backend", "Transcription result: ${intentResult.intent}")

        if (callerIntent == ApiClient.CallerIntent.YES) {
            broadcastStatus("playing_thank_you")
            LogStore.log("Automation", "Playing thank_you.wav after positive response")
            val thankYou = callAudioPlayer.getAudioFile("thank_you.wav")
            if (thankYou != null) {
                callAudioPlayer.playAudioFile(thankYou, streamType = AudioManager.STREAM_VOICE_CALL)
            }
            telephonyController.endCall()
        } else {
            LogStore.log("Automation", "Ending call on negative or unclear intent")
            telephonyController.endCall()
        }

        broadcastStatus("completed")
        sessionRunning.set(false)
        audioRoutingManager.restoreAudioMode()
        broadcastLog("Automation session complete")
    }

    private fun startForegroundServiceWithNotification() {
        val notification = buildNotification("GSM automation is monitoring calls")
        startForeground(NOTIFICATION_ID, notification)
        broadcastStatus("service_running")
        LogStore.log("Service", "Foreground notification started")
    }

    private fun buildNotification(content: String): Notification {
        val notificationIntent = Intent(this, com.optimatrix.gsmcall.ui.MainActivity::class.java)
        val pendingIntent = androidx.core.app.PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) androidx.core.app.PendingIntent.FLAG_IMMUTABLE else 0
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GSM Call Automation")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "GSM call automation",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifications for live GSM automation service"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun broadcastStatus(status: String) {
        sendBroadcastIntent(ACTION_STATUS_UPDATE) {
            putExtra(EXTRA_CURRENT_STATUS, status)
        }
    }

    private fun broadcastLog(message: String) {
        LogStore.log("Broadcast", message)
        sendBroadcastIntent(ACTION_LOG_UPDATE) {
            putExtra(EXTRA_LOG_MESSAGE, message)
        }
    }

    private fun sendBroadcastIntent(action: String, extras: Intent.() -> Unit = {}) {
        val intent = Intent(action).apply(extras)
        sendBroadcast(intent)
    }
}
