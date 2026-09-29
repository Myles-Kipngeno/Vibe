"""When Groq is busy, the next provider answers instead of the phone falling back to templates."""

from __future__ import annotations

import pytest

from app.config import Settings, get_settings
from app.providers.base import GeneratedSuggestion, GenerationResult, LLMProvider, ProviderError
from app.providers.fallback import FallbackProvider
from app.providers.registry import get_provider, reset_provider_cache


class Fake(LLMProvider):
    def __init__(self, name, fail=None):
        self.name = name
        self.model = f"{name}-model"
        self.fail = fail
        self.calls = 0

    def generate(self, system, user, schema, context=None):
        self.calls += 1
        if self.fail:
            raise ProviderError(self.fail)
        return GenerationResult(suggestions=[GeneratedSuggestion(text=f"from {self.name}", rationale="r", approach="a")])


def test_the_main_provider_answers_when_it_can():
    groq, gemini = Fake("groq"), Fake("gemini")
    result = FallbackProvider([groq, gemini]).generate("s", "u", GenerationResult)
    assert result.suggestions[0].text == "from groq"
    assert gemini.calls == 0


def test_a_busy_main_provider_hands_over_to_the_next():
    groq, gemini = Fake("groq", fail="groq rate limit reached."), Fake("gemini")
    chain = FallbackProvider([groq, gemini])
    result = chain.generate("s", "u", GenerationResult)
    assert result.suggestions[0].text == "from gemini"
    assert chain.last_used is gemini


def test_when_every_provider_fails_the_error_names_each():
    chain = FallbackProvider([Fake("groq", fail="rate limit"), Fake("gemini", fail="HTTP 500")])
    with pytest.raises(ProviderError, match=r"groq: rate limit \| gemini: HTTP 500"):
        chain.generate("s", "u", GenerationResult)


# --- Configuration -------------------------------------------------------------------


@pytest.fixture()
def keys(monkeypatch):
    for var in ("AI_API_KEY", "GROQ_API_KEY", "GEMINI_API_KEY", "OPENROUTER_API_KEY", "AI_MODEL", "GEMINI_MODEL"):
        monkeypatch.delenv(var, raising=False)
    monkeypatch.setenv("AI_PROVIDER", "auto")
    _fresh()
    yield monkeypatch
    _fresh()


def _fresh():
    get_settings.cache_clear()
    reset_provider_cache()


def test_one_key_means_no_fallback(keys):
    keys.setenv("GROQ_API_KEY", "gsk_x")
    assert Settings().fallback_providers == []
    _fresh()
    assert not isinstance(get_provider(), FallbackProvider)


def test_groq_and_gemini_keys_give_groq_then_gemini(keys):
    keys.setenv("GROQ_API_KEY", "gsk_x")
    keys.setenv("GEMINI_API_KEY", "gem_y")
    _fresh()
    provider = get_provider()
    assert isinstance(provider, FallbackProvider)
    assert [p.name for p in provider.providers] == ["groq", "gemini"]
    assert provider.describe()["fallbacks"] == ["gemini"]


def test_each_provider_only_ever_gets_its_own_key(keys):
    keys.setenv("GROQ_API_KEY", "gsk_x")
    keys.setenv("GEMINI_API_KEY", "gem_y")
    s = Settings()
    assert s.key_for("groq") == "gsk_x"
    assert s.key_for("gemini") == "gem_y"
    assert s.key_for("openrouter") is None
    _fresh()
    groq, gemini = get_provider().providers
    assert groq._key == "gsk_x" and gemini._key == "gem_y"


def test_choosing_gemini_first_still_uses_the_gemini_key(keys):
    keys.setenv("AI_PROVIDER", "gemini")
    keys.setenv("GROQ_API_KEY", "gsk_x")
    keys.setenv("GEMINI_API_KEY", "gem_y")
    _fresh()
    provider = get_provider()
    assert [p.name for p in provider.providers] == ["gemini", "groq"]
    assert provider.providers[0]._key == "gem_y"


def test_the_backup_can_have_its_own_model(keys):
    keys.setenv("GROQ_API_KEY", "gsk_x")
    keys.setenv("GEMINI_API_KEY", "gem_y")
    keys.setenv("GEMINI_MODEL", "gemini-newer")
    _fresh()
    assert get_provider().providers[1].model == "gemini-newer"


def test_health_says_what_stands_by(client, keys):
    keys.setenv("GROQ_API_KEY", "gsk_x")
    keys.setenv("GEMINI_API_KEY", "gem_y")
    _fresh()
    notes = client.get("/api/health").json()["notes"]
    assert "If groq is busy or down, replies come from gemini." in notes
