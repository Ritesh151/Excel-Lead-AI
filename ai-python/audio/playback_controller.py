"""
audio/playback_controller.py
High-level conversation audio controller for live SIM calls.

This is the ONLY class the calling logic talks to for audio.
It knows about the conversation script and audio file names.
It does NOT transcribe, does NOT touch MongoDB, does NOT use ADB.

Conversation flow:
    1. play_greeting()     → plays greeting.wav, blocks until done
    2. (caller responds — transcription happens outside this module)
    3. play_thank_you()    → plays thank_you.wav / closing.wav on YES
       OR
       silence (NO → caller hangs up via ADB)

Public API:
    PlaybackController.play_greeting()   → PlaybackResult
    PlaybackController.play_thank_you()  → PlaybackResult
    PlaybackController.play_file(name)   → PlaybackResult
    PlaybackController.stop()
"""

from __future__ import annotations

import time
from dataclasses import dataclass
from enum import Enum, auto
from pathlib import Path
from typing import Optional, Union

from logger import logger
from config import settings
from audio.audio_player import AudioPlayer
from audio.utils import inspect_wav
from audio.exceptions import AudioBaseError, AudioFileNotFoundError, AudioFileInvalidError


# ─── Result types ─────────────────────────────────────────────────────────────

class PlaybackStatus(Enum):
    SUCCESS   = auto()   # played to completion
    STOPPED   = auto()   # stopped early by caller
    SKIPPED   = auto()   # file missing/empty — logged but did not crash
    ERROR     = auto()   # unexpected playback error


@dataclass
class PlaybackResult:
    """Returned by every PlaybackController.play_*() call."""
    file_name: str
    status: PlaybackStatus
    duration_seconds: float = 0.0
    error_message: Optional[str] = None

    @property
    def succeeded(self) -> bool:
        return self.status == PlaybackStatus.SUCCESS

    def __str__(self) -> str:
        return (
            f"PlaybackResult({self.file_name}: "
            f"{self.status.name} {self.duration_seconds:.2f}s"
            + (f" — {self.error_message}" if self.error_message else "")
            + ")"
        )


# ─── File registry ────────────────────────────────────────────────────────────

class AudioFiles:
    """
    Centralised registry mapping logical names → file names.
    Change file names here without touching any other module.
    """
    GREETING   = "greeting.wav"
    THANK_YOU  = "thank_you.wav"   # primary name
    CLOSING    = "closing.wav"     # fallback alias for thank_you
    QUESTION_1 = "question_1.wav"
    QUESTION_2 = "question_2.wav"


# ─── PlaybackController ───────────────────────────────────────────────────────

