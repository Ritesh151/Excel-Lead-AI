# AI Voice Calling Automation System

Automated SIM-based outbound calling using an Android device connected via ADB.
Reads leads from Excel, dials each one sequentially, plays audio prompts, records the response, transcribes it with Faster-Whisper, and saves results to MongoDB.

---

## Architecture

```
leads/leads.xlsx
       │
       ▼
backend-node (Express :3000)
  ├── reads Excel → imports leads to MongoDB
  ├── orchestrates sequential calling loop
  └── calls ai-python for each lead
            │
            ▼
      ai-python (FastAPI :8000)
        ├── ADB → dials phone number
        ├── plays greeting.wav via phone speaker
        ├── records caller response
        ├── transcribes with Faster-Whisper
        └── returns outcome (YES / NO) → backend saves to MongoDB
```

---

## MVP Call Flow

1. Import leads from `leads/leads.xlsx`
2. For each pending lead (sequential):
   - Dial via ADB
   - Play `audio/greeting.wav`
   - Record caller response
   - Transcribe with Whisper
   - If **YES** → play `audio/question_1.wav` + `audio/closing.wav` → end call
   - If **NO** → end call immediately
3. Save transcription + outcome to MongoDB

---

## Project Structure

```
.
├── ai-python/                    # FastAPI AI Engine
│   ├── adb/                      # ADB device interaction
│   │   ├── adb_manager.py        # ADB device manager
│   │   ├── call_controller.py    # Dial/hangup via ADB
│   │   ├── device_checker.py     # Device readiness checks
│   │   ├── exceptions.py
│   │   └── models.py
│   ├── audio/                    # Audio playback engine
│   │   ├── audio_player.py       # Play WAV via sounddevice
│   │   ├── playback_controller.py
│   │   ├── exceptions.py
│   │   └── utils.py
│   ├── conversation/             # (placeholder)
│   ├── db/                       # MongoDB database layer
│   │   ├── call_repository.py    # CRUD for call records
│   │   ├── exceptions.py
│   │   ├── models.py             # Pydantic DB models
│   │   ├── mongodb_client.py
│   │   └── utils.py
│   ├── leads/                    # Lead management
│   │   ├── importer.py           # Read leads from Excel
│   │   ├── models.py
│   │   └── validators.py         # Phone number validation
│   ├── recorder/                 # Call recording
│   │   ├── recorder.py           # Record audio via sounddevice
│   │   ├── recording_manager.py
│   │   ├── exceptions.py
│   │   ├── models.py
│   │   └── utils.py
│   ├── routers/                  # FastAPI route handlers
│   │   ├── call.py               # /api/call/* endpoints
│   │   ├── campaign.py           # /api/campaign/* endpoints
│   │   ├── health.py             # /health endpoint
│   │   └── transcription.py      # /api/transcribe endpoint
│   ├── transcription/            # Speech-to-text + intent
│   │   ├── transcription_service.py
│   │   ├── intent_detector.py    # YES/NO/UNCLEAR detection
│   │   └── example.py
│   ├── whisper/                  # Whisper model loader
│   │   ├── whisper_engine.py
│   │   └── exceptions.py
│   ├── workers/                  # Background workers
│   │   └── call_worker.py        # Call execution worker
│   ├── workflow/                 # Call orchestration
│   │   ├── orchestrator.py       # Full call workflow
│   │   └── states.py             # State machine definitions
│   ├── config.py                 # Pydantic settings
│   ├── database.py               # Motor async MongoDB client
│   ├── logger.py                 # Loguru logger
│   ├── main.py                   # FastAPI app entry point
│   ├── requirements.txt
│   ├── test_audio_to_phone.py
│   ├── .env.example
│   └── .python-version
│
├── backend-node/                 # Express Backend
│   ├── src/
│   │   ├── index.js              # Express app entry point
│   │   ├── api/
│   │   │   ├── controllers/
│   │   │   │   ├── callController.js
│   │   │   │   └── leadsController.js
│   │   │   └── routes/
│   │   │       ├── callRoutes.js
│   │   │       └── leadsRoutes.js
│   │   ├── audio/
│   │   │   ├── audioProcessor.js
│   │   │   └── audioValidator.js
│   │   ├── mongodb/
│   │   │   ├── connection.js
│   │   │   └── models/
│   │   │       ├── Lead.js
│   │   │       └── CallLog.js
│   │   ├── routes/
│   │   │   ├── audioRoutes.js
│   │   │   ├── testAudioRoutes.js
│   │   │   ├── voiceFlowRoutes.js
│   │   │   └── webhookRoutes.js
│   │   ├── services/
│   │   │   ├── CampaignService.js
│   │   │   ├── ExotelService.js
│   │   │   ├── ExotelDebugger.js
│   │   │   ├── NgrokService.js
│   │   │   ├── RecordingService.js
│   │   │   ├── callService.js
│   │   │   └── leadsService.js
│   │   ├── startup/
│   │   │   └── diagnostics.js
│   │   └── utils/
│   │       ├── logger.js         # Winston logger
│   │       └── urlValidator.js
│   ├── audio_optimizer.js        # WAV → telephony conversion
│   ├── package.json
│   └── .env.example
│
├── frontend-kotlin/              # Android (Kotlin) App
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   ├── gradlew
│   ├── gradlew.bat
│   ├── gradle/wrapper/
│   │   ├── gradle-wrapper.jar
│   │   └── gradle-wrapper.properties
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── res/
│       │   ├── values/
│       │   │   ├── strings.xml
│       │   │   └── themes.xml
│       │   └── xml/
│       │       └── accessibility_service_config.xml
│       └── java/com/optimatrix/gsmcall/
│           ├── accessibility/
│           │   └── CallAccessibilityService.kt
│           ├── api/
│           │   └── ApiClient.kt
│           ├── audio/
│           │   ├── AudioRoutingManager.kt
│           │   ├── CallAudioPlayer.kt
│           │   └── MediaSessionController.kt
│           ├── permissions/
│           │   └── PermissionManager.kt
│           ├── recording/
│           │   ├── RecordingManager.kt
│           │   └── WavFileWriter.kt
│           ├── services/
│           │   └── CallAutomationService.kt
│           ├── telephony/
│           │   ├── CallSession.kt
│           │   ├── CallSessionTracker.kt
│           │   ├── CallState.kt
│           │   └── TelephonyController.kt
│           ├── ui/
│           │   ├── CallAutomationScreen.kt
│           │   ├── MainActivity.kt
│           │   ├── MainUiState.kt
│           │   └── MainViewModel.kt
│           ├── utils/
│           │   └── LogStore.kt
│           ├── websocket/
│           │   └── SocketManager.kt
│           └── workers/
│               └── SyncWorker.kt
│
├── audio/                        # Pre-recorded WAV prompts
│   ├── greeting.wav
│   ├── greeting_telephony.wav
│   ├── question_1.wav
│   ├── question_1_telephony.wav
│   ├── question_2.wav
│   ├── question_2_telephony.wav
│   ├── closing.wav
│   └── closing_telephony.wav
│
├── leads/                        # Excel lead files
│   ├── leads.xlsx                # Columns: Name, Mobile Number
│   └── Excel_Gen.ipynb           # Jupyter notebook to generate leads
│
├── recordings/                   # Saved caller recordings (auto-generated)
├── logs/                         # Application logs (auto-generated)
│
├── docker/                       # (empty — future Docker config)
├── setup.sh                      # Project setup script
├── validate.sh                   # Environment validation script
├── .gitignore
├── IMPLEMENTATION_SUMMARY.md
├── QUICK_REFERENCE.md
├── README_EXOTEL.md
├── STARTUP.md
├── RUN.txt
└── how to run.txt
```

