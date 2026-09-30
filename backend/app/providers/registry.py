"""Chooses and caches the configured provider."""

from __future__ import annotations

from functools import lru_cache

from ..config import Settings, get_settings
from .base import LLMProvider, ProviderError
from .mock import MockProvider


@lru_cache(maxsize=1)
def get_provider() -> LLMProvider:
    settings = get_settings()
    choice = settings.resolved_provider

    if choice == "mock":
        return MockProvider()

    primary = _build(choice, settings)
    # Every other free-tier provider with a key stands by, in order, for when
    # the main one is rate-limited or down. One key set means no fallback.
    backups = [_build(name, settings) for name in settings.fallback_providers]
    if not backups:
        return primary
    from .fallback import FallbackProvider

    return FallbackProvider([primary, *backups])


def _build(choice: str, settings: Settings) -> LLMProvider:
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
        base_url = (settings.ai_base_url if choice == settings.resolved_provider else None) or preset_url
        if not base_url:
            raise ProviderError("AI_PROVIDER=openai_compatible needs AI_BASE_URL.")
        key = settings.key_for(choice)
        if not key and choice != "ollama":
            raise ProviderError(
                f"AI_PROVIDER={choice} needs an API key in {choice.upper()}_API_KEY (or AI_API_KEY)."
            )
        return OpenAICompatibleProvider(
            name=choice,
            base_url=base_url,
            model=settings.model_for(choice),
            api_key=key,
            max_tokens=min(settings.max_tokens, 3000),  # ~5 scored candidates plus the reading
        )

    raise ProviderError(
        f"Unknown AI_PROVIDER '{choice}'. Supported values: auto, groq, gemini, "
        "openrouter, ollama, openai_compatible, anthropic, mock."
    )


def reset_provider_cache() -> None:
    """Used by tests that swap configuration between cases."""
    get_provider.cache_clear()
