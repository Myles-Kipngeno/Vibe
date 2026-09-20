"""What his verdicts on past suggestions say about what he will actually send.

Every suggestion he marks used, edited or rejected is a judgement on our own
output, which makes this the only signal in the app that is about us rather than
about her. It is also the easiest one to over-read: three rejections in a row
are just as likely to be three bad suggestions as a standing preference. So this
module measures instead of guessing, refuses to report a pattern until there is
enough of it on both sides, and hands the model his own words in preference to
our summary of them.

None of this is scoped per contact, on purpose. What is being learned is how he
likes his own replies to sound, and that travels with him between conversations.
"""

from __future__ import annotations

from dataclasses import dataclass, field

from .textstats import (
    avg_word_count,
    emoji_frequency,
    question_frequency,
    sheng_ratio,
)

KEPT_VERDICTS = ("used", "edited")

# Below this many judgements there is no pattern worth claiming, only noise.
MIN_TOTAL = 4
# A comparison needs examples on both sides or it is describing one pile.
MIN_PER_SIDE = 2
# Above this we stop hedging in the prompt.
CONFIDENT_TOTAL = 12

MAX_EXAMPLES = 4
MAX_NOTES = 4
MAX_EXAMPLE_CHARS = 140

# A difference smaller than this is measurement wobble, not a preference.
WORD_DELTA = 4.0
SHENG_DELTA = 0.18
EMOJI_DELTA = 0.35
QUESTION_DELTA = 0.35


@dataclass(frozen=True)
class FeedbackSignal:
    """A read of his feedback history, with the evidence it rests on."""

    used: int = 0
    edited: int = 0
    rejected: int = 0
    used_examples: list[str] = field(default_factory=list)
    rejected_examples: list[str] = field(default_factory=list)
    notes: list[str] = field(default_factory=list)
    patterns: list[str] = field(default_factory=list)

    @property
    def total(self) -> int:
        return self.used + self.edited + self.rejected

    @property
    def kept(self) -> int:
        return self.used + self.edited

    @property
    def has_signal(self) -> bool:
        """True when there is something honest to tell the model."""
        return self.total >= MIN_TOTAL and bool(
            self.patterns or self.notes or self.rejected_examples or self.used_examples
        )


def _clip(text: str) -> str:
    text = " ".join(text.split())
    if len(text) <= MAX_EXAMPLE_CHARS:
        return text
    return text[: MAX_EXAMPLE_CHARS - 1].rstrip() + "\u2026"


def _compare(kept: list[str], rejected: list[str]) -> list[str]:
    """Plain-language differences between what he sends and what he discards.

    Only differences big enough to survive a handful of samples are reported;
    everything else is left unsaid rather than dressed up as a finding.
    """
    if len(kept) < MIN_PER_SIDE or len(rejected) < MIN_PER_SIDE:
        return []

    out: list[str] = []

    kept_words, rej_words = avg_word_count(kept), avg_word_count(rejected)
    if abs(kept_words - rej_words) >= WORD_DELTA:
        direction = "shorter" if kept_words < rej_words else "longer"
        out.append(
            f"He keeps the {direction} ones: what he sent averaged "
            f"{kept_words:.0f} words, what he threw out {rej_words:.0f}."
        )

    kept_sheng, rej_sheng = sheng_ratio(kept), sheng_ratio(rejected)
    if abs(kept_sheng - rej_sheng) >= SHENG_DELTA:
        out.append(
            "He keeps the more Sheng-heavy ones and rejects the plainer English."
            if kept_sheng > rej_sheng
            else "He rejects the ones that lean hardest on Sheng -- the slang is "
            "being laid on thicker than he would."
        )

    kept_emoji, rej_emoji = emoji_frequency(kept), emoji_frequency(rejected)
    if abs(kept_emoji - rej_emoji) >= EMOJI_DELTA:
        out.append(
            "Emojis are not the problem -- the ones he sent had them."
            if kept_emoji > rej_emoji
            else "He rejects the ones carrying emojis. Write without them."
        )

    kept_q, rej_q = question_frequency(kept), question_frequency(rejected)
    if abs(kept_q - rej_q) >= QUESTION_DELTA:
        out.append(
            "He keeps the ones that ask her something back."
            if kept_q > rej_q
            else "He rejects the ones that end in a question. Not every reply "
            "needs to hand her homework."
        )

    return out


