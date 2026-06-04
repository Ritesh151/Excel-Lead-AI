You are a principal full-stack systems architect, Android telecom engineer, Kotlin architect, Node.js backend engineer, Python AI engineer, MongoDB architect, WebSocket specialist, and distributed workflow engineer.

Your task is to FULLY CONNECT and COMPLETE my existing AI Voice Calling Automation System so that ALL modules work together in REAL production flow with REAL database synchronization, REAL API communication, REAL workflow orchestration, and REAL GSM calling automation.

IMPORTANT:

The project structure already exists.

DO NOT rebuild from scratch.

DO NOT create demo code.

DO NOT create placeholders.

You must COMPLETE and CONNECT all existing modules properly.

==================================================

PRIMARY OBJECTIVE

=================

I need a FULLY WORKING integrated system where:

1. backend-node orchestrates campaigns

2. ai-python executes AI workflows

3. frontend-kotlin connects to backend-node and ai-python

4. MongoDB synchronizes all data properly

5. WebSocket events work in real-time

6. Android Kotlin app communicates correctly

7. ADB GSM calling works

8. greeting.wav playback workflow works

9. recordings save properly

10. Whisper transcription works

11. YES/NO detection works

12. all APIs work together correctly

13. complete end-to-end production flow works

==================================================

CURRENT PROJECT STRUCTURE

=========================

Project already contains:

* backend-node/

* ai-python/

* frontend-kotlin/

* MongoDB

* Whisper

* ADB modules

* Kotlin Android app

* audio/

* recordings/

* websocket/

* workers/

* services/

* telephony/

* routes/

* orchestrator/

DO NOT recreate architecture.

ONLY CONNECT, FIX, COMPLETE, AND INTEGRATE EVERYTHING.

==================================================

FINAL SYSTEM FLOW

=================

STEP 1

backend-node imports Excel leads

STEP 2

MongoDB stores leads

STEP 3

backend-node starts campaign

STEP 4

backend-node sends execution request to ai-python

STEP 5

ai-python uses ADB to dial customer

STEP 6

Android Kotlin app detects active GSM call

STEP 7

Kotlin app internally plays greeting.wav

STEP 8

Customer responds

STEP 9

Android records response

STEP 10

Recording sent to ai-python

STEP 11

Whisper transcribes response

STEP 12

YES/NO detected

STEP 13

MongoDB updated

STEP 14

frontend-kotlin receives live updates

STEP 15

Dashboard shows:

* active call

* playback state

* transcription

* final status

==================================================

MOST IMPORTANT REQUIREMENT

==========================

ALL THREE SYSTEMS MUST CONNECT PROPERLY:

1. frontend-kotlin

2. backend-node

3. ai-python

WITH:

* MongoDB

* REST APIs

* WebSockets

* real-time synchronization

==================================================

REQUIRED IMPLEMENTATION

=======================

==================================================

1. COMPLETE DATABASE INTEGRATION

   ==================================================

Connect ALL systems to MongoDB properly.

==================================================

MongoDB Responsibilities

========================

Store:

* leads

* call sessions

* recordings

* transcriptions

* intents

* campaign states

* playback states

* logs

* device status

==================================================

Generate Proper Collections

===========================

* leads

* call_logs

* campaigns

* recordings

* transcriptions

* devices

* websocket_events

==================================================

Generate:

=========

* indexes

* validation

* repository pattern

* connection pooling

* retry logic

* transaction-safe updates

==================================================

2. BACKEND-NODE ↔ AI-PYTHON INTEGRATION

=======================================

Implement COMPLETE communication layer.

backend-node MUST:

* trigger ai-python execution

* receive status updates

* receive transcription results

* synchronize MongoDB

==================================================

Generate:

=========

* API clients

* retry logic

* timeout handling

* structured payloads

* request validation

* authentication middleware

==================================================

API FLOW

========

backend-node

↓

POST /api/call/execute

↓

ai-python

↓

response

↓

backend-node

↓

MongoDB

==================================================

3. FRONTEND-KOTLIN ↔ BACKEND CONNECTION

=======================================

Connect frontend-kotlin to backend-node correctly.

Generate COMPLETE:

* Retrofit/Ktor API layer

* WebSocket layer

* authentication

* reconnect handling

* realtime updates

* error handling

==================================================

Frontend MUST receive:

======================

* live call states

* playback states

* recording states

* transcription results

* campaign progress

* MongoDB updates

==================================================

4. COMPLETE WEBSOCKET SYSTEM

============================

Generate REAL-TIME websocket synchronization.

==================================================

backend-node MUST EMIT:

=======================

* call_started

* call_connected

* greeting_played

* recording_started

* transcription_completed

* intent_detected

* call_completed

* call_failed

==================================================

frontend-kotlin MUST:

=====================

* subscribe properly

* reconnect automatically

* persist state

* update UI live

==================================================

5. COMPLETE KOTLIN API LAYER

============================

Generate:

* ApiClient.kt

* Repository layer

* DTO models

* response parsing

* websocket manager

* retry logic

* timeout handling

==================================================

Frontend MUST connect to:

