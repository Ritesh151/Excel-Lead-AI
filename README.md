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
├── ai-python/              # FastAPI AI Engine
│   ├── adb/                # ADB device interaction (Phase 2)
│   ├── audio/              # Audio playback (Phase 2)
│   ├── conversation/       # Conversation state (Phase 3)
│   ├── recorder/           # Call recording (Phase 2)
│   ├── routers/            # FastAPI route handlers
│   ├── transcription/      # Whisper wrapper (Phase 3)
│   ├── whisper/            # Model loader (Phase 3)
│   ├── workers/            # Call execution worker
│   ├── config.py           # Pydantic settings
│   ├── database.py         # Motor async MongoDB client
│   ├── logger.py           # Loguru logger
│   ├── main.py             # FastAPI app entry point
│   ├── requirements.txt
│   └── .env.example
│
├── backend-node/           # Express Backend
│   └── src/
│       ├── api/
│       │   ├── controllers/    # leadsController, callController
│       │   └── routes/         # leadsRoutes, callRoutes
│       ├── mongodb/
│       │   ├── connection.js   # Mongoose connect/disconnect
│       │   └── models/         # Lead, CallLog schemas
│       ├── services/           # leadsService, callService
│       ├── utils/              # logger (Winston)
│       └── index.js            # Express app entry point
│   ├── package.json
│   └── .env.example
│
├── audio/                  # Pre-recorded WAV prompts
│   ├── greeting.wav
│   ├── question_1.wav
│   ├── question_2.wav
│   └── closing.wav
│
├── leads/                  # Excel lead files
│   └── leads.xlsx          # Columns: Name, Phone
│
├── recordings/             # Saved caller recordings (auto-generated)
└── logs/                   # Application logs (auto-generated)
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
