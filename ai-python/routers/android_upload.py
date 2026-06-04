"""
routers/android_upload.py
Dedicated router for the Android Kotlin app upload endpoint.

The Android ApiClient.kt posts to /api/calls/recording (note the plural 'calls').
This router exposes that exact path so the Android app does not need any code change.

Flow:
  Android ApiClient → POST /api/calls/recording (multipart)
  → save WAV to recordings/
  → Whisper transcription
  → intent detection
  → MongoDB save
  → return { intent, transcription } to Android app
"""

from __future__ import annotations

import time
from pathlib import Path
from typing import Optional

from fastapi import APIRouter, HTTPException, UploadFile, File, Form
from pydantic import BaseModel

from config import settings
from logger import logger

router = APIRouter(tags=["Android"])


class AndroidUploadResponse(BaseModel):
    intent: str = "UNKNOWN"
    transcription: Optional[str] = None
    success: bool = True
    db_id: Optional[str] = None
    duration_ms: float = 0.0


@router.post(
    "/api/calls/recording",
    response_model=AndroidUploadResponse,
    summary="Android app uploads recording WAV (ApiClient.kt)",
)
async def android_recording_upload(
    file: UploadFile = File(...),
    callType: str = Form(default="outgoing"),
    remoteNumber: str = Form(default="unknown"),
    timestamp: str = Form(default=""),
):
    """
    Receives multipart WAV from the Android Kotlin app (ApiClient.kt).
    Saves to recordings/, transcribes with Whisper, updates MongoDB,
    returns intent+transcription to the app.

    ApiClient.kt expects response JSON:
      { "intent": "YES" | "NO" | "UNKNOWN", "transcription": "..." }
    """
    logger.info(
        f"[AndroidUpload] file={file.filename} number={remoteNumber} "
        f"callType={callType} ts={timestamp}"
    )
    t0 = time.monotonic()

    # Validate file
    if not file.filename:
        raise HTTPException(status_code=400, detail="No file provided")

    content = await file.read()
    if len(content) < 44:
        logger.warning(f"[AndroidUpload] File too small ({len(content)} bytes) — likely empty")
        return AndroidUploadResponse(intent="UNKNOWN", transcription=None, success=False)

    # Save to disk
    recordings_dir = Path(settings.recordings_dir).resolve()
    recordings_dir.mkdir(parents=True, exist_ok=True)

    ts_suffix = timestamp or str(int(time.time()))
    safe_number = remoteNumber.replace('+', '').replace(' ', '')
    save_name = f"android_{safe_number}_{ts_suffix}.wav"
    save_path = recordings_dir / save_name

    try:
        save_path.write_bytes(content)
        logger.info(f"[AndroidUpload] Saved {len(content)} bytes → {save_path.name}")
    except Exception as exc:
        logger.error(f"[AndroidUpload] Save error: {exc}")
        raise HTTPException(status_code=500, detail=f"Failed to save recording: {exc}")

    # Transcribe + save
    try:
        from routers.call import _transcribe_and_save
        response = await _transcribe_and_save(
            recording_path=save_path,
            phone_number=remoteNumber if remoteNumber != "unknown" else None,
            customer_name=None,
            call_sid=None,
            t0=t0,
        )
        return AndroidUploadResponse(
            intent=response.intent,
            transcription=response.transcription,
            success=response.success,
            db_id=response.db_id,
            duration_ms=response.duration_ms,
        )
    except HTTPException as exc:
        # Return UNKNOWN intent so Android app can continue
        logger.error(f"[AndroidUpload] Transcription error: {exc.detail}")
        return AndroidUploadResponse(
            intent="UNKNOWN",
            transcription=None,
            success=False,
            duration_ms=round((time.monotonic() - t0) * 1000, 1),
        )
    except Exception as exc:
        logger.error(f"[AndroidUpload] Unexpected error: {exc}")
        return AndroidUploadResponse(
            intent="UNKNOWN",
            transcription=None,
            success=False,
            duration_ms=round((time.monotonic() - t0) * 1000, 1),
        )
