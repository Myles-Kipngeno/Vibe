package com.vibe.keyboard.engine

enum class Speaker { ME, THEM }

data class ChatMessage(val speaker: Speaker, val text: String)

/**
 * What Vibe can honestly see of a conversation.
 *
 * A keyboard cannot read the chat on screen. What reaches Vibe is what the user
 * copied (one message, or several copied together) and what they have typed in
 * the box. Keeping that explicit here stops anything downstream from behaving
 * as if it had the whole thread.
 */
data class Conversation(
    val messages: List<ChatMessage>,
    val draft: String = "",
) {
    val lastFromThem: ChatMessage? get() = messages.lastOrNull { it.speaker == Speaker.THEM }
    val isEmpty: Boolean get() = messages.isEmpty()
}

/** Why Vibe is (or is not) showing up for a conversation. */
sealed interface VibeSignal {
    /** Nothing worth interrupting for. The most common answer, on purpose. */
    data object Quiet : VibeSignal

    /** An ordinary message Vibe can help answer. */
    data object Reply : VibeSignal

    /** They referred to someone or something only the user knows about. */
    data class NeedsContext(
        val subject: String,
        /** One short line for the collapsed card: "They mentioned Randy." */
        val headline: String,
        val quote: String,
        val question: String,
        /** Stable key so an answered question is never asked twice. */
        val key: String,
    ) : VibeSignal

    data class PictureRequest(val quote: String) : VibeSignal

    /** The conversation is winding down; offer Continue / Wrap up / Wait. */
    data class Ending(val isNight: Boolean) : VibeSignal

    /** They said no or asked for space. Only a respectful exit is on offer. */
    data class Boundary(val quote: String) : VibeSignal
}
