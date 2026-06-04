"""
routers package
Exports all FastAPI routers so main.py has a single clean import.
"""

from .health import router as health_router
from .call import router as call_router
from .campaign import router as campaign_router

__all__ = ["health_router", "call_router", "campaign_router"]
