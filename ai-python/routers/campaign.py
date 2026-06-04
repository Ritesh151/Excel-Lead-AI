"""
routers/campaign.py
FastAPI router for campaign-level orchestration.

POST /api/campaign/start   — load leads, run sequential calls in background
POST /api/campaign/stop    — request graceful stop
GET  /api/campaign/status  — current campaign state and results
"""

from __future__ import annotations

import threading
import time
from typing import Optional

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

from logger import logger
from config import settings
from workflow.orchestrator import CallOrchestrator
from workflow.states import LeadResult


router = APIRouter(prefix="/api/campaign", tags=["Campaign"])


# ─── Thread-safe campaign state ───────────────────────────────────────────

class CampaignState:
    def __init__(self) -> None:
        self.running: bool = False
        self.orchestrator: Optional[CallOrchestrator] = None
        self.thread: Optional[threading.Thread] = None
        self.results: list[LeadResult] = []
        self.start_time: Optional[float] = None


_campaign_lock = threading.Lock()
_campaign = CampaignState()


def _set_running(val: bool) -> None:
    with _campaign_lock:
        _campaign.running = val


# ─── Schemas ──────────────────────────────────────────────────────────────

class StartCampaignResponse(BaseModel):
    success: bool
    message: str
    total_leads: int = 0


class CampaignStatusResponse(BaseModel):
    running: bool
    total_leads: int = 0
    completed: int = 0
    yes_count: int = 0
    no_count: int = 0
    unknown_count: int = 0
    failed: int = 0
    elapsed_seconds: float = 0.0


# ─── Background runner ───────────────────────────────────────────────────

def _run_campaign(file_path: Optional[str]) -> None:
    logger.info("Campaign thread: initializing")
    try:
        orch = CallOrchestrator()
        orch.initialize()

        with _campaign_lock:
            _campaign.orchestrator = orch
            _campaign.start_time = time.monotonic()

        logger.info("Campaign thread: starting sequential calls")
        results = orch.run(file_path=file_path)

        with _campaign_lock:
            _campaign.results = results
            _campaign.running = False

        logger.info(f"Campaign complete: {len(results)} leads processed")
    except Exception as exc:
        logger.critical(f"Campaign failed: {exc}")
        _set_running(False)


# ─── Endpoints ────────────────────────────────────────────────────────────

@router.post("/start", response_model=StartCampaignResponse, summary="Start a campaign")
async def start_campaign():
    with _campaign_lock:
        if _campaign.running:
            raise HTTPException(status_code=409, detail="Campaign already running")

    try:
        from leads.importer import import_leads
        result = import_leads(settings.leads_file_path)
        total = len(result.leads)
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"Failed to load leads: {exc}")

    if total == 0:
        raise HTTPException(status_code=400, detail="No valid leads found in Excel")

    fresh = CampaignState()
    fresh.running = True

    with _campaign_lock:
        _campaign.running = fresh.running  # atomic bool set

    thread = threading.Thread(
        target=_run_campaign,
        args=(settings.leads_file_path,),
        daemon=True,
    )
    thread.start()

    with _campaign_lock:
        _campaign.thread = thread

    logger.info(f"Campaign started: {total} leads")
    return StartCampaignResponse(
        success=True,
        message=f"Campaign started with {total} leads",
        total_leads=total,
    )


@router.post("/stop", summary="Request graceful campaign stop")
async def stop_campaign():
    with _campaign_lock:
        if not _campaign.running:
            return {"success": True, "message": "No active campaign"}
        orch = _campaign.orchestrator
        _campaign.running = False

    if orch:
        orch.request_stop()

    logger.info("Campaign stop requested")
    return {"success": True, "message": "Stop requested — will stop after current call"}


@router.get("/status", response_model=CampaignStatusResponse, summary="Get campaign status")
async def get_status():
    with _campaign_lock:
        running = _campaign.running
        results = list(_campaign.results)
        start_time = _campaign.start_time

    completed = len(results)
    yes_count = sum(1 for r in results if r.intent.value == "YES")
    no_count = sum(1 for r in results if r.intent.value == "NO")
    unknown_count = sum(1 for r in results if r.intent.value == "UNKNOWN")
    failed = sum(1 for r in results if r.stage.value == "failed")
    elapsed = (time.monotonic() - start_time) if start_time else 0.0

    return CampaignStatusResponse(
        running=running,
        total_leads=completed,
        completed=completed,
        yes_count=yes_count,
        no_count=no_count,
        unknown_count=unknown_count,
        failed=failed,
        elapsed_seconds=round(elapsed, 1),
    )
