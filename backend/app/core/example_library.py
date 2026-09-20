"""Choosing which curated examples, if any, are worth showing the model.

The library is a reference, not a script. An example records a situation he
handled and the line he wrote; the danger is obvious and worth naming, because
the whole product is defined against it -- handed a stack of lines that worked,
a model will send one. That is a pickup-line generator, and it is the thing
this app refuses to be.

So selection is conservative in three ways. Examples are excluded outright when
the conversation is in a state where reaching for a past success is wrong, they
must earn their place by matching the situation rather than merely existing,
and the prompt section built from them says plainly that they are there for
register and approach and that the words are not to be reused.
"""

from __future__ import annotations

from ..schemas import Analysis, ConversationExample

# Two is enough to show a register. More reads as a menu to pick from.
MAX_EXAMPLES = 2

# Among examples that match the situation, this is the floor for being worth
# showing at all. Matching the situation is a precondition, not a contributor:
# see _matches_situation.
MIN_SCORE = 1

_GOAL_WORDS: dict[str, tuple[str, ...]] = {
    "start": ("start", "opening", "first", "new", "intro"),
    "keep_flowing": ("flow", "ongoing", "chat", "casual", "keep"),
    "make_her_laugh": ("joke", "funny", "laugh", "humour", "humor", "banter"),
    "playful": ("playful", "tease", "banter", "light"),
    "flirt": ("flirt", "flirty", "interest", "attraction"),
    "get_to_know": ("know", "question", "curious", "deeper", "learn"),
    "answer_personal": ("personal", "answer", "serious", "open"),
    "ask_out": ("ask", "date", "meet", "plans", "out"),
    "comfort": ("comfort", "support", "bad", "rough", "sad", "difficult"),
    "end_naturally": ("end", "close", "wrap", "goodbye", "exit"),
    "next_day": ("morning", "next day", "follow", "again"),
}

_MIX_BANDS: tuple[tuple[float, str], ...] = (
    (0.7, "heavy_sheng"),
    (0.35, "mixed"),
    (0.12, "light_sheng"),
    (0.0, "english"),
)


def mix_band(sheng_ratio: float) -> str:
    """The language band his measured ratio falls into."""
    for floor, name in _MIX_BANDS:
        if sheng_ratio >= floor:
            return name
    return "english"


def _mentions(haystack: str, needles: tuple[str, ...]) -> bool:
    lowered = haystack.lower()
    return any(n in lowered for n in needles)


def _matches_situation(example: ConversationExample, goal: str) -> bool:
    """Whether the example is about the kind of moment he is actually in.

    This is a gate rather than a score. As a score it was worth two points of
    four, which meant an example about opening a conversation could be offered
    for "make her laugh" on the strength of being in the same language band and
    recording a failure -- two incidental signals, no relevance at all. Nothing
    should reach the prompt without matching the situation.
    """
    subject = f"{example.situation} {example.context} {example.tone}"
    return _mentions(subject, _GOAL_WORDS.get(goal, ()))


def _score(example: ConversationExample, analysis: Analysis, band: str) -> int:
    """How to rank examples that have already matched the situation."""
    score = 0
    if example.tone and example.tone.lower() == analysis.tone.lower():
        score += 1
    if example.language_mix == band:
        score += 1
    # An example that recorded what did not work is more useful than one that
    # only recorded a win: it carries the edge as well as the centre.
    if example.what_did_not.strip():
        score += 1
    return score


def relevant(
    examples: list[ConversationExample],
    goal: str,
    analysis: Analysis,
    sheng_ratio: float,
) -> list[ConversationExample]:
    """The examples worth showing, best first, or nothing at all.

    Returning nothing is a normal outcome and usually the right one. A library
    built from other conversations has no standing to advise this one.
    """
    if analysis.boundary_detected:
        # She has set a boundary. Nothing that once "worked" applies here, and
        # reaching for it is exactly the behaviour the gates exist to stop.
        return []

    band = mix_band(sheng_ratio)
    scored = [
        (_score(e, analysis, band), e)
        for e in examples
        if _matches_situation(e, goal) and not _excluded(e, analysis)
    ]
    ranked = sorted(
        (pair for pair in scored if pair[0] >= MIN_SCORE),
        key=lambda pair: pair[0],
        reverse=True,
    )
    return [e for _, e in ranked[:MAX_EXAMPLES]]


def _excluded(example: ConversationExample, analysis: Analysis) -> bool:
    """Honour the example's own statement of when it does not apply.

    `not_suitable_when` is free text he wrote, so this is a keyword check and
    nothing cleverer. It is matched against the state the analysis measured,
    and when in doubt the example is dropped -- a missing example costs a
    little texture, a misapplied one costs him the conversation.
    """
    text = example.not_suitable_when.lower()
    if not text.strip():
        return False

    cooling = analysis.engagement in ("cooling", "disengaged")
    fading = analysis.flow_state in ("slowing_down", "winding_down", "stalled", "ended")
    heavy = analysis.tone == "serious"

    # The keys are the words he is likely to reach for; the values are the
    # states the analysis can actually measure. Anything the analysis cannot
    # see is not listed, rather than approximated.
    state = {
        "boundary": analysis.boundary_detected,
        "low engagement": cooling,
        "disengaged": cooling,
        "cooling": cooling,
        "not interested": cooling,
        "one word": cooling,
        "winding down": fading,
        "slowing": fading,
        "stalled": fading,
        "serious": heavy,
        "sad": heavy,
        "upset": heavy,
        "heavy": heavy,
    }
    return any(phrase in text and applies for phrase, applies in state.items())


def examples_brief(examples: list[ConversationExample]) -> str:
    """The prompt section. Empty when nothing was selected."""
    if not examples:
        return ""

    blocks: list[str] = []
    for example in examples:
        parts = [f"- Situation: {example.situation}"]
        if example.context:
            parts.append(f"  Context: {example.context}")
        if example.opening_line:
            parts.append(f'  What he wrote: "{example.opening_line}"')
        if example.reaction:
            parts.append(f"  What came back: {example.reaction}")
        if example.what_worked:
            parts.append(f"  Why it worked: {example.what_worked}")
        if example.what_did_not:
            parts.append(f"  What fell flat: {example.what_did_not}")
        if example.not_suitable_when:
            parts.append(f"  He marked it wrong for: {example.not_suitable_when}")
        blocks.append("\n".join(parts))

    return "\n".join(blocks)
