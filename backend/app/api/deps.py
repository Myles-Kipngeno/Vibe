"""Shared FastAPI dependencies."""

from __future__ import annotations

from functools import lru_cache

from ..config import get_settings
from ..providers.base import LLMProvider
from ..providers.registry import get_provider as _get_provider
from ..storage.store import Store


@lru_cache(maxsize=1)
def get_store() -> Store:
    return Store(get_settings().data_dir)


def get_provider() -> LLMProvider:
    return _get_provider()


def reset_caches() -> None:
    """Tests point DATA_DIR somewhere temporary and call this."""
    get_store.cache_clear()
    get_settings.cache_clear()
