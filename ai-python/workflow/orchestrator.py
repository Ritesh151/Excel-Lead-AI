"""
workflow/orchestrator.py
ADB+Android GSM AI Calling Orchestrator.

ARCHITECTURE (v3 — ADB+Android+Kotlin flow):
  This orchestrator uses:
    1. ADB to dial customer via SIM card
    2. Android Kotlin app (CallAutomationService) handles:
       - Call state detection (OFFHOOK → CONNECTED)
       - Audio routing (PcmStreamingEngine + SamsungWorkarounds)
       - greeting.wav playback via all routing strategies
       - RecordingManager captures customer response (18 seconds)
       - Uploads WAV to this FastAPI endpoint
    3. This Python engine:
       - Monitors ADB for call state (parallel to Android app)
       - Receives uploaded WAV from Android
       - Transcribes with Faster-Whisper
       - Detects YES/NO intent
       - Updates MongoDB

Flow:
    1. Load leads from Excel
    2. For each lead:
       a. Dial via ADB (am start -a android.intent.action.CALL)
       b. Wait for CONNECTED state (polling dumpsys telephony.registry)
       c. Android app AUTOMATICALLY:
          - Detects CONNECTED via TelephonyController
          - Sets MODE_IN_COMMUNICATION
          - Plays greeting.wav via PcmStreamingEngine (5 strategies)
          - Records 18s customer response
          - Uploads WAV to /api/call/transcribe
       d. Transcription result saved to MongoDB
       e. Hang up after recording done
       f. Move to next lead
"""

from __future__ import annotations

import time
from pathlib import Path
from typing import Optional

from config import settings
from logger import logger

from leads.importer import import_leads, ImportResult
from leads.models import LeadRecord

from adb.device_checker import get_device, assert_device_ready
from adb.adb_manager import AdbManager
from adb.call_controller import CallController, CallConfig, RetryConfig
from adb.models import CallOutcome, DeviceInfo

from db.mongodb_client import get_client
from db.models import CallRecord, CallStatus
from db.call_repository import CallRepository
from db.utils import ensure_indexes, ensure_schema_validation

from workflow.states import CallStage, Intent, LeadResult, CallAttemptResult


