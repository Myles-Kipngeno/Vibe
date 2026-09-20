"""Feedback only earns its place in the prompt when it says something true.

The risk this module carries is not that it fails loudly -- it is that it reads
a preference into four rejections that were simply four weak suggestions, and
then quietly steers every reply afterwards. So most of these tests are about
staying silent.

All names and messages here are fictional.
"""

from __future__ import annotations

from app.core.feedback_signal import (
    CONFIDENT_TOTAL,
    MIN_TOTAL,
    feedback_brief,
    summarize,
)


def fb(verdict: str, text: str, note: str = "") -> dict:
    return {
        "suggestion_id": "sug_" + str(abs(hash(text)) % 10**8),
        "suggestion_text": text,
        "verdict": verdict,
        "note": note,
        "created_at": "2026-01-01T12:00:00",
    }


def brief(entries: list[dict]) -> str:
    return feedback_brief(summarize(entries))


# --- Silence when there is nothing to say --------------------------------------


def test_no_feedback_produces_no_section():
    signal = summarize([])
    assert signal.total == 0
    assert not signal.has_signal
    assert feedback_brief(signal) == ""


def test_a_couple_of_verdicts_is_not_a_pattern():
    """Below the floor it says nothing at all, however tempting the data looks."""
    entries = [fb("rejected", "Long winded reply number one here"),
               fb("rejected", "Another long winded reply here")]
    assert len(entries) < MIN_TOTAL
    assert brief(entries) == ""


def test_one_sided_history_reports_no_measured_pattern():
    """Four rejections and nothing kept cannot tell us what he *does* want."""
    entries = [fb("rejected", f"Some rejected suggestion number {i}") for i in range(4)]
    signal = summarize(entries)
    assert signal.patterns == []
    # It still shows him what he threw out -- that much is fact.
    assert signal.rejected_examples
    assert "threw out" in feedback_brief(signal)


def test_unknown_verdict_is_ignored_rather_than_guessed_at():
    entries = [fb("maybe", "who knows"), fb("used", "sawa")]
    signal = summarize(entries)
    assert signal.total == 1
    assert signal.used == 1


# --- Measured patterns ---------------------------------------------------------


def test_it_notices_he_keeps_the_shorter_ones():
    kept = [fb("used", "Haha sawa"), fb("used", "Niaje, poa?"), fb("edited", "Tuonane")]
    tossed = [
        fb("rejected",
           "I was thinking about what you said earlier and it really stayed with "
           "me the whole afternoon honestly"),
        fb("rejected",
           "That sounds like it was a genuinely difficult situation to deal with "
           "and I hope you are doing alright now"),
    ]
    signal = summarize(kept + tossed)
    assert any("shorter" in p for p in signal.patterns)


def test_it_notices_rejected_suggestions_carry_emojis():
    kept = [fb("used", "sawa tuonane kesho"), fb("used", "poa sana bro")]
    tossed = [fb("rejected", "sawa tuonane kesho 😄🔥"), fb("rejected", "poa sana 😍✨")]
    patterns = summarize(kept + tossed).patterns
    assert any("emoji" in p.lower() for p in patterns)


def test_it_notices_he_rejects_the_ones_ending_in_a_question():
    kept = [fb("used", "Haha that is actually wild"), fb("used", "Sawa nimekuget")]
    tossed = [fb("rejected", "So what did you end up doing after that?"),
              fb("rejected", "And how did that make you feel about it?")]
    patterns = summarize(kept + tossed).patterns
    assert any("question" in p.lower() for p in patterns)


def test_a_difference_too_small_to_mean_anything_is_not_reported():
    """Nearly identical piles must produce no claim about his taste."""
    kept = [fb("used", "sawa tuonane kesho"), fb("used", "poa sana bro")]
    tossed = [fb("rejected", "sawa tuonane leo"), fb("rejected", "poa sana rafiki")]
    assert summarize(kept + tossed).patterns == []


# --- His own words outrank our summary -----------------------------------------


def test_his_note_is_quoted_verbatim():
    entries = [
        fb("rejected", "That sounds really tough", note="too soft, I don't talk like that"),
        fb("used", "Pole sana"),
        fb("used", "Sawa"),
        fb("rejected", "I am always here for you"),
    ]
    text = brief(entries)
    assert "too soft, I don't talk like that" in text
    assert "his own words" in text