---

## Quick Start

### Prerequisites

- Node.js ≥ 18
- Python ≥ 3.10
- MongoDB running on `localhost:27017`
- Android device connected via USB with ADB enabled

### 1 — Backend (Node.js)

```bash
cd backend-node
cp .env.example .env        # fill in your values
npm install
npm run dev
# Server: http://localhost:3000
```

### 2 — AI Engine (Python)

```bash
cd ai-python
cp .env.example .env        # fill in your values
python -m venv .venv
source .venv/bin/activate   # Windows: .venv\Scripts\activate
pip install -r requirements.txt
python main.py
# Server: http://localhost:8000
# Docs:   http://localhost:8000/docs
```

### 3 — Verify

```bash
curl http://localhost:3000/health
curl http://localhost:8000/health
```

---

## API Endpoints

### Backend (Node.js :3000)

| Method | Path               | Description              |
|--------|--------------------|--------------------------|
| GET    | /health            | Health check             |
| GET    | /api/leads         | List all leads           |
| GET    | /api/leads/:id     | Single lead              |
| POST   | /api/leads/import  | Import from Excel        |
| POST   | /api/call/start    | Start calling session    |
| POST   | /api/call/stop     | Stop calling session     |
| GET    | /api/call/status   | Session status           |

### AI Engine (Python :8000)

| Method | Path               | Description              |
|--------|--------------------|--------------------------|
| GET    | /health            | Health check             |
| GET    | /docs              | Swagger UI               |
| POST   | /api/call/execute  | Execute single call      |
| GET    | /api/call/status   | Engine status            |

---

## leads.xlsx Format

| Column | Required | Example      |
|--------|----------|--------------|
| Name   | No       | Rajesh Kumar |
| Phone  | Yes      | 9876543210   |

---

## Implementation Phases

- [x] **Phase 1** — MVP Foundation (current)
- [ ] **Phase 2** — ADB calling + audio playback + recording
- [ ] **Phase 3** — Whisper transcription + YES/NO detection
- [ ] **Phase 4** — Excel import service
- [ ] **Phase 5** — Kotlin frontend dashboard
