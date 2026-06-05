# Quick Start — Network Configuration

Get the Android app working with backend-node in 5 minutes.

---

## Problem

Android app shows error:
```
CLEARTEXT communication not permitted by network security policy
```

Or app can't connect to backend at `http://10.216.39.119:3000`.

---

## Solution (3 Steps)

### Step 1: Get Your IP Address

```bash
# On Mac/Linux
ifconfig | grep "inet " | grep -v 127.0.0.1

# Expected output (look for the address):
# inet 10.216.39.119 netmask 0xffffff00 broadcast 10.216.39.255
#       ↑ THIS IS YOUR IP
```

```bash
# On Windows
ipconfig

# Look for: "IPv4 Address" (e.g., 10.216.39.119)
```

**Write down your IP:** _______________

### Step 2: Update Configuration

Edit file: `frontend-kotlin/local.properties`

```properties
BACKEND_HOST=10.216.39.119    # ← Replace with YOUR IP from Step 1
BACKEND_PORT=3000
```

**CRITICAL:** Make sure you use YOUR actual IP, not an example.

### Step 3: Rebuild

```bash
cd frontend-kotlin
./gradlew installDebug
```

**Expected:** `BUILD SUCCESSFUL`

---

## Test It

### Test 1: Backend Running?

```bash
lsof -i :3000
```

Should show `node` process listening on port 3000.

If not:
```bash
cd backend-node
npm start
```

### Test 2: Phone Can Reach Backend?

On Android phone (use Termux or Android Terminal app):
```bash
ping 10.216.39.119
```

Should get replies. If not:
- Phone on same WiFi as backend? ✓ Check
- IP address correct? ✓ Verify in Step 1
- Firewall blocking port 3000? ✓ Check

### Test 3: App Can Connect?

In the app:
1. Press "Start Automation"
2. Open Logcat: `adb logcat | grep Diagnostics`
3. Should see:
   ```
   ✓ All connectivity checks passed
   ```

If you see network errors, check:
- Backend IP in `local.properties` matches YOUR IP
- Backend is actually running
- Phone is on same network

---

## Common Errors

| Error | Fix |
|-------|-----|
| `CLEARTEXT communication not permitted` | Already fixed! This version allows it. Just rebuild with `./gradlew installDebug` |
| `Cannot reach backend` | Check: (1) IP in local.properties (2) Backend running (3) Phone on same WiFi |
| `DNS resolution failed` | Use IP address, not hostname. Check IP is correct. |
| `Connection refused` | Backend not running. Run `npm start` in backend-node directory. |

---

## Done!

When campaign starts and you see:
```
✓ Campaign started: 42 leads queued
```

It's working! 🎉

---

## Need Help?

- **Setup issues?** → See `NETWORK_DEBUG.md`
- **Troubleshooting?** → See `NETWORK_DEBUG.md` → Troubleshooting section
- **Details?** → See `ANDROID_NETWORK_FIX.md`
- **Verify setup?** → See `CLEARTEXT_FIX_CHECKLIST.md`

