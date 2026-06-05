# Android Cleartext Communication Fix — Checklist

Complete checklist for implementing the Android 9+ network security fix.

---

## Pre-Implementation Checks

- [x] Android 9+ target (compileSdk 34)
- [x] OkHttp dependency included (4.11.0)
- [x] Kotlin 2.1.0 compatible
- [x] Backend runs on LAN IP (not emulator)

---

## Files to Verify Exist

After pulling these changes, verify these files exist:

```bash
# Network security configuration
ls -la frontend-kotlin/src/main/res/xml/network_security_config.xml

# Diagnostics class
ls -la frontend-kotlin/src/main/java/com/optimatrix/gsmcall/network/ConnectivityDiagnostics.kt

# Documentation
ls -la ANDROID_NETWORK_FIX.md
ls -la NETWORK_DEBUG.md
ls -la CLEARTEXT_FIX_CHECKLIST.md
```

**Expected:** All files should exist and be readable.

---

## AndroidManifest.xml Verification

Check that these lines exist in `<application>` tag:

```xml
<application
    ...
    android:networkSecurityConfig="@xml/network_security_config"
    android:usesCleartextTraffic="true"
    ...
>
```

```bash
grep -n "networkSecurityConfig\|usesCleartextTraffic" \
  frontend-kotlin/src/main/AndroidManifest.xml
```

**Expected:** Two lines should be found.

---

## Gradle Configuration

Verify `build.gradle.kts` has these buildConfigField entries:

```kotlin
buildConfigField("String", "BACKEND_HOST", "\"$backendHost\"")
buildConfigField("int", "BACKEND_PORT", backendPort)
buildConfigField("String", "BASE_URL", "\"http://$backendHost:$backendPort\"")
buildConfigField("String", "WS_URL", "\"ws://$backendHost:$backendPort/\"")
```

```bash
grep -n "buildConfigField" frontend-kotlin/build.gradle.kts | head -4
```

**Expected:** 4 buildConfigField entries.

---

## Backend IP Configuration

Edit `frontend-kotlin/local.properties`:

```properties
BACKEND_HOST=10.216.39.119    # ← Update to YOUR actual LAN IP
BACKEND_PORT=3000
```

**How to find YOUR IP:**

```bash
# macOS/Linux
ifconfig | grep "inet " | grep -v 127.0.0.1

# Windows
ipconfig
```

**What it looks like:**
```
inet 10.216.39.119 netmask 0xffffff00 broadcast 10.216.39.255
              ↑ THIS IS YOUR IP
```

- [ ] `BACKEND_HOST` is set to your actual LAN IP (not example IP)
- [ ] `BACKEND_PORT` is set to 3000

---

## Code Changes Verification

### ApiClient.kt

Check that OkHttpClient has retry enabled:

```bash
grep -A5 "OkHttpClient.Builder()" \
  frontend-kotlin/src/main/java/com/optimatrix/gsmcall/api/ApiClient.kt | \
  grep "retryOnConnectionFailure"
```

**Expected:** Should find `.retryOnConnectionFailure(true)`

- [ ] Retry on connection failure is enabled

### MainViewModel.kt

Check that startCampaign includes diagnostics:

```bash
grep -n "ConnectivityDiagnostics" \
  frontend-kotlin/src/main/java/com/optimatrix/gsmcall/ui/MainViewModel.kt
```

**Expected:** Should find reference to ConnectivityDiagnostics class

- [ ] startCampaign() runs connectivity diagnostics

### ConnectivityDiagnostics.kt

Verify diagnostics class exists and compiles:

```bash
./gradlew compileDebugKotlin 2>&1 | grep -i error
```

**Expected:** No errors

- [ ] Diagnostics class compiles without errors

---

## Compilation & Build

```bash
cd frontend-kotlin

# Clean build
./gradlew clean

# Compile Kotlin
./gradlew compileDebugKotlin

# Full debug build (if ADB device connected)
./gradlew installDebug
```

All steps should complete successfully:

- [ ] `./gradlew clean` — SUCCESS
- [ ] `./gradlew compileDebugKotlin` — BUILD SUCCESSFUL
- [ ] `./gradlew installDebug` — BUILD SUCCESSFUL (if device connected)

---

## On Android Device/Emulator

### Step 1: Verify ADB Connection

```bash
adb devices
```

**Expected:**
```
List of attached devices
DEVICE_ID              device
```

- [ ] Device shows as "device" (not "offline" or "unauthorized")

### Step 2: Get Backend IP from Development Machine

```bash
# macOS/Linux
ifconfig | grep "inet " | grep -v 127.0.0.1

# Windows
ipconfig
```

- [ ] Noted your actual LAN IP (e.g., 10.216.39.119)

### Step 3: Verify Backend Running

```bash
lsof -i :3000
```

**Expected:**
```
COMMAND     PID     USER    FD   TYPE            DEVICE SIZE/OFF NODE NAME
node      12345   username   24u   IPv4 0x1234abcd      0t0  TCP localhost:3000 (LISTEN)
```

