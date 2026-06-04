# 🚀 QUICK REFERENCE - EXOTEL AI CALLING SYSTEM

## 📞 YOUR EXOTEL CREDENTIALS (Configured)

```
EXOTEL_API_KEY:    f126470652bca076c7728d552d94e15bb532b196fda11f2b
EXOTEL_API_TOKEN:  136478c48bf752ed451b6ab34671ea5b0e6dfc2bb8ee5ccb
EXOTEL_CALLER_ID:  09513886363
EXOTEL_SID:        ⚠️  GET FROM: https://manage.exotel.com → Settings → API
```

---

## 🔗 IMPORTANT URLs

| Service | URL | Status |
|---------|-----|--------|
| Backend API | `http://localhost:3000` | Running |
| Python API | `http://localhost:8000` | Running |
| MongoDB | `mongodb://localhost:27017` | Running |
| ngrok Dashboard | `http://localhost:4040` | Running |
| ngrok Tunnel | `https://xxxxx.ngrok.io` | *Get from ngrok output* |

---

## ⚙️ CONFIGURATION FILES

| File | Purpose |
|------|---------|
| `backend-node/.env` | ✅ **CONFIGURED** - Exotel credentials & MongoDB |
| `ai-python/.env` | ✅ **CONFIGURED** - Whisper & intent detection |
| `leads/leads.xlsx` | ⚠️ **TODO** - Create with Name + Mobile columns |
| `audio/greeting.wav` | ⚠️ **TODO** - Place your greeting audio file |

---

## 🎯 ESSENTIAL SETUP STEPS

### Step 1: Get Your Exotel SID
```
1. Go to https://manage.exotel.com
2. Dashboard → Settings → API
3. Copy your Account SID
4. Paste into backend-node/.env: EXOTEL_SID=your_sid_here
```

### Step 2: Prepare Audio File
```bash
# Option A: Convert existing audio to GSM-optimized WAV
ffmpeg -i your_greeting.mp3 \
  -acodec libgsm \
  -ar 8000 \
  -ac 1 \
  -y audio/greeting.wav

# Option B: Create from text (Google Translate → Download)
# Manual: Download MP3 from Google Translate, then convert as above
```

### Step 3: Create Leads File
```
Create: leads/leads.xlsx
Columns: Name | Mobile Number
Examples:
  Rajesh Gajjar | 9427047705
  Ritesh Kumar  | 9876543210
```

### Step 4: Start Services (in separate terminals)

**Terminal 1 - MongoDB:**
```bash
mongod --dbpath /var/lib/mongodb
# Wait for: "waiting for connections on port 27017"
```

**Terminal 2 - ngrok:**
```bash
ngrok http 3000
# Note the URL: https://abc123xyz.ngrok.io
```

**Terminal 3 - Update Backend Config:**
```bash
cd backend-node
# Edit .env:
# 1. Set EXOTEL_SID=your_actual_sid
# 2. Set BASE_URL=https://abc123xyz.ngrok.io (from ngrok)
```

**Terminal 4 - Backend:**
```bash
cd backend-node
npm start
# Wait for: "Backend server running on http://localhost:3000"
```

**Terminal 5 - Python:**
```bash
cd ai-python
source venv/bin/activate
python main.py server
# Wait for: "Uvicorn running on http://0.0.0.0:8000"
```

---

## 📱 TEST YOUR SETUP

### Check Health (in new terminal)
```bash
# Backend
curl http://localhost:3000/health
# Response: {"status":"ok","service":"ai-calling-backend"}

# Python
curl http://localhost:8000/api/health
# Response: {"status":"ok",...}

# MongoDB
mongosh --eval "db.adminCommand('ping')"
# Response: { ok: 1 }
```

### View Configuration
```bash
# Backend config
cat backend-node/.env

# Python config
cat ai-python/.env

# View leads (if exists)
python -c "import openpyxl; wb = openpyxl.load_workbook('leads/leads.xlsx'); print(wb.active.values)"
```

---

## ▶️ START A CAMPAIGN

### Option 1: Using curl
```bash
curl -X POST http://localhost:3000/api/call/start \
  -H "Content-Type: application/json" \
  -d '{"campaignName":"Test Campaign"}'

# Response:
# {
#   "success": true,
#   "data": {
#     "campaignId": "camp_...",
#     "totalLeads": 5,
#     "message": "Campaign started successfully"
#   }
# }
```

