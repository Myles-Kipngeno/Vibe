"""The free-tier provider path: Groq, Gemini, OpenRouter, Ollama.

Every test talks to a fake server through httpx's MockTransport, so nothing
here needs a key or spends anything.
"""

from __future__ import annotations

import json

import httpx
import pytest

from app.config import Settings
from app.providers.base import GenerationResult, ProviderError
from app.providers.openai_compatible import OpenAICompatibleProvider

GOOD = {
    "suggestions": [
        {"text": "😂 You know I had to", "rationale": "matches her laugh", "approach": "own it"},
        {"text": "Haha wacha tu", "rationale": "light Sheng", "approach": "brush off"},
        {"text": "Would you not have?", "rationale": "asks back", "approach": "asks back"},
    ]
}


def provider(handler, key="k-test"):
    client = httpx.Client(transport=httpx.MockTransport(handler))
    return OpenAICompatibleProvider("groq", "https://api.example/v1", "some-model", key, client=client)


def reply(content: str, status: int = 200):
    return lambda request: httpx.Response(status, json={"choices": [{"message": {"content": content}}]})


def test_sends_an_openai_style_request_with_the_key_and_json_mode():
    seen = {}

    def handler(request: httpx.Request):
        seen["url"] = str(request.url)
        seen["auth"] = request.headers.get("authorization")
        seen["body"] = json.loads(request.content)
        return httpx.Response(200, json={"choices": [{"message": {"content": json.dumps(GOOD)}}]})

    result = provider(handler).generate("system rules", "the chat", GenerationResult)
    assert [s.text for s in result.suggestions][0] == "😂 You know I had to"
    assert seen["url"] == "https://api.example/v1/chat/completions"
    assert seen["auth"] == "Bearer k-test"
    assert seen["body"]["model"] == "some-model"
    assert seen["body"]["response_format"] == {"type": "json_object"}
    assert seen["body"]["messages"][0]["content"].startswith("system rules")
    assert "three suggestions" in seen["body"]["messages"][0]["content"]


def test_a_code_fence_around_the_json_is_tolerated():
    result = provider(reply("```json\n" + json.dumps(GOOD) + "\n```")).generate("s", "u", GenerationResult)
    assert len(result.suggestions) == 3


def test_prose_instead_of_json_is_an_error_not_a_guess():
    with pytest.raises(ProviderError, match="valid suggestions"):
        provider(reply("Sure! Here are some replies: ...")).generate("s", "u", GenerationResult)


@pytest.mark.parametrize("status,match", [(401, "API key"), (429, "rate limit"), (500, "HTTP 500")])
def test_http_failures_say_what_happened(status, match):
    with pytest.raises(ProviderError, match=match):
        provider(reply("{}", status=status)).generate("s", "u", GenerationResult)


def test_network_failure_is_a_provider_error():
    def handler(request):
        raise httpx.ConnectError("down")

    with pytest.raises(ProviderError, match="network"):
        provider(handler).generate("s", "u", GenerationResult)


def test_no_key_sends_no_authorization_header():
    seen = {}

    def handler(request):
        seen["auth"] = request.headers.get("authorization")
        return httpx.Response(200, json={"choices": [{"message": {"content": json.dumps(GOOD)}}]})

    provider(handler, key=None).generate("s", "u", GenerationResult)
    assert seen["auth"] is None


# --- Choosing a provider without an Anthropic key ------------------------------


def test_auto_picks_a_free_tier_key_when_one_is_set(monkeypatch):
    monkeypatch.setenv("AI_PROVIDER", "auto")
    monkeypatch.setenv("GROQ_API_KEY", "gsk_test")
    monkeypatch.delenv("AI_MODEL", raising=False)
    s = Settings()
    assert s.resolved_provider == "groq"
    assert s.ai_api_key == "gsk_test"
    assert s.model == "openai/gpt-oss-120b"


