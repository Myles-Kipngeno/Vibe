"""Chooses and caches the configured provider."""

from __future__ import annotations

from functools import lru_cache

from ..config import get_settings
from .base import LLMProvider, ProviderError
from .mock import MockProvider


@lru_cache(maxsize=1)
def get_provider() -> LLMProvider:
    settings = get_settings()
    choice = settings.resolved_provider

    if choice == "mock":
        return MockProvider()

    if choice == "anthropic":
        from .anthropic_provider import AnthropicProvider

        return AnthropicProvider(
            api_key=settings.anthropic_api_key,
            model=settings.model,
            max_tokens=settings.max_tokens,
        )

    raise ProviderError(
        f"Unknown AI_PROVIDER '{choice}'. Supported values: auto, anthropic, mock."
    )


def reset_provider_cache() -> None:
    """Used by tests that swap configuration between cases."""
    get_provider.cache_clear()
