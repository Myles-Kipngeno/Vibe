"""Any model served over the OpenAI-compatible chat API.

Groq, Google's Gemini API, OpenRouter and a local Ollama all speak the same
`/chat/completions` protocol, so one class covers every provider that can be
used without an Anthropic key. The model is asked for JSON matching our schema
and the reply is validated with Pydantic, exactly as the Claude provider does
-- nothing is scraped out of prose.
"""

from __future__ import annotations

import json
import re
from typing import TypeVar

import httpx
from pydantic import BaseModel, ValidationError

from .base import LLMProvider, ProviderError

T = TypeVar("T", bound=BaseModel)

# Where each named provider lives, and a sensible default model. Every value can
# be overridden with AI_BASE_URL / AI_MODEL, because model names change faster
# than this file does.
PRESETS: dict[str, tuple[str, str]] = {
    "groq": ("https://api.groq.com/openai/v1", "openai/gpt-oss-120b"),
    "gemini": ("https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash"),
    "openrouter": ("https://openrouter.ai/api/v1", "meta-llama/llama-3.3-70b-instruct:free"),
    "ollama": ("http://localhost:11434/v1", "llama3.2"),
}

_JSON_INSTRUCTION = """

## Output format
Reply with one JSON object and nothing else -- no markdown, no commentary:
{"suggestions": [{"text": "...", "rationale": "...", "approach": "..."}]}
Give three suggestions that differ in approach, not just wording.
`text` is the message exactly as he would send it. `rationale` is one short
sentence on why it fits. `approach` is two or three words ("light tease",
"asks back", "warm close")."""


class OpenAICompatibleProvider(LLMProvider):
    is_mock = False

    def __init__(
        self,
        name: str,
        base_url: str,
        model: str,
        api_key: str | None,
        max_tokens: int = 1200,
        timeout: float = 30.0,
        client: httpx.Client | None = None,
    ) -> None:
        self.name = name
        self.model = model
        self._url = base_url.rstrip("/") + "/chat/completions"
        self._key = api_key
        self._max_tokens = max_tokens
        self._client = client or httpx.Client(timeout=timeout)

    def generate(
        self, system: str, user: str, schema: type[T], context: dict | None = None
    ) -> T:
        headers = {"Content-Type": "application/json"}
        if self._key:
            headers["Authorization"] = f"Bearer {self._key}"
        body = {
            "model": self.model,
            "messages": [
                {"role": "system", "content": system + _JSON_INSTRUCTION},
                {"role": "user", "content": user},
            ],
            "temperature": 0.9,
            "max_tokens": self._max_tokens,
            "response_format": {"type": "json_object"},
        }
        try:
            response = self._client.post(self._url, headers=headers, json=body)
        except httpx.HTTPError as exc:
            raise ProviderError(f"Could not reach {self.name} (network error).") from exc

        if response.status_code in (401, 403):
            raise ProviderError(f"{self.name} rejected the API key (HTTP {response.status_code}). Check AI_API_KEY.")
        if response.status_code == 429:
            raise ProviderError(f"{self.name} rate limit reached. Wait a minute and try again.")
        if response.status_code >= 400:
            raise ProviderError(f"{self.name} returned HTTP {response.status_code}.")

        try:
            content = response.json()["choices"][0]["message"]["content"] or ""
        except (ValueError, KeyError, IndexError, TypeError) as exc:
            raise ProviderError(f"{self.name} sent a response in an unexpected shape.") from exc
        return _parse(content, schema, self.name)


def _parse(content: str, schema: type[T], name: str) -> T:
    """The JSON object, even when a model wraps it in a code fence anyway."""
    candidates = [content.strip()]
    match = re.search(r"\{.*\}", content, flags=re.DOTALL)
    if match:
        candidates.append(match.group(0))
    for candidate in candidates:
        try:
            return schema.model_validate_json(candidate)
        except (ValidationError, json.JSONDecodeError, ValueError):
            continue
    raise ProviderError(f"{name} did not return valid suggestions. Try again.")
