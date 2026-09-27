package com.vibe.keyboard.engine

enum class Flow { FLOWING, SLOWING, UNKNOWN }

/**
 * How the conversation is moving, from evidence only: reply length and, when
 * the source had timestamps, the gaps. There are no folk rules here about
 * waiting twenty minutes to look busy -- a gap is information about them, not
 * a tactic for the user.
 */
object Rhythm {

    fun assess(messages: List<ChatMessage>): Flow {
        val theirs = messages.filter { it.speaker == Speaker.THEM }
        if (theirs.size < 3) return Flow.UNKNOWN

        // Their last three replies all one-word: the conversation is thinning.
        if (theirs.takeLast(3).all { TextStats.isLowEffort(it.text) }) return Flow.SLOWING

        // Replies got much shorter than they usually are, and stayed that way.
        val usual = TextStats.avgWords(theirs.dropLast(2).map { it.text })
        val lately = TextStats.avgWords(theirs.takeLast(2).map { it.text })
        if (theirs.size >= 6 && usual >= 6 && lately <= usual / 3) return Flow.SLOWING

        if (gapsGrowing(messages)) return Flow.SLOWING
        return Flow.FLOWING
    }

    /** Their last two replies each took far longer than their usual reply, and at least 15 minutes. */
    private fun gapsGrowing(messages: List<ChatMessage>): Boolean {
        val gaps = mutableListOf<Long>()
        for (i in 1 until messages.size) {
            val prev = messages[i - 1]
            val cur = messages[i]
            if (cur.speaker == Speaker.THEM && prev.speaker == Speaker.ME) {
                val a = prev.sentAt ?: return false
                val b = cur.sentAt ?: return false
                gaps += (b - a).coerceAtLeast(0)
            }
        }
        if (gaps.size < 4) return false
        val earlier = gaps.dropLast(2).sorted()
        val median = earlier[earlier.size / 2]
        val minGap = 15 * 60_000L
        return gaps.takeLast(2).all { it >= minGap && it > median * 3 }
    }
}
