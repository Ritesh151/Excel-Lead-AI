# Network Testing Quick Start Guide
## 5-Minute Validation for GSM Automation

---

## PRE-FLIGHT CHECKLIST

### On PC:
- [ ] Backend-node running: `cd backend-node && npm start`
- [ ] Listening on 0.0.0.0:3000 (check output: "http://10.216.39.119:3000")
- [ ] Firewall allows port 3000 (Windows: Settings → Firewall → Allow app)
- [ ] MongoDB running (if required)

### On Android Phone:
- [ ] Connected to same WiFi as PC
- [ ] NOT on mobile data
- [ ] APK built with correct BACKEND_HOST (see below)

---

## STEP 1: BUILD APK WITH CORRECT BACKEND IP

### Get your PC's IP on WiFi:

**Windows:**
```powershell
ipconfig
# Look for "IPv4 Address" under your WiFi adapter
# Example: 10.216.39.119 or 192.168.1.100
```

**Linux:**
```bash
hostname -I
# Example: 10.216.39.119 192.168.1.50
```

**macOS:**
```bash
ifconfig | grep "inet " | grep -v 127.0.0.1
```

### Update local.properties:

**File:** `frontend-kotlin/local.properties`
```properties
BACKEND_HOST=10.216.39.119
BACKEND_PORT=3000
```

Replace `10.216.39.119` with YOUR PC's actual IP.

### Build APK:
```bash
cd frontend-kotlin
./gradlew assembleDebug

# Output: app/build/outputs/apk/debug/app-debug.apk
```

### Install on phone:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## STEP 2: VERIFY BACKEND IS RUNNING

### From PC terminal:

```bash
curl http://127.0.0.1:3000/health
```

**Expected response:**
```json
{
  "status": "ok",
  "service": "ai-calling-backend",
  "websocket": {
    "clients": 0,
    "android": false
  },
  "timestamp": "2026-01-23T10:30:00.000Z"
}
```

### From Android phone:

Open **Android Studio Logcat** and run the app:
```
adb logcat | grep -i "api\|socket\|network"
```

---

## STEP 3: RUN DIAGNOSTICS (BUILT-IN)

### In MainActivity:

Add this to your Activity's onCreate:

```kotlin
import com.optimatrix.gsmcall.startup.StartupNetworkValidator

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val validator = StartupNetworkValidator(this)
        validator.validateAsync { success ->
            if (success) {
                Log.i("App", "✓ Network Ready")
            } else {
                Log.e("App", "✗ Network Failed")
                Log.e("App", validator.getReport())
            }
        }
    }
}
```

### Expected output in logcat:

```
NetDiagnostics: ═══════════════════════════════════════════════════════
NetDiagnostics: NETWORK DIAGNOSTICS — FULL STACK VALIDATION
NetDiagnostics: ═══════════════════════════════════════════════════════
NetDiagnostics: → Checking WiFi connectivity...
NetDiagnostics: ✓ WiFi connected
NetDiagnostics: → Checking TCP connectivity to 10.216.39.119:3000...
NetDiagnostics: ✓ TCP reachable
NetDiagnostics: → Checking HTTP /health endpoint...
NetDiagnostics: ✓ HTTP health reachable
NetDiagnostics: → Checking WebSocket connectivity...
NetDiagnostics: ✓ WebSocket reachable
NetDiagnostics: Latency: 42ms
NetDiagnostics: RESULT: ✓ ALL CHECKS PASSED
```

---

## STEP 4: TEST HTTP ENDPOINT

### From Android (adb shell):

```bash
adb shell
ping 10.216.39.119          # Basic connectivity
curl http://10.216.39.119:3000/health      # HTTP test
curl http://10.216.39.119:3000/api/debug/network  # Get backend IPs
```

**Expected response for /api/debug/network:**
```json
{
  "localIps": ["10.216.39.119"],
  "primaryIp": "10.216.39.119",
  "backend": {
    "http": "http://10.216.39.119:3000",
    "ws": "ws://10.216.39.119:3000/",
    "binding": "0.0.0.0"
  }
}
```

---

## STEP 5: TEST WEBSOCKET

### From Android logcat:

```
SocketManager: Connecting to WebSocket: ws://10.216.39.119:3000/
SocketManager: ✓ WebSocket OPEN (HTTP 101)
SocketManager: → android_ready
SocketManager: ← connected { message: 'AI Calling backend ready' }
WebSocketServer: ✓ Android client registered from 10.216.39.X
```

---

## STEP 6: TEST HEALTH CHECK API

### From Android (ApiClient):

```kotlin
val client = ApiClient(context)
val health = client.checkHealth()

if (health.online) {
    Log.i("Test", "✓ Backend online")
    Log.i("Test", "  WebSocket clients: ${health.websocketClients}")
    Log.i("Test", "  Android connected: ${health.androidConnected}")
} else {
    Log.e("Test", "✗ Backend offline")
}
```

**Expected output:**
```
Test: ✓ Backend online
Test:   WebSocket clients: 1
Test:   Android connected: true
```

---

## COMMON ISSUES & FIXES

### Issue 1: "Connection refused"

**Cause:** Backend not running or not listening on 0.0.0.0