### Option 2: Using Python
```python
import requests
import json

url = "http://localhost:3000/api/call/start"
data = {"campaignName": "Test Campaign"}
response = requests.post(url, json=data)
print(response.json())
```

---

## 📊 MONITOR CAMPAIGN

```bash
# Check progress
curl http://localhost:3000/api/call/status

# Response example:
# {
#   "success": true,
#   "data": {
#     "isRunning": true,
#     "campaignId": "camp_...",
#     "totalLeads": 5,
#     "processedLeads": 2,
#     "successfulCalls": 2,
#     "failedCalls": 0,
#     "progressPercent": 40
#   }
# }

# View MongoDB results
mongosh
> use ai_calling
> db.call_logs.find().pretty()
> db.call_logs.find({ intent: "YES" }).count()
```

---

## 🛑 STOP CAMPAIGN

```bash
curl -X POST http://localhost:3000/api/call/stop

# Response: {"success":true,"message":"Campaign stop requested"}
```

---

## 🎤 TROUBLESHOOTING

### "Port 3000 already in use"
```bash
# Find process
lsof -i :3000

# Kill it
kill -9 <PID>

# Or use different port
PORT=3001 npm start
```

### "MongoDB connection refused"
```bash
# Start MongoDB
mongod --dbpath /var/lib/mongodb

# Or check if already running
pgrep mongod
```

### "Exotel call failed"
```bash
# Check credentials in .env
cat backend-node/.env | grep EXOTEL

# Verify Exotel account status
# https://manage.exotel.com → Dashboard → Account Status

# Check webhook URL is reachable
# Copy ngrok URL and test:
curl -I https://abc123xyz.ngrok.io/health
```

### "Whisper model not downloading"
```bash
# Check cache location
ls ~/.cache/huggingface/hub/models--openai--whisper-base/

# If missing, first run will download (~1.5GB)
# Let Python start and wait...

# Or pre-download
python -c "from faster_whisper import WhisperModel; WhisperModel('base')"
```

### "Recording webhook not receiving calls"
```bash
# 1. Check ngrok is running
pgrep ngrok

# 2. View ngrok dashboard
open http://localhost:4040

# 3. Verify webhook URL in Exotel
# Should be: https://your-ngrok-url/webhook/recording

# 4. Test webhook manually
curl -X POST https://your-ngrok-url/webhook/recording \
  -H "Content-Type: application/json" \
  -d '{"CallSid":"test","RecordingSid":"test","RecordingUrl":"http://example.com"}'
```

---

## 📂 IMPORTANT DIRECTORIES

```
/recordings/        ← Downloaded call recordings stored here
/logs/             ← Application logs
/audio/            ← Greeting audio files
/leads/            ← Excel file with phone numbers
```

---

## 📞 EXOTEL API ENDPOINTS

```
Make Call:
POST https://api.exotel.com/v1/Accounts/{SID}/Calls/connect.json

Get Status:
GET https://api.exotel.com/v1/Accounts/{SID}/Calls/{CallSid}.json

Download Recording:
GET https://api.exotel.com/v1/Accounts/{SID}/Recordings/{RecordingSid}.wav
```

---

## 🔐 SECURITY REMINDER

⚠️ **NEVER commit .env files to git!**
```bash
# .env files are already in .gitignore
git status  # Should NOT show .env files
```

✅ **Environment variables for production:**
- Use AWS Secrets Manager / Azure Key Vault
- Set via CI/CD pipeline
- Rotate credentials regularly

---

## 📈 PERFORMANCE TIPS

| Setting | Value | Effect |
|---------|-------|--------|
| `CALL_DELAY_MS` | 2000 | 2-second delay between calls |
| `EXOTEL_MAX_RETRIES` | 3 | Retry failed calls 3 times |
| `WHISPER_MODEL_SIZE` | base | Small model (fast) |
| `WHISPER_COMPUTE_TYPE` | int8 | Fast computation |
| `WHISPER_DEVICE` | cpu | No GPU required |

**For high volume (1000+ leads):**
- Increase `WHISPER_MODEL_SIZE` to `small` or `medium` for accuracy
- Set `CALL_DELAY_MS` to 3000-5000
- Consider Exotel's rate limiting (verify limits in account)

---

## 📚 DOCUMENTATION

```
README_EXOTEL.md      ← Architecture & APIs
STARTUP.md            ← Complete setup guide
RUN.txt               ← Command reference
QUICK_REFERENCE.md    ← This file
```

---

**🎯 System is now READY for production!**

Questions? Check STARTUP.md or README_EXOTEL.md
