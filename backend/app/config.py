"""Runtime configuration.

Model choice and credentials live here and nowhere else, so switching provider
is an environment change rather than a code change. Nothing in this module is
ever returned to the frontend except the provider *name* and model id.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from functools import lru_cache
from pathlib import Path

try:  # optional: .env loading is a developer convenience, not a requirement
    from dotenv import load_dotenv

    load_dotenv(Path(__file__).resolve().parent.parent / ".env")
except ImportError:  # pragma: no cover
    pass

BACKEND_DIR = Path(__file__).resolve().parent.parent


def _env_bool(key: str, default: bool) -> bool:
    raw = os.getenv(key)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


# Hosts that mean "this machine". A backend reachable only from here needs no
# sign-in, which is why local mode has none.
LOCAL_HOSTS: frozenset[str] = frozenset(
    {"localhost", "127.0.0.1", "::1", "[::1]", "0.0.0.0"}
)


# Environment variables set by hosts that put a service on a public URL. None
# of them is a secret or a guarantee -- they are simply the plainest evidence
# available, from inside the process, that it is not running on a laptop.
HOSTED_MARKERS: tuple[str, ...] = (
    "RENDER",                 # Render
    "FLY_APP_NAME",           # Fly.io
    "RAILWAY_ENVIRONMENT",    # Railway
    "DYNO",                   # Heroku
    "K_SERVICE",              # Google Cloud Run
    "WEBSITE_INSTANCE_ID",    # Azure App Service
    "VERCEL",                 # Vercel
)


def on_a_hosting_platform() -> bool:
    """Whether a platform that serves public traffic is running this."""
    return any(os.getenv(marker) for marker in HOSTED_MARKERS)


def is_local_origin(origin: str) -> bool:
    """Whether a CORS origin refers to this machine."""
    from urllib.parse import urlparse

    host = (urlparse(origin.strip()).hostname or origin.strip()).lower()
    return host in LOCAL_HOSTS


@dataclass(frozen=True)
class Settings:
    provider: str = field(default_factory=lambda: os.getenv("AI_PROVIDER", "auto"))
    anthropic_api_key: str | None = field(
        default_factory=lambda: os.getenv("ANTHROPIC_API_KEY") or None
    )
    model: str = field(default_factory=lambda: os.getenv("AI_MODEL", "claude-opus-5"))
    max_tokens: int = field(default_factory=lambda: int(os.getenv("AI_MAX_TOKENS", "4000")))
    data_dir: Path = field(
        default_factory=lambda: Path(os.getenv("DATA_DIR", str(BACKEND_DIR / "data")))
    )
    cors_origins: tuple[str, ...] = field(
        default_factory=lambda: tuple(
            o.strip()
            for o in os.getenv(
                "CORS_ORIGINS", "http://localhost:5173,http://127.0.0.1:5173"
            ).split(",")
            if o.strip()
        )
    )
    # Off by default: message bodies are private and should not end up in logs.
    log_message_content: bool = field(
        default_factory=lambda: _env_bool("LOG_MESSAGE_CONTENT", False)
    )

    # --- Supabase (optional) ---------------------------------------------
    # Setting both of these switches the app from single-user local storage to
    # accounts. Only the *anon* key belongs here: it is safe to expose because
    # Row Level Security is what protects the data. The service-role key would
    # bypass RLS entirely and is deliberately not supported.
    supabase_url: str | None = field(
        default_factory=lambda: (os.getenv("SUPABASE_URL") or "").strip() or None
    )
    supabase_anon_key: str | None = field(
        default_factory=lambda: (os.getenv("SUPABASE_ANON_KEY") or "").strip() or None
    )

    @property
    def supabase_enabled(self) -> bool:
        return bool(self.supabase_url and self.supabase_anon_key)

    # Set from the environment at startup; overridable in tests.
    hosted: bool = field(default_factory=on_a_hosting_platform)

    @property
    def public_origins(self) -> tuple[str, ...]:
        """Configured origins that are not this machine."""
        return tuple(o for o in self.cors_origins if not is_local_origin(o))

    @property
    def is_exposed_without_accounts(self) -> bool:
        """True when the app would serve private data to anyone who can reach it.

        Local mode has no sign-in because it has never needed one: the backend
        listens on this machine and nobody else can ask it anything. Two things
        end that, and both have to be caught.

        Naming a CORS origin elsewhere is the deliberate one. The other is
        simply being deployed -- which the first version of this check missed,
        because CORS looked like a sufficient proxy for exposure and is not.
        CORS is enforced by browsers; a public URL with the default localhost
        origins is still wide open to anything that is not a browser, which is
        every tool anyone would actually point at it.
        """
        exposed = bool(self.public_origins) or self.hosted
        return exposed and not self.supabase_enabled

    @property
    def resolved_provider(self) -> str:
        """`auto` picks Claude when a key is present, otherwise the offline mock."""
        if self.provider != "auto":
            return self.provider
        return "anthropic" if self.anthropic_api_key else "mock"


def exposure_error(settings: "Settings") -> str:
    """Why the app refused to start, and what to do about it.

    An error that does not say how to fix it gets worked around instead of
    fixed, and the way this one would be worked around is by deleting the
    check.
    """
    if settings.public_origins:
        reason = "CORS_ORIGINS names " + ", ".join(settings.public_origins)
        way_out = "or keep CORS_ORIGINS on localhost"
    else:
        reason = "this is running on a hosting platform, so it has a public URL"
        way_out = "or run it on your own machine instead"
    return (
        "Refusing to start: "
        + reason
        + ", but no Supabase project is configured. In local mode there is no "
        "sign-in, so every endpoint -- your contacts, everything you have had "
        "it remember, and DELETE /api/data -- would be open to anyone who can "
        "reach this server. Set SUPABASE_URL and SUPABASE_ANON_KEY (see the "
        "README), " + way_out + "."
    )


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()
