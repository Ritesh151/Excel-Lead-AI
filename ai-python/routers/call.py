"""
routers/call.py
FastAPI router for single-call execution.

POST /api/call/execute  — dial a phone number, play greeting, record,
                          transcribe, detect intent, save to MongoDB.
"""

from __future__ import annotations

import time
from pathlib import Path
from typing import Optional

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, field_validator

from logger import logger
from config import settings
from workflow.orchestrator import CallOrchestrator
from workflow.states import CallStage, Intent
from db.models import CallRecord, CallStatus
from db.call_repository import CallRepository


# ─── Request / Response Schemas ───────────────────────────────────────────

class ExecuteCallRequest(BaseModel):
    name: str
    phone: str

    @field_validator("phone")
    @classmethod
    def ensure_e164(cls, v: str) -> str:
        cleaned = v.strip().replace(" ", "").replace("-", "")
        if not cleaned.startswith("+"):
            cleaned = "+" + cleaned
        if not cleaned[1:].isdigit() or len(cleaned) < 8:
            raise ValueError(f"Invalid phone number: {v}")
        return cleaned

    @field_validator("name")
    @classmethod
    def ensure_name(cls, v: str) -> str:
        v = v.strip()
        if not v:
            raise ValueError("name is required")
        return v


class ExecuteCallResponse(BaseModel):
    success: bool
    phone: str
    name: str
    intent: str = "UNKNOWN"
    transcription: str = ""
    recording_file: str = ""
    db_id: Optional[str] = None
    duration_seconds: float = 0.0
    error: Optional[str] = None


# ─── Router ───────────────────────────────────────────────────────────────

router = APIRouter(prefix="/api/call", tags=["Call"])

_orchestrator: Optional[CallOrchestrator] = None


def _get_orch() -> CallOrchestrator:
    global _orchestrator
    if _orchestrator is None:
        _orchestrator = CallOrchestrator()
        _orchestrator.initialize()
    return _orchestrator


@router.post("/execute", response_model=ExecuteCallResponse, summary="Execute a single call")
async def execute_call(request: ExecuteCallRequest):
    """
    Dial `phone` via ADB, play greeting, record response,
    transcribe with Whisper, detect YES/NO, save to MongoDB.
    """
    logger.info(f"Call execute: {request.name} ({request.phone})")

    try:
        orch = _get_orch()
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"Orchestrator init failed: {exc}")

    t0 = time.monotonic()
    result = orch.execute_single_call(name=request.name, phone=request.phone)
    elapsed = time.monotonic() - t0

    # Save to MongoDB (always save when call connected, regardless of intent)
    db_id = None
    if result.stage != CallStage.IDLE:
        try:
            repo = CallRepository()
            call_status = CallStatus.COMPLETED if result.success else CallStatus.FAILED
            record = CallRecord(
                name=request.name,
                phone_number=request.phone,
                call_status=call_status,
                transcription=result.transcription,
                intent=result.intent.value,
                recording_file=result.recording_file,
            )
            db_id = repo.insert(record)
            logger.info(f"Saved to MongoDB: {db_id}")
        except Exception as exc:
            logger.error(f"MongoDB save failed: {exc}")

    logger.info(
        f"Call complete: {request.phone} -> intent={result.intent.value} "
        f"time={elapsed:.1f}s db_id={db_id}"
    )

    return ExecuteCallResponse(
        success=result.success,
        phone=request.phone,
        name=request.name,
        intent=result.intent.value,
        transcription=result.transcription,
        recording_file=result.recording_file,
        db_id=db_id,
        duration_seconds=round(elapsed, 1),
        error=result.error_message,
    )


@router.get("/status", summary="Get current call engine status")
async def get_status():
    try:
        orch = _get_orch()
        return {
            "status": "ready",
            "device_connected": orch._device is not None if hasattr(orch, '_device') else False,
        }
    except Exception as exc:
        return {"status": "error", "detail": str(exc)}
