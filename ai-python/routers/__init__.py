"""
routers package — AI Engine v3 (ADB+Android+Kotlin flow)

Active routers:
  health_router       — GET  /health
  call_router         — POST /api/call/execute     (ADB dial + Android automation)
                        POST /api/call/transcribe  (Whisper transcription by path)
                        GET  /api/call/status      (engine health + ADB status)
  android_router      — POST /api/calls/recording  (Android Kotlin app WAV upload)
  transcription_router— POST /api/transcribe       (legacy alias, kept for compatibility)
  campaign_router     — /api/campaign/*            (stubs — disabled, backend-node owns campaigns)
"""

from .health import router as health_router
from .call import router as call_router
from .campaign import router as campaign_router
from .android_upload import router as android_router

__all__ = ["health_router", "call_router", "campaign_router", "android_router"]
