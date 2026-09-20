"""Supabase-backed storage, accessed as the signed-in user.

## The trust model, stated plainly

Every request here carries **the user's own access token**, so PostgREST runs the
query under their identity and the Row Level Security policies in
`supabase/schema.sql` decide what they can touch. Isolation between users is
enforced by Postgres, not by filters in this file. That is deliberate: a bug in
this module can cause an error, but it cannot leak another user's conversations.

Two consequences worth being explicit about:

* **The service-role key is never used.** It bypasses RLS entirely, which would
  make this file the security boundary. It is not in the config at all.
* **`sub` is read from the token without verifying the signature**, and is used
  only to build `user_id=eq.…` filters on writes (PostgREST requires a filter
  for UPDATE/DELETE). A forged `sub` gains nothing: the token still has to pass
  Supabase's own signature check to get past the API gateway, and `auth.uid()`
  inside every RLS policy comes from that verified token, not from anything this
  file computed. If the two disagree, the query simply matches no rows.
"""

from __future__ import annotations

import base64
import binascii
import json
from datetime import datetime, timezone
from typing import Any

import httpx

from ..core.style_profile import blank_profile
from ..schemas import (
    ConversationExample,
    ConversationExampleCreate,
    ContactProfile,
    FeedbackCreate,
    Memory,
    MemoryCreate,
    StyleProfile,
)
from .base import BaseStore, StoreError

# Columns the frontend actually needs, plus the embedded memories. PostgREST
# resolves `conversation_memories(*)` through the declared foreign key.
_CONTACT_SELECT = "*,conversation_memories(*)"

_STYLE_COLUMNS = (
    "sheng_ratio",
    "avg_message_length",
    "emoji_frequency",
    "humor_style",
    "directness",
    "flirting_style",
    "common_expressions",
    "example_messages",
    "max_tone",
    "learned_from_messages",
    "updated_at",
)


def subject_from_token(token: str) -> str | None:
    """Read the `sub` claim without verifying the signature.

    See the module docstring: this is used for query filters only, never for an
    authorization decision. Returns None for anything that is not a readable JWT.
    """
    try:
        payload = token.split(".")[1]
        payload += "=" * (-len(payload) % 4)  # restore base64url padding
        claims = json.loads(base64.urlsafe_b64decode(payload))
    except (IndexError, ValueError, binascii.Error, UnicodeDecodeError):
        return None
    sub = claims.get("sub")
    return sub if isinstance(sub, str) and sub else None


def _now() -> datetime:
    return datetime.now(timezone.utc)


def _iso(value: Any) -> datetime:
    if isinstance(value, datetime):
        return value
    if isinstance(value, str):
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    return _now()


_EXAMPLE_FIELDS: tuple[str, ...] = (
    "situation",
    "context",
    "opening_line",
    "language_mix",
    "tone",
    "reaction",
    "what_worked",
    "what_did_not",
    "not_suitable_when",
)


