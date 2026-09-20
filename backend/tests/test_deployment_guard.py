"""The app must not serve private data to the internet without a sign-in.

Local mode has no authentication, which is correct while the backend listens
only on this machine. Naming a public CORS origin is the moment that stops
being true, and the failure is the kind nobody notices in time: by the time it
is spotted, the contacts and the remembered context have been readable by
whoever found the URL, along with the endpoint that deletes them.

So this is a refusal to start, not a warning, and these tests are here to keep
it that way.
"""

from __future__ import annotations

import pytest

from app.config import Settings, is_local_origin


def settings_with(origins: str, supabase: bool = False) -> Settings:
    return Settings(
        cors_origins=tuple(o.strip() for o in origins.split(",") if o.strip()),
        supabase_url="https://example.supabase.co" if supabase else None,
        supabase_anon_key="anon-key" if supabase else None,
    )


# --- what counts as this machine ------------------------------------------------


@pytest.mark.parametrize(
    "origin",
    [
        "http://localhost:5173",
        "http://127.0.0.1:5173",
        "http://localhost",
        "https://127.0.0.1:8000",
        "http://[::1]:5173",
        "http://0.0.0.0:5173",
    ],
)
def test_local_origins_are_recognised(origin):
    assert is_local_origin(origin)


@pytest.mark.parametrize(
    "origin",
    [
        "https://vibe.vercel.app",
        "http://vibe.vercel.app",
        "https://example.com",
        "http://192.168.1.5:5173",
        "https://localhost.evil.com",
    ],
)
def test_public_origins_are_not_mistaken_for_local(origin):
    """`localhost.evil.com` is a real domain someone else controls."""
    assert not is_local_origin(origin)


# --- the refusal ----------------------------------------------------------------


def test_a_public_origin_without_accounts_is_refused():
    assert settings_with("https://vibe.vercel.app").is_exposed_without_accounts


def test_the_same_origin_with_accounts_is_fine():
    """Accounts are the fix. With them on, Postgres decides who sees what."""
    assert not settings_with(
        "https://vibe.vercel.app", supabase=True
    ).is_exposed_without_accounts


def test_local_only_stays_allowed_without_accounts():
    """The default setup must keep working with no configuration at all."""
    assert not settings_with(
        "http://localhost:5173,http://127.0.0.1:5173"
    ).is_exposed_without_accounts


def test_one_public_origin_among_local_ones_still_refuses():
    """A mixed list is exposed. The weakest entry decides."""
    assert settings_with(
        "http://localhost:5173,https://vibe.vercel.app"
    ).is_exposed_without_accounts


def test_no_origins_configured_is_not_exposure():
    assert not settings_with("").is_exposed_without_accounts


def test_the_refusal_names_the_origin_and_the_fix():
    """An error that does not say what to do gets worked around instead."""
    from app.config import exposure_error

    message = exposure_error(settings_with("https://vibe.vercel.app"))
    assert "https://vibe.vercel.app" in message, "say which origin tripped it"
    assert "SUPABASE_URL" in message and "SUPABASE_ANON_KEY" in message
    assert "CORS_ORIGINS on localhost" in message, "give the other way out too"
    assert "DELETE /api/data" in message, "say what is actually at stake"
