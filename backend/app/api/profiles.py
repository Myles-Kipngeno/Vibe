"""Style profile, contacts, memories and the data-deletion endpoint."""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException

from ..core.style_profile import learn_from_messages, style_brief
from ..schemas import (
    ContactCreate,
    ContactProfile,
    ContactUpdate,
    Memory,
    MemoryCreate,
    Message,
    StyleProfile,
    StyleProfileUpdate,
)
from ..storage.store import Store
from .deps import get_store

style_router = APIRouter(prefix="/api/style", tags=["style"])
contacts_router = APIRouter(prefix="/api/contacts", tags=["contacts"])
data_router = APIRouter(prefix="/api/data", tags=["data"])


# --- My Style -----------------------------------------------------------------


@style_router.get("", response_model=StyleProfile)
def get_style(store: Store = Depends(get_store)) -> StyleProfile:
    return store.get_style_profile()


@style_router.get("/brief")
def get_style_brief(store: Store = Depends(get_store)) -> dict[str, str]:
    """The exact description handed to the model, so the user can see it."""
    return {"brief": style_brief(store.get_style_profile())}


@style_router.patch("", response_model=StyleProfile)
def update_style(
    payload: StyleProfileUpdate, store: Store = Depends(get_store)
) -> StyleProfile:
    profile = store.get_style_profile()
    for key, value in payload.model_dump(exclude_none=True).items():
        setattr(profile, key, value)
    return store.save_style_profile(profile)


@style_router.post("/learn", response_model=StyleProfile)
def learn_style(
    messages: list[Message], store: Store = Depends(get_store)
) -> StyleProfile:
    """Update the profile from the user's own messages in a conversation.

    Only messages marked `me` are used. This is explicit -- the frontend calls it
    when the user presses "Learn from this conversation", never automatically.
    """
    if not any(m.speaker == "me" for m in messages):
        raise HTTPException(
            status_code=422,
            detail="No messages marked as yours, so there is nothing to learn from.",
        )
    updated = learn_from_messages(store.get_style_profile(), messages)
    return store.save_style_profile(updated)


@style_router.post("/reset", response_model=StyleProfile)
def reset_style(store: Store = Depends(get_store)) -> StyleProfile:
    return store.reset_style_profile()


# --- Contacts -----------------------------------------------------------------


@contacts_router.get("", response_model=list[ContactProfile])
def list_contacts(store: Store = Depends(get_store)) -> list[ContactProfile]:
    return store.list_contacts()


@contacts_router.post("", response_model=ContactProfile, status_code=201)
def create_contact(
    payload: ContactCreate, store: Store = Depends(get_store)
) -> ContactProfile:
    return store.create_contact(payload.name, payload.nickname, payload.notes)


@contacts_router.get("/{contact_id}", response_model=ContactProfile)
def get_contact(contact_id: str, store: Store = Depends(get_store)) -> ContactProfile:
    contact = store.get_contact(contact_id)
    if contact is None:
        raise HTTPException(status_code=404, detail="Contact not found.")
    return contact


@contacts_router.patch("/{contact_id}", response_model=ContactProfile)
def update_contact(
    contact_id: str, payload: ContactUpdate, store: Store = Depends(get_store)
) -> ContactProfile:
    contact = store.update_contact(contact_id, **payload.model_dump(exclude_none=True))
    if contact is None:
        raise HTTPException(status_code=404, detail="Contact not found.")
    return contact


@contacts_router.delete("/{contact_id}", status_code=204)
def delete_contact(contact_id: str, store: Store = Depends(get_store)) -> None:
    if not store.delete_contact(contact_id):
        raise HTTPException(status_code=404, detail="Contact not found.")


@contacts_router.post("/{contact_id}/memories", response_model=Memory, status_code=201)
def add_memory(
    contact_id: str, payload: MemoryCreate, store: Store = Depends(get_store)
) -> Memory:
    memory = store.add_memory(contact_id, payload)
    if memory is None:
        raise HTTPException(status_code=404, detail="Contact not found.")
    return memory


@contacts_router.delete("/{contact_id}/memories/{memory_id}", status_code=204)
def delete_memory(
    contact_id: str, memory_id: str, store: Store = Depends(get_store)
) -> None:
    if not store.delete_memory(contact_id, memory_id):
        raise HTTPException(status_code=404, detail="Memory not found.")


# --- Privacy ------------------------------------------------------------------


@data_router.delete("", status_code=204)
def wipe_everything(store: Store = Depends(get_store)) -> None:
    """Delete the style profile, every contact and every stored memory."""
    store.wipe()
