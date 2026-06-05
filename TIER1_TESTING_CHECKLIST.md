# TIER 1 Testing & Deployment Checklist

**Status:** Ready for Testing  
**Date:** June 4, 2026  
**Target Device:** Samsung SM-A176B (Android 14/15)

---

## PRE-TESTING SETUP

### Build & Installation
- [ ] Run: `./gradlew clean build`
- [ ] Verify: "BUILD SUCCESSFUL" message appears
- [ ] Connect Samsung SM-A176B via USB
- [ ] Enable USB debugging on device
- [ ] Run: `./gradlew installDebug`
- [ ] Verify app installs without errors
- [ ] App icon appears on home screen

### Pre-Test Device State
- [ ] WiFi connected to same network as backend
- [ ] Backend API running and accessible (`http://10.216.39.119:5000`)
- [ ] Backend WebSocket running (`ws://10.216.39.119:3000`)
- [ ] RECORD_AUDIO permission NOT denied
- [ ] CALL_PHONE permission NOT denied
- [ ] PHONE_STATE permission NOT denied
- [ ] Battery >= 50%
- [ ] Volume turned up for audio testing

---

## CRASH SCENARIO TESTING

### Scenario 1: Immediate Call End
**Expected Crash Type:** WebSocket null-pointer (FIX 1.1)

Steps:
1. Open app
2. Grant permissions
3. Start campaign
4. Wait for outgoing call
5. Hang up after 1 second
6. Observe: App continues running

Expected:
- ✅ No crash
- ✅ Status shows "call_ended"
- ✅ Flow logged to UI

Result: [ ] PASS [ ] FAIL

Logs to check:
```
adb logcat | grep "WebSocket send failed"  # Should NOT appear
```

---

### Scenario 2: WiFi Disconnect During Recording
**Expected Crash Type:** WebSocket send failure (FIX 1.7)

Steps:
1. Open app
2. Start campaign
3. Wait for call to be recording
4. Disable WiFi from Settings
5. Observe status updates
6. Re-enable WiFi
7. Observe recovery attempt

Expected:
- ✅ App doesn't crash
- ✅ Status shows "ws_disconnected"
- ✅ After WiFi back: attempts to reconnect
- ✅ Eventually reconnects

Result: [ ] PASS [ ] FAIL

Logs to check:
```
adb logcat | grep "reconnect\|Reconnecting"
```

---

### Scenario 3: Recording Permission Revoked
**Expected Crash Type:** Recording permission exception (FIX 1.5)

Steps:
1. Open app
2. Start campaign
3. Go to Settings → Apps → GSM Call AI
4. Permissions → Revoke RECORD_AUDIO
5. Return to app
6. Wait for call/recording
7. Observe error handling

Expected:
- ✅ No crash
- ✅ Status shows "recording_permission_denied"
- ✅ Flow ends gracefully
- ✅ "Permission denied" logged

Result: [ ] PASS [ ] FAIL

Logs to check:
```
adb logcat | grep "permission\|Permission"
```

---

### Scenario 4: Rapid Start/Stop Calls
**Expected Crash Type:** WakeLock double-release (FIX 1.4)

Steps:
1. Open app
2. Start campaign
3. Let call complete (or hang up immediately)
4. Immediately start another call
5. Repeat 5 times rapidly
6. Observe WakeLock state

Expected:
- ✅ No IllegalArgumentException
- ✅ No "WakeLock not held" errors
- ✅ Each call completes or ends without crash
- ✅ All 5 calls succeed

Result: [ ] PASS [ ] FAIL

Logs to check:
```
adb logcat | grep "WakeLock"  # Should see acquire/release, no errors
```

---

### Scenario 5: Screen Rotation During Startup
**Expected Crash Type:** BroadcastReceiver race condition (FIX 1.6)

Steps:
1. Open app (portrait)
2. Grant permissions
3. Rotate to landscape
4. Rotate back to portrait
5. Rotate during call if possible
6. Observe receiver state

Expected:
- ✅ No "Unregister failed" errors
- ✅ No BroadcastReceiver crashes
- ✅ Status updates still work during rotation
- ✅ No ANR (Application Not Responding)

Result: [ ] PASS [ ] FAIL

Logs to check:
```
adb logcat | grep "statusReceiver\|BroadcastReceiver"
```

---

### Scenario 6: Max Reconnect Attempts
**Expected Crash Type:** Max reconnect trap (FIX 1.8)

Steps:
1. Open app
2. Start campaign
3. Go to Settings → WiFi → Disable
4. Wait 2 minutes (watch connection attempts)
5. Re-enable WiFi
6. Observe reconnection

Expected:
- ✅ WebSocket attempts: 15 reconnects
- ✅ After 15 attempts, enters 5-min backoff
- ✅ WiFi restored → manual reconnect triggered
- ✅ Connection re-established
- ✅ App continues (NOT stuck/zombie)

Result: [ ] PASS [ ] FAIL

Logs to check:
```
adb logcat | grep "Max reconnect\|backoff\|Reconnecting"
```

---

## FUNCTIONALITY TESTING

### Audio Recording Test
- [ ] Recording starts when prompted
- [ ] Recording completes without crash
- [ ] Audio file saved to `/data/data/com.optimatrix.gsmcall/files/recordings/`
- [ ] WAV file is valid (not corrupted)

