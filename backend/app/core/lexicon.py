"""Language resources for Kenyan Sheng + English texting.

This module holds *data*, not logic. It is deliberately separate so the word
lists can grow (or be swapped for a learned resource) without touching the
detectors that consume them.

Everything here is lowercase and compared case-insensitively.
"""

from __future__ import annotations

# --- Sheng / Kenyan-English markers ------------------------------------------
# Used to estimate how much Sheng a person actually writes. These are words that
# are rare in plain English texting, so a hit is strong evidence of Sheng.
SHENG_MARKERS: frozenset[str] = frozenset(
    {
        # greetings / openers
        "niaje", "sasa", "sema", "vipi", "mambo", "ukoaje", "unaendeleaje",
        "mzuka", "aiseh", "aisee", "eish", "ala",
        # people
        "msee", "wasee", "buda", "budaa", "mzee", "chali", "dame", "demu", "manzi",
        "mrembo", "siste", "brathe", "mathe", "fathe", "boyz", "squad",
        "beshte", "jamaa", "wadhii",
        # states / reactions
        "poa", "fiti", "freshi", "noma", "sawa", "safi",
        "bonoko", "ngori", "kali", "form",
        # verbs / actions
        "kuja", "twende", "tuko", "niko", "uko", "nakam", "nakuja", "nimefika",
        "kudunda", "kuchill", "kuhepa", "kuenjoy", "kutoa", "kuomoka",
        "nimeamka", "nimelala", "umelala", "umeamka", "nishafika",
        # money / places
        "doo", "ganji", "chapaa", "mbao", "ngiri", "keja", "mtaa", "tao",
        "ploti", "base",
        # discourse glue
        "manze", "bana", "ati", "eti", "wacha", "acha", "yaani", "kwanza",
        "kabisa", "buree", "tena", "basi", "haya", "sindio", "aje",
        # laughter
        "nimekufa", "wueh", "wueeh",
    }
)

# Kiswahili words kept deliberately small for now (product decision: Sheng +
# English first, Kiswahili very light).
SWAHILI_MARKERS: frozenset[str] = frozenset(
    {
        "habari", "asante", "karibu", "pole", "samahani", "tafadhali", "nzuri",
        "sawasawa", "ndio", "hapana", "kwaheri", "usiku", "mwema", "lala",
    }
)

LAUGH_TOKENS: frozenset[str] = frozenset(
    {"haha", "hahaha", "hehe", "lol", "lmao", "rotfl", "kekeke"}
)

LAUGH_EMOJI: frozenset[str] = frozenset({"\U0001F602", "\U0001F923", "\U0001F639"})

# --- Relationship / person nouns ---------------------------------------------
# A question containing one of these very often refers to a person only the two
# texters know, which is exactly when we must not invent details.
RELATIONSHIP_NOUNS: frozenset[str] = frozenset(
    {
        "sister", "sis", "brother", "bro", "mum", "mom", "mother", "dad", "father",
        "parents", "cousin", "aunt", "uncle", "grandma", "granny", "shosh",
        "boss", "landlord", "landlady", "roommate", "roomie", "neighbour", "neighbor",
        "bestie", "bestfriend", "friend", "ex", "boyfriend", "girlfriend",
        "colleague", "classmate", "lecturer", "teacher", "supervisor",
        "siste", "brathe", "mathe", "fathe", "beshte", "jamaa", "msee", "dame",
        "chali", "manzi", "buda", "squad", "family",
    }
)

# --- Personal-reference trigger phrases ---------------------------------------
# Phrases that point at a shared event/fact the assistant has no way to know.
# Kept as substrings; matched against a normalised (lowercased) message.
SHARED_EVENT_PHRASES: tuple[str, ...] = (
    # English
    "remember when", "remember that", "remember what", "remember how",
    "how did it go", "howd it go", "how did that go", "how was it",
    "did you tell", "did you talk to", "did you ask", "did you go",
    "what happened", "what did they say", "what did he say", "what did she say",
    "so did she", "so did he", "so did they", "did she finally", "did he finally",
    "you said you", "you told me", "you promised", "like you said",
    "how did your", "how is your", "hows your",
    "the thing we", "that thing", "our plan", "the plan we",
    "still on for", "are we still", "you know that",
    # Sheng / Swahili-flavoured
    "ulienda", "ulimwambia", "ulisema", "ulifika", "ulionana", "uliongea",
    "ilienda aje", "ilikuaje", "ilikuwaje", "imekuaje", "ile story", "ile kitu",
    "ile siku", "ile place", "ile maneno", "ile stori", "kule kwa", "ulipata",
    "mlienda", "mliongea", "alikwambia", "alisema", "alikuja",
)

# Phrases that signal the other person is asking for a photo of the user.
PICTURE_REQUEST_PHRASES: tuple[str, ...] = (
    "send me a pic", "send a pic", "send pic", "send me pic", "send me a photo",
    "send a photo", "send me your pic", "send me your photo", "send me a selfie",
    "send selfie", "pic ya", "picha", "nitumie pic", "nitumie picha",
    "let me see you", "how do you look", "show me your face", "unaonekanaje",
    "wacha nikuone", "dm me a pic", "send nudes", "tuma pic",
)

