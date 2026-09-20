"""Small, dependency-free text utilities shared by every detector.

Nothing in here calls an LLM. These are the measurements we can make honestly
and repeatedly, which is what makes the rest of the system testable.
"""

from __future__ import annotations

import re
import unicodedata

from .lexicon import (
    ENGLISH_VERB_LOOKALIKES,
    LAUGH_EMOJI,
    LAUGH_TOKENS,
    LOW_EFFORT_REPLIES,
    SHENG_MARKERS,
    SWAHILI_MARKERS,
    VERB_SUBJECT_PREFIXES,
    VERB_TENSE_MARKERS,
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


_VERB_RE = re.compile(
    "^(?:"
    + "|".join(sorted(VERB_SUBJECT_PREFIXES, key=len, reverse=True))
    + ")(?:"
    + "|".join(sorted(VERB_TENSE_MARKERS, key=len, reverse=True))
    + ")[a-z]{3,}$"
)


def looks_like_bantu_verb(word: str) -> bool:
    """True when a word has the shape of a conjugated Sheng/Kiswahili verb.

    Recognising the *shape* rather than the word is what lets the ratio see
    "nilienda", "tutaonana" and "anakuja" without anyone having listed them.
    It is a shape test and nothing more: it cannot tell a real stem from a
    plausible one, so it is deliberately conservative -- it demands a stem of
    at least three letters, which puts the shortest possible match at six
    characters, and keeps an explicit stop-list for the English words that
    decompose the same way.
    """
    word = word.strip().lower()
    if word in ENGLISH_VERB_LOOKALIKES:
        return False
    return bool(_VERB_RE.match(word))


def sheng_ratio(texts: list[str]) -> float:
    """Share of word tokens that are recognisably Sheng or Kiswahili.

    This is a *marker* ratio, not a true language-ID score, and it still
    under-counts Sheng built from words nobody has listed. It is good enough to
    tell "writes mostly English" from "mixes heavily", which is what the
    generator needs, and it claims nothing beyond that.

    A token counts if it is a listed marker or has the shape of a conjugated
    verb, which is what lets "Nilienda town jana" register at all -- as a word
    list alone it scored zero, because no list can hold every conjugation.

    The small scale-up is the honest remainder of that under-counting. It used
    to be x4, from when only sparse markers counted; with everyday vocabulary
    and verb shapes recognised it saturated instead, calling one Swahili verb
    in a three-word message a fully Sheng conversation.
    """
    tokens = [w for t in texts for w in words(t)]
    if not tokens:
        return 0.0
    hits = sum(
        1
        for w in tokens
        if w in SHENG_MARKERS or w in SWAHILI_MARKERS or looks_like_bantu_verb(w)
    )
    return min(1.0, (hits / len(tokens)) * 1.5)


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
