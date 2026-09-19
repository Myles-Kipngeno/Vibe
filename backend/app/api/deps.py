"""Shared FastAPI dependencies, including which storage backend a request uses.

There are two modes, and the app picks one from configuration:

* **Local mode** (no Supabase configured): one user, one JSON file on this
  machine, no sign-in. This is the default and keeps the app usable offline.
* **Account mode** (`SUPABASE_URL` + `SUPABASE_ANON_KEY` set): every request must
  carry the caller's Supabase access token, and all data access runs through
  Postgres under that identity so Row Level Security enforces isolation.

`get_store` returns the right one, and everything downstream is written against
`BaseStore` and cannot tell the difference.
"""

from __future__ import annotations

from functools import lru_cache

import httpx
from fastapi import Header, HTTPException

from ..config import get_settings
from ..providers.base import LLMProvider
from ..providers.registry import get_provider as _get_provider
from ..storage.base import BaseStore
from ..storage.store import LocalStore
from ..storage.supabase_store import SupabaseStore


@lru_cache(maxsize=1)
def get_local_store() -> LocalStore:
    return LocalStore(get_settings().data_dir)


@lru_cache(maxsize=1)
def _http_client() -> httpx.Client:
    """One pooled client for all Supabase traffic."""
    return httpx.Client(timeout=15.0)


def _bearer(authorization: str | None) -> str | None:
    if not authorization:
        return None
    scheme, _, token = authorization.partition(" ")
    if scheme.lower() != "bearer" or not token.strip():
        return None
    return token.strip()


def get_store(authorization: str | None = Header(default=None)) -> BaseStore:
    settings = get_settings()
    if not settings.supabase_enabled:
        return get_local_store()

    token = _bearer(authorization)
    if not token:
        raise HTTPException(
            status_code=401,
            detail="Sign in to continue.",
            headers={"WWW-Authenticate": "Bearer"},
        )

    assert settings.supabase_url and settings.supabase_anon_key
    return SupabaseStore(
        url=settings.supabase_url,
        anon_key=settings.supabase_anon_key,
        access_token=token,
        client=_http_client(),
    )


def get_provider() -> LLMProvider:
    return _get_provider()


def reset_caches() -> None:
    """Tests point DATA_DIR somewhere temporary and call this."""
    get_local_store.cache_clear()
    get_settings.cache_clear()


# Kept for older imports that expected the single-user accessor.
get_store_singleton = get_local_store
