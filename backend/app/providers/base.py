"""Provider abstraction for reply generation.

Application code never imports a vendor SDK directly. It asks the registry for
whatever provider is configured and calls `generate`. Swapping models -- or
adding a local/open-weights provider later -- means adding one file here and
changing one environment variable, with no changes to the intelligence layer.
"""

from __future__ import annotations

import abc
from typing import TypeVar

from pydantic import BaseModel

T = TypeVar("T", bound=BaseModel)


class ProviderError(RuntimeError):
    """Raised when a provider cannot fulfil a request."""


class LLMProvider(abc.ABC):
    """Minimal contract: structured generation from a system + user prompt."""

    name: str = "base"
    model: str | None = None
    is_mock: bool = False

    @abc.abstractmethod
    def generate(
        self, system: str, user: str, schema: type[T], context: dict | None = None
    ) -> T:
        """Return an instance of `schema` produced from the prompts.

        `context` carries the same information as the prompts in machine-readable
        form. Real model providers ignore it; the offline mock provider uses it
        instead of trying to parse English back out of the prompt.
        """

    def describe(self) -> dict[str, object]:
        return {"provider": self.name, "model": self.model, "is_mock": self.is_mock}


class GeneratedSuggestion(BaseModel):
    """One reply option as produced by a provider."""

    text: str
    rationale: str
    approach: str


class GenerationResult(BaseModel):
    """The structured payload every provider must return."""

    suggestions: list[GeneratedSuggestion]
