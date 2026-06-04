"""
routers/call.py
FastAPI router — ADB+Android+Kotlin GSM call execution AND transcription.

Endpoints:
  POST /api/call/execute     — ADB dial + wait for Android automation + return intent
  POST /api/call/transcribe  — Transcribe a recording by file path
  POST /api/calls/recording  — Android app uploads WAV directly (multipart)
  GET  /api/call/status      — Engine readiness + ADB device status
"""

from __future__ import annotations

import os
import time
from pathlib import Path
from typing import Optional

from fastapi import APIRouter, HTTPException, UploadFile, File, Form
from pydantic import BaseModel

from logger import logger
from config import settings
from transcription.transcription_service import TranscriptionService
from db.models import CallRecord, CallStatus
from db.call_repository import CallRepository

router = APIRouter(prefix="/api/call", tags=["Call"])

# ─── Shared service singleton ──────────────────────────────────────────────────

_transcription_service: Optional[TranscriptionService] = None


def _get_transcription_service() -> TranscriptionService:
    global _transcription_service
    if _transcription_service is None:
        logger.info("[CallRouter] Initializing TranscriptionService...")
        _transcription_service = TranscriptionService()
        _transcription_service.warm_up()
        logger.info("[CallRouter] TranscriptionService ready")
    return _transcription_service


# ─── Schemas ──────────────────────────────────────────────────────────────────

class ExecuteCallRequest(BaseModel):
    """
    POST /api/call/execute
    Called by backend-node AdbCampaignService to trigger a single ADB call.
    ai-python dials via ADB and waits for the Android Kotlin app to complete
    the automation (audio routing + recording + upload).
    """
    name: str
    phone: str


class ExecuteCallResponse(BaseModel):
    success: bool
    name: str
    phone: str
    intent: str = "UNKNOWN"
    transcription: str = ""
    recording_file: str = ""
    stage: str = ""
    duration_seconds: float = 0.0
    error: Optional[str] = None


class TranscribeRequest(BaseModel):
    """
    POST /api/call/transcribe
    Called by backend-node RecordingService after downloading an Exotel recording,
    OR called when backend-node forwards a recording from the Android app.
    """
    recording_path: str
    call_sid: Optional[str] = None
    phone_number: Optional[str] = None
    customer_name: Optional[str] = None


class TranscribeResponse(BaseModel):
    success: bool
    transcription: str = ""
    intent: str = "UNKNOWN"
    confidence: float = 0.0
    call_sid: Optional[str] = None
    phone_number: Optional[str] = None
    db_id: Optional[str] = None
    duration_ms: float = 0.0
    error: Optional[str] = None


# ─── POST /api/call/execute ────────────────────────────────────────────────────

@router.post(
    "/execute",
    response_model=ExecuteCallResponse,
    summary="ADB dial + wait for Android automation + return intent",
)
async def execute_call(request: ExecuteCallRequest):
    """
    Execute a full ADB call for one lead.

    Flow:
      1. ADB dials the customer via Samsung SIM
      2. Android Kotlin app (CallAutomationService) detects CONNECTED
      3. Android plays greeting.wav via PcmStreamingEngine (5 strategies)
      4. Android records 18s customer response
      5. Android uploads WAV to POST /api/calls/recording (this server)
      6. Whisper transcribes → intent detected → MongoDB saved
      7. This endpoint polls for completion and returns the result

    Used by: AdbCampaignService.js (backend-node)
    """
    logger.info(f"[Execute] Request: name={request.name} phone={request.phone}")
    t0 = time.monotonic()

    try:
        from workers.call_worker import execute_call as worker_execute_call
        result = worker_execute_call(name=request.name, phone=request.phone)
    except Exception as exc:
        logger.error(f"[Execute] Worker exception: {exc}")
        return ExecuteCallResponse(
            success=False,
            name=request.name,
            phone=request.phone,
            stage="failed",
            duration_seconds=round(time.monotonic() - t0, 2),
            error=str(exc),
        )

    duration = round(time.monotonic() - t0, 2)

    # Map stage enum to string safely
    stage_val = result.stage.value if hasattr(result.stage, "value") else str(result.stage)
    intent_val = result.intent.value if hasattr(result.intent, "value") else str(result.intent)

    logger.info(
        f"[Execute] Complete: phone={request.phone} "
        f"success={result.success} intent={intent_val} "
        f"stage={stage_val} duration={duration}s"
    )

    return ExecuteCallResponse(
        success=result.success,
        name=request.name,
        phone=request.phone,
        intent=intent_val,
        transcription=result.transcription or "",
        recording_file=result.recording_file or "",
        stage=stage_val,
        duration_seconds=duration,
        error=result.error_message,
    )


