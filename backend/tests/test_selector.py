"""Stage 3: choosing what to show from what the model drafted."""

from __future__ import annotations

from app.core.selector import label_for, select
from app.providers.base import GeneratedSuggestion


def c(text, style="", fit=None, approach="a"):
    return GeneratedSuggestion(text=text, rationale="r", approach=approach, style=style, fit=fit)


def test_best_first_then_other_directions():
    out = select([
        c("You're actually becoming a problem 😂", "natural", 7),
        c("At this rate I'll start charging you for all this distraction 😂", "funny", 9),
        c("I'm starting to think you enjoy making me like you", "bolder", 8),
        c("Okay but you're trouble", "playful", 5),
    ])
    assert [o.text for o in out] == [
        "At this rate I'll start charging you for all this distraction 😂",
        "I'm starting to think you enjoy making me like you",
        "You're actually becoming a problem 😂",
    ]
    assert [o.approach for o in out] == ["Best", "Bolder", "Natural"]


def test_the_same_reply_reworded_is_shown_once():
    out = select([
        c("Sleep is fighting for custody of you tonight 😂", "funny", 9),
        c("Sleep is really fighting for custody of you tonight", "natural", 8),
        c("Go sleep before you blame me for keeping you awake", "flirty", 7),
    ])
    assert len(out) == 2
    assert "custody" in out[0].text and "blame me" in out[1].text


def test_one_style_is_not_shown_twice():
    out = select([
        c("Joke one about the pilau", "funny", 9),
        c("A totally different joke about Saturday", "funny", 8),
        c("Saturday, you and me, that pilau place?", "bolder", 7),
    ])
    assert [o.approach for o in out] == ["Best", "Bolder"]


def test_a_clear_winner_is_shown_alone():
    out = select([c("Perfect for this moment", "creative", 9.5), c("Fine", "natural", 6), c("Meh", "short", 5)])
    assert [o.text for o in out] == ["Perfect for this moment"]


def test_templates_without_scores_pass_through_in_order():
    out = select([c("one", approach="warm"), c("two different words here", approach="tease")])
    assert [o.text for o in out] == ["one", "two different words here"]
    assert out[0].approach == "warm"  # no "Best" label invented for templates


def test_labels_are_friendly():
    assert label_for("teasing") == "Playful"
    assert label_for("creative/contextual") == "Original"
    assert label_for("bold") == "Bolder"
