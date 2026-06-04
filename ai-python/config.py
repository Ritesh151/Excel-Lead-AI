"""
config.py
Centralised settings loaded from environment variables via pydantic-settings.
Import `settings` anywhere to access typed config values.
"""

from pydantic_settings import BaseSettings
from pydantic import Field


class Settings(BaseSettings):
    # ── Server ────────────────────────────────────────────────────────────────
    host: str = Field("0.0.0.0", env="HOST")
    port: int = Field(8000, env="PORT")

    # ── MongoDB ───────────────────────────────────────────────────────────────
    mongo_uri: str = Field("mongodb://localhost:27017/ai_calling", env="MONGO_URI")
    mongo_db_name: str = Field("ai_calling", env="MONGO_DB_NAME")
    mongo_collection_calls: str = Field("call_records", env="MONGO_COLLECTION_CALLS")
    mongo_max_pool_size: int = Field(10, env="MONGO_MAX_POOL_SIZE")
    mongo_min_pool_size: int = Field(1, env="MONGO_MIN_POOL_SIZE")
    mongo_server_selection_timeout_ms: int = Field(5000, env="MONGO_SERVER_SELECTION_TIMEOUT_MS")
    mongo_connect_timeout_ms: int = Field(5000, env="MONGO_CONNECT_TIMEOUT_MS")
    mongo_socket_timeout_ms: int = Field(10000, env="MONGO_SOCKET_TIMEOUT_MS")
    mongo_retry_writes: bool = Field(True, env="MONGO_RETRY_WRITES")
    mongo_retry_reads: bool = Field(True, env="MONGO_RETRY_READS")
    mongo_retry_max_attempts: int = Field(3, env="MONGO_RETRY_MAX_ATTEMPTS")
    mongo_retry_delay_ms: int = Field(500, env="MONGO_RETRY_DELAY_MS")

    # ── Paths ─────────────────────────────────────────────────────────────────
    leads_file_path: str = Field("../leads/leads.xlsx", env="LEADS_FILE_PATH")
    audio_dir: str = Field("../audio", env="AUDIO_DIR")
    recordings_dir: str = Field("../recordings", env="RECORDINGS_DIR")

    # ── ADB ───────────────────────────────────────────────────────────────────
    adb_device_serial: str = Field("", env="ADB_DEVICE_SERIAL")

    # ── Audio Playback ────────────────────────────────────────────────────────
    # Master volume: 0.0 (silent) → 1.0 (full)
    audio_volume: float = Field(1.0, env="AUDIO_VOLUME")
    # Output device index for sounddevice (-1 = system default)
    audio_output_device: int = Field(-1, env="AUDIO_OUTPUT_DEVICE")

    # ── Recording ─────────────────────────────────────────────────────────────
    # How long to record caller response in seconds
    recording_duration: float = Field(5.0, env="RECORDING_DURATION")
    # Sample rate: 8000, 16000, 22050, 44100, 48000
    recording_sample_rate: int = Field(16_000, env="RECORDING_SAMPLE_RATE")
    # 1 = mono (recommended for speech), 2 = stereo
    recording_channels: int = Field(1, env="RECORDING_CHANNELS")
    # sounddevice input device index (-1 = system default)
    recording_input_device: int = Field(-1, env="RECORDING_INPUT_DEVICE")
    # Apply noise gate before saving
    recording_noise_reduction: bool = Field(False, env="RECORDING_NOISE_REDUCTION")
    # RMS threshold below which a frame is considered silence (0.0–1.0)
    recording_silence_threshold: float = Field(0.01, env="RECORDING_SILENCE_THRESHOLD")
    # Minimum fraction of frames above threshold to accept recording (0.0–1.0)
    recording_min_speech_ratio: float = Field(0.05, env="RECORDING_MIN_SPEECH_RATIO")

    # ── Call Flow ─────────────────────────────────────────────────────────────
    call_flow_greeting_file: str = Field("greeting.wav", env="CALL_FLOW_GREETING_FILE")
    call_flow_thank_you_file: str = Field("thank_you.wav", env="CALL_FLOW_THANK_YOU_FILE")
    call_flow_recording_duration: float = Field(5.0, env="CALL_FLOW_RECORDING_DURATION")
    call_flow_between_calls_delay: float = Field(3.0, env="CALL_FLOW_BETWEEN_CALLS_DELAY")
    call_flow_connect_timeout: float = Field(60.0, env="CALL_FLOW_CONNECT_TIMEOUT")
    call_flow_max_call_duration: float = Field(120.0, env="CALL_FLOW_MAX_CALL_DURATION")
    call_flow_retry_attempts: int = Field(2, env="CALL_FLOW_RETRY_ATTEMPTS")
    call_flow_retry_delay: float = Field(10.0, env="CALL_FLOW_RETRY_DELAY")
    call_flow_loop: bool = Field(False, env="CALL_FLOW_LOOP")

    # ── Whisper ───────────────────────────────────────────────────────────────
    # Model size: tiny | base | small | medium | large-v2 | large-v3
    whisper_model: str = Field("base", env="WHISPER_MODEL")
    # Compute device: cpu | cuda (use cuda only if GPU is available)
    whisper_device: str = Field("cpu", env="WHISPER_DEVICE")
    # Compute type: int8 (fast/low-mem) | float16 (GPU) | float32 (CPU precise)
    whisper_compute_type: str = Field("int8", env="WHISPER_COMPUTE_TYPE")
    # Primary language hint — "hi" for Hindi, "en" for English, None for auto
    whisper_language: str = Field("hi", env="WHISPER_LANGUAGE")
    # Beam size for decoding (higher = more accurate, slower)
    whisper_beam_size: int = Field(3, env="WHISPER_BEAM_SIZE")
    # VAD (Voice Activity Detection) filter — removes silence before transcribing
    whisper_vad_filter: bool = Field(True, env="WHISPER_VAD_FILTER")

    # ── backend-node integration ────────────────────────────────────────────────
    backend_node_url: str = Field("http://localhost:3000", env="BACKEND_NODE_URL")
    internal_api_token: str = Field("", env="INTERNAL_API_TOKEN")

    class Config:
        env_file = ".env"
        env_file_encoding = "utf-8"
        extra = "ignore"


# Singleton — import this throughout the app
settings = Settings()