# ─── POST /api/calls/recording ─────────────────────────────────────────────────

@router.post(
    "/recording",
    response_model=TranscribeResponse,
    tags=["Recording"],
    summary="Android app uploads recording WAV directly",
)
async def receive_android_recording(
    file: UploadFile = File(...),
    callType: str = Form(default="outgoing"),
    remoteNumber: str = Form(default="unknown"),
    timestamp: str = Form(default=""),
):
    """
    Receives a WAV recording uploaded directly by the Android Kotlin app
    (ApiClient.kt → POST /api/calls/recording).

    Saves the file to recordings/, runs Whisper transcription, detects intent,
    saves to MongoDB, and returns the result to the Android app.

    NOTE: The route is registered WITHOUT the /api/call prefix to match
    the Android app's existing endpoint: /api/calls/recording
    This router adds /api/call prefix so we use a separate router registration.
    """
    logger.info(
        f"[AndroidUpload] Received: filename={file.filename} "
        f"number={remoteNumber} callType={callType} ts={timestamp}"
    )
    t0 = time.monotonic()

    # Save uploaded file
    recordings_dir = Path(settings.recordings_dir).resolve()
    recordings_dir.mkdir(parents=True, exist_ok=True)

    safe_name = f"android_{remoteNumber.replace('+', '')}_{timestamp or int(time.time())}.wav"
    save_path = recordings_dir / safe_name

    try:
        content = await file.read()
        save_path.write_bytes(content)
        logger.info(f"[AndroidUpload] Saved {len(content)} bytes → {save_path}")
    except Exception as exc:
        logger.error(f"[AndroidUpload] Save failed: {exc}")
        raise HTTPException(status_code=500, detail=f"Failed to save recording: {exc}")

    # Transcribe
    return await _transcribe_and_save(
        recording_path=save_path,
        phone_number=remoteNumber if remoteNumber != "unknown" else None,
        customer_name=None,
        call_sid=None,
        t0=t0,
    )


# ─── POST /api/call/transcribe ────────────────────────────────────────────────

@router.post(
    "/transcribe",
    response_model=TranscribeResponse,
    summary="Transcribe a recording by file path",
)
async def transcribe_recording(request: TranscribeRequest):
    """
    Accept a recording file path, transcribe with Faster-Whisper,
    detect YES/NO intent, save to MongoDB, return result.

    Called by:
    - backend-node RecordingService.sendForTranscription (Exotel flow)
    - backend-node AdbCampaignService after Android upload
    """
    logger.info(
        f"[Transcribe] Path={request.recording_path} "
        f"sid={request.call_sid} phone={request.phone_number}"
    )
    t0 = time.monotonic()

    recording_path = Path(request.recording_path)
    if not recording_path.exists():
        logger.error(f"[Transcribe] File not found: {recording_path}")
        raise HTTPException(status_code=404, detail=f"Recording not found: {request.recording_path}")
    if not recording_path.is_file() or recording_path.stat().st_size == 0:
        raise HTTPException(status_code=400, detail="Recording file is empty or invalid")

    return await _transcribe_and_save(
        recording_path=recording_path,
        phone_number=request.phone_number,
        customer_name=request.customer_name,
        call_sid=request.call_sid,
        t0=t0,
    )


# ─── GET /api/call/status ─────────────────────────────────────────────────────

