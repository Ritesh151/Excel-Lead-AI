"""
audio/exceptions.py
Typed exception hierarchy for the audio playback subsystem.
"""


class AudioBaseError(Exception):
    """Root for all audio subsystem errors."""


class AudioFileNotFoundError(AudioBaseError):
    """WAV file path does not exist."""


class AudioFileInvalidError(AudioBaseError):
    """File exists but is not a valid / readable WAV file."""


class AudioDeviceError(AudioBaseError):
    """Playback device could not be opened or is unavailable."""


class AudioPlaybackError(AudioBaseError):
    """An error occurred during playback itself."""


class AudioVolumeError(AudioBaseError):
    """Volume value outside the allowed 0.0–1.0 range."""
