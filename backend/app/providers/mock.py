"""Offline provider used when no model API key is configured.

This is a **template engine, not a language model**. Every response it produces
is assembled from fixed patterns, and every API response carrying its output is
flagged `is_mock: true` so the UI can label it. It exists so the whole product --
parsing, analysis, alerts, the Continue/Stop/Wait flow -- can be built and tested
without spending money or leaking conversations to a third party.

It still respects the user's style profile, the selected goal and any context the
user supplied, which makes it a useful fixture for tests.
"""

from __future__ import annotations

import random
from typing import TypeVar

from pydantic import BaseModel

from .base import GeneratedSuggestion, GenerationResult, LLMProvider, ProviderError

T = TypeVar("T", bound=BaseModel)

# Each goal maps to (sheng-leaning template, english-leaning template, approach).
_TEMPLATES: dict[str, list[tuple[str, str, str]]] = {
    "start": [
        ("Niaje {name}, si uko poa leo?", "Hey {name}, how has your day been?", "warm opener"),
        ("Sasa {name} 😄 nimekuwa nikijiuliza unaendeleaje", "Hey {name} 😄 you crossed my mind, how are you doing?", "light curiosity"),
        ("Aiseh {name}, sema story", "Hey {name}, what have you been up to?", "casual check-in"),
    ],
    "keep_flowing": [
        ("Ai si uniambie zaidi, hiyo inanifanya nishangae", "Wait tell me more, that actually surprises me", "invite detail"),
        ("Haha sawa, so ilikuwaje after hapo?", "Haha okay, so what happened after that?", "follow the thread"),
        ("Hiyo ni interesting, mimi pia huwa na hiyo case", "That is interesting, I get that too honestly", "relate back"),
    ],
    "make_her_laugh": [
        ("Wueh, hiyo story imenimaliza 😂", "Okay that ended me 😂", "react big"),
        ("Si wewe ni mtu wa noma, umefanya nicheke peke yangu hapa", "You are trouble, I just laughed alone here", "self-deprecating"),
        ("Nimekufa 😂 sema tu ulikuwa unajaribu kunimaliza", "I am done 😂 just admit you were trying to finish me", "playful accusation"),
    ],
    "playful": [
        ("Wewe ni mtu wa maneno mengi, naona", "You talk a big game, I see", "light teasing"),
        ("Hiyo ni excuse, si uongeze", "That is an excuse and you know it", "call it out gently"),
        ("Haha sawa, nitakubelieve for now 😄", "Haha fine, I will believe you for now 😄", "mock concession"),
    ],
    "flirt": [
        ("Si unajua unaongea vizuri, hiyo ni shida", "You have a way of talking that is honestly a problem", "specific compliment"),
        ("Nimekuwa nikienjoy hii convo more than nilitarajia", "I am enjoying this more than I expected to", "honest and direct"),
        ("Unafaa kujua conversations zako ziko interesting", "You should know your conversations are genuinely interesting", "sincere note"),
    ],
    "get_to_know": [
        ("Sema, ni nini hukufanya siku ikue poa?", "Tell me, what actually makes a day good for you?", "open question"),
        ("Kitu gani watu huwa hawajui kukuhusu?", "What is something people usually get wrong about you?", "curious question"),
        ("Unapenda kufanya nini ukiwa free kabisa?", "What do you do with a completely free day?", "low-pressure question"),
    ],
    "answer_personal": [
        ("{context}", "{context}", "answer using the context you gave me"),
    ],
    "ask_out": [
        ("Si tuonane this weekend, kahawa au kitu?", "Let us actually meet this weekend, coffee or something?", "clear and easy to decline"),
        ("Nikikuita tuonane siku moja utakuja?", "If I asked you to hang out one of these days, would you come?", "asks permission first"),
        ("Kuna mahali napenda, ningependa tuende pamoja", "There is a place I like, I would like to take you there", "concrete invitation"),
    ],
    "comfort": [
        ("Pole sana, hiyo ni ngumu. Niko hapa ukitaka kuongea", "I am sorry, that is genuinely hard. I am here if you want to talk", "acknowledge, do not fix"),
        ("Hiyo ni mzigo. Usijilazimishe kuwa sawa leo", "That is a lot to carry. You do not have to be fine today", "give permission"),
        ("Sikuizi umekuwa ukibeba mengi. Nakusikia", "You have been carrying a lot lately. I hear you", "simple presence"),
    ],
    "end_naturally": [
        ("Sawa, wacha nikuache uendelee. Tuongee baadaye 😊", "Alright, let me let you get on with it. Talk later 😊", "clean exit"),
        ("Hii convo imekuwa poa. Tuongee tena", "This was a good one. Let us pick it up again", "warm close"),
        ("Niko na kitu nafanya, lakini tuongee baadaye", "I have something to handle, but let us talk later", "honest close"),
    ],
    "next_day": [
        ("Morning 😊 ulilala poa?", "Morning 😊 hope you slept well", "simple and low pressure"),
        ("Niaje, umeamka aje leo?", "Hey, how did you wake up today?", "light check-in"),
        ("Asubuhi njema. Siku iwe poa", "Good morning. Have a good one", "brief and warm"),
    ],
}

