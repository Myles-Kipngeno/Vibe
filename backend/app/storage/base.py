"""The storage contract shared by the local and Supabase backends.

Both implementations satisfy this interface exactly, which is what lets the API
layer stay unaware of where data lives. `LocalStore` keeps everything in a JSON
file on this machine; `SupabaseStore` talks to Postgres as the signed-in user so
Row Level Security decides what they can see.
"""

from __future__ import annotations

import abc

from ..schemas import ContactProfile, FeedbackCreate, Memory, MemoryCreate, StyleProfile


class StoreError(RuntimeError):
    """Raised when a storage backend cannot complete a request."""

    def __init__(self, message: str, status: int = 500) -> None:
        super().__init__(message)
        self.status = status


class BaseStore(abc.ABC):
    """Every method is scoped to exactly one user. There is no cross-user read."""

    # --- style profile -----------------------------------------------------

    @abc.abstractmethod
    def get_style_profile(self) -> StyleProfile: ...

    @abc.abstractmethod
    def save_style_profile(self, profile: StyleProfile) -> StyleProfile: ...

    @abc.abstractmethod
    def reset_style_profile(self) -> StyleProfile: ...

    # --- contacts ----------------------------------------------------------

    @abc.abstractmethod
    def list_contacts(self) -> list[ContactProfile]: ...

    @abc.abstractmethod
    def get_contact(self, contact_id: str) -> ContactProfile | None: ...

    @abc.abstractmethod
    def create_contact(
        self, name: str, nickname: str | None, notes: str
    ) -> ContactProfile: ...

    @abc.abstractmethod
    def update_contact(self, contact_id: str, **fields) -> ContactProfile | None: ...

    @abc.abstractmethod
    def delete_contact(self, contact_id: str) -> bool: ...

    @abc.abstractmethod
    def observe_contact_style(
        self, contact_id: str, sheng_ratio: float, avg_words: float
    ) -> None: ...

    # --- memories ----------------------------------------------------------

    @abc.abstractmethod
    def add_memory(self, contact_id: str, payload: MemoryCreate) -> Memory | None: ...

    @abc.abstractmethod
    def delete_memory(self, contact_id: str, memory_id: str) -> bool: ...

    @abc.abstractmethod
    def add_boundary(self, contact_id: str, quote: str) -> None: ...

    # --- derived helpers ---------------------------------------------------
    # Shared by both backends: both are expressed purely in terms of get_contact.

    def memory_keys(self, contact_id: str | None) -> set[str]:
        """Dedupe keys already answered, so a question is never asked twice."""
        if not contact_id:
            return set()
        contact = self.get_contact(contact_id)
        return {m.key for m in contact.memories} if contact else set()

    def memory_lines(self, contact_id: str | None) -> list[str]:
        if not contact_id:
            return []
        contact = self.get_contact(contact_id)
        if not contact:
            return []
        return [f"{m.key} -> {m.value}" for m in contact.memories]

    # --- feedback ----------------------------------------------------------

    @abc.abstractmethod
    def add_feedback(self, payload: FeedbackCreate) -> None: ...

    @abc.abstractmethod
    def list_feedback(self) -> list[dict]: ...

    # --- privacy -----------------------------------------------------------

    @abc.abstractmethod
    def wipe(self) -> None:
        """Delete everything this user has stored. Irreversible, by design."""
