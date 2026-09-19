"""Prompt construction for reply generation.

Prompts are built here and nowhere else, so the rules the assistant must follow
live in one auditable place. The hard rules -- never invent personal details,
never push past a boundary, never write explicit content -- are repeated in the
system prompt *and* enforced in code before and after generation, because a
prompt instruction alone is not a control.
"""

from __future__ import annotations

from ..schemas import Analysis, Message
from .lexicon import CONVERSATION_GOALS
from .style_profile import mirror_note, style_brief

SYSTEM_PROMPT = """You help one specific person write replies in an ongoing \
text conversation. You are not a pickup-line generator and you are not writing \
as yourself -- you are drafting messages in his voice, which he will read, edit \
and decide whether to send.

Context: he is in Kenya and texts in a natural Sheng + English mix. Match the \
mix described in his style profile. Keep Kiswahili light unless he uses it. \
Never force slang he would not use, and never stack emojis.

Hard rules:
1. Never invent a personal detail. If a person, event or shared memory is \
mentioned and you were not told what it is, do not guess, do not fill it in \
vaguely, and do not write around it as though you know. The system asks him for \
missing context before calling you, so anything you were not given is genuinely \
unknown.
2. Treat any reading of her mood, interest or intention as a guess, and say so \
in the rationale rather than stating it as fact.
3. If she has set a boundary, said she is unavailable, or asked to stop, do not \
generate anything designed to change her mind. Help him accept it gracefully.
4. Never write sexually explicit content. Suggestive banter between adults is \
fine when it is clearly mutual and he has enabled that tone, but stop escalating \
the moment there is any signal of discomfort.
5. Never promise an outcome. You are not making her like him.
6. Write messages a real person would send: no greeting-card phrasing, no \
therapy-speak, no "as an AI".

Return between 2 and 3 clearly different options. Different means a different \
angle, not the same sentence reworded. Each one needs a short honest rationale \
in plain language."""


def _format_transcript(messages: list[Message], limit: int = 30) -> str:
    recent = messages[-limit:]
    lines = []
    for m in recent:
        who = "HIM" if m.speaker == "me" else "HER"
        stamp = f" [{m.sent_at:%a %H:%M}]" if m.sent_at else ""
        lines.append(f"{who}{stamp}: {m.text}")
    return "\n".join(lines) if lines else "(no messages yet)"


def build_user_prompt(
    messages: list[Message],
    analysis: Analysis,
    goal: str,
    style_text: str,
    their_style: dict[str, float],
    supplied_context: dict[str, str],
    memories: list[str],
    avoid: list[str],
    action: str | None,
    goodnight: bool,
) -> str:
    goal_label = CONVERSATION_GOALS.get(goal, goal)

    sections: list[str] = []
    sections.append("## The conversation so far\n" + _format_transcript(messages))

    sections.append(
        "## What the system measured\n"
        f"- Topic (rough): {analysis.topic}\n"
        f"- Energy: {analysis.tone} (confidence: {analysis.tone_confidence})\n"
        f"- Her engagement: {analysis.engagement} (confidence: {analysis.engagement_confidence})\n"
        f"- Thread state: {analysis.flow_state.replace('_', ' ')}\n"
        f"- Recommendation: {analysis.recommendation} -- {analysis.recommendation_reason}"
    )

    if analysis.open_questions:
        unanswered = "\n".join(
            f"- {q.text} (asked by {'him' if q.asked_by == 'me' else 'her'})"
            for q in analysis.open_questions
        )
        sections.append("## Questions still hanging\n" + unanswered)

    sections.append("## His texting style\n" + style_text)

    note = mirror_note(their_style)
    if note:
        sections.append("## How she texts\n" + note)

    if memories:
        sections.append(
            "## What he has told you about her before\n"
            + "\n".join(f"- {m}" for m in memories)
            + "\nUse these only as background. Do not recite them back at her."
        )

    if supplied_context:
        supplied = "\n".join(f"- {k}: {v}" for k, v in supplied_context.items())
        sections.append(
            "## Context he just supplied (this is the only reliable source for these)\n"
            + supplied
        )

    if analysis.uncertainty_notes:
        sections.append(
            "## What is genuinely unknown\n"
            + "\n".join(f"- {n}" for n in analysis.uncertainty_notes)
        )

    if avoid:
        sections.append(
            "## Already suggested, do not repeat or lightly reword\n"
            + "\n".join(f"- {a}" for a in avoid)
        )

    task = f"## Your task\nWrite replies for him. His goal: {goal_label}."
    if action == "stop":
        task += (
            "\nHe chose to END this conversation. Write warm, final-feeling messages "
            "that close it without asking a new question and without any hook to "
            "reopen it."
        )
    elif action == "continue":
        task += (
            "\nHe chose to CONTINUE. Give him one genuinely interesting thing to send "
            "-- a real reaction or a question that is easy and enjoyable to answer. "
            "Do not send two topics at once."
        )
    if goodnight:
        task += (
            "\nThe conversation is winding down at night. A goodnight message is the "
            "natural move. Reflect how this conversation actually went -- if it was "
            "flat, do not write as though it was great."
        )
    if analysis.boundary_detected:
        task += (
            "\nIMPORTANT: she signalled a boundary. Only produce respectful "
            "acknowledgements. Nothing persuasive."
        )
    sections.append(task)

    return "\n\n".join(sections)