# --- Boundary / disinterest signals -------------------------------------------
# Hard signals: the other person has clearly said no. The product must never try
# to route around these.
HARD_BOUNDARY_PHRASES: tuple[str, ...] = (
    "not interested", "im not interested",
    "leave me alone", "stop texting me", "stop messaging me", "dont text me",
    "please stop", "stop it", "i said no", "no means no",
    "im uncomfortable", "this makes me uncomfortable",
    "youre crossing a line", "back off",
    "i have a boyfriend", "i have a bf", "im taken",
    "im married", "im engaged",
    "were just friends", "just friends",
    "i dont like you like that",
    "sitaki", "niache", "usinitext", "acha kunitext",
    "niko na boyfriend", "niko na bf", "nina boyfriend", "niko na mtu",
    "niko committed", "tuachane",
)

# Softer signals: might be playful, might be real. We surface these as an alert
# and ask the user; we never decide on our own that she "did not mean it".
AMBIGUOUS_SIGNAL_PHRASES: tuple[str, ...] = (
    "we'll see", "well see", "tutaona", "si tuone", "labda",
    "im busy", "niko busy", "niko job", "niko kazi",
    "some other time", "another day",
    "i dont know", "sijui", "sijajua",
)

# --- Conversation-closing signals ---------------------------------------------
WIND_DOWN_PHRASES: tuple[str, ...] = (
    "goodnight", "good night", "nighty", "sleep well", "sweet dreams",
    "im sleeping", "going to sleep", "off to bed", "bed time",
    "nalala", "nakwenda kulala", "nimechoka", "usiku mwema", "lala salama",
    "talk tomorrow", "tutaongea kesho", "tuongee kesho", "ttyl", "gtg",
    "got to go", "gotta go", "i have to go", "naenda", "nimeenda",
)

MORNING_PHRASES: tuple[str, ...] = (
    "good morning", "goodmorning", "habari ya asubuhi", "umeamkaje",
    "umeamka", "morning",
)

# --- Low-information replies (engagement signal) -------------------------------
LOW_EFFORT_REPLIES: frozenset[str] = frozenset(
    {
        "k", "kk", "ok", "okay", "oky", "sawa", "poa", "cool", "nice", "lol",
        "haha", "hmm", "hmmm", "yeah", "yea", "ya", "yep", "no", "nope", "sure",
        "aha", "oh", "ohh", "eish", "true", "fr", "yh", "mmh", "mm",
    }
)

# --- Stop-list for capitalised-name detection ----------------------------------
# Words that are frequently capitalised mid-sentence but are not proper names.
NAME_STOPWORDS: frozenset[str] = frozenset(
    {
        "i", "im", "ill", "ive", "ok", "okay", "lol", "omg",
        "god", "monday", "tuesday", "wednesday", "thursday", "friday",
        "saturday", "sunday", "january", "february", "march", "april", "may",
        "june", "july", "august", "september", "october", "november", "december",
        "hi", "hey", "hello", "yes", "no", "sure", "thanks", "thank", "please",
        "the", "a", "an", "and", "but", "so", "then", "well", "why", "what",
        "when", "where", "who", "how", "did", "do", "does", "is", "are", "was",
        "were", "you", "your", "me", "my", "we", "us", "they", "he", "she", "it",
        "wow", "haha", "eish", "ah", "oh", "nah", "yeah", "yep", "bro", "sis",
        "sorry", "morning", "night", "today", "tomorrow", "yesterday",
        # Sheng / Kiswahili words that routinely get capitalised mid-sentence
        "wewe", "mimi", "sisi", "nyinyi", "yeye", "wao", "kwani", "kwa", "sana",
        "leo", "kesho", "jana", "asubuhi", "jioni", "usiku", "nini", "wapi",
        "gani", "ndio", "hapana", "lakini", "pia", "bado", "juu", "hadi",
        "kama", "ama", "yangu", "yako", "yake", "hiyo", "hii", "ile", "hapo",
        "nimekuwa", "nilikuwa", "unafanya", "nafanya", "tuko", "mko",
    }
)

# Kenyan place names we can recognise without needing user context. Mentioning
# one is not by itself a reason to interrupt the user.
KNOWN_PLACES: frozenset[str] = frozenset(
    {
        "nairobi", "mombasa", "kisumu", "nakuru", "eldoret", "thika", "naivasha",
        "kilimani", "westlands", "kasarani", "rongai", "ngong", "karen", "kikuyu",
        "juja", "ruiru", "embakasi", "donholm", "umoja", "kayole", "buruburu",
        "cbd", "town", "tao", "naivas", "quickmart", "carrefour", "sarit",
        "junction", "nanyuki", "diani", "kilifi", "malindi",
    }
)

# Conversation goals offered in the UI. Kept here so backend and frontend agree.
CONVERSATION_GOALS: dict[str, str] = {
    "start": "Start a conversation",
    "keep_flowing": "Keep the conversation flowing",
    "make_her_laugh": "Make her laugh",
    "playful": "Be playful",
    "flirt": "Flirt naturally",
    "get_to_know": "Get to know each other",
    "answer_personal": "Respond to a personal question",
    "ask_out": "Ask her out",
    "comfort": "Comfort or support",
    "end_naturally": "End the conversation naturally",
    "next_day": "Continue tomorrow",
}
