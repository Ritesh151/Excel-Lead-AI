# Network Diagnostic Toolkit — Complete Android ↔ Backend Connection Fix

**Status**: Diagnostic & Fix Guide  
**Date**: June 4, 2026  
**Goal**: Identify and fix the real-world issue preventing Android ↔ backend connection

---

## Quick Diagnosis Script

Run these checks **before** we fix anything:

### Step 1: Backend Verification (On PC)

```bash
# 1. Check backend is running
ps aux | grep node | grep -v grep
# Should show: node backend-node/src/index.js or similar

# 2. Check port 3000 is listening
netstat -tlnp | grep 3000
# Should show: tcp 0.0.0.0:3000 LISTEN

# 3. Test backend locally
curl -v http://localhost:3000/health
# Should return: {"status":"ok"} or similar

# 4. Find your PC's actual IP
ifconfig | grep "inet " | grep -v 127.0.0.1
# Look for: 192.168.X.X or 10.X.X.X

# 5. Test backend from PC using that IP
# Replace ACTUAL_IP below
curl -v http://ACTUAL_IP:3000/health
# Should return: {"status":"ok"}
```

### Step 2: Network Verification (On PC)

```bash
# 1. Check WiFi status
iwconfig  # Linux
networksetup -getinfo Wi-Fi  # macOS
ipconfig | findstr /C:"IPv4 Address"  # Windows

# 2. Check firewall
sudo ufw status  # Linux
# Windows: Settings → Firewall → Allow port 3000
# macOS: System Preferences → Security → Firewall

# 3. Verify Android on same network
# Connect Android to same WiFi
# Check: Phone WiFi settings → IP address should be 10.X.X.X or 192.168.X.X
```

### Step 3: Android Verification (On Phone)

```bash
# 1. Connect to WiFi (same as PC)
Settings → WiFi → Select same network as PC

# 2. Get phone's IP
Settings → WiFi → Current network → IP address
# Note this IP

# 3. Install app
./gradlew installDebug

# 4. Check initial connection
adb logcat | grep -i "network\|backend\|websocket"
# Look for connection attempts

# 5. Test from phone
adb shell ping 10.216.39.119
# Should work if backend PC is on same network
```

---

## Common Issues & Fixes

### Issue 1: Backend Not Listening on 0.0.0.0

**Symptoms**:
- `netstat -tlnp | grep 3000` shows `127.0.0.1:3000` instead of `0.0.0.0:3000`
- Backend unreachable from other machines

**Root Cause**: Backend still binding to localhost

**Fix**:
```javascript
// In backend-node/src/index.js
const HOST = '0.0.0.0';  // NOT '127.0.0.1' or 'localhost'
httpServer.listen(PORT, HOST, () => {
  console.log(`Server listening on 0.0.0.0:${PORT}`);
});
```

### Issue 2: Firewall Blocking Port 3000

**Symptoms**:
- `curl http://192.168.X.X:3000/health` times out or refuses connection
- Phone can't reach backend even on same WiFi

**Root Cause**: Windows/macOS firewall blocking inbound connections

**Fix**:

#### Windows
```powershell
# Open Admin PowerShell
New-NetFirewallRule -DisplayName "Node.js 3000" `
  -Direction Inbound -Action Allow -Protocol TCP -LocalPort 3000

# Or via GUI:
# Settings → Firewall → Allow an app → Add node.exe
```

#### macOS
```bash
# Via System Preferences:
# System Preferences → Security & Privacy → Firewall → Firewall Options
# Or via command:
sudo /usr/libexec/ApplicationFirewall/socketfilterfw --setglobalstate off
```

#### Linux
```bash
sudo ufw allow 3000/tcp
```

### Issue 3: Android Can't Find Backend IP

**Symptoms**:
- Phone connects to WiFi but can't ping backend PC
- Different subnets (phone on 192.168.X but PC on 10.X)

**Root Cause**: Android and PC on different networks

**Fix**:
```bash
# On PC, check WiFi network:
ifconfig | grep "inet " | grep -v 127.0.0.1

# On Phone:
Settings → WiFi → Current network → IP address

# They MUST be on same network (same first 3 octets)
# If not: Connect phone to same WiFi as PC
```

### Issue 4: DNS Resolution Failing

**Symptoms**:
- App shows: "DNS resolution failed"
- Can't connect to backend even with correct IP

**Root Cause**: Phone trying to resolve 10.216.39.119 as hostname

**Fix**:
App should use **IP directly**, not hostname:
```kotlin
// WRONG
val url = "http://backend.local:3000"  // Won't resolve on LAN

