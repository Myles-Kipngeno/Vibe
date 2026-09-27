package com.vibe.keyboard.memory

import com.vibe.keyboard.engine.ContextDetector
import com.vibe.keyboard.engine.Lexicon
import com.vibe.keyboard.engine.TextStats

/**
 * Which of the user's own messages to show the model as their voice.
 *
 * Models copy examples far better than they follow a description, so a few
 * real messages ("aii niko tu manze") teach the spelling, Sheng and length
 * that "English + Sheng, short messages" cannot. Only ever the user's side.
 */
object StyleSamples {

    const val MAX = 8

    /**
     * Messages close to what is being answered first (shared words), then the
     * most recent, so the model sees the user in a similar moment and as they
     * text now. Anything already in the recent window is left out: the model
     * reads that in the transcript anyway.
     */
    fun pick(mine: List<String>, current: String, alreadyShown: Collection<String>, max: Int = MAX): List<String> {
        val shown = alreadyShown.map { it.trim() }.toSet()
        val pool = mine.map { it.trim() }
            .filter { it.isNotEmpty() && it !in shown && TextStats.words(it).size in 2..30 }
            .distinct()
        if (pool.isEmpty()) return emptyList()
        val want = TextStats.words(current).filter { it.length >= 3 && it !in Lexicon.topicStopwords }.toSet()
        val related = pool.withIndex()
            .map { (i, text) -> Triple(text, TextStats.words(text).count { it in want }, i) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Triple<String, Int, Int>> { it.second }.thenByDescending { it.third })
            .map { it.first }
            .take(max / 2)
        val recent = pool.asReversed().filter { it !in related }
        return (related + recent).take(max).map { it.take(300) }
    }

    /**
     * Messages safe to keep across chats: no names, places, numbers or links,
     * so nothing from one conversation can surface in another. What remains
     * is how the user writes, not what they wrote about.
     */
    fun isShareableAcrossChats(text: String): Boolean {
        val t = text.trim()
        val words = TextStats.words(t)
        if (words.size !in 2..15) return false
        if (t.any { it.isDigit() } || "http" in t || "@" in t || "www." in t) return false
        if (ContextDetector.candidateNames(t).isNotEmpty()) return false
        if (words.any { it in Lexicon.knownPlaces }) return false
        // A capital in the middle of a sentence is probably a name the detector missed.
        return !Regex("""(?<=[a-z,] )[A-Z][a-z]{2,}""").containsMatchIn(t)
    }
}
