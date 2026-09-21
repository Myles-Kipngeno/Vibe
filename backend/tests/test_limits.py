"""Ceilings on what one person can cost.

Two different problems with one shape. Every request field here is concatenated
into a prompt and billed per token, so an unbounded field is an unbounded bill;
and /suggest is the only endpoint that calls a paid model, on a key belonging
to whoever deployed this rather than to whoever is asking.

Neither limit should ever be met by someone using the app. These tests check
both halves of that: that abuse is refused, and that ordinary use is not.
"""

from __future__ import annotations

import pytest
from pydantic import ValidationError

from app import schemas
from app.api.rate_limit import SlidingWindow, reset_limits
from app.schemas import AnalyzeRequest, Message, ParseRequest, SuggestRequest


def convo(n: int = 4, chars: int = 20) -> list[Message]:
    return [Message(speaker="them", text="x" * chars) for _ in range(n)]


# --- what a request may carry ---------------------------------------------------


def test_a_four_megabyte_request_is_refused():
    """2000 messages of 2000 characters was accepted before any of this."""
    with pytest.raises(ValidationError):
        SuggestRequest(messages=convo(2000, 2000))


def test_one_enormous_message_is_refused():
    with pytest.raises(ValidationError):
        SuggestRequest(messages=convo(1, schemas.MAX_MESSAGE_CHARS + 1))


def test_an_enormous_paste_is_refused():
    with pytest.raises(ValidationError):
        ParseRequest(raw_text="x" * (schemas.MAX_PASTE_CHARS + 1))


def test_the_caps_are_actually_small_numbers():
    """Stated as literals, because the tests above cannot notice them rising.

    Every other case here is written as MAX_SOMETHING + 1, so raising a cap
    raises the threshold it is tested against and the test keeps passing. These
    are the sizes that must be refused whatever the constants say.
    """
    with pytest.raises(ValidationError):
        SuggestRequest(messages=[Message(speaker="them", text="x" * 1_000_000)])
    with pytest.raises(ValidationError):
        ParseRequest(raw_text="x" * 10_000_000)
    with pytest.raises(ValidationError):
        SuggestRequest(messages=convo(100_000, 10))

    assert schemas.MAX_MESSAGE_CHARS <= 20_000
    assert schemas.MAX_PASTE_CHARS <= 1_000_000
    assert schemas.MAX_MESSAGES <= 5_000


def test_the_other_prompt_fields_are_bounded_too():
    """avoid, supplied_context and the labels all reach the prompt as well."""
    with pytest.raises(ValidationError):
        SuggestRequest(messages=convo(), avoid=["x"] * (schemas.MAX_AVOID + 1))
    with pytest.raises(ValidationError):
        SuggestRequest(
            messages=convo(),
            supplied_context={"k": "x" * (schemas.MAX_CONTEXT_CHARS + 1)},
        )
    with pytest.raises(ValidationError):
        ParseRequest(raw_text="hi", me_label="x" * (schemas.MAX_LABEL_CHARS + 1))


def test_analyze_is_bounded_as_well_as_suggest():
    """It does not call a model, but it does parse everything it is given."""
    with pytest.raises(ValidationError):
        AnalyzeRequest(messages=convo(schemas.MAX_MESSAGES + 1, 10))


def test_a_real_conversation_is_not_refused():
    """The limits are worthless if they catch the people using the app."""
    long_but_real = [
        Message(speaker="me" if i % 2 else "them", text="niaje, uko aje leo? " * 10)
        for i in range(60)
    ]
    SuggestRequest(messages=long_but_real, avoid=["one", "two"])
    ParseRequest(raw_text="Her: hey\nMe: hi\n" * 2000)


# --- how often one person may spend money ---------------------------------------


def test_the_window_lets_the_limit_through_then_stops():
    window = SlidingWindow()
    assert all(window.check("k", 3, 60.0) is None for _ in range(3))
    wait = window.check("k", 3, 60.0)
    assert wait is not None and wait > 0