Steps:
```bash
# Check recordings
adb shell ls -la /data/data/com.optimatrix.gsmcall/files/recordings/
```

---

### WebSocket Connection Test
- [ ] "Connected" logged at startup
- [ ] Heartbeat messages sent every 30s
- [ ] Backend receives `android_ready` event
- [ ] All state changes broadcast to backend

Steps:
```bash
# Monitor WebSocket
adb logcat | grep "SocketManager\|Connected"
```

---

### BroadcastReceiver Stability Test
- [ ] Status updates received after each state change
- [ ] UI updates reflect backend status
- [ ] No duplicate receiver registrations
- [ ] Clean unregister on app close

Steps:
```bash
# Monitor receivers
adb logcat | grep "statusReceiver"
```

---

## STRESS TESTING

### 10 Consecutive Calls
- [ ] Start 10 calls in succession
- [ ] No memory leaks
- [ ] No crash after call 3, 5, or 10
- [ ] Final crash rate ~0%

Expected:
- All 10 calls complete successfully

---

### 2-Hour Continuous Operation
- [ ] Run app for 2 hours
- [ ] Let calls auto-dial continuously
- [ ] Monitor memory usage: `adb shell procrank | grep gsm`
- [ ] Monitor CPU: `adb shell top -n 1 | grep gsm`
- [ ] Battery drain <5%/hour

Expected:
- No memory growth
- CPU usage <5% idle
- Battery drain <5%/hour

---

## LOG REVIEW CHECKLIST

After each scenario, check logs for:

✅ **No EXCEPTION logs**
```bash
adb logcat | grep -i "EXCEPTION\|Exception"  # Should return nothing
```

✅ **No ERROR logs**
```bash
adb logcat | grep -i "ERROR\|Error"  # Should return nothing or only expected errors
```

✅ **No CRASH logs**
```bash
adb logcat | grep -i "CRASH\|crash"  # Should return nothing
```

✅ **No ANR logs**
```bash
adb logcat | grep -i "ANR\|anr"  # Should return nothing
```

✅ **Clean Flow logs**
```bash
adb logcat | grep "Flow\|$fid"  # Should see START...COMPLETE, no EXCEPTION
```

✅ **Recording logs**
```bash
adb logcat | grep "Recording"  # Should see started...finished
```

✅ **WebSocket logs**
```bash
adb logcat | grep "SocketManager\|Connected"  # Should see normal state transitions
```

---

## PERFORMANCE BENCHMARKS

After all tests, verify:

| Metric | Target | Measured |
|--------|--------|----------|
| CPU during call | <5% | [ ] _____ |
| Memory after 10 calls | <200MB | [ ] _____ |
| Battery drain | <5%/hour | [ ] _____ |
| Network bandwidth | <1MB/call | [ ] _____ |
| Audio quality | Clear | [ ] _____ |

---

## FINAL CHECKLIST BEFORE PRODUCTION

### Code Quality
- [ ] All 8 fixes implemented
- [ ] No syntax errors
- [ ] No diagnostic warnings
- [ ] Follows existing code patterns

### Testing
- [ ] All 6 crash scenarios pass
- [ ] 10 consecutive calls succeed
- [ ] 2-hour stress test complete
- [ ] Battery drain acceptable

### Logs
- [ ] No EXCEPTION logs
- [ ] No ERROR logs (unexpected)
- [ ] No CRASH logs
- [ ] No ANR logs
- [ ] All flows logged properly

### Device
- [ ] Tested on Samsung SM-A176B
- [ ] Android 14/15 compatible
- [ ] All permissions working
- [ ] Audio clear and stable

### Deployment
- [ ] Beta channel ready
- [ ] Release notes prepared
- [ ] Firebase Crashlytics enabled
- [ ] Monitoring plan in place

---

## GO/NO-GO DECISION

### GREEN (Go to Beta) ✅
- ✅ All crash scenarios pass
- ✅ No unexpected errors in logs
- ✅ Performance acceptable
- ✅ Ready for beta users

### RED (Fix Issues) ❌
- ❌ Any scenario fails
- ❌ Unexpected EXCEPTION or ERROR logs
- ❌ Battery drain >5%/hour
- ❌ Memory growth after calls

---

## BETA DEPLOYMENT

Once all tests pass:

```bash
# Build release APK
./gradlew build -c release

# Sign APK
jarsigner -verbose -sigalg SHA1withRSA -digestalg SHA1 \
  -keystore keystore.jks \
  app/build/outputs/apk/release/app-release-unsigned.apk \
  my-key-alias

# Align APK
zipalign -v 4 app-release-unsigned.apk app-release.apk

# Upload to Play Store Beta channel
# → Announce: "Beta 1.1 — Crash Stabilization"
# → Target: ~8% crash rate
# → Monitor: Firebase Crashlytics
```

---

## NOTES

- Record any crashes with full stack trace
- Screenshot any unexpected behavior
- Note performance issues if observed
- Report WiFi/network issues separately
- Time each scenario for performance tracking

---

## SIGN-OFF

**Tested by:** ________________  
**Date:** ________________  
**Device:** Samsung SM-A176B  
**Result:** [ ] PASS [ ] FAIL  
**Notes:** ________________________________________________

**Approved for Beta:** [ ] YES [ ] NO  
**Approved by:** ________________  
**Date:** ________________

---

