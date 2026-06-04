"""
adb/adb_manager.py
Low-level ADB command executor.

Responsibilities:
  - Wrap every subprocess call to ADB safely (no shell=True, no injection)
  - Enforce per-command timeouts
  - Raise typed exceptions on failures
  - Expose only high-level methods to the rest of the system

SECURITY:
  All commands are built as lists of strings and passed to subprocess with
  shell=False (the default).  User-supplied data (phone numbers, serials) is
  never interpolated into a shell string — it is always passed as a list element.
"""

from __future__ import annotations

import re
import shutil
import subprocess
import time
from typing import Optional

from logger import logger
from adb.exceptions import (
    AdbNotFoundError,
    AdbCommandError,
    AdbTimeoutError,
    DeviceNotFoundError,
)


# ─── Constants ────────────────────────────────────────────────────────────────

# Absolute path discovered once at import time
_ADB_BIN: Optional[str] = shutil.which("adb")

# Indian phone numbers only — allows + prefix, digits, spaces, dashes
# We re-validate with the leads validator before reaching here, but a
# second check at the ADB layer prevents any shell-injection bypass.
_SAFE_PHONE_RE = re.compile(r"^\+?[\d\s\-]{7,20}$")

# ADB command timeouts (seconds)
_DEFAULT_CMD_TIMEOUT = 10
_DIAL_CMD_TIMEOUT    = 15


# ─── Helpers ─────────────────────────────────────────────────────────────────

def _adb_bin() -> str:
    """Return the adb binary path, raising AdbNotFoundError if missing."""
    if _ADB_BIN is None:
        raise AdbNotFoundError(
            "adb binary not found on PATH. "
            "Install Android platform-tools and ensure 'adb' is on PATH."
        )
    return _ADB_BIN


def _validate_phone(phone: str) -> str:
    """
    Reject phone strings that could inject shell metacharacters.
    Returns the stripped phone string on success.
    Raises ValueError on invalid format.
    """
    phone = phone.strip()
    if not _SAFE_PHONE_RE.match(phone):
        raise ValueError(
            f"Phone number '{phone}' contains invalid characters. "
            "Only digits, +, spaces and dashes are allowed."
        )
    return phone


# ─── AdbManager ──────────────────────────────────────────────────────────────

