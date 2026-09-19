"""End-to-end coverage of the MVP flow, against the offline provider."""

from __future__ import annotations


def _messages(pairs):
    return [{"speaker": who, "text": text} for who, text in pairs]


def test_health_reports_offline_mode(client):
    body = client.get("/api/health").json()
    assert body["status"] == "ok"
    assert body["is_mock"] is True
    assert any("offline" in note.lower() for note in body["notes"])


def test_the_full_mvp_flow(client):
    # 1. Paste a conversation and label who said what.
    parsed = client.post(
        "/api/conversation/parse",
        json={
            "raw_text": "Her: Niaje\nMe: Poa sana, wewe je\nHer: Ulienda town na Randy?",
            "me_label": "Me",
            "them_label": "Her",
        },
    ).json()
    messages = parsed["messages"]
    assert [m["speaker"] for m in messages] == ["them", "me", "them"]

    # 2. Analyse it.
    analysis = client.post(
        "/api/conversation/analyze", json={"messages": messages}
    ).json()["analysis"]
    assert analysis["recommendation"] in ("continue", "stop", "wait")
    assert any(a["kind"] == "personal_context" for a in analysis["alerts"])

    # 3. Asking for replies is refused while the personal reference is unresolved.
    blocked = client.post(
        "/api/conversation/suggest",
        json={"messages": messages, "goal": "keep_flowing"},
    ).json()
    assert blocked["blocked"] is True
    assert blocked["suggestions"] == []
    assert blocked["unresolved_alerts"]

    # 4. Supply the missing context, then generation proceeds.
    key = blocked["unresolved_alerts"][0]["dedupe_key"]
    answered = client.post(
        "/api/conversation/suggest",
        json={
            "messages": messages,
            "goal": "keep_flowing",
            "supplied_context": {key: "Randy is my cousin. We went to buy a phone."},
        },
    ).json()
    assert answered["blocked"] is False
    assert answered["suggestions"]
    assert answered["is_mock"] is True

    # 5. Feedback is accepted.
    first = answered["suggestions"][0]
    assert (
        client.post(
            "/api/conversation/feedback",
            json={
                "suggestion_id": first["id"],
                "suggestion_text": first["text"],
                "verdict": "used",
            },
        ).status_code
        == 204
    )


def test_wait_returns_guidance_and_no_message(client):
    messages = _messages([("them", "haha sawa"), ("me", "so tuonane siku gani?")])
    body = client.post(
        "/api/conversation/suggest",
        json={"messages": messages, "goal": "keep_flowing", "action": "wait"},
    ).json()
    assert body["suggestions"] == []
    assert body["guidance"]


def test_a_boundary_blocks_persuasive_generation(client):
    messages = _messages([("me", "tuonane weekend?"), ("them", "I have a boyfriend")])
    body = client.post(
        "/api/conversation/suggest",
        json={"messages": messages, "goal": "flirt"},
    ).json()
    assert body["blocked"] is True
    assert "boundary" in body["blocked_reason"].lower()


def test_stop_after_a_boundary_produces_graceful_exits(client):
    messages = _messages([("me", "tuonane weekend?"), ("them", "I have a boyfriend")])
    body = client.post(
        "/api/conversation/suggest",
        json={"messages": messages, "goal": "end_naturally", "action": "stop"},
    ).json()
    assert body["blocked"] is False
    assert body["suggestions"]


def test_already_suggested_lines_are_not_repeated(client):
    messages = _messages([("me", "niaje"), ("them", "Niko poa, nimekuwa nikisoma")])
    first = client.post(
        "/api/conversation/suggest", json={"messages": messages, "goal": "playful"}
    ).json()["suggestions"]
    avoid = [s["text"] for s in first]

    second = client.post(
        "/api/conversation/suggest",
        json={"messages": messages, "goal": "playful", "avoid": avoid},
    ).json()["suggestions"]
    assert all(s["text"] not in avoid for s in second)


def test_unknown_goal_is_rejected(client):
    body = client.post(
        "/api/conversation/suggest",
        json={"messages": _messages([("them", "hi")]), "goal": "make_her_fall_in_love"},
    )
    assert body.status_code == 422


# --- Contacts, memory isolation and privacy -----------------------------------


def test_memory_is_isolated_between_contacts(client):
    ann = client.post("/api/contacts", json={"name": "Ann"}).json()
    bea = client.post("/api/contacts", json={"name": "Bea"}).json()

    client.post(
        f"/api/contacts/{ann['id']}/memories",
        json={"key": "person:randy", "value": "Her cousin"},
    )

    assert client.get(f"/api/contacts/{ann['id']}").json()["memories"]
    assert client.get(f"/api/contacts/{bea['id']}").json()["memories"] == []


def test_stored_context_stops_the_question_being_asked_again(client):
    ann = client.post("/api/contacts", json={"name": "Ann"}).json()
    messages = _messages([("me", "sasa"), ("them", "Ulienda town na Randy?")])

    blocked = client.post(
        "/api/conversation/suggest",
        json={"messages": messages, "goal": "keep_flowing", "contact_id": ann["id"]},
    ).json()
    key = blocked["unresolved_alerts"][0]["dedupe_key"]

    client.post(
        f"/api/contacts/{ann['id']}/memories",
        json={"key": key, "value": "Randy is my cousin"},
    )

    again = client.post(
        "/api/conversation/suggest",
        json={"messages": messages, "goal": "keep_flowing", "contact_id": ann["id"]},
    ).json()
    assert again["blocked"] is False


def test_a_memory_can_be_deleted(client):
    ann = client.post("/api/contacts", json={"name": "Ann"}).json()
    memory = client.post(
        f"/api/contacts/{ann['id']}/memories",
        json={"key": "person:randy", "value": "Her cousin"},
    ).json()

    assert (
        client.delete(f"/api/contacts/{ann['id']}/memories/{memory['id']}").status_code
        == 204
    )
    assert client.get(f"/api/contacts/{ann['id']}").json()["memories"] == []


def test_a_stated_boundary_is_remembered_for_the_contact(client):
    ann = client.post("/api/contacts", json={"name": "Ann"}).json()
    client.post(
        "/api/conversation/analyze",
        json={
            "messages": _messages([("me", "hey"), ("them", "I have a boyfriend")]),
            "contact_id": ann["id"],
        },
    )
    assert client.get(f"/api/contacts/{ann['id']}").json()["stated_boundaries"]


def test_style_can_be_learned_edited_and_reset(client):
    learned = client.post(
        "/api/style/learn",
        json=_messages([("me", "niaje manze"), ("me", "poa sana bana")]),
    ).json()
    assert learned["learned_from_messages"] == 2

    edited = client.patch("/api/style", json={"humor_style": "dry and sarcastic"}).json()
    assert edited["humor_style"] == "dry and sarcastic"

    reset = client.post("/api/style/reset").json()
    assert reset["learned_from_messages"] == 0


def test_learning_requires_messages_marked_as_the_users(client):
    response = client.post("/api/style/learn", json=_messages([("them", "hello")]))
    assert response.status_code == 422


def test_deleting_all_data_clears_everything(client):
    client.post("/api/contacts", json={"name": "Ann"})
    client.post("/api/style/learn", json=_messages([("me", "niaje manze")]))

    assert client.delete("/api/data").status_code == 204
    assert client.get("/api/contacts").json() == []
    assert client.get("/api/style").json()["learned_from_messages"] == 0
