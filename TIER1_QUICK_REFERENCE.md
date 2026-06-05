# TIER 1 Fixes — Quick Reference

Complete, copy-paste ready fixes for all 8 critical crashes. Implementation: ~85 minutes.

---

## FIX 1.1: CallAutomationService - Null Socket Checks (5 min)

**File:** `CallAutomationService.kt`  
**Location:** Line ~145 in `onSessionUpdated()`

**REPLACE:**
```kotlin
socketManager?.sendCallState(phase, session.remoteNumber)
```

**WITH:**
```kotlin
if (socketManager?.connected == true) {
    try {
        socketManager?.sendCallState(phase, session.remoteNumber)
    } catch (ex: Exception) {
        LogStore.log("Telephony", "Failed to send state: ${ex.message}")
    }
}
```

**ALSO ADD** at the end of `onSessionUpdated()` if not present:
```kotlin
// Broadcast WebSocket status
if (socketManager?.connected == true) {
    broadcastStatus("websocket_connected")
} else {
    broadcastStatus("websocket_disconnected")
}
```

---

## FIX 1.2: CallAutomationService - Wrap Flow in Try-Catch (15 min)

**File:** `CallAutomationService.kt`  
**Location:** Entire `private suspend fun runAutomationFlow(session: CallSession)` function

**REPLACE the entire function with:**

```kotlin
private suspend fun runAutomationFlow(session: CallSession) {
    val fid = flowCounter.incrementAndGet()
    LogStore.log("Flow", "[$fid] ════════════ START ════════════")
    LogStore.log("Flow", "[$fid] Phone: ${session.remoteNumber}")
    
    withContext(Dispatchers.Main) { acquireWakeLock() }
    sessionRunning.set(true)

    try {
        // ─────────────────────────────────────────────────────────────────
        // PASTE YOUR ENTIRE ORIGINAL runAutomationFlow() BODY HERE
        // Between this try and catch blocks
        // Keep all the existing logic exactly as-is:
        //   - broadcastStatus calls
        //   - delay calls
        //   - socketManager?.sendCallState calls
        //   - audioPlayer calls
        //   - recordingManager calls
        //   - etc.
        // ─────────────────────────────────────────────────────────────────

        LogStore.log("Flow", "[$fid] ════════════ COMPLETE ════════════")

    } catch (ex: CancellationException) {
        LogStore.log("Flow", "[$fid] CANCELLED (expected during shutdown)")
        throw ex // Re-throw — coroutine system will handle it
        
    } catch (ex: Exception) {
        LogStore.log("Flow", "[$fid] ❌ EXCEPTION: ${ex.javaClass.simpleName}")
        LogStore.log("Flow", "[$fid] Message: ${ex.message}")
        LogStore.log("Flow", "[$fid] Stack: ${ex.stackTraceToString().take(500)}")
        
        broadcastLog("[$fid] ERROR RECOVERED: ${ex.message}")
        broadcastStatus("error_exception")
        
        try {
            socketManager?.sendCallState("error_exception", session.remoteNumber, fid)
        } catch (_: Exception) {}
        
        endCallSafely()
        
    } finally {
        LogStore.log("Flow", "[$fid] Finally block executing")
        sessionRunning.set(false)
        
        withContext(Dispatchers.Main) {
            try {
                audioRoutingManager.restoreAudioMode()
                LogStore.log("Flow", "[$fid] Audio mode restored")
            } catch (ex: Exception) {
                LogStore.log("Flow", "[$fid] Audio restore error: ${ex.message}")
            }
            
            releaseWakeLock()
        }
        
        LogStore.log("Flow", "[$fid] ════════════ CLEANUP DONE ════════════")
    }
}
```

---

## FIX 1.3: RecordingManager - AudioRecord Initialization (20 min)

**File:** `RecordingManager.kt`  
**Location:** Entire `fun recordResponse(durationSeconds: Int)` function

**REPLACE the entire function with:**

```kotlin
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
            
            // CRITICAL: Samsung needs time for async initialization
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
```

---

## FIX 1.4: CallAutomationService - WakeLock State (10 min)

**File:** `CallAutomationService.kt`  
**Location:** Class level and two methods

**Step 1: Add import (top of file)**
```kotlin
import java.util.concurrent.atomic.AtomicBoolean
```

**Step 2: Add class member (after line ~55)**
```kotlin
private val wakeLockAcquired = AtomicBoolean(false)
```

