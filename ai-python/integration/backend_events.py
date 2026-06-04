"""
Notify backend-node WebSocket layer via HTTP POST /api/events/emit.
Used after transcription, call execution milestones, and device status changes.
"""

from __future__ import annotations

from typing import Any, Optional

import httpx

from config import settings
from logger import logger

_client: Optional[httpx.Client] = None


def _get_client() -> httpx.Client:
    global _client
    if _client is None:
        _client = httpx.Client(
            timeout=httpx.Timeout(10.0, connect=5.0),
            headers=_auth_headers(),
        )
    return _client


def _auth_headers() -> dict[str, str]:
    token = (settings.internal_api_token or "").strip()
    if not token:
        return {}
    return {"Authorization": f"Bearer {token}"}


def emit_event(event: str, payload: Optional[dict[str, Any]] = None) -> bool:
    """
    POST { event, payload } to backend-node. Non-fatal on failure.
    """
    base = (settings.backend_node_url or "").rstrip("/")
    if not base:
        return False

    body = {"event": event, "payload": payload or {}}
    try:
        resp = _get_client().post(f"{base}/api/events/emit", json=body)
        if resp.status_code >= 400:
            logger.warning(f"[BackendEvents] emit failed {event}: HTTP {resp.status_code}")
            return False
        return True
    except Exception as exc:
        logger.debug(f"[BackendEvents] emit {event} skipped: {exc}")
        return False


def emit_call_started(phone: str, name: str = "", campaign_id: str = "") -> None:
    emit_event("call_started", {"phone": phone, "name": name, "campaignId": campaign_id})


def emit_call_connected(phone: str) -> None:
    emit_event("call_connected", {"phone": phone})


def emit_greeting_played(phone: str, success: bool = True) -> None:
    emit_event("greeting_played", {"phone": phone, "success": success})


def emit_recording_started(phone: str) -> None:
    emit_event("recording_started", {"phone": phone})


def emit_recording_saved(phone: str, file_path: str, duration_sec: int = 0) -> None:
    emit_event(
        "recording_saved",
        {"phone": phone, "filePath": file_path, "durationSec": duration_sec},
    )


def emit_transcription_done(
    phone: str,
    transcription: str,
    intent: str,
    confidence: float = 0.0,
) -> None:
    emit_event(
        "transcription_done",
        {
            "phone": phone,
            "transcription": transcription,
            "intent": intent,
            "confidence": confidence,
        },
    )


def emit_intent_detected(phone: str, intent: str, confidence: float = 0.0) -> None:
    emit_event(
        "intent_detected",
        {"phone": phone, "intent": intent, "confidence": confidence},
    )


def emit_call_completed(
    phone: str,
    intent: str,
    transcription: str = "",
    db_id: Optional[str] = None,
) -> None:
    emit_event(
        "call_completed",
        {
            "phone": phone,
            "intent": intent,
            "transcription": transcription,
            "dbId": db_id,
        },
    )


def emit_call_failed(phone: str, error: str) -> None:
    emit_event("call_failed", {"phone": phone, "error": error})


def emit_device_status(serial: str, model: str, status: str) -> None:
    emit_event("device_status", {"serial": serial, "model": model, "status": status})
