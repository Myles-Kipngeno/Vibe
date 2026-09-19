"""Claude-backed provider.

Uses the official Anthropic Python SDK with structured outputs, so the response
is validated against our Pydantic schema instead of being scraped out of prose.
"""

from __future__ import annotations

from typing import TypeVar

from pydantic import BaseModel

from .base import LLMProvider, ProviderError

T = TypeVar("T", bound=BaseModel)


class AnthropicProvider(LLMProvider):
    name = "anthropic"
    is_mock = False

    def __init__(self, api_key: str | None, model: str, max_tokens: int = 4000) -> None:
        try:
            import anthropic  # imported lazily so the app runs without the SDK
        except ImportError as exc:  # pragma: no cover - depends on install
            raise ProviderError(
                "The 'anthropic' package is not installed. Run "
                "`pip install -r requirements.txt`, or set AI_PROVIDER=mock."
            ) from exc

        self._sdk = anthropic
        self.model = model
        self.max_tokens = max_tokens
        # An explicit key wins; otherwise the SDK resolves ANTHROPIC_API_KEY or a
        # logged-in CLI profile on its own.
        self._client = anthropic.Anthropic(api_key=api_key) if api_key else anthropic.Anthropic()

    def generate(
        self, system: str, user: str, schema: type[T], context: dict | None = None
    ) -> T:
        try:
            response = self._client.messages.parse(
                model=self.model,
                max_tokens=self.max_tokens,
                system=system,
                messages=[{"role": "user", "content": user}],
                output_format=schema,
            )
        except self._sdk.APIStatusError as exc:
            raise ProviderError(
                f"Claude returned {exc.status_code}. Check ANTHROPIC_API_KEY and your "
                "account's credit balance."
            ) from exc
        except self._sdk.APIConnectionError as exc:
            raise ProviderError("Could not reach the Claude API (network error).") from exc

        if getattr(response, "stop_reason", None) == "refusal":
            raise ProviderError(
                "The model declined to answer this request. Try a different goal or "
                "rephrase the conversation context."
            )

        parsed = response.parsed_output
        if parsed is None:  # pragma: no cover - guarded by structured outputs
            raise ProviderError("The model returned no structured output.")
        return parsed
