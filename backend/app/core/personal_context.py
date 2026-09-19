"""Detects the moments where the assistant must stop and ask the user.

The product rule this module enforces: **never invent a personal detail**. When
the other person refers to a person, event or fact that only the two of them
know, we raise a `ContextAlert` instead of guessing, and the generator refuses
to write about that subject until the user has filled the gap.

Everything here is deterministic. That matters for three reasons: it works with
no API key, it is cheap enough to run on every keystroke-sized request, and it
can be unit-tested against realistic conversations.
"""

from __future__ import annotations

import hashlib
import re

from ..schemas import ContextAlert, Message
from . import lexicon as lex
from .textstats import contains_any, emojis, normalize, words

# Distress vocabulary for the emotional-shift alert.
_DISTRESS_PHRASES: tuple[str, ...] = (
    "im not okay", "im not ok", "not doing well", "im stressed", "so stressed",
    "im depressed", "been crying", "i was crying", "im tired of", "cant anymore",
    "i feel alone", "i feel so", "im scared", "im worried", "lost my",
    "passed away", "funeral", "msiba", "hospital", "im sick", "niko sick",
    "nimechoka sana", "maisha ni ngumu", "sina pesa", "nimefired", "lost my job",
    "we broke up", "tumeachana", "he hit me", "im hurt",
)

# Questions only the user can answer about themselves.
_PERSONAL_QUESTION_PATTERNS: tuple[tuple[str, str], ...] = (
    (r"\bhow old are (you|u)\b", "your age"),
    (r"\bwhere do (you|u) (stay|live)\b", "where you live"),
    (r"\bunaishi wapi\b", "where you live"),
    (r"\buko wapi\b", "where you are right now"),
    (r"\bwhat do (you|u) do\b", "what you do for a living"),
    (r"\bunafanya (kazi )?(nini|gani)\b", "what you do for a living"),
    (r"\bare (you|u) seeing (anyone|someone)\b", "whether you are seeing anyone"),
    (r"\b(uko|una) na (mtu|dame|chali|mpenzi)\b", "whether you are seeing anyone"),
    (r"\bwhat(s| is) your (name|number)\b", "the detail she asked for"),
    (r"\bsend me your (number|contact)\b", "whether to share your number"),
)

# Kiswahili/Sheng verb prefixes. A word starting with one of these is a
# conjugated verb ("ulienda", "nimefika"), never somebody's name.
_VERB_PREFIXES: tuple[str, ...] = (
    "uli", "ali", "nili", "tuli", "wali", "mli", "nime", "ume", "ame", "tume",
    "wame", "mme", "nina", "nika", "unaf", "unak", "anaf", "hawa", "sita",
    "hatu", "nita", "utaf", "atak", "tuta", "ninap", "unap",
)

_NAME_PRECEDERS: frozenset[str] = frozenset(
    {"na", "with", "kwa", "tell", "told", "ask", "asked", "saw", "met", "and", "za", "ya"}
)


def _alert_id(kind: str, dedupe_key: str) -> str:
    digest = hashlib.sha1(f"{kind}:{dedupe_key}".encode()).hexdigest()[:10]
    return f"alert_{digest}"


def _make(
    kind: str,
    priority: str,
    title: str,
    quote: str,
    question: str,
    dedupe_key: str,
    subject: str | None = None,
) -> ContextAlert:
    return ContextAlert(
        id=_alert_id(kind, dedupe_key),
        kind=kind,  # type: ignore[arg-type]
        priority=priority,  # type: ignore[arg-type]
        title=title,
        quote=quote.strip(),
        question=question,
        subject=subject,
        dedupe_key=dedupe_key,
    )


def focus_messages(messages: list[Message]) -> list[Message]:
    """The messages we are actually replying to.

    Those are the other person's messages since the user last wrote. If the user
    wrote last (or never), we still look at their most recent message so the
    workspace is useful on a freshly pasted conversation.
    """
    last_me = max(
        (i for i, m in enumerate(messages) if m.speaker == "me"), default=-1
    )
    pending = [m for m in messages[last_me + 1 :] if m.speaker == "them"]
    if pending:
        return pending
    theirs = [m for m in messages if m.speaker == "them"]
    return theirs[-1:] if theirs else []


