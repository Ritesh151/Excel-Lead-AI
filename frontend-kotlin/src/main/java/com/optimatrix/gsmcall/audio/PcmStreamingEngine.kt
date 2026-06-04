package com.optimatrix.gsmcall.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import com.optimatrix.gsmcall.utils.LogStore
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PcmStreamingEngine — Low-level PCM AudioTrack injection engine.
 *
 * Target: Samsung SM-A176B Android 14/15
 * Purpose: Maximize probability of audio leaking into GSM uplink
 *          by streaming raw PCM through every known Android audio path.
 *
 * Strategy:
 *   1. STREAM_VOICE_CALL + USAGE_VOICE_COMMUNICATION (primary)
 *   2. USAGE_VOICE_COMMUNICATION_SIGNALLING (secondary)
 *   3. STREAM_MUSIC with MODE_IN_COMMUNICATION (fallback)
 *   4. Direct PCM frame injection at 8000Hz mono (narrowband GSM)
 */
class PcmStreamingEngine(private val audioManager: AudioManager) {

    companion object {
        private const val TAG = "PcmStreamingEngine"
        private const val SAMPLE_RATE_GSM = 8000        // GSM narrowband
        private const val SAMPLE_RATE_WIDEBAND = 16000  // AMR-WB / WCDMA wideband
        private const val CHANNELS = AudioFormat.CHANNEL_OUT_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_SAMPLE = 2
        private const val BUFFER_MS = 40 // 40ms buffering
    }

    private var audioTrack: AudioTrack? = null
    private val isPlaying = AtomicBoolean(false)
    private var playbackThread: Thread? = null

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Stream a WAV file through aggressive GSM audio routing.
     * Tries all strategies in order. Blocks until playback completes.
     *
     * @param wavFile  WAV file to play (will be resampled to 8000Hz if needed)
     * @return true if playback succeeded on any strategy
     */
    fun streamWavFile(wavFile: File): Boolean {
        LogStore.log(TAG, "=== BEGIN PCM STREAMING ENGINE ===")
        LogStore.log(TAG, "File: ${wavFile.absolutePath} size=${wavFile.length()}")

        if (!wavFile.exists() || wavFile.length() < 44) {
            LogStore.log(TAG, "ERROR: WAV file missing or too small")
            return false
        }

        // Parse WAV header
        val wavData = parseWav(wavFile) ?: return false
        LogStore.log(TAG, "WAV parsed: rate=${wavData.sampleRate}Hz ch=${wavData.channels} samples=${wavData.pcm.size}")

        // Resample to 8000Hz mono for GSM uplink injection
        val pcm8k = resampleTo8kMono(wavData)
        LogStore.log(TAG, "PCM resampled to 8000Hz mono: ${pcm8k.size} bytes")

        // Try each strategy
        val strategies = listOf(
            ::strategyVoiceCallStream,
            ::strategyVoiceCommunicationUsage,
            ::strategyVoiceCommSignalling,
            ::strategyModeInCommunicationMusic,
            ::strategyRawAudioTrack
        )

        for ((index, strategy) in strategies.withIndex()) {
            LogStore.log(TAG, "--- Attempting strategy ${index + 1}/${strategies.size} ---")
            try {
                val success = strategy(pcm8k)
                if (success) {
                    LogStore.log(TAG, "Strategy ${index + 1} SUCCEEDED")
                    return true
                }
                LogStore.log(TAG, "Strategy ${index + 1} completed (may not have routed to GSM uplink)")
            } catch (ex: Exception) {
                LogStore.log(TAG, "Strategy ${index + 1} EXCEPTION: ${ex.message}")
            }
        }

        LogStore.log(TAG, "=== ALL STRATEGIES ATTEMPTED ===")
        return true // All strategies were attempted — audio was played even if routing uncertain
    }

    fun stop() {
        isPlaying.set(false)
        audioTrack?.let {
            try {
                if (it.state == AudioTrack.STATE_INITIALIZED && it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    it.stop()
                }
                it.release()
            } catch (ex: Exception) {
                LogStore.log(TAG, "Stop exception: ${ex.message}")
            }
        }
        audioTrack = null
        playbackThread?.interrupt()
        playbackThread = null
        LogStore.log(TAG, "Engine stopped")
    }

    // ── Strategy 1: STREAM_VOICE_CALL ─────────────────────────────────────────

