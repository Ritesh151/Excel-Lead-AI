# Network Debugging Guide

Complete network troubleshooting for Android cleartext traffic and backend connectivity.

---

## Quick Checklist

- [ ] Backend host IP is correct in `frontend-kotlin/local.properties`
- [ ] Backend IP matches your actual LAN IP (not emulator IP)
- [ ] `network_security_config.xml` exists at `frontend-kotlin/src/main/res/xml/`
- [ ] AndroidManifest has `android:networkSecurityConfig="@xml/network_security_config"`
- [ ] Android app is rebuilt: `./gradlew installDebug`
- [ ] Phone and backend are on same WiFi network
- [ ] backend-node is running on port 3000
- [ ] No firewall blocking port 3000

---

## Network Architecture

```
┌─────────────────────────────────────────────────┐
│  Local Development Network (10.216.39.0/24)    │
├─────────────────────────────────────────────────┤
│                                                 │
│  Your Desktop/Laptop (backend-node runs here) │
│  IP: 10.216.39.119                             │
│  PORT: 3000 (http)                             │
│  PORT: 8000 (ai-python)                        │
│                                                 │
│  ↕️  WiFi / LAN Cable                           │
│                                                 │
│  Android Phone (on same network)              │
│  IP: 10.216.39.XXX (DHCP assigned)            │
│  Connects to: http://10.216.39.119:3000       │
│  WebSocket:   ws://10.216.39.119:3000/        │
│                                                 │
└─────────────────────────────────────────────────┘
```

---

## Configuration Files

### 1. Backend IP Configuration

**File:** `frontend-kotlin/local.properties`

```properties
sdk.dir=/path/to/Android/Sdk
BACKEND_HOST=10.216.39.119    # ← Update to YOUR IP
BACKEND_PORT=3000
```

**Where to find your IP:**
```bash
# On your development machine (Mac/Linux)
ifconfig | grep "inet " | grep -v 127.0.0.1

# On Windows
ipconfig
# Look for "IPv4 Address" in your WiFi network
```

### 2. Network Security Configuration

**File:** `frontend-kotlin/src/main/res/xml/network_security_config.xml`

This file permits cleartext HTTP to local IP ranges. Includes:
- Private class A: `10.0.0.0/8`
- Private class B: `172.16.0.0/12`
- Private class C: `192.168.0.0/16`
- Specific IPs like `10.216.39.119`

If your backend IP is NOT in these ranges, add it:
```xml
<domain includeSubdomains="false">YOUR_IP_HERE</domain>
```

### 3. AndroidManifest.xml

```xml
<application
    ...
    android:networkSecurityConfig="@xml/network_security_config"
    android:usesCleartextTraffic="true"
    ...
>
```

---

## Debugging Steps

### Step 1: Verify Backend is Running

```bash
# Check if backend-node is listening on port 3000
lsof -i :3000

# Expected output:
# COMMAND     PID     USER    FD   TYPE            DEVICE SIZE/OFF NODE NAME
# node      12345   ritesh   24u   IPv4     0xabcd1234      0t0  TCP localhost:3000 (LISTEN)

# If not listening, start backend-node:
cd backend-node
npm start
```

### Step 2: Get Your Actual IP Address

```bash
# Find the IP address on the network where backend-node is listening
hostname -I

# Or more specific:
ip addr show | grep "inet " | grep -v "127.0.0.1"

# Example output:
# inet 10.216.39.119/24 brd 10.216.39.255 scope global wlan0
#                ↑ THIS IS YOUR IP
```

### Step 3: Update local.properties

Edit `frontend-kotlin/local.properties`:
```properties
BACKEND_HOST=10.216.39.119    # Use YOUR IP from Step 2
BACKEND_PORT=3000
```

### Step 4: Rebuild Android App

```bash
cd frontend-kotlin
./gradlew installDebug
```

### Step 5: Test Backend Connectivity

Open Android Terminal on the phone (long-press home → Terminal, or use Termux app):

```bash
# Test DNS
ping 10.216.39.119

# Expected: replies from 10.216.39.119 every second

# Test HTTP health endpoint
curl http://10.216.39.119:3000/health

# Expected output:
# {"status":"ok","service":"ai-calling-backend",...}
```

### Step 6: View App Logs

```bash
# In Android Studio:
# View → Tool Windows → Logcat
# Filter: "ApiClient" or "ViewModel" or "Connectivity"

# Or use adb:
adb logcat | grep -i "network\|cleartext\|api\|connectivity"
```

---

## Common Errors & Fixes

### Error: "CLEARTEXT communication not permitted by network security policy"

**Cause:** Android 9+ blocks HTTP by default.

