"""
transcription package
Speech-to-text + YES/NO intent detection pipeline.

Public surface:
    from transcription import TranscriptionService, TranscriptionResult
    from transcription import detect_intent, Intent, DetectionResult
"""

from transcription.transcription_service import TranscriptionService, TranscriptionResult
from transcription.intent_detector import detect_intent, Intent, DetectionResult

__all__ = [
    "TranscriptionService",
    "TranscriptionResult",
    "detect_intent",
    "Intent",
    "DetectionResult",
]