class SupabaseStore(BaseStore):
    def __init__(
        self,
        url: str,
        anon_key: str,
        access_token: str,
        client: httpx.Client | None = None,
        timeout: float = 15.0,
    ) -> None:
        self._base = url.rstrip("/") + "/rest/v1"
        self._token = access_token
        self._user_id = subject_from_token(access_token)
        self._headers = {
            "apikey": anon_key,
            "Authorization": f"Bearer {access_token}",
            "Content-Type": "application/json",
        }
        self._client = client or httpx.Client(timeout=timeout)

    # --- request plumbing --------------------------------------------------

    def _owner_filter(self) -> dict[str, str]:
        """PostgREST refuses unfiltered UPDATE/DELETE, and rightly so."""
        if not self._user_id:
            raise StoreError("Your session token is not readable. Sign in again.", 401)
        return {"user_id": f"eq.{self._user_id}"}

    def _request(
        self,
        method: str,
        table: str,
        *,
        params: dict[str, str] | None = None,
        json_body: Any = None,
        prefer: str | None = None,
    ) -> Any:
        headers = dict(self._headers)
        if prefer:
            headers["Prefer"] = prefer
        try:
            response = self._client.request(
                method,
                f"{self._base}/{table}",
                params=params,
                json=json_body,
                headers=headers,
            )
        except httpx.HTTPError as exc:
            raise StoreError(f"Could not reach Supabase: {exc}", 502) from exc

        if response.status_code in (401, 403):
            # Either the token expired, or RLS refused the row. We do not
            # distinguish, because saying "that row exists but is not yours"
            # would itself leak something.
            raise StoreError(
                "Your session has expired or you do not have access to that. "
                "Sign in again.",
                401,
            )
        if response.status_code >= 400:
            detail = ""
            try:
                detail = response.json().get("message", "")
            except ValueError:
                detail = response.text[:200]
            raise StoreError(f"Supabase rejected the request: {detail}", 502)

        if response.status_code == 204 or not response.content:
            return None
        return response.json()

    def _rows(self, table: str, params: dict[str, str]) -> list[dict]:
        result = self._request("GET", table, params=params)
        return result if isinstance(result, list) else []

    # --- mapping -----------------------------------------------------------

    @staticmethod
    def _to_memory(row: dict) -> Memory:
        return Memory(
            id=str(row["id"]),
            key=row["key"],
            value=row["value"],
            source=row.get("source", "user"),
            confidence=row.get("confidence", "confirmed"),
            created_at=_iso(row.get("created_at")),
        )

    @classmethod
    def _to_contact(cls, row: dict) -> ContactProfile:
        memories = [cls._to_memory(m) for m in row.get("conversation_memories") or []]
        memories.sort(key=lambda m: m.created_at)
        return ContactProfile(
            id=str(row["id"]),
            name=row["name"],
            nickname=row.get("nickname"),
            notes=row.get("notes") or "",
            memories=memories,
            observed_sheng_ratio=row.get("observed_sheng_ratio"),
            observed_avg_length=row.get("observed_avg_length"),
            stated_boundaries=row.get("stated_boundaries") or [],
            created_at=_iso(row.get("created_at")),
            updated_at=_iso(row.get("updated_at")),
        )

    # --- style profile -----------------------------------------------------

    def get_style_profile(self) -> StyleProfile:
        rows = self._rows("communication_preferences", {"select": "*", "limit": "1"})
        if not rows:
            # The signup trigger normally creates this row; if it is missing we
            # fall back to defaults rather than failing the whole request.
            return blank_profile()
        row = rows[0]
        return StyleProfile.model_validate(
            {k: row[k] for k in _STYLE_COLUMNS if k in row}
        )

    def save_style_profile(self, profile: StyleProfile) -> StyleProfile:
        profile.updated_at = _now()
        payload = json.loads(profile.model_dump_json(include=set(_STYLE_COLUMNS)))
        rows = self._request(
            "PATCH",
            "communication_preferences",
            params=self._owner_filter(),
            json_body=payload,
            prefer="return=representation",
        )
        if not rows:
            # No row to patch (trigger never ran): insert one instead.
            self._request(
                "POST",
                "communication_preferences",
                json_body={**payload, "user_id": self._user_id},
                prefer="return=representation",
            )
        return profile

    def reset_style_profile(self) -> StyleProfile:
        return self.save_style_profile(blank_profile())

    # --- contacts ----------------------------------------------------------

    def list_contacts(self) -> list[ContactProfile]:
        rows = self._rows(
            "contact_profiles",
            {"select": _CONTACT_SELECT, "order": "created_at.asc"},
        )
        return [self._to_contact(r) for r in rows]

    def get_contact(self, contact_id: str) -> ContactProfile | None:
        rows = self._rows(
            "contact_profiles",
            {"select": _CONTACT_SELECT, "id": f"eq.{contact_id}", "limit": "1"},
        )
        return self._to_contact(rows[0]) if rows else None

    def create_contact(
        self, name: str, nickname: str | None, notes: str
    ) -> ContactProfile:
        # `user_id` is filled by the column default `auth.uid()`, so the client
        # never gets to name an owner.
        rows = self._request(
            "POST",
            "contact_profiles",
            json_body={"name": name, "nickname": nickname, "notes": notes},
            prefer="return=representation",
        )
        if not rows:
            raise StoreError("Supabase did not return the new contact.", 502)
        return self._to_contact(rows[0])

    def _patch_contact(self, contact_id: str, fields: dict) -> ContactProfile | None:
        rows = self._request(
            "PATCH",
            "contact_profiles",
            params={"id": f"eq.{contact_id}"},
            json_body={**fields, "updated_at": _now().isoformat()},
            prefer="return=representation",
        )
        if not rows:
            return None
        return self.get_contact(contact_id)

    def update_contact(self, contact_id: str, **fields) -> ContactProfile | None:
        clean = {k: v for k, v in fields.items() if v is not None}
        if not clean:
            return self.get_contact(contact_id)
        return self._patch_contact(contact_id, clean)

    def delete_contact(self, contact_id: str) -> bool:
        rows = self._request(
            "DELETE",
            "contact_profiles",
            params={"id": f"eq.{contact_id}"},
            prefer="return=representation",
        )
        return bool(rows)

    def observe_contact_style(
        self, contact_id: str, sheng_ratio: float, avg_words: float
    ) -> None:
        self._patch_contact(
            contact_id,
            {
                "observed_sheng_ratio": round(sheng_ratio, 3),
                "observed_avg_length": round(avg_words),
            },
        )

    # --- memories ----------------------------------------------------------

    def add_memory(self, contact_id: str, payload: MemoryCreate) -> Memory | None:
        if self.get_contact(contact_id) is None:
            return None
        # `unique (contact_id, key)` plus merge-duplicates means re-answering a
        # context question corrects the stored value instead of stacking copies.
        rows = self._request(
            "POST",
            "conversation_memories",
            json_body={
                "contact_id": contact_id,
                "key": payload.key,
                "value": payload.value,
                "source": payload.source,
                "confidence": payload.confidence,
            },
            prefer="return=representation,resolution=merge-duplicates",
        )
        if not rows:
            return None
        return self._to_memory(rows[0])

    def delete_memory(self, contact_id: str, memory_id: str) -> bool:
        rows = self._request(
            "DELETE",
            "conversation_memories",
            params={"id": f"eq.{memory_id}", "contact_id": f"eq.{contact_id}"},
            prefer="return=representation",
        )
        return bool(rows)

    def add_boundary(self, contact_id: str, quote: str) -> None:
        contact = self.get_contact(contact_id)
        if contact is None or quote in contact.stated_boundaries:
            return
        self._patch_contact(
            contact_id, {"stated_boundaries": [*contact.stated_boundaries, quote]}
        )

    # --- feedback ----------------------------------------------------------

    def add_feedback(self, payload: FeedbackCreate) -> None:
        self._request(
            "POST",
            "suggestion_feedback",
            json_body={
                "suggestion_id": payload.suggestion_id,
                "suggestion_text": payload.suggestion_text,
                "verdict": payload.verdict,
                "note": payload.note,
            },
            prefer="return=minimal",
        )

    def list_feedback(self) -> list[dict]:
        return self._rows(
            "suggestion_feedback", {"select": "*", "order": "created_at.desc"}
        )

    # --- the example library -----------------------------------------------

    @staticmethod
    def _to_example(row: dict) -> ConversationExample:
        return ConversationExample(
            id=str(row["id"]),
            created_at=row["created_at"],
            **{f: (row.get(f) or "") for f in _EXAMPLE_FIELDS if f != "language_mix"},
            language_mix=row.get("language_mix") or "mixed",
        )

    def add_example(self, payload: ConversationExampleCreate) -> ConversationExample:
        """`user_id` is not sent: the column defaults to auth.uid().

        The owner is decided by Postgres from the verified token, which is why
        a client cannot plant a row belonging to somebody else.
        """
        rows = self._request(
            "POST",
            "conversation_examples",
            json_body=payload.model_dump(),
            prefer="return=representation",
        )
        if not rows:
            raise StoreError("Supabase did not return the saved example.", 502)
        return self._to_example(rows[0])

    def list_examples(self) -> list[ConversationExample]:
        rows = self._rows(
            "conversation_examples", {"select": "*", "order": "created_at.desc"}
        )
        return [self._to_example(r) for r in rows]

    def delete_example(self, example_id: str) -> bool:
        rows = self._request(
            "DELETE",
            "conversation_examples",
            params={"id": f"eq.{example_id}", **self._owner_filter()},
            prefer="return=representation",
        )
        return bool(rows)

    # --- privacy -----------------------------------------------------------

    def wipe(self) -> None:
        """Delete everything this user owns, then restore a default profile.

        Contacts cascade to their memories, so those go with them. Each delete is
        still scoped by `user_id` as well as by RLS -- belt and braces on the one
        operation that is impossible to undo.
        """
        owner = self._owner_filter()
        for table in (
            "contact_profiles",
            "suggestion_feedback",
            "context_alerts",
            "conversations",
            "conversation_examples",
        ):
            self._request("DELETE", table, params=dict(owner), prefer="return=minimal")
        self.reset_style_profile()
