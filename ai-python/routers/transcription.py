"""
FastAPI transcription endpoint
Handles recording transcription requests from Node.js backend
"""

import os
from pathlib import Path
from typing import Optional

from fastapi import APIRouter, HTTPException, UploadFile, File
from pydantic import BaseModel

from transcription.transcription_service import TranscriptionService
from logger import logger

router = APIRouter()
transcription_service = TranscriptionService()


class TranscriptionRequest(BaseModel):
    """Request model for transcription"""
    recording_path: str
    call_sid: Optional[str] = None
    phone_number: Optional[str] = None


class TranscriptionResponse(BaseModel):
    """Response model for transcription"""
    success: bool
    transcription: str
    intent: str  # YES, NO, or UNCLEAR
    confidence: float
    call_sid: Optional[str] = None
    phone_number: Optional[str] = None
    error_message: Optional[str] = None


@router.post("/api/transcribe", response_model=TranscriptionResponse)
async def transcribe_recording(request: TranscriptionRequest):
    """
    Transcribe a recording file and detect intent
    
    Args:
        request: TranscriptionRequest with recording_path
        
    Returns:
        TranscriptionResponse with transcription and intent detection
    """
    try:
        logger.info(f"Transcription request received", {
            "recording_path": request.recording_path,
            "call_sid": request.call_sid,
            "phone_number": request.phone_number,
        })

        # Validate recording path exists
        recording_path = Path(request.recording_path)
        if not recording_path.exists():
            logger.error(f"Recording file not found", {
                "path": str(recording_path)
            })
            raise HTTPException(
                status_code=404,
                detail=f"Recording file not found: {request.recording_path}"
            )

        # Validate file is readable
        if not recording_path.is_file():
            raise HTTPException(
                status_code=400,
                detail="Invalid recording path"
            )

        # Transcribe recording
        result = await transcription_service.transcribe(str(recording_path))

        if not result.success:
            logger.error(f"Transcription failed", {
                "recording_path": request.recording_path,
                "error": result.error_message,
            })
            raise HTTPException(
                status_code=500,
                detail=f"Transcription failed: {result.error_message}"
            )

        # Format intent
        intent_str = result.intent.value.upper() if hasattr(result.intent, 'value') else str(result.intent).upper()

        logger.info(f"Transcription completed", {
            "call_sid": request.call_sid,
            "transcription": result.transcription[:100],
            "intent": intent_str,
            "confidence": result.confidence,
        })

        return TranscriptionResponse(
            success=True,
            transcription=result.transcription,
            intent=intent_str,
            confidence=round(result.confidence, 3),
            call_sid=request.call_sid,
            phone_number=request.phone_number,
            error_message=None,
        )

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Transcription endpoint error", {
            "error": str(e),
            "recording_path": request.recording_path,
        })
        raise HTTPException(
            status_code=500,
            detail=f"Transcription error: {str(e)}"
        )


@router.post("/api/transcribe/upload", response_model=TranscriptionResponse)
async def transcribe_upload(
    file: UploadFile = File(...),
    call_sid: Optional[str] = None,
    phone_number: Optional[str] = None,
):
    """
    Transcribe an uploaded recording file
    
    Args:
        file: Uploaded audio file
        call_sid: Call SID for tracking
        phone_number: Customer phone number
        
    Returns:
        TranscriptionResponse
    """
    temp_path = None
    try:
        logger.info(f"File upload received", {
            "filename": file.filename,
            "content_type": file.content_type,
            "call_sid": call_sid,
        })

        # Save temp file
        temp_dir = Path(os.getenv("TEMP_DIR", "/tmp"))
        temp_dir.mkdir(exist_ok=True)
        temp_path = temp_dir / f"{call_sid or 'upload'}_{file.filename}"

        # Write file
        with open(temp_path, "wb") as f:
            content = await file.read()
            f.write(content)

        # Transcribe
        result = await transcription_service.transcribe(str(temp_path))

        if not result.success:
            raise HTTPException(
                status_code=500,
                detail=f"Transcription failed: {result.error_message}"
            )

        intent_str = result.intent.value.upper() if hasattr(result.intent, 'value') else str(result.intent).upper()

        return TranscriptionResponse(
            success=True,
            transcription=result.transcription,
            intent=intent_str,
            confidence=round(result.confidence, 3),
            call_sid=call_sid,
            phone_number=phone_number,
        )

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Upload transcription error", {
            "error": str(e),
            "filename": file.filename,
        })
        raise HTTPException(
            status_code=500,
            detail=f"Upload transcription error: {str(e)}"
        )
    finally:
        # Cleanup temp file
        if temp_path and temp_path.exists():
            try:
                temp_path.unlink()
            except Exception as e:
                logger.warn(f"Failed to delete temp file", {
                    "path": str(temp_path),
                    "error": str(e),
                })


@router.get("/api/health")
async def health():
    """Health check endpoint"""
    return {
        "status": "ok",
        "service": "transcription-api",
        "timestamp": str(Path(__file__).stem),
    }
