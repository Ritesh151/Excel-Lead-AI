"""
database.py
Async MongoDB client using Motor.
Call `connect_db()` on app startup and `close_db()` on shutdown.
Access collections via `get_db()`.
"""

from motor.motor_asyncio import AsyncIOMotorClient
from pymongo.errors import ServerSelectionTimeoutError

from config import settings
from logger import logger

# Module-level client — shared across all requests
_client: AsyncIOMotorClient | None = None


async def connect_db() -> None:
    """Initialise the Motor client and verify connectivity."""
    global _client

    try:
        _client = AsyncIOMotorClient(
            settings.mongo_uri,
            serverSelectionTimeoutMS=5000,
        )
        # Lightweight ping to confirm the connection is live
        await _client.admin.command("ping")
        logger.info(f"MongoDB connected: {settings.mongo_uri}")
    except ServerSelectionTimeoutError as exc:
        logger.error(f"MongoDB connection failed: {exc}")
        raise


async def close_db() -> None:
    """Close the Motor client gracefully."""
    global _client
    if _client is not None:
        _client.close()
        _client = None
        logger.info("MongoDB connection closed")


def get_db():
    """
    Return the database handle.
    Raises RuntimeError if called before connect_db().
    """
    if _client is None:
        raise RuntimeError("Database not initialised — call connect_db() first")
    # Extract DB name from the URI (fallback: ai_calling)
    db_name = settings.mongo_uri.rsplit("/", 1)[-1] or "ai_calling"
    return _client[db_name]
