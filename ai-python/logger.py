"""
logger.py
Centralised Loguru logger configuration.
Import `logger` from here instead of loguru directly so all modules
share the same sink configuration.
"""

import sys
from pathlib import Path
from loguru import logger

LOG_DIR = Path(__file__).parent.parent / "logs"
LOG_DIR.mkdir(parents=True, exist_ok=True)

# Remove the default stderr sink so we can add our own with formatting
logger.remove()

# ── Console sink ──────────────────────────────────────────────────────────────
logger.add(
    sys.stdout,
    level="DEBUG",
    format=(
        "<green>{time:YYYY-MM-DD HH:mm:ss}</green> | "
        "<level>{level: <8}</level> | "
        "<cyan>{name}</cyan>:<cyan>{line}</cyan> — <level>{message}</level>"
    ),
    colorize=True,
)

# ── File sink — combined ───────────────────────────────────────────────────────
logger.add(
    LOG_DIR / "ai_engine.log",
    level="DEBUG",
    format="{time:YYYY-MM-DD HH:mm:ss} | {level: <8} | {name}:{line} — {message}",
    rotation="10 MB",
    retention="7 days",
    compression="zip",
)

# ── File sink — errors only ───────────────────────────────────────────────────
logger.add(
    LOG_DIR / "ai_engine_error.log",
    level="ERROR",
    format="{time:YYYY-MM-DD HH:mm:ss} | {level: <8} | {name}:{line} — {message}",
    rotation="5 MB",
    retention="14 days",
    compression="zip",
)

__all__ = ["logger"]
