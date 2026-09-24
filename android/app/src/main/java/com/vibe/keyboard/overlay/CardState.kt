package com.vibe.keyboard.overlay

import com.vibe.keyboard.engine.VibeSignal

/** Everything the Vibe card can be. `Hidden` is the most common, on purpose. */
sealed interface CardState {
    data object Hidden : CardState

    /** Analysing, or fetching the first suggestion. */
    data class Thinking(val label: String) : CardState

    data class Suggestion(
        val text: String,
        val label: String,
        val isPreview: Boolean,
        /** Regenerate in flight: keep the old text visible, dimmed. */
        val refreshing: Boolean = false,
        /** Auto Reply put it in the box already; the card offers Undo instead of Use. */
        val autoInserted: Boolean = false,
    ) : CardState

    data class ContextNeeded(
        val signal: VibeSignal.NeedsContext,
        val expanded: Boolean = false,
        val draft: String = "",
        val remember: Boolean = true,
    ) : CardState

    data class Ending(val isNight: Boolean) : CardState

    data class PictureRequest(val quote: String) : CardState

    data class Boundary(val quote: String) : CardState

    /** A one-line, self-dismissing note ("Copy their message, then tap ✦"). */
    data class Notice(val text: String) : CardState

    data class Failed(val message: String) : CardState
}

/** Things the controller needs the keyboard to do to the text field. */
sealed interface FieldAction {
    data class Insert(val text: String) : FieldAction
    /** Undo an Auto Reply insertion, only if it is still exactly what was inserted. */
    data class Remove(val text: String) : FieldAction
}
