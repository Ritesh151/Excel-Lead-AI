"""
main.py
AI Calling Engine — FastAPI server (v3, ADB+Android+Kotlin flow).

Endpoints registered:
  GET  /health                — readiness probe
  POST /api/call/execute      — ADB dial + wait for Android automation
  POST /api/call/transcribe   — transcribe recording file (path)
  GET  /api/call/status       — Whisper + ADB + MongoDB health
  POST /api/calls/recording   — Android Kotlin app WAV upload (ApiClient.kt)
  POST /api/transcribe        — legacy alias for /api/call/transcribe
  POST /api/transcribe/upload — legacy file-upload endpoint
  POST /api/campaign/start    — stub (410 Gone)
  POST /api/campaign/stop     — stub (410 Gone)
  GET  /api/campaign/status   — stub

Server startup:
    python main.py server                  # default port 8000
    python main.py server --port 9000      # custom port
    python main.py server --reload         # dev hot-reload

CLI calls (for testing/scripting):
    python main.py intent "haan bilkul"
    python main.py call --phone +919427047705 --name "Test User"
    python main.py run --file ../leads/leads.xlsx
"""

from __future__ import annotations

import argparse
import signal
import sys
from contextlib import asynccontextmanager
from typing import Optional

import uvicorn
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from config import settings
from logger import logger
from db.mongodb_client import get_client, close_client
from db.utils import ensure_indexes, ensure_schema_validation


# ─── Lifespan ──────────────────────────────────────────────────────────────────

@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info("=" * 60)
    logger.info("AI CALLING ENGINE v3 — ADB+Android+Kotlin")
    logger.info("=" * 60)

    # MongoDB
    try:
        get_client().connect()
        ensure_indexes()
        ensure_schema_validation()
        logger.info("MongoDB connected and indexes ensured")
    except Exception as exc:
        logger.warning(f"MongoDB unavailable at startup (non-fatal): {exc}")

    # Pre-warm Whisper
    try:
        from routers.call import _get_transcription_service
        _get_transcription_service()
        logger.info("Whisper model pre-warmed")
    except Exception as exc:
        logger.warning(f"Whisper pre-warm skipped (will load on first request): {exc}")

    # Log all registered endpoints
    logger.info("Registered endpoints:")
    for route in app.routes:
        if hasattr(route, "methods"):
            for method in route.methods:
                logger.info(f"  {method:6} {route.path}")

    yield  # ── application runs ─────────────────────────────────────────────

    logger.info("AI Engine shutting down…")
    close_client()
    logger.info("AI Engine stopped")


# ─── App ───────────────────────────────────────────────────────────────────────

app = FastAPI(
    title="AI Calling Engine",
    description=(
        "ADB-based outbound GSM calling + Android in-call audio routing + "
        "Faster-Whisper transcription + YES/NO intent detection."
    ),
    version="3.0.0",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# ─── Mount routers ─────────────────────────────────────────────────────────────

from routers import health_router, call_router, campaign_router, android_router
from routers import transcription as transcription_router

app.include_router(health_router)           # GET /health
app.include_router(call_router)             # /api/call/*
app.include_router(android_router)          # POST /api/calls/recording  ← Android app
app.include_router(campaign_router)         # /api/campaign/* (stubs)
app.include_router(transcription_router.router)  # /api/transcribe (legacy)


# ─── CLI ───────────────────────────────────────────────────────────────────────

def _signal_handler(signum: int, _frame) -> None:
    logger.warning(f"Signal {signum} received — shutting down")
    sys.exit(0)


def cmd_server(args: argparse.Namespace) -> None:
    host = args.host or settings.host
    port = args.port or settings.port
    logger.info(f"Starting AI Engine on {host}:{port}")
    uvicorn.run("main:app", host=host, port=port, reload=args.reload, log_level="info")


def cmd_intent(args: argparse.Namespace) -> None:
    from transcription.intent_detector import detect_intent
    result = detect_intent(args.text)
    print(f'  Input:   "{args.text}"')
    print(f'  Intent:  {result.intent.value}')
    print(f'  Conf:    {result.confidence:.2f}')
    print(f'  Match:   {result.matched_keyword!r}')


def cmd_call(args: argparse.Namespace) -> None:
    from workflow.orchestrator import CallOrchestrator
    orch = CallOrchestrator()
    orch.initialize()
    result = orch.execute_single_call(name=args.name or "Customer", phone=args.phone)
    intent_val = result.intent.value if hasattr(result.intent, "value") else str(result.intent)
    print(f"\n  Phone:   {args.phone}")
    print(f"  Success: {result.success}")
    print(f"  Intent:  {intent_val}")
    print(f"  Text:    {result.transcription[:80]!r}")
    if result.error_message:
        print(f"  Error:   {result.error_message}")
    orch.shutdown()


def cmd_run(args: argparse.Namespace) -> None:
    from workflow.orchestrator import CallOrchestrator
    orch = CallOrchestrator()
    orch.initialize()
    results = orch.run(file_path=args.file or settings.leads_file_path)
    yes = sum(1 for r in results if getattr(r.intent, "value", str(r.intent)) == "YES")
    no = sum(1 for r in results if getattr(r.intent, "value", str(r.intent)) == "NO")
    failed = sum(1 for r in results if getattr(r.stage, "value", str(r.stage)) == "failed")
    print(f"\n  Total: {len(results)}  YES: {yes}  NO: {no}  Failed: {failed}")
    orch.shutdown()


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="ai-engine",
        description="AI Calling Engine v3 — ADB+Android+Kotlin",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    p_srv = sub.add_parser("server", help="Start FastAPI server")
    p_srv.add_argument("--host", default=None)
    p_srv.add_argument("--port", type=int, default=None)
    p_srv.add_argument("--reload", action="store_true")

    p_intent = sub.add_parser("intent", help="Test intent detection")
    p_intent.add_argument("text")

    p_call = sub.add_parser("call", help="Single ADB call")
    p_call.add_argument("--phone", required=True)
    p_call.add_argument("--name", default="Customer")

    p_run = sub.add_parser("run", help="Campaign from Excel")
    p_run.add_argument("--file", default=None)

    return parser


def main() -> None:
    signal.signal(signal.SIGINT, _signal_handler)
    signal.signal(signal.SIGTERM, _signal_handler)
    args = build_parser().parse_args()
    dispatch = {"server": cmd_server, "intent": cmd_intent, "call": cmd_call, "run": cmd_run}
    dispatch[args.command](args)


if __name__ == "__main__":
    main()
