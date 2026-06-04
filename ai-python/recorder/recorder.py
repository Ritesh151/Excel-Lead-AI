"""
recorder/recorder.py
Low-level microphone capture engine.

Captures audio from the default (or configured) system microphone using
sounddevice.  The PC microphone picks up the phone speaker audio during
a live call — the caller's voice is captured this way.

Responsibilities:
  - Open the input device safely
  - Record for a configurable duration
  - Convert raw capture to float32 numpy array
  - Apply optional noise gate
  - Detect silent / empty recordings
  - Return raw audio data — file saving is handled by RecordingManager

Public API:
    Recorder.record()           → tuple[np.ndarray, RecordingConfig]
    Recorder.list_input_devices() → list[dict]
    Recorder.check_device()     → DeviceInfo
"""

from __future__ import annotations

import time
from dataclasses import dataclass
from typing import Optional

import numpy as np

from logger import logger
from recorder.models import RecordingConfig
from recorder.utils import (
    apply_noise_gate,
    compute_rms,
    float32_to_int16,
    int16_to_float32,
    is_silent,
)
from recorder.exceptions import (
    EmptyRecordingError,
    MicrophoneNotFoundError,
    MicrophonePermissionError,
    MicrophoneUnavailableError,
    RecordingError,
)


# ─── Device info ──────────────────────────────────────────────────────────────

@dataclass
class DeviceInfo:
    """Metadata for a single audio input device."""
    index:      int
    name:       str
    channels:   int
    sample_rate: int
    is_default: bool = False


# ─── Recorder ─────────────────────────────────────────────────────────────────

