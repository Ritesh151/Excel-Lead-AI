"""
main.py
AI Calling Engine — FastAPI server entry point.

ARCHITECTURE (v2 — Exotel flow):
  This service is a TRANSCRIPTION-ONLY backend.
  It does NOT dial calls, play audio, use ADB, or control any Android device.

  Responsibilities:
    1. Accept POST /api/call/transcribe from backend-node
    2. Run Faster-Whisper on the recording
    3. Detect YES / NO intent
    4. Save result to MongoDB
    5. Return JSON to backend-node

  The full call lifecycle is:
    backend-node  → Exotel API   (outbound call)
    Exotel        → customer     (rings + plays greeting_telephony.wav)
    customer      → Exotel       (records response)
    Exotel        → backend-node (webhook with recording URL)
    backend-node  → ai-python    (POST /api/call/transcribe)
    ai-python     → MongoDB      (save result)

Server startup:
    python main.py server
    python main.py server --port 9000 --reload

Intent test:
    python main.py intent "haan bilkul"
"""

from __future__ import annotations

import argparse
import signal
import sys
from contextlib import asynccontextmanager
from typing import Optional

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
import uvicorn

from config import settings
from logger import logger
from db.mongodb_client import get_client, close_client
from db.utils import ensure_indexes, ensure_schema_validation


# ─── FastAPI Lifespan ──────────────────────────────────────────────────────────

@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info("=" * 60)
    logger.info("AI ENGINE (Exotel flow) — STARTING")
    logger.info("Mode: transcription-only (ADB disabled)")
    logger.info("=" * 60)

    # MongoDB
    try:
        get_client().connect()
        ensure_indexes()
        ensure_schema_validation()
        logger.info("MongoDB connected and indexes ensured")
    except Exception as exc:
        logger.warning(f"MongoDB not available at startup (non-fatal): {exc}")

    # Pre-warm Whisper model so first call is fast
    try:
        from routers.call import _get_transcription_service
        _get_transcription_service()
        logger.info("Whisper model pre-warmed successfully")
    except Exception as exc:
        logger.warning(f"Whisper pre-warm skipped (will load on first request): {exc}")

    logger.info("AI Engine ready — listening for transcription requests")

    yield  # ── application runs ─────────────────────────────────────────────

    logger.info("AI Engine shutting down...")
    close_client()
    logger.info("AI Engine stopped")


# ─── FastAPI App ──────────────────────────────────────────────────────────────

app = FastAPI(
    title="AI Calling Engine — Transcription Service",
    description=(
        "Faster-Whisper transcription and YES/NO intent detection. "
        "Part of the Exotel-based outbound calling pipeline."
    ),
    version="2.0.0",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

# ─── Mount Routers ────────────────────────────────────────────────────────────

from routers import health_router, call_router, campaign_router
from routers import transcription as transcription_router

app.include_router(health_router)
app.include_router(call_router)
app.include_router(campaign_router)
app.include_router(transcription_router.router)


# ─── CLI ──────────────────────────────────────────────────────────────────────

def _signal_handler(signum: int, _frame) -> None:
    logger.warning(f"Signal {signum} received — shutting down")
    sys.exit(0)


def cmd_server(args: argparse.Namespace) -> None:
    host = args.host or settings.host
    port = args.port or settings.port
    logger.info(f"Starting AI Engine server on {host}:{port}")
    uvicorn.run(
        "main:app",
        host=host,
        port=port,
        reload=args.reload,
        log_level="info",
    )


def cmd_intent(args: argparse.Namespace) -> None:
    from transcription.intent_detector import detect_intent
    result = detect_intent(args.text)
    print(f'  Input:   "{args.text}"')
    print(f'  Intent:  {result.intent.value}')
    print(f'  Conf:    {result.confidence:.2f}')
    print(f'  Match:   {result.matched_keyword!r}')


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="AI Calling Engine — FastAPI transcription server",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Start FastAPI server (default port 8000)
  python main.py server

  # Start on custom port with auto-reload
  python main.py server --port 9000 --reload

  # Test intent detection
  python main.py intent "haan bilkul"
  python main.py intent "nahi chahiye"
        """,
    )
    sub = parser.add_subparsers(dest="command", required=True)

    p_srv = sub.add_parser("server", help="Start FastAPI transcription server")
    p_srv.add_argument("--host", default=None, help="Bind address (default: 0.0.0.0)")
    p_srv.add_argument("--port", type=int, default=None, help="Bind port (default: 8000)")
    p_srv.add_argument("--reload", action="store_true", help="Auto-reload on code changes")

    p_intent = sub.add_parser("intent", help="Test intent detection on text")
    p_intent.add_argument("text", help='Text to classify, e.g. "haan bilkul"')

    return parser


def main() -> None:
    signal.signal(signal.SIGINT, _signal_handler)
    signal.signal(signal.SIGTERM, _signal_handler)

    parser = build_parser()
    args = parser.parse_args()

    if args.command == "server":
        cmd_server(args)
    elif args.command == "intent":
        cmd_intent(args)


if __name__ == "__main__":
    main()
