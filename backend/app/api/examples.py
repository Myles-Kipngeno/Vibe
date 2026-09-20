"""The curated example library (Phase 3).

An example is a reference card he wrote about a conversation of his own, not a
transcript of one. That distinction is enforced here in the only way an API
can: the route refuses a submission that has no situation to describe, and the
schema has no field for her messages, so there is nowhere to put them.
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException

from ..schemas import ConversationExample, ConversationExampleCreate
from ..storage.store import Store
from .deps import get_store

router = APIRouter(prefix="/api/examples", tags=["examples"])


@router.get("", response_model=list[ConversationExample])
def list_examples(store: Store = Depends(get_store)) -> list[ConversationExample]:
    return store.list_examples()


@router.post("", response_model=ConversationExample, status_code=201)
def create_example(
    payload: ConversationExampleCreate, store: Store = Depends(get_store)
) -> ConversationExample:
    if not payload.situation.strip():
        raise HTTPException(
            status_code=422,
            detail="An example needs a situation, or it cannot be matched to one.",
        )
    return store.add_example(payload)


@router.delete("/{example_id}", status_code=204)
def delete_example(example_id: str, store: Store = Depends(get_store)) -> None:
    if not store.delete_example(example_id):
        raise HTTPException(status_code=404, detail="No such example.")
