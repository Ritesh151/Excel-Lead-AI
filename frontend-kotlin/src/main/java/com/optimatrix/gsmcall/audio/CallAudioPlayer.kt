package com.optimatrix.gsmcall.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import com.optimatrix.gsmcall.utils.LogStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * CallAudioPlayer — Production-grade in-call audio playback.
 *
 * UPGRADED: Uses RoutingRetryEngine for maximum GSM uplink injection probability.
 * Also maintains MediaPlayer fallback with all stream types.
 *
 * Primary path: RoutingRetryEngine → PcmStreamingEngine → VoiceCallAudioTrack
 * Fallback:     MediaPlayer with STREAM_VOICE_CALL
 * Last resort:  MediaPlayer with STREAM_MUSIC
 *
 * Samsung SM-A176B specific:
 *   - Copies audio from assets to external storage before playback
 *   - Validates WAV file on every play
 *   - Logs AudioManager state before/after playback
 */
class CallAudioPlayer(private val context: Context) {

    companion object {
        private const val TAG = "CallAudioPlayer"
        private const val PLAYBACK_TIMEOUT_SEC = 60L
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val routingRetryEngine = RoutingRetryEngine(context)
    private var mediaPlayer: MediaPlayer? = null

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Get or extract an audio file from assets.
     * Files are stored in external storage /Android/data/.../files/audio/
     */
    fun getAudioFile(fileName: String): File? {
        val audioFolder = File(context.getExternalFilesDir(null), "audio").apply {
            if (!exists()) mkdirs()
        }
        val file = File(audioFolder, fileName)
        if (!file.exists() || file.length() < 44) {
            copyAssetToFile(fileName, file)
        }
        return if (file.exists() && file.length() >= 44) {
            LogStore.log(TAG, "Audio file ready: ${file.absolutePath} size=${file.length()}")
            file
        } else {
            LogStore.log(TAG, "Audio file not found after copy attempt: $fileName")
            null
        }
    }

    /**
     * Play a WAV file using the complete routing strategy cascade.
     *
     * Strategy order:
     *   1. RoutingRetryEngine (all PCM strategies — primary)
     *   2. MediaPlayer STREAM_VOICE_CALL (secondary)
     *   3. MediaPlayer STREAM_MUSIC (last resort)
     *
     * @param file       WAV file to play
     * @param streamType Requested stream type (used for MediaPlayer fallback)
     * @return true if audio was played (via any strategy)
     */
    fun playAudioFile(file: File, streamType: Int = AudioManager.STREAM_VOICE_CALL): Boolean {
        LogStore.log(TAG, "=== playAudioFile() START ===")
        LogStore.log(TAG, "File: ${file.absolutePath} streamType=$streamType")

        if (!file.exists() || file.length() < 44) {
            LogStore.log(TAG, "ERROR: File missing or invalid size=${file.length()}")
            return false
        }

        release() // Release any previous player

        // Log current audio state
        logAudioDiagnostics("PRE_PLAY")

        // ── Primary: PCM Routing Engine ───────────────────────────────────────
        LogStore.log(TAG, "--- PHASE 1: PCM Routing Retry Engine ---")
        val reports = routingRetryEngine.playWithAllStrategies(file)
        val pcmSucceeded = reports.any { it.success }
        LogStore.log(TAG, "PCM engine reports: ${ reports.map { "${it.strategy}:${if(it.success)"OK" else "FAIL"}" }}")

        // ── Secondary: MediaPlayer STREAM_VOICE_CALL ──────────────────────────
        LogStore.log(TAG, "--- PHASE 2: MediaPlayer STREAM_VOICE_CALL ---")
        val mp1Success = playWithMediaPlayer(file, AudioManager.STREAM_VOICE_CALL, "MP_VoiceCall")

        // ── Tertiary: MediaPlayer STREAM_MUSIC ───────────────────────────────
        LogStore.log(TAG, "--- PHASE 3: MediaPlayer STREAM_MUSIC ---")
        val mp2Success = playWithMediaPlayer(file, AudioManager.STREAM_MUSIC, "MP_Music")

        logAudioDiagnostics("POST_PLAY")

        val overallSuccess = pcmSucceeded || mp1Success || mp2Success
        LogStore.log(TAG, "=== playAudioFile() DONE: pcm=$pcmSucceeded mp1=$mp1Success mp2=$mp2Success overall=$overallSuccess ===")
        return overallSuccess
    }

    fun release() {
        mediaPlayer?.let {
            try {
                if (it.isPlaying) it.stop()
                it.release()
            } catch (ex: Exception) {
                LogStore.log(TAG, "Release exception: ${ex.message}")
            }
        }
        mediaPlayer = null
    }

    // ── MediaPlayer helpers ───────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun playWithMediaPlayer(file: File, streamType: Int, label: String): Boolean {
        val streamName = when (streamType) {
            AudioManager.STREAM_VOICE_CALL -> "VOICE_CALL"
            AudioManager.STREAM_MUSIC -> "MUSIC"
            AudioManager.STREAM_RING -> "RING"
            else -> "UNKNOWN($streamType)"
        }
        LogStore.log(TAG, "[$label] MediaPlayer stream=$streamName file=${file.name}")

        val latch = CountDownLatch(1)
        var succeeded = false

        return try {
            val mp = MediaPlayer()
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    mp.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                }
                mp.setAudioStreamType(streamType)
                mp.setDataSource(file.absolutePath)
                mp.setOnPreparedListener { player ->
                    LogStore.log(TAG, "[$label] Prepared — starting playback")
                    player.start()
                }
                mp.setOnCompletionListener {
                    LogStore.log(TAG, "[$label] Playback complete")
                    succeeded = true
                    latch.countDown()
                }
                mp.setOnErrorListener { _, what, extra ->
                    LogStore.log(TAG, "[$label] Error what=$what extra=$extra")
                    latch.countDown()
                    true
                }
                mp.prepare() // Synchronous for file:// sources
                mp.start()
                latch.await(PLAYBACK_TIMEOUT_SEC, TimeUnit.SECONDS)
            } finally {
                try { if (mp.isPlaying) mp.stop() } catch (_: Exception) {}
                try { mp.release() } catch (_: Exception) {}
            }
            LogStore.log(TAG, "[$label] Result: succeeded=$succeeded")
            succeeded
        } catch (ex: Exception) {
            LogStore.log(TAG, "[$label] Exception: ${ex.message}")
            false
        }
    }

    // ── Asset copy ────────────────────────────────────────────────────────────

    private fun copyAssetToFile(assetName: String, destination: File) {
        try {
            context.assets.open(assetName).use { input ->
                FileOutputStream(destination).use { output ->
                    input.copyTo(output)
                }
            }
            LogStore.log(TAG, "Copied asset '$assetName' → ${destination.absolutePath} (${destination.length()} bytes)")
        } catch (ex: IOException) {
            LogStore.log(TAG, "Asset copy failed '$assetName': ${ex.message}")
        }
    }

    // ── Diagnostics ───────────────────────────────────────────────────────────

    private fun logAudioDiagnostics(label: String) {
        try {
            val mode = when (audioManager.mode) {
                AudioManager.MODE_NORMAL -> "NORMAL"
                AudioManager.MODE_RINGTONE -> "RINGTONE"
                AudioManager.MODE_IN_CALL -> "IN_CALL"
                AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
                else -> "UNKNOWN(${audioManager.mode})"
            }
            val vcVol = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            val vcMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            val mVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val mMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            LogStore.log(TAG, "[$label] mode=$mode VOICE_CALL=$vcVol/$vcMax MUSIC=$mVol/$mMax " +
                    "speaker=${audioManager.isSpeakerphoneOn} btSco=${audioManager.isBluetoothScoOn}")
        } catch (ex: Exception) {
            LogStore.log(TAG, "[$label] diagnostics exception: ${ex.message}")
        }
    }
}
