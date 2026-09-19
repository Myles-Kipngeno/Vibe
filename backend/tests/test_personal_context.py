"""The behaviour this product lives or dies on: knowing what it does not know.

All names and events here are fictional.
"""

from __future__ import annotations

import pytest

from app.core.personal_context import candidate_names, detect_alerts, unresolved
from tests.conftest import msgs


def kinds(alerts) -> set[str]:
    return {a.kind for a in alerts}


def subjects(alerts) -> set[str]:
    return {(a.subject or "").lower() for a in alerts}


# --- It must ask ---------------------------------------------------------------


def test_unknown_person_triggers_context_alert():
    convo = msgs(
        ("me", "Niaje, ulikuwa unafanya nini weekend?"),
        ("them", "Nilikuwa nimechill tu. Ulienda town na Randy?"),
    )
    alerts = detect_alerts(convo)
    assert "personal_context" in kinds(alerts)
    assert "randy" in subjects(alerts)


def test_question_about_a_relative_triggers_an_alert():
    convo = msgs(
        ("me", "It was a long weekend"),
        ("them", "How did your sister take it?"),
    )
    alerts = detect_alerts(convo)
    assert any(a.subject == "sister" for a in alerts)


def test_shared_memory_with_laughter_is_flagged_as_an_inside_joke():
    convo = msgs(
        ("me", "leo nimechoka"),
        ("them", "\U0001F602\U0001F602 remember what happened at Naivas?"),
    )
    alerts = detect_alerts(convo)
    assert "inside_joke" in kinds(alerts)


def test_sheng_reference_to_a_past_event_is_flagged():
    convo = msgs(("me", "sasa"), ("them", "Ulimwambia ama bado?"))
    alerts = detect_alerts(convo)
    assert "personal_context" in kinds(alerts)


def test_follow_up_about_an_unseen_event_is_flagged():
    convo = msgs(("me", "hey"), ("them", "So did she finally reply?"))
    alerts = detect_alerts(convo)
    assert "personal_context" in kinds(alerts)


def test_picture_request_is_high_priority():
    convo = msgs(("me", "haha"), ("them", "Send me a pic \U0001F602"))
    alerts = detect_alerts(convo)
    picture = [a for a in alerts if a.kind == "picture_request"]
    assert picture and picture[0].priority == "high"


def test_boundary_statement_is_surfaced():
    convo = msgs(("me", "tuonane weekend?"), ("them", "Sorry, I have a boyfriend"))
    alerts = detect_alerts(convo)
    assert "boundary_signal" in kinds(alerts)


def test_emotional_shift_is_surfaced():
    convo = msgs(("me", "vipi leo"), ("them", "Not great honestly, I lost my job"))
    alerts = detect_alerts(convo)
    assert "emotional_shift" in kinds(alerts)


# --- It must NOT ask -----------------------------------------------------------


def test_ordinary_message_does_not_interrupt():
    convo = msgs(
        ("me", "Niaje, umeamka aje?"),
        ("them", "Niko poa sana, nimeamka mapema leo. Wewe je?"),
    )
    assert detect_alerts(convo) == []


def test_known_place_is_not_treated_as_a_mystery_person():
    convo = msgs(("me", "uko wapi"), ("them", "Niko Westlands nikichill"))
    assert not any(a.kind == "personal_context" for a in detect_alerts(convo))


def test_a_person_the_user_introduced_is_not_re_asked():
    convo = msgs(
        ("me", "Nilikuwa na Brian jana, we went hiking"),
        ("them", "Haha sawa, na Brian mlienjoy?"),
    )
    alerts = detect_alerts(convo)
    assert "brian" not in subjects(alerts)


def test_already_answered_context_is_never_asked_again():
    convo = msgs(("me", "sasa"), ("them", "Ulienda town na Randy?"))
    first = detect_alerts(convo)
    key = next(a.dedupe_key for a in first if a.subject == "Randy")

    second = detect_alerts(convo, known_context_keys={key})
    assert "randy" not in subjects(second)


def test_only_the_messages_being_replied_to_raise_alerts():
    """An old reference the user already answered should not resurface."""
    convo = msgs(
        ("them", "Ulienda town na Randy?"),
        ("me", "Ndio, tulienda"),
        ("them", "Poa sana"),
    )
    assert "randy" not in subjects(detect_alerts(convo))


# --- Blocking behaviour --------------------------------------------------------


def test_low_priority_alerts_do_not_block_generation():
    convo = msgs(("me", "tuonane kesho?"), ("them", "we'll see"))
    alerts = detect_alerts(convo)
    assert alerts, "an ambiguous reply should still be surfaced"
    assert unresolved(alerts, {}) == []


def test_missing_context_blocks_until_the_user_answers():
    convo = msgs(("me", "sasa"), ("them", "Ulienda town na Randy?"))
    alerts = detect_alerts(convo)
    blocking = unresolved(alerts, {})
    assert blocking

    answered = {a.dedupe_key: "Randy is my cousin, we went to buy a phone" for a in blocking}
    assert unresolved(alerts, answered) == []


@pytest.mark.parametrize(
    "text,expected",
    [
        ("Ulienda town na Randy?", "Randy"),
        ("Did you tell Brian what happened?", "Brian"),
        ("niko poa", None),
        ("I was in Nairobi", None),
    ],
)
def test_candidate_name_extraction(text, expected):
    names = candidate_names(text, set())
    if expected is None:
        assert names == []
    else:
        assert expected in names
