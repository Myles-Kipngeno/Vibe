"""Conversation rhythm: is this flowing, cooling off, or over?

Deliberately *not* rule-of-thumb dating advice. There is no "wait 20 minutes to
seem busy" here. We read the evidence actually present in the transcript --
message lengths, who is asking questions, low-effort replies, explicit sign-offs
and, only when timestamps exist, gaps -- and we say how confident we are.
"""

from __future__ import annotations

from datetime import datetime

from ..schemas import Analysis, FlowState, Message, OpenQuestion, Recommendation
from . import lexicon as lex
from .textstats import (
    avg_word_count,
    contains_any,
    is_low_effort,
    normalize,
    question_frequency,
    words,
)


def time_of_day(when: datetime | None) -> str | None:
    if when is None:
        return None
    hour = when.hour
    if 5 <= hour < 11:
        return "morning"
    if 11 <= hour < 17:
        return "day"
    if 17 <= hour < 22:
        return "evening"
    return "night"


def _has_timestamps(messages: list[Message]) -> bool:
    return sum(1 for m in messages if m.sent_at is not None) >= 2


def gap_minutes(messages: list[Message]) -> float | None:
    """Minutes since the last message, if we actually know when it was sent."""
    stamped = [m for m in messages if m.sent_at is not None]
    if len(stamped) < 1:
        return None
    last = stamped[-1].sent_at
    assert last is not None
    now = datetime.now(tz=last.tzinfo)
    return max(0.0, (now - last).total_seconds() / 60.0)


def open_questions(messages: list[Message]) -> list[OpenQuestion]:
    """Questions asked after the other side last replied -- i.e. still hanging."""
    result: list[OpenQuestion] = []
    for idx, msg in enumerate(messages):
        if "?" not in msg.text:
            continue
        answered = any(
            later.speaker != msg.speaker and not is_low_effort(later.text)
            for later in messages[idx + 1 :]
        )
        if not answered:
            for sentence in msg.text.split("?"):
                s = sentence.strip()
                if s:
                    result.append(
                        OpenQuestion(
                            text=f"{s}?", asked_by=msg.speaker, message_index=idx
                        )
                    )
    return result[-4:]


def engagement(messages: list[Message]) -> tuple[str, str, dict[str, float]]:
    """Read how invested the other person currently is.

    Returns (level, confidence, observed style metrics).
    """
    theirs = [m.text for m in messages if m.speaker == "them"]
    metrics: dict[str, float] = {}
    if not theirs:
        return "neutral", "low", metrics

    metrics["avg_words"] = round(avg_word_count(theirs), 2)
    metrics["question_rate"] = round(question_frequency(theirs), 2)

    recent = theirs[-3:]
    earlier = theirs[:-3]
    metrics["recent_avg_words"] = round(avg_word_count(recent), 2)

    low_effort_recent = sum(1 for t in recent if is_low_effort(t))
    asks_back = any("?" in t for t in recent)
    shrinking = bool(earlier) and avg_word_count(recent) < avg_word_count(earlier) * 0.55

    confidence = "high" if len(theirs) >= 5 else "medium" if len(theirs) >= 3 else "low"

    if low_effort_recent >= 2 and not asks_back:
        return "disengaged", confidence, metrics
    if shrinking and not asks_back:
        return "cooling", confidence, metrics
    if asks_back or avg_word_count(recent) >= 6:
        return "engaged", confidence, metrics
    return "neutral", "low" if confidence == "low" else "medium", metrics


def flow_state(messages: list[Message], level: str) -> FlowState:
    if not messages:
        return "stalled"
    recent_text = " ".join(m.text for m in messages[-3:])
    if contains_any(recent_text, lex.WIND_DOWN_PHRASES):
        return "winding_down"
    last = messages[-1]
    gap = gap_minutes(messages)
    if gap is not None and gap > 12 * 60:
        return "ended" if level == "disengaged" else "stalled"
    if last.speaker == "me" and level in ("cooling", "disengaged"):
        return "stalled"
    if level == "disengaged":
        return "slowing_down"
    if level == "cooling":
        return "slowing_down"
    return "flowing"


