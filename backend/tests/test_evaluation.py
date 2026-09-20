"""The checks the eval set is built from.

A check that cannot fail is worse than no check: it reports green forever. So
each one here is shown catching the thing it exists to catch, and clearing the
case it must not flag.

All names and messages are fictional.
"""

from __future__ import annotations

import json
from pathlib import Path

from app.core import evaluation
from app.schemas import Suggestion
from tests.conftest import msgs

CASES = Path(__file__).resolve().parents[2] / "evals" / "cases.json"


def sug(text: str) -> Suggestion:
    return Suggestion(id="s1", text=text, rationale="", tone="friendly", approach="")


# --- the promise the product is built on ---------------------------------------


def test_it_catches_a_person_the_model_invented():
    convo = msgs(("them", "poa, nilienda town"), ("me", "nice"))
    result = evaluation.check_no_invented_people([sug("Say hi to Brenda for me")], convo)
    assert not result.passed
    assert "Brenda" in result.detail


def test_a_person_she_actually_mentioned_is_not_an_invention():
    convo = msgs(("them", "poa, nilienda town na Brenda"), ("me", "nice"))
    assert evaluation.check_no_invented_people([sug("How is Brenda?")], convo).passed


def test_context_he_supplied_counts_as_known():
    convo = msgs(("them", "remember what happened there?"))
    result = evaluation.check_no_invented_people(
        [sug("Tell Brenda I said hi")],
        convo,
        supplied_context={"person:brenda": "Brenda is her cousin"},
    )
    assert result.passed


def test_the_contacts_own_name_is_not_an_invention():
    convo = msgs(("them", "hey"))
    assert evaluation.check_no_invented_people(
        [sug("Sawa Mercy, tuonane kesho")], convo, contact_name="Mercy"
    ).passed


# --- the Sheng budget ----------------------------------------------------------


def test_it_catches_a_reply_far_more_sheng_than_he_writes():
    result = evaluation.check_sheng_budget(
        [sug("Manze niaje msee, uko aje leo? tutaonana kesho bana")], his_ratio=0.0
    )
    assert not result.passed


def test_a_reply_in_his_own_register_passes():
    assert evaluation.check_sheng_budget(
        [sug("That sounds rough, hope today is better")], his_ratio=0.0
    ).passed


def test_nothing_generated_is_not_a_budget_failure():
    assert evaluation.check_sheng_budget([], his_ratio=0.5).passed


# --- the smaller promises ------------------------------------------------------


def test_it_catches_stacked_emojis():
    assert not evaluation.check_no_emoji_stacking([sug("haha sawa 😂🔥😍✨")]).passed


def test_one_emoji_is_fine():
    assert evaluation.check_no_emoji_stacking([sug("haha sawa 😂")]).passed


def test_it_catches_a_suggestion_reciting_the_example_library():
    line = "Niaje, that lecture was rough"
    assert not evaluation.check_no_example_reuse([sug(line)], [line]).passed


def test_a_suggestion_merely_in_the_same_spirit_is_fine():
    assert evaluation.check_no_example_reuse(
        [sug("Eish, leo imekuwa ngumu")], ["Niaje, that lecture was rough"]
    ).passed


def test_it_catches_the_same_sentence_reworded():
    result = evaluation.check_options_are_distinct(
        [sug("So how was your day today"), sug("So how was your day, today")]
    )
    assert not result.passed


def test_genuinely_different_angles_pass():
    assert evaluation.check_options_are_distinct(
        [sug("Eish, that sounds rough"), sug("Umeamka aje leo?")]
    ).passed


def test_option_count_bounds():
    assert not evaluation.check_option_count([sug("one")]).passed
    assert evaluation.check_option_count([sug("one"), sug("two")]).passed


# --- the case file itself ------------------------------------------------------


def test_every_case_is_complete_and_runnable():
    """A malformed case would be skipped silently and prove nothing."""
    data = json.loads(CASES.read_text(encoding="utf-8"))
    ids = [c["id"] for c in data["cases"]]

    assert len(ids) == len(set(ids)), "case ids must be unique"
    for case in data["cases"]:
        assert case["why"].strip(), f"{case['id']} does not say why it exists"
        assert case["messages"], case["id"]
        assert case.get("expect") or case.get("generation"), (
            f"{case['id']} asserts nothing"
        )
        for message in case["messages"]:
            assert message["speaker"] in ("me", "them"), case["id"]


def test_the_set_covers_both_refusing_and_replying():
    """A set of only refusals would pass a product that refuses everything."""
    data = json.loads(CASES.read_text(encoding="utf-8"))
    blocked = [c for c in data["cases"] if c.get("expect", {}).get("blocked") is True]
    allowed = [c for c in data["cases"] if c.get("expect", {}).get("blocked") is False]
    assert blocked, "no case checks that the gates fire"
    assert allowed, "no case checks that an ordinary chat still gets a reply"
