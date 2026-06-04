"""
adb package
Android ADB calling subsystem.

Public surface:
    from adb import AdbManager, CallController, make_controller
    from adb import get_device, auto_select_device
    from adb.models import CallConfig, CallResult, CallOutcome, CallState, RetryConfig
    from adb.exceptions import *
"""

from adb.adb_manager import AdbManager
from adb.call_controller import CallController, make_controller
from adb.device_checker import get_device, auto_select_device, get_connected_devices
from adb.models import (
    CallConfig,
    CallOutcome,
    CallResult,
    CallState,
    DeviceInfo,
    DeviceState,
    RetryConfig,
)

__all__ = [
    # Manager
    "AdbManager",
    # Controller
    "CallController",
    "make_controller",
    # Device
    "get_device",
    "auto_select_device",
    "get_connected_devices",
    # Models
    "CallConfig",
    "CallOutcome",
    "CallResult",
    "CallState",
    "DeviceInfo",
    "DeviceState",
    "RetryConfig",
]
