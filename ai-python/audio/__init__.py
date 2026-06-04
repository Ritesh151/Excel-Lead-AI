"""
audio package
WAV playback subsystem for live phone call audio.

Public surface:
    from audio import PlaybackController, AudioPlayer
    from audio import PlaybackResult, PlaybackStatus, AudioFiles
    from audio.exceptions import AudioBaseError, ...
"""

from audio.audio_player import AudioPlayer, PlaybackHandle, Backend
from audio.playback_controller import (
    PlaybackController,
    PlaybackResult,
    PlaybackStatus,
    AudioFiles,
)

__all__ = [
    # High-level
    "PlaybackController",
    "PlaybackResult",
    "PlaybackStatus",
    "AudioFiles",
    # Low-level
    "AudioPlayer",
    "PlaybackHandle",
    "Backend",
]
