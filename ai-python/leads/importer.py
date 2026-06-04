"""
leads/importer.py
Core Excel import pipeline.

Public API:
    import_leads(file_path)  → ImportResult
    load_leads(file_path)    → list[dict]   (convenience wrapper)

Pipeline steps:
    1. Validate file exists and is .xlsx
    2. Read with pandas (openpyxl engine)
    3. Verify required columns are present
    4. For each row — validate + normalise phone and name
    5. Collect valid leads and invalid rows separately
    6. Remove duplicate phone numbers (keep first occurrence)
    7. Persist invalid rows to logs/invalid_leads.json
    8. Return ImportResult
"""

from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Union

import pandas as pd

from logger import logger
from leads.models import ImportResult, InvalidLeadRecord, LeadRecord
from leads.validators import validate_row

# ─── Constants ────────────────────────────────────────────────────────────────

# Exact header names expected in the Excel file (case-insensitive match)
_REQUIRED_COLUMNS = {"name", "mobile number"}

# Where invalid rows are persisted
_LOG_DIR = Path(__file__).parent.parent.parent / "logs"
_INVALID_LEADS_LOG = _LOG_DIR / "invalid_leads.json"


# ─── Internal Helpers ─────────────────────────────────────────────────────────

def _resolve_columns(df: pd.DataFrame) -> tuple[str, str]:
    """
    Find the Name and Mobile Number columns regardless of original casing.

    Returns:
        (name_col, phone_col) — the actual column names in the DataFrame.

    Raises:
        ValueError if either required column is missing.
    """
    col_map = {c.strip().lower(): c for c in df.columns}
    missing = _REQUIRED_COLUMNS - col_map.keys()
    if missing:
        raise ValueError(
            f"Missing required column(s): {', '.join(sorted(missing))}. "
            f"Found: {list(df.columns)}"
        )
    return col_map["name"], col_map["mobile number"]


def _save_invalid_leads(invalid_rows: list[InvalidLeadRecord]) -> None:
    """
    Append invalid rows to logs/invalid_leads.json.
    Creates the file if it doesn't exist; appends to the existing array.
    """
    if not invalid_rows:
        return

    _LOG_DIR.mkdir(parents=True, exist_ok=True)

    # Load existing records if file already exists
    existing: list[dict] = []
    if _INVALID_LEADS_LOG.exists():
        try:
            with _INVALID_LEADS_LOG.open("r", encoding="utf-8") as fh:
                existing = json.load(fh)
                if not isinstance(existing, list):
                    existing = []
        except (json.JSONDecodeError, OSError) as exc:
            logger.warning(f"Could not read existing invalid_leads.json, overwriting: {exc}")
            existing = []

    # Append new records with a timestamp
    timestamp = datetime.now(timezone.utc).isoformat()
    new_records = [
        {**row.model_dump(), "logged_at": timestamp}
        for row in invalid_rows
    ]
    existing.extend(new_records)

    try:
        with _INVALID_LEADS_LOG.open("w", encoding="utf-8") as fh:
            json.dump(existing, fh, ensure_ascii=False, indent=2)
        logger.info(
            f"Saved {len(invalid_rows)} invalid row(s) → {_INVALID_LEADS_LOG}"
        )
    except OSError as exc:
        # Don't crash the import just because we couldn't write the log
        logger.error(f"Failed to write invalid_leads.json: {exc}")


# ─── Public API ───────────────────────────────────────────────────────────────