@router.get("/status", summary="Engine readiness + ADB device status")
async def get_status():
    """
    Health check for the transcription engine and ADB device.
    Returns Whisper model status and ADB device info.
    """
    result: dict = {
        "status": "ready",
        "engine": "faster-whisper",
        "mode": "adb+android",
        "adb": {},
        "whisper": {},
        "mongodb": False,
    }

    # Whisper status
    try:
        svc = _get_transcription_service()
        model_name = getattr(svc.engine, "_model_name", "unknown")
        result["whisper"] = {
            "model": model_name,
            "ready": svc.engine.is_ready,
        }
    except Exception as exc:
        result["whisper"] = {"error": str(exc), "ready": False}
        result["status"] = "initializing"

    # ADB device status
    try:
        from adb.device_checker import get_connected_devices
        from adb.models import DeviceState
        devices = get_connected_devices()
        online = [d for d in devices if d.state == DeviceState.ONLINE]
        result["adb"] = {
            "devices_connected": len(devices),
            "devices_online": len(online),
            "serials": [d.serial for d in online],
        }
    except Exception as exc:
        result["adb"] = {"error": str(exc)}

    # MongoDB ping
    try:
        from db.utils import ping
        result["mongodb"] = ping()
    except Exception:
        result["mongodb"] = False

    return result


# ─── Shared transcription helper ──────────────────────────────────────────────

async def _transcribe_and_save(
    recording_path: Path,
    phone_number: Optional[str],
    customer_name: Optional[str],
    call_sid: Optional[str],
    t0: float,
) -> TranscribeResponse:
    """Shared logic: Whisper + intent + MongoDB save."""

    # Transcribe
    try:
        svc = _get_transcription_service()
    except Exception as exc:
        logger.error(f"[Transcribe] Service init failed: {exc}")
        raise HTTPException(status_code=500, detail=f"Transcription service unavailable: {exc}")

    result = svc.transcribe_file(recording_path)
    elapsed_ms = (time.monotonic() - t0) * 1000

    if not result.success:
        logger.error(f"[Transcribe] Failed: {result.error_message}")
        raise HTTPException(status_code=500, detail=f"Transcription failed: {result.error_message}")

    intent_str = result.intent.value if hasattr(result.intent, "value") else str(result.intent)

    logger.info(
        f"[Transcribe] intent={intent_str} "
        f'text="{result.transcription[:80]}" '
        f"conf={result.confidence:.2f} "
        f"time={elapsed_ms:.0f}ms"
    )

    # Save to MongoDB
    db_id: Optional[str] = None
    try:
        repo = CallRepository()
        status = CallStatus.COMPLETED if result.success else CallStatus.FAILED
        record = CallRecord(
            name=customer_name or "Unknown",
            phone_number=phone_number or "+00000000000",
            call_status=status,
            transcription=result.transcription,
            intent=intent_str,
            recording_file=str(recording_path),
        )
        db_id = repo.insert(record)
        logger.info(f"[Transcribe] Saved to MongoDB: {db_id}")
    except Exception as exc:
        logger.error(f"[Transcribe] MongoDB save failed (non-fatal): {exc}")

    if phone_number and phone_number != "+00000000000":
        try:
            from integration.backend_events import (
                emit_transcription_done,
                emit_intent_detected,
                emit_call_completed,
            )
            emit_transcription_done(
                phone_number,
                result.transcription or "",
                intent_str,
                result.confidence,
            )
            emit_intent_detected(phone_number, intent_str, result.confidence)
            emit_call_completed(
                phone_number,
                intent_str,
                result.transcription or "",
                db_id,
            )
        except Exception as exc:
            logger.debug(f"[Transcribe] Backend WS notify skipped: {exc}")

    return TranscribeResponse(
        success=True,
        transcription=result.transcription,
        intent=intent_str,
        confidence=round(result.confidence, 3),
        call_sid=call_sid,
        phone_number=phone_number,
        db_id=db_id,
        duration_ms=round(elapsed_ms, 1),
    )
