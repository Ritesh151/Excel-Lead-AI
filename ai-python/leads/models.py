"""
leads/models.py
Pydantic models used throughout the leads pipeline.

LeadRecord      — a single clean, validated lead ready for calling
InvalidLeadRecord — a row that failed validation, saved to logs/invalid_leads.json
ImportResult    — the final summary returned by the importer
"""

from __future__ import annotations

from typing import Optional
from pydantic import BaseModel, field_validator


class LeadRecord(BaseModel):
    """A validated, normalised lead."""

    name: str
    phone: str  # always in E.164 format: +91XXXXXXXXXX

    @field_validator("phone")
    @classmethod
    def phone_must_be_e164(cls, v: str) -> str:
        if not v.startswith("+") or not v[1:].isdigit():
            raise ValueError(f"Phone '{v}' is not valid E.164")
        return v

    def to_dict(self) -> dict:
        return {"name": self.name, "phone": self.phone}


class InvalidLeadRecord(BaseModel):
    """A row that could not be imported, together with the reason."""

    row_number: int               # 1-based, header = row 1
    raw_name: Optional[str]
    raw_phone: Optional[str]
    reason: str                   # human-readable failure reason


class ImportResult(BaseModel):
    """Summary returned by import_leads()."""

    total_rows: int               # data rows read (excluding header)
    valid_count: int
    invalid_count: int
    duplicate_count: int
    leads: list[LeadRecord]       # clean leads, duplicates removed
    invalid_rows: list[InvalidLeadRecord]