def test_the_wait_it_reports_is_when_room_frees_up():
    """Retry-After is built from this, so it has to be a real number."""
    window = SlidingWindow()
    window.check("k", 1, 60.0)
    wait = window.check("k", 1, 60.0)
    assert wait is not None
    assert 55 < wait <= 60


def test_one_person_hitting_the_limit_does_not_block_another():
    window = SlidingWindow()
    for _ in range(3):
        window.check("alice", 3, 60.0)
    assert window.check("alice", 3, 60.0) is not None
    assert window.check("bob", 3, 60.0) is None


def test_two_signed_in_people_get_their_own_budgets():
    """Otherwise one person's use would lock everybody else out.

    The API tests run in local mode, where there is no token and every caller
    shares an address, so nothing there exercises this.
    """
    import base64
    import json as _json

    from app.api.rate_limit import _identity

    def token_for(sub: str) -> str:
        def seg(d):
            return base64.urlsafe_b64encode(_json.dumps(d).encode()).decode().rstrip("=")

        return f"{seg({'alg': 'ES256'})}.{seg({'sub': sub})}.sig"

    class FakeRequest:
        client = type("C", (), {"host": "10.0.0.1"})()

    alice = _identity(FakeRequest(), f"Bearer {token_for('alice-uuid')}")
    bob = _identity(FakeRequest(), f"Bearer {token_for('bob-uuid')}")
    anon = _identity(FakeRequest(), None)

    assert alice != bob, "one account must not spend another's allowance"
    assert alice.startswith("user:") and "alice-uuid" in alice
    assert anon.startswith("ip:"), "with no token, the address is all there is"


def test_old_hits_fall_out_of_the_window():
    window = SlidingWindow()
    assert window.check("k", 1, 0.0) is None
    assert window.check("k", 1, 0.0) is None, "a zero window forgets immediately"


# --- through the API ------------------------------------------------------------


def test_suggest_starts_refusing_once_the_limit_is_reached(client, monkeypatch):
    monkeypatch.setenv("SUGGEST_PER_MINUTE", "3")
    monkeypatch.setenv("SUGGEST_PER_HOUR", "0")
    from app.api import deps

    deps.reset_caches()
    reset_limits()

    body = {
        "messages": [
            {"speaker": "them", "text": "niaje, uko aje leo?"},
            {"speaker": "me", "text": "poa sana, wewe je?"},
        ],
        "goal": "keep_flowing",
    }
    codes = [client.post("/api/conversation/suggest", json=body).status_code for _ in range(5)]
    assert codes[:3] == [200, 200, 200], codes
    assert codes[3:] == [429, 429], codes
    reset_limits()


def test_the_refusal_says_when_to_come_back(client, monkeypatch):
    monkeypatch.setenv("SUGGEST_PER_MINUTE", "1")
    monkeypatch.setenv("SUGGEST_PER_HOUR", "0")
    from app.api import deps

    deps.reset_caches()
    reset_limits()

    body = {"messages": [{"speaker": "them", "text": "hi"}], "goal": "keep_flowing"}
    client.post("/api/conversation/suggest", json=body)
    blocked = client.post("/api/conversation/suggest", json=body)

    assert blocked.status_code == 429
    assert "Retry-After" in blocked.headers
    assert int(blocked.headers["Retry-After"]) >= 1
    assert "try again in" in blocked.json()["detail"].lower()
    reset_limits()


def test_the_free_endpoints_are_not_limited(client, monkeypatch):
    """Only generation costs money. Rationing analysis would just annoy."""
    monkeypatch.setenv("SUGGEST_PER_MINUTE", "1")
    from app.api import deps

    deps.reset_caches()
    reset_limits()

    body = {"messages": [{"speaker": "them", "text": "niaje"}]}
    codes = [client.post("/api/conversation/analyze", json=body).status_code for _ in range(5)]
    assert codes == [200] * 5, codes
    reset_limits()
