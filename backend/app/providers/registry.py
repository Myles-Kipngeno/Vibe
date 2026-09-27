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

    from .openai_compatible import PRESETS, OpenAICompatibleProvider

    if choice in PRESETS or choice == "openai_compatible":
        preset_url, _ = PRESETS.get(choice, ("", ""))
        base_url = settings.ai_base_url or preset_url
        if not base_url:
            raise ProviderError("AI_PROVIDER=openai_compatible needs AI_BASE_URL.")
        if not settings.ai_api_key and choice != "ollama":
            raise ProviderError(f"AI_PROVIDER={choice} needs an API key in AI_API_KEY.")
        return OpenAICompatibleProvider(
            name=choice,
            base_url=base_url,
            model=settings.model,
            api_key=settings.ai_api_key,
            max_tokens=min(settings.max_tokens, 1500),
        )

    raise ProviderError(
        f"Unknown AI_PROVIDER '{choice}'. Supported values: auto, groq, gemini, "
        "openrouter, ollama, openai_compatible, anthropic, mock."
    )


def reset_provider_cache() -> None:
    """Used by tests that swap configuration between cases."""
    get_provider.cache_clear()
