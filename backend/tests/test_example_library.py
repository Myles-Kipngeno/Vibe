"""The library is a reference, not a script.

Handed a stack of lines that worked, a model will send one -- which is the
pickup-line generator this product is defined against. So most of what matters
here is refusal: when examples are withheld, and what the prompt says about the
ones it does show.

All examples and messages here are fictional.
"""

from __future__ import annotations

import pytest

from app.core.example_library import (
    MAX_EXAMPLES,
    examples_brief,
    mix_band,
    relevant,
)
from app.schemas import Analysis, ConversationExample


def example(**fields) -> ConversationExample:
    base = dict(
        id="ex_1",
        situation="Opening a new chat with someone from class",
        context="",
        opening_line="Niaje, that lecture was rough",
        language_mix="mixed",
        tone="",
        reaction="Replied in a minute and asked a question back",
        what_worked="It named something we both sat through",
        what_did_not="",
        not_suitable_when="",
        created_at="2026-01-01T12:00:00",
    )
    base.update(fields)
    return ConversationExample(**base)


def analysis(**fields) -> Analysis:
    base = dict(
        topic="class",
        tone="friendly",
        tone_confidence="medium",
        flow_state="flowing",
        engagement="engaged",
        engagement_confidence="medium",
        summary="",
        recommendation="continue",
        recommendation_reason="",
    )
    base.update(fields)
    return Analysis(**base)


# --- When the library must stay shut -------------------------------------------


def test_a_boundary_withholds_every_example():
    """Nothing that once worked applies once she has said no.

    This is the same refusal the generator gates enforce; an example library
    would be a way to route around it.
    """
    assert relevant([example()], "start", analysis(boundary_detected=True), 0.5) == []


def test_an_example_is_dropped_when_he_marked_it_wrong_for_this_state():
    shut = example(not_suitable_when="when she is disengaged or giving one word replies")
    assert relevant([shut], "start", analysis(engagement="disengaged"), 0.5) == []
    # The same example is fine when she is engaged.
    assert relevant([shut], "start", analysis(engagement="engaged"), 0.5) == [shut]


def test_not_suitable_when_is_honoured_for_a_serious_mood():
    shut = example(not_suitable_when="not when the conversation is serious")
    assert relevant([shut], "start", analysis(tone="serious"), 0.5) == []


def test_an_unrelated_example_is_not_offered_just_for_existing():
    """A library hit is not a match. Most conversations should get nothing."""
    unrelated = example(
        situation="Comforting a friend after a funeral",
        what_did_not="",
        language_mix="english",
    )
    assert relevant([unrelated], "make_her_laugh", analysis(), 0.9) == []


def test_incidental_signals_cannot_substitute_for_relevance():
    """The flaw a live run caught, which the test above was too weak to see.

    An example about opening a conversation was offered for "make her laugh".
    It matched nothing situational: it scored on being in the same language
    band and on recording a failure, two signals that say nothing about
    whether it belongs here. Matching the situation is now a precondition.
    """
    wrong_situation = example(
        situation="Starting a new chat, opening line",
        language_mix="mixed",              # same band as him
        what_did_not="Two questions at once got one word back",  # records a failure
    )
    assert relevant([wrong_situation], "make_her_laugh", analysis(), 0.5) == []
    assert relevant([wrong_situation], "comfort", analysis(), 0.5) == []
    # It is still offered for the situation it is actually about.
    assert relevant([wrong_situation], "start", analysis(), 0.5) == [wrong_situation]


def test_an_empty_library_is_not_an_error():
    assert relevant([], "start", analysis(), 0.5) == []
    assert examples_brief([]) == ""


# --- What gets through, and how much -------------------------------------------


def test_a_matching_situation_is_offered():
    opener = example(situation="Starting a new chat, opening line")
    assert relevant([opener], "start", analysis(), 0.5) == [opener]


def test_no_more_than_two_are_ever_shown():
    """More than a couple reads as a menu to choose from."""
    many = [
        example(id=f"ex_{i}", situation="Starting a new chat, opening line")
        for i in range(8)
    ]
    assert len(relevant(many, "start", analysis(), 0.5)) == MAX_EXAMPLES