// RIGHT
val url = "http://10.216.39.119:3000"  // Direct IP
```

---

## Complete Verification Checklist

Run this **before** reporting issues:

### PC (Backend)
- [ ] Node.js running: `ps aux | grep node`
- [ ] Port 3000 open: `netstat -tlnp | grep 3000`
- [ ] Shows `0.0.0.0:3000` (NOT `127.0.0.1:3000`)
- [ ] Firewall allows 3000: Check OS firewall
- [ ] Backend responds: `curl http://localhost:3000/health`
- [ ] Backend responds from IP: `curl http://ACTUAL_IP:3000/health`
- [ ] IP is correct: `ifconfig`

### Network
- [ ] Phone on **same WiFi** as PC
- [ ] Phone IP: `192.168.X.X` or `10.X.X.X`
- [ ] PC IP: Same first 3 octets as phone
- [ ] No VPN active on phone
- [ ] No VPN active on PC
- [ ] Firewall allows connections from phone

### Android App
- [ ] APK built: `./gradlew build`
- [ ] APK installed: `adb install -r app-debug.apk`
- [ ] Manifest has `networkSecurityConfig`: Verified ✅
- [ ] Manifest has `usesCleartextTraffic="true"`: Verified ✅
- [ ] Config file exists: `res/xml/network_security_config.xml`
- [ ] Backend IP is correct: `local.properties`
- [ ] App can ping backend: `adb shell ping 10.216.39.119`

---

## Step-by-Step Connection Test

### Phase 1: Start Backend (5 min)

```bash
# Terminal 1
cd backend-node
npm start

# Expected output:
# 🚀 AI CALLING BACKEND — PRODUCTION NETWORK CONFIGURATION
# 🌍 EXTERNAL ACCESS (Android LAN):
#    ✓ HTTP  [0]: http://192.168.29.148:3000
#    ✓ WS    [0]: ws://192.168.29.148:3000/
```

**Note the IP shown**: Use this in next steps

### Phase 2: Verify Backend Reachable from PC (2 min)

```bash
# Terminal 2
# Use the IP shown in Phase 1
BACKEND_IP="192.168.29.148"  # Replace with actual

# Test 1: TCP connection
curl -v http://$BACKEND_IP:3000/health

# Expected: 200 OK, {"status":"ok"} or similar
```

### Phase 3: Connect Phone to WiFi (1 min)

```
Settings → WiFi → Connect to same network as PC
```

### Phase 4: Verify Phone IP (1 min)

```
Settings → WiFi → Current network → IP address
Note: Should be 192.168.X.X or 10.X.X.X
```

### Phase 5: Test from Phone (2 min)

```bash
# Terminal 3
adb shell ping 192.168.29.148  # Use IP from Phase 1

# Expected: 0% packet loss, replies received
```

### Phase 6: Build & Install App (5 min)

```bash
cd frontend-kotlin
./gradlew clean build -x lintDebug
./gradlew installDebug

# Expected: BUILD SUCCESSFUL
```

### Phase 7: Check Logs (2 min)

```bash
adb logcat | grep -i "backend\|websocket\|network"

# Expected:
# ✓ Backend connected (WebSocket)
# ✓ Diagnostics passed

# NOT:
# ✗ CLEARTEXT communication not permitted
# ✗ Cannot reach backend
```

### Phase 8: Test Campaign Start (3 min)

```
In app:
1. Click "Start Automation"
2. Watch diagnostics
3. Should pass all 5 checks
4. Campaign should start
```

---

## Real-World Troubleshooting Tree

```
╔═══════════════════════════════════════════════════════════════╗
║ Android Can't Reach Backend — Where's the Break?            ║
╚═══════════════════════════════════════════════════════════════╝

1. Is backend running?
   ├─ NO → Start backend: cd backend-node && npm start
   └─ YES → Continue

2. Is backend listening on 0.0.0.0:3000?
   ├─ NO (shows 127.0.0.1:3000) → Fix binding in index.js
   └─ YES → Continue

3. Does firewall allow port 3000?
   ├─ NO (netstat shows but curl hangs) → Allow port in firewall
   └─ YES → Continue

4. Is phone on same WiFi as PC?
   ├─ NO (different networks) → Connect phone to PC's WiFi
   └─ YES → Continue

5. Can phone ping PC?
   ├─ NO (adb shell ping fails) → Check WiFi settings
   └─ YES → Continue

6. Is backend IP correct in app?
   ├─ NO (wrong IP in local.properties) → Update IP
   └─ YES → Continue

7. Did app build successfully?
   ├─ NO (BUILD FAILED) → Run: ./gradlew clean build
   └─ YES → Continue

8. Did app install successfully?
   ├─ NO (adb install failed) → Reconnect device, retry
   └─ YES → Continue

9. Check app logs
   ├─ "CLEARTEXT not permitted" → Config not loaded (clean rebuild)
   ├─ "Cannot reach backend" → Backend not listening or firewall
   ├─ "Connection refused" → Backend not on 0.0.0.0
   └─ "✓ Backend connected" → SUCCESS!
```

