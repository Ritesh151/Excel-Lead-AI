package com.optimatrix.gsmcall.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import com.optimatrix.gsmcall.utils.LogStore

/**
 * SamsungAudioWorkarounds — Samsung SM-A176B specific audio hacks.
 *
 * Samsung Android 14/15 (One UI 6/7) has aggressive audio policy enforcement.
 * These workarounds attempt to bypass or work with Samsung's audio HAL
 * to maximize probability of audio routing into the GSM uplink path.
 *
 * Techniques:
 *   1. Aggressive repeated AudioFocus requests
 *   2. MODE_IN_COMMUNICATION cycling
 *   3. Speakerphone toggle trick
 *   4. Bluetooth SCO routing attempt
 *   5. Multiple requestAudioFocus retries
 *   6. Volume boost on all voice streams
 *   7. AudioFocus type cycling
 */
class SamsungAudioWorkarounds(private val context: Context) {

    companion object {
        private const val TAG = "SamsungWorkarounds"
        private const val SETTLE_MS = 30L
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequests = mutableListOf<AudioFocusRequest>()
    private var hadFocus = false

    // ── Full Samsung preparation sequence ─────────────────────────────────────

    /**
     * Execute full Samsung preparation sequence before audio playback.
     * Call this right before starting AudioTrack playback.
     */
    fun prepareForVoicePlayback() {
        LogStore.log(TAG, "=== Samsung Audio Preparation BEGIN ===")
        logAudioState("BEFORE")

        step1_setModeCommunication()
        step2_requestAudioFocusAggressive()
        step3_volumeBoost()
        step4_disableSpeakerphone()
        step5_bluetoothScoAttempt()
        step6_repeatedModeForcing()

        logAudioState("AFTER")
        LogStore.log(TAG, "=== Samsung Audio Preparation COMPLETE ===")
    }

    /**
     * Restore audio state after playback.
     */
    fun restoreAfterPlayback(originalMode: Int) {
        LogStore.log(TAG, "Restoring audio state originalMode=$originalMode")
        try {
            audioManager.stopBluetoothSco()
            audioManager.isBluetoothScoOn = false
            audioManager.isSpeakerphoneOn = false
            audioManager.mode = originalMode

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequests.forEach { req ->
                    try { audioManager.abandonAudioFocusRequest(req) } catch (_: Exception) {}
                }
                focusRequests.clear()
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
            LogStore.log(TAG, "Audio state restored")
        } catch (ex: Exception) {
            LogStore.log(TAG, "Restore exception: ${ex.message}")
        }
    }

    // ── Step 1: Set MODE_IN_COMMUNICATION ────────────────────────────────────

    private fun step1_setModeCommunication() {
        try {
            val current = audioManager.mode
            LogStore.log(TAG, "[Step1] Current mode=$current setting MODE_IN_COMMUNICATION")
            if (current != AudioManager.MODE_IN_COMMUNICATION) {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                Thread.sleep(SETTLE_MS)
                LogStore.log(TAG, "[Step1] Mode set. New mode=${audioManager.mode}")
            } else {
                LogStore.log(TAG, "[Step1] Already in MODE_IN_COMMUNICATION")
            }
        } catch (ex: Exception) {
            LogStore.log(TAG, "[Step1] Exception: ${ex.message}")
        }
    }

    // ── Step 2: Aggressive repeated AudioFocus requests ──────────────────────

    private fun step2_requestAudioFocusAggressive() {
        LogStore.log(TAG, "[Step2] Requesting AudioFocus (GAIN_TRANSIENT x3)")
        repeat(3) { i ->
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
                    focusRequests.add(req)
                    hadFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                    LogStore.log(TAG, "[Step2] Focus request ${i+1}: result=$result granted=$hadFocus")
                } else {
                    @Suppress("DEPRECATION")
                    val result = audioManager.requestAudioFocus(
                        null,
                        AudioManager.STREAM_VOICE_CALL,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                    )
                    hadFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                    LogStore.log(TAG, "[Step2] Legacy focus ${i+1}: result=$result")
                }
                Thread.sleep(20L)
            } catch (ex: Exception) {
                LogStore.log(TAG, "[Step2] Exception attempt ${i+1}: ${ex.message}")
            }
        }
    }

    // ── Step 3: Volume boost ──────────────────────────────────────────────────

    private fun step3_volumeBoost() {
        LogStore.log(TAG, "[Step3] Boosting voice call volumes")
        try {
            val streams = listOf(
                AudioManager.STREAM_VOICE_CALL to "VOICE_CALL",
                AudioManager.STREAM_MUSIC to "MUSIC",
                AudioManager.STREAM_RING to "RING"
            )
            streams.forEach { (stream, name) ->
                try {
                    val max = audioManager.getStreamMaxVolume(stream)
                    audioManager.setStreamVolume(stream, max, 0)
                    val actual = audioManager.getStreamVolume(stream)
                    LogStore.log(TAG, "[Step3] $name volume: max=$max set=$actual")
                } catch (ex: Exception) {
                    LogStore.log(TAG, "[Step3] Volume $name failed: ${ex.message}")
                }
            }
        } catch (ex: Exception) {
            LogStore.log(TAG, "[Step3] Exception: ${ex.message}")
        }
    }

    // ── Step 4: Disable speakerphone ──────────────────────────────────────────

    private fun step4_disableSpeakerphone() {
        LogStore.log(TAG, "[Step4] Disabling speakerphone (route to earpiece)")
        try {
            audioManager.isSpeakerphoneOn = false
            Thread.sleep(SETTLE_MS)
            LogStore.log(TAG, "[Step4] speakerphoneOn=${audioManager.isSpeakerphoneOn}")
        } catch (ex: Exception) {
            LogStore.log(TAG, "[Step4] Exception: ${ex.message}")
        }
    }

    // ── Step 5: Bluetooth SCO routing attempt ─────────────────────────────────

    private fun step5_bluetoothScoAttempt() {
        LogStore.log(TAG, "[Step5] Attempting Bluetooth SCO routing")
        try {
            audioManager.startBluetoothSco()
            Thread.sleep(SETTLE_MS)
            val scoOn = audioManager.isBluetoothScoOn
            LogStore.log(TAG, "[Step5] BluetoothSCO started scoOn=$scoOn")
            // If SCO not available, fall back immediately
            if (!scoOn) {
                audioManager.stopBluetoothSco()
                LogStore.log(TAG, "[Step5] SCO not available — stopped")
            }
        } catch (ex: Exception) {
            LogStore.log(TAG, "[Step5] SCO exception: ${ex.message}")
        }
    }

    // ── Step 6: Repeated MODE_IN_COMMUNICATION forcing ────────────────────────

    private fun step6_repeatedModeForcing() {
        LogStore.log(TAG, "[Step6] Repeated mode stabilization")
        repeat(3) { i ->
            try {
                if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    LogStore.log(TAG, "[Step6] Re-forced MODE_IN_COMMUNICATION attempt ${i+1}")
                    Thread.sleep(20L)
                }
            } catch (ex: Exception) {
                LogStore.log(TAG, "[Step6] Exception ${i+1}: ${ex.message}")
            }
        }
    }

    // ── Diagnostic logging ────────────────────────────────────────────────────

    fun logAudioState(label: String) {
        try {
            val mode = when (audioManager.mode) {
                AudioManager.MODE_NORMAL -> "NORMAL"
                AudioManager.MODE_RINGTONE -> "RINGTONE"
                AudioManager.MODE_IN_CALL -> "IN_CALL"
                AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
                else -> "UNKNOWN(${audioManager.mode})"
            }
            val voiceVol = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            val voiceMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            val musicVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val musicMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val speaker = audioManager.isSpeakerphoneOn
            val btSco = audioManager.isBluetoothScoOn

            LogStore.log(TAG, "[$label] AudioState: mode=$mode " +
                    "voiceVol=$voiceVol/$voiceMax " +
                    "musicVol=$musicVol/$musicMax " +
                    "speaker=$speaker btSco=$btSco")
        } catch (ex: Exception) {
            LogStore.log(TAG, "[$label] logAudioState exception: ${ex.message}")
        }
    }

    /**
     * Quick test: is audio currently in a call-routing state?
     */
    fun isInCallMode(): Boolean {
        return audioManager.mode == AudioManager.MODE_IN_CALL ||
                audioManager.mode == AudioManager.MODE_IN_COMMUNICATION
    }
}
