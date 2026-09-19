"""Tests for the Supabase backend, against a fake PostgREST.

These verify the half we control: that requests carry the user's own token, that
writes never name an owner, that filters are correct, and that a 401 from
Postgres surfaces as a clean 401 rather than a 500.

What they cannot verify is Row Level Security itself -- that is enforced by
Postgres and needs a real project to exercise. `supabase/schema.sql` is the
source of truth for it, and the checklist for testing it lives in the README.
"""

from __future__ import annotations

import base64
import json

import httpx
import pytest

from app.schemas import FeedbackCreate, MemoryCreate
from app.storage.base import StoreError
from app.storage.supabase_store import SupabaseStore, subject_from_token

USER_ID = "11111111-2222-3333-4444-555555555555"
OTHER_ID = "99999999-8888-7777-6666-555555555555"


def make_token(sub: str = USER_ID) -> str:
    """A structurally valid JWT. The signature is never checked by our code."""
    def seg(data: dict) -> str:
        raw = base64.urlsafe_b64encode(json.dumps(data).encode()).decode()
        return raw.rstrip("=")

    return f"{seg({'alg': 'ES256'})}.{seg({'sub': sub, 'role': 'authenticated'})}.sig"


CONTACT_ROW = {
    "id": "c-1",
    "name": "Ann",
    "nickname": None,
    "notes": "",
    "observed_sheng_ratio": None,
    "observed_avg_length": None,
    "stated_boundaries": [],
    "created_at": "2026-09-19T10:00:00+00:00",
    "updated_at": "2026-09-19T10:00:00+00:00",
    "conversation_memories": [
        {
            "id": "m-1",
            "key": "person:randy",
            "value": "Her cousin",
            "source": "user",
            "confidence": "confirmed",
            "created_at": "2026-09-19T10:05:00+00:00",
        }
    ],
}


