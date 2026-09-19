"""Continue / Stop / Wait, engagement reading and honesty about uncertainty."""

from __future__ import annotations

from datetime import datetime

from app.core import generator
from app.core.rhythm import build_analysis, engagement, open_questions
from tests.conftest import msgs

NIGHT = datetime(2026, 3, 4, 23, 30)
MORNING = datetime(2026, 3, 5, 8, 15)


def analyse(convo, when=None):
    return generator.analyse(convo, set(), None, when, "flirtatious")


def test_waits_when_the_user_sent_the_last_message():
    convo = msgs(("them", "haha sawa"), ("me", "so tuonane siku gani?"))
    assert analyse(convo).recommendation == "wait"


def test_waits_instead_of_pushing_a_disengaged_conversation():
    convo = msgs(
        ("me", "So how was the trip? ulienjoy?"),
        ("them", "yeah"),
        ("me", "nice, what was the best part"),
        ("them", "k"),
    )
    analysis = analyse(convo)
    assert analysis.engagement == "disengaged"
    assert analysis.recommendation == "wait"
    assert "pressure" in analysis.recommendation_reason


def test_continues_when_she_is_engaged_and_it_is_his_turn():
    convo = msgs(
        ("me", "niaje"),
        ("them", "Niko poa! Nimekuwa nikisoma hii book ya mystery, iko noma sana"),
        ("me", "haha which one"),
        ("them", "Ni ya detective, sitakuambia ending. Wewe husoma?"),
    )
    analysis = analyse(convo)
    assert analysis.engagement == "engaged"
    assert analysis.recommendation == "continue"


def test_stops_when_someone_signs_off():
    convo = msgs(
        ("me", "haha hiyo ni noma"),
        ("them", "Nimechoka sana, nalala. Goodnight"),
    )
    analysis = analyse(convo, NIGHT)
    assert analysis.flow_state == "winding_down"
    assert analysis.recommendation == "stop"


def test_a_boundary_forces_stop_and_serious_tone():
    convo = msgs(("me", "you're cute"), ("them", "I have a boyfriend"))
    analysis = analyse(convo)
    assert analysis.boundary_detected
    assert analysis.recommendation == "stop"
    assert analysis.tone == "serious"


def test_missing_timestamps_are_declared_not_guessed():
    analysis = analyse(msgs(("them", "hi"), ("me", "niaje")))
    assert any("timestamp" in note.lower() for note in analysis.uncertainty_notes)


def test_tone_reading_is_flagged_as_an_inference():
    convo = msgs(
        ("me", "you looked good in that pic"),
        ("them", "haha thanks, you're cute too"),
        ("me", "so when do I take you out"),
    )
    analysis = analyse(convo)
    assert analysis.tone in ("flirtatious", "playful")
    if analysis.tone == "flirtatious":
        assert any("guess" in n for n in analysis.uncertainty_notes)


def test_tone_is_capped_by_the_users_comfort_setting():
    convo = msgs(("them", "nakumiss, wish you were here"), ("me", "same"))
    capped = build_analysis(convo, [], None, max_tone="playful")
    assert capped.tone == "playful"


def test_open_questions_are_tracked():
    convo = msgs(
        ("them", "Unafanya nini weekend?"),
        ("me", "sijui bado"),
        ("me", "Wewe uko na plan?"),
    )
    questions = open_questions(convo)
    assert any("plan" in q.text for q in questions)


def test_engagement_confidence_is_low_on_a_short_thread():
    _, confidence, _ = engagement(msgs(("them", "hi")))
    assert confidence == "low"


def test_goodnight_is_offered_at_night_when_winding_down():
    convo = msgs(("me", "haha"), ("them", "Nimechoka, nalala"))
    analysis = analyse(convo, NIGHT)
    assert generator.should_offer_goodnight(convo, analysis)


def test_morning_follow_up_is_not_offered_after_an_unanswered_message():
    convo = msgs(
        ("them", "Goodnight \U0001F60A"),
        ("me", "Goodnight, tuongee kesho"),
    )
    analysis = analyse(convo, MORNING)
    assert not generator.should_offer_morning(convo, analysis)


def test_morning_follow_up_is_offered_after_a_warm_conversation():
    convo = msgs(
        ("me", "hii convo imekuwa poa sana"),
        ("them", "Haha ndio, nimeenjoy. Wewe hulala saa ngapi?"),
        ("me", "kawaida saa nne"),
        ("them", "Poa, nalala sasa. Goodnight \U0001F60A"),
    )
    analysis = analyse(convo, MORNING)
    assert generator.should_offer_morning(convo, analysis)
