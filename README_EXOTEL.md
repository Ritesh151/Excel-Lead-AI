# 🎤 AI Voice Calling Automation Platform
## Exotel Cloud Telephony Edition

**Production-ready automated voice calling system using Exotel API, Python Whisper, and Node.js**

---

## 🎯 SYSTEM OVERVIEW

Transform lead lists into automated voice campaigns with:
- ☎️ **Cloud-based calling** via Exotel Voice API
- 🎵 **Automated greeting playback** for every incoming call
- 🎙️ **Call recording** and voice capture
- 🧠 **AI transcription** using Faster-Whisper
- 🎓 **Intent detection** (YES/NO/UNCLEAR)
- 💾 **MongoDB** for complete audit trail
- ⚙️ **Fully scalable** Node.js + Python architecture

---

## 📊 ARCHITECTURE

```
┌─────────────────────────────────────────────────────────────────┐
│                      EXOTEL CLOUD                               │
│                  (Cloud Telephony Service)                      │
└────┬──────────────────────────────────────────────────┬─────────┘
     │                                                  │
     │ Makes Calls                                      │ Sends Webhooks
     │                                                  │ (Recordings)
     ▼                                                  ▼
┌──────────────────────────────────────────────────────────────────┐
│          Node.js Backend  (Express + MongoDB)                    │
├──────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │  Campaign Engine                                           │ │
│  │  - Reads leads from Excel                                  │ │
│  │  - Manages sequential calling                              │ │
│  │  - Tracks call progress                                    │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                  │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │  Exotel Service                                            │ │
│  │  - Authenticates with Exotel API                           │ │
│  │  - Initiates outbound calls                                │ │
│  │  - Manages retries & error handling                        │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                  │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │  Voice XML Endpoint  (/voice-flow)                         │ │
│  │  - Generates XML for Exotel                                │ │
│  │  - Serves greeting.wav to caller                           │ │
│  │  - Instructs Exotel to record response                     │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                  │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │  Recording Webhook  (/webhook/recording)                   │ │
│  │  - Receives recording from Exotel                          │ │
│  │  - Downloads WAV file                                      │ │
│  │  - Sends to Python for transcription                       │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                  │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │  MongoDB Call Logs                                         │ │
│  │  - Stores all call metadata                                │ │
│  │  - Tracks transcriptions & intents                         │ │
│  │  - Complete audit trail                                    │ │
│  └────────────────────────────────────────────────────────────┘ │
└────────────────┬───────────────────────────────────────┬─────────┘
                 │                                       │
                 │ HTTP POST                             │
                 │ /api/transcribe                       │ Serves
                 │                                       │ /audio/
                 ▼                                       ▼
┌──────────────────────────────────────────────────────────────────┐
│      Python FastAPI  (Faster-Whisper)                            │
├──────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │  Transcription Engine                                      │ │
│  │  - Receives recording file path                            │ │
│  │  - Uses Faster-Whisper (base model)                        │ │
│  │  - Supports Hindi + English                                │ │
│  │  - Returns transcribed text                                │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                  │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │  Intent Detection                                          │ │
│  │  - Analyzes transcription                                  │ │
│  │  - Detects YES / NO / UNCLEAR                              │ │
│  │  - Supports Hindi variants                                 │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                  │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │  MongoDB Updater                                           │ │
│  │  - Updates call log with transcription                     │ │
│  │  - Stores intent & confidence                              │ │
│  └────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────┘
```

---

## 🔄 CALL FLOW

```
STEP 1: Campaign Starts
 ├─ Backend reads leads/leads.xlsx
 ├─ Validates phone numbers (E.164 format)
 ├─ Creates call log entries
 └─ Initiates sequential Exotel calls

STEP 2: Exotel Rings Customer
 ├─ Exotel dials customer phone
 ├─ Customer sees incoming call
 └─ Customer picks up

STEP 3: Greeting Played
 ├─ Backend generates Voice XML
 ├─ Exotel fetches XML from /voice-flow endpoint
 ├─ Exotel plays audio/greeting.wav
 └─ Caller hears: "Hello, I'm Ritesh Gajjar..."

STEP 4: Recording
 ├─ Caller speaks their response
 ├─ Exotel records audio
 ├─ Recording completes (max 60 seconds)
 └─ Exotel sends webhook: POST /webhook/recording

STEP 5: Download & Transcribe
 ├─ Backend receives webhook with RecordingSid
 ├─ Downloads recording from Exotel
 ├─ Saves to /recordings/
 ├─ Sends to Python FastAPI: /api/transcribe
 └─ Python returns: transcription + intent

STEP 6: Store Results
 ├─ Backend updates MongoDB call_logs
 ├─ Stores:
 │  ├─ transcription: "yes, interested"
 │  ├─ intent: "YES"
 │  ├─ confidence: 0.95
 │  └─ final status: "transcribed"
 └─ Campaign continues to next lead

STEP 7: Results Ready
 ├─ View all calls: GET /api/call/status
 ├─ Query MongoDB: db.call_logs.find({ intent: "YES" })
 └─ Download recording: /recordings/<filename>.wav
```