def test_a_non_anthropic_key_in_the_anthropic_slot_is_not_used(monkeypatch):
    monkeypatch.setenv("AI_PROVIDER", "auto")
    for var in ("GROQ_API_KEY", "GEMINI_API_KEY", "OPENROUTER_API_KEY", "AI_API_KEY"):
        monkeypatch.delenv(var, raising=False)
    monkeypatch.setenv("ANTHROPIC_API_KEY", "sk-proj-not-anthropic")
    assert Settings().resolved_provider == "mock"


def test_model_can_be_overridden(monkeypatch):
    monkeypatch.setenv("AI_PROVIDER", "gemini")
    monkeypatch.setenv("AI_MODEL", "gemini-newer-flash")
    assert Settings().model == "gemini-newer-flash"


# --- What the keyboard sends -----------------------------------------------------


def test_keyboard_memory_and_style_notes_reach_the_prompt(client, monkeypatch):
    captured = {}
    from app.api import deps
    from app.providers.mock import MockProvider

    class Spy(MockProvider):
        def generate(self, system, user, schema, context=None):
            captured["user"] = user
            return super().generate(system, user, schema, context)

    client.app.dependency_overrides[deps.get_provider] = lambda: Spy()
    try:
        r = client.post(
            "/api/conversation/suggest",
            json={
                "messages": [{"speaker": "them", "text": "you actually went there? 😂"}],
                "memory_notes": ["Randy is my roommate", "Recurring topic: weekend"],
                "style_notes": "English + Sheng, short messages, says sawa sawa",
            },
        )
    finally:
        client.app.dependency_overrides.pop(deps.get_provider, None)
    assert r.status_code == 200
    assert "- Randy is my roommate" in captured["user"]
    assert "says sawa sawa" in captured["user"]


def test_memory_notes_are_bounded(client):
    r = client.post(
        "/api/conversation/suggest",
        json={"messages": [{"speaker": "them", "text": "hey"}], "memory_notes": ["x"] * 100},
    )
    assert r.status_code == 422


def test_his_own_messages_reach_the_prompt_as_voice_not_lines(client):
    captured = {}
    from app.api import deps
    from app.providers.mock import MockProvider

    class Spy(MockProvider):
        def generate(self, system, user, schema, context=None):
            captured["user"] = user
            return super().generate(system, user, schema, context)

    client.app.dependency_overrides[deps.get_provider] = lambda: Spy()
    try:
        r = client.post(
            "/api/conversation/suggest",
            json={
                "messages": [{"speaker": "them", "text": "weekend uko free?"}],
                "style_samples": ["aii niko tu manze", "sawa sawa, tutaonana kesho 😂"],
            },
        )
    finally:
        client.app.dependency_overrides.pop(deps.get_provider, None)
    assert r.status_code == 200
    assert "## Messages he actually wrote\n- aii niko tu manze" in captured["user"]
    assert "Do not reuse or adapt these lines" in captured["user"]


def test_style_samples_are_bounded(client):
    r = client.post(
        "/api/conversation/suggest",
        json={"messages": [{"speaker": "them", "text": "hey"}], "style_samples": ["x"] * 11},
    )
    assert r.status_code == 422


# --- The coaching guide ------------------------------------------------------------


def _prompt_for(client, goal="keep_flowing", text="haha you actually went there? 😂", action=None):
    captured = {}
    from app.api import deps
    from app.providers.mock import MockProvider

    class Spy(MockProvider):
        def generate(self, system, user, schema, context=None):
            captured["user"] = user
            return super().generate(system, user, schema, context)

    client.app.dependency_overrides[deps.get_provider] = lambda: Spy()
    try:
        body = {"messages": [{"speaker": "them", "text": text}], "goal": goal}
        if action:
            body["action"] = action
        client.post("/api/conversation/suggest", json=body)
    finally:
        client.app.dependency_overrides.pop(deps.get_provider, None)
    return captured.get("user", "")


def test_every_reply_is_coached_with_the_goal_playbook(client):
    prompt = _prompt_for(client, goal="ask_out")
    assert "## Coaching" in prompt
    assert "Tie every message to something in this chat" in prompt
    assert "at least two of the options must be an actual invitation" in prompt
    assert "never paste one as-is" in prompt