def candidate_names(text: str, known: set[str]) -> list[str]:
    """Proper nouns that look like people the assistant has never heard of.

    Texting capitalisation is unreliable, so we use two weak signals together:
    a capitalised token that does not start a sentence, or any token following a
    preposition that normally introduces a person ("na Randy", "with Brian").

    Sentence position matters: in "Nimechill tu. Ulienda town na Randy?" the word
    "Ulienda" is only capitalised because it opens a sentence, and treating it as
    a person would mean interrupting the user to ask who "Ulienda" is.
    """
    found: list[str] = []
    tokens = list(re.finditer(r"[A-Za-z][A-Za-z'\-]+", text))
    for idx, match in enumerate(tokens):
        token = match.group(0)
        low = token.lower().strip("'-")
        if len(low) < 3 or low in known:
            continue
        if (
            low in lex.NAME_STOPWORDS
            or low in lex.KNOWN_PLACES
            or low in lex.SHENG_MARKERS
            or low in lex.SWAHILI_MARKERS
            or low in lex.RELATIONSHIP_NOUNS
            or low in lex.LOW_EFFORT_REPLIES
            or low.startswith(_VERB_PREFIXES)
        ):
            continue

        preceding = text[: match.start()].rstrip()
        starts_sentence = not preceding or preceding[-1] in ".!?\n"
        prev_word = tokens[idx - 1].group(0).lower() if idx else ""

        capitalised_midway = token[0].isupper() and not starts_sentence and not token.isupper()
        after_preceder = prev_word in _NAME_PRECEDERS and token[0].isupper()
        if capitalised_midway or after_preceder:
            name = token.strip("'-").capitalize()
            if name not in found:
                found.append(name)
    return found