def detect_tone(messages: list[Message], max_tone: str = "flirtatious") -> tuple[str, str]:
    """Estimate the energy of the conversation, and how sure we are.

    We never upgrade the tone past what the evidence supports, and the returned
    tone is capped by the user's configured comfort level elsewhere.
    """
    recent = messages[-6:]
    joined = " ".join(m.text for m in recent)
    norm = normalize(joined)

    suggestive = contains_any(
        joined,
        (
            "miss you", "thinking about you", "wish you were here", "cuddle",
            "in bed", "kwa bed", "come over", "kuja hapa", "nakumiss",
            "what are you wearing", "your body", "kiss you",
        ),
    )
    flirty = contains_any(
        joined,
        (
            "cute", "beautiful", "mrembo", "handsome", "sexy", "crush", "date",
            "tuonane", "i like you", "nakupenda", "my person", "boyfriend material",
            "take you out", "nikuone",
        ),
    )
    heavy = contains_any(joined, ("sorry", "serious", "we need to talk", "pole sana"))
    playful_markers = sum(1 for m in recent if "?" not in m.text and len(words(m.text)) <= 12)

    if suggestive:
        tone = "suggestive"
    elif flirty:
        tone = "flirtatious"
    elif heavy and not flirty:
        tone = "serious"
    elif playful_markers >= 3 or "haha" in norm or "lol" in norm:
        tone = "playful"
    else:
        tone = "friendly"

    order = ["friendly", "playful", "flirtatious", "suggestive", "serious"]
    # Respect the user's configured ceiling (serious is never capped away).
    if tone != "serious" and order.index(tone) > order.index(max_tone):
        tone = max_tone

    confidence = "high" if len(recent) >= 5 else "medium" if len(recent) >= 3 else "low"
    if tone in ("flirtatious", "suggestive") and confidence == "high":
        confidence = "medium"  # reading intent is always a guess
    return tone, confidence


def recommend(
    messages: list[Message],
    level: str,
    flow: FlowState,
    boundary: bool,
) -> tuple[Recommendation, str]:
    """Continue / Stop / Wait, with the reason stated in plain language."""
    if boundary:
        return (
            "stop",
            "She said something that reads as a boundary. The respectful move is to "
            "acknowledge it and leave the ball in her court.",
        )
    if not messages:
        return "continue", "Nothing here yet -- an opener is all that is needed."

    last = messages[-1]
    if last.speaker == "me":
        if level in ("cooling", "disengaged"):
            return (
                "wait",
                "You sent the last message and her replies have been getting shorter. "
                "Following up again now would be doing all the work for both of you.",
            )
        return (
            "wait",
            "You are the last one to text. Give her the chance to reply.",
        )

    if flow == "winding_down":
        return (
            "stop",
            "One of you is signing off. Ending this warmly is better than stretching it.",
        )
    if level == "disengaged":
        return (
            "wait",
            "Her last few replies were one-word. Pushing more topics now usually reads "
            "as pressure -- leaving space is the stronger option.",
        )
    if level == "cooling":
        return (
            "continue",
            "It is slowing but she is still replying. One genuinely interesting message "
            "can turn this around; two in a row cannot.",
        )
    return "continue", "She is engaged and it is your turn."


def wind_down_context(messages: list[Message], tod: str | None) -> dict[str, bool]:
    """Signals the goodnight / next-morning suggestions depend on."""
    joined = " ".join(m.text for m in messages[-4:])
    theirs = [m.text for m in messages if m.speaker == "them"]
    return {
        "someone_signing_off": bool(contains_any(joined, lex.WIND_DOWN_PHRASES)),
        "is_night": tod == "night",
        "is_morning": tod == "morning",
        "was_warm": len(theirs) >= 2
        and avg_word_count(theirs) >= 5
        and question_frequency(theirs) > 0,
        "she_went_quiet": bool(messages) and messages[-1].speaker == "me",
    }


