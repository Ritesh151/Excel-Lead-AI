package com.optimatrix.gsmcall.audio

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import com.optimatrix.gsmcall.utils.LogStore
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * RoutingRetryEngine — Exhaustive routing retry logic.
 *
 * If the customer cannot hear the greeting, this engine automatically retries
 * with every possible Android audio routing combination available on
 * Samsung SM-A176B running Android 14/15.
 *
 * Retry strategies (in order):
 *   1. PcmStreamingEngine (all 5 internal strategies)
 *   2. VoiceCallAudioTrack (direct PCM, Samsung-safe)
 *   3. MediaPlayer with VOICE_COMMUNICATION attributes
 *   4. MediaPlayer with STREAM_VOICE_CALL stream type
 *   5. MediaPlayer with STREAM_MUSIC (speakerphone fallback — last resort)
 *
 * All strategies are ALWAYS attempted — not just first success.
 * The goal is maximum exposure to routing paths.
 */
class RoutingRetryEngine(private val context: Context) {

    companion object {
        private const val TAG = "RoutingRetryEngine"
        private const val DELAY_BETWEEN_RETRIES_MS = 300L
        private const val PLAYBACK_TIMEOUT_MS = 45_000L
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val samsungWorkarounds = SamsungAudioWorkarounds(context)
    private val pcmEngine = PcmStreamingEngine(audioManager)
    private val voiceTrack = VoiceCallAudioTrack(audioManager)

    data class PlaybackReport(
        val strategy: String,
        val success: Boolean,
        val errorMessage: String = ""
    )

    /**
     * Play the WAV file using ALL available routing strategies.
     * Continues to the next strategy regardless of success/failure.
     *
     * @param wavFile  The greeting WAV file
     * @return List of PlaybackReport — one per strategy attempted
     */
    fun playWithAllStrategies(wavFile: File): List<PlaybackReport> {
        LogStore.log(TAG, "=== RoutingRetryEngine START ===")
        LogStore.log(TAG, "File: ${wavFile.absolutePath}")

        val reports = mutableListOf<PlaybackReport>()
        val originalMode = audioManager.mode

        // Samsung preparation sequence
        samsungWorkarounds.prepareForVoicePlayback()

        // Strategy 1: PcmStreamingEngine (5 sub-strategies)
        reports += try {
            LogStore.log(TAG, ">>> Strategy 1: PcmStreamingEngine (all sub-strategies)")
            val ok = pcmEngine.streamWavFile(wavFile)
            PlaybackReport("PcmStreamingEngine", ok)
        } catch (ex: Exception) {
            LogStore.log(TAG, "Strategy 1 exception: ${ex.message}")
            PlaybackReport("PcmStreamingEngine", false, ex.message ?: "unknown")
        }

        Thread.sleep(DELAY_BETWEEN_RETRIES_MS)

        // Strategy 2: VoiceCallAudioTrack
        reports += try {
            LogStore.log(TAG, ">>> Strategy 2: VoiceCallAudioTrack direct PCM")
            val pcm = PcmStreamingEngine(audioManager).let {
                // Re-use parse + resample from PcmStreamingEngine via public parseAndResample
                parseWavTo8kMono(wavFile)
            }
            if (pcm != null) {
                val ok = voiceTrack.play(pcm, 1)
                PlaybackReport("VoiceCallAudioTrack", ok)
            } else {
                PlaybackReport("VoiceCallAudioTrack", false, "WAV parse failed")
            }
        } catch (ex: Exception) {
            LogStore.log(TAG, "Strategy 2 exception: ${ex.message}")
            PlaybackReport("VoiceCallAudioTrack", false, ex.message ?: "unknown")
        }

        Thread.sleep(DELAY_BETWEEN_RETRIES_MS)

        // Strategy 3: MediaPlayer with USAGE_VOICE_COMMUNICATION
        reports += playWithMediaPlayer(
            wavFile,
            "MediaPlayer_VoiceComm",
            audioManager.mode,
            usageVoiceCommunication = true,
            speakerphone = false
        )

        Thread.sleep(DELAY_BETWEEN_RETRIES_MS)

        // Strategy 4: MediaPlayer with STREAM_VOICE_CALL
        reports += playWithMediaPlayerStream(wavFile, "MediaPlayer_VoiceCallStream", AudioManager.STREAM_VOICE_CALL)

        Thread.sleep(DELAY_BETWEEN_RETRIES_MS)

        // Strategy 5: MediaPlayer with STREAM_MUSIC (speakerphone last resort)
        reports += playWithMediaPlayerStream(wavFile, "MediaPlayer_Music_Speaker", AudioManager.STREAM_MUSIC)

        // Restore audio state
        samsungWorkarounds.restoreAfterPlayback(originalMode)

        // Summary
        LogStore.log(TAG, "=== RoutingRetryEngine COMPLETE ===")
        reports.forEach { r ->
            LogStore.log(TAG, "  ${r.strategy}: ${if (r.success) "OK" else "FAIL"} ${r.errorMessage}")
        }

        return reports
    }

    // ── MediaPlayer strategy helpers ──────────────────────────────────────────

    private fun playWithMediaPlayer(
        wavFile: File,
        name: String,
        mode: Int,
        usageVoiceCommunication: Boolean,
        speakerphone: Boolean
    ): PlaybackReport {
        LogStore.log(TAG, ">>> Strategy $name mode=$mode speaker=$speakerphone")
        return try {
            audioManager.mode = mode
            audioManager.isSpeakerphoneOn = speakerphone
            Thread.sleep(50)

            val latch = CountDownLatch(1)
            var error: String? = null
            var completed = false

            val mp = MediaPlayer()
            try {
                if (usageVoiceCommunication && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    mp.setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                }
                mp.setDataSource(wavFile.absolutePath)
                mp.setOnPreparedListener { it.start() }
                mp.setOnCompletionListener {
                    completed = true
                    latch.countDown()
                }
                mp.setOnErrorListener { _, what, extra ->
                    error = "error what=$what extra=$extra"
                    latch.countDown()
                    true
                }
                mp.prepareAsync()
                val timedOut = !latch.await(PLAYBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                if (timedOut) {
                    error = "Playback timed out"
                    LogStore.log(TAG, "$name: TIMEOUT")
                }
            } finally {
                try { if (mp.isPlaying) mp.stop() } catch (_: Exception) {}
                try { mp.release() } catch (_: Exception) {}
            }

            LogStore.log(TAG, "$name: completed=$completed error=$error")
            PlaybackReport(name, completed && error == null, error ?: "")
        } catch (ex: Exception) {
            LogStore.log(TAG, "$name exception: ${ex.message}")
            PlaybackReport(name, false, ex.message ?: "unknown")
        }
    }

    @Suppress("DEPRECATION")
    private fun playWithMediaPlayerStream(wavFile: File, name: String, streamType: Int): PlaybackReport {
        LogStore.log(TAG, ">>> Strategy $name streamType=$streamType")
        return try {
            val latch = CountDownLatch(1)
            var error: String? = null
            var completed = false

            val mp = MediaPlayer()
            try {
                mp.setAudioStreamType(streamType)
                mp.setDataSource(wavFile.absolutePath)
                mp.setOnPreparedListener { it.start() }
                mp.setOnCompletionListener {
                    completed = true
                    latch.countDown()
                }
                mp.setOnErrorListener { _, what, extra ->
                    error = "error what=$what extra=$extra"
                    latch.countDown()
                    true
                }
                mp.prepareAsync()
                val timedOut = !latch.await(PLAYBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                if (timedOut) error = "Timeout"
            } finally {
                try { if (mp.isPlaying) mp.stop() } catch (_: Exception) {}
                try { mp.release() } catch (_: Exception) {}
            }

            LogStore.log(TAG, "$name: completed=$completed error=$error")
            PlaybackReport(name, completed && error == null, error ?: "")
        } catch (ex: Exception) {
            LogStore.log(TAG, "$name exception: ${ex.message}")
            PlaybackReport(name, false, ex.message ?: "unknown")
        }
    }

    // ── WAV utility ───────────────────────────────────────────────────────────

    private fun parseWavTo8kMono(wavFile: File): ByteArray? {
        return try {
            val engine = PcmStreamingEngine(audioManager)
            // Use reflection or duplicate parseWav logic — simpler to duplicate here
            val allBytes = wavFile.readBytes()
            if (allBytes.size < 44) return null

            val riff = String(allBytes, 0, 4)
            val wave = String(allBytes, 8, 4)
            if (riff != "RIFF" || wave != "WAVE") return null

            val channels = (allBytes[22].toInt() and 0xFF) or ((allBytes[23].toInt() and 0xFF) shl 8)
            val sampleRate = (allBytes[24].toInt() and 0xFF) or ((allBytes[25].toInt() and 0xFF) shl 8) or
                    ((allBytes[26].toInt() and 0xFF) shl 16) or ((allBytes[27].toInt() and 0xFF) shl 24)

            var pos = 12
            var dataOffset = -1
            var dataSize = 0
            while (pos + 8 <= allBytes.size) {
                val chunkId = String(allBytes, pos, 4)
                val cs = (allBytes[pos+4].toInt() and 0xFF) or ((allBytes[pos+5].toInt() and 0xFF) shl 8) or
                        ((allBytes[pos+6].toInt() and 0xFF) shl 16) or ((allBytes[pos+7].toInt() and 0xFF) shl 24)
                if (chunkId == "data") { dataOffset = pos + 8; dataSize = cs; break }
                pos += 8 + cs; if (cs == 0) break
            }
            if (dataOffset < 0) return null

            val pcmRaw = allBytes.copyOfRange(dataOffset, minOf(dataOffset + dataSize, allBytes.size))

            // Convert to 8k mono via the engine's methods (done inline here)
            val shorts = ShortArray(pcmRaw.size / 2)
            for (i in shorts.indices) {
                shorts[i] = ((pcmRaw[i*2].toInt() and 0xFF) or (pcmRaw[i*2+1].toInt() shl 8)).toShort()
            }
            val mono = if (channels == 2) ShortArray(shorts.size / 2) { i ->
                ((shorts[i*2].toInt() + shorts[i*2+1].toInt()) / 2).toShort()
            } else shorts

            val ratio = sampleRate.toDouble() / 8000.0
            val outShorts = if (sampleRate == 8000) mono else ShortArray((mono.size / ratio).toInt()) { i ->
                val srcPos = i * ratio
                val si = srcPos.toInt().coerceIn(0, mono.size - 2)
                val frac = (srcPos - si).toFloat()
                (mono[si].toFloat() + frac * (mono[si+1].toFloat() - mono[si].toFloat())).toInt().toShort()
            }

            val out = ByteArray(outShorts.size * 2)
            for (i in outShorts.indices) {
                out[i*2] = (outShorts[i].toInt() and 0xFF).toByte()
                out[i*2+1] = (outShorts[i].toInt() shr 8).toByte()
            }
            out
        } catch (ex: Exception) {
            LogStore.log(TAG, "parseWavTo8kMono exception: ${ex.message}")
            null
        }
    }
}