class PlaybackController:
    """
    Conversation-aware audio controller.

    Args:
        audio_dir:     Directory that contains the WAV files.
        volume:        Master volume 0.0–1.0.
        output_device: sounddevice device index (-1 = default).
        strict:        If True, raise on missing/empty files.
                       If False (default), log a warning and skip.
    """

    def __init__(
        self,
        audio_dir: Optional[Union[str, Path]] = None,
        volume: Optional[float] = None,
        output_device: Optional[int] = None,
        strict: bool = False,
    ) -> None:
        self._audio_dir = Path(
            audio_dir or settings.audio_dir
        ).resolve()

        _volume = volume if volume is not None else float(settings.audio_volume)
        _device = (
            output_device
            if output_device is not None
            else int(settings.audio_output_device)
        )

        self._player = AudioPlayer(volume=_volume, output_device=_device)
        self._strict = strict

        logger.info(
            f"PlaybackController ready — "
            f"audio_dir={self._audio_dir} "
            f"volume={_volume} "
            f"device={_device}"
        )

    # ── Volume control ────────────────────────────────────────────────────────

    @property
    def volume(self) -> float:
        return self._player.volume

    @volume.setter
    def volume(self, value: float) -> None:
        self._player.volume = value

    # ── Conversation steps ────────────────────────────────────────────────────

    def play_greeting(self) -> PlaybackResult:
        """
        Play the greeting prompt and BLOCK until it finishes.

        Audio: greeting.wav
        Text : "Hello, I'm Ritesh Gajjar from Opti Matrix,
                are you looking for any IT Services?"

        Returns:
            PlaybackResult — check .succeeded before proceeding.
        """
        logger.info("▶ Playing GREETING")
        return self._play_file_safe(AudioFiles.GREETING, block=True)

    def play_thank_you(self) -> PlaybackResult:
        """
        Play the YES-response acknowledgement and BLOCK until done.

        Audio: thank_you.wav (falls back to closing.wav if not found)
        Text : "Okk, Thank you"

        Returns:
            PlaybackResult
        """
        logger.info("▶ Playing THANK YOU")
        # Try thank_you.wav first; fall back to closing.wav
        result = self._play_file_safe(AudioFiles.THANK_YOU, block=True)
        if result.status == PlaybackStatus.SKIPPED:
            logger.debug(
                "thank_you.wav not available, trying closing.wav as fallback"
            )
            result = self._play_file_safe(AudioFiles.CLOSING, block=True)
        return result

    def play_question(self, number: int = 1) -> PlaybackResult:
        """
        Play a numbered question prompt.

        Args:
            number: 1 or 2

        Returns:
            PlaybackResult
        """
        filename = AudioFiles.QUESTION_1 if number == 1 else AudioFiles.QUESTION_2
        logger.info(f"▶ Playing QUESTION {number}")
        return self._play_file_safe(filename, block=True)

    def play_file(
        self,
        filename: str,
        block: bool = True,
        volume: Optional[float] = None,
    ) -> PlaybackResult:
        """
        Play any WAV file from the audio directory by filename.

        Args:
            filename: Just the filename, e.g. "greeting.wav"
            block:    Block until done (True) or return immediately (False).
            volume:   Per-call volume override.

        Returns:
            PlaybackResult
        """
        return self._play_file_safe(filename, block=block, volume=volume)

    def stop(self) -> None:
        """Stop any currently playing audio immediately."""
        self._player.stop()
        logger.debug("Playback stopped by PlaybackController.stop()")

    @property
    def is_playing(self) -> bool:
        return self._player.is_playing

    # ── Device listing ────────────────────────────────────────────────────────

    @staticmethod
    def list_output_devices() -> list[dict]:
        """Return available output devices (sounddevice only)."""
        return AudioPlayer.list_output_devices()

    # ── Internal ──────────────────────────────────────────────────────────────

    def _resolve_path(self, filename: str) -> Path:
        return self._audio_dir / filename

    def _play_file_safe(
        self,
        filename: str,
        block: bool = True,
        volume: Optional[float] = None,
    ) -> PlaybackResult:
        """
        Play `filename` with full error handling.

        In non-strict mode (default), missing / empty files return
        PlaybackStatus.SKIPPED instead of raising, so a missing file
        never crashes an active call.
        """
        path = self._resolve_path(filename)
        start = time.monotonic()

        # ── Pre-flight validation ────────────────────────────────────────────
        try:
            info = inspect_wav(path)
        except AudioFileNotFoundError as exc:
            msg = str(exc)
            logger.warning(f"Audio file not found — {msg}")
            if self._strict:
                raise
            return PlaybackResult(
                file_name=filename,
                status=PlaybackStatus.SKIPPED,
                error_message=msg,
            )
        except AudioFileInvalidError as exc:
            msg = str(exc)
            logger.warning(f"Audio file invalid — {msg}")
            if self._strict:
                raise
            return PlaybackResult(
                file_name=filename,
                status=PlaybackStatus.SKIPPED,
                error_message=msg,
            )

        # ── Playback ─────────────────────────────────────────────────────────
        if self._player._output_device is not None:
            logger.debug(f"Using audio output device index: {self._player._output_device}")
        else:
            logger.debug("Using default system audio output device")

        try:
            handle = self._player.play(path, volume=volume, block=block)
        except AudioBaseError as exc:
            msg = str(exc)
            logger.error(f"Playback error for {filename}: {msg}")
            if self._strict:
                raise
            return PlaybackResult(
                file_name=filename,
                status=PlaybackStatus.ERROR,
                duration_seconds=time.monotonic() - start,
                error_message=msg,
            )

        # For blocking calls the handle is already done; for non-blocking
        # it may still be in flight — duration reflects wall time so far.
        duration = time.monotonic() - start

        if handle.error:
            return PlaybackResult(
                file_name=filename,
                status=PlaybackStatus.ERROR,
                duration_seconds=duration,
                error_message=str(handle.error),
            )

        status = PlaybackStatus.SUCCESS if handle.is_done else PlaybackStatus.SUCCESS
        result = PlaybackResult(
            file_name=filename,
            status=status,
            duration_seconds=duration,
        )
        logger.debug(str(result))
        return result
