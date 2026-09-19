"""Prove Row Level Security actually isolates two accounts.

This is the check the unit tests cannot make. `tests/test_supabase_store.py`
runs against a fake PostgREST, so it proves the backend attaches the right token
and never names an owner -- but only a real project can prove that Postgres
refuses one user's rows to another.

Run it after applying `supabase/schema.sql`:

    python scripts/verify_rls.py --url https://xxxx.supabase.co --anon-key eyJ...

It signs up two throwaway accounts, then attacks the boundary from both sides:
straight at PostgREST (which is where RLS lives) and through the running backend
(which is how the app actually reaches it). It cleans up after itself.

Nothing here needs a service-role key. If this script could read another user's
rows with only an anon key and a normal login, so could anyone.
"""

from __future__ import annotations

import argparse
import json
import secrets
import sys
import urllib.error
import urllib.request

TIMEOUT = 30

passed = 0
failed = 0


def check(name: str, ok: bool, detail: str = "") -> bool:
    global passed, failed
    if ok:
        passed += 1
        print(f"  PASS  {name}" + (f"  -- {detail}" if detail else ""))
    else:
        failed += 1
        print(f"  FAIL  {name}  -- {detail}")
    return ok


def call(
    url: str,
    method: str = "GET",
    headers: dict[str, str] | None = None,
    body: dict | list | None = None,
) -> tuple[int, object]:
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(url, data=data, method=method)
    for key, value in (headers or {}).items():
        request.add_header(key, value)
    if data is not None:
        request.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT) as response:
            raw = response.read()
            return response.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as exc:
        raw = exc.read()
        try:
            return exc.code, json.loads(raw) if raw else None
        except json.JSONDecodeError:
            return exc.code, raw.decode("utf-8", "replace")
    except urllib.error.URLError as exc:
        return 0, str(exc.reason)


class Account:
    """One signed-up test user, with the pieces needed to act as them."""

    def __init__(self, label: str, email: str, user_id: str, token: str):
        self.label = label
        self.email = email
        self.user_id = user_id
        self.token = token

    def rest(self, anon_key: str) -> dict[str, str]:
        return {"apikey": anon_key, "Authorization": f"Bearer {self.token}"}

    def api(self) -> dict[str, str]:
        return {"Authorization": f"Bearer {self.token}"}