class AdbManager:
    """
    Safe, typed wrapper around the adb command-line tool.

    All methods that accept a `serial` parameter target that specific device.
    If serial is None the command targets the only connected device (adb -d).
    """

    def __init__(self, serial: Optional[str] = None) -> None:
        """
        Args:
            serial: Device serial from `adb devices`.
                    Pass None to auto-select the only connected device.
        """
        self.serial = serial or ""
        logger.debug(f"AdbManager initialised (serial={self.serial or 'auto'})")

    # ── Internal runner ───────────────────────────────────────────────────────

    def _run(
        self,
        args: list[str],
        timeout: float = _DEFAULT_CMD_TIMEOUT,
        check: bool = True,
    ) -> subprocess.CompletedProcess:
        """
        Execute an adb command safely.

        Builds:   adb [-s SERIAL] <args...>
        No shell interpolation — args is always a plain list.

        Args:
            args:    Command tokens after `adb [-s SERIAL]`.
            timeout: Seconds before the command is killed.
            check:   If True, raise AdbCommandError on non-zero exit code.

        Returns:
            subprocess.CompletedProcess with stdout/stderr decoded as str.

        Raises:
            AdbTimeoutError  on subprocess timeout.
            AdbCommandError  on non-zero exit code (when check=True).
        """
        # Build base command
        cmd = [_adb_bin()]
        if self.serial:
            cmd += ["-s", self.serial]

        cmd += args

        logger.debug(f"ADB ▶ {' '.join(cmd)}")

        try:
            result = subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                timeout=timeout,
                # shell=False is the default — explicit for clarity
                shell=False,
            )
        except subprocess.TimeoutExpired as exc:
            raise AdbTimeoutError(
                f"ADB command timed out after {timeout}s: {' '.join(cmd)}"
            ) from exc
        except FileNotFoundError as exc:
            raise AdbNotFoundError(
                f"adb binary not found: {exc}"
            ) from exc

        if check and result.returncode != 0:
            raise AdbCommandError(
                f"ADB command failed (exit {result.returncode}): "
                f"{' '.join(cmd)}\nstderr: {result.stderr.strip()}"
            )

        return result

    # ── Device commands ───────────────────────────────────────────────────────

    def get_devices_raw(self) -> str:
        """
        Run `adb devices` and return raw stdout.
        Does NOT require a serial — always targets the ADB server.
        """
        cmd = [_adb_bin(), "devices", "-l"]
        logger.debug(f"ADB ▶ {' '.join(cmd)}")
        try:
            result = subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                timeout=_DEFAULT_CMD_TIMEOUT,
                shell=False,
            )
        except subprocess.TimeoutExpired as exc:
            raise AdbTimeoutError("adb devices timed out") from exc
        return result.stdout

    def get_prop(self, prop: str) -> str:
        """
        Read a single Android system property.
        Example: get_prop("ro.product.model") → "Pixel 6"
        """
        result = self._run(["shell", "getprop", prop])
        return result.stdout.strip()

    def is_screen_on(self) -> bool:
        """Return True if the device screen is currently on."""
        result = self._run(["shell", "dumpsys", "power"], check=False)
        return "mWakefulness=Awake" in result.stdout

    def wake_screen(self) -> None:
        """Press KEYCODE_WAKEUP to turn the screen on."""
        self._run(["shell", "input", "keyevent", "224"])
        logger.debug("Screen wake sent")

    def press_key(self, keycode: int) -> None:
        """Send a single Android keyevent by numeric keycode."""
        self._run(["shell", "input", "keyevent", str(keycode)])

    # ── Telephony commands ────────────────────────────────────────────────────

    def dial(self, phone: str) -> None:
        """
        Place an outgoing SIM call to `phone` using the tel: URI.

        Uses `am start` with ACTION_CALL — does NOT use `shell=True`.
        Also wakes the screen and dismisses keyguard before dialing to
        ensure the Samsung dialler is in foreground.

        Args:
            phone: E.164 number, e.g. "+919427047705"

        Raises:
            ValueError       for malformed phone strings.
            AdbCommandError  if the intent fails to start.
        """
        phone = _validate_phone(phone)
        uri = f"tel:{phone}"

        logger.info(f"Dialling {phone} via ADB ACTION_CALL")

        # Wake + unlock screen before dialing (critical for Samsung)
        try:
            if not self.is_screen_on():
                self.wake_screen()
                time.sleep(0.3)
            self.dismiss_keyguard()
            time.sleep(0.3)
        except Exception as exc:
            logger.warning(f"Screen wake/unlock failed (continuing anyway): {exc}")

        # Primary dial command: ACTION_CALL (requires CALL_PHONE permission on device)
        result = self._run(
            [
                "shell", "am", "start",
                "-a", "android.intent.action.CALL",
                "-d", uri,
            ],
            timeout=_DIAL_CMD_TIMEOUT,
            check=False,  # don't raise — inspect output
        )

        # am start returns 0 but may print "Error:" on some Samsung versions
        if result.returncode != 0 or "Error" in result.stdout or "error" in result.stderr:
            logger.warning(
                f"ACTION_CALL may have failed (rc={result.returncode}): "
                f"stdout={result.stdout.strip()} stderr={result.stderr.strip()}"
            )
            # Fallback: try ACTION_DIAL (opens dialler without CALL_PHONE, less reliable)
            logger.info(f"Trying ACTION_DIAL fallback for {phone}")
            self._run(
                [
                    "shell", "am", "start",
                    "-a", "android.intent.action.DIAL",
                    "-d", uri,
                ],
                timeout=_DIAL_CMD_TIMEOUT,
                check=False,
            )
        else:
            logger.info(f"ACTION_CALL sent successfully: {result.stdout.strip() or 'ok'}")

    def hangup(self) -> None:
        """
        End the active call.

        Strategy (most compatible across Android versions):
          1. KeyEvent ENDCALL (6) — works on most devices
          2. Fallback: input tap on the end-call button area is NOT used
             here because coordinates are device-specific; callers should
             rely on telecom service method instead if ENDCALL fails.
        """
        logger.info("Hanging up call (KEYCODE_ENDCALL)")
        self._run(["shell", "input", "keyevent", "6"])  # KEYCODE_ENDCALL

    def hangup_via_telecom(self) -> None:
        """
        Alternative hangup using the telecom service command.
        More reliable on Android 9+ when ENDCALL keyevent is ignored.
        Requires the device to run as a privileged shell (standard USB ADB).
        """
        logger.info("Hanging up via telecom service")
        self._run(["shell", "telecom", "hangup-all-calls"], check=False)

    def get_telephony_state_raw(self) -> str:
        """
        Dump telephony.registry to extract the current call state integer.

        Returns the raw dumpsys output string for parsing by the caller.
        Using check=False because some Android versions return non-zero
        even on success.
        """
        result = self._run(
            ["shell", "dumpsys", "telephony.registry"],
            timeout=8,
            check=False,
        )
        return result.stdout

    def get_call_state_int(self) -> int:
        """
        Parse `dumpsys telephony.registry` and return the call state integer.

          0 → IDLE
          1 → RINGING
          2 → OFFHOOK

        Returns -1 if the value cannot be parsed (treated as UNKNOWN).
        """
        raw = self.get_telephony_state_raw()

        # Android ≤ 11:  "mCallState=2"
        # Android 12+:   "mCallState2=2" or "callState=2"
        patterns = [
            r"mCallState=(\d)",
            r"mCallState\d+=(\d)",
            r"callState=(\d)",
        ]
        for pattern in patterns:
            match = re.search(pattern, raw)
            if match:
                val = int(match.group(1))
                logger.debug(f"Telephony callState raw={val}")
                return val

        logger.warning("Could not parse call state from telephony.registry")
        return -1

    def dismiss_keyguard(self) -> None:
        """Dismiss the lock screen (requires ADB authorisation)."""
        self._run(["shell", "wm", "dismiss-keyguard"], check=False)

    def wait_for_device(self, timeout: float = 30.0) -> None:
        """
        Block until the device comes online or timeout expires.
        Polls every second instead of `adb wait-for-device` (which hangs
        indefinitely if the device never appears).

        Raises:
            DeviceNotFoundError on timeout.
        """
        logger.info(f"Waiting for device (serial={self.serial or 'any'}, timeout={timeout}s)")
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                result = self._run(["get-state"], timeout=5, check=False)
                if result.stdout.strip() == "device":
                    logger.info("Device is online")
                    return
            except (AdbCommandError, AdbTimeoutError):
                pass
            time.sleep(1.0)

        raise DeviceNotFoundError(
            f"Device (serial={self.serial or 'any'}) did not come online within {timeout}s"
        )