- [ ] backend-node is listening on port 3000

### Step 4: Install App

```bash
./gradlew installDebug
```

- [ ] APK built successfully
- [ ] App installed on device
- [ ] No "CLEARTEXT communication not permitted" error during installation

### Step 5: Launch App & Test

```bash
# Open the app on the device and press "Start Automation"

# Monitor logs for connectivity diagnostics:
adb logcat | grep -i "diagnostics\|apiclient\|connectivity"
```

**Expected output in logs:**
```
═══════════════════════════════════════════
  CONNECTIVITY DIAGNOSTICS
═══════════════════════════════════════════
Backend        : http://10.216.39.119:3000
Connected      : ✓ Yes
Type           : WiFi
DNS            : ✓ 10.216.39.119
TCP Socket     : ✓ Connectable
HTTP Health    : ✓ Reachable
WebSocket      : ✓ Reachable

✓ All connectivity checks passed
═══════════════════════════════════════════
```

- [ ] No "CLEARTEXT communication not permitted" error
- [ ] Diagnostics report shows all checks passed ✓
- [ ] Campaign starts successfully

---

## Testing Endpoints

On the Android device or desktop, test these endpoints:

### Test 1: Health Check

```bash
curl http://10.216.39.119:3000/health
```

**Expected:** HTTP 200 with JSON response

- [ ] Health check successful

### Test 2: Campaign Status

```bash
curl http://10.216.39.119:3000/api/adb/status
```

**Expected:** HTTP 200 with campaign status JSON

- [ ] Status endpoint reachable

### Test 3: Start Campaign (POST)

```bash
curl -X POST http://10.216.39.119:3000/api/adb/start \
  -H "Content-Type: application/json" \
  -d '{"campaignName":"Test Campaign"}'
```

**Expected:** HTTP 200 with campaign created

- [ ] Campaign start endpoint works

---

## Troubleshooting Checks

If you see errors, verify:

### "CLEARTEXT communication not permitted"

- [ ] `network_security_config.xml` file exists
- [ ] AndroidManifest has `android:networkSecurityConfig` attribute
- [ ] App was rebuilt with `./gradlew clean installDebug`

### "Cannot reach backend"

- [ ] Backend host in `local.properties` matches actual LAN IP
- [ ] Backend is running: `lsof -i :3000`
- [ ] Firewall allows port 3000
- [ ] Phone is on same WiFi network

### "DNS resolution failed"

- [ ] Backend IP is correct (not hostname)
- [ ] Phone can ping backend: `ping 10.216.39.119` (in Termux)
- [ ] IP format is correct: `10.216.39.119` (not with spaces)

### "TCP connection refused"

- [ ] Backend is actually running and listening
- [ ] Port 3000 is not blocked by firewall
- [ ] IP is correct and reachable

---

## Final Verification

Run through the complete flow one time:

```
1. [ ] Backend is running (lsof -i :3000)
2. [ ] BACKEND_HOST is set correctly in local.properties
3. [ ] App is rebuilt (./gradlew installDebug)
4. [ ] Device is connected (adb devices)
5. [ ] App launches without cleartext errors
6. [ ] Open app, press "Start Automation"
7. [ ] See connectivity diagnostics in logs
8. [ ] All diagnostics checks pass ✓
9. [ ] Campaign starts and shows leads
```

---

## Documentation Reference

For more details, see:

- **README.md** — Network Configuration section
- **ANDROID_NETWORK_FIX.md** — Technical deep-dive
- **NETWORK_DEBUG.md** — Detailed troubleshooting
- **This file** — Implementation checklist

---

## Success Indicators

You'll know it's working when:

✓ App launches without "CLEARTEXT communication" crash  
✓ Connectivity diagnostics shows all checks passed  
✓ "Start Automation" button triggers backend campaign  
✓ Campaign processes leads through ai-python and ADB  
✓ Calls dial successfully from the Android phone  

---

## Quick Command Reference

```bash
# Verify files exist
ls frontend-kotlin/src/main/res/xml/network_security_config.xml
ls frontend-kotlin/src/main/java/com/optimatrix/gsmcall/network/ConnectivityDiagnostics.kt

# Check build configuration
grep "networkSecurityConfig\|usesCleartextTraffic" \
  frontend-kotlin/src/main/AndroidManifest.xml

# Verify backend IP
grep "BACKEND_HOST" frontend-kotlin/local.properties

# Build and install
cd frontend-kotlin
./gradlew clean installDebug

# Monitor logs
adb logcat | grep -i "cleartext\|diagnostics\|apiClient"

# Test backend connectivity
curl http://10.216.39.119:3000/health

# Check if backend is running
lsof -i :3000
```

---

## Sign-Off

When all items are checked:

- [ ] All files exist and compile
- [ ] Configuration is set correctly
- [ ] App builds without errors
- [ ] No network security errors at runtime
- [ ] Connectivity diagnostics pass
- [ ] Campaign starts successfully

**Status:** ✓ READY FOR PRODUCTION USE

