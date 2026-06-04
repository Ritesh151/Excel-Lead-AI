"""
recorder/recording_manager.py
High-level recording orchestrator.

This is the single entry-point the calling logic uses for all recording.
It coordinates Recorder (capture) + utils (file I/O) and returns a
typed RecordingResult for every attempt — success or failure.

Public API:
    RecordingManager.record_response(phone) → RecordingResult
    RecordingManager.list_input_devices()   → list[DeviceInfo]
    RecordingManager.check_device()         → DeviceInfo
"""

from __future__ import annotations

import time
from pathlib import Path
from typing import Optional, Union

from logger import logger
from config import settings
from recorder.models import RecordingConfig, RecordingResult
from recorder.recorder import Recorder, DeviceInfo
from recorder.utils import (
    ensure_recordings_dir,
    get_wav_duration,
    make_recording_filename,
    save_wav,
)
from recorder.exceptions import (
    EmptyRecordingError,
    MicrophoneNotFoundError,
    MicrophonePermissionError,
    MicrophoneUnavailableError,
    RecorderBaseError,
    RecordingDirectoryError,
    RecordingError,
    RecordingFileError,
)


class RecordingManager:
    """
    Orchestrates microphone capture → WAV file save for one call at a time.

    Args:
        recordings_dir: Where to save WAV files (defaults to settings.recordings_dir).
        config:         RecordingConfig with sample rate, duration, etc.
                        Pass None to use environment-driven defaults.
    """

    def __init__(
        self,
        recordings_dir: Optional[Union[str, Path]] = None,
        config: Optional[RecordingConfig] = None,
    ) -> None:
        # Resolve the recordings directory — create it if absent
        raw_dir = recordings_dir or settings.recordings_dir
        try:
            self._recordings_dir = ensure_recordings_dir(raw_dir)
        except RecordingDirectoryError as exc:
            logger.error(f"RecordingManager: {exc}")
            raise

        self._cfg = config or _config_from_settings()
        self._recorder = Recorder(config=self._cfg)

        logger.info(
            f"RecordingManager ready — "
            f"dir={self._recordings_dir} "
            f"duration={self._cfg.duration_seconds}s "
            f"rate={self._cfg.sample_rate}Hz "
            f"noise_reduction={self._cfg.noise_reduction}"
        )

    # ── Public API ─────────────────────────────────────────────────────────────

    def record_response(self, phone: str) -> RecordingResult:
        """
        Record the caller's voice response and save it as a WAV file.

        This is the main method called immediately after greeting playback.
        Blocks for `config.duration_seconds`.

        Args:
            phone: E.164 phone number, used in the output file name.

        Returns:
            RecordingResult — always returns, never raises.
            Check .success before using .file_path.
        """
        logger.info(f"[{phone}] Recording caller response …")
        start = time.monotonic()

        # ── Capture ───────────────────────────────────────────────────────────
        try:
            samples, cfg = self._recorder.record()

        except MicrophoneNotFoundError as exc:
            return self._failure(phone, start, f"Microphone not found: {exc}")

        except MicrophonePermissionError as exc:
            return self._failure(phone, start, f"Microphone permission denied: {exc}")

        except MicrophoneUnavailableError as exc:
            return self._failure(phone, start, f"Microphone unavailable: {exc}")

        except EmptyRecordingError as exc:
            # We have the data (it's just silent) — still try to save it
            # so callers can listen back if needed, but flag is_empty=True
            logger.warning(f"[{phone}] Empty recording: {exc}")
            return RecordingResult(
                phone            = phone,
                file_path        = None,
                duration_seconds = time.monotonic() - start,
                sample_rate      = self._cfg.sample_rate,
                channels         = self._cfg.channels,
                is_empty         = True,
                success          = False,
                error_message    = str(exc),
            )

        except RecordingError as exc:
            return self._failure(phone, start, f"Recording failed: {exc}")

        except Exception as exc:
            return self._failure(phone, start, f"Unexpected error: {exc}")

        # ── Save WAV ──────────────────────────────────────────────────────────
        filename  = make_recording_filename(phone)
        file_path = self._recordings_dir / filename

        try:
            saved_path = save_wav(
                file_path    = file_path,
                samples      = samples,
                sample_rate  = cfg.sample_rate,
                sample_width = cfg.sample_width,
                channels     = cfg.channels,
            )
        except RecordingFileError as exc:
            return self._failure(phone, start, f"Could not save WAV: {exc}")
        except Exception as exc:
            return self._failure(phone, start, f"Unexpected save error: {exc}")

        duration = get_wav_duration(saved_path)
        elapsed  = time.monotonic() - start

        logger.info(
            f"[{phone}] Recording saved — "
            f"file={saved_path.name} "
            f"duration={duration:.2f}s "
            f"elapsed={elapsed:.2f}s"
        )

        return RecordingResult(
            phone            = phone,
            file_path        = saved_path,
            duration_seconds = duration,
            sample_rate      = cfg.sample_rate,
            channels         = cfg.channels,
            is_empty         = False,
            success          = True,
        )

    def list_input_devices(self) -> list[DeviceInfo]:
        """Return all audio input devices visible to sounddevice."""
        return self._recorder.list_input_devices()

    def check_device(self) -> DeviceInfo:
        """
        Verify the configured input device is accessible before starting calls.

        Raises:
            MicrophoneNotFoundError if the device is not present.
        """
        return self._recorder.check_device()

    # ── Config access ─────────────────────────────────────────────────────────

    @property
    def config(self) -> RecordingConfig:
        return self._cfg

    @property
    def recordings_dir(self) -> Path:
        return self._recordings_dir

    # ── Internal helpers ──────────────────────────────────────────────────────

    def _failure(
        self,
        phone: str,
        start: float,
        message: str,
    ) -> RecordingResult:
        """Build a failed RecordingResult and log the error."""
        logger.error(f"[{phone}] {message}")
        return RecordingResult(
            phone            = phone,
            file_path        = None,
            duration_seconds = time.monotonic() - start,
            sample_rate      = self._cfg.sample_rate,
            channels         = self._cfg.channels,
            is_empty         = False,
            success          = False,
            error_message    = message,
        )


# ─── Settings-driven config factory ──────────────────────────────────────────

def _config_from_settings() -> RecordingConfig:
    """
    Build a RecordingConfig from the application settings object.
    Falls back to sensible defaults for any missing setting.
    """
    return RecordingConfig(
        duration_seconds  = float(getattr(settings, "recording_duration",  5.0)),
        sample_rate       = int(getattr(settings,   "recording_sample_rate", 16_000)),
        channels          = int(getattr(settings,   "recording_channels",   1)),
        input_device      = _nullable_int(
                                getattr(settings, "recording_input_device", -1)
                            ),
        noise_reduction   = bool(getattr(settings, "recording_noise_reduction", False)),
        silence_threshold = float(getattr(settings, "recording_silence_threshold", 0.01)),
        min_speech_ratio  = float(getattr(settings, "recording_min_speech_ratio",  0.05)),
    )


def _nullable_int(value) -> Optional[int]:
    """Convert a value to int, returning None if it's -1, None, or empty."""
    try:
        v = int(value)
        return None if v < 0 else v
    except (TypeError, ValueError):
        return None