def test_each_chip_gets_its_own_playbook(client):
    assert "Funny: make her laugh" in _prompt_for(client, goal="make_her_laugh")
    assert "Flirt: every option must be unmistakably flirting" in _prompt_for(client, goal="flirt")


def test_no_coaching_past_a_boundary_or_when_ending(client):
    assert "## Coaching" not in _prompt_for(client, text="I'm not interested, please stop texting me", action="stop")
    assert "## Coaching" not in _prompt_for(client, action="stop")


def test_savage_and_spicy_are_real_goals_with_their_own_playbooks(client):
    assert client.get("/api/conversation/goals").json()["savage"] == "Clap back / roast"
    savage = _prompt_for(client, goal="savage")
    assert "Savage: roast, sass and clap back" in savage
    assert "wet twice" not in savage  # innuendo lines only for Spicy
    spicy = _prompt_for(client, goal="spicy")
    assert "Spicy: suggestive" in spicy and "wet twice" in spicy


def test_every_prompt_says_how_to_react_to_her_mood(client):
    prompt = _prompt_for(client)
    assert "Rude or mean for no reason" in prompt
    assert "\"I'm busy\" or \"not today\" is not a no" in prompt
    assert "Send me a picture" in prompt


def test_what_is_still_excluded_stays_out():
    from app.core.coaching import GUIDE, PLAYBOOK, REFERENCE_LINES, SPICY_LINES, WHEN_SHE

    text = (GUIDE + WHEN_SHE + " ".join(PLAYBOOK.values()) + REFERENCE_LINES + SPICY_LINES).lower()
    for banned in ("love bomb", "make her jealous", "slave", "give you the d"):
        assert banned not in text
    assert "never push" in text and "never explicit" in text


def test_sheng_goes_inside_the_sentence_and_flirt_must_carry_tension(client):
    prompt = _prompt_for(client, goal="flirt")
    assert 'never bolted on as an opener' in prompt
    assert '"Mambo,", "Sawa,", "Cheki,"' in prompt
    assert "every option must be unmistakably flirting" in prompt
    assert "Give three different kinds of flirt" in prompt
    assert "I'm charging in hugs" in prompt


def test_a_busy_server_gets_one_retry():
    calls = []

    def handler(request):
        calls.append(1)
        if len(calls) == 1:
            return httpx.Response(503, json={"error": "overloaded"})
        return httpx.Response(200, json={"choices": [{"message": {"content": json.dumps(GOOD)}}]})

    client = httpx.Client(transport=httpx.MockTransport(handler))
    p = OpenAICompatibleProvider("gemini", "https://g.example/v1", "m", "k", client=client, retry_delay=0)
    assert len(p.generate("s", "u", GenerationResult).suggestions) == 3
    assert len(calls) == 2


def test_still_busy_after_the_retry_is_an_error():
    client = httpx.Client(transport=httpx.MockTransport(lambda r: httpx.Response(503, json={})))
    p = OpenAICompatibleProvider("gemini", "https://g.example/v1", "m", "k", client=client, retry_delay=0)
    with pytest.raises(ProviderError, match="HTTP 503"):
        p.generate("s", "u", GenerationResult)


def test_gemini_is_asked_to_think_briefly_and_uses_a_current_model():
    from app.providers.openai_compatible import PRESETS

    seen = {}

    def handler(request):
        seen.update(json.loads(request.content))
        return httpx.Response(200, json={"choices": [{"message": {"content": json.dumps(GOOD)}}]})

    client = httpx.Client(transport=httpx.MockTransport(handler))
    OpenAICompatibleProvider("gemini", "https://g.example/v1", "m", "k", client=client).generate("s", "u", GenerationResult)
    assert seen["reasoning_effort"] == "low"
    assert PRESETS["gemini"][1] != "gemini-2.5-flash"


def test_language_is_kept_simple_and_varied(client):
    prompt = _prompt_for(client, goal="flirt")
    assert "mostly English, with just a little Sheng" in prompt
    assert "At least one option in plain" in prompt
    from app.core.prompts import SYSTEM_PROMPT

    assert "mostly in English with a light touch of Sheng" in " ".join(SYSTEM_PROMPT.split())