def _latest_per_suggestion(feedback: list[dict]) -> list[dict]:
    """Oldest-first, one entry per suggestion, keeping his latest word on it."""
    ordered = sorted(
        feedback, key=lambda e: str(e.get("created_at") or ""), reverse=False
    )
    latest: dict[str, dict] = {}
    for entry in ordered:
        key = str(entry.get("suggestion_id") or "") or f"_anon{len(latest)}"
        latest[key] = entry
    return list(latest.values())


def summarize(feedback: list[dict]) -> FeedbackSignal:
    """Condense the raw feedback log into something a prompt can use.

    Two things are normalised first, because the stores do not agree and the
    UI can send the same verdict twice.

    Order: the local store appends oldest-first, the Supabase store returns
    `created_at.desc`. Sorting on the timestamp makes "most recent" mean the
    same thing on both, so quoting recent verdicts is not backwards on one.

    Repeats: a verdict is a judgement on a suggestion, not a click. Copying a
    suggestion twice, or rejecting it and then saying why, must not count
    twice or the pattern comparison ends up reading emphasis into a
    double-tap. The last judgement of each suggestion is the one that stands --
    storage stays a faithful log, and this is the only place that decides what
    the log means.
    """
    if not feedback:
        return FeedbackSignal()

    feedback = _latest_per_suggestion(feedback)

    used = edited = rejected = 0
    kept_texts: list[str] = []
    rejected_texts: list[str] = []
    notes: list[str] = []

    for entry in feedback:
        verdict = str(entry.get("verdict", "")).strip().lower()
        text = str(entry.get("suggestion_text", "")).strip()
        note = str(entry.get("note", "") or "").strip()

        if verdict == "used":
            used += 1
        elif verdict == "edited":
            edited += 1
        elif verdict == "rejected":
            rejected += 1
        else:  # an unknown verdict tells us nothing; do not invent a meaning
            continue

        if text:
            (kept_texts if verdict in KEPT_VERDICTS else rejected_texts).append(text)
        if note:
            notes.append(f'{verdict}: "{_clip(note)}"')

    return FeedbackSignal(
        used=used,
        edited=edited,
        rejected=rejected,
        used_examples=[_clip(t) for t in kept_texts[-MAX_EXAMPLES:]],
        rejected_examples=[_clip(t) for t in rejected_texts[-MAX_EXAMPLES:]],
        notes=notes[-MAX_NOTES:],
        patterns=_compare(kept_texts, rejected_texts),
    )


def feedback_brief(signal: FeedbackSignal) -> str:
    """The prompt section. Empty string when there is nothing honest to say."""
    if not signal.has_signal:
        return ""

    lines = [
        f"- Verdicts so far: {signal.total} judged -- {signal.used} sent as "
        f"written, {signal.edited} sent after editing, {signal.rejected} thrown out."
    ]
    lines.extend(f"- {p}" for p in signal.patterns)

    if signal.notes:
        lines.append("- What he said when he judged them, in his own words:")
        lines.extend(f"  - {n}" for n in signal.notes)

    if signal.rejected_examples:
        lines.append("- Suggestions he threw out. Do not reword these -- avoid them:")
        lines.extend(f'  - "{t}"' for t in signal.rejected_examples)

    if signal.used_examples:
        lines.append("- Suggestions he actually sent. This register landed:")
        lines.extend(f'  - "{t}"' for t in signal.used_examples)

    if signal.total < CONFIDENT_TOTAL:
        lines.append(
            f"- NOTE: {signal.total} verdicts is a small sample. Treat this as a "
            "weak hint, not a rule, and let the conversation in front of you win."
        )

    return "\n".join(lines)
