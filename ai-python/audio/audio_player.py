"""
audio/audio_player.py
Low-level WAV playback engine with dual-backend support.

Backend priority:
  1. sounddevice  — precise, non-blocking capable, device-selectable
  2. pydub        — fallback using simpleaudio under the hood (no device selection)

Both backends play through the PC system speaker.
The phone microphone picks up the PC speaker audio naturally during a live call.

Public API:
    AudioPlayer.play(path, volume, block)  → PlaybackHandle
    AudioPlayer.stop()
    AudioPlayer.is_playing                 → bool
    AudioPlayer.list_output_devices()      → list[dict]
"""

from __future__ import annotations

import subprocess
import threading
import time
from dataclasses import dataclass, field
from enum import Enum, auto
from pathlib import Path
from typing import Optional, Union

from logger import logger
from audio.utils import (
    apply_volume,
    compute_rms,
    inspect_wav,
    load_wav,
    prepare_playback_audio,
    rms_to_dbfs,
)
from audio.exceptions import (
    AudioDeviceError,
    AudioFileInvalidError,
    AudioFileNotFoundError,
    AudioPlaybackError,
)


# ─── Backend enum ─────────────────────────────────────────────────────────────

class Backend(Enum):
    SOUNDDEVICE = auto()
    PYDUB       = auto()


# ─── Playback handle ──────────────────────────────────────────────────────────

@dataclass
class PlaybackHandle:
    """
    Returned by AudioPlayer.play().
    Lets the caller wait for completion, check status, or stop early.
    """
    file_name: str
    duration_seconds: float
    backend: Backend
    _done_event: threading.Event = field(default_factory=threading.Event, repr=False)
    _error: Optional[Exception]  = field(default=None, repr=False)

    def wait(self, timeout: Optional[float] = None) -> bool:
        """
        Block until playback finishes or timeout expires.

        Args:
            timeout: Max seconds to wait. None = wait forever.

        Returns:
            True if playback completed, False if timed out.
        """
        return self._done_event.wait(timeout=timeout)

    @property
    def is_done(self) -> bool:
        return self._done_event.is_set()

    @property
    def error(self) -> Optional[Exception]:
        return self._error

    def _mark_done(self, error: Optional[Exception] = None) -> None:
        self._error = error
        self._done_event.set()


# ─── AudioPlayer ──────────────────────────────────────────────────────────────

