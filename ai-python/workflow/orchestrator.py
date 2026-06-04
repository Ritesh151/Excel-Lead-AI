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

from audio.playback_controller import PlaybackController

from recorder.recording_manager import RecordingManager

from transcription.transcription_service import TranscriptionService

from db.mongodb_client import get_client
from db.models import CallRecord, CallStatus
from db.call_repository import CallRepository
from db.utils import ensure_indexes, ensure_schema_validation

from workflow.states import CallStage, Intent, LeadResult, CallAttemptResult


class CallOrchestrator:

    def __init__(self) -> None:
        self._device: Optional[DeviceInfo] = None
        self._adb_manager: Optional[AdbManager] = None
        self._call_controller: Optional[CallController] = None
        self._playback: Optional[PlaybackController] = None
        self._recorder: Optional[RecordingManager] = None
        self._transcriber: Optional[TranscriptionService] = None
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
        logger.info(f"Device: {self._device.serial} ({self._device.model})")

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

    def _init_audio(self) -> None:
        logger.info("Initializing audio playback")
        self._playback = PlaybackController(
            audio_dir=settings.audio_dir,
            volume=settings.audio_volume,
        )
        logger.info("Playback controller ready")

    def _init_recorder(self) -> None:
        logger.info("Initializing recording manager")
        self._recorder = RecordingManager(
            recordings_dir=settings.recordings_dir,
        )
        logger.info(f"Recording manager ready -> {settings.recordings_dir}")

    def _init_transcriber(self) -> None:
        logger.info("Initializing transcription service")
        self._transcriber = TranscriptionService()
        self._transcriber.warm_up()
        logger.info("Transcription service ready")

    def _init_database(self) -> None:
        logger.info("Connecting to MongoDB")
        get_client().connect()
        ensure_indexes()
        ensure_schema_validation()
        self._repo = CallRepository()
        logger.info("MongoDB ready")

    def initialize(self) -> None:
        logger.info("=" * 60)
        logger.info("CALL ORCHESTRATOR INITIALIZATION")
        logger.info("=" * 60)
        self._start_time = time.monotonic()
        self._init_adb()
        self._init_audio()
        self._init_recorder()
        self._init_transcriber()
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
        logger.info(f"Attempt {attempt} for {lead.name} ({lead.phone})")

        call_result = self._call_controller.call(lead.phone)

        if call_result.outcome == CallOutcome.CONNECTED:
            logger.info(f"Call connected: {lead.phone}")
            return self._handle_connected_call(lead)

        logger.warning(
            f"Call failed: outcome={call_result.outcome.value} "
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
        stage = CallStage.CONNECTED

        # ── Play greeting ──────────────────────────────────────────────
        stage = CallStage.PLAYING_GREETING
        logger.info(f"Playing greeting for {lead.name}")
        try:
            greet_result = self._playback.play_greeting()
            if not greet_result.succeeded:
                logger.warning(f"Greeting playback issue: {greet_result.error_message}")
        except Exception as exc:
            logger.error(f"Greeting playback failed: {exc}")
            self._hangup()
            return CallAttemptResult(
                success=False, stage=stage,
                error_message=f"Greeting error: {exc}",
            )

        # ── Record response ────────────────────────────────────────────
        stage = CallStage.RECORDING
        logger.info(f"Recording response from {lead.name}")
        try:
            rec_result = self._recorder.record_response(lead.phone)
            if not rec_result.success:
                logger.warning(f"Recording issue: {rec_result.error_message}")
        except Exception as exc:
            logger.error(f"Recording failed: {exc}")
            self._hangup()
            return CallAttemptResult(
                success=False, stage=stage,
                error_message=f"Recording error: {exc}",
            )

        # ── Transcribe ─────────────────────────────────────────────────
        stage = CallStage.TRANSCRIBING
        logger.info(f"Transcribing recording for {lead.name}")

        if rec_result.success and rec_result.file_path:
            try:
                trans_result = self._transcriber.transcribe_file(rec_result.file_path)
            except Exception as exc:
                logger.error(f"Transcription failed: {exc}")
                self._hangup()
                return CallAttemptResult(
                    success=False, stage=stage,
                    error_message=f"Transcription error: {exc}",
                )
        else:
            trans_result = None

        stage = CallStage.EVALUATING
        transcription = trans_result.transcription if trans_result else ""
        intent = Intent.UNKNOWN
        if trans_result:
            if trans_result.is_yes:
                intent = Intent.YES
            elif trans_result.is_no:
                intent = Intent.NO

        logger.info(
            f"Intent detected: {intent.value} | "
            f'Transcription: "{transcription[:80]}"'
        )

        # ── Thank you (only for YES) ───────────────────────────────────
        if intent == Intent.YES:
            stage = CallStage.PLAYING_THANK_YOU
            logger.info(f"Playing thank_you for {lead.name}")
            try:
                thank_result = self._playback.play_thank_you()
                if not thank_result.succeeded:
                    logger.warning(f"Thank you playback issue: {thank_result.error_message}")
            except Exception as exc:
                logger.error(f"Thank you playback failed: {exc}")

        # ── Hang up ────────────────────────────────────────────────────
        self._hangup()

        recording_file = str(rec_result.file_path) if (rec_result.success and rec_result.file_path) else ""

        return CallAttemptResult(
            success=True,
            stage=CallStage.COMPLETED,
            intent=intent,
            transcription=transcription,
            recording_file=recording_file,
        )

    def _hangup(self) -> None:
        try:
            logger.info("Hanging up call")
            self._adb_manager.hangup()
            time.sleep(settings.call_flow_between_calls_delay / 2)
        except Exception as exc:
            logger.warning(f"Hangup error (non-fatal): {exc}")

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

    # ── Single Call Execution (for API mode) ─────────────────────────────

    def execute_single_call(self, name: str, phone: str) -> CallAttemptResult:
        """
        Execute a single call for a given name and phone number.
        Used by the FastAPI /api/call/execute endpoint.

        Returns CallAttemptResult with success, intent, transcription, etc.
        """
        from leads.models import LeadRecord

        lead = LeadRecord(name=name, phone=phone)
        logger.info(f"Single call: {name} ({phone})")

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

            if attempt and attempt.success:
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
            self._hangup()
        except Exception:
            pass
        try:
            from db.mongodb_client import close_client
            close_client()
        except Exception:
            pass
        logger.info("Orchestrator shutdown complete")
