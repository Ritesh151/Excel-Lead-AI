"""
audio/example.py
Manual test runner for the audio playback subsystem.

Usage:
    # List available output devices
    python -m audio.example devices

    # Play a single file
    python -m audio.example play greeting.wav

    # Simulate the full YES conversation flow
    python -m audio.example simulate yes

    # Simulate NO flow
    python -m audio.example simulate no

    # Play at custom volume
    python -m audio.example play greeting.wav --volume 0.7

    # Play on a specific output device
    python -m audio.example play greeting.wav --device 2
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

# Allow running from inside ai-python/
sys.path.insert(0, str(Path(__file__).parent.parent))

from logger import logger
from audio.playback_controller import PlaybackController, PlaybackStatus
from audio.audio_player import AudioPlayer


# ─── Sub-commands ─────────────────────────────────────────────────────────────

def cmd_devices(_args: argparse.Namespace) -> None:
    """List available audio output devices."""
    print("\n── Audio Output Devices ──────────────────────────────")
    devices = AudioPlayer.list_output_devices()
    if not devices:
        print("  sounddevice not installed — device listing unavailable.")
        print("  Install it: pip install sounddevice")
    else:
        for d in devices:
            marker = " ◀ default" if d["index"] == 0 else ""
            print(
                f"  [{d['index']:>2}] {d['name']:<40} "
                f"{d['channels']}ch  {d['sample_rate']}Hz{marker}"
            )
    print()


def cmd_play(args: argparse.Namespace) -> None:
    """Play a single WAV file."""
    ctrl = PlaybackController(
        volume=args.volume,
        output_device=args.device if args.device >= 0 else None,
    )

    print(f"\n── Playing: {args.file} ─────────────────────────────")
    result = ctrl.play_file(args.file, block=True)

    if result.succeeded:
        print(f"  Done  ({result.duration_seconds:.2f}s)")
    elif result.status == PlaybackStatus.SKIPPED:
        print(f"  SKIPPED — {result.error_message}")
        print("  → Replace the placeholder WAV file with a real recording.")
    else:
        print(f"  ERROR — {result.error_message}")
    print()


def cmd_simulate(args: argparse.Namespace) -> None:
    """Simulate the full call conversation flow."""
    response = args.response.lower()
    ctrl = PlaybackController(volume=args.volume)

    print(f"\n── Call Simulation (response='{response.upper()}') ──────────")

    # ── Step 1: Play greeting ─────────────────────────────────────────────────
    print("\n[1] Playing greeting.wav …")
    result = ctrl.play_greeting()
    print(f"    {result.status.name}  ({result.duration_seconds:.2f}s)")
    if not result.succeeded:
        print(f"    Note: {result.error_message}")

    # ── Step 2: Simulate waiting for caller response ──────────────────────────
    print("\n[2] Waiting for caller response (simulated 2s) …")
    time.sleep(2)
    print(f"    Detected: {response.upper()}")

    # ── Step 3: Act on response ───────────────────────────────────────────────
    if response == "yes":
        print("\n[3] Playing thank_you.wav (YES branch) …")
        result = ctrl.play_thank_you()
        print(f"    {result.status.name}  ({result.duration_seconds:.2f}s)")
        if not result.succeeded:
            print(f"    Note: {result.error_message}")
        print("\n[4] Ending call …")
    else:
        print("\n[3] NO response — ending call immediately (no audio played)")

    print("\n── Simulation complete ──────────────────────────────────\n")


# ─── CLI ──────────────────────────────────────────────────────────────────────

def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Audio Playback System — manual test runner"
    )
    sub = parser.add_subparsers(dest="command", required=True)

    # devices
    sub.add_parser("devices", help="List audio output devices")

    # play
    p_play = sub.add_parser("play", help="Play a single WAV file")
    p_play.add_argument("file", help="Filename from audio/ dir, e.g. greeting.wav")
    p_play.add_argument(
        "--volume", type=float, default=1.0,
        help="Playback volume 0.0–1.0 (default: 1.0)"
    )
    p_play.add_argument(
        "--device", type=int, default=-1,
        help="sounddevice output device index (default: system default)"
    )

    # simulate
    p_sim = sub.add_parser("simulate", help="Simulate full call conversation")
    p_sim.add_argument(
        "response", choices=["yes", "no"],
        help="Simulated caller response"
    )
    p_sim.add_argument(
        "--volume", type=float, default=1.0,
        help="Playback volume 0.0–1.0 (default: 1.0)"
    )

    return parser


def main() -> None:
    parser = build_parser()
    args = parser.parse_args()

    dispatch = {
        "devices":  cmd_devices,
        "play":     cmd_play,
        "simulate": cmd_simulate,
    }
    dispatch[args.command](args)


if __name__ == "__main__":
    main()
