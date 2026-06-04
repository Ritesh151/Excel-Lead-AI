"""
routers/call.py
FastAPI router — ADB+Android+Kotlin GSM call execution AND transcription.

Endpoints:
  POST /api/call/execute     — ADB dial + wait for Android automation + return intent
  POST /api/call/transcribe  — Transcribe a recording by file path
  GET  /api/call/status      — Engine readiness + ADB device status
  GET  /api/debug/device     — ADB device diagnostics
  GET  /api/debug/campaign   — Campaign/queue state
  GET  /api/debug/adb        — Raw ADB state dump
  GET  /api/debug/queue      — Queue processing state
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

router = APIRouter(tags=["Call"])

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
    Called by backend-node AdbCampaignService.
    Fields accepted: name, phone, lead_id (optional), campaign_id (optional).
    """
    name: str
    phone: str
    lead_id: Optional[str] = None
    campaign_id: Optional[str] = None


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
    "/api/call/execute",
    response_model=ExecuteCallResponse,
    summary="ADB dial + wait for Android automation + return intent",
)
async def execute_call(request: ExecuteCallRequest):
    """
    Execute a full ADB call for one lead.

    Flow:
      1. ADB dials the customer via Samsung SIM
      2. Android Kotlin app (CallAutomationService) detects CONNECTED
      3. Android plays greeting.wav via PcmStreamingEngine
      4. Android records 18s customer response
      5. Android uploads WAV → Whisper → intent saved to MongoDB
      6. This endpoint returns the result

    Used by: AdbCampaignService.js (backend-node)
    """
    logger.info(f"[Execute] Request: name={request.name} phone={request.phone} "
                f"lead_id={request.lead_id} campaign_id={request.campaign_id}")
    t0 = time.monotonic()

    try:
        from workers.call_worker import execute_call as worker_execute_call
        result = worker_execute_call(name=request.name, phone=request.phone)
    except Exception as exc:
        logger.error(f"[Execute] Worker exception: {exc}", exc_info=True)
        return ExecuteCallResponse(
            success=False,
            name=request.name,
            phone=request.phone,
            stage="failed",
            duration_seconds=round(time.monotonic() - t0, 2),
            error=str(exc),
        )

    duration = round(time.monotonic() - t0, 2)

    stage_val = result.stage.value if hasattr(result.stage, "value") else str(result.stage)
    intent_val = result.intent.value if hasattr(result.intent, "value") else str(result.intent)

    logger.info(
        f"[Execute] Complete: phone={request.phone} "
        f"success={result.success} intent={intent_val} "
        f"stage={stage_val} duration={duration}s"
    )

    # Notify backend-node of call completion
    try:
        from integration.backend_events import emit_call_completed, emit_call_failed
        if result.success:
            emit_call_completed(request.phone, intent_val, result.transcription or "", None)
        else:
            emit_call_failed(request.phone, result.error_message or "Call failed")
    except Exception as e:
        logger.debug(f"[Execute] Backend event notify skipped: {e}")

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


# ─── POST /api/call/transcribe ────────────────────────────────────────────────

