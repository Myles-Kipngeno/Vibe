"""Static checks on supabase/schema.sql.

These do not need a database. They guard one specific, likely mistake: adding a
table later and forgetting to give it Row Level Security or an owner policy.
That table would then be readable by every signed-in user, and nothing else in
this repo would notice -- the app would keep working perfectly.

The live behaviour of the policies is a separate question, answered by
`scripts/verify_rls.py` against a real project.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

SCHEMA = Path(__file__).resolve().parents[2] / "supabase" / "schema.sql"


@pytest.fixture(scope="module")
def sql() -> str:
    assert SCHEMA.exists(), f"schema not found at {SCHEMA}"
    return SCHEMA.read_text(encoding="utf-8")


def declared_tables(sql: str) -> set[str]:
    return set(re.findall(r"create table if not exists public\.(\w+)", sql))


def rls_enabled_tables(sql: str) -> set[str]:
    """Tables named in the `foreach` array that enables RLS."""
    block = re.search(
        r"foreach t in array array\[(.*?)\]", sql, re.DOTALL
    )
    assert block, "could not find the block that enables row level security"
    return set(re.findall(r"'(\w+)'", block.group(1)))


def policy_tables(sql: str) -> set[str]:
    return set(re.findall(r"create policy \w+ on public\.(\w+)", sql))


def test_schema_declares_the_expected_tables(sql):
    assert declared_tables(sql) >= {
        "profiles",
        "communication_preferences",
        "contact_profiles",
        "conversation_memories",
    }


def test_every_table_has_row_level_security_enabled(sql):
    missing = declared_tables(sql) - rls_enabled_tables(sql)
    assert not missing, (
        f"these tables never get RLS enabled, so any signed-in user could read "
        f"them: {sorted(missing)}"
    )


def test_every_table_has_an_owner_policy(sql):
    missing = declared_tables(sql) - policy_tables(sql)
    assert not missing, (
        f"RLS with no policy denies everyone, which breaks the app silently: "
        f"{sorted(missing)}"
    )


def test_no_policy_targets_a_table_that_does_not_exist(sql):
    stray = policy_tables(sql) - declared_tables(sql)
    assert not stray, f"policies for unknown tables: {sorted(stray)}"


def test_every_policy_checks_both_directions(sql):
    """`using` filters reads; `with check` stops writes naming another owner.

    A policy with only `using` lets a user insert a row owned by someone else.
    """
    policies = re.findall(
        r"create policy \w+ on public\.(\w+)\s*(.*?);", sql, re.DOTALL
    )
    assert policies, "no policies found"
    for table, body in policies:
        assert "using" in body, f"{table}: policy has no USING clause"
        assert "with check" in body, (
            f"{table}: policy has no WITH CHECK clause, so a user could write a "
            f"row owned by someone else"
        )


def test_every_policy_scopes_to_the_authenticated_user(sql):
    policies = re.findall(
        r"create policy \w+ on public\.(\w+)\s*(.*?);", sql, re.DOTALL
    )
    for table, body in policies:
        assert "auth.uid()" in body, (
            f"{table}: policy does not reference auth.uid(), so it is not "
            f"scoped to the caller"
        )


def test_owned_tables_default_their_owner_to_auth_uid(sql):
    """A client must never be able to choose the owner of a row it creates."""
    for table in declared_tables(sql) - {"profiles"}:
        block = re.search(
            rf"create table if not exists public\.{table} \((.*?)\n\);", sql, re.DOTALL
        )
        assert block, f"could not read the definition of {table}"
        user_id_line = [
            line for line in block.group(1).splitlines() if re.match(r"\s*user_id\s", line)
        ]
        assert user_id_line, f"{table} has no user_id column"
        assert "default auth.uid()" in user_id_line[0], (
            f"{table}.user_id has no `default auth.uid()`, so a client could "
            f"name a different owner on insert"
        )


def test_the_service_role_is_never_granted_anything(sql):
    """Any grant to service_role would route around every policy above."""
    assert "service_role" not in sql.lower()
