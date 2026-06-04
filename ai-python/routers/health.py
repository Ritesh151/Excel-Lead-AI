"""
routers/health.py
Simple health-check endpoint for container / load-balancer probes.
"""

from datetime import datetime, timezone

from fastapi import APIRouter

router = APIRouter(tags=["Health"])


@router.get("/health", summary="Health check")
async def health_check():
    return {
        "status": "ok",
        "service": "ai-calling-engine",
        "timestamp": datetime.now(timezone.utc).isoformat(),
    }
