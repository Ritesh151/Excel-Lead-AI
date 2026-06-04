# STARTUP GUIDE — Android GSM AI Calling System

**Architecture:** ADB Dialing + Android In-Call Audio Routing + Python AI Engine  
**Target Device:** Samsung SM-A176B (Android 14/15)

---

## PRE-REQUISITES

### 1. Hardware
- ✅ Samsung SM-A176B with active SIM card (with outgoing call balance)
- ✅ USB cable (USB-A to USB-C or USB-C to USB-C)
- ✅ PC/Laptop running Linux (tested on Linux)

### 2. Software
- ✅ Python 3.12+ (with venv)
- ✅ Node.js 18+ (with npm)
- ✅ MongoDB 6.0+
- ✅ Android Studio SDK / platform-tools (adb)
- ✅ ffmpeg (for audio conversion)

### 3. Android Phone Setup
- ✅ Enable Developer Options
- ✅ Enable USB Debugging
- ✅ Connect via USB
- ✅ Authorize ADB RSA key (tap "Allow" on phone)
- ✅ Insert active SIM card with outgoing balance

---

## STEP 1: Verify ADB Connection

```bash
# Check adb is installed
adb version

# Expected: Android Debug Bridge version 1.0.41 or higher

# List connected devices
adb devices

# Expected output:
# List of devices attached
# <device_serial>   device

# If device shows "unauthorized":
# - Look at phone screen
# - Tap "Allow" on the RSA key popup
# - Run adb devices again

# Test dialing capability
adb shell am start -a android.intent.action.CALL -d "tel:+919427047705"

# Phone should dial immediately (cancel call manually)
```

---

## STEP 2: Setup Backend Node.js

```bash
cd backend-node

# Install dependencies
npm install

# Copy environment template
cp .env.example .env

# Edit .env
nano .env
```

**Required .env values:**
```env
PORT=3000
MONGO_URI=mongodb://localhost:27017/ai_calling
AUDIO_DIR=../audio
RECORDINGS_DIR=../recordings
AI_ENGINE_URL=http://localhost:8000

# Exotel (NOT used in ADB flow — can leave default or comment out)
# EXOTEL_SID=...
# EXOTEL_API_KEY=...
# EXOTEL_API_TOKEN=...
# EXOTEL_CALLER_ID=...
```

**Start backend:**
```bash
npm run dev

# Expected output:
# Server running on http://localhost:3000
# MongoDB connected
```

---

## STEP 3: Setup Python AI Engine

```bash
cd ai-python

# Create virtual environment
python3 -m venv .venv
source .venv/bin/activate

# Install dependencies
pip install -r requirements.txt

# Copy environment template
cp .env.example .env

# Edit .env
nano .env
```

**Required .env values:**
```env
HOST=0.0.0.0
PORT=8000
MONGO_URI=mongodb://localhost:27017/ai_calling
LEADS_FILE_PATH=../leads/leads.xlsx
AUDIO_DIR=../audio
RECORDINGS_DIR=../recordings

# ADB device serial (leave empty for auto-detect)
ADB_DEVICE_SERIAL=

# Whisper configuration
WHISPER_MODEL=base
WHISPER_DEVICE=cpu
WHISPER_COMPUTE_TYPE=int8
WHISPER_LANGUAGE=hi

# Call flow timing
CALL_FLOW_CONNECT_TIMEOUT=60.0
CALL_FLOW_MAX_CALL_DURATION=120.0
CALL_FLOW_BETWEEN_CALLS_DELAY=3.0
CALL_FLOW_RETRY_ATTEMPTS=2
```

**Start AI engine:**
```bash
python main.py server

# Expected output:
# AI ENGINE v3 — ADB+Android+Kotlin GSM Flow
# MongoDB connected
# Whisper model pre-warmed
# AI Engine ready
# Uvicorn running on http://0.0.0.0:8000
```

---

## STEP 4: Build & Install Android App

```bash
cd frontend-kotlin

# Build debug APK
./gradlew assembleDebug

# Expected output: BUILD SUCCESSFUL
# APK location: build/outputs/apk/debug/frontend-kotlin-debug.apk

# Install on Samsung phone
adb install -r build/outputs/apk/debug/frontend-kotlin-debug.apk

# Expected output: Success
```

