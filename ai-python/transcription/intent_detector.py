from __future__ import annotations

import re
import unicodedata
from enum import Enum
from dataclasses import dataclass
from typing import Optional


class Intent(str, Enum):
    YES = "YES"
    NO = "NO"
    UNKNOWN = "UNKNOWN"


_YES_KEYWORDS: frozenset[str] = frozenset({
    "yes", "yeah", "yep", "yup", "sure", "ok", "okay",
    "alright", "absolutely", "definitely", "certainly",
    "of course", "go ahead", "please", "interested",
    "sounds good", "why not", "correct", "right", "affirmative",
    "haan", "han", "ha", "haa", "bilkul", "zaroor", "sahi",
    "theek", "theek hai", "thik", "thik hai", "acha", "accha",
    "acha ji", "accha ji", "ji haan", "ji han", "ji ha",
    "batao", "bataiye", "chahiye", "chahte", "chahta",
    "yes please", "yes sir", "yes madam", "yes bhai",
    "haan sir", "haan ji", "ha ji", "haa ji",
    "ok sir", "ok ji", "okay ji",
    "haan bilkul", "bilkul sahi",
})

_NO_KEYWORDS: frozenset[str] = frozenset({
    "no", "nope", "nah", "never", "not", "don't", "dont",
    "not interested", "no thanks", "no thank you",
    "not now", "not today", "not at all",
    "i don't need", "i dont need", "we don't need",
    "no need", "already have", "not required",
    "busy", "call later", "call back", "will call",
    "nahi", "nahin", "na", "naa", "bilkul nahi", "bilkul nahin",
    "abhi nahi", "abhi nahin", "fir kabhi", "baad mein",
    "mujhe nahi chahiye", "hamein nahi chahiye",
    "koi zaroorat nahi", "zaroorat nahi", "jaroorat nahi",
    "mujhe nahi", "humko nahi",
    "mat karo", "band karo", "rokiye",
    "nahi chahiye", "nahin chahiye",
    "no bhai", "no yaar", "nahi sir", "nahi ji",
    "no interest", "not interested sir",
})

_NEGATION_PATTERNS: tuple[re.Pattern, ...] = (
    re.compile(r"\bnot\s+interest", re.IGNORECASE),
    re.compile(r"\bdon[\'']?t\s+need", re.IGNORECASE),
    re.compile(r"\bno\s+need\b", re.IGNORECASE),
    re.compile(r"\bnahi\b", re.IGNORECASE),
    re.compile(r"\bnahin\b", re.IGNORECASE),
    re.compile(r"\bna\b", re.IGNORECASE),
)

_KEEP_RE = re.compile(r"[^\w\s']", flags=re.UNICODE)


def _normalise(text: str) -> str:
    text = unicodedata.normalize("NFKC", text)
    text = text.lower()
    text = _KEEP_RE.sub(" ", text)
    text = re.sub(r"\s+", " ", text).strip()
    return text


@dataclass(frozen=True)
class DetectionResult:
    raw_text: str
    clean_text: str
    intent: Intent
    confidence: float
    matched_keyword: Optional[str] = None

    def to_dict(self) -> dict:
        return {
            "transcription": self.raw_text,
            "intent": self.intent.value,
            "confidence": round(self.confidence, 3),
            "matched": self.matched_keyword,
        }

    def __str__(self) -> str:
        return (
            f"DetectionResult(intent={self.intent.value} "
            f"conf={self.confidence:.2f} "
            f"match={self.matched_keyword!r} "
            f'text="{self.raw_text[:60]}")'
        )


def detect_intent(raw_text: str) -> DetectionResult:
    if not raw_text or not raw_text.strip():
        return DetectionResult(
            raw_text=raw_text or "",
            clean_text="",
            intent=Intent.UNKNOWN,
            confidence=0.0,
            matched_keyword=None,
        )

    clean = _normalise(raw_text)

    for pattern in _NEGATION_PATTERNS:
        m = pattern.search(clean)
        if m:
            return DetectionResult(
                raw_text=raw_text,
                clean_text=clean,
                intent=Intent.NO,
                confidence=0.85,
                matched_keyword=m.group(0),
            )

    for kw in _NO_KEYWORDS:
        if _word_match(clean, kw):
            return DetectionResult(
                raw_text=raw_text,
                clean_text=clean,
                intent=Intent.NO,
                confidence=1.0,
                matched_keyword=kw,
            )

    for kw in _YES_KEYWORDS:
        if _word_match(clean, kw):
            return DetectionResult(
                raw_text=raw_text,
                clean_text=clean,
                intent=Intent.YES,
                confidence=1.0,
                matched_keyword=kw,
            )

    return DetectionResult(
        raw_text=raw_text,
        clean_text=clean,
        intent=Intent.UNKNOWN,
        confidence=0.0,
        matched_keyword=None,
    )


def _word_match(text: str, keyword: str) -> bool:
    if " " in keyword:
        return keyword in text
    return bool(re.search(r"\b" + re.escape(keyword) + r"\b", text))
