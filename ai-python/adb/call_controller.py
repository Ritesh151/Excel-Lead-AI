"""
adb/call_controller.py
High-level SIM call orchestrator.

Responsibilities:
  - Dial a number via AdbManager
  - Poll call state until a terminal state is reached
  - Detect CALLING → RINGING → CONNECTED → ENDED transitions
  - Enforce per-phase timeouts
  - Hang up the call cleanly
  - Retry on transient failures
  - Return a typed CallResult for every attempt

Public API:
    CallController.call(phone)            → CallResult
    CallController.call_sequential(leads) → list[CallResult]

This module does NOT play audio, record, or save to MongoDB.
"""

from __future__ import annotations

import time
from typing import Optional

from logger import logger
from adb.models import (
    CallConfig,
    CallOutcome,
    CallResult,
    CallState,
    RetryConfig,
)
from adb.adb_manager import AdbManager
from adb.device_checker import assert_device_ready
from adb.exceptions import (
    AdbBaseError,
    CallTimeoutError,
    CallRejectedError,
    CallFailedError,
    InvalidPhoneNumberError,
    DeviceOfflineError,
)


# ─── Telephony state mapping ──────────────────────────────────────────────────

# Raw integer from dumpsys → internal enum
# 0 = IDLE  /  1 = RINGING (on receiver side)  /  2 = OFFHOOK
_INT_TO_RAW = {0: "IDLE", 1: "RINGING", 2: "OFFHOOK", -1: "UNKNOWN"}


# ─── CallController ───────────────────────────────────────────────────────────