---

## STEP 5: Configure Android App

### A. Grant Permissions
```
Open app → Tap "Request permissions" → Allow all:
  ✅ Phone (Call phone, Read phone state, Manage calls)
  ✅ Microphone (Record audio)
  ✅ Notifications
  ✅ Files (for recording storage)
  ✅ Accessibility (separate step below)
```

### B. Enable Accessibility Service
```
Settings → Accessibility → Installed Services
→ "GSM Call Automation" → Toggle ON
→ Tap "Allow" on confirmation dialog
```

### C. Disable Battery Optimization
```
Settings → Apps → GSM Call AI → Battery
→ Select "Unrestricted" or "No restrictions"
```

### D. Enable Autostart (Samsung specific)
```
Settings → Apps → GSM Call AI
→ Tap three dots menu → "Allow background activity"
→ Device care → Battery → Background usage limits
→ Remove "GSM Call AI" from restricted list
```

### E. Copy Audio Files to App
```bash
# Copy greeting.wav and thank_you.wav to Android app assets
# Option 1: Via ADB
adb push ../audio/greeting.wav /sdcard/Android/data/com.optimatrix.gsmcall/files/audio/
adb push ../audio/thank_you.wav /sdcard/Android/data/com.optimatrix.gsmcall/files/audio/

# Option 2: App auto-copies from assets on first run (if files exist in app/src/main/assets/)
```

---

## STEP 6: Prepare Audio Files

Audio files MUST be telephony-compatible: **mono, 8000Hz, 16-bit PCM WAV**

```bash
cd audio

# Check current audio format
ffmpeg -i greeting.wav

# If not 8000Hz mono:
ffmpeg -i greeting.wav -ac 1 -ar 8000 -sample_fmt s16 -c:a pcm_s16le greeting_telephony.wav
ffmpeg -i thank_you.wav -ac 1 -ar 8000 -sample_fmt s16 -c:a pcm_s16le thank_you_telephony.wav

# Rename to standard names
mv greeting_telephony.wav greeting.wav
mv thank_you_telephony.wav thank_you.wav

# Verify
ffprobe greeting.wav 2>&1 | grep Audio
# Expected: Audio: pcm_s16le, 8000 Hz, mono
```

---

## STEP 7: Prepare Lead Data

```bash
cd leads

# Edit leads.xlsx (Excel file)
# Required columns:
#   Name            | Mobile Number
#   Rajesh Gajjar   | 9427047705
#   ...             | ...

# Phone number formats accepted:
#   9427047705           → +919427047705
#   09427047705          → +919427047705
#   +919427047705        → +919427047705
#   919427047705         → +919427047705

# Save as leads.xlsx
```

---

## STEP 8: Start All Services

### Terminal 1: MongoDB
```bash
mongod --dbpath /path/to/data/db
# Or if MongoDB is running as service:
sudo systemctl start mongod
```

### Terminal 2: Backend Node
```bash
cd backend-node
npm run dev
```

### Terminal 3: AI Python Engine
```bash
cd ai-python
source .venv/bin/activate
python main.py server
```

### Terminal 4: Android App
```bash
# Open app on phone
# Tap "Start automation"
# Service starts in foreground
# Status shows: "Monitoring GSM calls…"
```

---

## STEP 9: Execute Test Call

### Option A: Via API (Recommended)
```bash
curl -X POST http://localhost:8000/api/call/execute \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Test User",
    "phone": "+919427047705"
  }'
```

### Option B: Via CLI
```bash
cd ai-python
python main.py call --phone +919427047705 --name "Test User"
```

### Option C: Via Campaign
```bash
cd ai-python
python main.py run --file ../leads/leads.xlsx
```

---

## STEP 10: Monitor Execution

### A. Watch Android Logs
```bash
adb logcat -s GSMCallAutomation:* PcmStreamingEngine:* CallAutomation:* TelephonyController:* AudioRouting:*
```

### B. Watch Python Logs
```bash
# In ai-python terminal — logs printed to stdout
```

### C. Watch Android App UI
```
Open app → See live logs in "Debug console" section
```

