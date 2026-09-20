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

from app.config import (
    HOSTED_MARKERS,
    Settings,
    is_local_origin,
    on_a_hosting_platform,
)


def settings_with(
    origins: str, supabase: bool = False, hosted: bool = False
) -> Settings:
    return Settings(
        cors_origins=tuple(o.strip() for o in origins.split(",") if o.strip()),
        supabase_url="https://example.supabase.co" if supabase else None,
        supabase_anon_key="anon-key" if supabase else None,
        hosted=hosted,
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


# --- being deployed is exposure, whatever CORS says -----------------------------


def test_a_hosted_service_without_accounts_is_refused():
    """The case the first version of this check missed.

    CORS looked like a sufficient proxy for exposure. It is not: it is enforced
    by browsers, so a public URL with the default localhost origins is still
    open to curl, which is what anyone pointing at it would actually use.
    """
    deployed = settings_with("http://localhost:5173", hosted=True)
    assert deployed.public_origins == ()
    assert deployed.is_exposed_without_accounts


def test_a_hosted_service_with_accounts_is_fine():
    assert not settings_with(
        "http://localhost:5173", supabase=True, hosted=True
    ).is_exposed_without_accounts


def test_the_same_config_on_a_laptop_is_fine():
    """Nothing changes for local development."""
    assert not settings_with(
        "http://localhost:5173", hosted=False
    ).is_exposed_without_accounts


@pytest.mark.parametrize(
    "marker", ["RENDER", "FLY_APP_NAME", "RAILWAY_ENVIRONMENT", "DYNO"]
)
def test_the_platforms_we_actually_target_are_listed(marker):
    """Named here rather than read from the list the code defines.

    Parametrising over HOSTED_MARKERS itself cannot notice the list shrinking:
    delete an entry and the case for it simply stops existing. These are spelled
    out so that removing one is a failure, not a silent gap.
    """
    assert marker in HOSTED_MARKERS


@pytest.mark.parametrize("marker", HOSTED_MARKERS)
def test_each_platform_marker_is_detected(marker, monkeypatch):
    for m in HOSTED_MARKERS:
        monkeypatch.delenv(m, raising=False)
    assert not on_a_hosting_platform()
    monkeypatch.setenv(marker, "1")
    assert on_a_hosting_platform(), marker


def test_a_laptop_is_not_mistaken_for_a_platform(monkeypatch):
    for m in HOSTED_MARKERS:
        monkeypatch.delenv(m, raising=False)
    assert not on_a_hosting_platform()


def test_the_hosted_refusal_explains_itself_too():
    from app.config import exposure_error

    message = exposure_error(settings_with("http://localhost:5173", hosted=True))
    assert "hosting platform" in message, "say why it thinks it is exposed"
    assert "your own machine" in message, "the way out differs from the CORS case"
    assert "SUPABASE_URL" in message
    assert "DELETE /api/data" in message
