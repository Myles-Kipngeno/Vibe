package com.vibe.keyboard.ai

import com.vibe.keyboard.engine.Conversation

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
}

data class SuggestionRequest(
    val conversation: Conversation,
    val intent: ReplyIntent,
    /** What the user told Vibe when it asked for context, keyed by the question's key. */
    val context: Map<String, String> = emptyMap(),
    /** Suggestions already shown, so Regenerate gives something new. */
    val avoid: List<String> = emptyList(),
)

data class Suggestion(val text: String, val isPreview: Boolean)

class AIException(message: String, cause: Throwable? = null) : Exception(message, cause)

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
}