---

## 📁 PROJECT STRUCTURE

```
AI Calling - Excel Lead/
│
├── README.md (this file)
├── STARTUP.md (Complete setup guide)
├── RUN.txt (Quick reference)
│
├── backend-node/
│   ├── package.json
│   ├── .env.example (Exotel credentials template)
│   │
│   ├── src/
│   │   ├── index.js (Express entry point)
│   │   │
│   │   ├── services/
│   │   │   ├── ExotelService.js (Exotel API wrapper)
│   │   │   ├── CampaignService.js (Lead processing engine)
│   │   │   └── RecordingService.js (Download & transcription)
│   │   │
│   │   ├── routes/
│   │   │   ├── voiceFlowRoutes.js (GET /voice-flow XML endpoint)
│   │   │   ├── webhookRoutes.js (POST /webhook/recording)
│   │   │   ├── leadsRoutes.js (Lead management)
│   │   │   └── callRoutes.js (Campaign control)
│   │   │
│   │   ├── api/
│   │   │   ├── controllers/
│   │   │   │   ├── callController.js (Campaign APIs)
│   │   │   │   └── leadsController.js (Lead APIs)
│   │   │   └── routes/
│   │   │
│   │   ├── mongodb/
│   │   │   ├── connection.js (MongoDB setup)
│   │   │   └── models/
│   │   │       ├── CallLog.js (Call records schema)
│   │   │       └── Lead.js (Lead schema)
│   │   │
│   │   └── utils/
│   │       └── logger.js (Winston logging)
│   │
│   ├── logs/ (Generated)
│   │   ├── combined.log
│   │   └── error.log
│   │
│   └── node_modules/ (Generated)
│
├── ai-python/
│   ├── requirements.txt (Python dependencies)
│   ├── .env.example (Whisper config template)
│   │
│   ├── main.py (FastAPI entry point)
│   │
│   ├── routers/
│   │   ├── transcription.py (FastAPI /api/transcribe endpoint)
│   │   ├── health_router.py
│   │   ├── call_router.py
│   │   └── campaign_router.py
│   │
│   ├── transcription/
│   │   ├── transcription_service.py (Whisper wrapper)
│   │   └── intent_detector.py (YES/NO detection)
│   │
│   ├── whisper/
│   │   ├── whisper_engine.py (Faster-Whisper engine)
│   │   └── exceptions.py
│   │
│   ├── db/
│   │   ├── mongodb_client.py (MongoDB async driver)
│   │   └── models.py
│   │
│   ├── logger.py (Logging setup)
│   ├── config.py (Settings)
│   │
│   ├── logs/ (Generated)
│   │   ├── ai_engine.log
│   │   └── ai_engine_error.log
│   │
│   ├── venv/ (Generated - virtual environment)
│   └── __pycache__/ (Generated)
│
├── audio/
│   ├── greeting.wav (Main greeting - must exist!)
│   └── thank_you.wav (Optional thank you)
│
├── leads/
│   └── leads.xlsx (Excel file with leads)
│
├── recordings/ (Generated)
│   ├── <call_sid>_<timestamp>.wav
│   └── ...
│
├── logs/ (Generated)
│   ├── combined.log (Node.js)
│   ├── error.log (Node.js)
│   ├── ai_engine.log (Python)
│   └── ai_engine_error.log (Python)
│
└── frontend-kotlin/ (Android app - optional)
    └── (Removed: no longer needed with Exotel)
```

---

## ⚙️ KEY SERVICES

