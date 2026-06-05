# AI Voice Calling Automation System

Automated GSM outbound calling using an Android device as the dialer.  
Reads leads from Excel, dials each one sequentially via the Android phone, plays a greeting audio prompt, records the customer response, transcribes it with Faster-Whisper, detects YES/NO intent, and saves everything to MongoDB.

No SIM card API, no VoIP provider, no Exotel — just a real Android phone connected via USB.

---

## Network Configuration (Android 9+)

### Cleartext Traffic Policy

Android 9+ enforces HTTPS by default and blocks cleartext (HTTP) traffic. This project runs backend-node and ai-python on a **local development network**, so we need to permit cleartext HTTP to local IP addresses.

**Network Security Configuration:**
- File: `frontend-kotlin/src/main/res/xml/network_security_config.xml`
- Permits cleartext HTTP to:
  - Local IP ranges: `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`
  - Loopback: `localhost`, `127.0.0.1`
  - Your specific backend: `10.216.39.119` ← Change this to your actual IP
- All other domains (internet) require HTTPS (production-safe)

**AndroidManifest Configuration:**
- Sets `android:networkSecurityConfig="@xml/network_security_config"`
- Sets `android:usesCleartextTraffic="true"` (only applies to config rules)

**Backend Configuration** (`frontend-kotlin/local.properties`):
```properties
BACKEND_HOST=10.216.39.119    # Change to your actual IP
BACKEND_PORT=3000
```

### Connectivity Diagnostics

Before attempting a campaign start, the app runs automatic diagnostics:
- Checks Android network connectivity (WiFi/cellular)
- Validates DNS resolution of backend host
- Tests TCP socket connection to backend:port
- Verifies HTTP `/health` endpoint reachability
- Tests WebSocket connectivity

If issues are found, they're logged and reported to the UI, but the campaign start is still attempted (non-blocking).

**Diagnostics Output Example:**
```
═══════════════════════════════════════════
  CONNECTIVITY DIAGNOSTICS
═══════════════════════════════════════════
Backend        : http://10.216.39.119:3000
Android Network: ✓ WiFi
DNS            : ✓ 10.216.39.119
TCP Socket     : ✓ Connectable
HTTP Health    : ✓ Reachable
WebSocket      : ✓ Reachable
```

### Troubleshooting Network Errors

#### "CLEARTEXT communication not permitted"
- Ensure `network_security_config.xml` is in `src/main/res/xml/`
- Ensure AndroidManifest references it: `android:networkSecurityConfig="@xml/network_security_config"`
- Add your backend IP to the `<domain>` list if not in a private range
- Rebuild: `./gradlew installDebug`

#### "Failed to reach backend"
- Verify backend-node is running: `lsof -i :3000`
- Verify IP is correct in `local.properties` and matches your actual LAN IP
- Check firewall isn't blocking port 3000
- Ping from phone: open Android Terminal → `ping 10.216.39.119`
- Check backend logs for incoming requests

#### "Cannot resolve hostname"
- Verify backend host in `local.properties` is an IP (not hostname)
- Ensure phone and backend are on same network (same WiFi/LAN)
- Restart router / reconnect to WiFi

---

## Architecture

```
leads/leads.xlsx
       │
       ▼
backend-node (Express :3000)
  ├── imports leads into MongoDB
  ├── orchestrates sequential campaign loop
  ├── receives WAV uploads from Android app
  ├── proxies recordings to ai-python for transcription
  ├── stores results in MongoDB
  └── streams live events via WebSocket

       │ ADB commands          │ WAV upload + HTTP
       ▼                       ▼
 ai-python (FastAPI :8000)    Android Kotlin App
  ├── dials via ADB            ├── foreground service (START_STICKY)
  ├── Faster-Whisper STT       ├── monitors call state (PhoneStateListener)
  ├── YES/NO intent detection  ├── plays greeting.wav on VOICE_CALL stream
  └── saves to MongoDB         ├── records 18s customer response
                               ├── uploads WAV → backend-node
                               └── WebSocket → real-time status to dashboard
```

---

## Call Flow