def summarise(messages: list[Message], level: str, flow: FlowState) -> str:
    """One honest sentence about where the conversation stands."""
    if not messages:
        return "No messages yet."
    theirs = [m for m in messages if m.speaker == "them"]
    mine = [m for m in messages if m.speaker == "me"]
    parts = [
        f"{len(messages)} messages ({len(mine)} from you, {len(theirs)} from her)",
        f"her replies average {avg_word_count([m.text for m in theirs]):.0f} words",
        f"reading as {level}",
        f"the thread is {flow.replace('_', ' ')}",
    ]
    if not _has_timestamps(messages):
        parts.append("no timestamps, so reply timing is unknown")
    return "; ".join(parts) + "."


def topic_guess(messages: list[Message]) -> str:
    """What is being talked about, when that can be told from words alone.

    A word used once is not a topic, it is a word. The previous version counted
    every content word and, because nothing repeats in a short conversation,
    broke the tie alphabetically -- so "how did the interview go?" came back as
    "about, actually, asking", which is the first three words of the alphabet
    and nothing to do with the interview. That went into every prompt.

    Recurrence is the only evidence available here that a word is the subject
    rather than incidental, so nothing is claimed without it. Most short
    conversations therefore return nothing at all, which is the honest answer:
    the model reading this has the conversation itself, and the interface can
    say it is not sure.
    """
    stop = lex.NAME_STOPWORDS | lex.LOW_EFFORT_REPLIES | lex.TOPIC_STOPWORDS

    counts: dict[str, int] = {}
    first_seen: dict[str, int] = {}
    for position, m in enumerate(messages[-8:]):
        for w in words(normalize(m.text)):
            if len(w) < 4 or w in stop:
                continue
            counts[w] = counts.get(w, 0) + 1
            first_seen.setdefault(w, position)

    # Ties break on where the word first appeared, not on the alphabet. Two
    # equally frequent words are equally good guesses; the earlier one at least
    # reflects the conversation rather than its spelling.
    recurring = [w for w, n in counts.items() if n >= 2]
    ranked = sorted(recurring, key=lambda w: (-counts[w], first_seen[w]))[:3]
    return ", ".join(ranked)


def build_analysis(
    messages: list[Message],
    alerts: list,
    local_time: datetime | None,
    max_tone: str = "flirtatious",
) -> Analysis:
    level, level_conf, metrics = engagement(messages)
    flow = flow_state(messages, level)
    boundary_alerts = [a for a in alerts if a.kind == "boundary_signal"]
    boundary = bool(boundary_alerts)
    tone, tone_conf = detect_tone(messages, max_tone=max_tone)
    if boundary:
        tone = "serious"
    rec, reason = recommend(messages, level, flow, boundary)

    notes: list[str] = []
    if not _has_timestamps(messages):
        notes.append(
            "No timestamps in this conversation, so I cannot tell how long the gaps "
            "between replies were."
        )
    if level_conf == "low":
        notes.append("Too few messages from her to read engagement with confidence.")
    if tone in ("flirtatious", "suggestive"):
        notes.append(
            "Reading the tone is a guess, not a fact -- interest and consent are "
            "not things I can read out of text."
        )

    return Analysis(
        topic=topic_guess(messages),
        tone=tone,  # type: ignore[arg-type]
        tone_confidence=tone_conf,  # type: ignore[arg-type]
        flow_state=flow,
        engagement=level,  # type: ignore[arg-type]
        engagement_confidence=level_conf,  # type: ignore[arg-type]
        summary=summarise(messages, level, flow),
        open_questions=open_questions(messages),
        alerts=alerts,
        boundary_detected=boundary,
        boundary_quotes=[a.quote for a in boundary_alerts],
        recommendation=rec,
        recommendation_reason=reason,
        time_of_day=time_of_day(local_time),  # type: ignore[arg-type]
        uncertainty_notes=notes,
        observed_their_style=metrics,
    )
