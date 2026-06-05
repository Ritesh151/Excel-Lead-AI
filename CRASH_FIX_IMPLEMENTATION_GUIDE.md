# Crash Fix Implementation Guide

## Quick Start: Emergency Stabilization (2 hours)

This guide provides step-by-step implementation for TIER 1 critical fixes. Follow in order.

---

## FIX #1: CallAutomationService - Null Socket Manager (15 mins)

**Problem:** WebSocket reconnection fails silently, later calls crash when trying to use null socketManager

**Files to modify:** `CallAutomationService.kt`

### Step 1: Add null-safe helper method (after line 220)

```kotlin
private fun broadcastViaSocket(action: suspend () -> Unit) {
    if (socketManager != null && socketManager!!.connected) {
        try {
            serviceScope.launch { action() }
        } catch (ex: Exception) {
            LogStore.log("Service", "Socket broadcast failed: ${ex.message}")
        }
    } else {
        LogStore.log("Service", "WebSocket not connected — message dropped")
    }
}
```

### Step 2: Update onSessionUpdated() (line ~145)

**Before:**
```kotlin
socketManager?.sendCallState(phase, session.remoteNumber)
```

**After:**
```kotlin
if (socketManager?.connected == true) {
    try {
        socketManager?.sendCallState(phase, session.remoteNumber)
    } catch (ex: Exception) {
        LogStore.log("Telephony", "Failed to send state: ${ex.message}")
    }
}
```

### Step 3: Test
- Start app, let service initialize
- Pull WiFi cable
- Wait 30 seconds (watchdog reconnect attempts)
- Reconnect WiFi
- Trigger a call
- **Expected:** Service continues running, no NPE

**Time to verify:** 2 mins

---

## FIX #2: CallAutomationService - Wrap Flow in Try-Catch (20 mins)

**Problem:** Uncaught exceptions in runAutomationFlow crash the entire service

**Files to modify:** `CallAutomationService.kt`

### Step 1: Backup original runAutomationFlow() method (copy to comment)

### Step 2: Wrap entire method body (line ~195 onwards)

**Replace the entire `private suspend fun runAutomationFlow` with:**

```kotlin
private suspend fun runAutomationFlow(session: CallSession) {
    val fid = flowCounter.get()
    LogStore.log("Flow", "[$fid] START number=${session.remoteNumber}")
    withContext(Dispatchers.Main) { acquireWakeLock() }

    try {
        // ── ENTIRE ORIGINAL FLOW CODE GOES HERE (indent by 2 more spaces) ──
        // [Copy all the code from "broadcastStatus("call_connected")" to "LogStore.log("Flow", "[$fid] COMPLETE")"]
        // ────────────────────────────────────────────────────────────────
        
    } catch (ex: CancellationException) {
        // Expected when service is stopping — re-throw to let coroutine system handle it
        throw ex
    } catch (ex: Exception) {
        LogStore.log("Flow", "[$fid] CAUGHT EXCEPTION: ${ex.javaClass.simpleName}: ${ex.message}")
        LogStore.log("Flow", "[$fid] Stack trace: ${ex.stackTraceToString()}")
        broadcastLog("[$fid] CRASH RECOVERED: ${ex.message}")
        broadcastStatus("error_exception")
        try {
            socketManager?.sendCallState("error_exception", session.remoteNumber, fid)
        } catch (_: Exception) {}
        endCallSafely()
    } finally {
        sessionRunning.set(false)
        withContext(Dispatchers.Main) {
            audioRoutingManager.restoreAudioMode()
            releaseWakeLock()
        }
        LogStore.log("Flow", "[$fid] Flow finally block complete")
    }
}
```

### Step 3: Test
- Start app
- Trigger a call
- While recording, rapidly toggle WiFi off/on
- **Expected:** Flow logs "CAUGHT EXCEPTION", service doesn't crash

**Time to verify:** 3 mins

---

## FIX #3: RecordingManager - AudioRecord Null Initialization (25 mins)

**Problem:** AudioRecord.build() returns null-initialized recorder on Samsung, crashes immediately

**Files to modify:** `RecordingManager.kt`

### Step 1: Find and select the entire recordResponse() function (lines ~35-95)

### Step 2: Replace with this version:

