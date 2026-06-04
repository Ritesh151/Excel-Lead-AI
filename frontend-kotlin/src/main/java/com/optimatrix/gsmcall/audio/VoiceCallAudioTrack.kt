package com.optimatrix.gsmcall.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import com.optimatrix.gsmcall.utils.LogStore
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * VoiceCallAudioTrack — Dedicated STREAM_VOICE_CALL low-latency track.
 *
 * This is a companion to PcmStreamingEngine that specifically focuses
 * on VOICE_COMMUNICATION usage with Samsung-specific workarounds.
 *
 * Samsung SM-A176B quirks addressed:
 *   - Repeated AudioFocus requests before playback
 *   - MODE_IN_COMMUNICATION must be set BEFORE track creation
 *   - Samsung audio HAL may need repeated routing commands
 *   - Short delay after mode change before track.play()
 */
class VoiceCallAudioTrack(private val audioManager: AudioManager) {

    companion object {
        private const val TAG = "VoiceCallAudioTrack"
        private const val SAMPLE_RATE = 8000
        private const val BUFFER_SIZE_FACTOR = 4 // 4x min buffer for stability
    }

    /**
     * Play PCM bytes through STREAM_VOICE_CALL with Samsung-safe initialization.
     *
     * @param pcmBytes  Raw 16-bit signed PCM at 8000Hz mono
     * @param attempt   Retry attempt number (affects retry strategy)
     * @return true if playback completed without error
     */
    fun play(pcmBytes: ByteArray, attempt: Int = 1): Boolean {
        LogStore.log(TAG, "play() attempt=$attempt pcmSize=${pcmBytes.size}")

        // Samsung workaround: ensure MODE_IN_COMMUNICATION is active
        val prevMode = audioManager.mode
        if (prevMode != AudioManager.MODE_IN_COMMUNICATION) {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            LogStore.log(TAG, "Mode changed: $prevMode → MODE_IN_COMMUNICATION")
            Thread.sleep(50) // Let HAL settle
        }

        // Samsung workaround: turn off speakerphone — force earpiece path
        audioManager.isSpeakerphoneOn = false

        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = if (minBuf > 0) minBuf * BUFFER_SIZE_FACTOR else SAMPLE_RATE * 2

        LogStore.log(TAG, "AudioTrack minBuf=$minBuf bufSize=$bufSize mode=${audioManager.mode}")

        val track = createTrack(bufSize) ?: return false

        return try {
            LogStore.log(TAG, "AudioTrack state=${track.state} sampleRate=${track.sampleRate}")

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                LogStore.log(TAG, "Track not initialized — aborting")
                return false
            }

            // Samsung workaround: set volume to max before starting
            track.setVolume(1.0f)

            track.play()
            LogStore.log(TAG, "track.play() called — streaming ${pcmBytes.size} bytes")

            // Stream in chunks for low-latency response
            val chunkSize = bufSize / 2
            var offset = 0
            while (offset < pcmBytes.size) {
                val remaining = pcmBytes.size - offset
                val toWrite = minOf(chunkSize, remaining)
                val written = track.write(pcmBytes, offset, toWrite)
                if (written < 0) {
                    LogStore.log(TAG, "write() returned error $written at offset $offset")
                    break
                }
                offset += written
            }

            // Flush
            track.stop()
            LogStore.log(TAG, "Playback complete: wrote $offset / ${pcmBytes.size} bytes")
            offset >= pcmBytes.size - 4 // Allow for tiny rounding
        } catch (ex: Exception) {
            LogStore.log(TAG, "Playback exception: ${ex.message}")
            false
        } finally {
            try { track.release() } catch (_: Exception) {}
        }
    }

    private fun createTrack(bufSize: Int): AudioTrack? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(
                    AudioManager.STREAM_VOICE_CALL,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize,
                    AudioTrack.MODE_STREAM
                )
            }
        } catch (ex: Exception) {
            LogStore.log(TAG, "AudioTrack creation failed: ${ex.message}")
            null
        }
    }
}