### ExotelService.js
Wrapper around Exotel REST API:
- Authenticate with API key + token
- Make outbound calls with callback URLs
- Fetch call status and recordings
- Generate VoiceXML for Exotel

### CampaignService.js
Campaign orchestration:
- Read leads from Excel
- Validate & normalize phone numbers
- Remove duplicates
- Sequential call execution with delays
- Progress tracking & reporting

### RecordingService.js
Recording pipeline:
- Download from Exotel
- Validate WAV files
- Send to Python transcription
- Update MongoDB with results
- Retry logic for failures

### TranscriptionService (Python)
Faster-Whisper transcription:
- Load model (base, small, medium, etc.)
- Process audio files
- Return text + confidence
- Support Hindi + English
- Intent detection

---

## 📡 API ENDPOINTS

### Backend (Node.js)

**Health Check:**
```
GET /health
Response: { "status": "ok", "service": "ai-calling-backend" }
```

**Campaign Control:**
```
POST /api/call/start
Body: { "campaignName": "Campaign" }
Response: { "campaignId": "camp_...", "totalLeads": 50 }

POST /api/call/stop
Response: { "message": "Campaign stop requested" }

GET /api/call/status
Response: { "isRunning": true, "progress": "10/50", "progressPercent": 20 }
```

**Lead Management:**
```
GET /api/leads
Response: [{ "name": "John", "phoneNumber": "+919427047705" }]

POST /api/leads/import
Response: { "imported": 50, "duplicates": 5 }
```

**Voice Flow (Exotel):**
```
GET /voice-flow?campaignId=camp_123&leadId=log_456
Response: (XML with Play + Record directives)
```

**Webhooks:**
```
POST /webhook/recording (from Exotel)
POST /webhook/call-status (from Exotel)
```

### Python (FastAPI)

**Health Check:**
```
GET /api/health
Response: { "status": "ok", "service": "transcription-api" }
```

**Transcribe Recording:**
```
POST /api/transcribe
Body: { 
  "recording_path": "/path/to/recording.wav",
  "call_sid": "abc123",
  "phone_number": "+919427047705"
}
Response: {
  "transcription": "yes interested",
  "intent": "YES",
  "confidence": 0.95
}
```

---

## 🗄️ MongoDB SCHEMA

### call_logs Collection

```javascript
{
  _id: ObjectId,
  
  // Campaign & customer info
  campaignId: "camp_1234567890",
  phoneNumber: "+919427047705",
  customerName: "Rajesh Gajjar",
  
  // Exotel call tracking
  callSid: "abc123xyz",
  status: "transcribed",  // pending, initiated, ringing, answered, completed, failed, etc.
  
  // Intent detection
  intent: "YES",  // YES, NO, UNCLEAR, or null
  confidence: 0.95,
  
  // Transcription
  transcription: "yes interested in it services",
  
  // Recording info
  recordingPath: "./recordings/abc123_1234567890.wav",
  recordingSid: "rec_xyz",
  recordingUrl: "https://...",
  
  // Timing
  startTime: ISODate("2024-06-01T10:00:00Z"),
  answeredAt: ISODate("2024-06-01T10:00:05Z"),
  endTime: ISODate("2024-06-01T10:00:45Z"),
  recordedAt: ISODate("2024-06-01T10:00:50Z"),
  transcribedAt: ISODate("2024-06-01T10:01:10Z"),
  duration: 45,
  
  // Error handling
  errorMessage: null,
  retryCount: 0,
  
  // Metadata
  createdAt: ISODate("2024-06-01T10:00:00Z"),
  updatedAt: ISODate("2024-06-01T10:01:10Z")
}
```

---

## 🔐 SECURITY

✅ **Implemented:**
- Environment variables for secrets (EXOTEL_API_KEY, tokens)
- Request validation on all endpoints
- MongoDB sanitization
- HTTPS via ngrok (development) / proper SSL (production)
- Webhook payload validation

⚠️ **For Production:**
- Implement rate limiting (50 requests/minute)
- Add request signing for webhooks
- Use API keys for authentication
- Enable CORS restrictions
- Set up VPN/firewall rules
- Encrypt sensitive data in MongoDB

---

## 🚀 QUICK START