class Recorder:
    """
    Microphone capture engine backed by sounddevice.

    Args:
        config: RecordingConfig with all tunable parameters.

    The Recorder is stateless between calls — you can reuse one instance
    across multiple sequential calls without side effects.
    """

    def __init__(self, config: Optional[RecordingConfig] = None) -> None:
        self._cfg = config or RecordingConfig()
        self._sd = self._import_sounddevice()  # lazy import; raises if missing
        logger.debug(
            f"Recorder initialised — "
            f"device={self._cfg.input_device} "
            f"rate={self._cfg.sample_rate}Hz "
            f"duration={self._cfg.duration_seconds}s "
            f"channels={self._cfg.channels} "
            f"noise_reduction={self._cfg.noise_reduction}"
        )

    # ── Public API ────────────────────────────────────────────────────────────

    def record(self) -> tuple[np.ndarray, RecordingConfig]:
        """
        Capture audio from the microphone for `config.duration_seconds`.

        This call BLOCKS for exactly the configured duration.

        Returns:
            (samples_int16, config)
            samples_int16: int16 numpy array, shape (n_frames,) for mono
                           or (n_frames, channels) for stereo.

        Raises:
            MicrophoneNotFoundError     no usable input device.
            MicrophonePermissionError   OS denied microphone access.
            MicrophoneUnavailableError  device exists but could not open.
            RecordingError              capture failed mid-stream.
            EmptyRecordingError         recording was silent / no speech.
        """
        cfg  = self._cfg
        sd   = self._sd

        logger.info(
            f"Recording started — "
            f"{cfg.duration_seconds}s @ {cfg.sample_rate}Hz "
            f"ch={cfg.channels} "
            f"device={cfg.input_device or 'default'}"
        )

        start_ts = time.monotonic()

        # ── Capture ───────────────────────────────────────────────────────────
        try:
            # sd.rec() returns a float32 array of shape (frames, channels)
            raw: np.ndarray = sd.rec(
                frames       = cfg.total_frames,
                samplerate   = cfg.sample_rate,
                channels     = cfg.channels,
                dtype        = "float32",
                device       = cfg.input_device,  # None → default
                blocking     = True,              # block until complete
            )
        except sd.PortAudioError as exc:
            msg = str(exc)
            logger.error(f"PortAudio error during recording: {msg}")
            if "Permission" in msg or "access" in msg.lower():
                raise MicrophonePermissionError(
                    f"OS denied microphone access: {msg}"
                ) from exc
            if "Invalid device" in msg or "No Default Input" in msg:
                raise MicrophoneNotFoundError(
                    f"No suitable input device found: {msg}"
                ) from exc
            raise MicrophoneUnavailableError(
                f"Microphone could not be opened: {msg}"
            ) from exc
        except Exception as exc:
            raise RecordingError(
                f"Unexpected error during capture: {exc}"
            ) from exc

        elapsed = time.monotonic() - start_ts
        logger.info(f"Recording captured — {elapsed:.2f}s, shape={raw.shape}")

        # ── Flatten mono ──────────────────────────────────────────────────────
        # sounddevice always returns (frames, channels); squeeze mono to (frames,)
        if cfg.channels == 1 and raw.ndim == 2:
            raw = raw[:, 0]

        # ── Validate — not all zeros ──────────────────────────────────────────
        if raw.size == 0 or np.all(raw == 0):
            raise EmptyRecordingError(
                "Recording contains only zeros — microphone may be muted or disconnected"
            )

        # ── Optional noise gate ───────────────────────────────────────────────
        if cfg.noise_reduction:
            before_rms = compute_rms(raw)
            raw = apply_noise_gate(raw, threshold=cfg.silence_threshold)
            after_rms = compute_rms(raw)
            logger.debug(
                f"Noise gate applied — RMS before={before_rms:.4f} after={after_rms:.4f}"
            )

        # ── Silence detection ─────────────────────────────────────────────────
        silent = is_silent(
            raw,
            threshold        = cfg.silence_threshold,
            min_speech_ratio = cfg.min_speech_ratio,
        )
        if silent:
            logger.warning(
                f"Recording appears silent (rms={compute_rms(raw):.4f}, "
                f"threshold={cfg.silence_threshold})"
            )
            raise EmptyRecordingError(
                f"Recording is below speech threshold "
                f"(rms={compute_rms(raw):.4f} < {cfg.silence_threshold})"
            )

        rms = compute_rms(raw)
        logger.info(f"Recording valid — rms={rms:.4f}")

        # ── Convert float32 → int16 for WAV storage ───────────────────────────
        samples_int16 = float32_to_int16(raw)

        return samples_int16, cfg

    # ── Device utilities ──────────────────────────────────────────────────────

    def list_input_devices(self) -> list[DeviceInfo]:
        """
        Return all available audio input devices.
        """
        sd = self._sd
        default_idx = self._get_default_input_index()
        result: list[DeviceInfo] = []

        for idx, dev in enumerate(sd.query_devices()):
            if dev["max_input_channels"] > 0:
                result.append(DeviceInfo(
                    index       = idx,
                    name        = dev["name"],
                    channels    = dev["max_input_channels"],
                    sample_rate = int(dev["default_samplerate"]),
                    is_default  = (idx == default_idx),
                ))

        return result

    def check_device(self) -> DeviceInfo:
        """
        Verify the configured input device is accessible.

        Returns:
            DeviceInfo for the target device.

        Raises:
            MicrophoneNotFoundError if the device does not exist.
        """
        sd = self._sd
        device_idx = self._cfg.input_device

        if device_idx is None:
            device_idx = self._get_default_input_index()
            if device_idx is None:
                raise MicrophoneNotFoundError(
                    "No default audio input device found. "
                    "Connect a microphone and try again."
                )

        devices = sd.query_devices()
        if device_idx >= len(devices):
            raise MicrophoneNotFoundError(
                f"Device index {device_idx} does not exist "
                f"(only {len(devices)} devices available)"
            )

        dev = devices[device_idx]
        if dev["max_input_channels"] == 0:
            raise MicrophoneNotFoundError(
                f"Device [{device_idx}] '{dev['name']}' has no input channels"
            )

        return DeviceInfo(
            index       = device_idx,
            name        = dev["name"],
            channels    = dev["max_input_channels"],
            sample_rate = int(dev["default_samplerate"]),
            is_default  = (device_idx == self._get_default_input_index()),
        )

    # ── Internal ─────────────────────────────────────────────────────────────

    @staticmethod
    def _import_sounddevice():
        """
        Import sounddevice, raising a clear error if not installed.
        Kept as a method so the rest of the module stays importable
        even without sounddevice installed.
        """
        try:
            import sounddevice as sd
            return sd
        except ImportError as exc:
            raise MicrophoneUnavailableError(
                "sounddevice is not installed. "
                "Run: pip install sounddevice"
            ) from exc

    def _get_default_input_index(self) -> Optional[int]:
        """Return the index of the system default input device, or None."""
        try:
            default = self._sd.query_devices(kind="input")
            # query_devices(kind=) returns the device dict directly
            devices = self._sd.query_devices()
            for idx, dev in enumerate(devices):
                if dev["name"] == default["name"]:
                    return idx
        except Exception:
            pass
        return None
