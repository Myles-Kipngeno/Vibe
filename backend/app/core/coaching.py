"""The coaching guide: how to text to make her smile, and to move things forward.

Distilled from the texting and dating videos the user chose (Chiche, Mistari
Monday, Pretty Boy Meir, Zoomology, TrueCam, Jessica Os, TSB Magazine, and
the flirty-text and funny-question lists). It is written as instructions to
the model and goes into every generation prompt.

The user asked for everything to be kept, including mean and spicy material,
and most of it is: roasts and clap-backs (the Savage goal), innuendo (the
Spicy goal), going cold when she is rude, asking her for a picture, inviting
again after "I'm busy", pulling back when he wants space, and a reaction for
every mood she shows.

Four things are not included, and the user was told so plainly rather than
it being presented as agreed:
- pushing on after she says no or that she has someone (her consent, and the
  hard rule in prompts.SYSTEM_PROMPT);
- scripts engineered to make her insecure or hooked: love-bombing then
  withdrawing, manufacturing jealousy, negging as a technique;
- fully explicit sexual messages (the product's own line; Spicy goes up to it);
- one line from a video that jokes about the slave trade.

The reference lines show energy and shape. The model adapts them to what she
actually said and never pastes them: a line tied to her beats any line from
a video.
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
- Compliment her wit, taste, energy or something she did as well as her looks. \
Hang a compliment on an event ("that song you sent got me through traffic").
- Callbacks and inside jokes are gold: bring back something from earlier in \
this chat.
- Sheng goes where it would naturally fall inside the sentence ("legs day \
imekuua? I'll carry you"), never bolted on as an opener. Do not start a reply \
with a filler greeting or tag -- "Mambo,", "Sawa,", "Cheki,", "Manze," -- \
and never greet ("Mambo", "Niaje") in the middle of a chat.
- Confident and light, never needy: no begging, no "good morning ❤️ / miss \
you 😢" spam. Emojis: 0-2, to show tone, never stacked.
- Have your own life. After a warm streak it is fine to be less available -- \
reply when he genuinely has time, and let her come to him sometimes.
- Bold is attractive when it is playful: an over-the-top compliment with a \
wink ("I had compliments ready but they evaporated") lands better than a \
careful one.
- When she is laughing or engaged, that is the moment to move toward a plan; \
good moods fade.
- "I'm busy" or "not today" is not a no: accept it lightly and invite again \
another day, casually. A clear no, "stop", or "I have someone" is the end -- \
exit with style ("Lucky guy. Have a good one 😊"), never push."""

WHEN_SHE = """\
Match his reply to her mood:
- Rude or mean for no reason: do not grovel. Clap back with a playful jab, or \
go short and cool ("okay 😂") and let her come back.
- Teasing or roasting him: roast back harder, with a smile. Never get defensive.
- Dry replies ("ok", "lol", "hmm"): stop asking questions. Change the energy \
with a bold tease or a callback -- or pull back and let the chat rest.
- Slow to reply or left him on read: no double-texting, no "?". Leave it, or \
one light message later that stands on its own.
- Jealous ("who's she?"): tease her about it or reassure lightly -- never \
make her anxious on purpose.
- Upset with him: own his part in one short line, no speeches, no excuses.
- Sad or stressed: drop the flirting, be warm and present.
- Excited or happy: match the energy, hype her up.
- Flirting back or getting suggestive: meet her level, one step at a time."""

PLAYBOOK: dict[str, str] = {
    "flirt": """\
Flirt: every option must carry attraction or tension -- a reply that a friend, \
a brother or a coach could send is a failed option ("rest up", "nap like a \
champ", "impressive hustle" all fail). Ways to do it:
- Turn her situation into something about the two of you: legs day -> "I'll \
carry you later 😜"; she's tired -> "come here, I'll fix that".
- Tease and compliment in the same line, so it stings a little and flatters \
("you can't dance but you still had everyone watching you").
- Assume the attraction: "you're trying to make me miss you, aren't you?"
- Leave a hook she has to answer: "want to know what I thought when I first \
saw you?", "so… I had a crazy dream about you".
- When she flirts, raise it one notch, don't just agree: she asks "just that \
photo? 😏" -> "that one's my favourite, but I'm open to more evidence 😏".
Confident, warm, a little cheeky. One emoji at most per option.""",
    "make_her_laugh": """\
Funny: make her laugh with the conversation itself -- a callback, an absurd \
take on what she said, self-deprecating humour, a silly hypothetical ("stuck \
in a lift with one song on repeat, which one?"). Punchy, not a joke with a \
setup. Humour first, flirting optional.""",
    "playful": """\
Playful: light challenge and teasing. Turn what she said into a game or a \
dare ("prove it", "bold of you to assume", "guess the emoji next to your \
name in my phone"). Keep it clearly affectionate.""",
    "savage": """\
Savage: roast, sass and clap back. Mean is allowed when it is funny: mock \
what she said, call out her excuse, act unbothered, flip her tease back on \
her twice as hard. Aim at what she said or did, not at her looks or her \
worth, and keep one wink in it so she laughs instead of leaving.""",
    "spicy": """\
Spicy: suggestive, teasing and heated -- innuendo, double meanings, \
"you're dangerous", hinting at what he would do. Go exactly as far as she \
has already gone and one step more at most; if she has not flirted back \
yet, keep it to a bold tease. Suggestive, never explicit.""",
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
- "So… I had a crazy dream about you."
- "Missing that face. Send me a picture 😏"
- "You're stealing my sleep now and it's starting to bother me 😏"
- "Someone's going to win you over someday. Why not let me try?"
- "Tell people you've been robbed of your heart."
- "Can I call you mine?"
- "March was bad, April looks great, can we go out in May?"
- "They call me the king of algebra, I'll make your ex disappear 😂"
- "Do you watch Friends? Will you be my lobster?"
- "Text so we can spam emojis, or call so I can hear you laugh?"
- "Since you love [thing she mentioned], we should do it together. Saturday?\""""

# Only for Spicy, and only as far as she has gone.
SPICY_LINES = """\
- "I'm rushing too… to get you home 😏"
- "I'd kiss you in the rain so you get wet twice 😌"
- "It's not Christmas, but if you jingle my bells…"
- "If you ever want to get rid of extra sugar, I know the most natural way 😏"
- "I have some skills that can't be talked about, only performed."
- "I have a few fantasies about you. They all start with a candlelight dinner.\""""


def coaching_section(goal: str) -> str:
    """The guide, how to react to her mood, this goal's playbook, and the lines."""
    play = PLAYBOOK.get(goal, PLAYBOOK["keep_flowing"])
    lines = REFERENCE_LINES + ("\n" + SPICY_LINES if goal == "spicy" else "")
    return (
        "## Coaching\n"
        + GUIDE
        + "\n\n"
        + WHEN_SHE
        + "\n\n"
        + play
        + "\n\nReference lines -- the energy he likes. Adapt one to what she "
        "actually said if it fits; never paste one as-is, and skip them when "
        "none fits:\n"
        + lines
    )