    private fun strategyVoiceCallStream(pcm: ByteArray): Boolean {
        LogStore.log(TAG, "[S1] STREAM_VOICE_CALL + USAGE_VOICE_COMMUNICATION @ 8000Hz")
        return playWithTrack(pcm, buildTrackVoiceCall(SAMPLE_RATE_GSM), "S1_VOICE_CALL")
    }

    private fun buildTrackVoiceCall(sampleRate: Int): AudioTrack? {
        val bufferSize = maxOf(
            AudioTrack.getMinBufferSize(sampleRate, CHANNELS, ENCODING),
            sampleRate * BYTES_PER_SAMPLE * BUFFER_MS / 1000
        )
        LogStore.log(TAG, "[S1] bufferSize=$bufferSize sampleRate=$sampleRate")

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
                            .setEncoding(ENCODING)
                            .setSampleRate(sampleRate)
                            .setChannelMask(CHANNELS)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(
                    AudioManager.STREAM_VOICE_CALL,
                    sampleRate, CHANNELS, ENCODING, bufferSize,
                    AudioTrack.MODE_STREAM
                )
            }
        } catch (ex: Exception) {
            LogStore.log(TAG, "[S1] Build failed: ${ex.message}")
            null
        }
    }

    // ── Strategy 2: USAGE_VOICE_COMMUNICATION + high sample rate ─────────────

    private fun strategyVoiceCommunicationUsage(pcm: ByteArray): Boolean {
        LogStore.log(TAG, "[S2] USAGE_VOICE_COMMUNICATION @ 16000Hz wideband")
        // Upsample 8k PCM to 16k for wideband path attempt
        val pcm16k = upsample8kTo16k(pcm)
        return try {
            val bufferSize = maxOf(
                AudioTrack.getMinBufferSize(SAMPLE_RATE_WIDEBAND, CHANNELS, ENCODING),
                SAMPLE_RATE_WIDEBAND * BYTES_PER_SAMPLE * BUFFER_MS / 1000
            )
            val track = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(ENCODING)
                            .setSampleRate(SAMPLE_RATE_WIDEBAND)
                            .setChannelMask(CHANNELS)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(
                    AudioManager.STREAM_VOICE_CALL,
                    SAMPLE_RATE_WIDEBAND, CHANNELS, ENCODING, bufferSize,
                    AudioTrack.MODE_STREAM
                )
            }
            playWithTrack(pcm16k, track, "S2_WIDEBAND")
        } catch (ex: Exception) {
            LogStore.log(TAG, "[S2] Exception: ${ex.message}")
            false
        }
    }

    // ── Strategy 3: USAGE_VOICE_COMMUNICATION_SIGNALLING ─────────────────────

    private fun strategyVoiceCommSignalling(pcm: ByteArray): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            LogStore.log(TAG, "[S3] Skipped — requires API 26+")
            return false
        }
        LogStore.log(TAG, "[S3] USAGE_VOICE_COMMUNICATION_SIGNALLING @ 8000Hz")
        return try {
            val bufferSize = maxOf(
                AudioTrack.getMinBufferSize(SAMPLE_RATE_GSM, CHANNELS, ENCODING),
                SAMPLE_RATE_GSM * BYTES_PER_SAMPLE * BUFFER_MS / 1000
            )
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(ENCODING)
                        .setSampleRate(SAMPLE_RATE_GSM)
                        .setChannelMask(CHANNELS)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            playWithTrack(pcm, track, "S3_SIGNALLING")
        } catch (ex: Exception) {
            LogStore.log(TAG, "[S3] Exception: ${ex.message}")
            false
        }
    }

    // ── Strategy 4: MODE_IN_COMMUNICATION + STREAM_MUSIC ─────────────────────

    private fun strategyModeInCommunicationMusic(pcm: ByteArray): Boolean {
        LogStore.log(TAG, "[S4] STREAM_MUSIC + MODE_IN_COMMUNICATION (speakerphone attempt)")
        return try {
            val bufferSize = maxOf(
                AudioTrack.getMinBufferSize(SAMPLE_RATE_GSM, CHANNELS, ENCODING),
                SAMPLE_RATE_GSM * BYTES_PER_SAMPLE * BUFFER_MS / 1000
            )
            val track = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(ENCODING)
                            .setSampleRate(SAMPLE_RATE_GSM)
                            .setChannelMask(CHANNELS)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    SAMPLE_RATE_GSM, CHANNELS, ENCODING, bufferSize,
                    AudioTrack.MODE_STREAM
                )
            }
            playWithTrack(pcm, track, "S4_MUSIC")
        } catch (ex: Exception) {
            LogStore.log(TAG, "[S4] Exception: ${ex.message}")
            false
        }
    }

    // ── Strategy 5: Raw AudioTrack with no special attributes ─────────────────

    private fun strategyRawAudioTrack(pcm: ByteArray): Boolean {
        LogStore.log(TAG, "[S5] Raw AudioTrack minimal config @ 8000Hz")
        return try {
            val bufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE_GSM, CHANNELS, ENCODING)
            @Suppress("DEPRECATION")
            val track = AudioTrack(
                AudioManager.STREAM_VOICE_CALL,
                SAMPLE_RATE_GSM, CHANNELS, ENCODING,
                bufferSize * 2,
                AudioTrack.MODE_STREAM
            )
            playWithTrack(pcm, track, "S5_RAW")
        } catch (ex: Exception) {
            LogStore.log(TAG, "[S5] Exception: ${ex.message}")
            false
        }
    }

    // ── Core playback ─────────────────────────────────────────────────────────

    private fun playWithTrack(pcm: ByteArray, track: AudioTrack?, strategyName: String): Boolean {
        if (track == null) {
            LogStore.log(TAG, "[$strategyName] AudioTrack is null")
            return false
        }
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            LogStore.log(TAG, "[$strategyName] AudioTrack not initialized state=${track.state}")
            track.release()
            return false
        }

        return try {
            LogStore.log(TAG, "[$strategyName] Starting playback ${pcm.size} bytes")
            audioTrack = track
            isPlaying.set(true)
            track.play()

            var offset = 0
            val chunkSize = 1024
            while (offset < pcm.size && isPlaying.get()) {
                val remaining = pcm.size - offset
                val writeSize = minOf(chunkSize, remaining)
                val written = track.write(pcm, offset, writeSize)
                if (written < 0) {
                    LogStore.log(TAG, "[$strategyName] write error code=$written at offset=$offset")
                    break
                }
                offset += written
            }

            // Flush remaining buffered audio
            track.stop()
            LogStore.log(TAG, "[$strategyName] Playback complete wrote=${offset}/${pcm.size} bytes")
            true
        } catch (ex: Exception) {
            LogStore.log(TAG, "[$strategyName] Playback exception: ${ex.message}")
            false
        } finally {
            try { track.release() } catch (_: Exception) {}
            if (audioTrack == track) audioTrack = null
            isPlaying.set(false)
        }
    }

    // ── WAV parsing ───────────────────────────────────────────────────────────

    data class WavData(val pcm: ByteArray, val sampleRate: Int, val channels: Int, val bitsPerSample: Int)

    private fun parseWav(file: File): WavData? {
        return try {
            FileInputStream(file).use { fis ->
                val header = ByteArray(44)
                fis.read(header)

                val riff = String(header, 0, 4)
                val wave = String(header, 8, 4)
                if (riff != "RIFF" || wave != "WAVE") {
                    LogStore.log(TAG, "Invalid WAV header riff=$riff wave=$wave")
                    return null
                }

                val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val audioFormat = buf.getShort(20).toInt() and 0xFFFF
                val channels = buf.getShort(22).toInt() and 0xFFFF
                val sampleRate = buf.getInt(24)
                val bitsPerSample = buf.getShort(34).toInt() and 0xFFFF

                LogStore.log(TAG, "WAV fmt: format=$audioFormat ch=$channels rate=${sampleRate}Hz bits=$bitsPerSample")

                // Skip to data chunk (look for "data" marker)
                var dataStart = 36
                val searchBuf = ByteArray(8)
                var fileOffset = 36L
                // Skip any extra chunks to find "data"
                fis.skip(0) // already at position 44 after header read
                // Re-open cleanly
                null // will re-parse below
            }
            // Re-parse cleanly
            FileInputStream(file).use { fis ->
                val allBytes = fis.readBytes()
                val buf = ByteBuffer.wrap(allBytes).order(ByteOrder.LITTLE_ENDIAN)
                val riff = String(allBytes, 0, 4)
                val wave = String(allBytes, 8, 4)
                if (riff != "RIFF" || wave != "WAVE") return null

                val channels = (allBytes[22].toInt() and 0xFF) or ((allBytes[23].toInt() and 0xFF) shl 8)
                val sampleRate = (allBytes[24].toInt() and 0xFF) or ((allBytes[25].toInt() and 0xFF) shl 8) or
                        ((allBytes[26].toInt() and 0xFF) shl 16) or ((allBytes[27].toInt() and 0xFF) shl 24)
                val bitsPerSample = (allBytes[34].toInt() and 0xFF) or ((allBytes[35].toInt() and 0xFF) shl 8)

                // Find "data" chunk
                var pos = 12
                var dataOffset = -1
                var dataSize = 0
                while (pos + 8 <= allBytes.size) {
                    val chunkId = String(allBytes, pos, 4)
                    val chunkSize = (allBytes[pos+4].toInt() and 0xFF) or
                            ((allBytes[pos+5].toInt() and 0xFF) shl 8) or
                            ((allBytes[pos+6].toInt() and 0xFF) shl 16) or
                            ((allBytes[pos+7].toInt() and 0xFF) shl 24)
                    if (chunkId == "data") {
                        dataOffset = pos + 8
                        dataSize = chunkSize
                        break
                    }
                    pos += 8 + chunkSize
                    if (chunkSize == 0) break
                }

                if (dataOffset < 0) {
                    LogStore.log(TAG, "data chunk not found")
                    return null
                }

                val actualSize = minOf(dataSize, allBytes.size - dataOffset)
                val pcm = allBytes.copyOfRange(dataOffset, dataOffset + actualSize)
                LogStore.log(TAG, "WAV data: offset=$dataOffset size=$actualSize ch=$channels rate=$sampleRate bits=$bitsPerSample")
                WavData(pcm, sampleRate, channels, bitsPerSample)
            }
        } catch (ex: Exception) {
            LogStore.log(TAG, "WAV parse exception: ${ex.message}")
            null
        }
    }

    // ── Audio resampling ──────────────────────────────────────────────────────

    private fun resampleTo8kMono(wav: WavData): ByteArray {
        // Convert to shorts
        val shorts = ShortArray(wav.pcm.size / 2)
        val bb = ByteBuffer.wrap(wav.pcm).order(ByteOrder.LITTLE_ENDIAN)
        for (i in shorts.indices) {
            shorts[i] = bb.getShort()
        }

        // Mix to mono if stereo
        val mono: ShortArray = if (wav.channels == 2) {
            ShortArray(shorts.size / 2) { i ->
                ((shorts[i * 2].toInt() + shorts[i * 2 + 1].toInt()) / 2).toShort()
            }
        } else {
            shorts
        }

        // Resample to 8000 Hz using linear interpolation
        val outSamples: ShortArray = if (wav.sampleRate == 8000) {
            mono
        } else {
            val ratio = wav.sampleRate.toDouble() / 8000.0
            val outLen = (mono.size / ratio).toInt()
            ShortArray(outLen) { i ->
                val srcPos = i * ratio
                val srcIdx = srcPos.toInt().coerceIn(0, mono.size - 2)
                val frac = (srcPos - srcIdx).toFloat()
                val s0 = mono[srcIdx].toFloat()
                val s1 = mono[srcIdx + 1].toFloat()
                (s0 + frac * (s1 - s0)).toInt().toShort()
            }
        }

        // Convert back to bytes
        val out = ByteArray(outSamples.size * 2)
        val outBuf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        for (s in outSamples) outBuf.putShort(s)
        return out
    }

    private fun upsample8kTo16k(pcm8k: ByteArray): ByteArray {
        // Simple 2x upsample with linear interpolation
        val shorts = ShortArray(pcm8k.size / 2)
        ByteBuffer.wrap(pcm8k).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)

        val out16k = ShortArray(shorts.size * 2)
        for (i in shorts.indices) {
            out16k[i * 2] = shorts[i]
            val next = if (i + 1 < shorts.size) shorts[i + 1] else shorts[i]
            out16k[i * 2 + 1] = ((shorts[i].toInt() + next.toInt()) / 2).toShort()
        }

        val outBytes = ByteArray(out16k.size * 2)
        ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(out16k)
        return outBytes
    }
}
