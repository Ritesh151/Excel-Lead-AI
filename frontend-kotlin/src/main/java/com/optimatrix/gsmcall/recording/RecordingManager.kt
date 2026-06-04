package com.optimatrix.gsmcall.recording

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.optimatrix.gsmcall.utils.LogStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

class RecordingManager(context: Context) {
    private val recordsDir = File(context.getExternalFilesDir(null), "recordings").apply { if (!exists()) mkdirs() }
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bytesPerSample = 2

    fun recordResponse(durationSeconds: Int): File? {
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat).coerceAtLeast(sampleRate * bytesPerSample)
        val tempBuffer = ByteArray(bufferSize)
        val outFile = File(recordsDir, "response_${System.currentTimeMillis()}.wav")
        val rawStream = ByteArrayOutputStream()

        var recorder: AudioRecord? = null
        return try {
            recorder = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .build()
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                LogStore.log("Recording", "VOICE_COMMUNICATION audio source unavailable, falling back to MIC")
                recorder.release()
                recorder = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize)
            }
            recorder.startRecording()
            LogStore.log("Recording", "Recording started for $durationSeconds seconds")
            var totalBytes = 0
            val endTime = System.currentTimeMillis() + durationSeconds * 1000L
            while (System.currentTimeMillis() < endTime) {
                val read = recorder.read(tempBuffer, 0, tempBuffer.size)
                if (read > 0) {
                    rawStream.write(tempBuffer, 0, read)
                    totalBytes += read
                }
            }
            recorder.stop()
            LogStore.log("Recording", "Recording finished size=$totalBytes bytes")
            val pcmData = trimSilence(rawStream.toByteArray())
            WavFileWriter.writeWavFile(outFile, pcmData, sampleRate, 1, 16)
            LogStore.log("Recording", "Saved response to ${outFile.absolutePath}")
            outFile
        } catch (ex: Exception) {
            LogStore.log("Recording", "Recording failed: ${ex.message}")
            null
        } finally {
            recorder?.release()
            rawStream.close()
        }
    }

    private fun trimSilence(rawPcm: ByteArray, silenceThreshold: Int = 512, windowSamples: Int = 800): ByteArray {
        if (rawPcm.isEmpty()) return rawPcm
        val shorts = ShortArray(rawPcm.size / 2)
        for (index in shorts.indices) {
            shorts[index] = ((rawPcm[index * 2].toInt() and 0xFF) or (rawPcm[index * 2 + 1].toInt() shl 8)).toShort()
        }
        var startIndex = 0
        var endIndex = shorts.size
        for (i in 0 until shorts.size step windowSamples) {
            if (windowContainsSound(shorts, i, windowSamples, silenceThreshold)) {
                startIndex = i
                break
            }
        }
        for (i in shorts.size - windowSamples downTo 0 step windowSamples) {
            if (windowContainsSound(shorts, i, windowSamples, silenceThreshold)) {
                endIndex = i + windowSamples
                break
            }
        }
        if (startIndex >= endIndex) return rawPcm
        val trimmed = ByteArray((endIndex - startIndex) * 2)
        System.arraycopy(rawPcm, startIndex * 2, trimmed, 0, trimmed.size)
        return trimmed
    }

    private fun windowContainsSound(data: ShortArray, offset: Int, window: Int, threshold: Int): Boolean {
        val end = (offset + window).coerceAtMost(data.size)
        for (i in offset until end) {
            if (abs(data[i].toInt()) > threshold) {
                return true
            }
        }
        return false
    }
}
