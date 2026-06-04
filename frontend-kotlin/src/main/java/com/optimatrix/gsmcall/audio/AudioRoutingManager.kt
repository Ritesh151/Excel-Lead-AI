package com.optimatrix.gsmcall.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import com.optimatrix.gsmcall.utils.LogStore

class AudioRoutingManager(private val context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var originalMode = audioManager.mode
    private var focusRequest: AudioFocusRequest? = null
    private var hadFocus = false

    fun prepareInCallRouting() {
        try {
            originalMode = audioManager.mode
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attributes)
                    .setAcceptsDelayedFocusGain(true)
                    .setWillPauseWhenDucked(false)
                    .build()
                val result = audioManager.requestAudioFocus(focusRequest!!)
                hadFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                val result = audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                hadFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = false
            audioManager.isBluetoothScoOn = true
            if (!audioManager.isBluetoothScoOn) {
                audioManager.startBluetoothSco()
            }
            LogStore.log("AudioRouting", "Prepared in-call routing mode=${audioManager.mode} speaker=${audioManager.isSpeakerphoneOn} sco=${audioManager.isBluetoothScoOn} focus=$hadFocus")
        } catch (ex: Exception) {
            LogStore.log("AudioRouting", "Failed prepareInCallRouting: ${ex.message}")
        }
    }

    fun applyFallbackRouting() {
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = true
            audioManager.isBluetoothScoOn = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
                audioManager.abandonAudioFocusRequest(focusRequest!!)
            }
            LogStore.log("AudioRouting", "Fallback routing applied speakerphoneOn=true mode=${audioManager.mode}")
        } catch (ex: Exception) {
            LogStore.log("AudioRouting", "Failed fallback routing: ${ex.message}")
        }
    }

    fun restoreAudioMode() {
        try {
            audioManager.mode = originalMode
            audioManager.isSpeakerphoneOn = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
                audioManager.abandonAudioFocusRequest(focusRequest!!)
            }
            audioManager.stopBluetoothSco()
            audioManager.isBluetoothScoOn = false
            LogStore.log("AudioRouting", "Audio mode restored mode=${audioManager.mode}")
        } catch (ex: Exception) {
            LogStore.log("AudioRouting", "Failed restoreAudioMode: ${ex.message}")
        }
    }
}
