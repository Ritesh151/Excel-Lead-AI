"""
routers/call.py
FastAPI router for transcription-only call processing.

ARCHITECTURE NOTE (v2 — Exotel flow):
  The ADB/Android dialing path has been DISABLED.
  This router now only handles:
    POST /api/transcribe  — called by backend-node after Exotel delivers a recording
    GET  /api/call/status — health/readiness check for the transcription engine

  Outbound calls are placed exclusively by backend-node via the Exotel API.
  This service has NO responsibility for dialing, audio playback, or ADB.
"""

from __future__ import annotations

import time
from pathlib import Path
from typing import Optional

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

from logger import logger
from transcription.transcription_service import TranscriptionService
from db.models import CallRecord, CallStatus
from db.call_repository import CallRepository


# ─── Shared transcription service instance ────────────────────────────────────

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

class TranscribeRequest(BaseModel):
    """
    Called by backend-node after a recording is received from Exotel.
    """
    recording_path: str           # absolute path on shared filesystem
    call_sid: Optional[str] = None
    phone_number: Optional[str] = None
    customer_name: Optional[str] = None


class TranscribeResponse(BaseModel):
    success: bool
    transcription: str = ""
    intent: str = "UNKNOWN"       # YES | NO | UNKNOWN
    confidence: float = 0.0
    call_sid: Optional[str] = None
    phone_number: Optional[str] = None
    db_id: Optional[str] = None
    duration_ms: float = 0.0
    error: Optional[str] = None


# ─── Router ───────────────────────────────────────────────────────────────────

router = APIRouter(prefix="/api/call", tags=["Call"])


@router.post(
    "/transcribe",
    response_model=TranscribeResponse,
    summary="Transcribe a recording and detect YES/NO intent",
)
async def transcribe_recording(request: TranscribeRequest):
    """
    Accept a recording file path, transcribe it with Faster-Whisper,
    detect YES/NO intent, save the result to MongoDB, and return the result.

    Called by backend-node RecordingService after downloading a recording
    from Exotel. This is the ONLY ai-python call entry point in Exotel flow.
    """
    logger.info(
        "[Transcribe] Request received",
        extra={
            "recording_path": request.recording_path,
            "call_sid": request.call_sid,
            "phone_number": request.phone_number,
        },
    )

    t0 = time.monotonic()

    # ── Validate file ─────────────────────────────────────────────────────────
    recording_path = Path(request.recording_path)
    if not recording_path.exists():
        logger.error(f"[Transcribe] Recording not found: {recording_path}")
        raise HTTPException(
            status_code=404,
            detail=f"Recording file not found: {request.recording_path}",
        )
    if not recording_path.is_file():
        raise HTTPException(
            status_code=400,
            detail=f"Path is not a file: {request.recording_path}",
        )
    if recording_path.stat().st_size == 0:
        raise HTTPException(
            status_code=400,
            detail=f"Recording file is empty: {request.recording_path}",
        )

    # ── Transcribe ────────────────────────────────────────────────────────────
    try:
        svc = _get_transcription_service()
    except Exception as exc:
        logger.error(f"[Transcribe] TranscriptionService init failed: {exc}")
        raise HTTPException(
            status_code=500,
            detail=f"Transcription service unavailable: {exc}",
        )

    result = svc.transcribe_file(recording_path)
    elapsed_ms = (time.monotonic() - t0) * 1000

    if not result.success:
        logger.error(
            f"[Transcribe] Transcription failed: {result.error_message}",
            extra={"call_sid": request.call_sid},
        )
        raise HTTPException(
            status_code=500,
            detail=f"Transcription failed: {result.error_message}",
        )

    intent_str = result.intent.value if hasattr(result.intent, "value") else str(result.intent)

    logger.info(
        f"[Transcribe] Complete — intent={intent_str} "
        f'text="{result.transcription[:80]}" '
        f"conf={result.confidence:.2f} "
        f"time={elapsed_ms:.0f}ms",
    )

    # ── Save to MongoDB ───────────────────────────────────────────────────────
    db_id: Optional[str] = None
    try:
        repo = CallRepository()
        status = CallStatus.COMPLETED if result.success else CallStatus.FAILED
        record = CallRecord(
            name=request.customer_name or "Unknown",
            phone_number=request.phone_number or "",
            call_status=status,
            transcription=result.transcription,
            intent=intent_str,
            recording_file=str(recording_path),
        )
        db_id = repo.insert(record)
        logger.info(f"[Transcribe] Saved to MongoDB: {db_id}")
    except Exception as exc:
        # Never let a DB failure block the response — backend-node already
        # stores the call_sid; MongoDB save here is supplementary.
        logger.error(f"[Transcribe] MongoDB save failed (non-fatal): {exc}")

    return TranscribeResponse(
        success=True,
        transcription=result.transcription,
        intent=intent_str,
        confidence=round(result.confidence, 3),
        call_sid=request.call_sid,
        phone_number=request.phone_number,
        db_id=db_id,
        duration_ms=round(elapsed_ms, 1),
    )


@router.get("/status", summary="Transcription engine readiness check")
async def get_status():
    """
    Health check for the transcription engine.
    Returns whether the Whisper model is loaded and ready.
    """
    try:
        svc = _get_transcription_service()
        return {
            "status": "ready",
            "engine": "faster-whisper",
            "model": svc.engine._model_name if hasattr(svc.engine, "_model_name") else "unknown",
            "adb_disabled": True,
            "mode": "exotel-transcription-only",
        }
    except Exception as exc:
        return {
            "status": "initializing",
            "detail": str(exc),
            "adb_disabled": True,
            "mode": "exotel-transcription-only",
        }
