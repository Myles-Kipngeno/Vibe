"""The coaching guide: how to text to make her smile, and to move things forward.

Distilled, with the user, from the texting and dating videos he chose
(Chiche, Mistari Monday, Pretty Boy Meir, Zoomology, TrueCam, Jessica Os,
TSB Magazine, and the flirty-text and funny-question lists). It is written
as instructions to the model and goes into every generation prompt.

Deliberately not included, and agreed with the user: love-bombing then
withdrawing, being mean so kindness feels rare, manufacturing jealousy,
explicit sexual lines, and persisting after she is annoyed or has said no.
Those work by knocking her confidence or ignoring her, and they contradict
the hard rules in prompts.SYSTEM_PROMPT.

The reference lines show energy and shape. The model is told to adapt them
to what she actually said, never to paste them -- the whole guide points at
the same thing: a line tied to her beats any line from a video.
"""

from __future__ import annotations

GUIDE = """\
How good texting works (apply to every option):
- Tie every message to something in this chat: what she said, a story, a \
place, a joke you two have. A line that could be sent to anyone is weak.
- Short. One or two lines, no paragraphs. At most one question, and make it \
open and fun to answer, never "wyd" or "how was your day".
- Relevant beats fast: react to what she actually said before adding anything.
- Share something first, then ask ("terrible day, flat tyre 😩 hope yours \
went better?") -- it gets a real answer instead of "fine".
- Show interest and warmth, not check-ins. Affection builds; "just checking \
in" fades.
- Compliment her wit, taste, energy or something she did more than her body. \
Hang a compliment on an event ("that song you sent got me through traffic").
- Callbacks and inside jokes are gold: bring back something from earlier in \
this chat.
- Confident and light, never needy: no begging, no double-texting energy, no \
"good morning ❤️ / miss you 😢" spam. Emojis: 0-2, to show tone, never stacked.
- Bold is attractive when it is playful: an over-the-top compliment with a \
wink ("I had compliments ready but they evaporated") lands better than a \
careful one.
- When she is laughing or engaged, that is the moment to move toward a plan; \
good moods fade.
- If she says no or has someone, exit with style and warmth ("Lucky guy. \
Have a good one 😊") -- never argue, never keep pushing."""

PLAYBOOK: dict[str, str] = {
    "flirt": """\
Flirt: playful and a little bold. Exaggerate for fun, assume the attraction \
lightly, tease something she said, and let a compliment carry a twist. Build \
tension with a hook she will want to answer ("so… I had a crazy dream about \
you", "want to know what I thought when I first saw you?"). Warm, never crude.""",
    "make_her_laugh": """\
Funny: make her laugh with the conversation itself -- a callback, an absurd \
take on what she said, self-deprecating humour, a silly hypothetical ("stuck \
in a lift with one song on repeat, which one?"). Punchy, not a joke with a \
setup. Humour first, flirting optional.""",
    "playful": """\
Playful: light challenge and teasing. Turn what she said into a game or a \
dare ("prove it", "bold of you to assume", "guess the emoji next to your \
name in my phone"). Keep it clearly affectionate.""",
    "ask_out": """\
Ask her out: at least two of the options must be an actual invitation, not a \
hint. Make it specific and easy to say yes to: a real activity, ideally tied \
to something she mentioned, with a day or time ("Saturday?", "this week?"). \
Paint the picture in a few words ("there's a spot with the best mandazi in \
town"). Casual beats formal: "I'm going to X, come through" is easier to \
accept than a grand date, and a no costs nothing. Only use a place or \
activity from this chat or a generic one -- never invent shared plans.""",
    "get_to_know": """\
Deeper: go one level below small talk with an open, fun question, and share \
your own answer or a bit of yourself so it is not an interview ("okay real \
question, what's something you're excited about lately?"). Show a side of \
him that is not just jokes.""",
    "comfort": """\
Support: no flirting and no jokes unless she is joking. Short, warm, present. \
Acknowledge what happened, offer to listen or to distract her, and ask \
nothing demanding.""",
    "keep_flowing": """\
Keep it flowing: react to her last message with something real, then add \
one hook -- a callback, a small tease, or an easy question tied to her \
message. If the chat is getting dry, change the energy rather than asking \
another question.""",
    "next_day": """\
Next day: open warm and specific -- reference last night or something she \
said, not a generic "good morning".""",
    "start": """\
Opening: personal, with a reason for texting -- how you met or something she \
posted or said ("it feels great to talk to you without spilling a drink on \
you this time"). Never a bare "hey".""",
    "end_naturally": """\
Ending: warm and a little memorable, leave a small hook for next time only \
if it has been going well.""",
}

# Energy and shape only. The prompt tells the model to adapt, not paste.
REFERENCE_LINES = """\
- "You were honestly the highlight of my evening."
- "Apparently [place] was only fun because you were there. When can you make me happy again?"
- "Got great news and guess what, you're the only one I wanna tell."
- "Thinking about the story you told me. When do I get to hear the rest?"
- "What are you doing this weekend, besides seeing me?"
- "You're stealing my sleep now and it's starting to bother me 😏"
- "Someone's going to win you over someday. Why not let me try?"
- "Tell people you've been robbed of your heart."
- "March was bad, April looks great, can we go out in May?"
- "They call me the king of algebra, I'll make your ex disappear 😂"
- "Do you watch Friends? Will you be my lobster?"
- "Text so we can spam emojis, or call so I can hear you laugh?"
- "Since you love [thing she mentioned], we should do it together. Saturday?\""""


def coaching_section(goal: str) -> str:
    """The guide, the playbook for this goal, and the reference lines."""
    play = PLAYBOOK.get(goal, PLAYBOOK["keep_flowing"])
    return (
        "## Coaching\n"
        + GUIDE
        + "\n\n"
        + play
        + "\n\nReference lines -- the energy he likes. Adapt one to what she "
        "actually said if it fits; never paste one as-is, and skip them when "
        "none fits:\n"
        + REFERENCE_LINES
    )
