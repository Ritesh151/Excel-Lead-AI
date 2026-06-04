"""
recorder/models.py
Pure data containers for the recording subsystem.
No I/O, no side effects — safe to import anywhere.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional


# ─── Recording config ─────────────────────────────────────────────────────────

@dataclass
class RecordingConfig:
    """
    All tunable parameters for a single recording session.

    Attributes:
        duration_seconds:    How long to record (default 5 s).
        sample_rate:         Audio sample rate in Hz (default 16 000 Hz —
                             optimal for speech / Whisper).
        channels:            1 = mono (speech), 2 = stereo.
        sample_width:        Bytes per sample: 2 → int16 (standard WAV).
        input_device:        sounddevice device index for the microphone.
                             None → system default input.
        noise_reduction:     If True, apply basic RMS-gate noise reduction
                             before saving.
        silence_threshold:   RMS amplitude below which a frame is considered
                             silence (used for empty-recording detection).
                             Range: 0.0–1.0 (float32 normalised).
        min_speech_ratio:    Fraction of frames that must be above
                             silence_threshold to consider the recording
                             non-empty (0.0–1.0).
    """
    duration_seconds:   float = 5.0
    sample_rate:        int   = 16_000
    channels:           int   = 1
    sample_width:       int   = 2          # bytes → int16
    input_device:       Optional[int] = None
    noise_reduction:    bool  = False
    silence_threshold:  float = 0.01       # ~-40 dBFS
    min_speech_ratio:   float = 0.05       # at least 5 % of frames must be speech

    def __post_init__(self) -> None:
        from recorder.exceptions import RecordingConfigError
        if self.duration_seconds <= 0:
            raise RecordingConfigError(
                f"duration_seconds must be > 0, got {self.duration_seconds}"
            )
        if self.sample_rate not in (8_000, 16_000, 22_050, 44_100, 48_000):
            raise RecordingConfigError(
                f"Unsupported sample_rate {self.sample_rate}. "
                "Use 8000, 16000, 22050, 44100 or 48000."
            )
        if self.channels not in (1, 2):
            raise RecordingConfigError(
                f"channels must be 1 or 2, got {self.channels}"
            )
        if self.sample_width not in (1, 2, 4):
            raise RecordingConfigError(
                f"sample_width must be 1, 2, or 4, got {self.sample_width}"
            )
        if not 0.0 <= self.silence_threshold <= 1.0:
            raise RecordingConfigError(
                f"silence_threshold must be in [0.0, 1.0], "
                f"got {self.silence_threshold}"
            )
        if not 0.0 <= self.min_speech_ratio <= 1.0:
            raise RecordingConfigError(
                f"min_speech_ratio must be in [0.0, 1.0], "
                f"got {self.min_speech_ratio}"
            )

    @property
    def numpy_dtype(self) -> str:
        """numpy dtype string matching sample_width."""
        return {1: "int8", 2: "int16", 4: "int32"}[self.sample_width]

    @property
    def total_frames(self) -> int:
        """Total sample frames for the full duration."""
        return int(self.duration_seconds * self.sample_rate)


# ─── Recording result ─────────────────────────────────────────────────────────

@dataclass
class RecordingResult:
    """
    Returned by Recorder.record() after every attempt — success or failure.

    Attributes:
        phone:           The phone number this recording belongs to.
        file_path:       Absolute path to the saved WAV file (None on failure).
        duration_seconds: Actual recorded duration.
        sample_rate:     Sample rate used.
        channels:        Channels recorded.
        is_empty:        True if the recording was below the speech threshold.
        success:         True if a usable WAV file was saved.
        error_message:   Human-readable failure reason (None on success).
    """
    phone:            str
    file_path:        Optional[Path]
    duration_seconds: float
    sample_rate:      int
    channels:         int
    is_empty:         bool  = False
    success:          bool  = False
    error_message:    Optional[str] = None

    def to_dict(self) -> dict:
        return {
            "phone":            self.phone,
            "file_path":        str(self.file_path) if self.file_path else None,
            "duration_seconds": round(self.duration_seconds, 3),
            "sample_rate":      self.sample_rate,
            "channels":         self.channels,
            "is_empty":         self.is_empty,
            "success":          self.success,
            "error_message":    self.error_message,
        }

    def __str__(self) -> str:
        status = "OK" if self.success else "FAILED"
        empty  = " [EMPTY]" if self.is_empty else ""
        path   = str(self.file_path) if self.file_path else "—"
        return (
            f"RecordingResult({status}{empty} | "
            f"phone={self.phone} | "
            f"{self.duration_seconds:.2f}s | "
            f"file={path}"
            + (f" | err={self.error_message}" if self.error_message else "")
            + ")"
        )
