"""
adb/device_checker.py
Device discovery, selection, and health verification.

Public API:
    get_connected_devices()  → list[DeviceInfo]
    select_device(serial)    → DeviceInfo
    auto_select_device()     → DeviceInfo   (picks the only online device)
    assert_device_ready(mgr) → None         (raises if device is not usable)
"""

from __future__ import annotations

import re
import shutil
import subprocess
from typing import Optional

from logger import logger
from adb.models import DeviceInfo, DeviceState
from adb.adb_manager import AdbManager
from adb.exceptions import (
    AdbNotFoundError,
    DeviceNotFoundError,
    DeviceUnauthorizedError,
    DeviceOfflineError,
    MultipleDevicesError,
)


# ─── Device list parsing ──────────────────────────────────────────────────────

def _parse_devices_output(raw: str) -> list[DeviceInfo]:
    """
    Parse the stdout of `adb devices -l` into a list of DeviceInfo objects.

    Example lines:
        emulator-5554          device product:sdk_gphone ...
        R38M8063YKE            device
        192.168.1.5:5555       unauthorized
    """
    devices: list[DeviceInfo] = []

    for line in raw.splitlines():
        line = line.strip()
        # Skip the header line and blank lines
        if not line or line.startswith("List of devices"):
            continue

        # Split on first whitespace: serial <state> [extra...]
        parts = line.split(None, 1)
        if len(parts) < 2:
            continue

        serial = parts[0]
        rest = parts[1]

        # Map the state string to our enum
        state_str = rest.split()[0] if rest else "unknown"
        try:
            state = DeviceState(state_str)
        except ValueError:
            state = DeviceState.OFFLINE

        devices.append(DeviceInfo(serial=serial, state=state))

    return devices


# ─── Public functions ─────────────────────────────────────────────────────────

def get_connected_devices() -> list[DeviceInfo]:
    """
    Return all devices currently known to the ADB server.
    Each entry has serial + state; model/android_version are not populated here
    (call enrich_device_info() for that).

    Raises:
        AdbNotFoundError if adb is not on PATH.
    """
    adb_bin = shutil.which("adb")
    if adb_bin is None:
        raise AdbNotFoundError(
            "adb not found on PATH. Install Android platform-tools."
        )

    try:
        result = subprocess.run(
            [adb_bin, "devices", "-l"],
            capture_output=True,
            text=True,
            timeout=10,
            shell=False,
        )
    except subprocess.TimeoutExpired as exc:
        raise AdbNotFoundError("adb devices command timed out") from exc

    devices = _parse_devices_output(result.stdout)
    logger.debug(f"ADB devices: {[(d.serial, d.state.value) for d in devices]}")
    return devices


def enrich_device_info(device: DeviceInfo) -> DeviceInfo:
    """
    Populate model and android_version on an existing DeviceInfo.
    Non-fatal — if getprop fails the fields remain empty strings.

    Args:
        device: A DeviceInfo with state == ONLINE.
    """
    if not device.is_ready:
        return device

    mgr = AdbManager(serial=device.serial)
    try:
        device.model = mgr.get_prop("ro.product.model")
        device.android_version = mgr.get_prop("ro.build.version.release")
        logger.debug(
            f"Device {device.serial}: model={device.model!r} "
            f"android={device.android_version!r}"
        )
    except Exception as exc:  # noqa: BLE001
        logger.warning(f"Could not enrich device info for {device.serial}: {exc}")

    return device


def select_device(serial: str) -> DeviceInfo:
    """
    Look up a specific device by serial and return its DeviceInfo.

    Raises:
        DeviceNotFoundError    if the serial is not in the device list.
        DeviceUnauthorizedError if RSA key is not approved.
        DeviceOfflineError     if the device is offline.
    """
    devices = get_connected_devices()
    for device in devices:
        if device.serial == serial:
            _assert_state(device)
            return enrich_device_info(device)

    raise DeviceNotFoundError(
        f"Device with serial '{serial}' not found. "
        f"Connected serials: {[d.serial for d in devices]}"
    )


def auto_select_device() -> DeviceInfo:
    """
    Automatically pick the single online device.

    Raises:
        DeviceNotFoundError  if no online device exists.
        MultipleDevicesError if more than one online device is present.
    """
    devices = get_connected_devices()
    online = [d for d in devices if d.state == DeviceState.ONLINE]

    if not online:
        all_states = [(d.serial, d.state.value) for d in devices]
        raise DeviceNotFoundError(
            f"No online ADB device found. All devices: {all_states or 'none'}"
        )

    if len(online) > 1:
        raise MultipleDevicesError(
            f"Multiple online devices found: {[d.serial for d in online]}. "
            "Set ADB_DEVICE_SERIAL in .env to target a specific device."
        )

    device = online[0]
    logger.info(f"Auto-selected device: {device.serial}")
    return enrich_device_info(device)


def get_device(serial: Optional[str] = None) -> DeviceInfo:
    """
    Convenience entry point: select by serial if given, else auto-select.

    Args:
        serial: Device serial from config (empty string / None → auto).

    Returns:
        A ready DeviceInfo.
    """
    if serial:
        return select_device(serial)
    return auto_select_device()


def assert_device_ready(mgr: AdbManager) -> None:
    """
    Verify the device targeted by `mgr` is online and responsive.
    Sends a lightweight `adb get-state` command.

    Raises:
        DeviceOfflineError if the device does not respond.
    """
    try:
        result = mgr._run(["get-state"], timeout=6, check=False)
        state = result.stdout.strip()
        if state != "device":
            raise DeviceOfflineError(
                f"Device is not in 'device' state (got '{state}')"
            )
        logger.debug(f"Device ready check passed (serial={mgr.serial or 'auto'})")
    except Exception as exc:
        raise DeviceOfflineError(
            f"Device health check failed: {exc}"
        ) from exc


# ─── Internal ────────────────────────────────────────────────────────────────

def _assert_state(device: DeviceInfo) -> None:
    """Raise the appropriate exception if the device is not ONLINE."""
    if device.state == DeviceState.UNAUTHORIZED:
        raise DeviceUnauthorizedError(
            f"Device {device.serial} is connected but not authorised. "
            "Accept the RSA key prompt on the device screen."
        )
    if device.state == DeviceState.OFFLINE:
        raise DeviceOfflineError(
            f"Device {device.serial} is offline. Reconnect the USB cable."
        )
    if device.state == DeviceState.NO_DEVICE:
        raise DeviceNotFoundError(
            f"Device {device.serial} is not present."
        )
