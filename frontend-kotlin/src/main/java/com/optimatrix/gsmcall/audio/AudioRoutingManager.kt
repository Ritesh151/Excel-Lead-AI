package com.optimatrix.gsmcall.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import com.optimatrix.gsmcall.utils.LogStore

/**
 * AudioRoutingManager — Complete in-call audio routing orchestrator.
 *
 * UPGRADE: Integrates SamsungAudioWorkarounds and provides aggressive
 * multi-mode routing setup for Samsung SM-A176B Android 14/15.
 *
 * Call sequence:
 *   1. prepareInCallRouting()     → before playback
 *   2. applyFallbackRouting()     → if primary fails
 *   3. restoreAudioMode()         → after call ends
 */
class AudioRoutingManager(private val context: Context) {

    companion object {
        private const val TAG = "AudioRoutingManager"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val samsungWorkarounds = SamsungAudioWorkarounds(context)
    private var originalMode = AudioManager.MODE_NORMAL
    private var focusRequest: AudioFocusRequest? = null
    private var hadFocus = false
    private var isRouted = false


    // ── Primary routing setup ─────────────────────────────────────────────────

    fun prepareInCallRouting() {
        LogStore.log(TAG, "=== prepareInCallRouting() START ===")
        samsungWorkarounds.logAudioState("PRE_ROUTING")

        try {
            originalMode = audioManager.mode
            LogStore.log(TAG, "Original mode saved: $originalMode")

            // Set MODE_IN_COMMUNICATION immediately
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            Thread.sleep(30)

            // Request AudioFocus — AUDIOFOCUS_GAIN_TRANSIENT for in-call usage
            requestAudioFocusCompat()

            // Samsung: force mode again after focus
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

            // Disable speakerphone — route to earpiece/in-call path
            audioManager.isSpeakerphoneOn = false

            // Try Bluetooth SCO (if BT headset connected, route through it)
            attemptBluetoothSco()

            // Boost voice volume
            boostVoiceVolume()

            isRouted = true
            samsungWorkarounds.logAudioState("POST_ROUTING")
            LogStore.log(TAG, "=== prepareInCallRouting() COMPLETE ===")

        } catch (ex: Exception) {
            LogStore.log(TAG, "prepareInCallRouting EXCEPTION: ${ex.message}")
        }
    }

    // ── Fallback routing ──────────────────────────────────────────────────────

    fun applyFallbackRouting() {
        LogStore.log(TAG, "applyFallbackRouting() — speakerphone ON as last resort")
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = true  // last resort: speaker so phone mic picks up
            Thread.sleep(30)
            samsungWorkarounds.logAudioState("FALLBACK_ROUTING")
        } catch (ex: Exception) {
            LogStore.log(TAG, "applyFallbackRouting EXCEPTION: ${ex.message}")
        }
    }

    // ── Restore ───────────────────────────────────────────────────────────────

    fun restoreAudioMode() {
        LogStore.log(TAG, "restoreAudioMode() originalMode=$originalMode")
        try {
            audioManager.isSpeakerphoneOn = false
            audioManager.mode = originalMode
            audioManager.stopBluetoothSco()
            audioManager.isBluetoothScoOn = false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let {
                    audioManager.abandonAudioFocusRequest(it)
                }
                focusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }

            isRouted = false
            LogStore.log(TAG, "Audio mode restored to $originalMode")
        } catch (ex: Exception) {
            LogStore.log(TAG, "restoreAudioMode EXCEPTION: ${ex.message}")
        }
    }

    // ── Diagnostic info ───────────────────────────────────────────────────────

    fun logCurrentState(label: String = "CURRENT") = samsungWorkarounds.logAudioState(label)

    fun isInCallMode() = samsungWorkarounds.isInCallMode()

    val isRouteReady get() = isRouted

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun requestAudioFocusCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attrs)
                    .setAcceptsDelayedFocusGain(false)
                    .setWillPauseWhenDucked(false)
                    .build()
                val result = audioManager.requestAudioFocus(req)
                focusRequest = req
                hadFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                LogStore.log(TAG, "AudioFocus requested: result=$result granted=$hadFocus")
            } else {
                @Suppress("DEPRECATION")
                val result = audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
                hadFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                LogStore.log(TAG, "AudioFocus legacy: result=$result")
            }
        } catch (ex: Exception) {
            LogStore.log(TAG, "requestAudioFocus exception: ${ex.message}")
        }
    }

    private fun attemptBluetoothSco() {
        try {
            audioManager.startBluetoothSco()
            Thread.sleep(50)
            val on = audioManager.isBluetoothScoOn
            LogStore.log(TAG, "BluetoothSCO started: isOn=$on")
            if (!on) {
                audioManager.stopBluetoothSco()
                LogStore.log(TAG, "BluetoothSCO not available — stopped")
            }
        } catch (ex: Exception) {
            LogStore.log(TAG, "BluetoothSCO exception: ${ex.message}")
        }
    }

    private fun boostVoiceVolume() {
        try {
            val maxVoice = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVoice, 0)
            val maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic, 0)
            LogStore.log(TAG, "Voice volume boosted: VOICE_CALL=$maxVoice MUSIC=$maxMusic")
        } catch (ex: Exception) {
            LogStore.log(TAG, "boostVoiceVolume exception: ${ex.message}")
        }
    }
}
