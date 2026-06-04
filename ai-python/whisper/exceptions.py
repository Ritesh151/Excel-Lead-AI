"""
whisper/exceptions.py
Typed exception hierarchy for the Whisper transcription subsystem.
"""


class WhisperBaseError(Exception):
    """Root for all Whisper subsystem errors."""


class WhisperNotInstalledError(WhisperBaseError):
    """faster-whisper package is not installed."""


class WhisperModelLoadError(WhisperBaseError):
    """The model weights could not be loaded (network, disk, or config error)."""


class WhisperAudioError(WhisperBaseError):
    """The audio input is missing, empty, or in an unsupported format."""


class WhisperTranscriptionError(WhisperBaseError):
    """An error occurred during the inference pass itself."""
