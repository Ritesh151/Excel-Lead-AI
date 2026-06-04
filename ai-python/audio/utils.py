"""
audio/utils.py
Utility functions for WAV file inspection and audio data manipulation.
No playback happens here — pure data operations only.
"""

from __future__ import annotations

import wave
from dataclasses import dataclass
from pathlib import Path
from typing import Optional, Union

import numpy as np

from audio.exceptions import AudioFileNotFoundError, AudioFileInvalidError


# ─── WAV metadata ─────────────────────────────────────────────────────────────

@dataclass(frozen=True)
class WavInfo:
    """Metadata extracted from a WAV file header without loading sample data."""
    path: Path
    channels: int       # 1 = mono, 2 = stereo
    sample_rate: int    # Hz  (e.g. 44100, 16000)
    sample_width: int   # bytes per sample (1=8-bit, 2=16-bit, 4=32-bit)
    n_frames: int       # total sample frames
    duration_seconds: float

    @property
    def dtype(self) -> str:
        """Return the numpy dtype string matching sample_width."""
        return {1: "int8", 2: "int16", 4: "int32"}.get(self.sample_width, "int16")

    def __str__(self) -> str:
        return (
            f"{self.path.name}: {self.channels}ch "
            f"{self.sample_rate}Hz {self.sample_width*8}-bit "
            f"{self.duration_seconds:.2f}s"
        )


def inspect_wav(path: Union[str, Path]) -> WavInfo:
    """
    Read WAV header and return metadata without loading sample data into RAM.

    Args:
        path: Path to the .wav file.

    Returns:
        WavInfo dataclass.

    Raises:
        AudioFileNotFoundError if the file does not exist or is empty.
        AudioFileInvalidError  if the file is not a valid WAV.
    """
    path = Path(path).resolve()

    if not path.exists():
        raise AudioFileNotFoundError(f"WAV file not found: {path}")

    if path.stat().st_size == 0:
        raise AudioFileInvalidError(
            f"WAV file is empty (0 bytes): {path}. "
            "Replace it with a real recording."
        )

    try:
        with wave.open(str(path), "rb") as wf:
            channels    = wf.getnchannels()
            sample_rate = wf.getframerate()
            sample_width = wf.getsampwidth()
            n_frames    = wf.getnframes()
    except wave.Error as exc:
        raise AudioFileInvalidError(
            f"Cannot read WAV file '{path}': {exc}"
        ) from exc
    except Exception as exc:
        raise AudioFileInvalidError(
            f"Unexpected error reading '{path}': {exc}"
        ) from exc

    duration = n_frames / sample_rate if sample_rate > 0 else 0.0

    return WavInfo(
        path=path,
        channels=channels,
        sample_rate=sample_rate,
        sample_width=sample_width,
        n_frames=n_frames,
        duration_seconds=duration,
    )


# ─── WAV loading ──────────────────────────────────────────────────────────────

def load_wav(path: Union[str, Path]) -> tuple[np.ndarray, int]:
    """
    Load a WAV file into a numpy float32 array normalised to [-1.0, 1.0].

    Args:
        path: Path to the .wav file.

    Returns:
        (samples, sample_rate)
        samples shape: (n_frames,) for mono, (n_frames, 2) for stereo.

    Raises:
        AudioFileNotFoundError, AudioFileInvalidError on bad files.
    """
    info = inspect_wav(path)  # validates file first

    try:
        with wave.open(str(info.path), "rb") as wf:
            raw = wf.readframes(info.n_frames)
    except wave.Error as exc:
        raise AudioFileInvalidError(
            f"Failed to read frames from '{info.path}': {exc}"
        ) from exc

    # Convert raw bytes → numpy integer array
    dtype_map = {1: np.int8, 2: np.int16, 4: np.int32}
    np_dtype = dtype_map.get(info.sample_width, np.int16)
    samples = np.frombuffer(raw, dtype=np_dtype).copy()

    # Reshape stereo: (n_frames * channels,) → (n_frames, channels)
    if info.channels > 1:
        samples = samples.reshape(-1, info.channels)

    # Normalise to float32 in [-1.0, 1.0]
    max_val = float(np.iinfo(np_dtype).max)
    samples_f32 = samples.astype(np.float32) / max_val

    return samples_f32, info.sample_rate


# ─── Volume adjustment ────────────────────────────────────────────────────────

def apply_volume(samples: np.ndarray, volume: float) -> np.ndarray:
    """
    Scale sample amplitudes by `volume`.

    Args:
        samples: float32 numpy array in [-1.0, 1.0].
        volume:  Scalar multiplier in [0.0, 1.0].

    Returns:
        New float32 array with volume applied and values clamped to [-1.0, 1.0].
    """
    if not 0.0 <= volume <= 1.0:
        from audio.exceptions import AudioVolumeError
        raise AudioVolumeError(
            f"Volume must be between 0.0 and 1.0, got {volume}"
        )
    if volume == 1.0:
        return samples  # no-op path — avoids copy allocation
    return np.clip(samples * volume, -1.0, 1.0).astype(np.float32)


# ─── Mono/Stereo conversion ───────────────────────────────────────────────────

