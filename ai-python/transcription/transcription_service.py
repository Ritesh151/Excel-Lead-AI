from __future__ import annotations

import time
from dataclasses import dataclass
from pathlib import Path
from typing import Optional, Union

import numpy as np

from logger import logger
from whisper.whisper_engine import WhisperEngine, TranscriptionOutput
from whisper.exceptions import WhisperBaseError, WhisperAudioError
from transcription.intent_detector import detect_intent, DetectionResult, Intent


@dataclass
class TranscriptionResult:
    transcription: str
    intent: Intent
    confidence: float
    matched_keyword: Optional[str]
    language: str
    audio_duration: float
    inference_ms: float
    success: bool = True
    error_message: Optional[str] = None

    @property
    def is_yes(self) -> bool:
        return self.intent == Intent.YES

    @property
    def is_no(self) -> bool:
        return self.intent == Intent.NO

    @property
    def is_unknown(self) -> bool:
        return self.intent == Intent.UNKNOWN

    def to_dict(self) -> dict:
        return {
            "transcription": self.transcription,
            "intent": self.intent.value,
            "confidence": round(self.confidence, 3),
            "matched_keyword": self.matched_keyword,
            "language": self.language,
            "audio_duration": round(self.audio_duration, 2),
            "inference_ms": round(self.inference_ms, 1),
            "success": self.success,
            "error_message": self.error_message,
        }

    def __str__(self) -> str:
        status = "OK" if self.success else "FAILED"
        return (
            f'TranscriptionResult({status} | '
            f'intent={self.intent.value} '
            f'conf={self.confidence:.2f} | '
            f'lang={self.language} | '
            f'"{self.transcription[:60]}")'
        )


class TranscriptionService:

    def __init__(self, engine: Optional[WhisperEngine] = None) -> None:
        self._engine = engine or WhisperEngine()
        logger.info(
            f"TranscriptionService ready — "
            f"model={self._engine._model_name} "
            f"device={self._engine._device}"
        )

    @property
    def engine(self) -> WhisperEngine:
        return self._engine

    def warm_up(self) -> None:
        logger.info("Warming up Whisper model …")
        t = time.monotonic()
        self._engine.load()
        logger.info(f"Whisper model warm-up complete in {(time.monotonic()-t)*1000:.0f}ms")

    def transcribe(
        self,
        audio: Union[str, Path, np.ndarray],
    ) -> TranscriptionResult:
        start = time.monotonic()

        try:
            whisper_out: TranscriptionOutput = self._engine.transcribe(audio)
        except WhisperAudioError as exc:
            logger.warning(f"Audio problem: {exc}")
            return self._failure(str(exc), start)
        except WhisperBaseError as exc:
            logger.error(f"Whisper error: {exc}")
            return self._failure(str(exc), start)
        except Exception as exc:
            logger.error(f"Unexpected transcription error: {exc}")
            return self._failure(f"Unexpected error: {exc}", start)

        raw_text = whisper_out.text.strip()

        if not raw_text:
            logger.warning("Whisper returned empty transcription")
            return TranscriptionResult(
                transcription="",
                intent=Intent.UNKNOWN,
                confidence=0.0,
                matched_keyword=None,
                language=whisper_out.language,
                audio_duration=whisper_out.audio_duration,
                inference_ms=whisper_out.duration_ms,
                success=True,
                error_message="Empty transcription — no speech detected",
            )

        detection: DetectionResult = detect_intent(raw_text)
        total_ms = (time.monotonic() - start) * 1000

        result = TranscriptionResult(
            transcription=raw_text,
            intent=detection.intent,
            confidence=detection.confidence,
            matched_keyword=detection.matched_keyword,
            language=whisper_out.language,
            audio_duration=whisper_out.audio_duration,
            inference_ms=total_ms,
            success=True,
        )

        logger.info(
            f"Transcription result — "
            f'text="{raw_text[:60]}" | '
            f"intent={detection.intent.value} "
            f"conf={detection.confidence:.2f} "
            f"match={detection.matched_keyword!r} "
            f"lang={whisper_out.language} "
            f"time={total_ms:.0f}ms"
        )

        return result

    def transcribe_file(self, path: Union[str, Path]) -> TranscriptionResult:
        path = Path(path)
        if not path.exists():
            return self._failure(f"WAV file not found: {path}", time.monotonic())
        if path.stat().st_size == 0:
            return self._failure(f"WAV file is empty: {path}", time.monotonic())
        return self.transcribe(path)

    @staticmethod
    def _failure(message: str, start: float) -> TranscriptionResult:
        logger.error(f"Transcription failed: {message}")
        return TranscriptionResult(
            transcription="",
            intent=Intent.UNKNOWN,
            confidence=0.0,
            matched_keyword=None,
            language="unknown",
            audio_duration=0.0,
            inference_ms=(time.monotonic() - start) * 1000,
            success=False,
            error_message=message,
        )
