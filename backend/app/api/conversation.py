"""Conversation endpoints: parse, analyse, suggest, feedback."""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException

from ..core import generator
from ..core.lexicon import CONVERSATION_GOALS
from ..core.parser import parse_conversation
from ..core.style_profile import observe_their_style
from ..providers.base import LLMProvider, ProviderError
from ..schemas import (
    AnalyzeRequest,
    AnalyzeResponse,
    FeedbackCreate,
    ParseRequest,
    ParseResponse,
    SuggestRequest,
    SuggestResponse,
)
from ..storage.store import Store
from .deps import get_provider, get_store
from .rate_limit import limit_generation

router = APIRouter(prefix="/api/conversation", tags=["conversation"])


@router.get("/goals")
def list_goals() -> dict[str, str]:
    return CONVERSATION_GOALS


@router.post("/parse", response_model=ParseResponse)
def parse(payload: ParseRequest) -> ParseResponse:
    if not payload.raw_text.strip():
        raise HTTPException(status_code=422, detail="Nothing to parse.")
    return parse_conversation(payload.raw_text, payload.me_label, payload.them_label)


def _context_keys(store: Store, contact_id: str | None) -> set[str]:
    """Dedupe keys we already have answers for, so we never re-ask."""
    return store.memory_keys(contact_id)


@router.post("/analyze", response_model=AnalyzeResponse)
def analyze(
    payload: AnalyzeRequest,
    store: Store = Depends(get_store),
    provider: LLMProvider = Depends(get_provider),
) -> AnalyzeResponse:
    contact = store.get_contact(payload.contact_id) if payload.contact_id else None
    profile = store.get_style_profile()

    analysis = generator.analyse(
        messages=payload.messages,
        known_keys=_context_keys(store, payload.contact_id),
        contact_name=contact.name if contact else None,
        local_time=payload.local_time,
        max_tone=profile.max_tone,
    )

    if contact:
        their = observe_their_style(payload.messages)
        if their:
            store.observe_contact_style(
                contact.id, their.get("sheng_ratio", 0.0), their.get("avg_words", 0.0)
            )
        # A stated boundary is remembered for this contact, not just this session.
        for quote in analysis.boundary_quotes:
            store.add_boundary(contact.id, quote)

    return AnalyzeResponse(
        analysis=analysis, provider=provider.name, is_mock=provider.is_mock
    )


@router.post(
    "/suggest",
    response_model=SuggestResponse,
    dependencies=[Depends(limit_generation)],
)
def suggest(
    payload: SuggestRequest,
    store: Store = Depends(get_store),
    provider: LLMProvider = Depends(get_provider),
) -> SuggestResponse:
    if payload.goal not in CONVERSATION_GOALS:
        raise HTTPException(status_code=422, detail=f"Unknown goal '{payload.goal}'.")

    contact = store.get_contact(payload.contact_id) if payload.contact_id else None
    profile = store.get_style_profile()

    # Context the user stored earlier for this contact counts as supplied, so an
    # already-answered question never blocks generation again.
    supplied = dict(payload.supplied_context)
    for line in store.memory_lines(payload.contact_id):
        key, _, value = line.partition(" -> ")
        supplied.setdefault(key, value)

    analysis = generator.analyse(
        messages=payload.messages,
        known_keys=_context_keys(store, payload.contact_id),
        contact_name=contact.name if contact else None,
        local_time=payload.local_time,
        max_tone=profile.max_tone,
    )

    try:
        return generator.generate(
            provider=provider,
            messages=payload.messages,
            analysis=analysis,
            goal=payload.goal,
            profile=profile,
            supplied_context=supplied,
            memories=store.memory_lines(payload.contact_id),
            avoid=payload.avoid,
            action=payload.action,
            contact_name=(contact.nickname or contact.name) if contact else None,
            feedback=store.list_feedback(),
            examples=store.list_examples(),
        )
    except ProviderError as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc


@router.post("/feedback", status_code=204)
def feedback(payload: FeedbackCreate, store: Store = Depends(get_store)) -> None:
    store.add_feedback(payload)