1. Import leads from `leads/leads.xlsx` into MongoDB (status: `pending`)
2. For each pending lead, sequentially:
   - ADB dials the phone number
   - Android app detects `CONNECTED` state
   - Stabilize 1.2 s → route audio to `STREAM_VOICE_CALL`
   - Play `audio/greeting.wav` through earpiece
   - Record 18 s of customer response
   - Upload WAV to `backend-node /api/calls/recording`
   - backend-node proxies to ai-python for Whisper transcription
   - ai-python returns `{ intent, transcription }` (YES / NO / UNKNOWN)
   - If **YES** → play `audio/thank_you.wav` → end call
   - If **NO / UNKNOWN** → end call immediately
3. Intent + transcription saved to MongoDB
4. Real-time progress broadcast over WebSocket to dashboard

---

## Project Structure

```
.
├── ai-python/                         # FastAPI AI Engine (:8000)
│   ├── adb/
│   │   ├── adb_manager.py             # ADB device manager
│   │   ├── call_controller.py         # Dial / hangup via ADB
│   │   ├── device_checker.py          # Device readiness checks
│   │   ├── exceptions.py
│   │   └── models.py
│   ├── audio/
│   │   ├── audio_player.py            # Play WAV via sounddevice
│   │   ├── playback_controller.py
│   │   ├── exceptions.py
│   │   └── utils.py
│   ├── db/
│   │   ├── call_repository.py         # CRUD for call records
│   │   ├── models.py                  # Pydantic DB models
│   │   ├── mongodb_client.py
│   │   ├── exceptions.py
│   │   └── utils.py
│   ├── leads/
│   │   ├── importer.py                # Read leads from Excel
│   │   ├── models.py
│   │   └── validators.py              # Phone number validation
│   ├── recorder/
│   │   ├── recorder.py                # Record audio via sounddevice
│   │   ├── recording_manager.py
│   │   ├── exceptions.py
│   │   ├── models.py
│   │   └── utils.py
│   ├── routers/
│   │   ├── android.py                 # POST /api/calls/recording
│   │   ├── call.py                    # /api/call/*
│   │   ├── campaign.py                # /api/campaign/* (stubs)
│   │   ├── health.py                  # GET /health
│   │   └── transcription.py           # /api/transcribe (legacy)
│   ├── transcription/
│   │   ├── transcription_service.py   # Faster-Whisper wrapper
│   │   └── intent_detector.py         # YES / NO / UNCLEAR logic
│   ├── whisper/
│   │   └── whisper_engine.py          # Model loader + cache
│   ├── workflow/
│   │   ├── orchestrator.py            # Full call workflow
│   │   └── states.py                  # State machine definitions
│   ├── workers/
│   │   └── call_worker.py
│   ├── config.py                      # Pydantic settings (from .env)
│   ├── logger.py                      # Loguru logger
│   ├── main.py                        # App entry point + CLI
│   ├── requirements.txt
│   ├── .env.example
│   └── .python-version
│
├── backend-node/                      # Express Backend (:3000)
│   └── src/
│       ├── index.js                   # App entry point
│       ├── config.js                  # Validated env config
│       ├── api/
│       │   ├── controllers/
│       │   │   ├── callController.js
│       │   │   └── leadsController.js
│       │   └── routes/
│       │       ├── adbRoutes.js       # /api/adb/*
│       │       ├── callRoutes.js      # /api/call/*
│       │       ├── debugRoutes.js     # /api/debug/*
│       │       └── leadsRoutes.js     # /api/leads/*
│       ├── audio/
│       │   ├── audioProcessor.js
│       │   └── audioValidator.js
│       ├── middleware/
│       │   └── auth.js                # Internal API token check
│       ├── mongodb/
│       │   ├── connection.js
│       │   └── models/
│       │       ├── CallLog.js
│       │       ├── Lead.js
│       │       └── Recording.js
│       ├── routes/
│       │   ├── audioRoutes.js         # GET /audio/:file
│       │   ├── exotelTestRoutes.js    # GET /test-audio-playback, /debug/audio-report
│       │   ├── testAudioRoutes.js
│       │   ├── testCallRoutes.js      # GET /test-call/voice-xml
│       │   ├── voiceFlowRoutes.js     # GET /voice-flow, POST /voice-flow/call-status
│       │   └── webhookRoutes.js       # POST /webhook/recording, /webhook/call-status
│       ├── services/
│       │   ├── AdbCampaignService.js
│       │   ├── AiPythonClient.js      # HTTP client for ai-python
│       │   ├── CampaignService.js     # Excel import + campaign loop
│       │   ├── callService.js
│       │   ├── LeadSyncService.js
│       │   ├── leadsService.js
│       │   ├── NgrokService.js
│       │   └── RecordingService.js    # Recording download + transcription pipeline
│       ├── socket/
│       │   └── WebSocketServer.js     # ws:// real-time event emitter
│       ├── startup/
│       │   └── diagnostics.js         # Startup health checks
│       └── utils/
│           ├── logger.js              # Winston logger
│           └── urlValidator.js
│
├── frontend-kotlin/                   # Android App (Kotlin)
│   └── src/main/java/com/optimatrix/gsmcall/
│       ├── accessibility/
│       │   └── CallAccessibilityService.kt
│       ├── api/
│       │   └── ApiClient.kt           # HTTP: upload WAV, health, campaign status
│       ├── audio/
│       │   ├── AudioRoutingManager.kt # Route audio to STREAM_VOICE_CALL
│       │   ├── CallAudioPlayer.kt     # Play WAV during active call
│       │   ├── MediaSessionController.kt
│       │   └── RoutingRetryEngine.kt
│       ├── permissions/
│       │   └── PermissionManager.kt
│       ├── receivers/
│       │   └── BootReceiver.kt        # Auto-start service on device boot
│       ├── recording/
│       │   ├── RecordingManager.kt    # Record mic during call
│       │   └── WavFileWriter.kt       # Write PCM → WAV
│       ├── services/
│       │   └── CallAutomationService.kt  # Core foreground service
│       ├── telephony/
│       │   ├── CallSession.kt
│       │   ├── CallSessionTracker.kt
│       │   ├── CallState.kt
│       │   └── TelephonyController.kt  # PhoneStateListener
│       ├── ui/
│       │   ├── MainActivity.kt
│       │   ├── CallAutomationScreen.kt
│       │   ├── MainViewModel.kt
│       │   └── MainUiState.kt
│       ├── utils/
│       │   └── LogStore.kt
│       ├── websocket/
│       │   └── SocketManager.kt       # WS client → backend-node
│       └── workers/
│           └── SyncWorker.kt          # WorkManager retry for failed uploads
│
├── audio/                             # Pre-recorded WAV prompts
│   ├── greeting.wav                   # Played when customer answers
│   ├── greeting_telephony.wav         # Telephony-optimised variant (8kHz/mono)
│   ├── thank_you.wav                  # Played on YES intent
│   ├── question_1.wav
│   ├── question_1_telephony.wav
│   ├── question_2.wav
│   ├── question_2_telephony.wav
│   ├── closing.wav
│   └── closing_telephony.wav
│
├── leads/
│   ├── leads.xlsx                     # Input: Name, Mobile Number
│   └── Excel_Gen.ipynb                # Jupyter notebook to generate test leads
│
├── recordings/                        # Saved caller WAV responses (auto-created)
├── logs/                              # Application logs (auto-created)
├── Run.sh                             # Convenience startup script
└── .gitignore
```