---

## Network Diagnostics Commands by OS

### Windows

```batch
REM Check listening ports
netstat -ano | findstr :3000

REM Test connection
curl http://192.168.X.X:3000/health

REM Find PC IP
ipconfig | findstr /C:"IPv4 Address"

REM Check firewall rule for port 3000
netsh advfirewall firewall show rule name="Node.js 3000"

REM Allow port 3000 in firewall
netsh advfirewall firewall add rule name="Node.js 3000" dir=in action=allow protocol=tcp localport=3000
```

### macOS

```bash
# Check listening ports
lsof -i :3000

# Test connection
curl http://192.168.X.X:3000/health

# Find PC IP
ifconfig | grep "inet " | grep -v 127.0.0.1

# Check firewall
sudo /usr/libexec/ApplicationFirewall/socketfilterfw --getglobalstate

# Disable firewall (development only)
sudo /usr/libexec/ApplicationFirewall/socketfilterfw --setglobalstate off
```

### Linux

```bash
# Check listening ports
netstat -tlnp | grep 3000
# or
ss -tlnp | grep 3000

# Test connection
curl http://192.168.X.X:3000/health

# Find PC IP
ifconfig | grep "inet " | grep -v 127.0.0.1
# or
ip addr

# Check firewall
sudo ufw status

# Allow port 3000
sudo ufw allow 3000/tcp
```

---

## Android Phone Diagnostics

```bash
# 1. Check connected WiFi
adb shell settings get global wifi_on

# 2. Get phone IP
adb shell ip addr show | grep "inet "

# 3. Check DNS
adb shell getprop net.dns1

# 4. Ping backend (replace IP)
adb shell ping -c 4 10.216.39.119

# 5. Test TCP connection
adb shell timeout 5 bash -c 'cat < /dev/null > /dev/tcp/10.216.39.119/3000' && echo "OK" || echo "FAILED"

# 6. Check app logs
adb logcat | grep -i "network\|backend\|websocket" | head -50

# 7. Check persistent logs
adb logcat -b all | grep -i "error\|exception" | head -50
```

---

## Backend Startup Verification

Expected output when `npm start` runs:

```
═══════════════════════════════════════════════════════════════════
🚀 AI CALLING BACKEND — PRODUCTION NETWORK CONFIGURATION
═══════════════════════════════════════════════════════════════════
⏰ Started at: 2024-06-04T10:30:45.123Z
📍 Hostname: my-pc
🖥️  Platform: linux x64

📡 NETWORK BINDING:
   ✓ Binding address: 0.0.0.0 (all interfaces)
   ✓ Port: 3000
   ✓ Protocol: HTTP/1.1 with WebSocket upgrade

🌍 EXTERNAL ACCESS (Android LAN):
   ✓ HTTP  [0]: http://192.168.29.148:3000
   ✓ WS    [0]: ws://192.168.29.148:3000/

💻 LOCAL ACCESS (PC only):
   ✓ HTTP: http://localhost:3000
   ✓ WS:   ws://localhost:3000/

🛠️  SERVICES:
   ✓ Express: ready
   ✓ WebSocket: attached
   ✓ MongoDB: connected
   ✓ AI-Python: http://localhost:8000

📊 DEBUG ENDPOINTS:
   ✓ GET /health
   ✓ GET /api/debug/network
   ✓ GET /api/debug/socket
   ✓ GET /api/debug/backend
   ✓ GET /api/debug/tcp

⚠️  IMPORTANT FOR ANDROID:
   📲 Use IP: 192.168.29.148
   📲 HTTP:   http://192.168.29.148:3000
   📲 WS:     ws://192.168.29.148:3000/

═══════════════════════════════════════════════════════════════════
```

**If you DON'T see this**: Backend might not have started properly.

---

## Next: After Diagnostics

Once you've run the above checks:
1. **Share which step fails** (backend not binding, firewall, phone can't ping, etc.)
2. **Share the exact error message**
3. **I'll provide the specific fix** for that exact issue

---

## Summary

✅ **Use this toolkit to identify the exact issue**  
✅ **Follow the troubleshooting tree**  
✅ **Run the verification checklist**  
✅ **Report which step fails with the exact error**

**Then I can provide the precise fix for your specific situation.**

---

**Status**: Diagnostic guide ready  
**Next**: Run these checks and report results