def test_rejected_examples_are_marked_do_not_reword():
    entries = [fb("rejected", "Hey beautiful, how was your day?"),
               fb("rejected", "Good morning gorgeous"),
               fb("used", "Niaje"),
               fb("used", "Morning")]
    text = brief(entries)
    assert "Hey beautiful, how was your day?" in text
    assert "Do not reword" in text


# --- Honesty about sample size -------------------------------------------------


def test_a_small_sample_is_flagged_as_a_weak_hint():
    entries = [fb("rejected", "one"), fb("rejected", "two"),
               fb("used", "three"), fb("used", "four")]
    text = brief(entries)
    assert "small sample" in text
    assert "weak hint" in text


def test_the_hedge_disappears_once_there_is_real_history():
    entries = [fb("used", f"kept message {i}") for i in range(CONFIDENT_TOTAL)]
    entries += [fb("rejected", f"tossed message {i}") for i in range(CONFIDENT_TOTAL)]
    assert "small sample" not in brief(entries)


def test_counts_are_reported_exactly():
    entries = [fb("used", "a"), fb("used", "b"), fb("edited", "c"), fb("rejected", "d")]
    signal = summarize(entries)
    assert (signal.used, signal.edited, signal.rejected) == (2, 1, 1)
    assert signal.kept == 3
    assert "2 sent as written, 1 sent after editing, 1 thrown out" in feedback_brief(signal)


# --- Bounded, whatever he throws at it -----------------------------------------


def test_a_long_history_does_not_grow_the_prompt_without_limit():
    entries = [fb("rejected", f"rejected suggestion number {i} " + "x" * 300)
               for i in range(200)]
    entries += [fb("used", f"kept suggestion number {i}") for i in range(200)]
    signal = summarize(entries)
    assert len(signal.rejected_examples) <= 4
    assert len(signal.used_examples) <= 4
    assert all(len(t) <= 140 for t in signal.rejected_examples)
    assert len(feedback_brief(signal)) < 2000


# --- One suggestion, one verdict ----------------------------------------------


def stamped(entry: dict, when: str) -> dict:
    return {**entry, "created_at": when}


def test_judging_the_same_suggestion_twice_counts_once():
    """Copying a suggestion twice is one judgement, not two."""
    once = fb("used", "Niaje, umeamkaje?")
    signal = summarize([once, dict(once)])
    assert signal.total == 1


def test_his_later_word_on_a_suggestion_replaces_the_earlier_one():
    """Reject it, then say why: the reason must survive and not double-count."""
    first = stamped(fb("rejected", "Hey beautiful"), "2026-01-01T10:00:00")
    second = stamped(
        {**first, "note": "far too corny"}, "2026-01-01T10:00:30"
    )
    signal = summarize([first, second])
    assert signal.total == 1
    assert signal.rejected == 1
    assert any("far too corny" in n for n in signal.notes)


def test_a_changed_verdict_takes_the_latest():
    early = stamped(fb("rejected", "Pole sana"), "2026-01-01T10:00:00")
    later = stamped({**early, "verdict": "used"}, "2026-01-02T10:00:00")
    signal = summarize([early, later])
    assert (signal.used, signal.rejected) == (1, 0)


def test_newest_first_storage_is_read_in_the_same_order_as_oldest_first():
    """The two stores disagree on order; the reading must not.

    The Supabase store returns `created_at.desc` and the local store appends.
    Handed the same history both ways, the recent examples quoted back have to
    be the same ones, or accounts mode silently quotes his oldest verdicts.
    """
    history = [
        stamped(fb("rejected", f"rejected suggestion {i}"), f"2026-01-{i + 1:02d}T10:00:00")
        for i in range(6)
    ] + [
        stamped(fb("used", f"kept suggestion {i}"), f"2026-01-{i + 10:02d}T10:00:00")
        for i in range(6)
    ]
    oldest_first = summarize(history)
    newest_first = summarize(list(reversed(history)))
    assert oldest_first.rejected_examples == newest_first.rejected_examples
    assert oldest_first.used_examples == newest_first.used_examples
    # And "recent" must really mean recent.
    assert "rejected suggestion 5" in " ".join(oldest_first.rejected_examples)
    assert "rejected suggestion 0" not in " ".join(oldest_first.rejected_examples)


# --- The wiring: feedback must actually reach the model ------------------------


