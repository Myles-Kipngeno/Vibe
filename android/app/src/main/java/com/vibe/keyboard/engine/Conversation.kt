package com.vibe.keyboard.engine

import kotlinx.serialization.Serializable

@Serializable
enum class Speaker { ME, THEM }

@Serializable
data class ChatMessage(
    val speaker: Speaker,
    val text: String,
    /** Epoch millis, when the source had a timestamp (an export, a multi-message copy). */
    val sentAt: Long? = null,
)

/**
 * What Vibe can honestly see of a conversation.
 *
 * A keyboard cannot read the chat on screen. What reaches Vibe is what the user
 * copied (one message, or several copied together), what they imported, and
 * what they have typed in the box. Keeping that explicit here stops anything
 * downstream from behaving as if it had the whole thread.
 */
data class Conversation(
    val messages: List<ChatMessage>,
    val draft: String = "",
    /** Sender names in a multi-message copy that are not the user. Empty for a single copied message. */
    val theirNames: List<String> = emptyList(),
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

    /**
     * Something heavy just landed ("lost my job", "been crying"). Vibe still
     * helps, but says so, and Auto mode always hands this one back to the user.
     */
    data class Emotional(val quote: String) : VibeSignal

    /** They referred to someone or something only the user knows about. */
    data class NeedsContext(
        val subject: String,
        /** One short line for the collapsed card: "They mentioned Randy." */
        val headline: String,
        val quote: String,
        val question: String,
        /** Stable key so an answered question is never asked twice. */
        val key: String,
        /**
         * Worth keeping for next time? Yes for a person or a shared event; no
         * for "what did they mean by that", which is about one message.
         */
        val rememberByDefault: Boolean = true,
        val bellLabel: String = "Needs context",
    ) : VibeSignal

    data class PictureRequest(val quote: String) : VibeSignal

    /**
     * The conversation is winding down; offer Continue / Wrap up / Wait.
     * [slowing] is the softer case: nobody said goodbye, the replies just thinned out.
     */
    data class Ending(val isNight: Boolean, val slowing: Boolean = false) : VibeSignal

    /** They said no or asked for space. Only a respectful exit is on offer. */
    data class Boundary(val quote: String) : VibeSignal

    /** They asked for a PIN, a password, money or an ID number. Vibe never answers these. */
    data class SensitiveRequest(val quote: String, val what: String) : VibeSignal
}