---

## Quick Start

### Prerequisites

| Requirement | Version |
|---|---|
| Node.js | ≥ 18 |
| Python | ≥ 3.10 |
| MongoDB | running on `localhost:27017` |
| Android device | USB connected, USB debugging enabled |
| ADB | installed and on PATH |

---

### 1 — Backend (Node.js)

```bash
cd backend-node
cp .env.example .env     # fill in required values
npm install
npm run dev
# → http://localhost:3000
```

### 2 — AI Engine (Python)

```bash
cd ai-python
cp .env.example .env
python -m venv .venv
source .venv/bin/activate      # Windows: .venv\Scripts\activate
pip install -r requirements.txt
python main.py server
# → http://localhost:8000
# → http://localhost:8000/docs  (Swagger UI)
```

### 3 — Android App

1. Open `frontend-kotlin/` in Android Studio
2. Edit `NetworkConfig.kt` — set `BACKEND_HOST` to your machine's LAN IP (e.g. `192.168.1.100`)
3. Build and install on the physical Android device
4. Grant all required permissions (phone, microphone, accessibility service)
5. Enable **Call Automation Service** from the app UI

### 4 — Verify everything is up

```bash
curl http://localhost:3000/health
curl http://localhost:8000/health
```

### 5 — Run a campaign

