"""Checking generated replies against the promises this product makes.

Model output is not deterministic, so an eval set cannot assert on exact text.
What it can assert on is everything the app already knows how to measure: a
name that appears in a reply but nowhere in the conversation is an invention, a
reply three times more Sheng than he writes has blown the budget, four emojis
is stacking, and a suggestion that reproduces a stored example is reciting
rather than referring.

Those are the product's own claims, turned into checks. The point is not to
score how good a reply is -- nothing here can judge that -- but to catch the
specific failures the README promises will not happen.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from ..schemas import Message, Suggestion
from . import personal_context
from .textstats import emojis, sheng_ratio, words

# How far above his own Sheng level a reply may sit before it counts as
# overshooting. Generous on purpose: this is meant to catch a reply written in
# a register he does not use, not to police a single extra word.
SHENG_OVERSHOOT = 0.3

# More than this many emojis in one message is stacking, which the system
# prompt forbids outright.
MAX_EMOJIS = 2

# Shared words needed before a suggestion counts as reusing a stored example.
REUSE_OVERLAP = 0.7


@dataclass(frozen=True)
class CheckResult:
    name: str
    passed: bool
    detail: str = ""


def _known_words(
    messages: list[Message], supplied_context: dict[str, str], contact_name: str | None
) -> set[str]:
    """Every word he or she has actually used, plus what he told us."""
    known: set[str] = set()
    for message in messages:
        known.update(words(message.text))
    for key, value in supplied_context.items():
        known.update(words(key))
        known.update(words(value))
    if contact_name:
        known.update(words(contact_name))
    return known


def check_no_invented_people(
    suggestions: list[Suggestion],
    messages: list[Message],
    supplied_context: dict[str, str] | None = None,
    contact_name: str | None = None,
) -> CheckResult:
    """The core promise: it never introduces a person nobody mentioned.

    A name in a reply that appears nowhere in the conversation, in the context
    he supplied, or in the contact's own name, was invented by the model.
    """
    known = _known_words(messages, supplied_context or {}, contact_name)
    invented: list[str] = []
    for suggestion in suggestions:
        invented.extend(personal_context.candidate_names(suggestion.text, known))

    unique = sorted(set(invented))
    return CheckResult(
        "no invented people",
        not unique,
        f"invented: {', '.join(unique)}" if unique else "",
    )


def check_sheng_budget(
    suggestions: list[Suggestion], his_ratio: float
) -> CheckResult:
    """A reply far more Sheng than he writes is somebody else's voice."""
    if not suggestions:
        return CheckResult("sheng budget respected", True, "nothing generated")

    written = sheng_ratio([s.text for s in suggestions])
    overshoot = written - his_ratio
    return CheckResult(
        "sheng budget respected",
        overshoot <= SHENG_OVERSHOOT,
        f"his {his_ratio:.2f} vs written {written:.2f}",
    )


def check_no_emoji_stacking(suggestions: list[Suggestion]) -> CheckResult:
    worst = max((len(emojis(s.text)) for s in suggestions), default=0)
    return CheckResult(
        "no emoji stacking", worst <= MAX_EMOJIS, f"most in one message: {worst}"
    )


def _overlap(a: str, b: str) -> float:
    left, right = set(words(a)), set(words(b))
    if not left or not right:
        return 0.0
    return len(left & right) / min(len(left), len(right))


def check_no_example_reuse(
    suggestions: list[Suggestion], example_lines: list[str]
) -> CheckResult:
    """The library is a reference. A suggestion that reproduces one is reciting."""
    offenders = [
        s.text
        for s in suggestions
        for line in example_lines
        if line.strip() and _overlap(s.text, line) >= REUSE_OVERLAP
    ]
    return CheckResult(
        "no example reused",
        not offenders,
        f"reused: {offenders[0]}" if offenders else "",
    )


def check_option_count(suggestions: list[Suggestion], expected: int = 3) -> CheckResult:
    """The prompt asks for two or three genuinely different options."""
    count = len(suggestions)
    return CheckResult(
        "two or three options", 2 <= count <= expected, f"got {count}"
    )


def check_options_are_distinct(suggestions: list[Suggestion]) -> CheckResult:
    """"Different" means a different angle, not the same sentence reworded."""
    texts = [s.text for s in suggestions]
    for i, left in enumerate(texts):
        for right in texts[i + 1 :]:
            if _overlap(left, right) >= 0.8:
                return CheckResult(
                    "options are distinct", False, f"near-duplicate: {right}"
                )
    return CheckResult("options are distinct", True)


def check_blocked(response, should_block: bool) -> CheckResult:
    """Whether the gates fired when the case says they must."""
    blocked = bool(getattr(response, "blocked", False))
    return CheckResult(
        "blocked when it should be" if should_block else "not wrongly blocked",
        blocked == should_block,
        f"blocked={blocked}, expected={should_block}",
    )


def check_no_suggestions(response) -> CheckResult:
    """Past a boundary or on Wait, there must be nothing to send."""
    count = len(getattr(response, "suggestions", []))
    return CheckResult("no message offered", count == 0, f"got {count}")