def import_leads(file_path: Union[str, Path]) -> ImportResult:
    """
    Read, validate, and normalise leads from an Excel (.xlsx) file.

    Args:
        file_path: Path to the .xlsx file.

    Returns:
        ImportResult with clean leads and invalid-row details.

    Raises:
        FileNotFoundError  if the file does not exist.
        ValueError         if the file is not .xlsx or columns are missing.
        RuntimeError       if pandas fails to parse the file.
    """
    path = Path(file_path).resolve()
    logger.info(f"Starting lead import from: {path}")

    # ── 1. File validation ───────────────────────────────────────────────────
    if not path.exists():
        raise FileNotFoundError(f"Leads file not found: {path}")

    if path.suffix.lower() != ".xlsx":
        raise ValueError(
            f"Only .xlsx files are supported, got: '{path.suffix}'"
        )

    # ── 2. Read with pandas ──────────────────────────────────────────────────
    try:
        df = pd.read_excel(path, engine="openpyxl", dtype=str)
    except Exception as exc:
        raise RuntimeError(f"Failed to parse Excel file: {exc}") from exc

    logger.debug(f"Excel read OK — {len(df)} data row(s), columns: {list(df.columns)}")

    if df.empty:
        logger.warning("Excel file has no data rows")
        return ImportResult(
            total_rows=0,
            valid_count=0,
            invalid_count=0,
            duplicate_count=0,
            leads=[],
            invalid_rows=[],
        )

    # ── 3. Resolve column names ──────────────────────────────────────────────
    try:
        name_col, phone_col = _resolve_columns(df)
    except ValueError as exc:
        raise ValueError(str(exc)) from exc

    # ── 4 & 5. Validate rows, collect valid / invalid ────────────────────────
    valid_leads: list[LeadRecord] = []
    invalid_rows: list[InvalidLeadRecord] = []

    for idx, row in df.iterrows():
        # idx is 0-based; row number in Excel = idx + 2 (1=header, 2=first data)
        row_number = int(idx) + 2  # type: ignore[arg-type]
        raw_name = row.get(name_col)
        raw_phone = row.get(phone_col)

        # Treat pandas NaN as None
        if pd.isna(raw_name):
            raw_name = None
        if pd.isna(raw_phone):
            raw_phone = None

        try:
            name, phone = validate_row(row_number, raw_name, raw_phone)
        except ValueError as exc:
            reason = str(exc)
            logger.warning(f"Row {row_number} invalid — {reason} | name={raw_name!r} phone={raw_phone!r}")
            invalid_rows.append(
                InvalidLeadRecord(
                    row_number=row_number,
                    raw_name=str(raw_name) if raw_name is not None else None,
                    raw_phone=str(raw_phone) if raw_phone is not None else None,
                    reason=reason,
                )
            )
            continue

        valid_leads.append(LeadRecord(name=name, phone=phone))

    # ── 6. Deduplicate by phone (keep first occurrence) ──────────────────────
    seen_phones: set[str] = set()
    unique_leads: list[LeadRecord] = []
    duplicate_count = 0

    for lead in valid_leads:
        if lead.phone in seen_phones:
            duplicate_count += 1
            logger.debug(f"Duplicate removed: {lead.phone} (name={lead.name!r})")
        else:
            seen_phones.add(lead.phone)
            unique_leads.append(lead)

    # ── 7. Persist invalid rows ──────────────────────────────────────────────
    _save_invalid_leads(invalid_rows)

    # ── 8. Build and return result ───────────────────────────────────────────
    result = ImportResult(
        total_rows=len(df),
        valid_count=len(unique_leads),
        invalid_count=len(invalid_rows),
        duplicate_count=duplicate_count,
        leads=unique_leads,
        invalid_rows=invalid_rows,
    )

    logger.info(
        f"Import complete — "
        f"total={result.total_rows} | "
        f"valid={result.valid_count} | "
        f"invalid={result.invalid_count} | "
        f"duplicates_removed={result.duplicate_count}"
    )

    return result


def load_leads(file_path: Union[str, Path]) -> list[dict]:
    """
    Convenience wrapper around import_leads().
    Returns only the clean lead list as plain dicts.

    Example:
        leads = load_leads("../leads/leads.xlsx")
        # [{"name": "Rajesh Gajjar", "phone": "+919427047705"}, ...]
    """
    result = import_leads(file_path)
    return [lead.to_dict() for lead in result.leads]