```kotlin
fun recordResponse(durationSeconds: Int): File? {
    val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    if (minBuffer == AudioRecord.ERROR || minBuffer == AudioRecord.ERROR_BAD_VALUE) {
        LogStore.log("Recording", "Invalid AudioRecord buffer size, cannot record")
        return null
    }
    
    val bufferSize = minBuffer.coerceAtLeast(sampleRate * bytesPerSample)
    val tempBuffer = ByteArray(bufferSize)
    val outFile = File(recordsDir, "response_${System.currentTimeMillis()}.wav")
    val rawStream = ByteArrayOutputStream()

    var recorder: AudioRecord? = null
    
    return try {
        // Try VOICE_COMMUNICATION first
        try {
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
            
            // CRITICAL: Wait for async initialization on Samsung
            Thread.sleep(100)
            
            if (recorder?.state != AudioRecord.STATE_INITIALIZED) {
                LogStore.log("Recording", "VOICE_COMMUNICATION failed to init, trying MIC fallback")
                recorder?.release()
                recorder = null
            }
        } catch (ex: Exception) {
            LogStore.log("Recording", "VOICE_COMMUNICATION exception: ${ex.javaClass.simpleName}, fallback to MIC")
            recorder?.release()
            recorder = null
        }

        // Fallback to MIC if VOICE_COMMUNICATION didn't work
        if (recorder == null) {
            try {
                recorder = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize)
                Thread.sleep(100)
                
                if (recorder?.state != AudioRecord.STATE_INITIALIZED) {
                    LogStore.log("Recording", "MIC also failed to initialize")
                    recorder?.release()
                    return null
                }
            } catch (ex: Exception) {
                LogStore.log("Recording", "MIC fallback exception: ${ex.javaClass.simpleName}: ${ex.message}")
                recorder?.release()
                return null
            }
        }

        // At this point, recorder should be non-null and initialized
        val activeRecorder = recorder ?: run {
            LogStore.log("Recording", "ERROR: recorder is null after both initialization attempts!")
            return null
        }

        // Start recording
        activeRecorder.startRecording()
        LogStore.log("Recording", "Recording started for $durationSeconds seconds")

        var totalBytes = 0
        val endTime = System.currentTimeMillis() + durationSeconds * 1000L
        
        while (System.currentTimeMillis() < endTime) {
            val read = activeRecorder.read(tempBuffer, 0, tempBuffer.size)
            if (read > 0) {
                rawStream.write(tempBuffer, 0, read)
                totalBytes += read
            } else if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE) {
                LogStore.log("Recording", "AudioRecord.read returned error code $read, stopping")
                break
            }
        }

        activeRecorder.stop()
        LogStore.log("Recording", "Recording stopped: $totalBytes bytes total")

        val pcmData = trimSilence(rawStream.toByteArray())
        WavFileWriter.writeWavFile(outFile, pcmData, sampleRate, 1, 16)
        LogStore.log("Recording", "Saved response to ${outFile.absolutePath}")
        
        outFile

    } catch (ex: Exception) {
        LogStore.log("Recording", "Recording flow exception: ${ex.javaClass.simpleName}: ${ex.message}")
        if (outFile.exists()) outFile.delete()
        null
    } finally {
        try {
            recorder?.stop()
        } catch (_: Exception) {}
        try {
            recorder?.release()
        } catch (_: Exception) {}
        try {
            rawStream.close()
        } catch (_: Exception) {}
    }
}
```

### Step 3: Test
- Start app with WiFi disabled
- Trigger a call
- Record should work or fail gracefully (not crash)
- Check logcat for "Recording started" or "Recording...exception"
- **Expected:** No crashes, proper fallback

**Time to verify:** 3 mins

---

## FIX #4: CallAutomationService - WakeLock Double-Release (15 mins)

**Problem:** IllegalArgumentException when WakeLock released twice

**Files to modify:** `CallAutomationService.kt`

### Step 1: Add state tracking at class level (after line ~55)

```kotlin
private val wakeLockAcquired = AtomicBoolean(false)
```

### Step 2: Replace acquireWakeLock() (line ~293)

**Before:**
```kotlin
private fun acquireWakeLock() {
    if (!wakeLock.isHeld) {
        wakeLock.acquire(WAKELOCK_TIMEOUT_MS)
        LogStore.log("Service", "WakeLock acquired")
    }
}
```

**After:**
```kotlin
private fun acquireWakeLock() {
    if (wakeLockAcquired.compareAndSet(false, true)) {
        try {
            wakeLock.acquire(WAKELOCK_TIMEOUT_MS)
            LogStore.log("Service", "WakeLock acquired ($WAKELOCK_TIMEOUT_MS ms)")
        } catch (ex: Exception) {
            wakeLockAcquired.set(false)
            LogStore.log("Service", "WakeLock acquire failed: ${ex.message}")
        }
    }
}
```