class FakePostgrest:
    """Records every request and replies with canned rows."""

    def __init__(self, responses: dict[str, object] | None = None, status: int = 200):
        self.requests: list[httpx.Request] = []
        self.responses = responses or {}
        self.status = status

    def handler(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        table = request.url.path.rsplit("/", 1)[-1]
        if self.status >= 400:
            return httpx.Response(self.status, json={"message": "nope"})
        body = self.responses.get(table, [])
        return httpx.Response(200, json=body)

    @property
    def last(self) -> httpx.Request:
        return self.requests[-1]

    def sent_json(self, index: int = -1) -> dict:
        return json.loads(self.requests[index].content)


def make_store(fake: FakePostgrest, sub: str = USER_ID) -> SupabaseStore:
    client = httpx.Client(transport=httpx.MockTransport(fake.handler))
    return SupabaseStore(
        url="https://example.supabase.co",
        anon_key="anon-key",
        access_token=make_token(sub),
        client=client,
    )


# --- identity and headers ------------------------------------------------------


def test_every_request_carries_the_users_own_token():
    fake = FakePostgrest({"contact_profiles": [CONTACT_ROW]})
    make_store(fake).list_contacts()

    assert fake.last.headers["Authorization"] == f"Bearer {make_token()}"
    assert fake.last.headers["apikey"] == "anon-key"


def test_subject_is_read_from_the_token():
    assert subject_from_token(make_token(OTHER_ID)) == OTHER_ID


@pytest.mark.parametrize("bad", ["", "not-a-jwt", "a.b", "a.!!!.c"])
def test_unreadable_tokens_do_not_crash(bad):
    assert subject_from_token(bad) is None


def test_an_unreadable_token_is_rejected_before_any_write():
    fake = FakePostgrest()
    store = SupabaseStore(
        url="https://example.supabase.co",
        anon_key="anon-key",
        access_token="garbage",
        client=httpx.Client(transport=httpx.MockTransport(fake.handler)),
    )
    with pytest.raises(StoreError) as exc:
        store.wipe()
    assert exc.value.status == 401
    assert fake.requests == [], "nothing should be sent with an unusable token"


# --- writes never name an owner ------------------------------------------------


def test_creating_a_contact_does_not_send_a_user_id():
    fake = FakePostgrest({"contact_profiles": [CONTACT_ROW]})
    make_store(fake).create_contact("Ann", None, "")

    body = fake.sent_json()
    assert "user_id" not in body, "the owner comes from auth.uid(), never the client"
    assert body["name"] == "Ann"


def test_adding_a_memory_does_not_send_a_user_id():
    fake = FakePostgrest(
        {
            "contact_profiles": [CONTACT_ROW],
            "conversation_memories": [CONTACT_ROW["conversation_memories"][0]],
        }
    )
    make_store(fake).add_memory("c-1", MemoryCreate(key="person:randy", value="Cousin"))

    body = fake.sent_json()
    assert "user_id" not in body
    assert body["contact_id"] == "c-1"


def test_re_answering_a_question_corrects_rather_than_duplicates():
    fake = FakePostgrest(
        {
            "contact_profiles": [CONTACT_ROW],
            "conversation_memories": [CONTACT_ROW["conversation_memories"][0]],
        }
    )
    make_store(fake).add_memory("c-1", MemoryCreate(key="person:randy", value="Cousin"))
    assert "merge-duplicates" in fake.last.headers["Prefer"]


def test_feedback_does_not_send_a_user_id():
    fake = FakePostgrest({"suggestion_feedback": []})
    make_store(fake).add_feedback(
        FeedbackCreate(suggestion_id="s1", suggestion_text="hi", verdict="used")
    )
    assert "user_id" not in fake.sent_json()


# --- filters -------------------------------------------------------------------


def test_reads_embed_the_contacts_memories():
    fake = FakePostgrest({"contact_profiles": [CONTACT_ROW]})
    contacts = make_store(fake).list_contacts()

    assert fake.last.url.params["select"] == "*,conversation_memories(*)"
    assert contacts[0].memories[0].key == "person:randy"


def test_deleting_a_memory_is_scoped_to_its_contact():
    fake = FakePostgrest({"conversation_memories": [{"id": "m-1"}]})
    make_store(fake).delete_memory("c-1", "m-1")

    params = fake.last.url.params
    assert params["id"] == "eq.m-1"
    assert params["contact_id"] == "eq.c-1", "a memory id alone must not be enough"


def test_wipe_is_filtered_by_the_owner_on_every_table():
    fake = FakePostgrest()
    make_store(fake).wipe()

    deletes = [r for r in fake.requests if r.method == "DELETE"]
    assert {r.url.path.rsplit("/", 1)[-1] for r in deletes} == {
        "contact_profiles",
        "suggestion_feedback",
        "context_alerts",
        "conversations",
        "conversation_examples",
    }
    assert all(r.url.params["user_id"] == f"eq.{USER_ID}" for r in deletes)


def test_style_updates_are_filtered_by_the_owner():
    fake = FakePostgrest({"communication_preferences": [{"user_id": USER_ID}]})
    store = make_store(fake)
    profile = store.get_style_profile()
    profile.humor_style = "dry"
    store.save_style_profile(profile)

    patch = [r for r in fake.requests if r.method == "PATCH"][0]
    assert patch.url.params["user_id"] == f"eq.{USER_ID}"


# --- failures ------------------------------------------------------------------


def test_a_row_denied_by_rls_reads_as_a_clean_401():
    fake = FakePostgrest(status=403)
    with pytest.raises(StoreError) as exc:
        make_store(fake).list_contacts()
    assert exc.value.status == 401
    assert "expired" in str(exc.value) or "access" in str(exc.value)


def test_a_missing_row_is_none_not_an_error():
    fake = FakePostgrest({"contact_profiles": []})
    assert make_store(fake).get_contact("nope") is None


def test_a_network_failure_is_a_502_not_a_crash():
    def boom(_: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("down")

    store = SupabaseStore(
        url="https://example.supabase.co",
        anon_key="anon-key",
        access_token=make_token(),
        client=httpx.Client(transport=httpx.MockTransport(boom)),
    )
    with pytest.raises(StoreError) as exc:
        store.list_contacts()
    assert exc.value.status == 502


def test_a_missing_preferences_row_falls_back_to_defaults():
    fake = FakePostgrest({"communication_preferences": []})
    profile = make_store(fake).get_style_profile()
    assert profile.learned_from_messages == 0


# --- the two backends really are interchangeable -------------------------------


def test_both_stores_implement_the_same_contract():
    from app.storage.base import BaseStore
    from app.storage.store import LocalStore

    required = {
        name
        for name, value in vars(BaseStore).items()
        if getattr(value, "__isabstractmethod__", False)
    }
    assert required, "BaseStore should declare abstract methods"

    for impl in (LocalStore, SupabaseStore):
        # Empty __abstractmethods__ is Python's own proof that a subclass
        # implements everything, so the class is instantiable at all.
        assert impl.__abstractmethods__ == frozenset(), (
            f"{impl.__name__} does not implement {set(impl.__abstractmethods__)}"
        )
        assert required <= set(dir(impl))
