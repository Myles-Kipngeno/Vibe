"""What the conversation is about, when that can be told from words alone.

This ran in every prompt and was reliably wrong. Counting content words in a
short conversation gives every word a count of one, and the old tiebreak was
alphabetical, so "how did the interview go?" came back as "about, actually,
asking" -- the first three words of the alphabet, and nothing to do with the
interview.

The fix is not a longer stoplist. It is that a word used once is not a topic,
so nothing is claimed without recurrence, and most short conversations honestly
return nothing.

All conversations here are fictional.
"""

from __future__ import annotations

from app.core.prompts import build_user_prompt
from app.core.rhythm import topic_guess
from app.schemas import Analysis
from tests.conftest import msgs


def analysis_with(topic: str) -> Analysis:
    return Analysis(
        topic=topic,
        tone="friendly",
        tone_confidence="medium",
        flow_state="flowing",
        engagement="engaged",
        engagement_confidence="medium",
        summary="",
        recommendation="continue",
        recommendation_reason="her turn",
    )


def prompt_for(topic: str) -> str:
    return build_user_prompt(
        messages=msgs(("them", "hey")),
        analysis=analysis_with(topic),
        goal="keep_flowing",
        style_text="- Language: plain English",
        their_style={},
        supplied_context={},
        memories=[],
        avoid=[],
        action=None,
        goodnight=False,
    )


# --- the regression -------------------------------------------------------------


def test_the_alphabet_is_not_a_topic():
    """The exact conversation that produced "about, actually, asking"."""
    topic = topic_guess(
        msgs(
            ("them", "hey, how did the interview go?"),
            ("me", "it went well actually, thanks for asking"),
            ("them", "that's great, you were nervous about it"),
        )
    )
    assert topic == "", topic
    for junk in ("about", "actually", "asking", "great", "were"):
        assert junk not in topic


def test_it_says_nothing_rather_than_guessing():
    """Most short conversations have no word that recurs. That is the answer."""
    for conversation in (
        msgs(("them", "so are we still on for saturday?"), ("me", "yeah what time")),
        msgs(("them", "niaje, uko aje leo?"), ("me", "poa sana, wewe je?")),
        msgs(("them", "haha"), ("me", "sawa")),
    ):
        assert topic_guess(conversation) == ""


# --- when there is something to say ---------------------------------------------


def test_a_word_that_recurs_is_the_topic():
    topic = topic_guess(
        msgs(
            ("them", "did you finish the assignment?"),
            ("me", "almost, the assignment is brutal"),
            ("them", "same, this assignment has taken all week"),
        )
    )
    assert topic == "assignment"


def test_the_most_repeated_word_comes_first():
    topic = topic_guess(
        msgs(
            ("them", "the wedding was lovely, the food was good"),
            ("me", "the wedding food was the best part"),
            ("them", "honestly the wedding made my month"),
        )
    )
    assert topic.split(", ")[0] == "wedding", topic


def test_ties_break_on_the_conversation_not_the_alphabet():
    """Two words used twice each: the one mentioned first wins.

    Alphabetical order is what produced the original bug, and it is not
    evidence of anything.
    """
    topic = topic_guess(
        msgs(
            ("them", "the zebra was incredible"),
            ("me", "and the antelope?"),
            ("them", "the zebra beat the antelope"),
        )
    )
    assert topic.startswith("zebra"), topic


def test_it_names_at_most_three_things():
    conversation = msgs(
        ("them", "wedding food music dancing flowers speeches"),
        ("me", "wedding food music dancing flowers speeches"),
    )
    assert len(topic_guess(conversation).split(", ")) <= 3


# --- what never counts ----------------------------------------------------------


def test_filler_is_not_a_topic_however_often_it_recurs():
    """These repeat constantly and mean nothing about the subject."""
    conversation = msgs(
        ("them", "actually I think that is really something"),
        ("me", "actually I think that is really something"),
        ("them", "actually I think that is really something"),
    )
    assert topic_guess(conversation) == ""


def test_sheng_filler_is_not_a_topic_either():
    conversation = msgs(
        ("them", "manze sasa yaani kabisa"),
        ("me", "manze sasa yaani kabisa"),
    )
    assert topic_guess(conversation) == ""


def test_one_word_replies_are_not_topics():
    conversation = msgs(("them", "sawa"), ("me", "sawa"), ("them", "sawa"))
    assert topic_guess(conversation) == ""


def test_an_empty_conversation_is_not_an_error():
    assert topic_guess([]) == ""


# --- and what reaches the model -------------------------------------------------


def test_no_topic_means_no_line_in_the_prompt():
    """An empty value must not become "Recurring subject: ".

    The model is reading the conversation a few lines further down. A blank
    label is worse than silence: it invites the model to explain the absence.
    """
    prompt = prompt_for("")
    assert "Recurring subject" not in prompt
    assert "Topic" not in prompt
    assert "## What the system measured" in prompt, "the rest must survive"
    assert "- Energy: friendly" in prompt


def test_a_real_topic_does_reach_the_prompt():
    prompt = prompt_for("assignment")
    assert "- Recurring subject: assignment" in prompt
    assert "- Energy: friendly" in prompt