```bash
# Place your leads file
cp your_leads.xlsx leads/leads.xlsx

# Start the campaign via API
curl -X POST http://localhost:3000/api/adb/start \
  -H "Content-Type: application/json" \
  -d '{"campaignName": "My Campaign"}'
```

---

## API Reference

### backend-node (:3000)

#### System

| Method | Path | Description |
|---|---|---|
| GET | `/health` | Service health + WebSocket status |
| GET | `/api/ws/status` | WebSocket client count |
| POST | `/api/events/emit` | Emit a WebSocket event (used by ai-python) |

#### Leads

| Method | Path | Description |
|---|---|---|
| GET | `/api/leads` | List all leads |
| GET | `/api/leads/:id` | Single lead |
| POST | `/api/leads/import` | Import from Excel |

#### ADB Campaign

| Method | Path | Description |
|---|---|---|
| POST | `/api/adb/start` | Start campaign |
| POST | `/api/adb/stop` | Stop campaign |
| GET | `/api/adb/status` | Campaign status + progress |
| POST | `/api/adb/call` | Single test call |

#### Android Recording Proxy

| Method | Path | Description |
|---|---|---|
| POST | `/api/calls/recording` | Receive WAV from Android, forward to ai-python for transcription |

#### Voice Flow & Webhooks

| Method | Path | Description |
|---|---|---|
| GET | `/voice-flow` | Serve voice XML (Play + Record) |
| POST | `/voice-flow/call-status` | Receive call lifecycle events |
| POST | `/webhook/recording` | Receive recording webhook |
| POST | `/webhook/call-status` | Receive call status webhook |
| POST | `/webhook/verify` | Verify webhook endpoint is reachable |

#### Audio & Debug

| Method | Path | Description |
|---|---|---|
| GET | `/audio/:fileName` | Serve WAV file (full buffer, no 206) |
| GET | `/test-call/voice-xml` | Preview voice XML |
| GET | `/test-audio-playback` | Minimal Play XML for audio testing |
| GET | `/debug/audio-report` | JSON WAV diagnostics (format, sample rate, etc.) |

---

### ai-python (:8000)

| Method | Path | Description |
|---|---|---|
| GET | `/health` | Readiness probe |
| GET | `/docs` | Swagger UI |
| POST | `/api/call/execute` | Execute single ADB call |
| POST | `/api/call/transcribe` | Transcribe a recording file (by path) |
| GET | `/api/call/status` | Whisper + ADB + MongoDB health |
| POST | `/api/calls/recording` | Receive WAV from Android/backend proxy → transcribe |
| POST | `/api/transcribe` | Legacy transcription alias |
| POST | `/api/transcribe/upload` | Legacy file-upload transcription |
| GET | `/api/campaign/status` | Campaign status stub |

---

## Environment Variables

### backend-node `.env`

| Variable | Default | Description |
|---|---|---|
| `PORT` | `3000` | HTTP port |
| `MONGO_URI` | `mongodb://localhost:27017/ai_calling` | MongoDB connection string |
| `MONGO_DB_NAME` | `ai_calling` | Database name |
| `AI_ENGINE_URL` | `http://localhost:8000` | ai-python base URL |
| `LEADS_FILE_PATH` | `../leads/leads.xlsx` | Path to leads Excel file |
| `AUDIO_DIR` | `../audio` | Directory containing WAV files |
| `RECORDINGS_DIR` | `../recordings` | Where to save received recordings |
| `CALL_DELAY_MS` | `5000` | Pause between calls in a campaign |
| `TRANSCRIPTION_TIMEOUT_MS` | `120000` | Max wait for Whisper response |
| `INTERNAL_API_TOKEN` | _(empty)_ | Optional auth token for internal routes |
| `LOG_LEVEL` | `debug` | Winston log level |
| `BASE_URL` | `http://localhost:3000` | Public URL (used in voice XML) |
| `AUDIO_FILE_NAME` | `greeting_telephony.wav` | WAV file served at `/audio/` |