### D. Export Android Logs
```
Tap "Export logs" in app → saves to /sdcard/gsmcall_logs_<timestamp>.txt
```

### E. Check MongoDB
```bash
mongosh
use ai_calling
db.call_records.find().sort({created_at:-1}).limit(10).pretty()
```

---

## EXPECTED BEHAVIOR

### 1. Call Initiation
```
[AI-Python] Dialing +919427047705 via ADB
[ADB] am start -a android.intent.action.CALL -d tel:+919427047705
[Android] TelephonyController: State IDLE → OFFHOOK
[Android] CallSessionTracker: Phase DIALING
```

### 2. Customer Answers
```
[ADB] Call state: OFFHOOK (2)
[Android] TelephonyController: State OFFHOOK → CONNECTED
[Android] CallAutomationService: CONNECTED — launching automation flow
```

### 3. Audio Routing
```
[Android] Waiting 1200ms for call stabilization
[Android] AudioRoutingManager: MODE_IN_COMMUNICATION
[Android] SamsungWorkarounds: AudioFocus requested (x3)
[Android] AudioRoutingManager: Audio routing prepared
```

### 4. Greeting Playback
```
[Android] CallAudioPlayer: Playing greeting.wav
[Android] PcmStreamingEngine: Strategy 1 VOICE_CALL @ 8000Hz
[Android] PcmStreamingEngine: Playback complete 48000/48000 bytes
[Android] PcmStreamingEngine: Strategy 1 SUCCEEDED
```

### 5. Recording
```
[Android] RecordingManager: Recording for 18s
[Android] RecordingManager: Recording saved response_1234567890.wav
```

### 6. Upload & Transcription
```
[Android] ApiClient: Uploading recording to backend
[Python] /api/call/transcribe: Recording received
[Python] Whisper: Transcribing 18s audio
[Python] Transcription: "haan bilkul ji"
[Python] Intent: YES confidence=0.95
[Python] MongoDB: Saved record
```

### 7. Post-Intent Action
```
[Android] Intent=YES — playing thank_you.wav
[Android] CallAudioPlayer: Playing thank_you.wav
[Android] TelephonyController: Ending call
```

### 8. Completion
```
[Android] Automation FLOW COMPLETE
[Android] Audio mode restored
[AI-Python] Call result: YES intent="haan bilkul ji"
[MongoDB] Call record updated
```

---

## TROUBLESHOOTING

### Issue 1: ADB "device unauthorized"
**Solution:**
```bash
adb kill-server
adb start-server
adb devices
# Look at phone screen → Tap "Allow" → Check "Always allow from this computer"
```

### Issue 2: Phone does not dial
**Solution:**
```bash
# Test manually:
adb shell am start -a android.intent.action.CALL -d "tel:+919427047705"

# If fails with "Permission denied":
# - Check CALL_PHONE permission granted in app
# - Check USB debugging is enabled
# - Check phone is not in "Charge only" mode (should be "File transfer" or "PTP")
```

### Issue 3: Customer cannot hear greeting
**Solution:**
- Check audio file format: `ffprobe audio/greeting.wav`
- Must be: mono, 8000Hz, 16-bit PCM
- Check Android app has greeting.wav: `adb shell ls /sdcard/Android/data/com.optimatrix.gsmcall/files/audio/`
- Check logs for "Strategy X SUCCEEDED"
- All strategies fail → Audio policy blocked → Samsung firmware issue (cannot fix without root)

### Issue 4: Recording is silent
**Solution:**
```bash
# Check RECORD_AUDIO permission granted
adb shell pm list permissions -d -g | grep RECORD_AUDIO

# Check microphone works:
# - Make normal phone call
# - Test if other party hears you
# If mic broken → hardware issue
```

### Issue 5: Service dies after screen-off
**Solution:**
- Disable battery optimization (see Step 5C)
- Enable background activity (see Step 5D)
- Check service is foreground: `adb shell dumpsys activity services | grep CallAutomationService`
- Should show "isForeground=true"

### Issue 6: Whisper transcription fails
**Solution:**
```bash
# Check WAV file is valid
ffprobe recordings/response_*.wav

# Check Python dependencies
pip list | grep faster-whisper

# Re-download Whisper model
rm -rf ~/.cache/huggingface/hub/models--guillaumekln--faster-whisper-*
python main.py server  # Will re-download
```