```bash
# 1. Start MongoDB
mongod --dbpath /var/lib/mongodb

# 2. Start ngrok (in new terminal)
ngrok http 3000

# 3. Update BASE_URL in backend-node/.env with ngrok URL

# 4. Start Backend
cd backend-node && npm install && npm start

# 5. Start Python (in new terminal)
cd ai-python && python -m venv venv && source venv/bin/activate
pip install -r requirements.txt && python main.py server

# 6. Start Campaign
curl -X POST http://localhost:3000/api/call/start \
  -H "Content-Type: application/json" \
  -d '{"campaignName":"Production"}'

# 7. Monitor
curl http://localhost:3000/api/call/status
```

---

## 🎵 Audio Requirements

**File Format:** WAV (PCM)
- **Sample Rate:** 8000-16000 Hz (8000 Hz recommended for GSM)
- **Channels:** Mono (1)
- **Bit Depth:** 16-bit
- **Duration:** < 60 seconds
- **Size:** < 10 MB
- **Normalization:** -3dB to -1dB peak

**Convert with FFmpeg:**
```bash
ffmpeg -i greeting.mp3 \
  -acodec libgsm \
  -ar 8000 \
  -ac 1 \
  -y greeting.wav
```

---

## 📊 MONITORING & ANALYTICS

### Real-time Dashboard
```bash
curl http://localhost:3000/api/call/status
```

### MongoDB Queries
```javascript
// Total calls
db.call_logs.countDocuments()

// Success rate
db.call_logs.countDocuments({ intent: "YES" }) / 
db.call_logs.countDocuments()

// Failed calls
db.call_logs.find({ status: "failed" }).pretty()

// Average call duration
db.call_logs.aggregate([
  { $group: { _id: null, avgDuration: { $avg: "$duration" } } }
])

// Calls by intent
db.call_logs.aggregate([
  { $group: { _id: "$intent", count: { $sum: 1 } } }
])
```

---

## 🐛 TROUBLESHOOTING

| Issue | Solution |
|-------|----------|
| "SDK location not found" | Set `ANDROID_HOME` or use Exotel (no Android needed) |
| Calls not connecting | Check Exotel credentials and Caller ID provisioning |
| Recordings not downloading | Verify webhook URL is reachable (test ngrok) |
| Transcription fails | Ensure WAV file is valid, Whisper model is downloaded |
| MongoDB connection error | Verify `mongod` is running: `mongosh --eval "db.adminCommand('ping')"` |
| Port 3000 in use | `lsof -i :3000` and kill process or use different port |

---

## 📞 EXOTEL SUPPORT

- **Dashboard:** https://manage.exotel.com
- **API Docs:** https://exotel.com/api
- **Support Email:** support@exotel.com

---

## 📝 ENVIRONMENT VARIABLES

### backend-node/.env
```
EXOTEL_SID=<your_sid>
EXOTEL_API_KEY=f126470652bca076c7728d552d94e15bb532b196fda11f2b
EXOTEL_API_TOKEN=136478c48bf752ed451b6ab34671ea5b0e6dfc2bb8ee5ccb
EXOTEL_CALLER_ID=09513886363
BASE_URL=<ngrok_url>
```

### ai-python/.env
```
WHISPER_MODEL_SIZE=base
WHISPER_DEVICE=cpu
WHISPER_COMPUTE_TYPE=int8
MONGO_URI=mongodb://localhost:27017/ai_calling
```

---

## ✅ CHECKLIST

- [ ] Install Node.js 18+, Python 3.10+, MongoDB
- [ ] Clone repo and install dependencies
- [ ] Configure Exotel account and credentials
- [ ] Create `audio/greeting.wav` (optimized for GSM)
- [ ] Create `leads/leads.xlsx` with Name and Mobile Number columns
- [ ] Set up ngrok tunnel
- [ ] Configure MongoDB webhooks in Exotel
- [ ] Start all services (MongoDB → ngrok → Backend → Python)
- [ ] Test with `curl` commands
- [ ] Monitor logs and MongoDB
- [ ] Deploy to production

---

## 🏆 SUCCESS METRICS

✅ **System Working When:**
- Leads imported successfully
- Campaign starts and processes leads
- Exotel calls connect
- Caller hears greeting.wav clearly
- Recordings downloaded and stored
- Transcription completes
- Intent detected (YES/NO)
- Results stored in MongoDB
- Full audit trail available

---

**🚀 Ready to launch your voice calling automation!**

For detailed setup: See [STARTUP.md](STARTUP.md)
For quick reference: See [RUN.txt](RUN.txt)