def to_mono(samples: np.ndarray) -> np.ndarray:
    """Average stereo channels to mono if needed. Returns unchanged if already mono."""
    if samples.ndim == 2 and samples.shape[1] == 2:
        return samples.mean(axis=1).astype(np.float32)
    return samples


def ensure_stereo(samples: np.ndarray) -> np.ndarray:
    """Duplicate mono channel to stereo if needed."""
    if samples.ndim == 1:
        return np.stack([samples, samples], axis=1)
    return samples


def compute_rms(samples: np.ndarray) -> float:
    """Return RMS level for a normalized float32 audio array."""
    if samples.size == 0:
        return 0.0
    return float(np.sqrt(np.mean(samples.astype(np.float32) ** 2)))


def rms_to_dbfs(rms: float) -> float:
    """Convert RMS amplitude to dBFS."""
    import math
    return 20.0 * math.log10(max(rms, 1e-9))


def normalize_audio(samples: np.ndarray, target_peak: float = 0.95) -> np.ndarray:
    """Scale audio so the peak amplitude reaches `target_peak` without clipping."""
    if samples.size == 0:
        return samples
    peak = float(np.max(np.abs(samples)))
    if peak < 1e-6:
        return samples
    gain = target_peak / peak
    return np.clip(samples * gain, -1.0, 1.0).astype(np.float32)


def apply_safe_gain(samples: np.ndarray, max_gain_db: float = 12.0) -> np.ndarray:
    """Boost audio level safely with a maximum gain cap."""
    if samples.size == 0:
        return samples
    import math
    rms = compute_rms(samples)
    if rms < 1e-6:
        return samples
    current_db = rms_to_dbfs(rms)
    target_db = min(current_db + max_gain_db, 0.0)
    gain_linear = 10 ** ((target_db - current_db) / 20)
    return np.clip(samples * gain_linear, -1.0, 1.0).astype(np.float32)


def remove_silence(
    samples: np.ndarray,
    threshold: float = 0.01,
    min_silence_sec: float = 0.05,
    sample_rate: int = 16000,
) -> np.ndarray:
    """Trim leading/trailing low-energy sections from the audio."""
    if samples.size == 0:
        return samples

    window_size = max(1, int(sample_rate * min_silence_sec))
    energy = np.convolve(samples ** 2, np.ones(window_size) / window_size, mode="same")
    mask = energy >= threshold ** 2
    if not np.any(mask):
        return samples

    start = int(np.argmax(mask))
    end = int(len(mask) - np.argmax(mask[::-1]))
    return samples[start:end].astype(np.float32)


def optimize_for_phone_mic(samples: np.ndarray, sample_rate: int) -> np.ndarray:
    """Enhance audio for narrowband voice pickup over GSM and a phone microphone."""
    if samples.size == 0 or sample_rate <= 0:
        return samples

    samples = samples.astype(np.float32)
    # voice band emphasis around 300-3400Hz while attenuating extreme lows/highs
    n = len(samples)
    spectrum = np.fft.rfft(samples)
    freqs = np.fft.rfftfreq(n, d=1.0 / sample_rate)

    boost = np.ones_like(spectrum, dtype=np.float32)
    # soft roll-on and roll-off for voice frequencies
    low = 100.0
    band_start = 300.0
    band_end = 3400.0
    high = 6000.0

    boost *= np.clip((freqs - low) / max(band_start - low, 1.0), 0.0, 1.0)
    pulse = np.clip((high - freqs) / max(high - band_end, 1.0), 0.0, 1.0)
    boost = np.minimum(boost, pulse)
    boost = 1.0 + 0.6 * boost
    boost[freqs < 80.0] *= 0.6
    boost[freqs > 5000.0] *= 0.5

    shaped = np.fft.irfft(spectrum * boost, n=n)
    shaped = normalize_audio(shaped, target_peak=0.95)
    return np.clip(shaped, -1.0, 1.0).astype(np.float32)


def prepare_playback_audio(
    path: Union[str, Path],
    volume: float = 1.0,
    target_sample_rate: Optional[int] = None,
    max_gain_db: float = 12.0,
) -> tuple[np.ndarray, int]:
    """Load a WAV file and prepare it for loud, clear speaker playback."""
    samples, sample_rate = load_wav(path)
    if target_sample_rate is None:
        target_sample_rate = sample_rate

    samples = to_mono(samples)
    samples = remove_silence(samples, threshold=0.01, min_silence_sec=0.04, sample_rate=sample_rate)
    samples = normalize_audio(samples, target_peak=0.92)
    samples = apply_safe_gain(samples, max_gain_db=max_gain_db)
    samples = optimize_for_phone_mic(samples, sample_rate)
    samples = apply_volume(samples, min(max(volume, 0.0), 1.0))

    if target_sample_rate != sample_rate and target_sample_rate > 0 and samples.size > 0:
        import numpy as np
        duration = len(samples) / float(sample_rate)
        new_length = max(1, int(duration * target_sample_rate + 0.5))
        x_old = np.linspace(0.0, duration, len(samples), endpoint=False)
        x_new = np.linspace(0.0, duration, new_length, endpoint=False)
        samples = np.interp(x_new, x_old, samples).astype(np.float32)
        sample_rate = target_sample_rate

    return samples, sample_rate
