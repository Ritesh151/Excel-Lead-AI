from __future__ import annotations

import time
import wave
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional, Union

import numpy as np

from logger import logger
from config import settings
from whisper.exceptions import (
    WhisperNotInstalledError,
    WhisperModelLoadError,
    WhisperTranscriptionError,
    WhisperAudioError,
)


@dataclass
class TranscriptionSegment:
    start: float
    end: float
    text: str
    avg_logprob: float
    no_speech_prob: float


@dataclass
class TranscriptionOutput:
    text: str
    language: str
    segments: list[TranscriptionSegment] = field(default_factory=list)
    duration_ms: float = 0.0
    audio_duration: float = 0.0
    model_name: str = ""

    def to_dict(self) -> dict:
        return {
            "text": self.text,
            "language": self.language,
            "duration_ms": round(self.duration_ms, 1),
            "audio_duration": round(self.audio_duration, 2),
            "model": self.model_name,
            "segments": [
                {
                    "start": round(s.start, 2),
                    "end": round(s.end, 2),
                    "text": s.text.strip(),
                    "conf": round(1.0 - s.no_speech_prob, 3),
                }
                for s in self.segments
            ],
        }

    def __str__(self) -> str:
        return (
            f'TranscriptionOutput(lang={self.language} '
            f'time={self.duration_ms:.0f}ms '
            f'text="{self.text[:80]}")'
        )


class WhisperEngine:

    def __init__(
        self,
        model_name: Optional[str] = None,
        device: Optional[str] = None,
        compute_type: Optional[str] = None,
        language: Optional[str] = None,
        beam_size: Optional[int] = None,
        vad_filter: Optional[bool] = None,
    ) -> None:
        self._model_name = model_name or settings.whisper_model
        self._device = device or settings.whisper_device
        self._compute_type = compute_type or settings.whisper_compute_type
        self._language = language or (settings.whisper_language or None)
        self._beam_size = beam_size if beam_size is not None else settings.whisper_beam_size
        self._vad_filter = vad_filter if vad_filter is not None else settings.whisper_vad_filter
        self._model = None

        logger.debug(
            f"WhisperEngine — model={self._model_name} device={self._device} "
            f"compute={self._compute_type} lang={self._language} "
            f"beam={self._beam_size} vad={self._vad_filter}"
        )

    @property
    def is_ready(self) -> bool:
        return self._model is not None

    @property
    def model_info(self) -> dict:
        return {
            "model_name": self._model_name,
            "device": self._device,
            "compute_type": self._compute_type,
            "language": self._language,
            "beam_size": self._beam_size,
            "vad_filter": self._vad_filter,
            "loaded": self.is_ready,
        }

    def load(self) -> None:
        if self._model is not None:
            return

        fw = self._import_faster_whisper()

        logger.info(f"Loading Whisper '{self._model_name}' on {self._device} ({self._compute_type}) …")
        t0 = time.monotonic()
        try:
            self._model = fw.WhisperModel(
                model_size_or_path=self._model_name,
                device=self._device,
                compute_type=self._compute_type,
            )
        except Exception as exc:
            raise WhisperModelLoadError(
                f"Failed to load Whisper model '{self._model_name}': {exc}"
            ) from exc

        elapsed = (time.monotonic() - t0) * 1000
        logger.info(f"Whisper model loaded in {elapsed:.0f}ms")

    def transcribe(
        self,
        audio: Union[str, Path, np.ndarray],
        language: Optional[str] = None,
    ) -> TranscriptionOutput:
        self.load()
        audio_f32, audio_duration = self._prepare_audio(audio)
        lang = language or self._language

        logger.info(
            f"Transcribing {audio_duration:.2f}s audio "
            f"(lang={lang or 'auto'}, beam={self._beam_size}, vad={self._vad_filter})"
        )

        t0 = time.monotonic()
        try:
            segments_iter, info = self._model.transcribe(
                audio_f32,
                language=lang,
                beam_size=self._beam_size,
                vad_filter=self._vad_filter,
                word_timestamps=False,
                condition_on_previous_text=False,
            )
            segments = list(segments_iter)
        except Exception as exc:
            raise WhisperTranscriptionError(
                f"Faster-Whisper inference failed: {exc}"
            ) from exc

        duration_ms = (time.monotonic() - t0) * 1000

        seg_objects = [
            TranscriptionSegment(
                start=s.start,
                end=s.end,
                text=s.text,
                avg_logprob=s.avg_logprob,
                no_speech_prob=s.no_speech_prob,
            )
            for s in segments
        ]

        full_text = " ".join(s.text.strip() for s in segments if s.text.strip())

        output = TranscriptionOutput(
            text=full_text.strip(),
            language=info.language,
            segments=seg_objects,
            duration_ms=duration_ms,
            audio_duration=audio_duration,
            model_name=self._model_name,
        )

        logger.info(
            f"Transcription done — {duration_ms:.0f}ms | "
            f"lang={output.language} | text=\"{output.text[:80]}\""
        )

        return output

    def _prepare_audio(
        self,
        audio: Union[str, Path, np.ndarray],
    ) -> tuple[np.ndarray, float]:
        if isinstance(audio, (str, Path)):
            return self._load_wav_file(Path(audio))
        if isinstance(audio, np.ndarray):
            return self._validate_array(audio)
        raise WhisperAudioError(f"audio must be path or numpy array, got {type(audio)}")

    @staticmethod
    def _load_wav_file(path: Path) -> tuple[np.ndarray, float]:
        if not path.exists():
            raise WhisperAudioError(f"WAV file not found: {path}")
        if path.stat().st_size == 0:
            raise WhisperAudioError(f"WAV file is empty: {path}")

        try:
            with wave.open(str(path), "rb") as wf:
                n_channels = wf.getnchannels()
                sample_width = wf.getsampwidth()
                frame_rate = wf.getframerate()
                n_frames = wf.getnframes()
                raw = wf.readframes(n_frames)
        except wave.Error as exc:
            raise WhisperAudioError(f"Cannot read WAV '{path}': {exc}") from exc

        dtype_map = {1: np.int8, 2: np.int16, 4: np.int32}
        np_dtype = dtype_map.get(sample_width, np.int16)
        samples = np.frombuffer(raw, dtype=np_dtype).copy().astype(np.float32)

        max_val = float(np.iinfo(np_dtype).max)
        samples /= max_val

        if n_channels > 1:
            samples = samples.reshape(-1, n_channels).mean(axis=1)

        if frame_rate != 16_000:
            logger.debug(f"Resampling {frame_rate}Hz -> 16000Hz")
            target_len = int(len(samples) * 16_000 / frame_rate)
            samples = np.interp(
                np.linspace(0, len(samples) - 1, target_len),
                np.arange(len(samples)),
                samples,
            ).astype(np.float32)

        duration = len(samples) / 16_000
        return samples, duration

    @staticmethod
    def _validate_array(arr: np.ndarray) -> tuple[np.ndarray, float]:
        if arr.size == 0:
            raise WhisperAudioError("Audio array is empty")
        if arr.ndim == 2:
            arr = arr.mean(axis=1)
        arr = arr.astype(np.float32)
        if arr.max() > 1.0 or arr.min() < -1.0:
            max_abs = np.abs(arr).max()
            if max_abs > 0:
                arr /= max_abs
        duration = len(arr) / 16_000
        return arr, duration

    @staticmethod
    def _import_faster_whisper():
        try:
            import faster_whisper
            return faster_whisper
        except ImportError as exc:
            raise WhisperNotInstalledError(
                "faster-whisper is not installed. Run: pip install faster-whisper"
            ) from exc
