package com.vibe.keyboard.context

import com.vibe.keyboard.engine.ChatMessage
import com.vibe.keyboard.engine.Conversation
import com.vibe.keyboard.memory.ConversationRecord
import com.vibe.keyboard.memory.MemorySource

/** Where a piece of context came from. Shown to the user; never guessed. */
enum class ContextSource(val label: String) {
    INPUT_CONNECTION("the message box"),
    CLIPBOARD("copied messages"),
    IMPORTED("imported chat"),
    USER_PROVIDED("what you told Vibe"),
    PLATFORM("the app"),
}

enum class ScanState {
    NOT_SCANNED,
    SCANNING,
    CONTEXT_READY,
    CONTEXT_STALE,
    CONTEXT_UNAVAILABLE,
    IMPORT_REQUIRED,
    ERROR,
}

/** Everything a provider can be asked, at the moment the user tapped Scan. */
data class ScanRequest(
    val platform: Platform,
    val capabilities: PlatformCapabilities,
    val record: ConversationRecord?,
    val copied: Conversation?,
    val fieldText: String,
    val sessionNotes: Map<String, String>,
)

sealed interface ProviderResult {
    val source: ContextSource

    data class Available(
        override val source: ContextSource,
        val messages: List<ChatMessage> = emptyList(),
        val notes: Int = 0,
        val draft: String? = null,
    ) : ProviderResult

    data class Unavailable(override val source: ContextSource, val reason: String) : ProviderResult
}

/**
 * One way of getting conversation context. Each implementation reports what
 * it actually obtained, or why it could not -- there is no "assume it worked".
 */
interface ConversationContextProvider {
    val source: ContextSource
    fun acquire(request: ScanRequest): ProviderResult
}

/** The text box the keyboard is typing into. The user's draft, never the chat. */
object InputConnectionContextProvider : ConversationContextProvider {
    override val source = ContextSource.INPUT_CONNECTION
    override fun acquire(request: ScanRequest): ProviderResult =
        if (request.fieldText.isBlank()) ProviderResult.Unavailable(source, "The message box is empty")
        else ProviderResult.Available(source, draft = request.fieldText)
}

/** Messages the user copied in the last few minutes. One message, or several with their names. */
object ClipboardContextProvider : ConversationContextProvider {
    override val source = ContextSource.CLIPBOARD
    override fun acquire(request: ScanRequest): ProviderResult {
        val copied = request.copied
        return if (copied == null || copied.isEmpty) ProviderResult.Unavailable(source, "Nothing copied recently")
        else ProviderResult.Available(source, messages = copied.messages)
    }
}

/** History the user imported for this chat (a WhatsApp export, for now). */
object ImportedConversationProvider : ConversationContextProvider {
    override val source = ContextSource.IMPORTED
    override fun acquire(request: ScanRequest): ProviderResult {
        val record = request.record ?: return ProviderResult.Unavailable(source, "No chat selected")
        return if (record.messages.isEmpty()) ProviderResult.Unavailable(source, "No history saved for ${record.contactName}")
        else ProviderResult.Available(source, messages = record.messages)
    }
}

/**
 * Reading the chat from the app itself. Android gives keyboards no API for
 * this, in any app, so this provider says so rather than pretending. It is the
 * one place a future, user-enabled mechanism would plug in.
 */
object PlatformContextProvider : ConversationContextProvider {
    override val source = ContextSource.PLATFORM
    override fun acquire(request: ScanRequest): ProviderResult =
        if (request.capabilities.canReadHistory) ProviderResult.Available(source)
        else ProviderResult.Unavailable(source, "Android doesn't let keyboards read ${request.platform.label} chats")
}

/** Context the user typed in when Vibe asked. */
object UserProvidedContextProvider : ConversationContextProvider {
    override val source = ContextSource.USER_PROVIDED
    override fun acquire(request: ScanRequest): ProviderResult {
        val saved = request.record?.memories?.count { it.source == MemorySource.USER } ?: 0
        val total = saved + request.sessionNotes.size
        return if (total == 0) ProviderResult.Unavailable(source, "You haven't told Vibe anything about this chat")
        else ProviderResult.Available(source, notes = total)
    }
}

/**
 * The normalized context the AI layer receives. It carries no idea of which
 * app or mechanism produced it beyond [sources], which is for the UI.
 */
data class NormalizedContext(
    val conversationId: String?,
    val platformId: String,
    val contactName: String?,
    val messages: List<ChatMessage>,
    val availableContext: Boolean,
    val sources: Set<ContextSource>,
    val unavailable: List<String>,
    val draft: String?,
)

class ContextScanner(
    private val providers: List<ConversationContextProvider> = listOf(
        PlatformContextProvider,
        ImportedConversationProvider,
        ClipboardContextProvider,
        InputConnectionContextProvider,
        UserProvidedContextProvider,
    ),
) {
    fun scan(request: ScanRequest): NormalizedContext {
        val results = providers.map { it.acquire(request) }
        val available = results.filterIsInstance<ProviderResult.Available>()
        val history = available.firstOrNull { it.source == ContextSource.IMPORTED }?.messages.orEmpty()
        val copied = available.firstOrNull { it.source == ContextSource.CLIPBOARD }?.messages.orEmpty()
        val messages = mergeTail(history, copied)
        return NormalizedContext(
            conversationId = request.record?.id,
            platformId = request.platform.id,
            contactName = request.record?.contactName,
            messages = messages,
            availableContext = messages.isNotEmpty(),
            sources = available.filter { it.messages.isNotEmpty() || it.notes > 0 }.map { it.source }.toSet(),
            unavailable = results.filterIsInstance<ProviderResult.Unavailable>()
                .filter { it.source == ContextSource.PLATFORM || it.source == ContextSource.CLIPBOARD }
                .map { it.reason },
            draft = available.firstOrNull { it.source == ContextSource.INPUT_CONNECTION }?.draft,
        )
    }

    companion object {
        /**
         * Appends [incoming] to [history] without repeating what overlaps: copied
         * messages are usually the newest few, some of which were saved before.
         */
        fun mergeTail(history: List<ChatMessage>, incoming: List<ChatMessage>): List<ChatMessage> {
            if (incoming.isEmpty()) return history
            if (history.isEmpty()) return incoming
            fun same(a: ChatMessage, b: ChatMessage) = a.text.trim() == b.text.trim()
            val maxOverlap = minOf(history.size, incoming.size)
            for (k in maxOverlap downTo 1) {
                val tail = history.subList(history.size - k, history.size)
                val head = incoming.subList(0, k)
                if (tail.indices.all { same(tail[it], head[it]) }) return history + incoming.drop(k)
            }
            // Everything copied is already somewhere in the recent history: nothing new.
            val recent = history.takeLast(50)
            if (incoming.all { m -> recent.any { same(it, m) } }) return history
            return history + incoming
        }
    }
}
