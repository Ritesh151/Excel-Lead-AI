"""
workers/call_worker.py
Background call execution worker.

Called by the FastAPI /api/call/execute endpoint.
Orchestrates: ADB dial -> play greeting -> record -> transcribe -> detect intent -> save.
"""

from __future__ import annotations

from typing import Optional

from logger import logger
from workflow.orchestrator import CallOrchestrator
from workflow.states import CallAttemptResult


_orchestrator: Optional[CallOrchestrator] = None


def get_orchestrator() -> CallOrchestrator:
    global _orchestrator
    if _orchestrator is None:
        _orchestrator = CallOrchestrator()
        _orchestrator.initialize()
    return _orchestrator


def execute_call(name: str, phone: str) -> CallAttemptResult:
    """
    Execute a single call against the given phone number.

    Args:
        name:  Caller name from the lead sheet.
        phone: Phone number in E.164 format (e.g., +919427047705).

    Returns:
        CallAttemptResult with success, intent, transcription, recording_file.
    """
    logger.info(f"Worker executing call: {name} ({phone})")
    orch = get_orchestrator()
    return orch.execute_single_call(name=name, phone=phone)