### Step 3: Replace releaseWakeLock() (line ~300)

**Before:**
```kotlin
private fun releaseWakeLock() {
    if (wakeLock.isHeld) { 
        wakeLock.release()
        LogStore.log("Service", "WakeLock released") 
    }
}
```

**After:**
```kotlin
private fun releaseWakeLock() {
    if (wakeLockAcquired.compareAndSet(true, false)) {
        try {
            wakeLock.release()
            LogStore.log("Service", "WakeLock released")
        } catch (ex: Exception) {
            LogStore.log("Service", "WakeLock release error: ${ex.javaClass.simpleName}")
            // Force reset if release fails
            wakeLockAcquired.set(false)
        }
    }
}
```

### Step 4: Test
- Start app
- Trigger call
- Let it complete
- Immediately trigger another call while first is still ending
- **Expected:** No IllegalArgumentException, both calls complete

**Time to verify:** 2 mins

---

## FIX #5: MainActivity - BroadcastReceiver Registration (12 mins)

**Problem:** Receiver unregistered even if registration failed

**Files to modify:** `MainActivity.kt`

### Step 1: Add state tracking (after line ~20)

```kotlin
private var isReceiverRegistered = AtomicBoolean(false)
```

Also add import:
```kotlin
import java.util.concurrent.atomic.AtomicBoolean
```

### Step 2: Update onCreate() registration (line ~44)

**Before:**
```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
} else {
    @Suppress("DEPRECATION")
    registerReceiver(statusReceiver, filter)
}
```

**After:**
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

### Step 3: Update onDestroy() (line ~107)

**Before:**
```kotlin
override fun onDestroy() {
    super.onDestroy()
    unregisterReceiver(statusReceiver)
}
```

**After:**
```kotlin
override fun onDestroy() {
    super.onDestroy()
    if (isReceiverRegistered.compareAndSet(true, false)) {
        try {
            unregisterReceiver(statusReceiver)
            LogStore.log("MainActivity", "statusReceiver unregistered")
        } catch (ex: Exception) {
            LogStore.log("MainActivity", "statusReceiver unregister failed: ${ex.message}")
        }
    }
}
```

### Step 4: Test
- Start app
- Rotate screen multiple times rapidly
- **Expected:** No IllegalArgumentException crashes

**Time to verify:** 2 mins

---

## FIX #6: RecordingManager - Try-Catch the Recording Call (8 mins)

**Problem:** Unhandled exceptions in recordingManager.recordResponse()

**Files to modify:** `CallAutomationService.kt`

### Step 1: Find recording call (line ~256)

**Before:**
```kotlin
val responseFile = withContext(Dispatchers.IO) {
    recordingManager.recordResponse(RECORDING_DURATION_SEC)
}

if (responseFile == null) {
    val msg = "[$fid] Recording failed"
    // ...
    return
}
```

