// package com.optimatrix.gsmcall.recording

// import android.content.Context
// import android.media.AudioFormat
// import android.media.AudioRecord
// import android.media.MediaRecorder
// import com.optimatrix.gsmcall.utils.LogStore
// import java.io.ByteArrayOutputStream
// import java.io.File
// import java.io.FileOutputStream
// import kotlin.math.abs

// class RecordingManager(context: Context) {
//     // Use application context to avoid leaking Activity/Service context
//     private val appContext = context.applicationContext
//     private val recordsDir = File(appContext.getExternalFilesDir(null), "recordings").apply { if (!exists()) mkdirs() }
//     private val sampleRate = 16000
//     private val channelConfig = AudioFormat.CHANNEL_IN_MONO
//     private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
//     private val bytesPerSample = 2

//     fun recordResponse(durationSeconds: Int): File? {
//         val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
//         // Guard against AudioRecord.ERROR or ERROR_BAD_VALUE from getMinBufferSize
//         if (minBuffer == AudioRecord.ERROR || minBuffer == AudioRecord.ERROR_BAD_VALUE) {
//             LogStore.log("Recording", "Invalid AudioRecord buffer size, cannot record")
//             return null
//         }
//         val bufferSize = minBuffer.coerceAtLeast(sampleRate * bytesPerSample)
//         val tempBuffer = ByteArray(bufferSize)
//         val outFile = File(recordsDir, "response_${System.currentTimeMillis()}.wav")
//         val rawStream = ByteArrayOutputStream()

//         var recorder: AudioRecord? = null
//         return try {
//             recorder = AudioRecord.Builder()
//                 .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
//                 .setAudioFormat(
//                     AudioFormat.Builder()
//                         .setEncoding(audioFormat)
//                         .setSampleRate(sampleRate)
//                         .setChannelMask(channelConfig)
//                         .build()
//                 )
//                 .setBufferSizeInBytes(bufferSize)
//                 .build()
//             if (recorder.state != AudioRecord.STATE_INITIALIZED) {
//                 LogStore.log("Recording", "VOICE_COMMUNICATION audio source unavailable, falling back to MIC")
//                 recorder.release()
//                 // MIC requires android.permission.RECORD_AUDIO; caller must ensure it is granted
//                 recorder = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize)
//             }
//             // Double-check state after potential fallback construction
//             if (recorder.state != AudioRecord.STATE_INITIALIZED) {
//                 LogStore.log("Recording", "AudioRecord failed to initialise (RECORD_AUDIO permission may be missing)")
//                 recorder.release()
//                 return null
//             }
//             recorder.startRecording()
//             LogStore.log("Recording", "Recording started for $durationSeconds seconds")
//             var totalBytes = 0
//             val endTime = System.currentTimeMillis() + durationSeconds * 1000L
//             while (System.currentTimeMillis() < endTime) {
//                 val read = recorder.read(tempBuffer, 0, tempBuffer.size)
//                 if (read > 0) {
//                     rawStream.write(tempBuffer, 0, read)
//                     totalBytes += read
//                 } else if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE) {
//                     // Unrecoverable read error; break to avoid a tight spin loop
//                     LogStore.log("Recording", "AudioRecord.read returned error code $read, stopping early")
//                     break
//                 }
//             }

//             recorder.stop()
//             LogStore.log("Recording", "Recording finished size=$totalBytes bytes")
//             val pcmData = trimSilence(rawStream.toByteArray())
//             WavFileWriter.writeWavFile(outFile, pcmData, sampleRate, 1, 16)
//             LogStore.log("Recording", "Saved response to ${outFile.absolutePath}")
//             outFile
//         } catch (ex: Exception) {
//             LogStore.log("Recording", "Recording failed: ${ex.message}")
//             // Clean up any partial file so callers do not receive a corrupt WAV
//             if (outFile.exists()) outFile.delete()
//             null
//         } finally {
//             recorder?.release()
//             rawStream.close()
//         }
//     }

//     private fun trimSilence(rawPcm: ByteArray, silenceThreshold: Int = 512, windowSamples: Int = 800): ByteArray {
//         if (rawPcm.isEmpty()) return rawPcm
//         // Guard against odd-length buffers that would cause an index-out-of-bounds below
//         val safeLength = rawPcm.size and 0x1.inv()
//         val shorts = ShortArray(safeLength / 2)
//         for (index in shorts.indices) {
//             // Little-endian PCM_16BIT: low byte first, high byte second
//             shorts[index] = ((rawPcm[index * 2].toInt() and 0xFF) or (rawPcm[index * 2 + 1].toInt() shl 8)).toShort()
//         }
//         var startIndex = 0
//         var endIndex = shorts.size
//         for (i in 0 until shorts.size step windowSamples) {
//             if (windowContainsSound(shorts, i, windowSamples, silenceThreshold)) {
//                 startIndex = i
//                 break
//             }
//         }
//         for (i in shorts.size - windowSamples downTo 0 step windowSamples) {
//             if (windowContainsSound(shorts, i, windowSamples, silenceThreshold)) {
//                 endIndex = i + windowSamples
//                 break
//             }
//         }
//         if (startIndex >= endIndex) return rawPcm
//         val trimmedSamples = (endIndex - startIndex).coerceAtMost(shorts.size - startIndex)
//         val trimmed = ByteArray(trimmedSamples * 2)
//         System.arraycopy(rawPcm, startIndex * 2, trimmed, 0, trimmed.size)
//         return trimmed
//     }

