"""
routers package — Exotel flow (v2)

Active routers:
  health_router     — GET  /health
  call_router       — POST /api/call/transcribe  (Whisper transcription only)
  transcription     — POST /api/transcribe        (legacy alias, still supported)
  campaign_router   — /api/campaign/*             (stubs — disabled, backend-node owns campaigns)

ADB / CallOrchestrator routers have been removed from this package.
"""

from .health import router as health_router
from .call import router as call_router
from .campaign import router as campaign_router

__all__ = ["health_router", "call_router", "campaign_router"]
