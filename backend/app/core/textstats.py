"""Small, dependency-free text utilities shared by every detector.

Nothing in here calls an LLM. These are the measurements we can make honestly
and repeatedly, which is what makes the rest of the system testable.
"""

from __future__ import annotations

import re
import unicodedata

from .lexicon import (
    LAUGH_EMOJI,
    LAUGH_TOKENS,
    LOW_EFFORT_REPLIES,
    SHENG_MARKERS,
    SWAHILI_MARKERS,
)

_WORD_RE = re.compile(r"[a-z0-9']+")
_PUNCT_STRIP = re.compile(r"[^\w\s']", flags=re.UNICODE)


def normalize(text: str) -> str:
    """Lowercase, strip accents and most punctuation, collapse whitespace.

    Apostrophes are removed too (``don't`` -> ``dont``) so the phrase lists in
    `lexicon` only need one spelling of each contraction.
    """
    folded = unicodedata.normalize("NFKD", text)
    folded = "".join(ch for ch in folded if not unicodedata.combining(ch))
    folded = folded.lower().replace("'", "").replace("’", "")
    folded = _PUNCT_STRIP.sub(" ", folded)
    return re.sub(r"\s+", " ", folded).strip()


def words(text: str) -> list[str]:
    return _WORD_RE.findall(text.lower().replace("’", "'"))


def is_emoji(ch: str) -> bool:
    """True for pictographic characters, which is close enough for texting."""
    code = ord(ch)
    return (
        0x1F300 <= code <= 0x1FAFF
        or 0x2600 <= code <= 0x27BF
        or code in (0x2764, 0x2665, 0x203C, 0x2049)
        or 0x1F000 <= code <= 0x1F2FF
    )


def emojis(text: str) -> list[str]:
    return [ch for ch in text if is_emoji(ch)]


def sheng_ratio(texts: list[str]) -> float:
    """Share of word tokens that are recognisably Sheng or Kiswahili.

    This is a *marker* ratio, not a true language-ID score: it under-counts
    Sheng sentences built from words we do not list. It is good enough to tell
    "writes mostly English" from "mixes heavily", which is what the generator
    needs, and it never claims more precision than that.
    """
    tokens = [w for t in texts for w in words(t)]
    if not tokens:
        return 0.0
    hits = sum(1 for w in tokens if w in SHENG_MARKERS or w in SWAHILI_MARKERS)
    # Markers are sparse by nature, so scale up and clamp: a message with one
    # marker in eight words already reads as mixed-language.
    return min(1.0, (hits / len(tokens)) * 4.0)


def avg_word_count(texts: list[str]) -> float:
    if not texts:
        return 0.0
    return sum(len(words(t)) for t in texts) / len(texts)


def emoji_frequency(texts: list[str]) -> float:
    if not texts:
        return 0.0
    return sum(1 for t in texts if emojis(t)) / len(texts)


def laugh_frequency(texts: list[str]) -> float:
    if not texts:
        return 0.0
    hits = 0
    for t in texts:
        norm = normalize(t)
        if any(tok in norm for tok in LAUGH_TOKENS) or any(e in t for e in LAUGH_EMOJI):
            hits += 1
    return hits / len(texts)


def question_frequency(texts: list[str]) -> float:
    if not texts:
        return 0.0
    return sum(1 for t in texts if "?" in t) / len(texts)


def is_low_effort(text: str) -> bool:
    """A reply that carries almost no new information."""
    norm = normalize(text)
    stripped = "".join(ch for ch in text if not is_emoji(ch)).strip()
    if not normalize(stripped) and emojis(text):
        return True  # emoji-only reply
    tokens = norm.split()
    if not tokens:
        return True
    if len(tokens) > 3:
        return False
    return all(t in LOW_EFFORT_REPLIES for t in tokens)


def contains_any(text: str, phrases: tuple[str, ...]) -> list[str]:
    """Return every phrase from `phrases` present in the normalised `text`."""
    norm = normalize(text)
    padded = f" {norm} "
    found = []
    for phrase in phrases:
        needle = normalize(phrase)
        if not needle:
            continue
        # Single words must match as whole words; longer phrases as substrings.
        if " " in needle:
            if needle in norm:
                found.append(phrase)
        elif f" {needle} " in padded:
            found.append(phrase)
    return found


def top_expressions(texts: list[str], limit: int = 8) -> list[str]:
    """Most repeated short phrases, used to seed "expressions you actually use"."""
    counts: dict[str, int] = {}
    for text in texts:
        toks = words(normalize(text))
        for n in (1, 2):
            for i in range(len(toks) - n + 1):
                gram = " ".join(toks[i : i + n])
                if len(gram) < 3:
                    continue
                counts[gram] = counts.get(gram, 0) + 1
    ranked = sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))
    return [gram for gram, count in ranked if count >= 2][:limit]
