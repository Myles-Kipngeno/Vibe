"""Local JSON storage: single-user mode, no account needed.

This is what runs when Supabase is not configured. Everything stays in one JSON
file on this machine, which is the most private option available and keeps the
app usable offline. It implements the same `BaseStore` contract as
`SupabaseStore`, so the API layer cannot tell them apart.
"""

from __future__ import annotations

import json
import threading
import uuid
from datetime import datetime, timezone
from pathlib import Path

from ..schemas import (
    ContactProfile,
    FeedbackCreate,
    Memory,
    MemoryCreate,
    StyleProfile,
)
from ..core.style_profile import blank_profile
from .base import BaseStore


def _now() -> datetime:
    return datetime.now(timezone.utc)


def _new_id(prefix: str) -> str:
    return f"{prefix}_{uuid.uuid4().hex[:12]}"


class LocalStore(BaseStore):
    """A small, file-backed store. One process, one user, one JSON document."""

    def __init__(self, data_dir: Path) -> None:
        self._dir = Path(data_dir)
        self._dir.mkdir(parents=True, exist_ok=True)
        self._path = self._dir / "store.json"
        self._lock = threading.RLock()
        self._data = self._read()

    # --- persistence -------------------------------------------------------

    def _read(self) -> dict:
        if not self._path.exists():
            return {"style_profile": None, "contacts": {}, "feedback": []}
        try:
            return json.loads(self._path.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, OSError):
            # A corrupt store must not take the app down; we start clean and
            # keep the old file so nothing is silently destroyed.
            backup = self._path.with_suffix(".corrupt.json")
            try:
                self._path.replace(backup)
            except OSError:
                pass
            return {"style_profile": None, "contacts": {}, "feedback": []}

    def _write(self) -> None:
        tmp = self._path.with_suffix(".tmp")
        tmp.write_text(
            json.dumps(self._data, indent=2, default=str), encoding="utf-8"
        )
        tmp.replace(self._path)

    # --- style profile -----------------------------------------------------

    def get_style_profile(self) -> StyleProfile:
        with self._lock:
            raw = self._data.get("style_profile")
            return StyleProfile.model_validate(raw) if raw else blank_profile()

    def save_style_profile(self, profile: StyleProfile) -> StyleProfile:
        with self._lock:
            profile.updated_at = _now()
            self._data["style_profile"] = json.loads(profile.model_dump_json())
            self._write()
            return profile

    def reset_style_profile(self) -> StyleProfile:
        with self._lock:
            self._data["style_profile"] = None
            self._write()
            return blank_profile()

    # --- contacts ----------------------------------------------------------

    def list_contacts(self) -> list[ContactProfile]:
        with self._lock:
            return [
                ContactProfile.model_validate(c)
                for c in self._data.get("contacts", {}).values()
            ]

    def get_contact(self, contact_id: str) -> ContactProfile | None:
        with self._lock:
            raw = self._data.get("contacts", {}).get(contact_id)
            return ContactProfile.model_validate(raw) if raw else None

    def create_contact(self, name: str, nickname: str | None, notes: str) -> ContactProfile:
        with self._lock:
            contact = ContactProfile(
                id=_new_id("contact"),
                name=name,
                nickname=nickname,
                notes=notes,
                memories=[],
                created_at=_now(),
                updated_at=_now(),
            )
            self._data.setdefault("contacts", {})[contact.id] = json.loads(
                contact.model_dump_json()
            )
            self._write()
            return contact

    def _save_contact(self, contact: ContactProfile) -> ContactProfile:
        contact.updated_at = _now()
        self._data.setdefault("contacts", {})[contact.id] = json.loads(
            contact.model_dump_json()
        )
        self._write()
        return contact

    def update_contact(self, contact_id: str, **fields) -> ContactProfile | None:
        with self._lock:
            contact = self.get_contact(contact_id)
            if contact is None:
                return None
            for key, value in fields.items():
                if value is not None:
                    setattr(contact, key, value)
            return self._save_contact(contact)

    def delete_contact(self, contact_id: str) -> bool:
        with self._lock:
            removed = self._data.get("contacts", {}).pop(contact_id, None)
            if removed is not None:
                self._write()
            return removed is not None

    def observe_contact_style(
        self, contact_id: str, sheng_ratio: float, avg_words: float
    ) -> None:
        with self._lock:
            contact = self.get_contact(contact_id)
            if contact is None:
                return
            contact.observed_sheng_ratio = round(sheng_ratio, 3)
            contact.observed_avg_length = round(avg_words)
            self._save_contact(contact)

    # --- memories ----------------------------------------------------------

    def add_memory(self, contact_id: str, payload: MemoryCreate) -> Memory | None:
        with self._lock:
            contact = self.get_contact(contact_id)
            if contact is None:
                return None
            # One value per key: re-answering a context question corrects it
            # rather than stacking duplicates.
            contact.memories = [m for m in contact.memories if m.key != payload.key]
            memory = Memory(
                id=_new_id("mem"),
                key=payload.key,
                value=payload.value,
                source=payload.source,
                confidence=payload.confidence,
                created_at=_now(),
            )
            contact.memories.append(memory)
            self._save_contact(contact)
            return memory

    def delete_memory(self, contact_id: str, memory_id: str) -> bool:
        with self._lock:
            contact = self.get_contact(contact_id)
            if contact is None:
                return False
            before = len(contact.memories)
            contact.memories = [m for m in contact.memories if m.id != memory_id]
            if len(contact.memories) == before:
                return False
            self._save_contact(contact)
            return True

    def add_boundary(self, contact_id: str, quote: str) -> None:
        """Record that she stated a boundary, so later sessions respect it too."""
        with self._lock:
            contact = self.get_contact(contact_id)
            if contact is None or quote in contact.stated_boundaries:
                return
            contact.stated_boundaries.append(quote)
            self._save_contact(contact)

    # --- feedback ----------------------------------------------------------

    def add_feedback(self, payload: FeedbackCreate) -> None:
        with self._lock:
            self._data.setdefault("feedback", []).append(
                {**payload.model_dump(), "created_at": _now().isoformat()}
            )
            self._write()

    def list_feedback(self) -> list[dict]:
        with self._lock:
            return list(self._data.get("feedback", []))

    # --- privacy -----------------------------------------------------------

    def wipe(self) -> None:
        """Delete everything this app has stored. Irreversible, by design."""
        with self._lock:
            self._data = {"style_profile": None, "contacts": {}, "feedback": []}
            self._write()


# Kept so existing imports and any external scripts keep working.
Store = LocalStore
