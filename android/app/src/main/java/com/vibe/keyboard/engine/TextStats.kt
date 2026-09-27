package com.vibe.keyboard.engine

/**
 * The measurements Vibe can make honestly and repeatedly. A port of
 * `backend/app/core/textstats.py`: no model, no guessing, so everything built
 * on it is testable and says only what the text shows.
 */
object TextStats {

    private val wordRe = Regex("""[a-z0-9']+""")
    private val shengVerb = Regex("""^(ni|u|a|tu|m|wa)(li|me|na|ta|ka|ki)[a-z]{3,}$""")
    private val englishLookalikes = setOf(
        "anymore", "another", "amazing", "america", "unless", "until", "umbrella",
        "wanted", "watching", "wakanda", "timeline", "tomorrow", "america",
    )

    fun words(text: String): List<String> =
        wordRe.findAll(text.lowercase().replace("’", "'")).map { it.value.replace("'", "") }.toList()

    /** Pictographic characters, close enough for texting. Counts code points, not chars. */
    fun emojis(text: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (isEmoji(cp)) out += String(Character.toChars(cp))
            i += Character.charCount(cp)
        }
        return out
    }

    private fun isEmoji(cp: Int) =
        cp in 0x1F300..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x1F000..0x1F2FF ||
            cp == 0x2764 || cp == 0x2665

    fun looksLikeShengVerb(word: String) = word !in englishLookalikes && shengVerb.matches(word)

    /** Share of words that are recognisably Sheng/Kiswahili. A marker ratio, not language ID. */
    fun shengRatio(texts: List<String>): Double {
        val tokens = texts.flatMap(::words)
        if (tokens.isEmpty()) return 0.0
        val hits = tokens.count { it in Lexicon.shengMarkers || looksLikeShengVerb(it) }
        return minOf(1.0, hits.toDouble() / tokens.size * 1.5)
    }

    fun avgWords(texts: List<String>): Double =
        if (texts.isEmpty()) 0.0 else texts.sumOf { words(it).size }.toDouble() / texts.size

    fun emojiRate(texts: List<String>): Double =
        if (texts.isEmpty()) 0.0 else texts.count { emojis(it).isNotEmpty() }.toDouble() / texts.size

    fun laughRate(texts: List<String>): Double {
        if (texts.isEmpty()) return 0.0
        return texts.count { t ->
            val w = words(t)
            w.any { it in Lexicon.laughTokens || it.startsWith("hahah") } || Lexicon.laughEmoji.any { t.contains(it) }
        }.toDouble() / texts.size
    }

    fun questionRate(texts: List<String>): Double =
        if (texts.isEmpty()) 0.0 else texts.count { '?' in it }.toDouble() / texts.size

    /** The emojis someone actually uses, most frequent first. */
    fun topEmojis(texts: List<String>, limit: Int = 3): List<String> =
        texts.flatMap(::emojis).groupingBy { it }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(limit).map { it.key }

    /** Short phrases someone repeats (at least twice): "sawa sawa", "aki", "no way". */
    fun commonPhrases(texts: List<String>, limit: Int = 8): List<String> {
        val counts = LinkedHashMap<String, Int>()
        for (t in texts) {
            val toks = words(t)
            for (n in 1..2) for (i in 0..toks.size - n) {
                val gram = toks.subList(i, i + n).joinToString(" ")
                if (gram.length < 3) continue
                counts[gram] = (counts[gram] ?: 0) + 1
            }
        }
        return counts.entries.filter { it.value >= 2 && it.key !in Lexicon.topicStopwords }
            .sortedByDescending { it.value }.take(limit).map { it.key }
    }

    /** "ok", "lol", a lone emoji: a reply that carries almost nothing new. */
    fun isLowEffort(text: String): Boolean {
        val w = words(text)
        if (w.isEmpty()) return true
        if (w.size > 3) return false
        return w.all { it in Lexicon.lowEffortReplies || it in Lexicon.laughTokens }
    }

    fun languageMix(ratio: Double): String = when {
        ratio >= 0.55 -> "Mostly Sheng/Kiswahili"
        ratio >= 0.15 -> "English + Sheng"
        else -> "Mostly English"
    }
}
