class DatabaseBaseError(Exception):
    """Root for all database subsystem errors."""


class DatabaseConnectionError(DatabaseBaseError):
    """MongoDB connection or authentication failure."""


class DatabaseOperationError(DatabaseBaseError):
    """A database read/write operation failed."""


class DatabaseValidationError(DatabaseBaseError):
    """Document failed schema validation before write."""


class DatabaseNotFoundError(DatabaseBaseError):
    """No document matched the query criteria."""
