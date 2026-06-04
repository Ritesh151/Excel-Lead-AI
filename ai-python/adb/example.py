"""
adb/example.py
Manual test runner for the ADB calling subsystem.

Usage:
    # List connected devices
    python -m adb.example devices

    # Call a single number
    python -m adb.example call +919427047705

    # Call all leads from Excel sequentially
    python -m adb.example leads

    # Call with custom timeouts
    python -m adb.example call +919427047705 --ring-timeout 30
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

# Allow running from inside ai-python/
sys.path.insert(0, str(Path(__file__).parent.parent))

from logger import logger
from config import settings
from adb.device_checker import get_connected_devices, get_device
from adb.call_controller import make_controller
from adb.models import CallConfig, RetryConfig
from adb.exceptions import AdbBaseError


# ─── Sub-commands ─────────────────────────────────────────────────────────────

def cmd_devices(_args: argparse.Namespace) -> None:
    """List all ADB-connected devices."""
    print("\n── Connected ADB Devices ──────────────────────────────")
    try:
        devices = get_connected_devices()
    except AdbBaseError as exc:
        print(f"[ERROR] {exc}")
        sys.exit(1)

    if not devices:
        print("  No devices found.")
    for d in devices:
        print(f"  {d.serial:25s}  state={d.state.value}")
    print()


def cmd_call(args: argparse.Namespace) -> None:
    """Place a single call."""
    phone = args.phone
    serial = settings.adb_device_serial or None

    config = CallConfig(
        ring_timeout=args.ring_timeout,
        retry=RetryConfig(max_attempts=args.retries),
    )

    print(f"\n── Calling {phone} ──────────────────────────────────")
    try:
        device = get_device(serial)
        print(f"  Device : {device.serial}  ({device.model or 'unknown model'})")
        print(f"  Android: {device.android_version or 'unknown'}")
        print()

        controller = make_controller(serial=device.serial, config=config)
        result = controller.call(phone)

        print(json.dumps(result.to_dict(), indent=2))
    except AdbBaseError as exc:
        print(f"[ERROR] {exc}")
        sys.exit(1)
    print()


def cmd_leads(args: argparse.Namespace) -> None:
    """Import leads from Excel and call them all sequentially."""
    # Import leads using the leads module
    leads_path = Path(settings.leads_file_path).resolve()
    print(f"\n── Sequential Calling from {leads_path.name} ──────────")

    try:
        from leads.importer import load_leads
        leads = load_leads(leads_path)
    except Exception as exc:
        print(f"[ERROR] Failed to load leads: {exc}")
        sys.exit(1)

    print(f"  Loaded {len(leads)} lead(s)")
    if not leads:
        print("  Nothing to call.")
        return

    serial = settings.adb_device_serial or None
    try:
        device = get_device(serial)
        print(f"  Device : {device.serial}  ({device.model or 'unknown'})\n")

        config = CallConfig(
            ring_timeout=args.ring_timeout,
            retry=RetryConfig(max_attempts=args.retries),
        )
        controller = make_controller(serial=device.serial, config=config)
        results = controller.call_sequential(leads)

    except AdbBaseError as exc:
        print(f"[ERROR] {exc}")
        sys.exit(1)

    print("\n── Results ─────────────────────────────────────────────")
    for r in results:
        print(
            f"  {r.phone:15s}  outcome={r.outcome.value:15s}  "
            f"duration={r.duration_seconds:5.1f}s  "
            f"attempts={r.attempt_number}"
        )
    print()


# ─── CLI ─────────────────────────────────────────────────────────────────────

def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="ADB SIM Calling — manual test runner"
    )
    sub = parser.add_subparsers(dest="command", required=True)

    # devices
    sub.add_parser("devices", help="List connected ADB devices")

    # call
    p_call = sub.add_parser("call", help="Call a single phone number")
    p_call.add_argument("phone", help="E.164 number, e.g. +919427047705")
    p_call.add_argument(
        "--ring-timeout", type=float, default=45.0,
        help="Seconds to wait for answer (default: 45)"
    )
    p_call.add_argument(
        "--retries", type=int, default=3,
        help="Max call attempts including first (default: 3)"
    )

    # leads
    p_leads = sub.add_parser("leads", help="Call all leads from Excel sequentially")
    p_leads.add_argument(
        "--ring-timeout", type=float, default=45.0,
        help="Seconds to wait for answer per lead (default: 45)"
    )
    p_leads.add_argument(
        "--retries", type=int, default=3,
        help="Max attempts per lead (default: 3)"
    )

    return parser


def main() -> None:
    parser = build_parser()
    args = parser.parse_args()

    dispatch = {
        "devices": cmd_devices,
        "call": cmd_call,
        "leads": cmd_leads,
    }
    dispatch[args.command](args)


if __name__ == "__main__":
    main()