class AudioPlayer:
    """
    Thread-safe WAV player with sounddevice/pydub dual-backend.

    Usage:
        player = AudioPlayer(volume=0.9)
        handle = player.play("audio/greeting.wav", block=True)
        # or non-blocking:
        handle = player.play("audio/greeting.wav", block=False)
        handle.wait()   # wait for completion elsewhere

    Only one file plays at a time; calling play() while already
    playing stops the current file first.
    """

    def __init__(
        self,
        volume: float = 1.0,
        output_device: Optional[int] = None,
    ) -> None:
        """
        Args:
            volume:        Master volume 0.0–1.0.
            output_device: sounddevice output device index.
                           None / -1 → system default.
        """
        if not 0.0 <= volume <= 1.0:
            raise ValueError(f"volume must be 0.0–1.0, got {volume}")

        self._volume = volume
        self._output_device: Optional[int] = (
            None if (output_device is None or output_device < 0) else output_device
        )
        self._lock = threading.Lock()
        self._current_handle: Optional[PlaybackHandle] = None
        self._stop_flag = threading.Event()

        # Detect best available backend once at construction.
        # Deferred: raises AudioDeviceError only when play() is first called,
        # so the object can be constructed and inspected without a sound card.
        self._backend: Optional[Backend] = self._detect_backend_safe()

        if self._output_device is None and self._backend == Backend.SOUNDDEVICE:
            self._output_device = self._choose_best_output_device()

        logger.debug(
            f"AudioPlayer ready — backend={self._backend.name if self._backend else 'none (not installed)'} "
            f"volume={self._volume} device={self._output_device}"
        )

    # ── Backend detection ─────────────────────────────────────────────────────

    @staticmethod
    def _detect_backend() -> Backend:
        """Detect backend; raises AudioDeviceError if none available."""
        try:
            import sounddevice  # noqa: F401
            logger.debug("Audio backend: sounddevice")
            return Backend.SOUNDDEVICE
        except ImportError:
            pass
        try:
            from pydub import playback as _pb  # noqa: F401
            logger.debug("Audio backend: pydub (sounddevice not available)")
            return Backend.PYDUB
        except ImportError:
            pass
        raise AudioDeviceError(
            "No audio playback backend available. "
            "Install sounddevice or pydub: pip install sounddevice pydub"
        )

    @staticmethod
    def _detect_backend_safe() -> Optional[Backend]:
        """Like _detect_backend() but returns None instead of raising."""
        try:
            return AudioPlayer._detect_backend()
        except AudioDeviceError:
            return None

    # ── Public API ────────────────────────────────────────────────────────────

    @property
    def volume(self) -> float:
        return self._volume

    @volume.setter
    def volume(self, value: float) -> None:
        if not 0.0 <= value <= 1.0:
            raise ValueError(f"volume must be 0.0–1.0, got {value}")
        self._volume = value
        logger.debug(f"AudioPlayer volume set to {value}")

    @property
    def is_playing(self) -> bool:
        with self._lock:
            return (
                self._current_handle is not None
                and not self._current_handle.is_done
            )

    def play(
        self,
        path: Union[str, Path],
        volume: Optional[float] = None,
        block: bool = True,
    ) -> PlaybackHandle:
        """
        Play a WAV file through the PC speaker.

        Args:
            path:   Absolute or relative path to the .wav file.
            volume: Per-call override (uses instance volume if None).
            block:  If True, this call blocks until playback finishes.
                    If False, playback runs on a background thread.

        Returns:
            PlaybackHandle — use .wait() for non-blocking callers.

        Raises:
            AudioFileNotFoundError  if the file doesn't exist.
            AudioFileInvalidError   if the file is empty or malformed.
            AudioDeviceError        if the output device fails to open.
            AudioPlaybackError      on runtime playback errors.
        """
        effective_volume = volume if volume is not None else self._volume
        path = Path(path).resolve()

        # Validate before starting anything
        info = inspect_wav(path)  # raises AudioFileNotFoundError / Invalid
        logger.info(f"Playing: {info}")

        # Ensure backend is available (deferred from __init__)
        if self._backend is None:
            self._backend = self._detect_backend()  # raises AudioDeviceError if none

        # Stop any currently playing audio
        self.stop()

        self._stop_flag.clear()
        handle = PlaybackHandle(
            file_name=path.name,
            duration_seconds=info.duration_seconds,
            backend=self._backend,
        )

        with self._lock:
            self._current_handle = handle

        if block:
            self._play_blocking(path, effective_volume, handle)
        else:
            t = threading.Thread(
                target=self._play_blocking,
                args=(path, effective_volume, handle),
                daemon=True,
                name=f"audio-{path.stem}",
            )
            t.start()

        return handle

    def stop(self) -> None:
        """
        Stop current playback immediately.
        Non-blocking — returns before the audio thread fully exits.
        """
        self._stop_flag.set()
        with self._lock:
            if self._current_handle and not self._current_handle.is_done:
                logger.debug(f"Stopping playback: {self._current_handle.file_name}")

    def wait_until_done(self, timeout: Optional[float] = None) -> bool:
        """
        Block the calling thread until playback completes.

        Returns:
            True if done, False if timed out.
        """
        with self._lock:
            handle = self._current_handle
        if handle is None:
            return True
        return handle.wait(timeout=timeout)

    # ── Device listing ────────────────────────────────────────────────────────

    @staticmethod
    def list_output_devices() -> list[dict]:
        """
        Return available output devices.
        Returns empty list if sounddevice is not installed.
        """
        try:
            import sounddevice as sd
            devices = sd.query_devices()
            default_index = None
            try:
                _, default_index = sd.default.device
            except Exception:
                default_index = None

            result = []
            for idx, dev in enumerate(devices):
                if dev["max_output_channels"] > 0:
                    result.append({
                        "index": idx,
                        "name": dev["name"],
                        "channels": dev["max_output_channels"],
                        "sample_rate": int(dev["default_samplerate"]),
                        "is_default": idx == default_index,
                    })
            return result
        except ImportError:
            return []
        except Exception as exc:
            logger.warning(f"Failed to list audio devices: {exc}")
            return []

    @staticmethod
    def _log_audio_diagnostics(
        file_name: str,
        samples: np.ndarray,
        sample_rate: int,
        volume: float,
    ) -> None:
        rms = compute_rms(samples)
        dbfs = rms_to_dbfs(rms)
        duration = len(samples) / sample_rate if sample_rate > 0 else 0.0
        logger.info(
            f"Audio diagnostics: file={file_name} "
            f"duration={duration:.2f}s "
            f"sample_rate={sample_rate}Hz "
            f"rms={rms:.4f} "
            f"dbfs={dbfs:.1f}dBFS "
            f"volume={volume:.2f}"
        )

    @staticmethod
    def _choose_best_output_device() -> Optional[int]:
        """Choose the best available sounddevice output device automatically."""
        devices = AudioPlayer.list_output_devices()
        if not devices:
            return None

        speaker_candidates = [
            d for d in devices
            if any(token in d["name"].lower() for token in (
                "speaker", "playback", "analog", "usb", "hdmi", "default"
            ))
        ]
        if speaker_candidates:
            speaker_candidates.sort(
                key=lambda d: (
                    d["is_default"],
                    d["channels"],
                    d["sample_rate"],
                ),
                reverse=True,
            )
            best = speaker_candidates[0]
            logger.debug(f"Auto-selected output device: {best}")
            return best["index"]

        devices.sort(
            key=lambda d: (d["channels"], d["sample_rate"], d["is_default"]),
            reverse=True,
        )
        best = devices[0]
        logger.debug(f"Auto-selected output device: {best}")
        return best["index"]

    @staticmethod
    def maximize_system_volume() -> None:
        """Attempt to boost Linux speaker volume to 100% using available tools."""
        commands = [
            ["pactl", "set-sink-volume", "@DEFAULT_SINK@", "100%"],
            ["pactl", "set-sink-mute", "@DEFAULT_SINK@", "0"],
            ["amixer", "set", "Master", "100%"],
        ]
        for cmd in commands:
            try:
                subprocess.run(cmd, check=True, capture_output=True)
                logger.info(f"System volume set with: {' '.join(cmd)}")
                return
            except FileNotFoundError:
                continue
            except subprocess.CalledProcessError as exc:
                logger.warning(
                    f"System volume command failed: {' '.join(cmd)} — {exc.stderr.decode('utf-8', errors='ignore') if exc.stderr else exc}"
                )
                continue
        logger.debug("No Linux system volume booster available (pactl/amixer missing or failed)")

    # ── Internal playback ─────────────────────────────────────────────────────

    def _play_blocking(
        self,
        path: Path,
        volume: float,
        handle: PlaybackHandle,
    ) -> None:
        """
        Internal: load file, apply volume, send to active backend.
        Always calls handle._mark_done() — even on error.
        """
        AudioPlayer.maximize_system_volume()
        try:
            if self._backend == Backend.SOUNDDEVICE:
                self._play_sounddevice(path, volume)
            else:
                self._play_pydub(path, volume)

            if not self._stop_flag.is_set():
                logger.info(f"Playback complete: {path.name}")
            else:
                logger.debug(f"Playback stopped early: {path.name}")

            handle._mark_done()

        except (AudioFileNotFoundError, AudioFileInvalidError) as exc:
            logger.error(f"Cannot play {path.name}: {exc}")
            handle._mark_done(error=exc)
            raise

        except Exception as exc:
            err = AudioPlaybackError(f"Playback failed for '{path.name}': {exc}")
            logger.error(str(err))
            handle._mark_done(error=err)
            raise err from exc

    def _play_sounddevice(self, path: Path, volume: float) -> None:
        """Play through sounddevice using numpy stream (blocking)."""
        import sounddevice as sd

        samples, sample_rate = prepare_playback_audio(path, volume=volume)
        self._log_audio_diagnostics(path.name, samples, sample_rate, volume)

        # sounddevice expects shape (frames,) for mono or (frames, 2) for stereo
        kwargs: dict = {
            "samplerate": sample_rate,
            "blocking": False,  # we handle blocking manually to support stop_flag
        }
        if self._output_device is not None:
            kwargs["device"] = self._output_device

        try:
            sd.play(samples, **kwargs)
        except sd.PortAudioError as exc:
            raise AudioDeviceError(
                f"sounddevice failed to open output device: {exc}"
            ) from exc

        # Poll until done or stop requested
        poll = 0.05  # seconds
        try:
            while sd.get_stream().active:
                if self._stop_flag.is_set():
                    sd.stop()
                    return
                time.sleep(poll)
        except Exception as exc:
            raise AudioPlaybackError(f"sounddevice playback failed: {exc}") from exc

    def _play_pydub(self, path: Path, volume: float) -> None:
        """Play through pydub (blocking). Volume applied via dBFS gain."""
        from pydub import AudioSegment
        from pydub.playback import _play_with_simpleaudio

        seg = AudioSegment.from_wav(str(path))
        seg = seg.set_channels(1)
        seg = seg.normalize()
        seg = seg.apply_gain(+6.0)

        if volume < 0.001:
            return  # effectively silent, skip playback
        import math
        db_change = 20 * math.log10(volume)
        seg = seg + db_change

        logger.debug(
            f"pydub fallback playback: {path.name} volume={volume:.2f}dB change={db_change:.2f}"
        )

        try:
            play_obj = _play_with_simpleaudio(seg)
        except Exception as exc:
            raise AudioDeviceError(f"pydub/simpleaudio failed to start: {exc}") from exc

        while play_obj.is_playing():
            if self._stop_flag.is_set():
                play_obj.stop()
                return
            time.sleep(0.05)
