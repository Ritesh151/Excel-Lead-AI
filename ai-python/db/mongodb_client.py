from __future__ import annotations

import time
from typing import Optional

from pymongo import MongoClient
from pymongo.errors import (
    AutoReconnect,
    ConnectionFailure,
    NetworkTimeout,
    ServerSelectionTimeoutError,
    PyMongoError,
)

from config import settings
from logger import logger
from db.exceptions import DatabaseConnectionError, DatabaseOperationError


_client: Optional[MongoClient] = None


class MongoDBClient:

    def __init__(self) -> None:
        self._client: Optional[MongoClient] = None
        self._db = None
        self._uri = settings.mongo_uri
        self._db_name = settings.mongo_db_name

    def connect(self) -> MongoClient:
        if self._client is not None:
            return self._client

        logger.info(f"Connecting to MongoDB at {self._uri}")
        try:
            self._client = MongoClient(
                self._uri,
                maxPoolSize=settings.mongo_max_pool_size,
                minPoolSize=settings.mongo_min_pool_size,
                serverSelectionTimeoutMS=settings.mongo_server_selection_timeout_ms,
                connectTimeoutMS=settings.mongo_connect_timeout_ms,
                socketTimeoutMS=settings.mongo_socket_timeout_ms,
                retryWrites=settings.mongo_retry_writes,
                retryReads=settings.mongo_retry_reads,
            )
            self._client.admin.command("ping")
            self._db = self._client[self._db_name]
            logger.info(f"MongoDB connected — db={self._db_name} pool={settings.mongo_max_pool_size}")
        except (ConnectionFailure, ServerSelectionTimeoutError) as exc:
            raise DatabaseConnectionError(
                f"Failed to connect to MongoDB at {self._uri}: {exc}"
            ) from exc

        return self._client

    @property
    def db(self):
        if self._db is None:
            raise DatabaseConnectionError("Not connected — call connect() first")
        return self._db

    @property
    def client(self) -> MongoClient:
        if self._client is None:
            raise DatabaseConnectionError("Not connected — call connect() first")
        return self._client

    def close(self) -> None:
        if self._client is not None:
            self._client.close()
            self._client = None
            self._db = None
            logger.info("MongoDB connection closed")

    def is_connected(self) -> bool:
        if self._client is None:
            return False
        try:
            self._client.admin.command("ping")
            return True
        except PyMongoError:
            return False

    def execute_with_retry(self, operation, *args, **kwargs):
        max_attempts = settings.mongo_retry_max_attempts
        delay_ms = settings.mongo_retry_delay_ms

        last_error = None
        for attempt in range(1, max_attempts + 1):
            try:
                return operation(*args, **kwargs)
            except (AutoReconnect, NetworkTimeout) as exc:
                last_error = exc
                if attempt < max_attempts:
                    logger.warning(
                        f"Retry {attempt}/{max_attempts} after MongoDB error: {exc}"
                    )
                    time.sleep(delay_ms / 1000)
                else:
                    logger.error(f"All {max_attempts} retries exhausted: {exc}")
            except PyMongoError as exc:
                raise DatabaseOperationError(
                    f"MongoDB operation failed: {exc}"
                ) from exc

        raise DatabaseOperationError(
            f"Operation failed after {max_attempts} retries: {last_error}"
        ) from last_error


_client_wrapper = MongoDBClient()


def get_client() -> MongoDBClient:
    return _client_wrapper


def close_client() -> None:
    _client_wrapper.close()
