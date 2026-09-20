"""How much Sheng he writes, measured rather than guessed.

This number decides how much Sheng the generator writes back, so both ways of
being wrong are expensive: read a plain-English texter as heavy Sheng and the
app answers him in slang he does not use, read a Sheng texter as English and it
answers him like a stranger.

All example messages here are fictional.
"""

from __future__ import annotations

from app.core.style_profile import style_brief
from app.core.textstats import looks_like_bantu_verb, sheng_ratio

PLAIN_ENGLISH = [
    "Hey, how was your day?",
    "I was thinking about that movie",
    "Sounds good to me",
]
ADMIN_ENGLISH = [
    "Did you fill the form yet?",
    "The base rate is fine",
    "I will check the form tomorrow",
]
LIGHT_MIX = ["Niaje, how was your day?", "Was thinking about that movie sana"]
MODERATE_MIX = [
    "Niaje, poa? I was thinking about that movie",
    "Sawa, tutaonana kesho",
]
HEAVY_SHENG = ["Niaje msee, uko aje leo?", "Manze nimechoka, twende base kesho"]


# --- The two regressions this work exists to fix -------------------------------


def test_ordinary_english_is_not_read_as_sheng():
    """"Did you fill the form yet?" used to score 0.71.

    `form`, `base` and `squad` were listed as Sheng markers. They are good
    Sheng, but they are also ordinary English, and the cost of the collision
    was that a plain-English texter was read as mixing more heavily than
    someone who actually mixes -- and then written back to in slang.
    """
    assert sheng_ratio(ADMIN_ENGLISH) == 0.0
    assert sheng_ratio(PLAIN_ENGLISH) == 0.0


def test_conjugated_verbs_register_without_being_listed():
    """"Nilienda town jana" used to score 0.00 -- pure English, it said.

    None of these forms appear in any word list. They are recognised by shape,
    which is the only approach that can keep up with a language that builds
    verbs by agglutination.
    """
    assert sheng_ratio(["Nilienda town jana"]) > 0.5
    assert sheng_ratio(["Tutaonana kesho asubuhi"]) > 0.5
    assert sheng_ratio(["Anakuja saa ngapi?"]) > 0.5


# --- The verb shape ------------------------------------------------------------


def test_it_recognises_conjugated_verb_shapes():
    for word in (
        "nilienda", "tutaonana", "anakuja", "nimeshafika", "umeamka",
        "wanakuja", "nitakuja", "tulienda", "mnakuja", "nikakuja",
    ):
        assert looks_like_bantu_verb(word), word


def test_english_words_that_decompose_the_same_way_are_not_verbs():
    """`unable` is u + na + ble. The guard is what keeps it out."""
    for word in (
        "unable", "unaware", "unanimous", "america", "american", "analysis",
        "amend", "water", "manage", "tuna", "wake", "nice",
    ):
        assert not looks_like_bantu_verb(word), word


def test_a_stem_too_short_to_be_a_stem_is_rejected():
    """A prefix and a tense marker with nothing behind them is not a verb.

    `nina` and `ume` are caught on length alone; `ninama` and `watama` are long
    enough to pass that and still have only a two-letter stem, which is what
    the stem rule is actually for.
    """
    assert not looks_like_bantu_verb("nina")
    assert not looks_like_bantu_verb("ume")
    assert not looks_like_bantu_verb("ata")
    assert not looks_like_bantu_verb("ninama")
    assert not looks_like_bantu_verb("watama")


def test_plain_english_words_are_never_verbs():
    for word in ("hello", "thinking", "tomorrow", "movie", "sounds", "about"):
        assert not looks_like_bantu_verb(word), word


# --- The scale has to stay ordered ---------------------------------------------


def test_more_sheng_always_scores_higher():
    """The property that actually matters: the ordering must hold.

    Exact values can be recalibrated; if this ordering ever breaks, the
    generator is being told the wrong thing about how he writes.
    """
    scores = [
        sheng_ratio(PLAIN_ENGLISH),
        sheng_ratio(LIGHT_MIX),
        sheng_ratio(MODERATE_MIX),
        sheng_ratio(HEAVY_SHENG),
    ]
    assert scores == sorted(scores), scores
    assert len(set(scores)) == 4, f"bands are not distinguishable: {scores}"


def test_a_light_mix_does_not_saturate_the_scale():
    """One Sheng word in an English sentence is not a Sheng conversation.

    The old x4 scale-up saturated: once verb shapes were recognised it called
    a three-word message with one Swahili verb a fully Sheng conversation, and
    every band above "light" collapsed into 1.00.
    """
    assert sheng_ratio(LIGHT_MIX) < 0.5
    assert sheng_ratio(MODERATE_MIX) < 1.0


def test_empty_input_is_zero_not_a_crash():
    assert sheng_ratio([]) == 0.0
    assert sheng_ratio(["", "   "]) == 0.0


# --- What the model is told ----------------------------------------------------


def _brief_for(texts: list[str]) -> str:
    from app.core.style_profile import blank_profile

    profile = blank_profile()
    return style_brief(profile.model_copy(update={"sheng_ratio": sheng_ratio(texts)}))


def test_the_prompt_describes_each_band_differently():
    plain = _brief_for(PLAIN_ENGLISH)
    heavy = _brief_for(HEAVY_SHENG)
    assert "almost no Sheng" in plain
    assert "heavy Sheng" in heavy
    assert plain != heavy
