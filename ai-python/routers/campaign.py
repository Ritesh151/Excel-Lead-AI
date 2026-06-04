"""
routers/campaign.py
Campaign router — DISABLED in Exotel flow.

ARCHITECTURE NOTE (v2 — Exotel flow):
  Campaign orchestration has moved entirely to backend-node/CampaignService.js.
  backend-node reads Excel, tracks leads in MongoDB, places Exotel calls,
  receives webhooks, downloads recordings, and calls ai-python/api/call/transcribe.

  This router is intentionally reduced to status-only stubs so that:
    - The FastAPI app still starts without errors
    - Any old client calling /api/campaign/start gets a clear error message
    - The route surface remains available for future re-use

  DO NOT add ADB / CallOrchestrator logic back here.
"""

from __future__ import annotations

from fastapi import APIRouter, HTTPException

from logger import logger


router = APIRouter(prefix="/api/campaign", tags=["Campaign"])


@router.post("/start", summary="[DISABLED] Campaign start — use backend-node instead")
async def start_campaign():
    """
    Campaign orchestration is handled by backend-node/CampaignService.js.
    Call POST http://localhost:3000/api/call/start instead.
    """
    logger.warning(
        "[Campaign] /api/campaign/start called on ai-python — "
        "this endpoint is disabled. Use backend-node POST /api/call/start."
    )
    raise HTTPException(
        status_code=410,  # 410 Gone — intentionally disabled
        detail=(
            "Campaign orchestration has moved to backend-node. "
            "Start a campaign via: POST http://localhost:3000/api/call/start"
        ),
    )


@router.post("/stop", summary="[DISABLED] Campaign stop — use backend-node instead")
async def stop_campaign():
    logger.warning("[Campaign] /api/campaign/stop called — disabled, use backend-node.")
    raise HTTPException(
        status_code=410,
        detail="Use POST http://localhost:3000/api/call/stop",
    )


@router.get("/status", summary="[DISABLED] Campaign status — use backend-node instead")
async def get_status():
    return {
        "running": False,
        "mode": "exotel",
        "note": "Campaign orchestration runs in backend-node. GET http://localhost:3000/api/call/status",
        "adb_disabled": True,
    }
