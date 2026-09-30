"""Stage 3 of the reply engine: choose what to show from what the model drafted.

The model drafts several candidates in different styles (natural, funny,
flirty, teasing, bolder...) and scores each against the quality check. This
module does the choosing in code rather than trusting the model to hold back:
it ranks by that score, drops near-duplicates so the options go in different
directions, and shows a single reply when one is clearly the best.

It never picks "the funniest" or "the boldest" on its own account: the score
it ranks by is the model's judgement of fit for this exact moment.
"""

from __future__ import annotations

import re

from ..providers.base import GeneratedSuggestion

MAX_SHOWN = 3
# Scores are 1-10. A lead this large means the rest are just filler.
CLEAR_WINNER_MARGIN = 2.5
# Share of words two options have in common before they count as the same reply.
NEAR_DUPLICATE = 0.6

_LABELS = {
    "natural": "Natural",
    "funny": "Funny",
    "flirty": "Flirty",
    "playful": "Playful",
    "teasing": "Playful",
    "creative": "Original",
    "contextual": "Original",
    "romantic": "Romantic",
    "bold": "Bolder",
    "bolder": "Bolder",
    "short": "Short",
    "sweet": "Sweet",
    "savage": "Savage",
    "spicy": "Spicy",
    "supportive": "Support",
}


def label_for(style: str) -> str:
    key = style.strip().lower().split("/")[0].split(" ")[0]
    return _LABELS.get(key, style.strip().capitalize() or "Option")


def _words(text: str) -> set[str]:
    return set(re.findall(r"[a-z']+", text.lower()))


def _same_reply(a: str, b: str) -> bool:
    wa, wb = _words(a), _words(b)
    if not wa or not wb:
        return a.strip().lower() == b.strip().lower()
    return len(wa & wb) / min(len(wa), len(wb)) >= NEAR_DUPLICATE


def select(candidates: list[GeneratedSuggestion]) -> list[GeneratedSuggestion]:
    """The best 1-3, meaningfully different from each other, best first.

    Candidates without a score (templates, older providers) keep their order.
    The first shown is labelled "Best"; the others by their style.
    """
    scored = [c for c in candidates if c.text.strip()]
    if not scored:
        return []
    if all(c.fit is not None for c in scored):
        scored = sorted(scored, key=lambda c: c.fit or 0, reverse=True)

    chosen: list[GeneratedSuggestion] = []
    for c in scored:
        if any(_same_reply(c.text, k.text) for k in chosen):
            continue
        # A second option in the same style is only worth it if nothing else is left.
        if c.style and any(k.style and label_for(k.style) == label_for(c.style) for k in chosen):
            continue
        chosen.append(c)
        if len(chosen) == MAX_SHOWN:
            break
    if len(chosen) < 2:
        for c in scored:
            if c not in chosen and not any(_same_reply(c.text, k.text) for k in chosen):
                chosen.append(c)
            if len(chosen) == 2:
                break

    best, rest = chosen[0], chosen[1:]
    if best.fit is not None and rest and all(r.fit is not None for r in rest):
        if best.fit - max(r.fit or 0 for r in rest) >= CLEAR_WINNER_MARGIN:
            rest = []

    if best.style or best.fit is not None:
        best = best.model_copy(update={"approach": "Best"})
        rest = [r.model_copy(update={"approach": label_for(r.style) if r.style else r.approach}) for r in rest]
    return [best, *rest]
