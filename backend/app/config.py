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

    @property
    def resolved_provider(self) -> str:
        """`auto` picks Claude when a key is present, otherwise the offline mock."""
        if self.provider != "auto":
            return self.provider
        return "anthropic" if self.anthropic_api_key else "mock"


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()
