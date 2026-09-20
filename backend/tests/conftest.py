"""Test fixtures.

Every test runs against a throwaway data directory and the offline provider, so
no test can touch the developer's real store or spend money on API calls.
"""

from __future__ import annotations

import os
import tempfile

import pytest

os.environ["AI_PROVIDER"] = "mock"
os.environ["DATA_DIR"] = tempfile.mkdtemp(prefix="vibe-test-")

from app.api import deps  # noqa: E402
from app.providers.registry import reset_provider_cache  # noqa: E402
from app.schemas import Message  # noqa: E402


@pytest.fixture()
def store(tmp_path, monkeypatch):
    monkeypatch.setenv("DATA_DIR", str(tmp_path))
    monkeypatch.delenv("SUPABASE_URL", raising=False)
    monkeypatch.delenv("SUPABASE_ANON_KEY", raising=False)
    deps.reset_caches()
    reset_provider_cache()
    yield deps.get_store()
    deps.reset_caches()
    reset_provider_cache()


@pytest.fixture()
def client(tmp_path, monkeypatch):
    monkeypatch.setenv("DATA_DIR", str(tmp_path))
    monkeypatch.setenv("AI_PROVIDER", "mock")
    monkeypatch.delenv("SUPABASE_URL", raising=False)
    monkeypatch.delenv("SUPABASE_ANON_KEY", raising=False)
    deps.reset_caches()
    reset_provider_cache()

    from fastapi.testclient import TestClient

    from app.main import app

    with TestClient(app) as test_client:
        yield test_client

    deps.reset_caches()
    reset_provider_cache()


def msgs(*pairs: tuple[str, str]) -> list[Message]:
    """Compact helper: msgs(("them", "hi"), ("me", "niaje"))."""
    return [Message(speaker=who, text=text) for who, text in pairs]  # type: ignore[arg-type]