def test_feedback_posted_to_the_api_reaches_the_generation_prompt(client, monkeypatch):
    """The regression this whole feature exists to prevent.

    Before this, verdicts were stored faithfully and then read by nobody. The
    test captures the prompt the provider is handed and looks for them in it.
    """
    from app.providers.mock import MockProvider

    captured: list[str] = []
    original = MockProvider.generate

    def spy(self, system, user, schema, context=None):
        captured.append(user)
        return original(self, system, user, schema, context)

    monkeypatch.setattr(MockProvider, "generate", spy)

    judged = [
        ("rejected", "Hey beautiful, how was your day gorgeous?", "way too corny"),
        ("rejected", "Good morning beautiful, thinking of you", ""),
        ("used", "Niaje, umeamkaje?", ""),
        ("used", "Poa sana", ""),
    ]
    for verdict, text, note in judged:
        assert (
            client.post(
                "/api/conversation/feedback",
                json={
                    "suggestion_id": "sug_" + str(abs(hash(text)) % 10**8),
                    "suggestion_text": text,
                    "verdict": verdict,
                    "note": note,
                },
            ).status_code
            == 204
        )

    body = client.post(
        "/api/conversation/suggest",
        json={
            "messages": [
                {"speaker": "them", "text": "niaje, uko aje leo?"},
                {"speaker": "me", "text": "poa sana, wewe je?"},
            ],
            "goal": "keep_flowing",
        },
    ).json()
    assert body["blocked"] is False

    assert captured, "the provider was never called"
    prompt = captured[-1]
    assert "How he has judged your past suggestions" in prompt
    assert "way too corny" in prompt
    assert "Hey beautiful, how was your day gorgeous?" in prompt
    assert "4 judged" in prompt


def test_a_fresh_account_gets_no_feedback_section(client, monkeypatch):
    """With nothing judged yet, the prompt must not carry an empty heading."""
    from app.providers.mock import MockProvider

    captured: list[str] = []
    original = MockProvider.generate

    def spy(self, system, user, schema, context=None):
        captured.append(user)
        return original(self, system, user, schema, context)

    monkeypatch.setattr(MockProvider, "generate", spy)

    client.post(
        "/api/conversation/suggest",
        json={
            "messages": [
                {"speaker": "them", "text": "niaje, uko aje leo?"},
                {"speaker": "me", "text": "poa sana, wewe je?"},
            ],
            "goal": "keep_flowing",
        },
    )
    assert captured
    assert "How he has judged your past suggestions" not in captured[-1]


def test_the_reject_then_explain_sequence_the_ui_sends(client, monkeypatch):
    """SuggestionCard posts twice: the bare rejection, then the reason.

    The card records a rejection as soon as it is clicked so the verdict is
    never lost, and posts again if he types why. That must end as one verdict
    carrying his words -- not two rejections, and not a rejection with the note
    dropped.
    """
    from app.providers.mock import MockProvider

    captured: list[str] = []
    original = MockProvider.generate

    def spy(self, system, user, schema, context=None):
        captured.append(user)
        return original(self, system, user, schema, context)

    monkeypatch.setattr(MockProvider, "generate", spy)

    def judge(sid: str, text: str, verdict: str, note: str = "") -> None:
        assert (
            client.post(
                "/api/conversation/feedback",
                json={
                    "suggestion_id": sid,
                    "suggestion_text": text,
                    "verdict": verdict,
                    "note": note,
                },
            ).status_code
            == 204
        )

    # The click, then the reason -- the same suggestion, twice.
    judge("sug_aaa", "Hey beautiful, how was your day?", "rejected")
    judge("sug_aaa", "Hey beautiful, how was your day?", "rejected", "too corny for me")
    # Enough other verdicts to clear the reporting floor.
    judge("sug_bbb", "Good morning gorgeous", "rejected")
    judge("sug_ccc", "Niaje, umeamkaje?", "used")
    judge("sug_ddd", "Poa sana", "used")

    client.post(
        "/api/conversation/suggest",
        json={
            "messages": [
                {"speaker": "them", "text": "niaje, uko aje leo?"},
                {"speaker": "me", "text": "poa sana, wewe je?"},
            ],
            "goal": "keep_flowing",
        },
    )

    prompt = captured[-1]
    assert "too corny for me" in prompt
    # Four suggestions judged, not five: the reason replaced the bare rejection.
    assert "4 judged" in prompt