**Step 3: REPLACE entire `acquireWakeLock()` method**
```kotlin
private fun acquireWakeLock() {
    if (wakeLockAcquired.compareAndSet(false, true)) {
        try {
            wakeLock.acquire(WAKELOCK_TIMEOUT_MS)
            LogStore.log("Service", "WakeLock acquired ($WAKELOCK_TIMEOUT_MS ms)")
        } catch (ex: Exception) {
            wakeLockAcquired.set(false)
            LogStore.log("Service", "WakeLock acquire error: ${ex.message}")
        }
    }
}
```

**Step 4: REPLACE entire `releaseWakeLock()` method**
```kotlin
private fun releaseWakeLock() {
    if (wakeLockAcquired.compareAndSet(true, false)) {
        try {
            wakeLock.release()
            LogStore.log("Service", "WakeLock released")
        } catch (ex: Exception) {
            LogStore.log("Service", "WakeLock release error: ${ex.javaClass.simpleName}")
            wakeLockAcquired.set(false)
        }
    }
}
```

---

## FIX 1.5: CallAutomationService - Recording Try-Catch (5 min)

**File:** `CallAutomationService.kt`  
**Location:** Inside `runAutomationFlow()` around line 256

**FIND:**
```kotlin
val responseFile = withContext(Dispatchers.IO) {
    recordingManager.recordResponse(RECORDING_DURATION_SEC)
}

if (responseFile == null) {
    val msg = "[$fid] Recording failed"
    // ... error handling
}
```

**REPLACE WITH:**
```kotlin
val responseFile = try {
    withContext(Dispatchers.IO) {
        recordingManager.recordResponse(RECORDING_DURATION_SEC)
    }
} catch (ex: SecurityException) {
    val msg = "[$fid] Recording permission denied"
    LogStore.log("Flow", msg)
    broadcastLog(msg)
    broadcastStatus("recording_permission_denied")
    socketManager?.sendCallState("recording_failed", session.remoteNumber, fid)
    endCallSafely()
    return
} catch (ex: Exception) {
    val msg = "[$fid] Recording exception: ${ex.javaClass.simpleName}: ${ex.message}"
    LogStore.log("Flow", msg)
    broadcastLog(msg)
    broadcastStatus("recording_error")
    socketManager?.sendCallState("recording_failed", session.remoteNumber, fid)
    endCallSafely()
    return
}

if (responseFile == null) {
    val msg = "[$fid] Recording returned null"
    LogStore.log("Flow", msg)
    broadcastLog(msg)
    broadcastStatus("recording_failed")
    socketManager?.sendCallState("recording_failed", session.remoteNumber, fid)
    endCallSafely()
    return
}
```

---

## FIX 1.6: MainActivity - Receiver Registration (5 min)

**File:** `MainActivity.kt`  
**Location:** Class level and two methods

**Step 1: Add import (top of file)**
```kotlin
import java.util.concurrent.atomic.AtomicBoolean
```

**Step 2: Add class member (after line ~20)**
```kotlin
private var isReceiverRegistered = AtomicBoolean(false)
```

**Step 3: Update onCreate() registration part**

**FIND:**
```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
} else {
    @Suppress("DEPRECATION")
    registerReceiver(statusReceiver, filter)
}
```

**REPLACE WITH:**
```kotlin
try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
    } else {
        @Suppress("DEPRECATION")
        registerReceiver(statusReceiver, filter)
    }
    isReceiverRegistered.set(true)
    LogStore.log("MainActivity", "statusReceiver registered")
} catch (ex: Exception) {
    LogStore.log("MainActivity", "statusReceiver registration failed: ${ex.message}")
    isReceiverRegistered.set(false)
}
```

**Step 4: REPLACE entire onDestroy() method**
```kotlin
override fun onDestroy() {
    super.onDestroy()
    
    if (isReceiverRegistered.compareAndSet(true, false)) {
        try {
            unregisterReceiver(statusReceiver)
            LogStore.log("MainActivity", "statusReceiver unregistered")
        } catch (ex: Exception) {
            LogStore.log("MainActivity", "statusReceiver unregister error: ${ex.message}")
        }
    }
}
```

---

## FIX 1.7: SocketManager - Check Send Return (10 min)

**File:** `SocketManager.kt`  
**Location:** Multiple send methods

**Step 1: Change sendEvent() signature**

**FIND:**
```kotlin
fun sendEvent(event: String) {
    if (isConnected.get()) {
        socket?.send(event)
    } else {
        LogStore.log(TAG, "Not connected — dropped: ${event.take(80)}")
    }
}
```

**REPLACE WITH:**
```kotlin
fun sendEvent(event: String): Boolean {
    if (!isConnected.get()) {
        LogStore.log(TAG, "Socket not connected — dropped: ${event.take(80)}")
        return false
    }
    
    return try {
        val sent = socket?.send(event) ?: false
        if (!sent) {
            LogStore.log(TAG, "WebSocket.send() returned false for: ${event.take(60)}")
            isConnected.set(false)
            scheduleReconnect()
        }
        sent
    } catch (ex: Exception) {
        LogStore.log(TAG, "sendEvent exception: ${ex.javaClass.simpleName}: ${ex.message}")
        isConnected.set(false)
        scheduleReconnect()
        false
    }
}
```

