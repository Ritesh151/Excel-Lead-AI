# 🚀 EXOTEL VOICE CALLING AUTOMATION - STARTUP GUIDE

**Complete setup and running instructions for production-ready automated calling platform.**

---

## 📋 TABLE OF CONTENTS

1. [Prerequisites](#prerequisites)
2. [Environment Setup](#environment-setup)
3. [Database Setup](#database-setup)
4. [Backend Node.js Setup](#backend-nodejs-setup)
5. [Python FastAPI Setup](#python-fastapi-setup)
6. [Audio Files](#audio-files)
7. [Ngrok Tunnel Setup](#ngrok-tunnel-setup)
8. [Exotel Configuration](#exotel-configuration)
9. [Starting Services](#starting-services)
10. [Testing](#testing)
11. [Troubleshooting](#troubleshooting)

---

## 📦 PREREQUISITES

- **Node.js** 18+ ([download](https://nodejs.org))
- **Python** 3.10+ ([download](https://www.python.org))
- **MongoDB** 4.0+ (local or Atlas)
- **Exotel Account** with API credentials
- **ngrok** for tunneling (local development)

### Check versions:
```bash
node --version      # v18.x.x or higher
python --version    # 3.10 or higher
mongod --version    # 4.0 or higher
```

---

## 🔧 ENVIRONMENT SETUP

### 1. Backend Node.js Environment

Create `.env` file in `backend-node/`:

```bash
cd backend-node
cp .env.example .env
```

Edit `backend-node/.env` with your **Exotel credentials**:

```env
# Server
NODE_ENV=development
PORT=3000

# MongoDB
MONGODB_URI=mongodb://localhost:27017/ai_calling

# Exotel (from RUN.txt)
EXOTEL_SID=your_exotel_sid_here
EXOTEL_API_KEY=f126470652bca076c7728d552d94e15bb532b196fda11f2b
EXOTEL_API_TOKEN=136478c48bf752ed451b6ab34671ea5b0e6dfc2bb8ee5ccb
EXOTEL_CALLER_ID=09513886363

# For ngrok, update after tunnel starts:
BASE_URL=http://localhost:3000  # Change to https://xxxxx.ngrok.io

# Python API
AI_ENGINE_URL=http://localhost:8000
```

### 2. Python FastAPI Environment

Create `.env` file in `ai-python/`:

```bash
cd ai-python
cp .env.example .env
```

Edit `ai-python/.env`:

```env
# FastAPI Server
HOST=0.0.0.0
PORT=8000

# MongoDB (same as backend)
MONGO_URI=mongodb://localhost:27017/ai_calling

# Whisper
WHISPER_MODEL_SIZE=base
WHISPER_DEVICE=cpu
WHISPER_COMPUTE_TYPE=int8
WHISPER_LANGUAGE=auto

# Paths
RECORDINGS_DIR=../recordings
AUDIO_DIR=../audio
```

---

## 🗄️ DATABASE SETUP

### Start MongoDB

**Option A: Local MongoDB**
```bash
# macOS with Homebrew
brew services start mongodb-community

# Linux
sudo systemctl start mongod

# Windows
net start MongoDB
```

**Option B: MongoDB Atlas (Cloud)**
```
1. Create account at https://www.mongodb.com/cloud/atlas
2. Create cluster
3. Get connection string
4. Update MONGODB_URI in .env
```

### Verify MongoDB is running:
```bash
mongosh --eval "db.adminCommand('ping')"
# Expected: { ok: 1 }
```

---

## 🔌 BACKEND NODE.JS SETUP

### 1. Install Dependencies

```bash
cd backend-node
npm install
```

### 2. Verify Installation

```bash
npm list
# Should see all packages without errors
```

### 3. Test Connection to MongoDB

```bash
node -e "const mongoose = require('mongoose'); mongoose.connect(process.env.MONGODB_URI || 'mongodb://localhost:27017/ai_calling').then(() => console.log('✓ MongoDB connected')).catch(e => console.error('✗ MongoDB error:', e.message))"
```

---

## 🐍 PYTHON FASTAPI SETUP

### 1. Create Virtual Environment

```bash
cd ai-python
python -m venv venv

# Activate
source venv/bin/activate  # macOS/Linux
# or
venv\Scripts\activate  # Windows
```

### 2. Install Dependencies

```bash
pip install -r requirements.txt
```

> **Note:** First install of `faster-whisper` downloads ~1.5GB model weights

### 3. Verify Installation

```bash
python -c "from transcription.transcription_service import TranscriptionService; print('✓ Whisper ready')"
```

---

## 🎵 AUDIO FILES

### Prepare Greeting Audio

Create or place audio files in `audio/`:

```
audio/
├── greeting.wav           # Main greeting to caller
└── thank_you.wav          # Thank you message
```

### Optimize for Telephony (8000 Hz, GSM codec)

Use FFmpeg:

```bash
# Convert to 8000 Hz, mono, GSM codec
ffmpeg -i greeting.wav -acodec libgsm -ar 8000 -ac 1 -y greeting_optimized.wav

# Copy optimized file
cp greeting_optimized.wav audio/greeting.wav
```

### Audio Specifications:
- **Format:** WAV (PCM)
- **Sample Rate:** 8000-16000 Hz (8000 Hz for GSM optimization)
- **Channels:** Mono (1)
- **Bit Depth:** 16-bit
- **Duration:** < 60 seconds
- **Volume:** Normalized to -3dB to -1dB peak

---

## 🌐 NGROK TUNNEL SETUP

**Ngrok creates a public HTTPS URL for Exotel to send webhooks**

### 1. Install Ngrok

Download from [ngrok.com](https://ngrok.com/download)

### 2. Authenticate

```bash
ngrok config add-authtoken YOUR_AUTH_TOKEN
```

### 3. Start Tunnel

```bash
# In a NEW terminal
ngrok http 3000
```

**Output:**
```
ngrok                                                              (Ctrl+C to quit)

Session Status                online
Account                       your-email@example.com
Version                       3.3.5
Region                        us (United States)
Latency                       45ms
Web Interface                 http://127.0.0.1:4040
Forwarding                    https://abc123xyz.ngrok.io -> http://localhost:3000
```

### 4. Update BASE_URL

Copy the forwarding URL and update `backend-node/.env`:

```env
BASE_URL=https://abc123xyz.ngrok.io
```

> **Note:** Ngrok URL changes each run (unless you have a paid plan)

---

## ☎️ EXOTEL CONFIGURATION

### 1. Get Exotel Credentials

From [Exotel Dashboard](https://exotel.com):

1. Go to Settings → API
2. Note your:
   - **SID** (Account ID)
   - **API Key**
   - **API Token**
   - **Caller ID** (Your dedicated number or phone number)

### 2. Configure Webhooks in Exotel

**Dashboard → Settings → Webhooks:**

1. **Recording Webhook:**
   ```
   URL: https://abc123xyz.ngrok.io/webhook/recording
   Method: POST
   ```

2. **Call Status Webhook:**
   ```
   URL: https://abc123xyz.ngrok.io/webhook/call-status
   Method: POST
   ```

3. **Voice Flow URL:**
   - Automatically called by Exotel → Backend returns XML

---

## 🎬 STARTING SERVICES

### Order Matters! Start in this sequence:

#### Terminal 1: MongoDB
```bash
mongod --dbpath /var/lib/mongodb
# or
brew services start mongodb-community
```

#### Terminal 2: Ngrok
```bash
ngrok http 3000
# Note the https://xxxxx.ngrok.io URL
```

#### Terminal 3: Backend Node.js
```bash
cd backend-node
npm install
npm start

# Expected output:
# Backend server running on http://localhost:3000
# Environment: development
# MongoDB connected
```

#### Terminal 4: Python FastAPI
```bash
cd ai-python
source venv/bin/activate  # or venv\Scripts\activate on Windows
python main.py server

# Expected output:
# INFO:     Uvicorn running on http://0.0.0.0:8000
# AI Engine (FastAPI) starting up...
# MongoDB connected and indexes ensured
```

### Verify All Services Running

```bash
# Test Backend
curl http://localhost:3000/health
# {"status":"ok","service":"ai-calling-backend","timestamp":"..."}

# Test FastAPI
curl http://localhost:8000/api/health
# {"status":"ok","service":"transcription-api",...}

# Test MongoDB
mongosh --eval "db.adminCommand('ping')"
# {"ok":1}
```

---

## 🧪 TESTING

### 1. Import Leads

```bash
curl -X POST http://localhost:3000/api/leads/import
# Response: { "success": true, "message": "Leads imported", "count": N }
```

### 2. Check Leads

```bash
curl http://localhost:3000/api/leads
# Response: [{ "name": "...", "phoneNumber": "+91..." }, ...]
```

### 3. Start Campaign

```bash
curl -X POST http://localhost:3000/api/call/start \
  -H "Content-Type: application/json" \
  -d '{"campaignName":"Test Campaign"}'

# Response:
#{
#  "success": true,
#  "data": {
#    "campaignId": "camp_1234567890",
#    "totalLeads": 5,
#    "message": "Campaign started successfully"
#  }
#}
```

### 4. Monitor Progress

```bash
curl http://localhost:3000/api/call/status

# Response:
#{
#  "success": true,
#  "data": {
#    "isRunning": true,
#    "campaignId": "camp_1234567890",
#    "totalLeads": 5,
#    "processedLeads": 2,
#    "successfulCalls": 2,
#    "failedCalls": 0,
#    "progressPercent": 40
#  }
#}
```

### 5. Stop Campaign

```bash
curl -X POST http://localhost:3000/api/call/stop
```

### 6. Check MongoDB

```bash
mongosh
use ai_calling
db.call_logs.find().limit(5).pretty()

# View results:
# db.call_logs.find({ "intent": "YES" }).count()
# db.call_logs.find({ "intent": "NO" }).count()
```

---

## 🐛 TROUBLESHOOTING

### Backend Won't Start

```bash
# Check if port 3000 is in use
lsof -i :3000

# If occupied, kill the process
kill -9 <PID>

# Or use different port
PORT=3001 npm start
```

### MongoDB Connection Error

```bash
# Verify MongoDB is running
mongosh --eval "db.adminCommand('ping')"

# If error, start MongoDB
mongod --dbpath /var/lib/mongodb

# Or check connection string
# Should be: mongodb://localhost:27017/ai_calling
```

### Exotel Call Not Connecting

1. **Check credentials** in `.env`
2. **Verify Caller ID** is provisioned in Exotel
3. **Check webhook URL** is reachable (test via Postman)
4. **Validate phone numbers** are E.164 format: `+919427047705`

### Transcription Fails

1. **Check recording exists:** `ls recordings/`
2. **Verify file is valid WAV:** `ffmpeg -i recording.wav`
3. **Check Whisper model downloaded:** `~/.cache/huggingface/hub/models--openai--whisper-base/`
4. **Increase timeout:** `TRANSCRIPTION_TIMEOUT_MS=180000` in `.env`

### Webhook Not Receiving Calls

1. **Check ngrok URL** is correct in Exotel settings
2. **Verify ngrok is running** and forwarding
3. **Check firewalls** don't block port 3000
4. **Test webhook endpoint:**
   ```bash
   curl -X POST https://xxxxx.ngrok.io/webhook/recording \
     -H "Content-Type: application/json" \
     -d '{"CallSid":"123","RecordingSid":"456","RecordingUrl":"..."}'
   ```

---

## 📊 MONITORING

### View Real-time Logs

**Backend:**
```bash
tail -f logs/combined.log
tail -f logs/error.log
```

**Python:**
```bash
tail -f logs/ai_engine.log
tail -f logs/ai_engine_error.log
```

### MongoDB Queries

```bash
mongosh

# Total calls
db.call_logs.countDocuments()

# Success rate
db.call_logs.countDocuments({ intent: "YES" })
db.call_logs.countDocuments({ intent: "NO" })

# Failed calls
db.call_logs.find({ status: "failed" })

# Today's calls
db.call_logs.find({ 
  createdAt: { $gte: new Date(new Date().setHours(0,0,0,0)) }
}).count()
```

---

## 📈 PRODUCTION DEPLOYMENT

### Before Going Live:

1. ✅ Use paid **ngrok plan** or **custom domain**
2. ✅ Enable **HTTPS** everywhere
3. ✅ Configure **rate limiting**
4. ✅ Set up **monitoring & alerting**
5. ✅ Use **environment-specific configs**
6. ✅ Enable **database backups**
7. ✅ Test **failure scenarios**
8. ✅ Implement **retry logic** for failed calls
9. ✅ Add **request validation**
10. ✅ Use **process manager** (PM2)

### Start with PM2:

```bash
# Install PM2
npm install -g pm2

# Start services
pm2 start "npm --prefix backend-node start" --name "api"
pm2 start "python ai-python/main.py server" --name "whisper"

# Monitor
pm2 monit

# Logs
pm2 logs
```

---

## 🎯 QUICK START (One-Liner Reference)

```bash
# Terminal 1
mongod --dbpath /var/lib/mongodb

# Terminal 2
ngrok http 3000

# Terminal 3
cd backend-node && npm start

# Terminal 4
cd ai-python && source venv/bin/activate && python main.py server

# In new terminal - trigger campaign
curl -X POST http://localhost:3000/api/call/start \
  -H "Content-Type: application/json" \
  -d '{"campaignName":"Production"}'
```

---

## 📞 EXOTEL API REFERENCE

### Make Call
```
POST https://api.exotel.com/v1/Accounts/{SID}/Calls/connect.json
```

### Get Call Status
```
GET https://api.exotel.com/v1/Accounts/{SID}/Calls/{CallSid}.json
```

### Download Recording
```
GET https://api.exotel.com/v1/Accounts/{SID}/Recordings/{RecordingSid}.wav
```

---

## 📝 NOTES

- **First Whisper run** downloads model (~1.5GB) - be patient
- **Ngrok URL changes** on every restart (upgrade to fixed URL)
- **Exotel webhooks require HTTPS** - use ngrok for local testing
- **MongoDB indexes** are auto-created on first run
- **Call recordings** stored in `recordings/` directory

---

**🚀 System Ready! Happy Calling!**
