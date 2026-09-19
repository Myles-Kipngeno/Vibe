"""The API's behaviour once Supabase accounts are configured.

Local mode stays open (one user, one machine, no sign-in). The moment Supabase
is configured, every route that touches stored data requires the caller's own
access token -- and the token is passed through to Postgres rather than being
turned into a trust decision here.
"""

from __future__ import annotations

import httpx
import pytest
from fastapi.testclient import TestClient

from app.api import deps
from app.providers.registry import reset_provider_cache
from tests.test_supabase_store import USER_ID, FakePostgrest, make_token


@pytest.fixture()
def supabase_client(tmp_path, monkeypatch):
    """An app configured for accounts, with Supabase replaced by a fake."""
    monkeypatch.setenv("DATA_DIR", str(tmp_path))
    monkeypatch.setenv("AI_PROVIDER", "mock")
    monkeypatch.setenv("SUPABASE_URL", "https://example.supabase.co")
    monkeypatch.setenv("SUPABASE_ANON_KEY", "anon-key")
    deps.reset_caches()
    reset_provider_cache()

    fake = FakePostgrest({"contact_profiles": [], "communication_preferences": []})
    monkeypatch.setattr(
        deps, "_http_client", lambda: httpx.Client(transport=httpx.MockTransport(fake.handler))
    )

    from app.main import app

    with TestClient(app) as client:
        yield client, fake

    deps.reset_caches()
    reset_provider_cache()


AUTH = {"Authorization": f"Bearer {make_token()}"}


def test_health_says_sign_in_is_required(supabase_client):
    client, _ = supabase_client
    body = client.get("/api/health").json()
    assert body["auth_required"] is True
    assert body["supabase_url"] == "https://example.supabase.co"
    assert any("Row Level Security" in note for note in body["notes"])


def test_health_itself_stays_public(supabase_client):
    """The frontend has to be able to discover the auth mode before signing in."""
    client, _ = supabase_client
    assert client.get("/api/health").status_code == 200


@pytest.mark.parametrize(
    "method,path",
    [
        ("GET", "/api/contacts"),
        ("GET", "/api/style"),
        ("POST", "/api/contacts"),
        ("DELETE", "/api/data"),
    ],
)
def test_data_routes_refuse_anonymous_callers(supabase_client, method, path):
    client, fake = supabase_client
    response = client.request(method, path, json={"name": "Ann"})
    assert response.status_code == 401
    assert response.json()["detail"] == "Sign in to continue."
    assert fake.requests == [], "nothing should reach the database unauthenticated"


@pytest.mark.parametrize(
    "header",
    [
        {"Authorization": "Basic abc"},
        {"Authorization": "Bearer"},
        {"Authorization": "Bearer   "},
    ],
)
def test_malformed_authorization_headers_are_rejected(supabase_client, header):
    client, _ = supabase_client
    assert client.get("/api/contacts", headers=header).status_code == 401


def test_a_signed_in_caller_reaches_the_database_as_themselves(supabase_client):
    client, fake = supabase_client
    assert client.get("/api/contacts", headers=AUTH).status_code == 200

    assert fake.requests, "the request should have been forwarded to Postgres"
    assert fake.last.headers["Authorization"] == AUTH["Authorization"]


def test_an_expired_token_surfaces_as_401_not_500(supabase_client, monkeypatch):
    client, _ = supabase_client
    expired = FakePostgrest(status=401)
    monkeypatch.setattr(
        deps,
        "_http_client",
        lambda: httpx.Client(transport=httpx.MockTransport(expired.handler)),
    )
    response = client.get("/api/contacts", headers=AUTH)
    assert response.status_code == 401
    assert "sign in" in response.json()["detail"].lower()


def test_conversation_analysis_still_needs_an_account(supabase_client):
    """Analysis is local, but it reads stored memories, so it is behind auth too."""
    client, _ = supabase_client
    response = client.post(
        "/api/conversation/analyze",
        json={"messages": [{"speaker": "them", "text": "niaje"}]},
    )
    assert response.status_code == 401


def test_parsing_needs_no_account(supabase_client):
    """Parsing touches no stored data, so it does not need one."""
    client, _ = supabase_client
    response = client.post(
        "/api/conversation/parse",
        json={"raw_text": "Her: hi\nMe: niaje", "me_label": "Me", "them_label": "Her"},
    )
    assert response.status_code == 200


def test_the_service_role_key_is_not_a_configuration_option():
    """Supporting it would move the security boundary out of Postgres."""
    import os

    from app.config import Settings, get_settings

    # No setting exposes it...
    assert not [f for f in Settings.__dataclass_fields__ if "service" in f.lower()]

    # ...and setting the env var changes nothing about how the app behaves.
    os.environ["SUPABASE_SERVICE_ROLE_KEY"] = "should-be-ignored"
    try:
        get_settings.cache_clear()
        values = vars(get_settings())
        assert "should-be-ignored" not in str(values)
    finally:
        del os.environ["SUPABASE_SERVICE_ROLE_KEY"]
        get_settings.cache_clear()


def test_local_mode_still_needs_no_sign_in(client):
    """The default single-user experience is unchanged by any of this."""
    assert client.get("/api/contacts").status_code == 200
    assert client.get("/api/health").json()["auth_required"] is False
    assert USER_ID  # imported fixture data is in use
