from __future__ import annotations

from datetime import datetime, timezone
from typing import Optional

from pymongo.collection import Collection
from pymongo.errors import DuplicateKeyError, PyMongoError

from config import settings
from logger import logger
from db.mongodb_client import get_client
from db.models import CallRecord, CallStatus
from db.exceptions import DatabaseOperationError, DatabaseValidationError, DatabaseNotFoundError


class CallRepository:

    def __init__(self) -> None:
        self._collection_name = settings.mongo_collection_calls

    @property
    def _collection(self) -> Collection:
        return get_client().db[self._collection_name]

    def insert(self, record: CallRecord) -> str:
        """
        Insert a call record. Returns the inserted document's _id as string.

        Raises:
            DatabaseValidationError: record fails pydantic validation.
            DatabaseOperationError:  MongoDB write failed.
        """
        if not record.name or not record.phone_number:
            raise DatabaseValidationError("name and phone_number are required")

        try:
            doc = record.to_document()
            doc["created_at"] = doc["created_at"].replace(tzinfo=timezone.utc)
            doc["updated_at"] = doc["updated_at"].replace(tzinfo=timezone.utc)

            result = get_client().execute_with_retry(
                self._collection.insert_one, doc
            )
            inserted_id = str(result.inserted_id)
            logger.info(f"Call record inserted — id={inserted_id} phone={record.phone_number}")
            return inserted_id
        except DatabaseOperationError:
            raise
        except PyMongoError as exc:
            raise DatabaseOperationError(f"Failed to insert call record: {exc}") from exc

    def find_by_id(self, record_id: str) -> Optional[CallRecord]:
        from bson.objectid import ObjectId
        try:
            doc = get_client().execute_with_retry(
                self._collection.find_one, {"_id": ObjectId(record_id)}
            )
        except PyMongoError as exc:
            raise DatabaseOperationError(f"Failed to find record {record_id}: {exc}") from exc

        if doc is None:
            return None
        return CallRecord.from_document(doc)

    def find_by_phone(
        self,
        phone_number: str,
        limit: int = 20,
        skip: int = 0,
    ) -> list[CallRecord]:
        try:
            cleaned = phone_number.strip().replace(" ", "").replace("-", "")
            if not cleaned.startswith("+"):
                cleaned = "+" + cleaned

            docs = get_client().execute_with_retry(
                self._collection.find(
                    {"phone_number": cleaned}
                )
                .sort("created_at", -1)
                .skip(skip)
                .limit(limit)
            )
            return [CallRecord.from_document(d) for d in docs]
        except PyMongoError as exc:
            raise DatabaseOperationError(
                f"Failed to query by phone {phone_number}: {exc}"
            ) from exc

    def find_by_intent(
        self,
        intent: str,
        limit: int = 50,
        skip: int = 0,
    ) -> list[CallRecord]:
        intent_upper = intent.upper()
        if intent_upper not in ("YES", "NO", "UNKNOWN"):
            raise DatabaseValidationError(f"Invalid intent filter: {intent}")

        try:
            docs = get_client().execute_with_retry(
                self._collection.find({"intent": intent_upper})
                .sort("created_at", -1)
                .skip(skip)
                .limit(limit)
            )
            return [CallRecord.from_document(d) for d in docs]
        except PyMongoError as exc:
            raise DatabaseOperationError(
                f"Failed to query by intent {intent}: {exc}"
            ) from exc

    def update_status(
        self,
        record_id: str,
        call_status: CallStatus,
        transcription: Optional[str] = None,
        intent: Optional[str] = None,
        recording_file: Optional[str] = None,
    ) -> bool:
        from bson.objectid import ObjectId

        update = {
            "call_status": call_status.value if isinstance(call_status, CallStatus) else call_status,
            "updated_at": datetime.now(timezone.utc),
        }
        if transcription is not None:
            update["transcription"] = transcription
        if intent is not None:
            update["intent"] = intent.upper()
        if recording_file is not None:
            update["recording_file"] = recording_file

        try:
            result = get_client().execute_with_retry(
                self._collection.update_one,
                {"_id": ObjectId(record_id)},
                {"$set": update},
            )
            if result.matched_count == 0:
                raise DatabaseNotFoundError(f"No record found with id={record_id}")
            logger.info(
                f"Call record {record_id} updated — status={update['call_status']} "
                f"intent={update.get('intent', 'unchanged')}"
            )
            return result.modified_count > 0
        except (DatabaseNotFoundError, DatabaseOperationError):
            raise
        except PyMongoError as exc:
            raise DatabaseOperationError(
                f"Failed to update record {record_id}: {exc}"
            ) from exc

    def delete(self, record_id: str) -> bool:
        from bson.objectid import ObjectId

        try:
            result = get_client().execute_with_retry(
                self._collection.delete_one, {"_id": ObjectId(record_id)}
            )
            if result.deleted_count == 0:
                raise DatabaseNotFoundError(f"No record found with id={record_id}")
            logger.info(f"Call record {record_id} deleted")
            return True
        except (DatabaseNotFoundError, DatabaseOperationError):
            raise
        except PyMongoError as exc:
            raise DatabaseOperationError(
                f"Failed to delete record {record_id}: {exc}"
            ) from exc

    def count(self, filter_query: Optional[dict] = None) -> int:
        try:
            return get_client().execute_with_retry(
                self._collection.count_documents, filter_query or {}
            )
        except PyMongoError as exc:
            raise DatabaseOperationError(f"Failed to count documents: {exc}") from exc

    def exists(self, phone_number: str) -> bool:
        try:
            cleaned = phone_number.strip().replace(" ", "").replace("-", "")
            if not cleaned.startswith("+"):
                cleaned = "+" + cleaned
            doc = get_client().execute_with_retry(
                self._collection.find_one,
                {"phone_number": cleaned},
                projection={"_id": 1},
            )
            return doc is not None
        except PyMongoError as exc:
            raise DatabaseOperationError(
                f"Failed to check existence for {phone_number}: {exc}"
            ) from exc
