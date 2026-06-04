"""
leads/validators.py
Pure, stateless validation and normalisation functions for lead data.

All functions are side-effect free — no I/O, no logging.
They raise ValueError with a descriptive message on failure.
"""

from __future__ import annotations

import re

# ─── Constants ────────────────────────────────────────────────────────────────

# Indian mobile numbers: 10 digits, starting with 6–9
_INDIAN_MOBILE_RE = re.compile(r"^[6-9]\d{9}$")

# Digits we strip from incoming numbers before normalisation
_STRIP_CHARS = re.compile(r"[\s\-().+]")

# Country code prefix patterns to remove before normalising
_COUNTRY_CODE_RE = re.compile(
    r"^(?:"
    r"\+91|"       # +91
    r"0091|"       # 0091
    r"91(?=\d{10}$)"  # 91 followed by exactly 10 digits
    r")"
)


# ─── Phone Normalisation ──────────────────────────────────────────────────────

def normalize_phone(raw: str) -> str:
    """
    Convert any common Indian phone format to E.164 (+91XXXXXXXXXX).

    Accepted input examples:
        +91 9427047705
        +919427047705
        09427047705
        9427047705
        91 94270 47705

    Returns:
        "+91XXXXXXXXXX" — always 13 characters.

    Raises:
        ValueError if the number cannot be normalised.
    """
    if not isinstance(raw, str):
        raw = str(raw)

    # Strip all whitespace, dashes, dots, parentheses, plus signs
    digits = _STRIP_CHARS.sub("", raw)

    # Remove leading zeros (STD trunk prefix)
    digits = digits.lstrip("0") if digits.startswith("00") else digits
    if digits.startswith("0"):
        digits = digits[1:]

    # Remove country code prefix if present
    digits = _COUNTRY_CODE_RE.sub("", digits)

    if not digits:
        raise ValueError("Phone number is empty after stripping")

    if not digits.isdigit():
        raise ValueError(f"Non-digit characters remain after normalisation: '{digits}'")

    if len(digits) != 10:
        raise ValueError(
            f"Expected 10 digits after stripping country code, got {len(digits)}: '{digits}'"
        )

    if not _INDIAN_MOBILE_RE.match(digits):
        raise ValueError(
            f"'{digits}' is not a valid Indian mobile number "
            f"(must start with 6–9 and be 10 digits)"
        )

    return f"+91{digits}"


# ─── Name Normalisation ───────────────────────────────────────────────────────

def normalize_name(raw: str | None) -> str:
    """
    Clean and title-case a lead name.
    Returns empty string for blank / missing names (name is optional).
    """
    if not raw or not isinstance(raw, str):
        return ""

    # Collapse multiple spaces, strip edges, title-case
    return " ".join(raw.split()).strip().title()


# ─── Row-level Validation ─────────────────────────────────────────────────────

def validate_row(
    row_number: int,
    raw_name: object,
    raw_phone: object,
) -> tuple[str, str]:
    """
    Validate a single Excel row.

    Args:
        row_number: 1-based row index (1 = header, 2 = first data row).
        raw_name:   Value from the Name column (may be None).
        raw_phone:  Value from the Mobile Number column (may be None).

    Returns:
        Tuple of (normalised_name, e164_phone).

    Raises:
        ValueError with a descriptive message on any failure.
    """
    # ── Phone is mandatory ───────────────────────────────────────────────────
    if raw_phone is None or str(raw_phone).strip() == "":
        raise ValueError("Mobile Number is empty")

    phone = normalize_phone(str(raw_phone).strip())
    name = normalize_name(raw_name)  # type: ignore[arg-type]

    return name, phone