### ai-python `.env`

| Variable | Default | Description |
|---|---|---|
| `PORT` | `8000` | FastAPI port |
| `MONGO_URI` | `mongodb://localhost:27017/ai_calling` | MongoDB connection string |
| `LEADS_FILE_PATH` | `../leads/leads.xlsx` | Path to leads Excel file |
| `ADB_DEVICE_SERIAL` | _(auto)_ | ADB device serial — blank = first connected device |
| `WHISPER_MODEL` | `base` | Model size: `tiny` / `base` / `small` / `medium` / `large-v3` |
| `WHISPER_DEVICE` | `cpu` | `cpu` or `cuda` |
| `WHISPER_COMPUTE_TYPE` | `int8` | `int8` (fast) / `float16` (GPU) / `float32` (precise) |
| `WHISPER_LANGUAGE` | `hi` | Primary language hint (`hi` = Hindi, `en` = English) |
| `WHISPER_VAD_FILTER` | `true` | Strip silence before transcribing |
| `RECORDING_DURATION` | `5.0` | Seconds to record caller response |
| `RECORDING_SAMPLE_RATE` | `16000` | Audio sample rate in Hz |
| `CALL_FLOW_GREETING_FILE` | `greeting.wav` | Greeting audio filename |
| `CALL_FLOW_THANK_YOU_FILE` | `thank_you.wav` | Played on YES intent |
| `CALL_FLOW_BETWEEN_CALLS_DELAY` | `3.0` | Seconds between calls |
| `BACKEND_NODE_URL` | `http://localhost:3000` | backend-node URL for event bridge |

---

## leads.xlsx Format

| Column | Required | Example |
|---|---|---|
| `Name` | No | Rajesh Kumar |
| `Mobile Number` | Yes | 9876543210 |

Accepted phone formats: `9876543210`, `09876543210`, `+919876543210`, `919876543210`

---

## Android App — Key Permissions

The Kotlin app requires these permissions at runtime:

- `READ_PHONE_STATE` + `READ_CALL_LOG` — detect call connect/disconnect
- `RECORD_AUDIO` — capture customer response
- `FOREGROUND_SERVICE` — keep service alive during calls
- `WAKE_LOCK` — prevent CPU sleep mid-call
- Accessibility Service — end calls programmatically (API < 28)

The app auto-starts on device boot via `BootReceiver` if the service was active before reboot.

---

## WebSocket Events

backend-node emits these events on `ws://localhost:3000/`:

| Event | Payload | Description |
|---|---|---|
| `call_state` | `{ phone, state, flowId }` | Call phase change |
| `recording_started` | `{ phone }` | Recording begun |
| `recording_saved` | `{ phone, path, duration }` | Recording file ready |
| `transcription_done` | `{ phone, transcription, intent, confidence }` | Whisper result |
| `intent_detected` | `{ phone, intent, confidence }` | YES / NO / UNKNOWN |
| `call_completed` | `{ phone, intent, transcription, db_id }` | Full call result |
| `playback_result` | `{ file, strategy, success }` | Audio playback outcome |

---

## CLI (ai-python)

```bash
# Start the FastAPI server
python main.py server
python main.py server --port 9000 --reload

# Test intent detection locally
python main.py intent "haan bilkul"

# Place a single test call via ADB
python main.py call --phone +919876543210 --name "Test User"

# Run full campaign from Excel
python main.py run --file ../leads/leads.xlsx
```

---

## Status

- [x] ADB-based outbound calling via Android device
- [x] In-call audio routing + WAV playback (STREAM_VOICE_CALL)
- [x] 18-second customer response recording
- [x] Faster-Whisper transcription (Hindi + English)
- [x] YES / NO / UNKNOWN intent detection
- [x] Excel → MongoDB lead import
- [x] Sequential campaign execution with rate limiting
- [x] Real-time WebSocket event streaming
- [x] Android foreground service (survives screen-off + battery optimization)
- [x] WorkManager retry for failed WAV uploads
- [x] MongoDB result persistence
