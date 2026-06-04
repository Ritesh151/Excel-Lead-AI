"""
main.py
AI Calling Engine — FastAPI server + CLI dual-mode entry point.

Server mode:
    python main.py server

CLI mode:
    python main.py run --file ../leads/leads.xlsx
    python main.py check
    python main.py intent "haan bilkul"
"""

from __future__ import annotations

import argparse
import json
import signal
import sys
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
import uvicorn

from config import settings
from logger import logger
from db.mongodb_client import get_client, close_client
from db.utils import ensure_indexes, ensure_schema_validation


# ─── FastAPI Lifespan ──────────────────────────────────────────────────────

@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info("AI Engine (FastAPI) starting up...")
    try:
        get_client().connect()
        ensure_indexes()
        ensure_schema_validation()
        logger.info("MongoDB connected and indexes ensured")
    except Exception as exc:
        logger.warning(f"MongoDB not available at startup: {exc}")

    yield

    logger.info("AI Engine shutting down...")
    close_client()


# ─── FastAPI App Factory ──────────────────────────────────────────────────

app = FastAPI(
    title="AI Calling Engine",
    description="Python FastAPI service — ADB calling, audio, Whisper transcription, intent detection",
    version="1.0.0",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

# ─── Mount Routers ────────────────────────────────────────────────────────

from routers import health_router, call_router, campaign_router, transcription
app.include_router(health_router)
app.include_router(call_router)
app.include_router(campaign_router)
app.include_router(transcription.router)


# ══════════════════════════════════════════════════════════════════════════
# CLI mode
# ══════════════════════════════════════════════════════════════════════════

_orchestrator_cli: Optional["CallOrchestrator"] = None


def _signal_handler(signum: int, _frame) -> None:
    logger.warning(f"Signal {signum} received, shutting down...")
    if _orchestrator_cli:
        _orchestrator_cli.request_stop()


def cmd_run(args: argparse.Namespace) -> None:
    from workflow.orchestrator import CallOrchestrator

    global _orchestrator_cli
    logger.info("=" * 60)
    logger.info("AI CALLING ENGINE — CLI RUN")
    logger.info("=" * 60)

    orch = CallOrchestrator()
    _orchestrator_cli = orch

    try:
        orch.initialize()
        results = orch.run(file_path=args.file)
        orch.shutdown()

        if args.json:
            data = [r.to_dict() for r in results]
            Path(args.json).write_text(json.dumps(data, indent=2, ensure_ascii=False))
            logger.info(f"Results written to {args.json}")
    except KeyboardInterrupt:
        logger.info("Interrupted by user")
        orch.shutdown()
        sys.exit(1)
    except Exception as exc:
        logger.critical(f"Fatal error: {exc}")
        orch.shutdown()
        sys.exit(1)


def cmd_check(args: argparse.Namespace) -> None:
    from adb.device_checker import get_device, assert_device_ready
    from adb.adb_manager import AdbManager

    serial = args.serial or settings.adb_device_serial or None
    device = get_device(serial)
    print(f"  Serial:   {device.serial}")
    print(f"  Model:    {device.model}")
    print(f"  State:    {device.state.value}")
    print(f"  Android:  {device.android_version}")
    print(f"  Ready:    {device.is_ready}")

    if device.is_ready:
        mgr = AdbManager(serial=device.serial)
        assert_device_ready(mgr)
        print("  ADB:      OK")
    else:
        print("  ADB:      NOT READY")
        sys.exit(1)

    print("Device check passed")


def cmd_intent(args: argparse.Namespace) -> None:
    from transcription.intent_detector import detect_intent

    result = detect_intent(args.text)
    print(f'  Input:   "{args.text}"')
    print(f'  Intent:  {result.intent.value}')
    print(f'  Conf:    {result.confidence:.2f}')
    print(f'  Match:   {result.matched_keyword!r}')


def cmd_server(args: argparse.Namespace) -> None:
    host = args.host or settings.host
    port = args.port or settings.port
    logger.info(f"Starting FastAPI server on {host}:{port}")
    uvicorn.run(
        "main:app",
        host=host,
        port=port,
        reload=args.reload,
        log_level="info",
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="AI Calling Engine — FastAPI server + CLI",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Start FastAPI server
  python main.py server

  # Start server on custom port
  python main.py server --port 9000 --reload

  # Run full workflow from CLI
  python main.py run --file ../leads/leads.xlsx

  # Run and save results as JSON
  python main.py run --json results.json

  # Check ADB device
  python main.py check

  # Test intent detection
  python main.py intent "haan bilkul"
        """,
    )
    sub = parser.add_subparsers(dest="command", required=True)

    p_srv = sub.add_parser("server", help="Start FastAPI server")
    p_srv.add_argument("--host", default=None, help="Bind address")
    p_srv.add_argument("--port", type=int, default=None, help="Bind port")
    p_srv.add_argument("--reload", action="store_true", help="Auto-reload on code changes")

    p_run = sub.add_parser("run", help="Run the full call workflow")
    p_run.add_argument("--file", "-f", default=None, help="Path to leads Excel file")
    p_run.add_argument("--json", "-j", default=None, help="Output results as JSON file")

    p_check = sub.add_parser("check", help="Check ADB device connectivity")
    p_check.add_argument("--serial", "-s", default=None, help="Device serial")

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
    elif args.command == "run":
        cmd_run(args)
    elif args.command == "check":
        cmd_check(args)
    elif args.command == "intent":
        cmd_intent(args)


if __name__ == "__main__":
    main()