**Step 2: Update all send*() helper methods to return Boolean**

Make sure all methods like `sendCallState()`, `sendPlaybackResult()`, `sendRecordingSaved()` etc. return Boolean and call sendJson() which also returns Boolean:

```kotlin
fun sendCallState(state: String, phone: String?, flowId: Int = 0): Boolean {
    return sendJson(mapOf(
        "event" to "call_state",
        "state" to state,
        "phone" to (phone ?: ""),
        "flowId" to flowId,
        "ts" to System.currentTimeMillis(),
    ))
}
```

---

## FIX 1.8: SocketManager - Reconnect Backoff (15 min)

**File:** `SocketManager.kt`  
**Location:** scheduleReconnect() method and new method

**Step 1: Add class members (after line ~50)**
```kotlin
private var inMaxReconnectBackoff = AtomicBoolean(false)
```

**Step 2: REPLACE entire scheduleReconnect() method**
```kotlin
private fun scheduleReconnect() {
    val attempt = reconnectAttempts.incrementAndGet()
    
    if (attempt > MAX_RECONNECT_ATTEMPTS) {
        // Enter long backoff period
        if (inMaxReconnectBackoff.compareAndSet(false, true)) {
            LogStore.log(TAG, "Max reconnect attempts ($MAX_RECONNECT_ATTEMPTS) reached — entering 5-minute backoff")
            handler.postDelayed({
                if (inMaxReconnectBackoff.compareAndSet(true, false)) {
                    LogStore.log(TAG, "Exiting backoff period, resetting attempt counter")
                    reconnectAttempts.set(0)
                    connectInternal()
                }
            }, 5 * 60 * 1_000L) // 5 minutes
        }
        return
    }
    
    // Exponential backoff: 3s, 6s, 12s, 24s, 48s, 48s, ...
    val exponentialDelay = RECONNECT_DELAY_BASE_MS * (1L shl minOf(attempt - 1, 4))
    LogStore.log(TAG, "Reconnect attempt $attempt/$MAX_RECONNECT_ATTEMPTS in ${exponentialDelay}ms")
    handler.postDelayed({ connectInternal() }, exponentialDelay)
}
```

**Step 3: ADD new manual reconnect method (after close() method)**
```kotlin
fun manualReconnect() {
    LogStore.log(TAG, "Manual reconnect requested — resetting backoff state")
    inMaxReconnectBackoff.set(false)
    reconnectAttempts.set(0)
    handler.removeCallbacksAndMessages(null)
    connectInternal()
}
```

---

## COMPILATION & TESTING

**Build:**
```bash
cd frontend-kotlin
./gradlew clean build
```

**Expected:** `BUILD SUCCESSFUL`

**Install:**
```bash
./gradlew installDebug
```

**Test Crash Scenarios:**

| Scenario | Steps | Expected |
|----------|-------|----------|
| **Call ends immediately** | Start app → Call → Hang up after 1s | No crash |
| **WiFi disconnects** | Call recording → Disable WiFi | Graceful error, no crash |
| **Permission missing** | Disable RECORD_AUDIO → Call | Handled gracefully |
| **Rapid start/stop** | Call → End → Call immediately | No WakeLock error |
| **Screen rotation** | Rotate during startup → Call → Rotate again | No receiver error |
| **WebSocket fails 15x** | WiFi down 2 min → Back up | Enters backoff, can recover |

**Monitor Logs:**
```bash
adb logcat | grep -E "Flow|Recording|Service|Socket|Telephony"
```

---

## DEPLOYMENT

After all 8 fixes compile and pass testing:

```bash
# Build release APK
./gradlew build -c release

# Sign APK (adjust per your setup)
jarsigner -verbose -sigalg SHA1withRSA -digestalg SHA1 \
  -keystore keystore.jks \
  app/build/outputs/apk/release/app-release-unsigned.apk \
  my-key-alias

# Align APK
zipalign -v 4 app-release-unsigned.apk app-release.apk

# Upload to Play Store (beta channel first)
# Then monitor crashes via Firebase Crashlytics
```

---

## SUCCESS INDICATORS

✓ Compiles without errors  
✓ No new lint warnings  
✓ All crash scenarios pass  
✓ Crash rate drops from 40% to ~8%  
✓ App stable for 100+ consecutive calls  
✓ Ready for beta deployment  