class CallOrchestrator:
    """
    ADB+Android GSM call orchestrator.

    Responsibilities:
      - ADB call dialing
      - Call state monitoring
      - Trigger Android app automation via ADB
      - Wait for transcription result (uploaded by Android app)
      - MongoDB persistence

    NOTE: Audio playback and recording are handled by the Android
    Kotlin app (CallAutomationService). This orchestrator ONLY
    handles ADB dialing and MongoDB persistence.
    """

    def __init__(self) -> None:
        self._device: Optional[DeviceInfo] = None
        self._adb_manager: Optional[AdbManager] = None
        self._call_controller: Optional[CallController] = None
        self._repo: Optional[CallRepository] = None
        self._leads: list[LeadRecord] = []
        self._results: list[LeadResult] = []
        self._start_time: float = 0.0
        self._should_stop: bool = False

    # ── Initialization ─────────────────────────────────────────────────────

    def _init_adb(self) -> None:
        logger.info("Initializing ADB device connection")
        serial = settings.adb_device_serial or None
        self._device = get_device(serial)
        logger.info(f"Device: {self._device.serial} model={self._device.model} android={self._device.android_version}")

        self._adb_manager = AdbManager(serial=self._device.serial)
        assert_device_ready(self._adb_manager)

        retry_cfg = RetryConfig(
            max_attempts=settings.call_flow_retry_attempts,
            delay_between_attempts=settings.call_flow_retry_delay,
        )
        call_cfg = CallConfig(
            dial_timeout=30.0,
            ring_timeout=settings.call_flow_connect_timeout,
            connected_timeout=settings.call_flow_max_call_duration,
            retry=retry_cfg,
        )
        self._call_controller = CallController(
            manager=self._adb_manager,
            config=call_cfg,
        )
        logger.info("Call controller ready")
        try:
            from integration.backend_events import emit_device_status
            emit_device_status(
                self._device.serial,
                self._device.model or "",
                "online",
            )
        except Exception:
            pass

    def _init_database(self) -> None:
        logger.info("Connecting to MongoDB")
        get_client().connect()
        ensure_indexes()
        ensure_schema_validation()
        self._repo = CallRepository()
        logger.info("MongoDB ready")

    def initialize(self) -> None:
        logger.info("=" * 60)
        logger.info("CALL ORCHESTRATOR v3 — ADB+Android+Kotlin Flow")
        logger.info("=" * 60)
        logger.info("Mode: ADB dialing + Android in-call audio routing")
        logger.info("Audio playback: Kotlin CallAutomationService")
        logger.info("Recording: Kotlin RecordingManager (AudioRecord)")
        logger.info("Transcription: Faster-Whisper")
        logger.info("=" * 60)
        self._start_time = time.monotonic()
        self._init_adb()
        self._init_database()
        logger.info("All components initialized successfully")

    # ── Lead Loading ───────────────────────────────────────────────────────

    def load_leads(self, file_path: Optional[str] = None) -> int:
        path = file_path or settings.leads_file_path
        logger.info(f"Loading leads from: {path}")

        result: ImportResult = import_leads(path)
        self._leads = result.leads

        logger.info(
            f"Leads loaded: {result.valid_count} valid, "
            f"{result.invalid_count} invalid, "
            f"{result.duplicate_count} duplicates"
        )
        for inv in result.invalid_rows:
            logger.warning(f"  Invalid row {inv.row_number}: {inv.reason}")

        return len(self._leads)

    # ── Core Call Flow ─────────────────────────────────────────────────────

    def _attempt_call(self, lead: LeadRecord, attempt: int) -> CallAttemptResult:
        logger.info(f"=" * 50)
        logger.info(f"Attempt {attempt} for {lead.name} ({lead.phone})")
        logger.info(f"= ADB dialing via Samsung SIM =")

        # Notify Android app to mark outgoing call
        self._notify_android_outgoing(lead.phone)

        call_result = self._call_controller.call(lead.phone)

        if call_result.outcome == CallOutcome.CONNECTED:
            logger.info(f"[{lead.phone}] ADB call connected — Android app handles audio")
            return self._handle_connected_call(lead)

        logger.warning(
            f"[{lead.phone}] Call not connected: outcome={call_result.outcome.value} "
            f"error={call_result.error_message}"
        )

        should_retry = call_result.outcome in (
            CallOutcome.NOT_ANSWERED,
            CallOutcome.FAILED,
            CallOutcome.TIMEOUT,
        )

        return CallAttemptResult(
            success=False,
            stage=CallStage.FAILED,
            error_message=call_result.error_message or call_result.outcome.value,
            should_retry=should_retry,
        )

    def _handle_connected_call(self, lead: LeadRecord) -> CallAttemptResult:
        """
        Handle a connected call.

        NOTE: Audio playback and recording are handled AUTOMATICALLY by
        the Android Kotlin app (CallAutomationService). This method:
          1. Waits for the Android app to complete its automation
          2. Waits for the WAV upload to arrive at /api/call/transcribe
          3. Transcription result is saved by the /api/call/transcribe endpoint
        """
        stage = CallStage.CONNECTED
        logger.info(f"[{lead.phone}] Call CONNECTED")
        logger.info(f"[{lead.phone}] Android app will handle: audio routing + playback + recording")

        # Wait for Android app to complete its flow
        # Android flow takes: 1.2s settle + ~6s greeting + 18s recording + ~2s upload = ~30s typical
        total_wait = settings.call_flow_max_call_duration
        logger.info(f"[{lead.phone}] Waiting up to {total_wait}s for Android automation + recording")

        # Poll for call end (Android app ends the call after recording)
        poll_start = time.monotonic()
        prev_state = self._adb_manager.get_call_state_int()

        while time.monotonic() - poll_start < total_wait:
            time.sleep(2.0)
            raw_state = self._adb_manager.get_call_state_int()

            if raw_state == 0:  # IDLE — call ended
                duration = time.monotonic() - poll_start
                logger.info(f"[{lead.phone}] Call ended after {duration:.1f}s")
                break

            if raw_state != prev_state:
                logger.debug(f"[{lead.phone}] State changed: {prev_state} → {raw_state}")
                prev_state = raw_state

        # Ensure hangup
        self._safe_hangup(lead.phone)

        # Recording + transcription are handled by the Android app uploading to /api/call/transcribe
        # Results are already in MongoDB by the time we get here
        # For this flow, we return success with UNKNOWN intent (actual intent saved separately)
        logger.info(f"[{lead.phone}] Android automation complete — checking MongoDB for result")

        # Poll MongoDB until Android upload + Whisper completes (up to ~90s)
        mongo_intent, transcription = self._poll_call_result(lead.phone, max_wait=90.0)
        logger.info(f"[{lead.phone}] MongoDB intent={mongo_intent.value} transcription_len={len(transcription or '')}")

        return CallAttemptResult(
            success=True,
            stage=CallStage.COMPLETED,
            intent=mongo_intent,
            transcription=transcription or "",
            recording_file="",
        )

    def _poll_call_result(self, phone: str, max_wait: float = 90.0) -> tuple[Intent, Optional[str]]:
        """Wait for Android upload transcription to appear in MongoDB."""
        deadline = time.monotonic() + max_wait
        last_intent = Intent.UNKNOWN
        last_text: Optional[str] = None

        while time.monotonic() < deadline:
            last_intent = self._fetch_latest_intent(phone)
            last_text = self._fetch_latest_transcription(phone)
            if last_intent in (Intent.YES, Intent.NO):
                return last_intent, last_text
            if last_text and len(last_text.strip()) > 2:
                return last_intent, last_text
            time.sleep(3.0)

        return last_intent, last_text

    def _fetch_latest_intent(self, phone: str) -> Intent:
        if not self._repo:
            return Intent.UNKNOWN
        try:
            records = self._repo.find_by_phone(phone, limit=1)
            if records:
                raw = records[0].intent
                if raw == "YES":
                    return Intent.YES
                elif raw == "NO":
                    return Intent.NO
        except Exception as exc:
            logger.warning(f"Could not fetch intent from MongoDB: {exc}")
        return Intent.UNKNOWN

    def _fetch_latest_transcription(self, phone: str) -> Optional[str]:
        if not self._repo:
            return None
        try:
            records = self._repo.find_by_phone(phone, limit=1)
            if records:
                return records[0].transcription
        except Exception as exc:
            logger.warning(f"Could not fetch transcription from MongoDB: {exc}")
        return None

    def _notify_android_outgoing(self, phone: str) -> None:
        """Send intent to Android app to prepare for outgoing call."""
        try:
            self._adb_manager._run([
                "shell", "am", "broadcast",
                "-a", "com.optimatrix.gsmcall.OUTGOING_CALL",
                "--es", "phone", phone
            ], check=False)
            logger.debug(f"Notified Android app of outgoing call to {phone}")
        except Exception as exc:
            logger.debug(f"Android notification failed (non-fatal): {exc}")

    def _safe_hangup(self, phone: str) -> None:
        try:
            logger.info(f"[{phone}] Hanging up")
            self._adb_manager.hangup()
            time.sleep(1.0)
        except Exception as exc:
            logger.warning(f"[{phone}] Hangup error (non-fatal): {exc}")
            try:
                self._adb_manager.hangup_via_telecom()
            except Exception as exc2:
                logger.warning(f"[{phone}] Telecom hangup also failed: {exc2}")

    # ── Database Persistence ─────────────────────────────────────────────

    def _save_result(self, lead: LeadRecord, attempt: CallAttemptResult) -> Optional[str]:
        if not self._repo:
            logger.warning("MongoDB not available, skipping save")
            return None

        try:
            call_status = (
                CallStatus.COMPLETED if attempt.success
                else CallStatus.FAILED
            )

            record = CallRecord(
                name=lead.name,
                phone_number=lead.phone,
                call_status=call_status,
                transcription=attempt.transcription,
                intent=attempt.intent.value,
                recording_file=attempt.recording_file,
            )
            doc_id = self._repo.insert(record)
            logger.info(f"Saved to MongoDB: {doc_id} ({lead.phone})")
            return doc_id
        except Exception as exc:
            logger.error(f"Failed to save to MongoDB: {exc}")
            return None

    # ── Single Call (API mode) ────────────────────────────────────────────

    def execute_single_call(self, name: str, phone: str) -> CallAttemptResult:
        """
        Execute a single ADB call.
        Used by the FastAPI /api/call/execute endpoint.
        """
        from leads.models import LeadRecord
        lead = LeadRecord(name=name, phone=phone)
        logger.info(f"Single call: {name} ({phone})")

        try:
            from integration.backend_events import emit_call_started
            emit_call_started(phone, name)
        except Exception:
            pass

        for attempt_num in range(1, settings.call_flow_retry_attempts + 1):
            result = self._attempt_call(lead, attempt_num)
            if result.success:
                return result
            if result.should_retry and attempt_num < settings.call_flow_retry_attempts:
                wait = settings.call_flow_retry_delay
                logger.info(f"Retry {attempt_num} in {wait}s for {phone}...")
                time.sleep(wait)
            else:
                return result

        return CallAttemptResult(
            success=False,
            stage=CallStage.FAILED,
            error_message="All attempts exhausted",
        )

    # ── Main Loop ────────────────────────────────────────────────────────

    def run(self, file_path: Optional[str] = None) -> list[LeadResult]:
        total = self.load_leads(file_path)

        if total == 0:
            logger.warning("No leads to process")
            return []

        logger.info(f"Processing {total} leads sequentially")
        self._results = []

        for idx, lead in enumerate(self._leads):
            if self._should_stop:
                logger.info("Stop requested, ending run")
                break

            lead_start = time.monotonic()
            lead_result = LeadResult(
                lead_index=idx + 1,
                total_leads=total,
                name=lead.name,
                phone=lead.phone,
                stage=CallStage.IDLE,
            )

            attempt: Optional[CallAttemptResult] = None
            for attempt_num in range(1, settings.call_flow_retry_attempts + 1):
                logger.info(f"[{idx + 1}/{total}] {lead.name} ({lead.phone}) — attempt {attempt_num}")

                attempt = self._attempt_call(lead, attempt_num)

                if attempt.success:
                    lead_result.stage = CallStage.COMPLETED
                    lead_result.intent = attempt.intent
                    lead_result.transcription = attempt.transcription
                    lead_result.recording_file = attempt.recording_file
                    break

                if attempt.should_retry and attempt_num < settings.call_flow_retry_attempts:
                    wait = settings.call_flow_retry_delay
                    logger.info(f"Retrying in {wait}s for {lead.phone}...")
                    time.sleep(wait)
                    lead_result.retry_count = attempt_num
                else:
                    lead_result.stage = CallStage.FAILED
                    lead_result.error_message = attempt.error_message
                    break

            duration = time.monotonic() - lead_start
            lead_result.duration_seconds = duration

            # Only save to MongoDB if not already saved by Android app
            if attempt and attempt.success and attempt.intent == Intent.UNKNOWN:
                # Android app already saved — skip to avoid duplicates
                logger.info(f"MongoDB result already saved by Android app for {lead.phone}")
            elif attempt and attempt.success:
                lead_result.db_id = self._save_result(lead, attempt)
            elif attempt:
                attempt_for_save = CallAttemptResult(
                    success=False,
                    stage=CallStage.FAILED,
                    error_message=attempt.error_message,
                )
                lead_result.db_id = self._save_result(lead, attempt_for_save)

            self._results.append(lead_result)
            self._log_lead_result(lead_result)

            # Delay between calls
            need_delay = (
                idx < total - 1
                and not self._should_stop
                and not lead_result.error_message
            )
            if need_delay:
                delay = settings.call_flow_between_calls_delay
                logger.info(f"Waiting {delay}s before next call...")
                time.sleep(delay)

        self._print_summary()
        return self._results

    def request_stop(self) -> None:
        logger.info("Stop requested")
        self._should_stop = True
        if self._call_controller:
            try:
                self._call_controller.request_stop()
            except Exception:
                pass

    # ── Reporting ────────────────────────────────────────────────────────

    def _log_lead_result(self, result: LeadResult) -> None:
        icon = {
            CallStage.COMPLETED: "OK",
            CallStage.FAILED: "FAIL",
            CallStage.SKIPPED: "SKIP",
        }.get(result.stage, "????")

        logger.info(
            f"[{icon}] {result.name} ({result.phone}) -> "
            f"intent={result.intent.value} "
            f"retries={result.retry_count} "
            f"duration={result.duration_seconds:.1f}s"
        )

    def _print_summary(self) -> None:
        total = len(self._results)
        if total == 0:
            return

        yes_count = sum(1 for r in self._results if r.intent == Intent.YES)
        no_count = sum(1 for r in self._results if r.intent == Intent.NO)
        unknown_count = sum(1 for r in self._results if r.intent == Intent.UNKNOWN)
        failed_count = sum(1 for r in self._results if r.stage == CallStage.FAILED)
        total_duration = time.monotonic() - self._start_time

        logger.info("=" * 60)
        logger.info("CALL SESSION SUMMARY")
        logger.info("=" * 60)
        logger.info(f"  Total leads:     {total}")
        logger.info(f"  YES responses:   {yes_count}")
        logger.info(f"  NO responses:    {no_count}")
        logger.info(f"  UNKNOWN:         {unknown_count}")
        logger.info(f"  Failed:          {failed_count}")
        logger.info(f"  Session time:    {total_duration:.1f}s")
        logger.info(f"  Avg per lead:    {total_duration / max(total, 1):.1f}s")
        logger.info("=" * 60)

    # ── Cleanup ──────────────────────────────────────────────────────────

    def shutdown(self) -> None:
        logger.info("Shutting down orchestrator")
        try:
            self._safe_hangup("cleanup")
        except Exception:
            pass
        try:
            from db.mongodb_client import close_client
            close_client()
        except Exception:
            pass
        logger.info("Orchestrator shutdown complete")