@router.post(
    "/api/call/transcribe",
    response_model=TranscribeResponse,
    summary="Transcribe a recording by file path",
)
async def transcribe_recording(request: TranscribeRequest):
    """
    Accept a recording file path, transcribe with Faster-Whisper,
    detect YES/NO intent, save to MongoDB, return result.
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

@router.get("/api/call/status", summary="Engine readiness + ADB device status")
async def get_status():
    result: dict = {
        "status": "ready",
        "engine": "faster-whisper",
        "mode": "adb+android",
        "adb": {},
        "whisper": {},
        "mongodb": False,
    }

    try:
        svc = _get_transcription_service()
        model_name = getattr(svc.engine, "_model_name", "unknown")
        result["whisper"] = {"model": model_name, "ready": svc.engine.is_ready}
    except Exception as exc:
        result["whisper"] = {"error": str(exc), "ready": False}
        result["status"] = "initializing"

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

    try:
        from db.utils import ping
        result["mongodb"] = ping()
    except Exception:
        result["mongodb"] = False

    return result


# ─── GET /api/debug/device ────────────────────────────────────────────────────

@router.get("/api/debug/device", summary="Full ADB device diagnostics")
async def debug_device():
    """Return complete device health: online/offline, model, Android version, call state."""
    result: dict = {
        "timestamp": time.time(),
        "adb_binary": None,
        "devices": [],
        "selected_device": None,
        "call_state": None,
        "screen_on": None,
        "error": None,
    }

    import shutil
    result["adb_binary"] = shutil.which("adb")

    try:
        from adb.device_checker import get_connected_devices, enrich_device_info
        from adb.models import DeviceState
        devices = get_connected_devices()
        result["devices"] = [
            {"serial": d.serial, "state": d.state.value, "model": d.model or ""}
            for d in devices
        ]
        online = [d for d in devices if d.state == DeviceState.ONLINE]
        if online:
            dev = enrich_device_info(online[0])
            result["selected_device"] = {
                "serial": dev.serial,
                "model": dev.model,
                "android_version": dev.android_version,
            }
            try:
                from adb.adb_manager import AdbManager
                mgr = AdbManager(serial=dev.serial)
                result["call_state"] = mgr.get_call_state_int()
                result["screen_on"] = mgr.is_screen_on()
            except Exception as e:
                result["call_state"] = f"error: {e}"
    except Exception as exc:
        result["error"] = str(exc)

    return result


# ─── GET /api/debug/adb ───────────────────────────────────────────────────────

@router.get("/api/debug/adb", summary="Raw ADB state and telephony dump")
async def debug_adb():
    """Return raw telephony.registry dump and device state."""
    result: dict = {
        "timestamp": time.time(),
        "device_serial": settings.adb_device_serial or "auto",
        "call_state_int": None,
        "telephony_raw_snippet": None,
        "error": None,
    }
    try:
        from adb.adb_manager import AdbManager
        serial = settings.adb_device_serial or None
        mgr = AdbManager(serial=serial)
        raw = mgr.get_telephony_state_raw()
        result["call_state_int"] = mgr.get_call_state_int()
        # Return only first 500 chars to keep response small
        result["telephony_raw_snippet"] = raw[:500] if raw else ""
    except Exception as exc:
        result["error"] = str(exc)

    return result


# ─── GET /api/debug/campaign ──────────────────────────────────────────────────

@router.get("/api/debug/campaign", summary="Campaign/orchestrator state")
async def debug_campaign():
    """Return current orchestrator state — active leads, retries, current phone."""
    from workers.call_worker import _orchestrator
    if _orchestrator is None:
        return {
            "timestamp": time.time(),
            "initialized": False,
            "message": "Orchestrator not yet initialized (no calls have been made)",
        }
    return {
        "timestamp": time.time(),
        "initialized": True,
        "should_stop": _orchestrator._should_stop,
        "results_count": len(_orchestrator._results),
        "leads_loaded": len(_orchestrator._leads),
        "device_serial": _orchestrator._device.serial if _orchestrator._device else None,
        "device_model": _orchestrator._device.model if _orchestrator._device else None,
    }


# ─── GET /api/debug/queue ─────────────────────────────────────────────────────

@router.get("/api/debug/queue", summary="Queue processing diagnostics")
async def debug_queue():
    """Check MongoDB queue state: pending/calling/completed counts."""
    result: dict = {
        "timestamp": time.time(),
        "mongodb_connected": False,
        "pending": 0,
        "calling": 0,
        "completed": 0,
        "failed": 0,
        "error": None,
    }
    try:
        from db.mongodb_client import get_client
        from db.utils import ping
        result["mongodb_connected"] = ping()
        if result["mongodb_connected"]:
            # Try to query lead counts if leads collection is accessible
            client = get_client()
            db = client._client[settings.mongo_db_name]
            for status in ("pending", "calling", "completed", "failed"):
                try:
                    result[status] = db["leads"].count_documents({"status": status})
                except Exception:
                    result[status] = -1
    except Exception as exc:
        result["error"] = str(exc)

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
        f'text="{(result.transcription or "")[:80]}" '
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
