package com.vibe.keyboard.memory

import com.vibe.keyboard.engine.ChatMessage
import com.vibe.keyboard.engine.ContextDetector
import com.vibe.keyboard.engine.Lexicon
import com.vibe.keyboard.engine.TextStats

/** The slice of one conversation's memory that bears on the message being answered. */
data class Retrieved(
    val memories: List<MemoryItem>,
    /** Older messages that mention the same people, for a model to read. */
    val snippets: List<ChatMessage>,
    /** Alert keys Vibe can already answer, so it does not ask about them. */
    val knownKeys: Set<String>,
) {
    companion object {
        val Empty = Retrieved(emptyList(), emptyList(), emptySet())
    }
}

/**
 * Picks what is relevant instead of sending a whole history with every request.
 * It only ever reads the one [ConversationRecord] it is handed, which is how
 * Sarah's memories stay out of Jane's replies: there is no other record in reach.
 */
object MemoryRetriever {

    fun retrieve(
        record: ConversationRecord?,
        current: String,
        maxMemories: Int = 6,
        maxSnippets: Int = 4,
        recentWindow: Int = 12,
    ): Retrieved {
        if (record == null) return Retrieved.Empty
        val names = ContextDetector.candidateNames(current).map { it.lowercase() }.toSet()
        val terms = TextStats.words(current)
            .filter { it.length >= 4 && it !in Lexicon.topicStopwords }.toSet() + names

        val scored = record.memories.map { m -> m to score(m, names, terms) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(maxMemories)
            .map { it.first }

        // Older messages only: the recent window already travels with the request,
        // and the message being answered is not evidence that Vibe knew it before.
        val older = record.messages.dropLast(recentWindow).filter { it.text.trim() != current.trim() }
        val snippets = if (names.isEmpty()) emptyList() else older.filter { m ->
            val words = TextStats.words(m.text).toSet()
            names.any { it in words }
        }.takeLast(maxSnippets)

        val namesSeenBefore = names.filter { n ->
            record.messages.any { m -> m.text.trim() != current.trim() && n in TextStats.words(m.text) }
        }
        val known = record.memories.map { it.key }.toSet() + namesSeenBefore.map { "person:$it" }
        return Retrieved(scored, snippets, known)
    }

    private fun score(m: MemoryItem, names: Set<String>, terms: Set<String>): Int {
        val subject = m.key.substringAfter(':')
        var s = 0
        if (subject in names) s += 4 else if (subject in terms) s += 3
        val valueWords = TextStats.words(m.value).toSet()
        s += terms.count { it in valueWords }
        if (s > 0 && m.source == MemorySource.USER) s += 1
        return s
    }
}