class CallController:
    """
    Orchestrates a single SIM call through its full lifecycle.

    Args:
        manager: AdbManager bound to the target device.
        config:  Tunable timeouts and retry settings.
    """

    def __init__(
        self,
        manager: AdbManager,
        config: Optional[CallConfig] = None,
    ) -> None:
        self._mgr = manager
        self._cfg = config or CallConfig()
        self._stop_requested = False   # for graceful sequential stop

    # ── Public: single call ───────────────────────────────────────────────────

    def call(self, phone: str) -> CallResult:
        """
        Place a call to `phone` with automatic retry on transient failures.

        The retry loop respects RetryConfig.max_attempts.
        Non-retryable outcomes (INVALID_NUMBER, REJECTED) are returned
        immediately without further attempts.

        Args:
            phone: E.164 number, e.g. "+919427047705"

        Returns:
            CallResult with outcome, final state, and duration.
        """
        cfg = self._cfg.retry
        last_result: Optional[CallResult] = None

        for attempt in range(1, cfg.max_attempts + 1):
            logger.info(
                f"[{phone}] Call attempt {attempt}/{cfg.max_attempts}"
            )

            result = self._attempt_call(phone, attempt)
            last_result = result

            if result.outcome not in cfg.retryable_outcomes:
                # Terminal outcome — no point retrying
                break

            if attempt < cfg.max_attempts:
                logger.info(
                    f"[{phone}] Retrying in {cfg.delay_between_attempts}s "
                    f"(outcome={result.outcome.value})"
                )
                time.sleep(cfg.delay_between_attempts)

        assert last_result is not None
        logger.info(
            f"[{phone}] Final outcome: {last_result.outcome.value} "
            f"(state={last_result.final_state.value}, "
            f"duration={last_result.duration_seconds:.1f}s)"
        )
        return last_result

    # ── Public: sequential batch ──────────────────────────────────────────────

    def call_sequential(self, leads: list[dict]) -> list[CallResult]:
        """
        Call each lead in `leads` one after another.

        Args:
            leads: List of dicts with at least a "phone" key.
                   (Output format from leads.importer.load_leads)

        Returns:
            List of CallResult in the same order as `leads`.
        """
        results: list[CallResult] = []
        total = len(leads)

        for idx, lead in enumerate(leads, start=1):
            if self._stop_requested:
                logger.info("Stop requested — halting sequential calling")
                break

            phone = lead.get("phone", "")
            name = lead.get("name", "unknown")

            logger.info(
                f"─── Lead {idx}/{total}: {name} ({phone}) ───"
            )

            # Quick device sanity check before each call
            try:
                assert_device_ready(self._mgr)
            except DeviceOfflineError as exc:
                logger.error(f"Device offline before calling {phone}: {exc}")
                results.append(CallResult(
                    phone=phone,
                    outcome=CallOutcome.DEVICE_ERROR,
                    final_state=CallState.FAILED,
                    error_message=str(exc),
                ))
                break  # No point continuing if device is gone

            result = self.call(phone)
            results.append(result)

            # Pause between calls (skip after the last one)
            if idx < total and not self._stop_requested:
                logger.debug(
                    f"Waiting {self._cfg.between_calls_delay}s before next call"
                )
                time.sleep(self._cfg.between_calls_delay)

        logger.info(
            f"Sequential calling complete — "
            f"{len(results)}/{total} leads processed"
        )
        return results

    def request_stop(self) -> None:
        """Ask call_sequential() to stop after the current call finishes."""
        self._stop_requested = True
        logger.info("Stop requested for sequential calling loop")

    # ── Private: single attempt ───────────────────────────────────────────────

    def _attempt_call(self, phone: str, attempt_number: int) -> CallResult:
        """
        Execute one call attempt without retry logic.

        State machine:
            PRE_DIAL → CALLING → RINGING → CONNECTED → ENDED

        Returns:
            CallResult for this attempt.
        """
        start_time = time.monotonic()

        # ── Wake screen so the dialler works on locked devices ───────────────
        try:
            if not self._mgr.is_screen_on():
                self._mgr.wake_screen()
                self._mgr.dismiss_keyguard()
                time.sleep(0.5)
        except AdbBaseError as exc:
            logger.warning(f"Screen wake failed (continuing anyway): {exc}")

        # ── Dial ─────────────────────────────────────────────────────────────
        try:
            self._mgr.dial(phone)
        except ValueError as exc:
            return CallResult(
                phone=phone,
                outcome=CallOutcome.INVALID_NUMBER,
                final_state=CallState.FAILED,
                attempt_number=attempt_number,
                error_message=str(exc),
            )
        except AdbBaseError as exc:
            logger.error(f"[{phone}] Dial command failed: {exc}")
            return CallResult(
                phone=phone,
                outcome=CallOutcome.FAILED,
                final_state=CallState.FAILED,
                attempt_number=attempt_number,
                error_message=str(exc),
            )

        # ── Wait for device to go OFFHOOK (dialler is active) ────────────────
        logger.info(f"[{phone}] Dialled — waiting for OFFHOOK")
        try:
            self._wait_for_offhook(phone)
        except CallTimeoutError as exc:
            logger.error(f"[{phone}] Never went OFFHOOK: {exc}")
            self._safe_hangup(phone)
            return CallResult(
                phone=phone,
                outcome=CallOutcome.TIMEOUT,
                final_state=CallState.FAILED,
                duration_seconds=time.monotonic() - start_time,
                attempt_number=attempt_number,
                error_message=str(exc),
            )
        except AdbBaseError as exc:
            logger.error(f"[{phone}] ADB error waiting for OFFHOOK: {exc}")
            return CallResult(
                phone=phone,
                outcome=CallOutcome.DEVICE_ERROR,
                final_state=CallState.FAILED,
                duration_seconds=time.monotonic() - start_time,
                attempt_number=attempt_number,
                error_message=str(exc),
            )

        logger.info(f"[{phone}] State: CALLING")

        # ── Poll state through the call lifecycle ─────────────────────────────
        final_state, outcome, error_msg = self._monitor_call(phone)
        duration = time.monotonic() - start_time

        # ── Always ensure the call is terminated ─────────────────────────────
        self._safe_hangup(phone)

        return CallResult(
            phone=phone,
            outcome=outcome,
            final_state=final_state,
            duration_seconds=duration,
            attempt_number=attempt_number,
            error_message=error_msg,
        )

    # ── State polling ─────────────────────────────────────────────────────────

    def _get_call_state(self) -> CallState:
        """
        Map the raw telephony integer to our CallState enum.
        Returns CallState.UNKNOWN on parse failure.
        """
        raw = self._mgr.get_call_state_int()
        if raw == 0:
            return CallState.IDLE
        if raw == 1:
            return CallState.RINGING
        if raw == 2:
            # OFFHOOK covers both outgoing ring and connected;
            # the caller tracks transitions to distinguish them
            return CallState.CALLING
        return CallState.UNKNOWN

    def _wait_for_offhook(self, phone: str) -> None:
        """
        Poll until the call state leaves IDLE (device went OFFHOOK).
        This confirms the dialler launched and the call is being placed.

        Raises:
            CallTimeoutError if OFFHOOK not detected within dial_timeout.
        """
        deadline = time.monotonic() + self._cfg.dial_timeout
        while time.monotonic() < deadline:
            state = self._get_call_state()
            if state != CallState.IDLE and state != CallState.UNKNOWN:
                return
            time.sleep(self._cfg.poll_interval)

        raise CallTimeoutError(
            f"[{phone}] Device did not go OFFHOOK within {self._cfg.dial_timeout}s"
        )

    def _monitor_call(
        self, phone: str
    ) -> tuple[CallState, CallOutcome, Optional[str]]:
        """
        Main state machine loop — tracks the call from CALLING to ENDED.

        Transition map:
            CALLING  → IDLE   : call ended before answer (not answered / rejected)
            CALLING  → RINGING: remote is ringing (on some devices)
            RINGING  → CALLING: back to OFFHOOK after ringing = CONNECTED
            RINGING  → IDLE   : rejected / missed
            CALLING  → stays  : still ringing on outgoing side
            After ring_timeout: treat as NOT_ANSWERED

        Returns:
            (final_state, outcome, error_message)
        """
        phase = "CALLING"          # semantic phase name for logging
        call_start = time.monotonic()
        connected_at: Optional[float] = None
        prev_raw = self._mgr.get_call_state_int()

        # Phase deadlines
        ring_deadline = call_start + self._cfg.ring_timeout
        connected_deadline: Optional[float] = None

        while True:
            elapsed = time.monotonic() - call_start
            raw = self._mgr.get_call_state_int()
            state = self._get_call_state()

            # ── Terminal: back to IDLE ────────────────────────────────────────
            if raw == 0:  # IDLE
                if connected_at is not None:
                    # Was connected, now ended normally
                    logger.info(f"[{phone}] Call ENDED (was connected)")
                    return CallState.ENDED, CallOutcome.CONNECTED, None
                elif phase == "RINGING":
                    logger.info(f"[{phone}] Call REJECTED / not answered")
                    return CallState.ENDED, CallOutcome.REJECTED, None
                else:
                    logger.info(f"[{phone}] Call ended before answer")
                    return CallState.ENDED, CallOutcome.NOT_ANSWERED, None

            # ── OFFHOOK (2) — covers CALLING and CONNECTED ───────────────────
            if raw == 2:
                if phase == "RINGING":
                    # Transition: RINGING → OFFHOOK means answered
                    if connected_at is None:
                        connected_at = time.monotonic()
                        connected_deadline = connected_at + self._cfg.connected_timeout
                        phase = "CONNECTED"
                        logger.info(f"[{phone}] State: CONNECTED")

                elif phase == "CONNECTED":
                    # Still connected — check connected timeout
                    if connected_deadline and time.monotonic() > connected_deadline:
                        logger.warning(
                            f"[{phone}] Connected timeout after "
                            f"{self._cfg.connected_timeout}s — hanging up"
                        )
                        return (
                            CallState.CONNECTED,
                            CallOutcome.TIMEOUT,
                            f"Connected timeout {self._cfg.connected_timeout}s exceeded",
                        )

                # Still in CALLING phase (ringing on our end)

            # ── RINGING (1) — remote side ringing ────────────────────────────
            if raw == 1 and phase == "CALLING":
                phase = "RINGING"
                logger.info(f"[{phone}] State: RINGING")

            # ── Ring timeout ──────────────────────────────────────────────────
            if phase in ("CALLING", "RINGING") and time.monotonic() > ring_deadline:
                logger.warning(
                    f"[{phone}] Ring timeout after {self._cfg.ring_timeout}s"
                )
                return (
                    CallState.RINGING,
                    CallOutcome.NOT_ANSWERED,
                    f"Ring timeout {self._cfg.ring_timeout}s exceeded",
                )

            prev_raw = raw
            time.sleep(self._cfg.poll_interval)

    # ── Hangup helpers ────────────────────────────────────────────────────────

    def _safe_hangup(self, phone: str) -> None:
        """
        Try to hang up the call.  Never raises — errors are logged only.
        Tries KEYCODE_ENDCALL first, then telecom service as fallback.
        """
        time.sleep(self._cfg.hangup_delay)
        try:
            self._mgr.hangup()
        except AdbBaseError as exc:
            logger.warning(
                f"[{phone}] ENDCALL keyevent failed ({exc}), "
                "trying telecom service hangup"
            )
            try:
                self._mgr.hangup_via_telecom()
            except AdbBaseError as exc2:
                logger.error(f"[{phone}] telecom hangup also failed: {exc2}")
        logger.debug(f"[{phone}] Hangup complete")


# ─── Factory helper ───────────────────────────────────────────────────────────

def make_controller(
    serial: Optional[str] = None,
    config: Optional[CallConfig] = None,
) -> CallController:
    """
    Convenience factory: create a CallController for the given device serial.

    Args:
        serial: Device serial (None → auto-detect).
        config: Custom CallConfig (None → defaults).

    Returns:
        Ready CallController.
    """
    mgr = AdbManager(serial=serial)
    return CallController(manager=mgr, config=config)