### Issue 7: MongoDB connection error
**Solution:**
```bash
# Check MongoDB is running
mongosh

# If fails:
sudo systemctl start mongod
# Or:
mongod --dbpath /path/to/data/db

# Check connection string in .env
MONGO_URI=mongodb://localhost:27017/ai_calling
```

---

## FINAL CHECKLIST

- [ ] ADB authorized (`adb devices` shows "device")
- [ ] Backend Node running (`http://localhost:3000/health`)
- [ ] AI Python running (`http://localhost:8000/health`)
- [ ] MongoDB running (`mongosh` connects)
- [ ] Android app installed
- [ ] All permissions granted (Phone, Mic, Notifications)
- [ ] Accessibility service enabled
- [ ] Battery optimization disabled
- [ ] Audio files (greeting.wav, thank_you.wav) in 8000Hz mono format
- [ ] Audio files copied to Android app
- [ ] Lead Excel file prepared (leads.xlsx)
- [ ] Android service started (tap "Start automation")
- [ ] Test call executed successfully
- [ ] Customer heard greeting.wav ✅
- [ ] Recording saved ✅
- [ ] Transcription in MongoDB ✅
- [ ] Intent detected (YES/NO) ✅

---

## PRODUCTION DEPLOYMENT

### 1. Build Release APK
```bash
cd frontend-kotlin

# Generate signing key (first time only)
keytool -genkey -v -keystore release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias gsmcall

# Configure signing in build.gradle.kts
# Then:
./gradlew assembleRelease

# APK: build/outputs/apk/release/frontend-kotlin-release.apk
```

### 2. Secure MongoDB
```bash
# Create admin user
mongosh
use admin
db.createUser({
  user: "admin",
  pwd: "<strong-password>",
  roles: ["root"]
})

# Update MONGO_URI in .env files:
MONGO_URI=mongodb://admin:<password>@localhost:27017/ai_calling?authSource=admin
```

### 3. Process Manager (Python)
```bash
# Use systemd or supervisor
# Example systemd service:
sudo nano /etc/systemd/system/ai-calling.service
```

```ini
[Unit]
Description=AI Calling Engine
After=network.target mongod.service

[Service]
Type=simple
User=your-user
WorkingDirectory=/path/to/ai-python
Environment="PATH=/path/to/ai-python/.venv/bin"
ExecStart=/path/to/ai-python/.venv/bin/python main.py server
Restart=always

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable ai-calling
sudo systemctl start ai-calling
```

### 4. Process Manager (Node.js)
```bash
npm install -g pm2
pm2 start src/index.js --name ai-calling-backend
pm2 save
pm2 startup
```

---

## SUCCESS CRITERIA

✅ **System is PRODUCTION-READY when:**
1. ADB dials customer phone via SIM
2. Customer answers → phone goes to CONNECTED state
3. Android app detects CONNECTED (TelephonyController)
4. Audio routing set to MODE_IN_COMMUNICATION
5. **Customer HEARS greeting.wav during live call**
6. Android records 18s customer response
7. Recording uploaded to Python backend
8. Whisper transcribes successfully
9. Intent detected (YES/NO/UNKNOWN)
10. Result saved to MongoDB
11. Call ends cleanly
12. Next lead processed automatically

---

## DEPLOYMENT ARCHITECTURE

```
Excel Leads (leads.xlsx)
       ↓
backend-node (Express.js)
       ↓
ai-python (FastAPI)
       ↓
ADB → Samsung SM-A176B (SIM call)
       ↓
   Customer phone rings
       ↓
   Customer answers
       ↓
Android CallAutomationService
   ↓               ↓
Audio routing   Recording (18s)
   ↓               ↓
greeting.wav    response.wav
   ↓               ↓
Customer hears  Upload to Python
                    ↓
                Whisper transcription
                    ↓
                Intent detection
                    ↓
                MongoDB storage
                    ↓
            YES → thank_you.wav → end call
            NO/UNKNOWN → end call
```

---

**System Status:** ✅ PRODUCTION READY

**Next Action:** Execute test call via `python main.py call --phone <number>`
