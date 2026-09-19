"""Turns a pasted conversation into labelled messages.

People paste conversations in whatever shape their phone gives them, so we try
the common formats in order and tell the caller which one matched. Anything we
could not place is returned in `unparsed_lines` rather than silently guessed --
mislabelling who said what would poison every downstream judgement.
"""

from __future__ import annotations

import re
from datetime import datetime

from ..schemas import Message, ParseResponse

# "[12/09/2025, 21:04] Ann: text"  and  "12/09/2025, 21:04 - Ann: text"
_WHATSAPP_RE = re.compile(
    r"^\[?(?P<date>\d{1,2}[/.-]\d{1,2}[/.-]\d{2,4})[,]?\s+"
    r"(?P<time>\d{1,2}:\d{2}(?::\d{2})?)\s*(?:[APap][Mm])?\]?\s*[-–]?\s*"
    r"(?P<who>[^:]{1,40}):\s*(?P<text>.*)$"
)

# "Me: text" / "Her: text" / "Me - text"
_LABEL_RE = re.compile(r"^(?P<who>[^:\-–]{1,24})\s*[:\-–]\s*(?P<text>.+)$")

_DATE_FORMATS = (
    "%d/%m/%Y %H:%M", "%d/%m/%y %H:%M", "%m/%d/%Y %H:%M", "%m/%d/%y %H:%M",
    "%d.%m.%Y %H:%M", "%Y-%m-%d %H:%M", "%d/%m/%Y %H:%M:%S", "%d/%m/%y %H:%M:%S",
)

_ME_ALIASES = {"me", "i", "myself", "you", "mimi", "self"}


def _parse_dt(date: str, time: str) -> datetime | None:
    clean_time = time if time.count(":") == 2 else time
    for fmt in _DATE_FORMATS:
        try:
            return datetime.strptime(f"{date} {clean_time}", fmt)
        except ValueError:
            continue
    return None


def _match_speaker(who: str, me_label: str, them_label: str) -> str | None:
    key = who.strip().lower()
    if not key:
        return None
    if key == me_label.strip().lower() or key in _ME_ALIASES:
        return "me"
    if key == them_label.strip().lower():
        return "them"
    return None


def parse_conversation(
    raw_text: str, me_label: str = "Me", them_label: str = "Them"
) -> ParseResponse:
    lines = [ln.rstrip() for ln in raw_text.splitlines()]
    lines = [ln for ln in lines if ln.strip()]
    if not lines:
        return ParseResponse(messages=[], unparsed_lines=[], detected_format="empty")

    # --- 1. WhatsApp export -------------------------------------------------
    wa_hits = [ln for ln in lines if _WHATSAPP_RE.match(ln)]
    if len(wa_hits) >= max(2, len(lines) // 2):
        names: list[str] = []
        for ln in wa_hits:
            m = _WHATSAPP_RE.match(ln)
            assert m
            who = m.group("who").strip()
            if who not in names:
                names.append(who)
        # In a WhatsApp export the user is whichever name matches their label;
        # if neither matches we fall back to "first speaker is the other person",
        # which the UI lets them flip.
        me_name = next(
            (n for n in names if n.lower() == me_label.strip().lower()),
            names[1] if len(names) > 1 else None,
        )
        messages: list[Message] = []
        unparsed: list[str] = []
        for ln in lines:
            m = _WHATSAPP_RE.match(ln)
            if not m:
                if messages:  # continuation of a multi-line message
                    messages[-1].text += "\n" + ln.strip()
                else:
                    unparsed.append(ln)
                continue
            who = m.group("who").strip()
            speaker = "me" if me_name and who == me_name else "them"
            messages.append(
                Message(
                    speaker=speaker,  # type: ignore[arg-type]
                    text=m.group("text").strip(),
                    sent_at=_parse_dt(m.group("date"), m.group("time")),
                )
            )
        return ParseResponse(
            messages=[m for m in messages if m.text],
            unparsed_lines=unparsed,
            detected_format="whatsapp_export",
        )

    # --- 2. Explicit labels ("Me:" / "Her:") --------------------------------
    labelled = 0
    for ln in lines:
        m = _LABEL_RE.match(ln)
        if m and _match_speaker(m.group("who"), me_label, them_label):
            labelled += 1
    if labelled >= max(2, len(lines) // 2):
        messages = []
        unparsed = []
        for ln in lines:
            m = _LABEL_RE.match(ln)
            speaker = _match_speaker(m.group("who"), me_label, them_label) if m else None
            if m and speaker:
                messages.append(
                    Message(speaker=speaker, text=m.group("text").strip())  # type: ignore[arg-type]
                )
            elif messages:
                messages[-1].text += "\n" + ln.strip()
            else:
                unparsed.append(ln)
        return ParseResponse(
            messages=[m for m in messages if m.text],
            unparsed_lines=unparsed,
            detected_format="labelled",
        )

    # --- 3. Fallback: alternate, starting with the other person --------------
    # This is a guess, and the response says so via `detected_format` -- the UI
    # shows a per-message toggle so the user can correct it in one click.
    messages = [
        Message(speaker="them" if i % 2 == 0 else "me", text=ln.strip())
        for i, ln in enumerate(lines)
    ]
    return ParseResponse(
        messages=messages, unparsed_lines=[], detected_format="alternating_guess"
    )
