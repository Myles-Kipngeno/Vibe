"""Learns how the user actually texts, from the user's own messages only.

Two rules shape this module:

* We only ever learn from messages the user marked as theirs. The other
  person's style is observed separately and is never blended into the user's
  voice.
* Learning is incremental and reversible. Every field stays editable, and the
  profile records how many messages it was built from so the user can judge
  whether it has seen enough to be trusted.
"""

from __future__ import annotations

from datetime import datetime, timezone

from ..schemas import Message, StyleProfile
from .textstats import (
    avg_word_count,
    emoji_frequency,
    laugh_frequency,
    normalize,
    question_frequency,
    sheng_ratio,
    top_expressions,
)

# How much a single learning pass is allowed to move an existing profile. New
# evidence should nudge the profile, not overwrite the user's own edits.
LEARNING_RATE = 0.35


def _blend(current: float, observed: float, weight: float) -> float:
    return round(current * (1 - weight) + observed * weight, 3)


def describe_humor(texts: list[str]) -> str:
    laughs = laugh_frequency(texts)
    if laughs >= 0.4:
        return "laughs a lot, leans on humour to keep things light"
    if laughs >= 0.15:
        return "playful teasing with the occasional joke"
    return "dry, understated humour"


def learn_from_messages(
    profile: StyleProfile, messages: list[Message], weight: float = LEARNING_RATE
) -> StyleProfile:
    """Return an updated copy of `profile` using the user's messages."""
    mine = [m.text for m in messages if m.speaker == "me" and m.text.strip()]
    if not mine:
        return profile

    updated = profile.model_copy(deep=True)
    updated.sheng_ratio = _blend(profile.sheng_ratio, sheng_ratio(mine), weight)
    updated.emoji_frequency = _blend(profile.emoji_frequency, emoji_frequency(mine), weight)
    updated.avg_message_length = max(
        1, round(_blend(float(profile.avg_message_length), avg_word_count(mine), weight))
    )
    # Directness: asking questions and writing short, unhedged messages reads as
    # direct. This is a heuristic and the user can override it in My Style.
    observed_directness = min(
        1.0, question_frequency(mine) * 0.6 + (1.0 if avg_word_count(mine) < 12 else 0.4)
    )
    updated.directness = _blend(profile.directness, observed_directness, weight * 0.5)
    updated.humor_style = describe_humor(mine)

    fresh = top_expressions(mine)
    merged = list(dict.fromkeys([*profile.common_expressions, *fresh]))
    updated.common_expressions = merged[:12]

    examples = list(dict.fromkeys([*profile.example_messages, *mine[-6:]]))
    updated.example_messages = examples[-10:]

    updated.learned_from_messages = profile.learned_from_messages + len(mine)
    updated.updated_at = datetime.now(timezone.utc)
    return updated


def sheng_budget(ratio: float) -> str:
    """How much Sheng the model may spend, as a rule rather than an adjective.

    "Natural Sheng + English mix" is not an instruction -- a model reading it
    reaches for every slang word it knows, which is the failure this product
    can least afford: slang he would not use, in a message sent under his name.
    A countable budget is something it can actually obey.
    """
    if ratio >= 0.7:
        return (
            "Sheng is his default register. Write Sheng sentences with English "
            "dropped in where it falls naturally, not English sentences with "
            "slang sprinkled on."
        )
    if ratio >= 0.35:
        return (
            "About one Sheng word or phrase a message, carrying otherwise "
            "English sentences. Two is already too many."
        )
    if ratio >= 0.12:
        return (
            "English is his base. At most one Sheng word every few messages, "
            "and only where it is doing real work."
        )
    return (
        "He does not write Sheng. Do not introduce it, however Kenyan the "
        "conversation sounds."
    )


def style_brief(profile: StyleProfile) -> str:
    """A compact, human-readable description handed to the language model."""
    if profile.sheng_ratio >= 0.7:
        language = "heavy Sheng with English words mixed in"
    elif profile.sheng_ratio >= 0.35:
        language = "natural Sheng + English mix, Sheng for flavour"
    elif profile.sheng_ratio >= 0.12:
        language = "mostly English with the occasional Sheng word"
    else:
        language = "plain English, almost no Sheng"

    if profile.emoji_frequency >= 0.6:
        emoji = "uses emojis often"
    elif profile.emoji_frequency >= 0.25:
        emoji = "uses an emoji now and then, rarely more than one"
    else:
        emoji = "hardly ever uses emojis"

    directness = (
        "direct and says what he means"
        if profile.directness >= 0.65
        else "fairly direct" if profile.directness >= 0.4 else "subtle, lets things unfold"
    )

    lines = [
        f"- Language: {language}",
        f"- Sheng budget: {sheng_budget(profile.sheng_ratio)}",
        f"- Message length: around {profile.avg_message_length} words",
        f"- Emojis: {emoji}",
        f"- Humour: {profile.humor_style}",
        f"- Directness: {directness}",
        f"- Flirting: {profile.flirting_style}",
    ]
    if profile.common_expressions:
        lines.append(
            "- Expressions he actually uses: "
            + ", ".join(profile.common_expressions[:8])
        )
    if profile.sheng_ratio >= 0.12:
        lines.append(
            "- Spend the budget on Sheng you have seen him use, here or in the "
            "conversation. Reaching for slang he has not used is how this "
            "starts sounding like an impression of him."
        )
    if profile.example_messages:
        samples = "; ".join(f'"{m}"' for m in profile.example_messages[-4:])
        lines.append(f"- Real examples of his texting: {samples}")
    if profile.learned_from_messages < 10:
        lines.append(
            f"- NOTE: this profile is built from only {profile.learned_from_messages} "
            "of his messages, so do not over-commit to it."
        )
    return "\n".join(lines)


def observe_their_style(messages: list[Message]) -> dict[str, float]:
    """Measured, not inferred: how the *other* person writes."""
    theirs = [m.text for m in messages if m.speaker == "them" and m.text.strip()]
    if not theirs:
        return {}
    return {
        "sheng_ratio": round(sheng_ratio(theirs), 3),
        "avg_words": round(avg_word_count(theirs), 2),
        "emoji_frequency": round(emoji_frequency(theirs), 3),
        "question_rate": round(question_frequency(theirs), 3),
        "laugh_rate": round(laugh_frequency(theirs), 3),
    }


def mirror_note(their_style: dict[str, float]) -> str:
    """Guidance so replies match her energy instead of steamrolling it."""
    if not their_style:
        return ""
    bits = []
    if their_style.get("avg_words", 0) < 5:
        bits.append("she writes short messages, so keep replies short too")
    elif their_style.get("avg_words", 0) > 18:
        bits.append("she writes long messages, so a one-liner will feel cold")
    if their_style.get("emoji_frequency", 0) < 0.15:
        bits.append("she barely uses emojis, so do not pile them on")
    if their_style.get("sheng_ratio", 0) < 0.1:
        bits.append("she texts in English, so keep Sheng light")
    elif their_style.get("sheng_ratio", 0) > 0.5:
        bits.append("she texts in Sheng, so matching that is natural")
    return "; ".join(bits)


def blank_profile() -> StyleProfile:
    return StyleProfile(
        common_expressions=[],
        example_messages=[],
        updated_at=datetime.now(timezone.utc),
    )
