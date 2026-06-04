"""
recorder/exceptions.py
Typed exception hierarchy for the recording subsystem.
Having a typed hierarchy lets callers catch exactly what they need
without parsing error message strings.
"""


class RecorderBaseError(Exception):
    """Root for all recording subsystem errors."""


# ── Device errors ─────────────────────────────────────────────────────────────

class MicrophoneNotFoundError(RecorderBaseError):
    """No suitable input device could be found or opened."""


class MicrophonePermissionError(RecorderBaseError):
    """The OS denied access to the microphone."""


class MicrophoneUnavailableError(RecorderBaseError):
    """The microphone exists but could not be opened (in use, driver error, etc.)."""


# ── Recording errors ──────────────────────────────────────────────────────────

class RecordingError(RecorderBaseError):
    """An error occurred during the recording capture itself."""


class EmptyRecordingError(RecorderBaseError):
    """Recording completed but contained only silence / no audio data."""


class RecordingTooShortError(RecorderBaseError):
    """Recorded fewer frames than the minimum acceptable duration."""


# ── File errors ───────────────────────────────────────────────────────────────

class RecordingFileError(RecorderBaseError):
    """Could not create or write the WAV output file."""


class RecordingDirectoryError(RecorderBaseError):
    """The recordings directory does not exist and could not be created."""


# ── Config errors ─────────────────────────────────────────────────────────────

class RecordingConfigError(RecorderBaseError):
    """Invalid recorder configuration (bad sample rate, duration, etc.)."""
