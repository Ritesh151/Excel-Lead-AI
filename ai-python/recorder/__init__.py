"""
recorder package
Caller voice recording subsystem.

Public surface:
    from recorder import RecordingManager, Recorder
    from recorder import RecordingConfig, RecordingResult
    from recorder.exceptions import RecorderBaseError, ...
"""

from recorder.recorder import Recorder, DeviceInfo
from recorder.recording_manager import RecordingManager
from recorder.models import RecordingConfig, RecordingResult

__all__ = [
    "RecordingManager",   # use this in calling logic
    "Recorder",           # low-level access if needed
    "RecordingConfig",
    "RecordingResult",
    "DeviceInfo",
]