def sign_up(base_url: str, anon_key: str, label: str) -> Account | None:
    email = f"rls-{label}-{secrets.token_hex(5)}@example.com"
    password = "Test-" + secrets.token_urlsafe(16)
    status, body = call(
        f"{base_url}/auth/v1/signup",
        "POST",
        {"apikey": anon_key},
        {"email": email, "password": password},
    )

    if status >= 400:
        message = body.get("msg") or body.get("error_description") if isinstance(body, dict) else body
        print(f"\n  Could not create test account {label}: {message}")
        return None

    if not isinstance(body, dict) or not body.get("access_token"):
        print(
            "\n  Signup succeeded but returned no session, which means email\n"
            "  confirmation is on. For this test, turn it off temporarily:\n"
            "    Supabase -> Authentication -> Sign In / Providers -> Email\n"
            "    -> uncheck 'Confirm email' -> Save, then re-run.\n"
            "  Turn it back on afterwards."
        )
        return None

    user_id = (body.get("user") or {}).get("id")
    return Account(label, email, str(user_id), body["access_token"])


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", required=True, help="https://<ref>.supabase.co")
    parser.add_argument("--anon-key", required=True)
    parser.add_argument(
        "--backend",
        default="http://127.0.0.1:8000",
        help="Vibe backend. Pass --backend '' to skip the app-path checks.",
    )
    args = parser.parse_args()

    base = args.url.rstrip("/")
    anon = args.anon_key
    rest = f"{base}/rest/v1"

    print("\n== Reachability ==")
    status, _ = call(f"{rest}/contact_profiles?select=id&limit=1", headers={"apikey": anon})
    if not check(
        "project reachable and schema applied",
        status in (200, 401),
        f"HTTP {status}"
        + (
            "  (404 usually means supabase/schema.sql has not been run yet)"
            if status == 404
            else ""
        ),
    ):
        return 1

    check(
        "anon key alone cannot read contacts",
        status == 401,
        "RLS rejects an unauthenticated request" if status == 401 else f"HTTP {status}",
    )

    print("\n== Two accounts ==")
    alice = sign_up(base, anon, "alice")
    bob = sign_up(base, anon, "bob")
    if not alice or not bob:
        return 1
    check("two accounts created", alice.user_id != bob.user_id,
          f"{alice.user_id[:8]}… and {bob.user_id[:8]}…")

    print("\n== Alice creates data ==")
    status, rows = call(
        f"{rest}/contact_profiles",
        "POST",
        {**alice.rest(anon), "Prefer": "return=representation"},
        {"name": "Ann", "notes": "alice's contact"},
    )
    if not check("Alice can create her own contact", status in (200, 201), f"HTTP {status}: {rows}"):
        return 1
    contact_id = rows[0]["id"]
    check(
        "the row is owned by Alice, set by auth.uid()",
        rows[0]["user_id"] == alice.user_id,
        "client never sent user_id",
    )

    status, mem = call(
        f"{rest}/conversation_memories",
        "POST",
        {**alice.rest(anon), "Prefer": "return=representation"},
        {"contact_id": contact_id, "key": "person:randy", "value": "her cousin"},
    )
    check("Alice can store a memory", status in (200, 201), f"HTTP {status}")

    print("\n== Bob cannot reach any of it (straight at PostgREST) ==")
    status, rows = call(f"{rest}/contact_profiles?select=*", headers=bob.rest(anon))
    check("Bob's contact list is empty", status == 200 and rows == [], f"HTTP {status}: {rows}")

    status, rows = call(
        f"{rest}/contact_profiles?select=*&id=eq.{contact_id}", headers=bob.rest(anon)
    )
    check(
        "Bob cannot read Alice's contact even knowing its id",
        status == 200 and rows == [],
        f"HTTP {status}: {rows}",
    )

    status, rows = call(f"{rest}/conversation_memories?select=*", headers=bob.rest(anon))
    check("Bob cannot read Alice's memories", status == 200 and rows == [], f"HTTP {status}")

    status, rows = call(
        f"{rest}/contact_profiles?id=eq.{contact_id}",
        "PATCH",
        {**bob.rest(anon), "Prefer": "return=representation"},
        {"name": "hacked"},
    )
    check(
        "Bob cannot modify Alice's contact",
        status in (200, 204) and not rows,
        f"HTTP {status}: {rows}  (no rows matched = RLS filtered it out)",
    )

    status, rows = call(
        f"{rest}/contact_profiles?id=eq.{contact_id}",
        "DELETE",
        {**bob.rest(anon), "Prefer": "return=representation"},
    )
    check("Bob cannot delete Alice's contact", status in (200, 204) and not rows, f"HTTP {status}")

    status, body = call(
        f"{rest}/contact_profiles",
        "POST",
        {**bob.rest(anon), "Prefer": "return=representation"},
        {"name": "planted", "user_id": alice.user_id},
    )
    check(
        "Bob cannot create a row owned by Alice",
        status >= 400,
        f"HTTP {status} -- the WITH CHECK clause refused it",
    )

    status, rows = call(f"{rest}/contact_profiles?select=*", headers=alice.rest(anon))
    check(
        "Alice's data survived all of that, unchanged",
        status == 200 and len(rows) == 1 and rows[0]["name"] == "Ann",
        f"{rows}",
    )

    if args.backend:
        print("\n== Through the Vibe backend ==")
        api = args.backend.rstrip("/")
        status, body = call(f"{api}/api/health")
        if status != 200:
            print(f"  SKIP  backend not running at {api}")
        elif not body.get("auth_required"):
            print("  SKIP  backend is in local mode; set SUPABASE_URL/ANON_KEY in backend/.env")
        else:
            status, _ = call(f"{api}/api/contacts")
            check("anonymous request is refused", status == 401, f"HTTP {status}")

            status, rows = call(f"{api}/api/contacts", headers=alice.api())
            check(
                "Alice sees her contact through the app",
                status == 200 and len(rows) == 1,
                f"HTTP {status}",
            )
            check(
                "her memory came back with it",
                status == 200 and rows and len(rows[0]["memories"]) == 1,
                "memories are embedded per contact",
            )

            status, rows = call(f"{api}/api/contacts", headers=bob.api())
            check("Bob sees nothing through the app", status == 200 and rows == [], f"HTTP {status}")

            status, _ = call(f"{api}/api/contacts/{contact_id}", headers=bob.api())
            check(
                "Bob gets 404 for Alice's contact, not its contents",
                status == 404,
                f"HTTP {status}",
            )

    print("\n== Cleanup ==")
    if isinstance(mem, list) and mem:
        call(f"{rest}/conversation_memories?id=eq.{mem[0]['id']}", "DELETE", alice.rest(anon))
    status, _ = call(f"{rest}/contact_profiles?id=eq.{contact_id}", "DELETE", alice.rest(anon))
    check("test rows removed", status in (200, 204), f"HTTP {status}")
    print(
        f"  NOTE  two test users remain in Authentication -> Users "
        f"({alice.email}, {bob.email}). Delete them there; the anon key cannot."
    )

    print("\n" + "=" * 56)
    print(f"  {passed} passed, {failed} failed")
    print("=" * 56)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
