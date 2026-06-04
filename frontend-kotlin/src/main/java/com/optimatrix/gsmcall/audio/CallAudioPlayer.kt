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

class CallAudioPlayer(private val context: Context) {
    private var mediaPlayer: MediaPlayer? = null

    fun getAudioFile(fileName: String): File? {
        val audioFolder = File(context.getExternalFilesDir(null), "audio").apply { if (!exists()) mkdirs() }
        val file = File(audioFolder, fileName)
        if (!file.exists()) {
            copyAssetToFile(fileName, file)
        }
        return if (file.exists()) file else null
    }

    fun playAudioFile(file: File, streamType: Int = AudioManager.STREAM_VOICE_CALL): Boolean {
        release()
        val latch = CountDownLatch(1)
        var succeeded = false
        try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setAudioStreamType(streamType)
                setDataSource(file.absolutePath)
                setOnPreparedListener {
                    it.start()
                    LogStore.log("AudioPlayer", "Started playback ${file.name} stream=$streamType")
                }
                setOnCompletionListener {
                    succeeded = true
                    latch.countDown()
                }
                setOnErrorListener { _, what, extra ->
                    LogStore.log("AudioPlayer", "Playback error what=$what extra=$extra")
                    latch.countDown()
                    true
                }
                prepare()
            }
            latch.await(45, TimeUnit.SECONDS)
        } catch (ex: Exception) {
            LogStore.log("AudioPlayer", "Failed to play ${file.name}: ${ex.message}")
        }
        return succeeded
    }

    fun release() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.stop()
            }
            it.release()
        }
        mediaPlayer = null
    }

    private fun copyAssetToFile(assetName: String, destination: File) {
        try {
            context.assets.open(assetName).use { input ->
                FileOutputStream(destination).use { output ->
                    input.copyTo(output)
                }
            }
            LogStore.log("AudioPlayer", "Copied asset $assetName to ${destination.absolutePath}")
        } catch (ex: IOException) {
            LogStore.log("AudioPlayer", "Asset copy failed for $assetName: ${ex.message}")
        }
    }
}
