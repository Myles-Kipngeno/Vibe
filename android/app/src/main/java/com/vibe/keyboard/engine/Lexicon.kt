package com.vibe.keyboard.engine

/**
 * The subset of `backend/app/core/lexicon.py` the keyboard needs to decide
 * whether to speak up. Phrases are matched against normalised text (lowercase,
 * no apostrophes or punctuation), so "I'm not interested" is "im not interested".
 */
internal object Lexicon {

    val boundaryPhrases = listOf(
        "not interested", "leave me alone", "stop texting me", "stop messaging me",
        "dont text me", "please stop", "i said no", "no means no", "im uncomfortable",
        "this makes me uncomfortable", "youre crossing a line", "back off",
        "i have a boyfriend", "i have a bf", "i have a girlfriend", "im taken",
        "im married", "im engaged", "were just friends", "i dont like you like that",
        "sitaki", "niache", "usinitext", "acha kunitext", "niko na boyfriend",
        "niko na bf", "nina boyfriend", "niko na mtu", "niko committed",
    )

    val pictureRequestPhrases = listOf(
        "send me a pic", "send a pic", "send pic", "send me pic", "send me a photo",
        "send a photo", "send me your pic", "send me your photo", "send me a selfie",
        "send selfie", "nitumie pic", "nitumie picha", "tuma pic", "tuma picha",
        "let me see you", "show me your face", "unaonekanaje", "wacha nikuone",
    )

    val windDownPhrases = listOf(
        "goodnight", "good night", "gn", "nighty", "sleep well", "sweet dreams",
        "im sleeping", "going to sleep", "off to bed", "bed time", "nalala",
        "naenda kulala", "usiku mwema", "lala salama", "talk tomorrow",
        "tuongee kesho", "tutaongea kesho", "ttyl", "gtg", "gotta go", "i have to go",
    )

    val sharedEventPhrases = listOf(
        "remember when", "remember that", "remember what", "remember how",
        "you remember", "how did it go", "how did that go", "did you tell",
        "did you talk to", "so did she", "so did he", "did she finally",
        "did he finally", "you told me", "you promised", "like you said",
        "that thing", "ile story", "ile kitu", "ile siku", "ile place",
        "ilienda aje", "ilikuaje", "ilikuwaje", "ulimwambia", "ulionana",
    )

    val relationshipNouns = listOf(
        "sister", "sis", "brother", "bro", "mum", "mom", "mother", "dad", "father",
        "parents", "cousin", "aunt", "uncle", "boss", "roommate", "bestie", "ex",
        "siste", "brathe", "mathe", "buda", "beshte",
    )

    val lowEffortReplies = setOf(
        "k", "kk", "ok", "okay", "sawa", "poa", "cool", "nice", "lol", "haha",
        "hmm", "yeah", "ya", "yep", "sure", "oh", "true", "fr", "mm", "sawa sawa",
    )

    val namePreceders = setOf("na", "with", "kwa", "tell", "told", "ask", "asked", "saw", "met")

    private val notNames = setOf(
        // Everyday words that get capitalised mid-sentence.
        "the", "and", "but", "you", "your", "are", "was", "were", "did", "does",
        "what", "when", "where", "who", "how", "why", "yes", "hey", "hello",
        "okay", "lol", "omg", "haha", "sorry", "please", "thanks", "today",
        "tomorrow", "yesterday", "morning", "night", "god", "bro", "sis",
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
        // Sheng / Kiswahili.
        "sasa", "niaje", "poa", "sawa", "wewe", "mimi", "sisi", "yeye", "kwani",
        "sana", "leo", "kesho", "jana", "nini", "wapi", "gani", "ndio", "hapana",
        "lakini", "bado", "kama", "ama", "hiyo", "hii", "ile", "hapo", "aii",
        "wueh", "manze", "aki", "eish", "buda", "msee", "dame", "chali",
        // Places Vibe can recognise without asking.
        "nairobi", "mombasa", "kisumu", "nakuru", "eldoret", "thika", "town",
        "tao", "cbd", "westlands", "kilimani", "rongai", "ngong", "karen",
        "naivas", "quickmart", "carrefour", "sarit", "junction", "juja", "ruiru",
    )

    // Kiswahili verb prefixes: "Ulienda", "Nimefika" are verbs, never names.
    private val verbPrefixes = listOf(
        "uli", "ali", "nili", "tuli", "wali", "mli", "nime", "ume", "ame", "tume",
        "nina", "nika", "unaf", "unak", "anaf", "anak", "nita", "utaf", "atak",
        "tuta", "haku", "sija",
    )

    fun isNotAName(lowercaseWord: String): Boolean =
        lowercaseWord in notNames ||
            lowercaseWord in relationshipNouns ||
            lowercaseWord in lowEffortReplies ||
            verbPrefixes.any { lowercaseWord.startsWith(it) }
}
