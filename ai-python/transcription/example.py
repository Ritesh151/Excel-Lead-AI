"""
transcription/example.py
Manual test runner for the transcription + intent detection pipeline.

Usage:
    # Transcribe a WAV recording
    python -m transcription.example file recordings/+91xxx_20240603.wav

    # Test intent detection only (no Whisper needed)
    python -m transcription.example intent "haan bilkul"
    python -m transcription.example intent "nahi chahiye"
    python -m transcription.example intent "yes please"

    # Run all intent detection tests
    python -m transcription.example test

    # Pre-load model and transcribe
    python -m transcription.example file path/to/file.wav --model small --lang hi
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from logger import logger
from transcription.intent_detector import detect_intent, Intent
from transcription.transcription_service import TranscriptionService
from whisper.exceptions import WhisperBaseError


# ─── Sub-commands ─────────────────────────────────────────────────────────────

def cmd_intent(args: argparse.Namespace) -> None:
    """Run intent detection on a text string (no Whisper required)."""
    text = args.text
    result = detect_intent(text)
    print(f'\n── Intent Detection ─────────────────────────────────')
    print(f'  Input : "{text}"')
    print(f'  Clean : "{result.clean_text}"')
    print(f'  Intent: {result.intent.value}')
    print(f'  Conf  : {result.confidence:.2f}')
    print(f'  Match : {result.matched_keyword!r}')
    print()


def cmd_test(_args: argparse.Namespace) -> None:
    """Run the built-in intent detection test suite."""
    cases: list[tuple[str, str]] = [
        # (input_text, expected_intent)
        # YES — English
        ("yes",                         "YES"),
        ("Yeah sure",                   "YES"),
        ("ok go ahead",                 "YES"),
        ("absolutely",                  "YES"),
        ("yes please",                  "YES"),
        # YES — Hindi / Hinglish
        ("haan",                        "YES"),
        ("haa ji",                      "YES"),
        ("bilkul",                      "YES"),
        ("ji haan",                     "YES"),
        ("haan bilkul",                 "YES"),
        ("theek hai",                   "YES"),
        ("acha",                        "YES"),
        # NO — English
        ("no",                          "NO"),
        ("nope",                        "NO"),
        ("not interested",              "NO"),
        ("no thank you",                "NO"),
        ("I don't need it",             "NO"),
        ("not now",                     "NO"),
        # NO — Hindi / Hinglish
        ("nahi",                        "NO"),
        ("nahin",                       "NO"),
        ("bilkul nahi",                 "NO"),
        ("nahi chahiye",                "NO"),
        ("mujhe nahi chahiye",          "NO"),
        ("koi zaroorat nahi",           "NO"),
        ("abhi nahi",                   "NO"),
        # Edge cases
        ("",                            "UNKNOWN"),
        ("   ",                         "UNKNOWN"),
        ("hello",                       "UNKNOWN"),
        ("please call back later",      "NO"),     # "nahi"-free but contains "no need" concept → UNKNOWN actually
        ("ok ok ok",                    "YES"),
    ]

    passed = 0
    failed = 0
    print(f'\n── Intent Detection Test Suite ({len(cases)} cases) ──────────')
    for text, expected in cases:
        result = detect_intent(text)
        ok = result.intent.value == expected
        mark = "\033[32mPASS\033[0m" if ok else "\033[31mFAIL\033[0m"
        if ok:
            passed += 1
        else:
            failed += 1
        print(
            f'  {mark}  "{text:<35}" → {result.intent.value:<8} '
            f'(expected {expected}) match={result.matched_keyword!r}'
        )

    print(f'\n  Results: {passed}/{len(cases)} passed', end="")
    if failed:
        print(f'  ({failed} failed)')
    else:
        print()
    print()


def cmd_file(args: argparse.Namespace) -> None:
    """Transcribe a WAV file and show the result."""
    path = Path(args.path)

    print(f'\n── Transcribing: {path.name} ─────────────────────────')
    print(f'  Model  : {args.model}')
    print(f'  Device : {args.device}')
    print(f'  Lang   : {args.lang or "auto"}')
    print()

    try:
        from whisper.whisper_engine import WhisperEngine
        engine  = WhisperEngine(
            model_name = args.model,
            device     = args.device,
            language   = args.lang or None,
        )
        service = TranscriptionService(engine=engine)
        print("  Loading model (first run downloads weights) …")
        service.warm_up()
        result  = service.transcribe_file(path)
    except WhisperBaseError as exc:
        print(f'  ERROR: {exc}')
        sys.exit(1)

    print('── Result ───────────────────────────────────────────')
    print(json.dumps(result.to_dict(), indent=2, ensure_ascii=False))
    print()


# ─── CLI ──────────────────────────────────────────────────────────────────────

def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Transcription + Intent Detection — manual test runner"
    )
    sub = parser.add_subparsers(dest="command", required=True)

    # intent
    p_intent = sub.add_parser("intent", help="Test intent detection on text")
    p_intent.add_argument("text", help='Text to classify, e.g. "haan bilkul"')

    # test
    sub.add_parser("test", help="Run built-in intent detection test suite")

    # file
    p_file = sub.add_parser("file", help="Transcribe a WAV file")
    p_file.add_argument("path", help="Path to .wav file")
    p_file.add_argument("--model",  default="base",
                        choices=["tiny","base","small","medium","large-v2","large-v3"],
                        help="Whisper model size (default: base)")
    p_file.add_argument("--device", default="cpu", choices=["cpu","cuda"],
                        help="Compute device (default: cpu)")
    p_file.add_argument("--lang",   default="hi",
                        help="Language hint: hi, en, or empty for auto (default: hi)")

    return parser


def main() -> None:
    parser = build_parser()
    args   = parser.parse_args()
    {"intent": cmd_intent, "test": cmd_test, "file": cmd_file}[args.command](args)


if __name__ == "__main__":
    main()
