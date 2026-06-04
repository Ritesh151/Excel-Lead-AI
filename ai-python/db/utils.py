from __future__ import annotations

from pymongo.errors import PyMongoError, OperationFailure

from config import settings
from logger import logger
from db.mongodb_client import get_client
from db.models import MONGO_SCHEMA_VALIDATOR


INDEX_SPECS: list[dict] = [
    {
        "keys": [("phone_number", 1), ("created_at", -1)],
        "name": "idx_phone_created",
        "background": True,
    },
    {
        "keys": [("call_status", 1)],
        "name": "idx_call_status",
        "background": True,
    },
    {
        "keys": [("intent", 1)],
        "name": "idx_intent",
        "background": True,
    },
    {
        "keys": [("created_at", -1)],
        "name": "idx_created_at",
        "background": True,
    },
    {
        "keys": [("phone_number", 1)],
        "name": "idx_phone_number",
        "background": True,
    },
]


def ensure_indexes() -> int:
    """
    Create all required indexes on the call_records collection.
    Returns the number of indexes created/existing.
    """
    collection_name = settings.mongo_collection_calls
    collection = get_client().db[collection_name]
    created = 0

    for spec in INDEX_SPECS:
        try:
            existing = collection.index_information()
            if spec["name"] in existing:
                logger.debug(f"Index '{spec['name']}' already exists")
                continue

            collection.create_index(
                spec["keys"],
                name=spec["name"],
                background=spec.get("background", True),
            )
            created += 1
            logger.info(f"Created index '{spec['name']}' on {collection_name}")
        except OperationFailure as exc:
            logger.error(f"Failed to create index '{spec['name']}': {exc}")
        except PyMongoError as exc:
            logger.error(f"MongoDB error creating index '{spec['name']}': {exc}")

    logger.info(f"Index check complete — {created} new, {len(INDEX_SPECS)} total defined")
    return created


def ensure_schema_validation() -> bool:
    """
    Apply JSON Schema validation to the call_records collection.
    Creates the collection if it doesn't exist.

    Returns True if validation was applied.
    """
    collection_name = settings.mongo_collection_calls
    db = get_client().db

    try:
        existing_collections = db.list_collection_names()
        if collection_name in existing_collections:
            cmd = {
                "collMod": collection_name,
                "validator": MONGO_SCHEMA_VALIDATOR,
                "validationLevel": "moderate",
                "validationAction": "warn",
            }
            db.command(cmd)
            logger.info(f"Schema validation updated on '{collection_name}'")
        else:
            db.create_collection(
                collection_name,
                validator=MONGO_SCHEMA_VALIDATOR,
                validationLevel="moderate",
                validationAction="warn",
            )
            logger.info(f"Collection '{collection_name}' created with schema validation")
        return True
    except OperationFailure as exc:
        logger.error(f"Failed to set schema validation: {exc}")
        return False
    except PyMongoError as exc:
        logger.error(f"MongoDB error during schema validation setup: {exc}")
        return False


def drop_indexes() -> int:
    """Drop all custom indexes (for testing/cleanup). Returns count dropped."""
    collection_name = settings.mongo_collection_calls
    collection = get_client().db[collection_name]
    existing = collection.index_information()

    dropped = 0
    for spec in INDEX_SPECS:
        name = spec["name"]
        if name in existing:
            try:
                collection.drop_index(name)
                dropped += 1
                logger.info(f"Dropped index '{name}'")
            except PyMongoError as exc:
                logger.error(f"Failed to drop index '{name}': {exc}")

    logger.info(f"Dropped {dropped} indexes from '{collection_name}'")
    return dropped


def list_indexes() -> list[dict]:
    """Return all indexes on the call records collection."""
    collection_name = settings.mongo_collection_calls
    collection = get_client().db[collection_name]

    indexes = []
    try:
        for name, spec in collection.index_information().items():
            indexes.append({
                "name": name,
                "keys": [(k, v) for k, v in spec.get("key", [])],
                "unique": spec.get("unique", False),
                "background": spec.get("background", False),
            })
    except PyMongoError as exc:
        logger.error(f"Failed to list indexes: {exc}")

    return indexes


def ping() -> bool:
    """Check MongoDB connectivity. Returns True if reachable."""
    try:
        get_client().client.admin.command("ping")
        return True
    except PyMongoError:
        return False
