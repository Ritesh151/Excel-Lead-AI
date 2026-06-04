"""
recorder/example.py
Manual test runner for the recording subsystem.

Usage:
    # List available input devices
    python -m recorder.example devices

    # Check the configured/default device is accessible
    python -m recorder.example check

    # Record a 5-second test clip (saved to recordings/)
    python -m recorder.example record --phone +919427047705

    # Record with custom duration and sample rate
    python -m recorder.example record --phone +919427047705 --duration 8 --rate 44100

    # Record with noise reduction enabled
    python -m recorder.example record --phone +919427047705 --noise-reduction

    # Record on a specific input device
    python -m recorder.example record --phone +919427047705 --device 2
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

# Allow running from inside ai-python/
sys.path.insert(0, str(Path(__file__).parent.parent))

from logger import logger
from recorder.recording_manager import RecordingManager
from recorder.models import RecordingConfig
from recorder.exceptions import RecorderBaseError, MicrophoneUnavailableError


# ─── Sub-commands ─────────────────────────────────────────────────────────────

def cmd_devices(_args: argparse.Namespace) -> None:
    """List all audio input devices."""
    print("\n── Audio Input Devices ──────────────────────────────")
    try:
        mgr = RecordingManager()
        devices = mgr.list_input_devices()
    except MicrophoneUnavailableError as exc:
        print(f"  ERROR: {exc}")
        print("  Install sounddevice:  pip install sounddevice")
        sys.exit(1)

    if not devices:
        print("  No input devices found.")
    else:
        for d in devices:
            marker = "  ◀ default" if d.is_default else ""
            print(
                f"  [{d.index:>2}] {d.name:<40} "
                f"{d.channels}ch  {d.sample_rate}Hz{marker}"
            )
    print()


def cmd_check(_args: argparse.Namespace) -> None:
    """Verify the configured input device is accessible."""
    print("\n── Device Check ─────────────────────────────────────")
    try:
        mgr    = RecordingManager()
        device = mgr.check_device()
        print(f"  OK  [{device.index}] {device.name}")
        print(f"      Channels : {device.channels}")
        print(f"      Rate     : {device.sample_rate} Hz")
        print(f"      Default  : {device.is_default}")
    except RecorderBaseError as exc:
        print(f"  FAILED: {exc}")
        sys.exit(1)
    print()


def cmd_record(args: argparse.Namespace) -> None:
    """Record a test clip and save it to recordings/."""
    phone = args.phone

    config = RecordingConfig(
        duration_seconds  = args.duration,
        sample_rate       = args.rate,
        channels          = 1,
        input_device      = args.device if args.device >= 0 else None,
        noise_reduction   = args.noise_reduction,
    )

    recordings_dir = (
        Path(__file__).parent.parent.parent / "recordings"
    ).resolve()

    print(f"\n── Recording Test ───────────────────────────────────")
    print(f"  Phone    : {phone}")
    print(f"  Duration : {args.duration}s")
    print(f"  Rate     : {args.rate} Hz")
    print(f"  Device   : {args.device if args.device >= 0 else 'default'}")
    print(f"  Noise NR : {args.noise_reduction}")
    print(f"  Save to  : {recordings_dir}")
    print()
    print(f"  Recording for {args.duration}s … (speak now)")

    try:
        mgr    = RecordingManager(recordings_dir=recordings_dir, config=config)
        result = mgr.record_response(phone)
    except RecorderBaseError as exc:
        print(f"\n  FAILED: {exc}")
        sys.exit(1)

    print()
    print("── Result ───────────────────────────────────────────")
    print(json.dumps(result.to_dict(), indent=2))

    if result.success:
        print(f"\n  ✓ Saved: {result.file_path}")
    elif result.is_empty:
        print("\n  ⚠ Empty recording — no speech detected.")
        print("  Check microphone volume and speak louder.")
    else:
        print(f"\n  ✗ Failed: {result.error_message}")
    print()


# ─── CLI ──────────────────────────────────────────────────────────────────────

def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Caller Recording System — manual test runner"
    )
    sub = parser.add_subparsers(dest="command", required=True)

    # devices
    sub.add_parser("devices", help="List audio input devices")

    # check
    sub.add_parser("check", help="Check configured input device is accessible")

    # record
    p_rec = sub.add_parser("record", help="Record a test clip")
    p_rec.add_argument(
        "--phone", default="+910000000000",
        help="Phone number for file naming (default: +910000000000)"
    )
    p_rec.add_argument(
        "--duration", type=float, default=5.0,
        help="Recording duration in seconds (default: 5)"
    )
    p_rec.add_argument(
        "--rate", type=int, default=16_000,
        choices=[8_000, 16_000, 22_050, 44_100, 48_000],
        help="Sample rate in Hz (default: 16000)"
    )
    p_rec.add_argument(
        "--device", type=int, default=-1,
        help="Input device index (-1 = system default)"
    )
    p_rec.add_argument(
        "--noise-reduction", action="store_true",
        help="Enable noise gate before saving"
    )

    return parser


def main() -> None:
    parser = build_parser()
    args   = parser.parse_args()
    dispatch = {
        "devices": cmd_devices,
        "check":   cmd_check,
        "record":  cmd_record,
    }
    dispatch[args.command](args)


if __name__ == "__main__":
    main()
