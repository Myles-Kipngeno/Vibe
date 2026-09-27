package com.vibe.keyboard.ai

import com.vibe.keyboard.context.ContextSource
import com.vibe.keyboard.engine.ChatMessage
import com.vibe.keyboard.engine.Conversation
import com.vibe.keyboard.memory.ConversationSummary
import com.vibe.keyboard.memory.MemoryItem
import com.vibe.keyboard.memory.UserStyleProfile

/** What the user asked Vibe to write. */
enum class ReplyIntent {
    REPLY,
    CONTINUE,
    WRAP_UP,
    GOODNIGHT,
    /** A playful text answer to a picture request. Vibe never sends photos. */
    PICTURE_REPLY,
    /** A respectful step back after they set a boundary. Nothing that pushes. */
    BOUNDARY_EXIT,
    /** The morning after a warm goodnight. Offered, never sent on its own. */
    MORNING,
}

/**
 * Everything Vibe retrieved for this one reply, from this one conversation.
 * Deliberately not the whole history: recent messages, the summary, and only
 * the memories and older messages that bear on what they just said.
 */
data class ReplyContext(
    /** "Sarah · WhatsApp", or null when no chat is selected. */
    val conversationLabel: String? = null,
    val sources: Set<ContextSource> = emptySet(),
    val recent: List<ChatMessage> = emptyList(),
    val summary: ConversationSummary? = null,
    val memories: List<MemoryItem> = emptyList(),
    val snippets: List<ChatMessage> = emptyList(),
    val userStyle: UserStyleProfile? = null,
    /** Messages the user actually wrote, as their voice. Only ever their side. */
    val styleSamples: List<String> = emptyList(),
) {
    companion object {
        val None = ReplyContext()
    }
}

data class SuggestionRequest(
    val conversation: Conversation,
    val intent: ReplyIntent,
    /** What the user told Vibe when it asked for context, keyed by the question's key. */
    val context: Map<String, String> = emptyMap(),
    /** Suggestions already shown, so Regenerate gives something new. */
    val avoid: List<String> = emptyList(),
    val reply: ReplyContext = ReplyContext.None,
)

data class Suggestion(
    val text: String,
    val isPreview: Boolean,
    /** Every option, [text] first. A real model returns up to three that differ in approach. */
    val options: List<String> = listOf(text),
    /** Shown under the options: why this came from the preview templates, for instance. */
    val note: String? = null,
    /** The server's id for each option, for feedback. Null for templates. */
    val optionIds: List<String?> = options.map { null },
)

/** What the user did with an option: the backend learns from these. */
enum class Verdict { USED, REJECTED }

open class AIException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Anything that can write a reply. The keyboard only ever talks to this.
 *
 * The planned `RemoteAIProvider` calls the existing backend's
 * `/api/conversation/suggest`, which already holds the prompts, the boundary and
 * context gates and the model key. The phone never holds an API key.
 */
interface AIProvider {
    /** True for anything that is not a real model, so the UI can say so. */
    val isPreview: Boolean

    /** @throws AIException when no suggestion could be produced. */
    suspend fun suggest(request: SuggestionRequest): Suggestion

    /** Tell the model's side what the user chose. Best effort; templates have nowhere to send it. */
    suspend fun feedback(text: String, id: String?, verdict: Verdict) {}
}