def detect_alerts(
    messages: list[Message],
    known_context_keys: set[str] | None = None,
    contact_name: str | None = None,
) -> list[ContextAlert]:
    """Return every alert raised by the messages we are replying to.

    `known_context_keys` are dedupe keys the user has already answered (stored
    memories or context supplied in this session); those alerts are suppressed
    so we never ask the same question twice.
    """
    known_context_keys = known_context_keys or set()
    known_names = {w.lower() for w in words(contact_name or "")}
    # Names the user themselves introduced are already explained by the
    # transcript, so they are not gaps.
    for m in messages:
        if m.speaker == "me":
            known_names.update(n.lower() for n in candidate_names(m.text, set()))

    alerts: list[ContextAlert] = []
    seen: set[str] = set()

    def push(alert: ContextAlert) -> None:
        if alert.dedupe_key in known_context_keys or alert.dedupe_key in seen:
            return
        seen.add(alert.dedupe_key)
        alerts.append(alert)

    for msg in focus_messages(messages):
        text = msg.text
        norm = normalize(text)
        has_laughter = bool(
            contains_any(text, tuple(lex.LAUGH_TOKENS))
        ) or any(e in text for e in lex.LAUGH_EMOJI)

        # 1. Boundary signals come first: they change what we are allowed to do.
        hard = contains_any(text, lex.HARD_BOUNDARY_PHRASES)
        if hard:
            push(
                _make(
                    "boundary_signal",
                    "high",
                    "She said something you should read yourself",
                    text,
                    "She signalled a boundary. Do you want help replying respectfully, "
                    "or would you rather handle this one yourself?",
                    f"boundary:{normalize(hard[0])}",
                    subject=hard[0],
                )
            )

        # 2. Picture requests always go to the user. We never send photos.
        if contains_any(text, lex.PICTURE_REQUEST_PHRASES):
            push(
                _make(
                    "picture_request",
                    "high",
                    "She asked for a picture",
                    text,
                    "She asked for a pic. Do you want to send one yourself, or should "
                    "I draft a playful reply instead?",
                    "picture_request",
                )
            )

        # 3. Emotional shift: something heavy just landed.
        distress = contains_any(text, _DISTRESS_PHRASES)
        if distress:
            push(
                _make(
                    "emotional_shift",
                    "high",
                    "The mood just changed",
                    text,
                    f"She mentioned something serious ({distress[0]}). What do you know "
                    "about this so I do not reply the wrong way?",
                    f"emotional:{normalize(distress[0])}",
                    subject=distress[0],
                )
            )

        # 4-6. Missing personal context. One message gets at most ONE of these:
        # a named person, a relative she asked about, or a reference to a shared
        # event. Asking three questions about one sentence is noise, not care.
        gaps: list[tuple[str, str, str, str]] = []  # (key, kind, subject, question)

        for name in candidate_names(text, known_names):
            gaps.append(
                (
                    f"person:{name.lower()}",
                    "personal_context",
                    name,
                    f"She mentioned {name}. Who is that to you?",
                )
            )

        if "?" in text or norm.startswith(("did", "how", "what", "uli", "ali", "ume")):
            for noun in lex.RELATIONSHIP_NOUNS:
                if f" {noun} " in f" {norm} ":
                    gaps.append(
                        (
                            f"relation:{noun}",
                            "personal_context",
                            noun,
                            f"She asked about your {noun}. What is going on there?",
                        )
                    )
                    break

        shared = contains_any(text, lex.SHARED_EVENT_PHRASES)
        if shared:
            phrase = shared[0]
            gaps.append(
                (
                    f"event:{normalize(phrase)}",
                    "inside_joke" if has_laughter else "personal_context",
                    phrase,
                    f'She referred to something between you two ("{phrase}"). '
                    "What should I know?",
                )
            )

        named = [g for g in gaps if not g[0].startswith("event:")]
        if named:
            gaps = named

        outstanding = [g for g in gaps if g[0] not in known_context_keys]
        if outstanding:
            key, kind, subject, question = outstanding[0]
            if len(outstanding) > 1:
                extras = ", ".join(g[2] for g in outstanding[1:])
                question += f" (This also touches on: {extras}.)"
            push(
                _make(
                    kind,
                    "high" if kind == "inside_joke" or len(outstanding) > 1 else "normal",
                    "There is a joke here I am missing"
                    if kind == "inside_joke"
                    else "I need a little context",
                    text,
                    question,
                    key,
                    subject=subject,
                )
            )

        # 7. Direct questions about the user that only the user can answer.
        for pattern, label in _PERSONAL_QUESTION_PATTERNS:
            if re.search(pattern, norm):
                push(
                    _make(
                        "personal_question",
                        "normal",
                        "She asked something personal",
                        text,
                        f"She asked about {label}. What should I say?",
                        f"personal:{label}",
                        subject=label,
                    )
                )
                break

        # 8. Genuinely ambiguous replies where a confident guess would be awkward.
        ambiguous = contains_any(text, lex.AMBIGUOUS_SIGNAL_PHRASES)
        if ambiguous and not hard and len(words(text)) <= 6:
            push(
                _make(
                    "ambiguous_message",
                    "low",
                    "This one could go either way",
                    text,
                    f'"{text.strip()}" could be a soft no or just a busy moment. '
                    "How did it read to you?",
                    f"ambiguous:{normalize(ambiguous[0])}",
                    subject=ambiguous[0],
                )
            )

        # 9. An emoji-only reply after a real question is worth flagging quietly.
        if emojis(text) and not normalize(text) and len(messages) > 2:
            push(
                _make(
                    "ambiguous_message",
                    "low",
                    "She replied with just an emoji",
                    text,
                    "Emoji-only reply. Do you read that as playful or as losing "
                    "interest?",
                    "ambiguous:emoji_only",
                )
            )

    priority_rank = {"high": 0, "normal": 1, "low": 2}
    alerts.sort(key=lambda a: priority_rank[a.priority])
    return alerts


def unresolved(alerts: list[ContextAlert], supplied: dict[str, str]) -> list[ContextAlert]:
    """Alerts that still block confident generation.

    Low-priority alerts are informational: they never block a reply. A picture
    request is a decision for the user, not a missing fact, so it does not block
    either -- but it does change what we suggest.
    """
    return [
        a
        for a in alerts
        if a.priority != "low"
        and a.kind in ("personal_context", "inside_joke", "personal_question")
        and not supplied.get(a.dedupe_key)
    ]