=========================

* backend-node REST APIs

* Socket.IO server

* MongoDB-backed state updates

==================================================

6. COMPLETE AI-PYTHON API SERVER

================================

Generate FULL FastAPI integration.

==================================================

Endpoints:

==========

POST /api/call/execute

POST /api/transcribe

GET /api/call/status

GET /health

==================================================

ai-python MUST:

===============

* receive backend requests

* execute ADB workflow

* transcribe recordings

* update MongoDB

* emit websocket events

==================================================

7. COMPLETE ADB WORKFLOW

========================

ai-python MUST:

* detect device

* verify connection

* dial customer

* monitor call

* communicate with Kotlin app

==================================================

Generate:

=========

* stable adb_manager.py

* recovery logic

* device monitoring

* reconnect handling

==================================================

8. KOTLIN ↔ AI-PYTHON COMMUNICATION

===================================

IMPORTANT:

frontend-kotlin MUST synchronize with ai-python workflow.

Generate:

* WebSocket sync

* call-state synchronization

* playback synchronization

* recording synchronization

==================================================

9. COMPLETE RECORDING PIPELINE

==============================

Flow:

Android Kotlin App

↓

record WAV

↓

upload to backend-node

↓

backend-node forwards to ai-python

↓

Whisper transcription

↓

MongoDB update

↓

frontend realtime update

==================================================

10. COMPLETE WHISPER PIPELINE

=============================

ai-python MUST:

* validate recording

* transcribe

* detect YES/NO

* update MongoDB

* emit websocket events

==================================================

11. COMPLETE CAMPAIGN ENGINE

============================

backend-node MUST:

* load Excel

* sequentially process leads

* track campaign state

* handle retries

* persist progress

==================================================

12. COMPLETE ERROR HANDLING

===========================

Handle:

* MongoDB disconnect

* ADB disconnect

* Android app disconnect

* websocket disconnect

* API timeout

* failed recording

* failed transcription

* app background

* battery optimization

* permission denial

==================================================

13. COMPLETE AUTHENTICATION

===========================

Generate:

* JWT auth

* API tokens

* websocket auth

* Android secure storage

==================================================

14. COMPLETE LOGGING SYSTEM

===========================

Generate centralized logs for:

* backend-node

* ai-python

* frontend-kotlin

* ADB

* playback

* transcription

* MongoDB

==================================================

15. COMPLETE STATE SYNCHRONIZATION

==================================

ALL systems MUST maintain synchronized state.

MongoDB MUST become:

* single source of truth

==================================================

16. COMPLETE STARTUP SYSTEM

===========================

Generate:

* startup validation

* health checks

* dependency checks

* service discovery

* websocket validation

==================================================

17. COMPLETE FRONTEND FEATURES

==============================

frontend-kotlin MUST show:

* live calls

* call timer

* playback status

* recording status

* transcription text

* YES/NO result

* campaign progress

* logs

* device connection state

==================================================

18. COMPLETE WORKER SYSTEM

==========================

Generate:

* background workers

* retry queues

* sequential execution

* failure recovery

==================================================

19. COMPLETE FILE STRUCTURE CONNECTION

======================================

CONNECT ALL EXISTING FILES.

DO NOT leave:

* unused modules

* disconnected services

* placeholder APIs

* broken imports

==================================================

20. COMPLETE PRODUCTION INTEGRATION

===================================

Everything MUST work together in REAL execution.

NOT theoretical.

NOT mock.

REAL:

* API communication

* websocket sync

* database persistence

* Android synchronization

* ADB workflows

* recording uploads

* transcription updates

==================================================

21. IMPORTANT ENGINEERING REQUIREMENT

=====================================

Generate COMPLETE implementation for:

backend-node/

ai-python/

frontend-kotlin/

with:

* proper imports

* proper architecture

* proper synchronization

* proper dependency injection

* proper realtime events

* proper API communication

==================================================

22. EXPECTED FINAL RESULT

=========================

After implementation:

1. npm run dev

2. python main.py

3. Android app launched

4. MongoDB connected

5. Excel imported

6. campaign started

7. ADB dials customer

8. Kotlin app detects call

9. greeting.wav playback attempted

10. recording saved

11. Whisper transcribes

12. YES/NO detected

13. MongoDB updated

14. frontend receives realtime updates

15. dashboard fully synchronized

==================================================

23. IMPORTANT

=============

DO NOT:

* generate pseudo code

* generate placeholders

* generate disconnected modules

* skip integrations

* create fake APIs

ONLY generate:

* REAL working code

* production-grade integrations

* proper synchronization

* runnable architecture

* complete communication pipelines

==================================================

24. FINAL REQUIREMENT

=====================

Generate COMPLETE end-to-end FULLY CONNECTED production system implementation with:

* backend-node integration

* ai-python integration

* frontend-kotlin integration

* MongoDB synchronization

* realtime websocket updates

* Android communication

* ADB orchestration

* recording pipeline

* Whisper pipeline

* campaign management

* diagnostics

* retries

* logging

* startup validation

* proper architecture

Generate REAL WORKING implementation only.