//     private fun windowContainsSound(data: ShortArray, offset: Int, window: Int, threshold: Int): Boolean {
//         val end = (offset + window).coerceAtMost(data.size)
//         for (i in offset until end) {
//             if (abs(data[i].toInt()) > threshold) {
//                 return true
//             }
//         }
//         return false
//     }
// }

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
    private val appContext = context.applicationContext
    private val recordsDir = File(appContext.getExternalFilesDir(null), "recordings").apply { if (!exists()) mkdirs() }
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bytesPerSample = 2

    fun recordResponse(durationSeconds: Int): File? {
        LogStore.log("Recording", "recordResponse START — duration=$durationSeconds")
        
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBuffer == AudioRecord.ERROR || minBuffer == AudioRecord.ERROR_BAD_VALUE) {
            LogStore.log("Recording", "ERROR: Invalid AudioRecord buffer size")
            return null
        }
        
        val bufferSize = minBuffer.coerceAtLeast(sampleRate * bytesPerSample)
        val tempBuffer = ByteArray(bufferSize)
        val outFile = File(recordsDir, "response_${System.currentTimeMillis()}.wav")
        val rawStream = ByteArrayOutputStream()

        var recorder: AudioRecord? = null
        
        return try {
            // ─── TRY VOICE_COMMUNICATION FIRST ─────────────────────────────
            try {
                LogStore.log("Recording", "Attempting VOICE_COMMUNICATION audio source")
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
                
                // CRITICAL: Samsung needs time for async initialization (FIX 1.3)
                Thread.sleep(100)
                
                if (recorder?.state != AudioRecord.STATE_INITIALIZED) {
                    LogStore.log("Recording", "VOICE_COMMUNICATION failed to init, trying MIC fallback")
                    recorder?.release()
                    recorder = null
                } else {
                    LogStore.log("Recording", "VOICE_COMMUNICATION initialized successfully")
                }
            } catch (ex: Exception) {
                LogStore.log("Recording", "VOICE_COMMUNICATION exception: ${ex.javaClass.simpleName}, fallback to MIC")
                recorder?.release()
                recorder = null
            }

            // ─── FALLBACK TO MIC ──────────────────────────────────────────
            if (recorder == null) {
                try {
                    LogStore.log("Recording", "Attempting MIC audio source fallback")
                    recorder = AudioRecord(
                        MediaRecorder.AudioSource.MIC, 
                        sampleRate, 
                        channelConfig, 
                        audioFormat, 
                        bufferSize
                    )
                    
                    Thread.sleep(100)
                    
                    if (recorder?.state != AudioRecord.STATE_INITIALIZED) {
                        LogStore.log("Recording", "MIC also failed to initialize")
                        recorder?.release()
                        return null
                    }
                    LogStore.log("Recording", "MIC initialized successfully")
                } catch (ex: Exception) {
                    LogStore.log("Recording", "MIC fallback exception: ${ex.javaClass.simpleName}: ${ex.message}")
                    recorder?.release()
                    return null
                }
            }

            // ─── VALIDATE FINAL RECORDER ──────────────────────────────────
            val activeRecorder = recorder ?: run {
                LogStore.log("Recording", "ERROR: Recorder is null after both initialization attempts!")
                return null
            }

            // ─── START RECORDING ──────────────────────────────────────────
            activeRecorder.startRecording()
            LogStore.log("Recording", "Recording started ($durationSeconds seconds)")

            var totalBytes = 0
            val endTime = System.currentTimeMillis() + durationSeconds * 1000L
            
            while (System.currentTimeMillis() < endTime) {
                val read = activeRecorder.read(tempBuffer, 0, tempBuffer.size)
                when {
                    read > 0 -> {
                        rawStream.write(tempBuffer, 0, read)
                        totalBytes += read
                    }
                    read == AudioRecord.ERROR_INVALID_OPERATION -> {
                        LogStore.log("Recording", "AudioRecord returned ERROR_INVALID_OPERATION")
                        break
                    }
                    read == AudioRecord.ERROR_BAD_VALUE -> {
                        LogStore.log("Recording", "AudioRecord returned ERROR_BAD_VALUE")
                        break
                    }
                }
            }

            activeRecorder.stop()
            LogStore.log("Recording", "Recording stopped — $totalBytes bytes total")

            // ─── TRIM SILENCE & WRITE WAV ─────────────────────────────────
            val pcmData = trimSilence(rawStream.toByteArray())
            WavFileWriter.writeWavFile(outFile, pcmData, sampleRate, 1, 16)
            LogStore.log("Recording", "Response saved to ${outFile.absolutePath}")
            
            outFile

        } catch (ex: Exception) {
            LogStore.log("Recording", "Exception in recordResponse: ${ex.javaClass.simpleName}: ${ex.message}")
            if (outFile.exists()) outFile.delete()
            null
            
        } finally {
            try { recorder?.stop() } catch (_: Exception) {}
            try { recorder?.release() } catch (_: Exception) {}
            try { rawStream.close() } catch (_: Exception) {}
            LogStore.log("Recording", "recordResponse cleanup complete")
        }
    }

    private fun trimSilence(rawPcm: ByteArray, silenceThreshold: Int = 512, windowSamples: Int = 800): ByteArray {
        if (rawPcm.isEmpty()) return rawPcm
        val safeLength = rawPcm.size and 0x1.inv()
        val shorts = ShortArray(safeLength / 2)
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
        val trimmedSamples = (endIndex - startIndex).coerceAtMost(shorts.size - startIndex)
        val trimmed = ByteArray(trimmedSamples * 2)
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
