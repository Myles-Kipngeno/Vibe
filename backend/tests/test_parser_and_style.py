"""Parsing pasted conversations, and learning the user's voice."""

from __future__ import annotations

from app.core.parser import parse_conversation
from app.core.style_profile import blank_profile, learn_from_messages, style_brief
from app.core.textstats import is_low_effort, sheng_ratio
from tests.conftest import msgs


def test_parses_a_whatsapp_export():
    raw = """[04/03/2026, 21:04] Ann: Niaje
[04/03/2026, 21:05] Me: Poa sana, wewe je
[04/03/2026, 21:06] Ann: Niko fiti"""
    result = parse_conversation(raw, me_label="Me", them_label="Ann")
    assert result.detected_format == "whatsapp_export"
    assert [m.speaker for m in result.messages] == ["them", "me", "them"]
    assert result.messages[0].sent_at is not None


def test_parses_labelled_lines():
    raw = "Her: hey\nMe: niaje\nHer: unafanya nini"
    result = parse_conversation(raw, me_label="Me", them_label="Her")
    assert result.detected_format == "labelled"
    assert [m.speaker for m in result.messages] == ["them", "me", "them"]


def test_falls_back_to_alternating_and_says_so():
    raw = "hey\nniaje\nunafanya nini"
    result = parse_conversation(raw)
    assert result.detected_format == "alternating_guess"
    assert len(result.messages) == 3


def test_multiline_messages_stay_together():
    raw = "Her: hey\nthis is the same message\nMe: sawa"
    result = parse_conversation(raw, me_label="Me", them_label="Her")
    assert "same message" in result.messages[0].text
    assert len(result.messages) == 2


def test_style_is_learned_only_from_the_users_own_messages():
    convo = msgs(
        ("them", "Hello, how are you doing today? I hope you are well."),
        ("me", "niaje manze"),
        ("me", "poa sana bana"),
    )
    learned = learn_from_messages(blank_profile(), convo)
    assert learned.learned_from_messages == 2
    # Her long, formal English must not drag his profile toward long messages.
    assert learned.avg_message_length <= 9


def test_heavy_sheng_raises_the_sheng_ratio():
    convo = msgs(("me", "niaje manze"), ("me", "poa sana bana"), ("me", "sawa msee"))
    learned = learn_from_messages(blank_profile(), convo)
    assert learned.sheng_ratio > blank_profile().sheng_ratio


def test_english_only_messages_lower_the_sheng_ratio():
    convo = msgs(
        ("me", "I will be there in about twenty minutes"),
        ("me", "Traffic is bad today so it might take longer"),
    )
    learned = learn_from_messages(blank_profile(), convo)
    assert learned.sheng_ratio < blank_profile().sheng_ratio


def test_style_brief_warns_when_the_profile_is_thin():
    brief = style_brief(blank_profile())
    assert "do not over-commit" in brief


def test_low_effort_replies_are_recognised():
    assert is_low_effort("k")
    assert is_low_effort("sawa")
    assert is_low_effort("\U0001F602")
    assert not is_low_effort("haha that was actually funny, tell me more")


def test_sheng_ratio_separates_english_from_sheng():
    assert sheng_ratio(["niaje manze poa"]) > sheng_ratio(["see you later tonight"])
