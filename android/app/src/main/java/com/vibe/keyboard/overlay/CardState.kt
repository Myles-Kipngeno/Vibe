package com.vibe.keyboard.overlay

import com.vibe.keyboard.auto.AutoRulesConfig
import com.vibe.keyboard.auto.ReplyMode
import com.vibe.keyboard.context.ScanState
import com.vibe.keyboard.engine.VibeSignal
import com.vibe.keyboard.memory.MemoryItem

/**
 * What the toolbar and the panel show. Every number in here is a count of
 * something Vibe actually holds; none is an estimate.
 */
data class VibeStatus(
    val platformLabel: String = "",
    val conversationId: String? = null,
    val conversationName: String? = null,
    val scanState: ScanState = ScanState.NOT_SCANNED,
    val messageCount: Int = 0,
    val memoryCount: Int = 0,
    val mode: ReplyMode = ReplyMode.SUGGEST,
    val canSend: Boolean = false,
    val importSupported: Boolean = false,
    val importHint: String? = null,
    val withoutContext: Boolean = false,
)

data class ConversationOption(
    val id: String,
    val name: String,
    val platformLabel: String,
    val messages: Int,
    val memories: Int,
    val active: Boolean,
    val otherApp: Boolean,
)

/** Cards that take typing from the keyboard's own keys instead of the app's text box. */
sealed interface CapturesText {
    val draft: String
}

/** Everything the Vibe card can be. `Hidden` is the most common, on purpose. */
sealed interface CardState {
    data object Hidden : CardState

    /** Analysing, or fetching the first suggestion. */
    data class Thinking(val label: String) : CardState

    data class Suggestion(
        /** The selected option: what Use inserts and Auto sends. */
        val text: String,
        val label: String,
        val isPreview: Boolean,
        /** Every option the model gave, [text] among them. */
        val options: List<String> = listOf(text),
        val selected: Int = 0,
        /** The server's id per option, for telling it what was used. */
        val optionIds: List<String?> = options.map { null },
        /** Regenerate in flight: keep the old text visible, dimmed. */
        val refreshing: Boolean = false,
        /** Auto mode put it in the box already; the card offers Undo instead of Use. */
        val autoInserted: Boolean = false,
        /** One line under the text: why Auto paused, where it came from, what happened. */
        val note: String? = null,
        /** Auto mode will press Send in this many seconds unless cancelled. */
        val sendIn: Int? = null,
        /** The app's Send action ran and the box emptied. */
        val sent: Boolean = false,
    ) : CardState

    data class ContextNeeded(
        val signal: VibeSignal.NeedsContext,
        val expanded: Boolean = false,
        override val draft: String = "",
        val remember: Boolean = true,
        /** "Remember for Sarah", or "Remember for this chat" before one is picked. */
        val rememberLabel: String = "Remember for this chat",
    ) : CardState, CapturesText

    data class Ending(val isNight: Boolean, val slowing: Boolean = false) : CardState

    data class PictureRequest(val quote: String) : CardState

    data class Boundary(val quote: String) : CardState

    /** Something Vibe will not handle for the user, like a request for a PIN. */
    data class Attention(val title: String, val quote: String, val detail: String) : CardState

    /** A one-line, self-dismissing note ("Copy their message, then tap Scan"). */
    data class Notice(val text: String) : CardState

    data class Failed(val message: String, val title: String = "Something went wrong") : CardState

    // --- Context and memory ---------------------------------------------------

    /** The ✦ panel: which chat, what Vibe holds for it, and the controls. */
    data class Panel(val status: VibeStatus) : CardState

    data class ChooseConversation(val platformLabel: String, val options: List<ConversationOption>) : CardState

    /** Naming a new chat, typed with the keyboard's own keys. */
    data class NameConversation(override val draft: String = "") : CardState, CapturesText

    /** The scan found nothing Vibe is allowed to read here. Says so, and offers what does work. */
    data class ContextUnavailable(
        val platformLabel: String,
        val importSupported: Boolean,
        val importHint: String?,
        val reason: String,
    ) : CardState

    data class MemoryView(
        val conversationName: String,
        val items: List<MemoryItem>,
        val messageCount: Int,
        val confirmClear: Boolean = false,
    ) : CardState

    data class EditMemory(val item: MemoryItem, override val draft: String) : CardState, CapturesText

    /** Turning Auto on is explained before it happens, with what this app allows. */
    data class AutoIntro(val platformLabel: String, val canSend: Boolean, val rules: AutoRulesConfig) : CardState
}

/** Things the controller needs the keyboard to do to the text field. */
sealed interface FieldAction {
    data class Insert(val text: String) : FieldAction
    /** Undo an Auto insertion, only if it is still exactly what was inserted. */
    data class Remove(val text: String) : FieldAction
    /** Press the field's own Send action, only if the box still holds exactly [text]. */
    data class Send(val text: String) : FieldAction
}

/** Things outside the keyboard: the companion app, and settings to persist. */
sealed interface VibeCommand {
    data class OpenImport(val platformId: String) : VibeCommand
    data object OpenSettings : VibeCommand
    data class PersistMode(val mode: ReplyMode) : VibeCommand
}