def test_an_example_that_records_a_failure_outranks_one_that_only_won():
    only_won = example(id="won", situation="Starting a new chat, opening line")
    also_failed = example(
        id="failed",
        situation="Starting a new chat, opening line",
        what_did_not="Sending two questions at once got a one word reply",
    )
    chosen = relevant([only_won, also_failed], "start", analysis(), 0.5)
    assert chosen[0].id == "failed"


def test_language_band_follows_his_measured_ratio():
    assert mix_band(0.9) == "heavy_sheng"
    assert mix_band(0.5) == "mixed"
    assert mix_band(0.2) == "light_sheng"
    assert mix_band(0.0) == "english"


# --- What the model is told about them -----------------------------------------


def test_the_brief_carries_the_conditions_not_just_the_win():
    brief = examples_brief([
        example(
            what_did_not="Sending two questions at once got a one word reply",
            not_suitable_when="when she is already quiet",
        )
    ])
    assert "fell flat" in brief
    assert "wrong for" in brief


@pytest.mark.parametrize("goal", ["start", "keep_flowing", "flirt", "comfort"])
def test_selection_never_raises_on_any_goal(goal):
    relevant([example()], goal, analysis(), 0.5)


# --- The routes and the wiring -------------------------------------------------


def test_examples_round_trip_through_the_api(client):
    created = client.post(
        "/api/examples",
        json={
            "situation": "Starting a new chat, opening line",
            "opening_line": "Niaje, that lecture was rough",
            "reaction": "Replied quickly and asked something back",
            "what_worked": "It named something we both sat through",
            "language_mix": "mixed",
        },
    )
    assert created.status_code == 201
    example_id = created.json()["id"]

    listed = client.get("/api/examples").json()
    assert [e["id"] for e in listed] == [example_id]

    assert client.delete(f"/api/examples/{example_id}").status_code == 204
    assert client.get("/api/examples").json() == []
    assert client.delete(f"/api/examples/{example_id}").status_code == 404


def test_an_example_without_a_situation_is_refused(client):
    """Nothing can be matched to a situation that was never described."""
    body = client.post("/api/examples", json={"situation": "   "})
    assert body.status_code == 422


def test_a_relevant_example_reaches_the_prompt_with_its_warning(client, monkeypatch):
    from app.providers.mock import MockProvider

    captured: list[str] = []
    original = MockProvider.generate

    def spy(self, system, user, schema, context=None):
        captured.append(user)
        return original(self, system, user, schema, context)

    monkeypatch.setattr(MockProvider, "generate", spy)

    client.post(
        "/api/examples",
        json={
            "situation": "Starting a new chat, opening line",
            "opening_line": "Niaje, that lecture was rough",
            "what_did_not": "Two questions at once got a one word reply",
        },
    )

    client.post(
        "/api/conversation/suggest",
        json={
            "messages": [
                {"speaker": "them", "text": "niaje, uko aje leo?"},
                {"speaker": "me", "text": "poa sana, wewe je?"},
            ],
            "goal": "start",
        },
    )

    prompt = captured[-1]
    assert "Conversations of his that went well" in prompt
    assert "that lecture was rough" in prompt
    # The instruction that keeps it a reference rather than a script.
    assert "Do not reuse a line" in prompt


def test_no_examples_means_no_section(client, monkeypatch):
    from app.providers.mock import MockProvider

    captured: list[str] = []
    original = MockProvider.generate

    def spy(self, system, user, schema, context=None):
        captured.append(user)
        return original(self, system, user, schema, context)

    monkeypatch.setattr(MockProvider, "generate", spy)

    client.post(
        "/api/conversation/suggest",
        json={
            "messages": [
                {"speaker": "them", "text": "niaje, uko aje leo?"},
                {"speaker": "me", "text": "poa sana, wewe je?"},
            ],
            "goal": "start",
        },
    )
    assert "Conversations of his that went well" not in captured[-1]


def test_wiping_everything_takes_the_library_with_it(client):
    client.post("/api/examples", json={"situation": "Starting a new chat"})
    assert client.get("/api/examples").json()

    wiped = client.delete("/api/data")
    assert wiped.status_code in (200, 204)
    assert client.get("/api/examples").json() == []
