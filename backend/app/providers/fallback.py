"""Try the next provider when one is busy or down.

Free tiers run out: Groq answers 429 once its per-minute allowance is spent.
Rather than the phone falling back to templates, the backend asks the next
configured provider (Gemini, then OpenRouter) and returns its answer. Only
when every provider fails does the request fail, with each reason listed.
"""

from __future__ import annotations

import logging
from typing import TypeVar

from pydantic import BaseModel

from .base import LLMProvider, ProviderError

T = TypeVar("T", bound=BaseModel)

log = logging.getLogger("vibe.providers")


class FallbackProvider(LLMProvider):
    is_mock = False

    def __init__(self, providers: list[LLMProvider]) -> None:
        if not providers:
            raise ValueError("FallbackProvider needs at least one provider")
        self.providers = providers
        # Reported as the primary: that is what normally answers.
        self.name = providers[0].name
        self.model = providers[0].model
        self.last_used: LLMProvider = providers[0]

    def generate(self, system: str, user: str, schema: type[T], context: dict | None = None) -> T:
        failures: list[str] = []
        for provider in self.providers:
            try:
                result = provider.generate(system, user, schema, context)
                self.last_used = provider
                if failures:
                    log.info("answered by %s after: %s", provider.name, "; ".join(failures))
                return result
            except ProviderError as exc:
                failures.append(f"{provider.name}: {exc}")
        raise ProviderError("Every model provider failed. " + " | ".join(failures))

    def describe(self) -> dict[str, object]:
        return {
            "provider": self.name,
            "model": self.model,
            "is_mock": False,
            "fallbacks": [p.name for p in self.providers[1:]],
        }
