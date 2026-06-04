"""
adb/exceptions.py
Custom exception hierarchy for the ADB calling subsystem.

Having a typed hierarchy lets callers catch only the errors they care about
and lets logs show exactly what went wrong without parsing message strings.
"""


class AdbBaseError(Exception):
    """Root exception for all ADB subsystem errors."""


# ── Infrastructure errors ─────────────────────────────────────────────────────

class AdbNotFoundError(AdbBaseError):
    """adb binary is not installed or not on PATH."""


class AdbCommandError(AdbBaseError):
    """An adb command exited with a non-zero return code."""


class AdbTimeoutError(AdbBaseError):
    """An adb command did not complete within the allowed time."""


# ── Device errors ─────────────────────────────────────────────────────────────

class DeviceNotFoundError(AdbBaseError):
    """No suitable ADB device could be found or selected."""


class DeviceUnauthorizedError(AdbBaseError):
    """Device is connected but the RSA key has not been approved."""


class DeviceOfflineError(AdbBaseError):
    """Device is listed by adb but is in an offline / unresponsive state."""


class MultipleDevicesError(AdbBaseError):
    """More than one device is connected and no serial was specified."""


# ── Call errors ───────────────────────────────────────────────────────────────

class CallError(AdbBaseError):
    """Base for call-level errors."""


class InvalidPhoneNumberError(CallError):
    """The phone number failed format validation."""


class CallTimeoutError(CallError):
    """The call did not reach the expected state within the timeout."""


class CallRejectedError(CallError):
    """The outgoing call was actively rejected by the recipient."""


class CallFailedError(CallError):
    """The call could not be placed (network, SIM, or device error)."""