_GOODNIGHT: list[tuple[str, str, str]] = [
    ("😂 Hii convo ilikuwa poa sana. Lala poa, goodnight 😊", "😂 This convo was actually too good. Go get some sleep, goodnight 😊", "warm sign-off"),
    ("Sawa, nakuacha ulale. Usiku mwema", "Alright, I will let you sleep. Goodnight", "short sign-off"),
    ("Goodnight, tuongee kesho 😊", "Goodnight, talk tomorrow 😊", "leaves the door open"),
]

_STOP: list[tuple[str, str, str]] = [
    ("Sawa, nimeelewa. Nitakuacha 😊", "Understood, I will leave it there 😊", "accepts it without argument"),
    ("Poa, asante kwa kuwa mkweli. All the best", "Cool, thanks for being straight with me. All the best", "graceful exit"),
    ("Sawa kabisa, hakuna shida", "That is completely fine, no pressure at all", "no guilt-tripping"),
]


class MockProvider(LLMProvider):
    name = "mock"
    model = None
    is_mock = True

    def __init__(self, seed: int | None = None) -> None:
        self._rng = random.Random(seed)

    def generate(
        self, system: str, user: str, schema: type[T], context: dict | None = None
    ) -> T:
        if schema is not GenerationResult:
            raise ProviderError(
                f"The offline mock provider cannot produce {schema.__name__}. "
                "Configure a real provider in .env."
            )
        context = context or {}
        goal = str(context.get("goal", "keep_flowing"))
        action = context.get("action")
        name = str(context.get("contact_name") or "").split(" ")[0] or "you"
        sheng = float(context.get("sheng_ratio", 0.4))
        avoid = {t.strip().lower() for t in context.get("avoid", [])}
        supplied = context.get("supplied_context") or {}

        if action == "stop":
            pool = _STOP
        elif context.get("goodnight"):
            pool = _GOODNIGHT
        else:
            pool = _TEMPLATES.get(goal, _TEMPLATES["keep_flowing"])

        suggestions: list[GeneratedSuggestion] = []
        for sheng_text, english_text, approach in pool:
            text = sheng_text if sheng >= 0.35 else english_text
            if "{context}" in text:
                answer = next(iter(supplied.values()), None)
                if not answer:
                    continue
                text = str(answer)
            text = text.replace("{name}", name)
            if text.strip().lower() in avoid:
                continue
            suggestions.append(
                GeneratedSuggestion(
                    text=text,
                    rationale=(
                        "Offline template. It matches your goal and language mix, but "
                        "it is not model-generated and does not read the conversation."
                    ),
                    approach=approach,
                )
            )

        if not suggestions:
            suggestions = [
                GeneratedSuggestion(
                    text="(No offline template left for this goal -- try another goal, "
                    "or configure a model provider.)",
                    rationale="Every template for this goal was already shown.",
                    approach="fallback",
                )
            ]
        return GenerationResult(suggestions=suggestions[:3])  # type: ignore[return-value]
