"""
whisper package
Faster-Whisper model wrapper.

Public surface:
    from whisper import WhisperEngine, TranscriptionOutput
    from whisper.exceptions import WhisperBaseError, ...
"""

from whisper.whisper_engine import WhisperEngine, TranscriptionOutput, TranscriptionSegment
from whisper.exceptions import (
    WhisperBaseError,
    WhisperNotInstalledError,
    WhisperModelLoadError,
    WhisperAudioError,
    WhisperTranscriptionError,
)

__all__ = [
    "WhisperEngine",
    "TranscriptionOutput",
    "TranscriptionSegment",
    "WhisperBaseError",
    "WhisperNotInstalledError",
    "WhisperModelLoadError",
    "WhisperAudioError",
    "WhisperTranscriptionError",
]
