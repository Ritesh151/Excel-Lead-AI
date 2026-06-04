"""
workers/call_worker.py
Background call execution worker.

Called by the FastAPI /api/call/execute endpoint.
Orchestrates: ADB dial → Android plays greeting → records → transcribes → intent → MongoDB.

FIXED:
  - Orchestrator is re-initialized if device disconnects between calls
  - Device offline errors trigger a reconnect attempt before returning failure
  - Detailed logging at every step for production debugging
"""

from __future__ import annotations

import threading
from typing import Optional

from logger import logger
from workflow.orchestrator import CallOrchestrator
from workflow.states import CallAttemptResult, CallStage

# Global orchestrator singleton — protected by lock
_orchestrator: Optional[CallOrchestrator] = None
_orchestrator_lock = threading.Lock()


def _build_orchestrator() -> CallOrchestrator:
    """Create and fully initialize a fresh CallOrchestrator."""
    orch = CallOrchestrator()
    orch.initialize()
    return orch


def get_orchestrator() -> CallOrchestrator:
    """
    Return the singleton orchestrator, creating it if not yet initialized.
    Thread-safe.
    """
    global _orchestrator
    with _orchestrator_lock:
        if _orchestrator is None:
            logger.info("[CallWorker] First call — initializing orchestrator")
            _orchestrator = _build_orchestrator()
    return _orchestrator


def reset_orchestrator() -> None:
    """
    Destroy and rebuild the orchestrator.
    Called when the ADB device disconnects or the orchestrator enters an error state.
    """
    global _orchestrator
    with _orchestrator_lock:
        if _orchestrator is not None:
            logger.warning("[CallWorker] Resetting orchestrator (device may have disconnected)")
            try:
                _orchestrator.shutdown()
            except Exception as exc:
                logger.debug(f"[CallWorker] Shutdown error (non-fatal): {exc}")
        _orchestrator = None
        logger.info("[CallWorker] Orchestrator reset — will reinitialize on next call")


def execute_call(name: str, phone: str) -> CallAttemptResult:
    """
    Execute a single call for the given lead.

    Args:
        name:  Caller name from the lead sheet.
        phone: Phone number in E.164 format (+919427047705).

    Returns:
        CallAttemptResult with success, intent, transcription, recording_file.

    Strategy:
      1. Try with existing orchestrator
      2. If DeviceOfflineError or connection error → reset + retry once
      3. Return failure if retry also fails
    """
    logger.info(f"[CallWorker] execute_call: name={name!r} phone={phone!r}")

    # ── First attempt ─────────────────────────────────────────────────────────
    try:
        orch = get_orchestrator()
        logger.info(f"[CallWorker] Orchestrator ready — executing call to {phone}")
        result = orch.execute_single_call(name=name, phone=phone)
        logger.info(
            f"[CallWorker] Call result: phone={phone} "
            f"success={result.success} "
            f"intent={result.intent.value if hasattr(result.intent, 'value') else result.intent} "
            f"stage={result.stage.value if hasattr(result.stage, 'value') else result.stage}"
        )
        return result

    except Exception as exc:
        error_str = str(exc)
        logger.error(f"[CallWorker] First attempt failed: {error_str}", exc_info=True)

        # Check if this looks like a device/ADB connectivity issue
        is_device_error = any(keyword in error_str.lower() for keyword in [
            "device", "offline", "adb", "not found", "unauthorized",
            "connection refused", "timeout", "transport"
        ])

        if not is_device_error:
            # Not a device error — return failure immediately, no retry
            return CallAttemptResult(
                success=False,
                stage=CallStage.FAILED,
                error_message=f"Call execution error: {error_str}",
                should_retry=False,
            )

    # ── Device error detected — reset and retry once ──────────────────────────
    logger.warning(f"[CallWorker] Device error detected for {phone} — resetting orchestrator and retrying")
    reset_orchestrator()

    try:
        orch = get_orchestrator()
        logger.info(f"[CallWorker] Orchestrator rebuilt — retrying call to {phone}")
        result = orch.execute_single_call(name=name, phone=phone)
        logger.info(
            f"[CallWorker] Retry result: phone={phone} "
            f"success={result.success} "
            f"intent={result.intent.value if hasattr(result.intent, 'value') else result.intent}"
        )
        return result

    except Exception as exc2:
        error_str2 = str(exc2)
        logger.error(f"[CallWorker] Retry also failed: {error_str2}", exc_info=True)
        # Reset again so next call gets a clean start
        reset_orchestrator()
        return CallAttemptResult(
            success=False,
            stage=CallStage.FAILED,
            error_message=f"Device error (retry failed): {error_str2}",
            should_retry=False,
        )