**Fix:**
1. Verify `network_security_config.xml` exists:
   ```bash
   ls frontend-kotlin/src/main/res/xml/network_security_config.xml
   ```
2. Verify AndroidManifest references it:
   ```bash
   grep "networkSecurityConfig" frontend-kotlin/src/main/AndroidManifest.xml
   ```
3. Rebuild:
   ```bash
   ./gradlew installDebug
   ```

### Error: "Failed to reach backend — is it running at http://192.168.1.100:3000?"

**Cause:** Wrong IP address or backend is offline.

**Fix:**
1. Verify backend is running:
   ```bash
   lsof -i :3000
   ```
2. Get correct IP:
   ```bash
   hostname -I
   ```
3. Update `local.properties` with correct IP
4. Rebuild:
   ```bash
   ./gradlew installDebug
   ```

### Error: "Connection refused: 10.216.39.119:3000"

**Cause:** Backend not listening on that IP or firewall blocking.

**Fix:**
1. Verify backend is actually running:
   ```bash
   curl http://10.216.39.119:3000/health
   ```
2. If curl works from desktop but app doesn't, check firewall:
   ```bash
   # On Linux
   ufw status
   sudo ufw allow 3000

   # On Mac
   # System Preferences → Security & Privacy → Firewall
   # Add backend port to allowed apps
   ```
3. Ensure phone is on same WiFi network
4. Restart backend and app

### Error: "Cannot resolve hostname"

**Cause:** Backend IP not valid or DNS issue.

**Fix:**
1. Use raw IP address (not hostname)
2. Ensure IP is in format: `10.216.39.119` (not `my-machine.local`)
3. Verify phone can ping the IP:
   ```bash
   # On phone (Termux or Android Terminal)
   ping 10.216.39.119
   ```

---

## Network Connectivity Diagnostics (Automatic)

The app runs automatic diagnostics when you press "Start Automation":

**Checks performed:**
1. ✓ Android network connectivity (WiFi/Cellular/Ethernet)
2. ✓ DNS resolution of backend host
3. ✓ TCP socket connection to backend:port
4. ✓ HTTP `/health` endpoint reachability
5. ✓ WebSocket connectivity

**View diagnostics:**
- Open Android Studio Logcat
- Filter: `"Diagnostics"` or `"ConnectivityDiagnostics"`
- Look for report starting with: `═══════════════════════════════════════════`

**Example output:**
```
═══════════════════════════════════════════
  CONNECTIVITY DIAGNOSTICS
═══════════════════════════════════════════
Timestamp      : 14:35:22.123
Backend        : http://10.216.39.119:3000
Backend Host   : 10.216.39.119
Backend Port   : 3000

─────────────────────────────────────────
  ANDROID NETWORK
─────────────────────────────────────────
Connected      : ✓ Yes
Type           : WiFi

─────────────────────────────────────────
  BACKEND CONNECTIVITY
─────────────────────────────────────────
DNS            : ✓ 10.216.39.119
TCP Socket     : ✓ Connectable
HTTP Health    : ✓ Reachable
WebSocket      : ✓ Reachable

✓ All connectivity checks passed
═══════════════════════════════════════════
```

---

## Port Forwarding (If needed)

If backend-node is on a different machine than your development environment:

```bash
# Forward remote port 3000 to local 3000
ssh -N -L 3000:localhost:3000 user@backend-machine.com

# Then use localhost in local.properties:
# BACKEND_HOST=192.168.1.100 (or 127.0.0.1 via tunnel)
```

---

## Production vs Development

**Development (this project):**
- Backend IP: Local LAN IP (e.g., `10.216.39.119`)
- Protocol: HTTP (cleartext on local network)
- Network Config: Permits local IPs

**Production:**
- Backend IP: Domain name (e.g., `api.example.com`)
- Protocol: HTTPS only
- Network Config: `cleartextTrafficPermitted="false"` for all (default)
- Use proper SSL certificates

---

## Testing Endpoints

Once configured correctly, test these endpoints:

```bash
# Health check
curl http://10.216.39.119:3000/health

# Campaign status
curl http://10.216.39.119:3000/api/adb/status

# Start campaign (POST)
curl -X POST http://10.216.39.119:3000/api/adb/start \
  -H "Content-Type: application/json" \
  -d '{"campaignName":"Test Campaign"}'

# All should return HTTP 200 with JSON
```

---

## Additional Resources

- Android Network Security: https://developer.android.com/training/articles/security-config
- OkHttp Debugging: https://github.com/square/okhttp/wiki/Interceptors
- Logcat Filtering: https://developer.android.com/tools/logcat
- ADB Commands: https://developer.android.com/tools/adb