**After:**
```kotlin
val responseFile = try {
    withContext(Dispatchers.IO) {
        recordingManager.recordResponse(RECORDING_DURATION_SEC)
    }
} catch (ex: SecurityException) {
    val msg = "[$fid] Recording permission denied: ${ex.message}"
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

### Step 2: Test
- Start app without RECORD_AUDIO permission
- Try to make a call
- **Expected:** Graceful error message, no crash

**Time to verify:** 2 mins

---

## FIX #7: SocketManager - Check Send Return Value (18 mins)

**Problem:** WebSocket.send() can return false, indicating message wasn't sent, but caller assumes success

**Files to modify:** `SocketManager.kt`

### Step 1: Modify sendEvent() method (line ~70)

**Before:**
```kotlin
fun sendEvent(event: String) {
    if (isConnected.get()) {
        socket?.send(event)
    } else {
        LogStore.log(TAG, "Not connected — dropped: ${event.take(80)}")
    }
}
```

**After:**
```kotlin
fun sendEvent(event: String): Boolean {
    if (!isConnected.get()) {
        LogStore.log(TAG, "Socket not connected — dropped: ${event.take(80)}")
        return false
    }
    
    return try {
        val sent = socket?.send(event) ?: false
        if (!sent) {
            LogStore.log(TAG, "WebSocket.send() returned false — connection dying, scheduling reconnect")
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

### Step 2: Update sendJson() to return status (line ~125)

**Before:**
```kotlin
private fun sendJson(map: Map<String, Any?>) {
    if (!isConnected.get()) {
        LogStore.log(TAG, "Not connected — dropping ${map["event"]}")
        return
    }
    try {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        val sent = socket?.send(obj.toString()) ?: false
        if (!sent) LogStore.log(TAG, "send() returned false for ${map["event"]}")
    } catch (ex: Exception) {
        LogStore.log(TAG, "sendJson exception: ${ex.message}")
    }
}
```

**After:**
```kotlin
private fun sendJson(map: Map<String, Any?>): Boolean {
    if (!isConnected.get()) {
        LogStore.log(TAG, "Not connected — dropping ${map["event"]}")
        return false
    }
    return try {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        val sent = socket?.send(obj.toString()) ?: false
        if (!sent) {
            LogStore.log(TAG, "send() returned false for ${map["event"]} — reconnecting")
            isConnected.set(false)
            scheduleReconnect()
        }
        sent
    } catch (ex: Exception) {
        LogStore.log(TAG, "sendJson exception: ${ex.javaClass.simpleName}: ${ex.message}")
        isConnected.set(false)
        scheduleReconnect()
        false
    }
}
```

### Step 3: Update all sendJson() callers to return Boolean (lines ~55-65)

**Before:**
```kotlin
fun sendReady() = sendJson(mapOf(...))
fun sendCallState(...) = sendJson(mapOf(...))
// etc.
```

**After:**
```kotlin
fun sendReady(): Boolean = sendJson(mapOf(
    "event" to "android_ready",
    "ts" to System.currentTimeMillis(),
))

fun sendCallState(state: String, phone: String?, flowId: Int = 0): Boolean = sendJson(mapOf(
    "event" to "call_state",
    "state" to state,
    "phone" to (phone ?: ""),
    "flowId" to flowId,
    "ts" to System.currentTimeMillis(),
))

fun sendPlaybackResult(fileName: String, strategy: String, success: Boolean): Boolean = sendJson(mapOf(
    "event" to "playback_result",
    "file" to fileName,
    "strategy" to strategy,
    "success" to success,
    "ts" to System.currentTimeMillis(),
))

fun sendRecordingSaved(filePath: String, durationSec: Int): Boolean = sendJson(mapOf(
    "event" to "recording_saved",
    "path" to filePath,
    "duration" to durationSec,
    "ts" to System.currentTimeMillis(),
))

fun sendWatchdog(): Boolean = sendJson(mapOf(
    "event" to "watchdog",
    "ts" to System.currentTimeMillis(),
))
```

### Step 4: Update CallAutomationService to check return values

In `runAutomationFlow()`, update all socketManager?.send*() calls:

**Before:**
```kotlin
socketManager?.sendCallState("connected", session.remoteNumber, fid)
```

**After:**
```kotlin
val sent = socketManager?.sendCallState("connected", session.remoteNumber, fid) ?: false
if (!sent) {
    LogStore.log("Flow", "[$fid] Failed to send connected state to backend")
}
```

### Step 5: Test
- Start app
- While recording, pull WiFi cable
- Push WiFi back in
- **Expected:** Service detects send failures, schedules reconnect, no message loss

**Time to verify:** 3 mins

---

## FIX #8: SocketManager - Max Reconnect Attempts Backoff (20 mins)

**Problem:** After 15 failed reconnect attempts, WebSocket gives up permanently

**Files to modify:** `SocketManager.kt`

### Step 1: Add backoff tracking (after line ~50)

```kotlin
private var lastMaxReconnectTime = 0L
private var inMaxReconnectBackoff = AtomicBoolean(false)
```

### Step 2: Replace scheduleReconnect() (line ~82)

**Before:**
```kotlin
private fun scheduleReconnect() {
    val attempt = reconnectAttempts.incrementAndGet()
    if (attempt > MAX_RECONNECT_ATTEMPTS) {
        LogStore.log(TAG, "Max reconnect attempts ($MAX_RECONNECT_ATTEMPTS) reached — stopping")
        return
    }
    val delay = RECONNECT_DELAY_BASE_MS * minOf(attempt, 5).toLong()
    LogStore.log(TAG, "Reconnecting in ${delay}ms (attempt $attempt)")
    handler.postDelayed({ connectInternal() }, delay)
}
```

**After:**
```kotlin
private fun scheduleReconnect() {
    val attempt = reconnectAttempts.incrementAndGet()
    
    if (attempt > MAX_RECONNECT_ATTEMPTS) {
        // Enter long backoff to avoid hammering the network
        if (inMaxReconnectBackoff.compareAndSet(false, true)) {
            lastMaxReconnectTime = System.currentTimeMillis()
            LogStore.log(TAG, "Max reconnect attempts ($MAX_RECONNECT_ATTEMPTS) reached — entering 5-minute backoff")
            handler.postDelayed({
                if (inMaxReconnectBackoff.compareAndSet(true, false)) {
                    LogStore.log(TAG, "Exiting backoff, resetting attempts")
                    reconnectAttempts.set(0)
                    connectInternal()
                }
            }, 5 * 60 * 1_000L) // 5 minutes
        }
        return
    }
    
    // Exponential backoff: 3s, 6s, 12s, 24s, 48s, 48s, 48s, ...
    val exponentialDelay = RECONNECT_DELAY_BASE_MS * (1L shl minOf(attempt - 1, 4))
    LogStore.log(TAG, "Reconnect attempt $attempt/$MAX_RECONNECT_ATTEMPTS in ${exponentialDelay}ms")
    handler.postDelayed({ connectInternal() }, exponentialDelay)
}
```

### Step 3: Add manual reconnect method (after close())

```kotlin
fun manualReconnect() {
    LogStore.log(TAG, "Manual reconnect requested — resetting state")
    inMaxReconnectBackoff.set(false)
    reconnectAttempts.set(0)
    handler.removeCallbacksAndMessages(null)
    connectInternal()
}
```

### Step 4: Test
- Start app, let it stabilize
- Simulate 15 failed network attempts (disconnect WiFi, let reconnect fail)
- Pull WiFi after attempts exhausted
- Reconnect WiFi (should NOT work yet — in backoff)
- Wait 30 seconds, call manualReconnect() (should work)
- **Expected:** Enters backoff, can still recover with manual reconnect

**Time to verify:** 5 mins

---

## VALIDATION CHECKLIST

After implementing all 8 fixes, verify:

```
[  ] Fix #1: socketManager null-checks added
[  ] Fix #2: runAutomationFlow() wrapped in try-catch
[  ] Fix #3: RecordingManager has dual init attempt + sleep
[  ] Fix #4: WakeLock state tracked with AtomicBoolean
[  ] Fix #5: BroadcastReceiver registration tracked
[  ] Fix #6: recordingManager.recordResponse() calls wrapped
[  ] Fix #7: SocketManager send*() methods return Boolean
[  ] Fix #8: SocketManager respects MAX_RECONNECTS with backoff

[  ] Compile without errors: ./gradlew clean build
[  ] No new lint warnings: ./gradlew lint
[  ] Run on real Samsung SM-A176B device

PRODUCTION TEST SCENARIOS:
[  ] Scenario 1: Call ends immediately → No crash
[  ] Scenario 2: WiFi disconnects → Graceful error, not crash
[  ] Scenario 3: Recording permission missing → Logged gracefully
[  ] Scenario 4: Backend unreachable → Enters backoff
[  ] Scenario 5: Rapid start/stop calls → No WakeLock errors
[  ] Scenario 6: Screen rotates during startup → No receiver errors
```

---

## Estimated Times

| Fix | Implementation | Testing | Total |
|-----|----------------|---------|-------|
| #1: Socket null-checks | 10 min | 2 min | 12 min |
| #2: Try-catch wrapper | 15 min | 3 min | 18 min |
| #3: AudioRecord init | 20 min | 3 min | 23 min |
| #4: WakeLock tracking | 12 min | 2 min | 14 min |
| #5: Receiver tracking | 10 min | 2 min | 12 min |
| #6: Recording try-catch | 8 min | 2 min | 10 min |
| #7: Send return value | 15 min | 3 min | 18 min |
| #8: Reconnect backoff | 18 min | 5 min | 23 min |
| **TOTAL** | **108 min** | **22 min** | **130 min** |

**Total Time: ~2.5 hours (including build and validation)**

---

## Deployment Checklist

Before pushing to production:

```
[  ] All 8 fixes implemented
[  ] Code compiles with no errors
[  ] No new lint warnings introduced
[  ] Tested on real Samsung SM-A176B (Android 14/15)
[  ] Tested all 6 crash scenarios without crashes
[  ] Backend connectivity verified
[  ] WebSocket reconnection verified
[  ] Audio playback verified
[  ] Recording verified
[  ] 10+ consecutive calls completed successfully
[  ] Log output reviewed for errors
[  ] Firebase Crashlytics enabled (optional but recommended)
[  ] APK signed and ready for upload
```

