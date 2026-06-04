from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum
from typing import Optional


class CallStage(str, Enum):
    IDLE = "idle"
    LOADING_LEADS = "loading_leads"
    LEADS_LOADED = "leads_loaded"
    DIALING = "dialing"
    WAITING_CONNECT = "waiting_connect"
    CONNECTED = "connected"
    PLAYING_GREETING = "playing_greeting"
    RECORDING = "recording"
    TRANSCRIBING = "transcribing"
    EVALUATING = "evaluating"
    PLAYING_THANK_YOU = "playing_thank_you"
    SAVING = "saving"
    HANGING_UP = "hanging_up"
    COMPLETED = "completed"
    FAILED = "failed"
    SKIPPED = "skipped"


class Intent(str, Enum):
    YES = "YES"
    NO = "NO"
    UNKNOWN = "UNKNOWN"


@dataclass
class LeadResult:
    lead_index: int
    total_leads: int
    name: str
    phone: str
    stage: CallStage
    intent: Intent = Intent.UNKNOWN
    transcription: str = ""
    recording_file: str = ""
    db_id: Optional[str] = None
    error_message: Optional[str] = None
    retry_count: int = 0
    duration_seconds: float = 0.0
    timestamp: str = field(default_factory=lambda: datetime.now(timezone.utc).isoformat())

    def to_dict(self) -> dict:
        return {
            "lead_index": self.lead_index,
            "total_leads": self.total_leads,
            "name": self.name,
            "phone": self.phone,
            "stage": self.stage.value,
            "intent": self.intent.value,
            "transcription": self.transcription,
            "recording_file": self.recording_file,
            "db_id": self.db_id,
            "error": self.error_message,
            "retry": self.retry_count,
            "duration": round(self.duration_seconds, 1),
        }


@dataclass
class CallAttemptResult:
    success: bool
    stage: CallStage
    intent: Intent = Intent.UNKNOWN
    transcription: str = ""
    recording_file: str = ""
    error_message: Optional[str] = None
    should_retry: bool = False
