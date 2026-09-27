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

    /** From `personal_context._DISTRESS_PHRASES`: something heavy just landed. */
    val distressPhrases = listOf(
        "im not okay", "im not ok", "not doing well", "im stressed", "so stressed",
        "im depressed", "been crying", "i was crying", "im tired of", "cant anymore",
        "i feel alone", "im scared", "im worried", "lost my", "passed away", "funeral",
        "msiba", "hospital", "im sick", "niko sick", "nimechoka sana", "maisha ni ngumu",
        "sina pesa", "nimefired", "lost my job", "we broke up", "tumeachana", "he hit me", "im hurt",
    )

    /** From `lexicon.AMBIGUOUS_SIGNAL_PHRASES`: could be a brush-off, could be literal. */
    val ambiguousPhrases = listOf(
        "well see", "tutaona", "si tuone", "labda", "some other time", "another day",
        "i dont know", "sijui", "sijajua", "what do you mean", "unamaanisha", "meaning",
    )

    /**
     * Requests Vibe must never answer on the user's behalf, with what to call
     * them on the card. Matched against normalised text.
     */
    val sensitiveRequests = listOf(
        "password" to "a password", "passcode" to "a passcode", "pin" to "a PIN",
        "otp" to "a one-time code", "verification code" to "a verification code",
        "the code i sent" to "a code", "mpesa" to "money", "m pesa" to "money",
        "send money" to "money", "send me money" to "money", "nitumie pesa" to "money",
        "tuma pesa" to "money", "account number" to "an account number",
        "card number" to "a card number", "id number" to "an ID number",
        "kitambulisho" to "an ID number", "bank details" to "bank details",
        "home address" to "your address",
    )

    val laughTokens = setOf("haha", "hahaha", "hehe", "lol", "lmao", "rotfl", "kekeke")
    val laughEmoji = setOf("😂", "🤣", "😹")

    /** From `lexicon.KNOWN_PLACES`. */
    val knownPlaces = setOf(
        "nairobi", "mombasa", "kisumu", "nakuru", "eldoret", "thika", "naivasha",
        "kilimani", "westlands", "kasarani", "rongai", "ngong", "karen", "kikuyu",
        "juja", "ruiru", "embakasi", "donholm", "umoja", "kayole", "buruburu",
        "cbd", "town", "tao", "naivas", "quickmart", "carrefour", "sarit",
        "junction", "nanyuki", "diani", "kilifi", "malindi", "campus",
    )

    /** Sheng/Kiswahili markers for the language-mix measurement (subset of the backend's). */
    val shengMarkers = setOf(
        "niaje", "sasa", "poa", "sawa", "manze", "aki", "sema", "nini", "kwani", "mbona",
        "noma", "fiti", "wueh", "buda", "msee", "maze", "bana", "lakini", "ama", "aje",
        "si", "hii", "hiyo", "ile", "uko", "wapi", "leo", "kesho", "tu", "ata", "na", "ni",
        "mimi", "wewe", "sisi", "yeye", "sana", "jana", "ndio", "hapana", "bado", "kama",
        "mrembo", "dame", "chali", "form", "fom", "doh", "mbao", "ocha", "mathe", "fathe",
        "nimechoka", "nalala", "tao", "pole", "asante", "karibu", "rada", "mzae", "kwenda",
    )

    /** From `lexicon.TOPIC_STOPWORDS`, plus short everyday words. A topic has to recur anyway. */
    val topicStopwords = setOf(
        "about", "actually", "after", "again", "already", "also", "always", "another",
        "anything", "around", "asked", "asking", "away", "back", "because", "been",
        "before", "being", "better", "came", "come", "coming", "could", "does", "doing",
        "done", "down", "else", "even", "ever", "every", "from", "getting", "give",
        "going", "gone", "good", "great", "guess", "have", "having", "here", "hope",
        "into", "just", "keep", "kind", "know", "last", "later", "like", "little", "long",
        "look", "made", "make", "many", "maybe", "might", "more", "much", "need", "never",
        "next", "nice", "only", "other", "over", "please", "pretty", "probably", "really",
        "right", "said", "same", "should", "since", "some", "something", "soon", "sorry",
        "still", "sure", "take", "tell", "than", "thanks", "that", "their", "them", "then",
        "there", "these", "they", "thing", "things", "think", "this", "those", "though",
        "thought", "time", "told", "took", "very", "want", "wanted", "well", "went",
        "were", "what", "when", "where", "which", "while", "will", "with", "would",
        "your", "yours", "okay", "haha", "hahaha", "yeah", "gonna", "wanna", "cant",
        "dont", "didnt", "youre", "thats", "whats", "omitted", "media", "message", "deleted",
        "kwanza", "manze", "sasa", "sana", "tena", "yaani", "kabisa", "lakini", "sawa",
        "hiyo", "kwani", "ndio", "bado", "wewe", "mimi", "nini", "gani", "wapi",
    )

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
            lowercaseWord in knownPlaces ||
            lowercaseWord in relationshipNouns ||
            lowercaseWord in lowEffortReplies ||
            verbPrefixes.any { lowercaseWord.startsWith(it) }
}
