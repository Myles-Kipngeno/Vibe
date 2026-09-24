package com.vibe.keyboard.engine

/**
 * Turns copied text into messages.
 *
 * WhatsApp copies several selected messages as
 * `[21:04, 24/09/2026] Ann: text`, one per line. Anything else is treated as a
 * single message from the other person: when someone long-presses a message in
 * a chat and copies it, it is almost always the one they want to reply to.
 */
object ConversationParser {

    private val whatsAppLine = Regex(
        """^\[(\d{1,2}:\d{2}(?:\s?[APap][Mm])?),\s*[^\]]+]\s*([^:]{1,40}):\s?(.*)$""",
    )

    fun parse(copied: String, myName: String? = null): Conversation {
        val lines = copied.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return Conversation(emptyList())

        val matched = lines.count { whatsAppLine.matches(it) }
        if (matched == 0) {
            return Conversation(listOf(ChatMessage(Speaker.THEM, copied.trim())))
        }

        val parsed = mutableListOf<Pair<String, StringBuilder>>()
        for (line in lines) {
            val m = whatsAppLine.matchEntire(line)
            if (m != null) {
                parsed += m.groupValues[2].trim() to StringBuilder(m.groupValues[3].trim())
            } else if (parsed.isNotEmpty()) {
                parsed.last().second.append('\n').append(line.trim())
            }
        }

        val names = parsed.map { it.first }.distinct()
        // Whoever sent the last message in a copied block is usually the other
        // person -- that is why the user is replying. The user can set their own
        // name later; until then this is the least-wrong guess.
        val me = myName?.takeIf { n -> names.any { it.equals(n, ignoreCase = true) } }
            ?: names.firstOrNull { it != parsed.last().first }.takeIf { names.size > 1 }

        return Conversation(
            parsed.map { (who, text) ->
                val speaker = if (me != null && who.equals(me, ignoreCase = true)) Speaker.ME else Speaker.THEM
                ChatMessage(speaker, text.toString())
            }.filter { it.text.isNotBlank() },
        )
    }
}
