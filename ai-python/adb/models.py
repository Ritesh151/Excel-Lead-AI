"""
adb/models.py
Data models and enums for the ADB calling subsystem.
No I/O — pure data containers.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Optional


# ─── Enums ────────────────────────────────────────────────────────────────────

class DeviceState(str, Enum):
    """Possible states returned by `adb devices`."""
    ONLINE      = "device"       # fully authorised and ready
    OFFLINE     = "offline"      # USB connected but not responding
    UNAUTHORIZED = "unauthorized" # connected but RSA key not approved
    NO_DEVICE   = "no_device"    # serial not in device list at all


class CallState(str, Enum):
    """
    Call lifecycle states polled from the Android telephony stack.

    Mapped from `dumpsys telephony.registry` CALL_STATE_* constants:
        0 → IDLE
        1 → RINGING
        2 → OFFHOOK  (covers both CALLING and CONNECTED)

    We extend with semantic states derived from transitions:
        CALLING    — we dialled, device is OFFHOOK but not yet answered
        RINGING    — remote end is ringing (CALL_STATE_RINGING on originator)
        CONNECTED  — call answered
        ENDED      — call terminated (back to IDLE after being non-IDLE)
        FAILED     — could not place the call at all
        UNKNOWN    — unable to determine state
    """
    IDLE        = "IDLE"
    CALLING     = "CALLING"
    RINGING     = "RINGING"
    CONNECTED   = "CONNECTED"
    ENDED       = "ENDED"
    FAILED      = "FAILED"
    UNKNOWN     = "UNKNOWN"


class CallOutcome(str, Enum):
    """Final result of a completed call attempt."""
    CONNECTED       = "connected"    # call was answered
    NOT_ANSWERED    = "not_answered" # rang but was not picked up / timed out
    REJECTED        = "rejected"     # actively declined by recipient
    FAILED          = "failed"       # ADB / device error prevented the call
    INVALID_NUMBER  = "invalid_number"
    DEVICE_ERROR    = "device_error"
    TIMEOUT         = "timeout"


# ─── Device ───────────────────────────────────────────────────────────────────

@dataclass
class DeviceInfo:
    """Represents a single ADB-connected device."""
    serial: str
    state: DeviceState
    model: str = ""        # populated by device_checker if available
    android_version: str = ""

    @property
    def is_ready(self) -> bool:
        return self.state == DeviceState.ONLINE


# ─── Call Result ──────────────────────────────────────────────────────────────

@dataclass
class CallResult:
    """
    Full record of a single call attempt.
    Returned by CallController.call().
    """
    phone: str
    outcome: CallOutcome
    final_state: CallState
    duration_seconds: float = 0.0
    attempt_number: int = 1         # which retry attempt (1-based)
    error_message: Optional[str] = None

    def to_dict(self) -> dict:
        return {
            "phone": self.phone,
            "outcome": self.outcome.value,
            "final_state": self.final_state.value,
            "duration_seconds": round(self.duration_seconds, 2),
            "attempt_number": self.attempt_number,
            "error_message": self.error_message,
        }


# ─── Retry Config ─────────────────────────────────────────────────────────────

@dataclass
class RetryConfig:
    """Retry behaviour for failed call attempts."""
    max_attempts: int = 3           # total attempts including the first
    delay_between_attempts: float = 5.0   # seconds to wait before retry
    # Outcomes that are worth retrying
    retryable_outcomes: tuple = field(default_factory=lambda: (
        CallOutcome.FAILED,
        CallOutcome.DEVICE_ERROR,
        CallOutcome.TIMEOUT,
    ))


# ─── Call Config ──────────────────────────────────────────────────────────────

@dataclass
class CallConfig:
    """Tunable parameters for a calling session."""
    dial_timeout: float = 8.0        # seconds to wait for OFFHOOK after dialling
    ring_timeout: float = 45.0       # max seconds to wait for answer while ringing
    connected_timeout: float = 120.0 # max seconds to stay on a connected call
    poll_interval: float = 1.0       # seconds between telephony state polls
    hangup_delay: float = 1.5        # seconds after action before hanging up
    between_calls_delay: float = 3.0 # seconds to pause between sequential calls
    retry: RetryConfig = field(default_factory=RetryConfig)
