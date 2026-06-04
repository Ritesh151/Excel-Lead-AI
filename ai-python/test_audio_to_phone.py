#!/usr/bin/env python3
"""Test tool for PC speaker playback to Android phone mic capture.

Usage examples:
    python test_audio_to_phone.py --list-devices
    python test_audio_to_phone.py --file greeting.wav --device 2 --play
    python test_audio_to_phone.py --play --record --record-duration 5
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path
from typing import Optional

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))

from logger import logger
from audio.playback_controller import PlaybackController, PlaybackStatus
from audio.audio_player import AudioPlayer


def list_devices() -> None:
    devices = AudioPlayer.list_output_devices()
    if not devices:
        print("No sounddevice output devices detected. Install sounddevice and try again.")
        return

    print("\nAvailable audio output devices:")
    for dev in devices:
        default = " (default)" if dev.get("is_default") else ""
        print(
            f"  [{dev['index']}] {dev['name']} {dev['channels']}ch "
            f"{dev['sample_rate']}Hz{default}"
        )
    print()


def choose_device(arg_device: int) -> Optional[int]:
    if arg_device >= 0:
        return arg_device
    devices = AudioPlayer.list_output_devices()
    if not devices:
        return None
    preferred = next((d for d in devices if d.get("is_default")), devices[0])
    logger.info(f"Auto-selected output device {preferred['index']} ({preferred['name']})")
    return preferred["index"]


def record_mic_capture(duration: float, output_path: Path) -> None:
    try:
        import sounddevice as sd
        import soundfile as sf
    except ImportError as exc:
        raise RuntimeError(
            "sounddevice and soundfile are required for recording. "
            "Install them with: pip install sounddevice soundfile"
        ) from exc

    sample_rate = 16000
    channels = 1
    logger.info(f"Recording microphone capture for {duration:.1f}s to {output_path}")
    recording = sd.rec(
        int(duration * sample_rate),
        samplerate=sample_rate,
        channels=channels,
        dtype="float32",
    )
    sd.wait()
    sf.write(str(output_path), recording, sample_rate)
    logger.info(f"Recording saved: {output_path}")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Standalone test tool for audio playback into Android phone mic."
    )
    parser.add_argument(
        "--list-devices",
        action="store_true",
        help="List available sound output devices and exit.",
    )
    parser.add_argument(
        "--file",
        default="greeting.wav",
        help="Audio filename from audio/ directory to play.",
    )
    parser.add_argument(
        "--device",
        type=int,
        default=-1,
        help="sounddevice output device index. -1 = auto select system default.",
    )
    parser.add_argument(
        "--play",
        action="store_true",
        help="Play the selected greeting audio through the PC speaker.",
    )
    parser.add_argument(
        "--record",
        action="store_true",
        help="Record PC microphone after playback to verify speaker output.",
    )
    parser.add_argument(
        "--record-duration",
        type=float,
        default=5.0,
        help="Seconds to record after playback.",
    )
    args = parser.parse_args()

    if args.list_devices:
        list_devices()
        return

    device_index = choose_device(args.device)
    if args.play:
        audio_path = ROOT / "audio" / args.file
        if not audio_path.exists():
            raise SystemExit(f"Audio file not found: {audio_path}")

        ctrl = PlaybackController(
            output_device=device_index,
            volume=1.0,
        )
        logger.info(f"Starting test playback: {audio_path.name}")
        result = ctrl.play_file(args.file, block=True)
        logger.info(f"Playback result: {result}")

        if not result.succeeded:
            raise SystemExit("Playback did not complete successfully. Check the logs.")
    else:
        logger.info("No playback requested. Use --play to exercise the audio path.")

    if args.record:
        recordings_dir = ROOT / "recordings"
        recordings_dir.mkdir(parents=True, exist_ok=True)
        output_path = recordings_dir / "phone_mic_capture.wav"
        record_mic_capture(args.record_duration, output_path)
        logger.info(
            "Review the recording to confirm speaker output is loud enough for the phone mic."
        )


if __name__ == "__main__":
    main()