**Fix:**
```bash
# Terminal on PC:
cd backend-node && npm start

# Check output includes:
# ✓ Binding address: 0.0.0.0 (all interfaces)
# ✓ HTTP  [0]: http://10.216.39.119:3000
```

### Issue 2: "Network unreachable" or "No route to host"

**Cause:** Phone not on same WiFi, or IP address wrong

**Fix:**
```bash
# On phone (adb shell):
ping 10.216.39.119    # Should respond

# If fails, get correct PC IP:
# PC Windows: ipconfig → IPv4 Address
# Update local.properties with correct IP
# Rebuild APK
```

### Issue 3: "HTTP timeout after 8000ms"

**Cause:** Backend not responding or network latency high

**Fix:**
```bash
# Check backend process:
ps aux | grep node

# Check port listening:
netstat -tulpn | grep 3000

# Test locally on PC:
curl http://127.0.0.1:3000/health

# If fails, check backend logs:
tail -f backend-node/logs/*.log
```

### Issue 4: "WebSocket: Connection error"

**Cause:** Firewall blocking port 3000 or backend WebSocket not initialized

**Fix:**
```powershell
# Windows: Allow Node.js through firewall
New-NetFirewallRule -DisplayName "backend-node" -Direction Inbound -LocalPort 3000 -Protocol TCP -Action Allow

# Verify rule created:
Get-NetFirewallRule -DisplayName "backend-node"

# Test connection:
telnet 10.216.39.119 3000
```

---

## AUTOMATED TESTING SCRIPT

### Create `test-network.sh`:

```bash
#!/bin/bash

BACKEND_HOST="10.216.39.119"
BACKEND_PORT="3000"

echo "Network Testing for GSM Automation"
echo "===================================="
echo ""

# Test 1: Ping
echo "1. Testing ping to $BACKEND_HOST..."
if ping -c 1 $BACKEND_HOST > /dev/null 2>&1; then
    echo "   ✓ PASS"
else
    echo "   ✗ FAIL"
    exit 1
fi

# Test 2: HTTP
echo "2. Testing HTTP /health endpoint..."
RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" http://$BACKEND_HOST:$BACKEND_PORT/health)
if [ "$RESPONSE" == "200" ]; then
    echo "   ✓ PASS (HTTP $RESPONSE)"
else
    echo "   ✗ FAIL (HTTP $RESPONSE)"
    exit 1
fi

# Test 3: TCP socket
echo "3. Testing TCP socket on port $BACKEND_PORT..."
if timeout 2 bash -c "echo '' > /dev/tcp/$BACKEND_HOST/$BACKEND_PORT" 2>/dev/null; then
    echo "   ✓ PASS"
else
    echo "   ✗ FAIL"
    exit 1
fi

# Test 4: Network interface
echo "4. Checking backend network interface..."
curl -s http://$BACKEND_HOST:$BACKEND_PORT/api/debug/network | grep -q "primaryIp"
if [ $? -eq 0 ]; then
    echo "   ✓ PASS"
else
    echo "   ✗ FAIL"
    exit 1
fi

echo ""
echo "✓ All tests passed!"
echo "Ready to start automation."
```

### Run it:
```bash
chmod +x test-network.sh
./test-network.sh
```

---

## VALIDATION FLOW BEFORE CAMPAIGN START

```
User clicks "Start Campaign"
           ↓
[StartupNetworkValidator.validateAsync()]
           ↓
   WiFi connected?  ──NO──> Show error & stop
           │
          YES
           ↓
   TCP reachable?   ──NO──> Show error & stop
           │
          YES
           ↓
   HTTP responding? ──NO──> Show error & stop
           │
          YES
           ↓
   WebSocket OK?    ──NO──> Show error & stop
           │
          YES
           ↓
   ✓ All checks passed
           ↓
   Start campaign
           ↓
   Backend: POST /api/adb/start
           ↓
   Android: Display campaign progress
```

---

## SUCCESS INDICATORS

When everything works:

✓ **Logcat shows:**
```
ApiClient: Health check → GET http://10.216.39.119:3000/health
ApiClient: Health response: 200
SocketManager: ✓ WebSocket OPEN (HTTP 101)
SocketManager: → android_ready
CallAutomationService: Campaign started: 20 leads
SocketManager: ← call_started { phone: "9876543210" }
```

✓ **Backend shows:**
```
[WS] New connection from 10.X.X.X:12345
[WS] ✓ Android client registered
[Req] POST /api/adb/start from 10.X.X.X
[Campaign] Started: camp_123
[WS] → campaign_progress { processed: 1, total: 20 }
```

✓ **User sees:**
```
✓ Backend: Connected
✓ WebSocket: Connected
Campaign: 1/20 leads processed
Call initiated: 9876543210
```

---

## DEBUGGING CHECKLIST

- [ ] `npm start` shows binding to 0.0.0.0:3000
- [ ] `curl http://10.216.39.119:3000/health` returns 200
- [ ] `adb logcat` shows "WebSocket OPEN"
- [ ] Phone ping to PC succeeds
- [ ] Firewall allows port 3000
- [ ] Phone and PC on same WiFi
- [ ] APK built with correct BACKEND_HOST
- [ ] backend-node and ai-python both running

---

**Ready to go!** 🚀

Once all checks pass, click "Start Campaign" and monitor the logs.
