package com.vibe.keyboard.engine

/**
 * On-device detection of the moments that matter: a boundary, a picture request,
 * a reference to something only the user knows, a conversation winding down.
 *
 * This is a deliberately small port of `backend/app/core/personal_context.py`
 * and `lexicon.py`, which remain the reference implementation. It exists so the
 * keyboard can decide whether to appear at all without a network round trip --
 * the decision to stay quiet has to be instant and free.
 */
object ContextDetector {

    fun detect(message: String, hourOfDay: Int, knownKeys: Set<String> = emptySet()): VibeSignal {
        val text = message.trim()
        if (text.isEmpty()) return VibeSignal.Quiet
        val norm = normalize(text)

        // Boundaries first: they change what Vibe is allowed to do at all.
        if (Lexicon.boundaryPhrases.any { norm.containsPhrase(it) }) {
            return VibeSignal.Boundary(text)
        }
        if (Lexicon.pictureRequestPhrases.any { norm.containsPhrase(it) }) {
            return VibeSignal.PictureRequest(text)
        }

        missingContext(text, norm)?.let { if (it.key !in knownKeys) return it }

        if (Lexicon.windDownPhrases.any { norm.containsPhrase(it) }) {
            return VibeSignal.Ending(isNight = hourOfDay >= 21 || hourOfDay < 4)
        }

        return if (isWorthReplyingTo(text, norm)) VibeSignal.Reply else VibeSignal.Quiet
    }

    /** One gap per message, never three: a named person beats a vague event. */
    private fun missingContext(text: String, norm: String): VibeSignal.NeedsContext? {
        candidateNames(text).firstOrNull()?.let { name ->
            return VibeSignal.NeedsContext(
                subject = name,
                headline = "They mentioned $name.",
                quote = text,
                question = "They mentioned $name. Who's that, and what happened?",
                key = "person:${name.lowercase()}",
            )
        }
        if ('?' in text) {
            Lexicon.relationshipNouns.firstOrNull { norm.containsPhrase("your $it") || norm.containsPhrase("ur $it") }
                ?.let { noun ->
                    return VibeSignal.NeedsContext(
                        subject = "your $noun",
                        headline = "They asked about your $noun.",
                        quote = text,
                        question = "They asked about your $noun. What's going on there?",
                        key = "relation:$noun",
                    )
                }
        }
        Lexicon.sharedEventPhrases.firstOrNull { norm.containsPhrase(it) }?.let { phrase ->
            return VibeSignal.NeedsContext(
                subject = phrase,
                headline = "They're bringing up something you two share.",
                quote = text,
                question = "This points at something between you two. What should Vibe know?",
                key = "event:$phrase",
            )
        }
        return null
    }

    /**
     * Proper nouns that look like people Vibe has never heard of: a capitalised
     * word that does not open a sentence, or one straight after "na"/"with".
     * Sentence position matters -- "Ulienda" in "Sawa. Ulienda town?" is only
     * capitalised because it starts the sentence.
     */
    fun candidateNames(text: String): List<String> {
        val tokens = Regex("""[A-Za-z][A-Za-z'\-]+""").findAll(text).toList()
        val found = mutableListOf<String>()
        tokens.forEachIndexed { i, match ->
            val token = match.value
            val low = token.lowercase().trim('\'', '-')
            if (low.length < 3 || Lexicon.isNotAName(low)) return@forEachIndexed

            val before = text.substring(0, match.range.first).trimEnd()
            val startsSentence = before.isEmpty() || before.last() in ".!?\n"
            val prev = if (i > 0) tokens[i - 1].value.lowercase() else ""
            val capitalised = token[0].isUpperCase() && !token.all { it.isUpperCase() }

            if (capitalised && (!startsSentence || prev in Lexicon.namePreceders)) {
                val name = low.replaceFirstChar { it.uppercase() }
                if (name !in found) found += name
            }
        }
        return found
    }

    /** "ok", "lol" and a lone emoji do not need an assistant. */
    private fun isWorthReplyingTo(text: String, norm: String): Boolean {
        val words = norm.split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return false
        if (words.size <= 2 && words.all { it in Lexicon.lowEffortReplies }) return false
        return '?' in text || words.size >= 3
    }

    internal fun normalize(text: String): String =
        text.lowercase()
            .replace("’", "'")
            .replace("'", "")
            .replace(Regex("""[^a-z0-9\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun String.containsPhrase(phrase: String): Boolean =
        " $this ".contains(" $phrase ")
}
