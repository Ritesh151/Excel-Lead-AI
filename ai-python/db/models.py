from __future__ import annotations

from datetime import datetime, timezone
from enum import Enum
from typing import Optional

from pydantic import BaseModel, Field, field_validator


class CallStatus(str, Enum):
    COMPLETED = "completed"
    FAILED = "failed"
    NO_ANSWER = "no_answer"
    BUSY = "busy"
    RINGING = "ringing"
    CALLING = "calling"

    def __str__(self) -> str:
        return self.value


class CallRecord(BaseModel):
    name: str = Field(..., min_length=1, max_length=200)
    phone_number: str = Field(..., pattern=r"^\+?[1-9]\d{6,14}$")
    call_status: CallStatus = Field(default=CallStatus.COMPLETED)
    transcription: str = Field(default="", max_length=5000)
    intent: str = Field(default="UNKNOWN", pattern=r"^(YES|NO|UNKNOWN)$")
    recording_file: str = Field(default="", max_length=500)
    created_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))
    updated_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))

    @field_validator("phone_number")
    @classmethod
    def validate_phone(cls, v: str) -> str:
        cleaned = v.strip().replace(" ", "").replace("-", "")
        if not cleaned.startswith("+"):
            cleaned = "+" + cleaned
        return cleaned

    @field_validator("recording_file")
    @classmethod
    def validate_recording_path(cls, v: str) -> str:
        if v and not v.strip():
            return ""
        return v.strip()

    def to_document(self) -> dict:
        return self.model_dump()

    @classmethod
    def from_document(cls, doc: dict) -> "CallRecord":
        doc.pop("_id", None)
        return cls(**doc)


MONGO_SCHEMA_VALIDATOR = {
    "$jsonSchema": {
        "bsonType": "object",
        "required": ["name", "phone_number", "call_status"],
        "properties": {
            "name": {
                "bsonType": "string",
                "minLength": 1,
                "maxLength": 200,
                "description": "Caller name",
            },
            "phone_number": {
                "bsonType": "string",
                "pattern": r"^\+?[1-9]\d{6,14}$",
                "description": "Phone number with country code",
            },
            "call_status": {
                "enum": ["completed", "failed", "no_answer", "busy", "ringing", "calling"],
                "description": "Current status of the call",
            },
            "transcription": {
                "bsonType": "string",
                "maxLength": 5000,
                "description": "ASR transcription text",
            },
            "intent": {
                "enum": ["YES", "NO", "UNKNOWN"],
                "description": "Detected caller intent",
            },
            "recording_file": {
                "bsonType": "string",
                "maxLength": 500,
                "description": "Path to the audio recording file",
            },
            "created_at": {
                "bsonType": "date",
                "description": "Record creation timestamp",
            },
            "updated_at": {
                "bsonType": "date",
                "description": "Record last update timestamp",
            },
        },
    }
}
