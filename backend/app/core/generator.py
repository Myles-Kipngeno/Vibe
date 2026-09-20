"""Turns an analysed conversation into reply suggestions.

This module owns the gates. A prompt can ask a model to behave; only code can
guarantee it. So before we ever call a provider we check:

* unresolved personal-context alerts -> refuse and ask the user instead
* a stated boundary -> only acknowledgement options, never persuasion
* the user chose "Wait" -> no message at all, just guidance

and after generation we strip anything that slipped past.
"""

from __future__ import annotations

import hashlib
from datetime import datetime

from ..providers.base import GenerationResult, LLMProvider
from ..schemas import (
    Analysis,
    ContextAlert,
    Message,
    StyleProfile,
    Suggestion,
    SuggestResponse,
)
from . import feedback_signal, personal_context, rhythm
from .prompts import SYSTEM_PROMPT, build_user_prompt
from .style_profile import observe_their_style, style_brief


def _suggestion_id(text: str) -> str:
    return "sug_" + hashlib.sha1(text.encode("utf-8")).hexdigest()[:10]


def should_offer_goodnight(messages: list[Message], analysis: Analysis) -> bool:
    signals = rhythm.wind_down_context(messages, analysis.time_of_day)
    return signals["someone_signing_off"] or (
        signals["is_night"] and analysis.flow_state in ("winding_down", "slowing_down")
    )


def should_offer_morning(messages: list[Message], analysis: Analysis) -> bool:
    """A next-morning follow-up only makes sense if last night actually ended well.

    We never suggest a second unanswered follow-up: if he already sent the last
    message and got nothing back, another one is not a natural continuation.
    """
    signals = rhythm.wind_down_context(messages, analysis.time_of_day)
    if not signals["is_morning"]:
        return False
    if signals["she_went_quiet"]:
        return False
    return signals["was_warm"] and not analysis.boundary_detected


def generate(
    provider: LLMProvider,
    messages: list[Message],
    analysis: Analysis,
    goal: str,
    profile: StyleProfile,
    supplied_context: dict[str, str],
    memories: list[str],
    avoid: list[str],
    action: str | None,
    contact_name: str | None,
    feedback: list[dict] | None = None,
) -> SuggestResponse:
    is_mock = provider.is_mock
    provider_name = provider.name

    # --- Gate 1: the user chose to wait. There is nothing to write. ---------
    if action == "wait":
        return SuggestResponse(
            suggestions=[],
            guidance=(
                "Nothing to send right now. "
                + analysis.recommendation_reason
                + " Come back when she replies, or when you actually have something "
                "you want to tell her."
            ),
            provider=provider_name,
            is_mock=is_mock,
        )

    # --- Gate 2: a stated boundary. Only graceful exits from here. ----------
    if analysis.boundary_detected and action != "stop":
        return SuggestResponse(
            suggestions=[],
            blocked=True,
            blocked_reason=(
                "She said something that reads as a boundary, so I will not write "
                "anything meant to keep this going. If you want, I can help you "
                "close the conversation respectfully -- choose Stop."
            ),
            guidance=analysis.recommendation_reason,
            provider=provider_name,
            is_mock=is_mock,
        )

    # --- Gate 3: missing personal context. Ask, do not guess. ---------------
    outstanding = personal_context.unresolved(analysis.alerts, supplied_context)
    if outstanding:
        return SuggestResponse(
            suggestions=[],
            blocked=True,
            blocked_reason=(
                "She referred to something only you two know about. I am not going to "
                "invent it -- tell me what it is and I will write the reply."
            ),
            unresolved_alerts=outstanding,
            provider=provider_name,
            is_mock=is_mock,
        )

    goodnight = should_offer_goodnight(messages, analysis)
    if should_offer_morning(messages, analysis) and goal == "keep_flowing":
        goal = "next_day"

    signal = feedback_signal.summarize(feedback or [])

    user_prompt = build_user_prompt(
        messages=messages,
        analysis=analysis,
        goal=goal,
        style_text=style_brief(profile),
        their_style=observe_their_style(messages),
        supplied_context=supplied_context,
        memories=memories,
        avoid=avoid,
        feedback_text=feedback_signal.feedback_brief(signal),
        action=action,
        goodnight=goodnight,
    )

    result = provider.generate(
        SYSTEM_PROMPT,
        user_prompt,
        GenerationResult,
        context={
            "goal": goal,
            "action": action,
            "goodnight": goodnight,
            "contact_name": contact_name,
            "sheng_ratio": profile.sheng_ratio,
            "avoid": avoid,
            "supplied_context": supplied_context,
            "feedback": signal,
        },
    )

    seen = {a.strip().lower() for a in avoid}
    suggestions: list[Suggestion] = []
    for item in result.suggestions:
        text = item.text.strip()
        if not text or text.lower() in seen:
            continue
        seen.add(text.lower())
        suggestions.append(
            Suggestion(
                id=_suggestion_id(text),
                text=text,
                rationale=item.rationale.strip(),
                tone=analysis.tone,
                approach=item.approach.strip(),
            )
        )

    guidance = analysis.recommendation_reason
    if goodnight:
        guidance = (
            "This is winding down for the night, so these are sign-offs rather than "
            "new topics. " + guidance
        )
    if is_mock:
        guidance = (
            "Offline mode: these are fixed templates, not model output. "
            "Add ANTHROPIC_API_KEY to backend/.env for real suggestions. "
        ) + guidance

    return SuggestResponse(
        suggestions=suggestions[:3],
        guidance=guidance,
        unresolved_alerts=[],
        provider=provider_name,
        is_mock=is_mock,
    )


def analyse(
    messages: list[Message],
    known_keys: set[str],
    contact_name: str | None,
    local_time: datetime | None,
    max_tone: str,
) -> Analysis:
    alerts: list[ContextAlert] = personal_context.detect_alerts(
        messages, known_context_keys=known_keys, contact_name=contact_name
    )
    return rhythm.build_analysis(messages, alerts, local_time, max_tone=max_tone)
