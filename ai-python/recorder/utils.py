"""
recorder/utils.py
Pure utility functions for audio data manipulation and WAV file I/O.
No recording happens here — side-effect free except for file writes.
"""

from __future__ import annotations

import re
import wave
from datetime import datetime, timezone
from pathlib import Path
from typing import Union

import numpy as np

from recorder.exceptions import RecordingFileError, RecordingDirectoryError


# ─── File naming ──────────────────────────────────────────────────────────────

# Characters that are safe in file names on all OSes
_SAFE_PHONE_RE = re.compile(r"[^\w+\-]")


def make_recording_filename(phone: str) -> str:
    """
    Build a deterministic, filesystem-safe WAV file name.

    Format:  <sanitised_phone>_<UTC_timestamp>.wav
    Example: +919427047705_20240603_182530_123456.wav

    Args:
        phone: E.164 or any phone string.

    Returns:
        File name string (not a full path).
    """
    # Replace everything except word chars, + and - with underscore
    safe_phone = _SAFE_PHONE_RE.sub("_", phone.strip())
    ts = datetime.now(timezone.utc).strftime("%Y%m%d_%H%M%S_%f")
    return f"{safe_phone}_{ts}.wav"


# ─── Directory helpers ────────────────────────────────────────────────────────

def ensure_recordings_dir(path: Union[str, Path]) -> Path:
    """
    Create the recordings directory if it does not exist.

    Args:
        path: Target directory path.

    Returns:
        Resolved absolute Path.

    Raises:
        RecordingDirectoryError if the directory cannot be created.
    """
    path = Path(path).resolve()
    try:
        path.mkdir(parents=True, exist_ok=True)
    except OSError as exc:
        raise RecordingDirectoryError(
            f"Cannot create recordings directory '{path}': {exc}"
        ) from exc
    return path


# ─── WAV I/O ──────────────────────────────────────────────────────────────────

def save_wav(
    file_path: Union[str, Path],
    samples: np.ndarray,
    sample_rate: int,
    sample_width: int = 2,
    channels: int = 1,
) -> Path:
    """
    Write a numpy array to a WAV file.

    Args:
        file_path:    Full destination path (including filename).
        samples:      int16 (or matching dtype) numpy array.
                      Shape: (n_frames,) for mono, (n_frames, ch) for multi.
        sample_rate:  Hz.
        sample_width: Bytes per sample (1=8-bit, 2=16-bit, 4=32-bit).
        channels:     Number of channels.

    Returns:
        Resolved Path to the written file.

    Raises:
        RecordingFileError on any write failure.
    """
    file_path = Path(file_path).resolve()

    # Ensure the samples are in the right integer dtype
    dtype_map = {1: np.int8, 2: np.int16, 4: np.int32}
    target_dtype = dtype_map.get(sample_width, np.int16)

    if samples.dtype != target_dtype:
        samples = samples.astype(target_dtype)

    # Flatten multi-channel to interleaved bytes
    raw_bytes = samples.tobytes()

    try:
        with wave.open(str(file_path), "wb") as wf:
            wf.setnchannels(channels)
            wf.setsampwidth(sample_width)
            wf.setframerate(sample_rate)
            wf.writeframes(raw_bytes)
    except Exception as exc:
        raise RecordingFileError(
            f"Failed to write WAV file '{file_path}': {exc}"
        ) from exc

    return file_path


def get_wav_duration(file_path: Union[str, Path]) -> float:
    """
    Read WAV header and return duration in seconds without loading samples.

    Returns 0.0 if the file cannot be read.
    """
    try:
        with wave.open(str(file_path), "rb") as wf:
            return wf.getnframes() / wf.getframerate()
    except Exception:
        return 0.0


# ─── Audio analysis ───────────────────────────────────────────────────────────

def compute_rms(samples: np.ndarray) -> float:
    """
    Compute Root Mean Square amplitude of a float32 sample array.

    Returns a value in [0.0, 1.0] where 0.0 is silence and 1.0 is full scale.
    """
    if samples.size == 0:
        return 0.0
    f32 = samples.astype(np.float32)
    return float(np.sqrt(np.mean(f32 ** 2)))


def is_silent(
    samples: np.ndarray,
    threshold: float = 0.01,
    min_speech_ratio: float = 0.05,
    frame_size: int = 512,
) -> bool:
    """
    Determine whether a recording is effectively silent.

    Splits the signal into short frames and counts how many exceed
    `threshold` RMS.  If the fraction of speech frames is below
    `min_speech_ratio`, the recording is considered silent.

    Args:
        samples:          float32 numpy array, any shape (flattened internally).
        threshold:        RMS value above which a frame counts as speech.
        min_speech_ratio: Minimum fraction of speech frames to be non-silent.
        frame_size:       Samples per analysis frame.

    Returns:
        True if the recording is silent / empty.
    """
    flat = samples.flatten().astype(np.float32)
    if flat.size == 0:
        return True

    # Trim to exact multiple of frame_size
    n_frames = flat.size // frame_size
    if n_frames == 0:
        return compute_rms(flat) < threshold

    trimmed = flat[: n_frames * frame_size].reshape(n_frames, frame_size)
    rms_per_frame = np.sqrt(np.mean(trimmed ** 2, axis=1))
    speech_frames = int(np.sum(rms_per_frame > threshold))
    speech_ratio  = speech_frames / n_frames

    return speech_ratio < min_speech_ratio


# ─── Noise reduction ──────────────────────────────────────────────────────────

def apply_noise_gate(
    samples: np.ndarray,
    threshold: float = 0.01,
    frame_size: int = 512,
) -> np.ndarray:
    """
    Simple RMS-based noise gate.

    Any frame whose RMS is below `threshold` is zeroed out.
    This removes low-level background hiss without affecting speech.

    Args:
        samples:    float32 numpy array, shape (n,) or (n, ch).
        threshold:  RMS below which frames are silenced.
        frame_size: Samples per gate frame.

    Returns:
        float32 numpy array with silent frames zeroed.
    """
    flat = samples.flatten().astype(np.float32)
    original_shape = samples.shape

    n_frames = len(flat) // frame_size
    remainder = len(flat) % frame_size

    result = flat.copy()

    for i in range(n_frames):
        start = i * frame_size
        end   = start + frame_size
        frame = flat[start:end]
        if compute_rms(frame) < threshold:
            result[start:end] = 0.0

    # Leave the remainder frame untouched (too short to gate reliably)
    return result.reshape(original_shape)


# ─── Float ↔ Int16 conversion ─────────────────────────────────────────────────

def float32_to_int16(samples: np.ndarray) -> np.ndarray:
    """Normalise float32 [-1.0, 1.0] → int16."""
    clipped = np.clip(samples, -1.0, 1.0)
    return (clipped * 32767).astype(np.int16)


def int16_to_float32(samples: np.ndarray) -> np.ndarray:
    """Convert int16 → float32 normalised to [-1.0, 1.0]."""
    return samples.astype(np.float32) / 32768.0
