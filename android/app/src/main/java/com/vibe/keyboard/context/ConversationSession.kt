package com.vibe.keyboard.context

import com.vibe.keyboard.ai.ReplyContext
import com.vibe.keyboard.engine.ChatMessage
import com.vibe.keyboard.engine.Conversation
import com.vibe.keyboard.engine.Speaker
import com.vibe.keyboard.engine.TextStats
import com.vibe.keyboard.memory.ConversationRecord
import com.vibe.keyboard.memory.ConversationStore
import com.vibe.keyboard.memory.ConversationSummarizer
import com.vibe.keyboard.memory.MemoryEdits
import com.vibe.keyboard.memory.MemoryItem
import com.vibe.keyboard.memory.MemoryKind
import com.vibe.keyboard.memory.MemoryRetriever
import com.vibe.keyboard.memory.MemorySource
import com.vibe.keyboard.memory.OutcomeTally
import com.vibe.keyboard.memory.PendingUse
import com.vibe.keyboard.memory.Retrieved
import com.vibe.keyboard.memory.StyleSamples
import com.vibe.keyboard.memory.UserStyleLearner
import com.vibe.keyboard.overlay.SessionMemory
import java.util.UUID

/** What a reply is built from: the retrieved slice, plus the alert keys already answered. */
data class ReplyBundle(val reply: ReplyContext, val knownKeys: Set<String>)

data class ScanOutcome(
    val state: ScanState,
    val context: NormalizedContext,
    val record: ConversationRecord?,
    val newMessages: Int,
)

/**
 * Which conversation the keyboard is helping with, and everything done to
 * that conversation's memory.
 *
 * Android tells a keyboard which app it is in, never which chat, so the
 * active conversation is one the user picked. Three things keep a picked chat
 * from leaking into the wrong one: it is dropped when the app changes, it
 * expires after the keyboard has been idle a while, and it is dropped when
 * copied messages carry a sender name that is not this chat's.
 */
class ConversationSession(
    private val store: ConversationStore,
    private val sessionMemory: SessionMemory,
    private val now: () -> Long,
    private val scanner: ContextScanner = ContextScanner(),
) {
    var platform: Platform = Platform.Unknown
        private set
    var capabilities: PlatformCapabilities = PlatformCapabilities.detect(Platform.Unknown, false)
        private set
    var withoutContext = false
        private set

    private var activeId: String? = null
    private var lastActiveAt = 0L
    /** A scan's "can't" answer, kept until something changes. */
    private var outcome: ScanState? = null
    private var copiedOnly = false

    /** @return true when this is a different app, so nothing from the last one carries over. */
    fun onInputStarted(packageName: String?, fieldDeclaresSend: Boolean): Boolean {
        val next = Platform.fromPackage(packageName)
        val changed = next.packageName != platform.packageName
        platform = next
        capabilities = PlatformCapabilities.detect(next, fieldDeclaresSend)
        if (changed) reset()
        else if (activeId != null && now() - lastActiveAt > IDLE_EXPIRY_MS) {
            activeId = null
            outcome = null
        }
        touch()
        return changed
    }

    fun reset() {
        activeId = null
        withoutContext = false
        outcome = null
        copiedOnly = false
        sessionMemory.clear()
    }

    fun touch() {
        lastActiveAt = now()
    }

    fun active(): ConversationRecord? = activeId?.let(store::get)

    /** Chats on this app first. Other apps' chats are listed, never mixed in. */
    fun choices(): List<ConversationRecord> =
        store.list().sortedWith(compareBy<ConversationRecord> { if (it.platformId == platform.id) 0 else 1 })

    fun select(id: String): ConversationRecord? {
        val record = store.get(id) ?: return null
        activeId = id
        withoutContext = false
        outcome = null
        touch()
        return record
    }

    fun create(name: String): ConversationRecord {
        val record = ConversationRecord(
            id = UUID.randomUUID().toString(),
            platformId = platform.id,
            contactName = name.trim().take(40),
            createdAt = now(),
        )
        store.save(record)
        select(record.id)
        return record
    }

    fun deselect() {
        activeId = null
        outcome = null
    }

    fun continueWithoutContext() {
        activeId = null
        withoutContext = true
        outcome = null
    }

    fun state(scanning: Boolean = false): ScanState {
        if (scanning) return ScanState.SCANNING
        outcome?.let { return it }
        val record = active() ?: return if (copiedOnly) ScanState.CONTEXT_READY else ScanState.NOT_SCANNED
        val scannedAt = record.lastScanAt ?: return ScanState.NOT_SCANNED
        return if (now() - scannedAt > STALE_AFTER_MS) ScanState.CONTEXT_STALE else ScanState.CONTEXT_READY
    }

    fun markError() {
        outcome = ScanState.ERROR
    }

    /**
     * The user asked Vibe to look. Only what the providers actually return is
     * used, and only a chat the user picked is written to.
     */
    fun scan(copied: Conversation?, fieldText: String): ScanOutcome {
        val record = active()
        val ctx = scanner.scan(ScanRequest(platform, capabilities, record, copied, fieldText, sessionMemory.all()))
        if (!ctx.availableContext) {
            outcome = if (capabilities.importSupported) ScanState.IMPORT_REQUIRED else ScanState.CONTEXT_UNAVAILABLE
            return ScanOutcome(outcome!!, ctx, record, 0)
        }
        outcome = null
        if (record == null) {
            copiedOnly = true
            return ScanOutcome(ScanState.CONTEXT_READY, ctx, null, ctx.messages.size)
        }
        val added = ctx.messages.size - record.messages.size
        val names = (record.theirNames + copied?.theirNames.orEmpty()).distinct()
        val updated = if (added > 0 || record.summary == null) {
            rebuild(record.copy(messages = ctx.messages, theirNames = names))
        } else record
        if (updated !== record) store.save(updated)
        return ScanOutcome(state(), ctx, updated, added.coerceAtLeast(0))
    }

    fun rebuild(record: ConversationRecord): ConversationRecord = ConversationSummarizer.rebuild(record, now())

    /**
     * New messages from the chat that is still going on after an import:
     * what the user copied, and what they sent. Added to the picked chat's
     * history without a Scan, so memory keeps up with the conversation.
     * @return how many were new.
     */
    fun append(messages: List<ChatMessage>, theirNames: List<String> = emptyList()): Int {
        val record = active() ?: return 0
        val merged = ContextScanner.mergeTail(record.messages, messages)
        val added = merged.size - record.messages.size
        if (added <= 0) return 0
        val newMessages = merged.drop(record.messages.size)
        val grown = scoreOutcome(record, newMessages).copy(
            messages = merged,
            theirNames = (record.theirNames + theirNames).distinct(),
        )
        store.save(refreshed(grown))
        return added
    }

    /** He used a suggestion of this style; her next message will say how it came back. */
    fun noteUsed(style: String) {
        val record = active() ?: return
        store.save(record.copy(pendingUse = PendingUse(style, now())))
    }

    /**
     * Her first message after a used suggestion: did it come back warm --
     * laughing, flirting, or asking something back -- rather than one word?
     * Counted per style, as a signal only; it proves nothing about cause.
     */
    private fun scoreOutcome(record: ConversationRecord, newMessages: List<ChatMessage>): ConversationRecord {
        val pending = record.pendingUse ?: return record
        val hers = newMessages.firstOrNull { it.speaker == Speaker.THEM } ?: return record
        if (now() - pending.at > OUTCOME_WINDOW_MS) return record.copy(pendingUse = null)
        val text = hers.text
        val warm = !TextStats.isLowEffort(text) &&
            (TextStats.laughRate(listOf(text)) > 0 || '?' in text || FLIRTY.any { it in text })
        val tally = record.outcomes[pending.style] ?: OutcomeTally()
        val next = tally.copy(warm = tally.warm + if (warm) 1 else 0, total = tally.total + 1)
        return record.copy(pendingUse = null, outcomes = record.outcomes + (pending.style to next))
    }

    /** "Playful 3/4, Flirty 1/3" once a style has been used a few times in this chat. */
    private fun outcomeNote(record: ConversationRecord?): String? {
        val seen = record?.outcomes?.filterValues { it.total >= 3 }.orEmpty()
        if (seen.isEmpty()) return null
        return "In this chat, after these styles of his, her next message came back warm (laughing, flirting or asking back) -- " +
            seen.entries.sortedByDescending { it.value.total }.joinToString(", ") { "${it.key.lowercase()} ${it.value.warm}/${it.value.total}" } +
            ". A signal, not proof: it does not mean those replies caused it."
    }

    /** A message the user just sent in the picked chat. Their side of it, as sent. */
    fun recordSent(text: String): Boolean {
        val clean = text.trim()
        if (clean.isEmpty()) return false
        return append(listOf(ChatMessage(Speaker.ME, clean, now()))) > 0
    }

    /**
     * Summary and memories are rebuilt every [REBUILD_EVERY] new messages, not
     * on each one; in between, the history is simply longer and still current.
     */
    private fun refreshed(record: ConversationRecord): ConversationRecord {
        val since = record.messages.size - (record.summary?.basedOnMessages ?: 0)
        return if (record.summary == null || since >= REBUILD_EVERY) rebuild(record) else record.copy(lastScanAt = now())
    }

    /**
     * True when copied messages name a sender this chat has never had: the
     * user has probably moved to another conversation in the same app.
     */
    fun isDifferentChat(copied: Conversation): Boolean {
        val record = active() ?: return false
        if (copied.theirNames.isEmpty() || record.theirNames.isEmpty()) return false
        return copied.theirNames.none { c -> record.theirNames.any { it.sameAs(c) } || record.contactName.sameAs(c) }
    }

    private fun String.sameAs(other: String): Boolean {
        val a = trim().lowercase()
        val b = other.trim().lowercase()
        return a == b || (a.substringBefore(' ') == b.substringBefore(' ') && a.substringBefore(' ').length >= 3)
    }

    /** The slice of the active chat that bears on this message. Nothing from any other chat. */
    fun contextFor(convo: Conversation): ReplyBundle {
        val record = active()
        val current = convo.lastFromThem?.text.orEmpty()
        val retrieved = if (record != null) MemoryRetriever.retrieve(record, current) else Retrieved.Empty
        val history = record?.messages.orEmpty()
        val recent = ContextScanner.mergeTail(history, convo.messages).takeLast(RECENT_WINDOW)
        val sources = buildSet {
            if (history.isNotEmpty()) add(ContextSource.IMPORTED)
            if (!convo.isEmpty) add(ContextSource.CLIPBOARD)
            if (sessionMemory.size() > 0 || record?.memories?.any { it.source == MemorySource.USER } == true) {
                add(ContextSource.USER_PROVIDED)
            }
        }
        val style = store.loadStyle()
        val mine = record?.messages?.filter { it.speaker == Speaker.ME }?.map { it.text }?.takeLast(400)
        val samples = StyleSamples.pick(
            mine = mine?.takeIf { it.size >= 3 } ?: style.samples,
            current = current,
            alreadyShown = recent.map { it.text },
        )
        val reply = ReplyContext(
            conversationLabel = record?.let { "${it.contactName} · ${Platform.fromId(it.platformId).label}" },
            sources = sources,
            recent = recent,
            summary = record?.summary,
            memories = retrieved.memories,
            snippets = retrieved.snippets,
            userStyle = style,
            styleSamples = samples,
            outcomeNote = outcomeNote(record),
        )
        return ReplyBundle(reply, retrieved.knownKeys + sessionMemory.keys())
    }

    /** Recent messages to reply from when nothing new was copied. */
    fun recentConversation(): Conversation? {
        val record = active() ?: return null
        if (record.messages.isEmpty()) return null
        return Conversation(record.messages.takeLast(RECENT_WINDOW))
    }

    // --- Memory the user controls ----------------------------------------

    /** @return true if it was saved to the chat; false if only for this session (no chat picked). */
    fun remember(key: String, value: String): Boolean {
        val record = active()
        if (record == null) {
            sessionMemory.put(key, value)
            return false
        }
        val item = MemoryItem(UUID.randomUUID().toString(), kindFor(key), key, value.trim(), MemorySource.USER, now())
        store.save(
            record.copy(
                memories = record.memories.filterNot { it.key == key } + item,
                forgottenKeys = record.forgottenKeys - key,
            ),
        )
        return true
    }

    fun forget(itemId: String) {
        active()?.let { store.save(MemoryEdits.forget(it, itemId)) }
    }

    fun edit(itemId: String, value: String) {
        active()?.let { store.save(MemoryEdits.edit(it, itemId, value)) }
    }

    fun clearMemory() {
        active()?.let { store.save(MemoryEdits.clear(it)) }
        sessionMemory.clear()
    }

    fun learnFromUsed(text: String) {
        store.saveStyle(UserStyleLearner.learnFromUsed(store.loadStyle(), text))
    }

    // --- Goodnight, then morning ------------------------------------------

    fun markGoodnight() {
        val record = active() ?: return
        store.save(record.copy(goodnightAt = now(), morningOffered = false))
    }

    /** A warm goodnight last night, it is morning now, and Vibe has not offered yet. */
    fun morningDue(hourOfDay: Int): Boolean {
        val record = active() ?: return false
        val at = record.goodnightAt ?: return false
        val since = now() - at
        return !record.morningOffered && hourOfDay in 5..11 && since in 4 * HOUR_MS..18 * HOUR_MS
    }

    fun markMorningOffered() {
        val record = active() ?: return
        store.save(record.copy(morningOffered = true))
    }

    private fun kindFor(key: String) = when (key.substringBefore(':')) {
        "person", "relation" -> MemoryKind.PERSON
        "event" -> MemoryKind.EVENT
        "place" -> MemoryKind.PLACE
        "topic" -> MemoryKind.TOPIC
        else -> MemoryKind.NOTE
    }

    companion object {
        const val HOUR_MS = 3_600_000L
        const val STALE_AFTER_MS = 12 * HOUR_MS
        const val IDLE_EXPIRY_MS = 10 * 60_000L
        const val RECENT_WINDOW = 12
        const val REBUILD_EVERY = 10
        /** Her reply has to come within this long to be read as a reaction at all. */
        const val OUTCOME_WINDOW_MS = 12 * HOUR_MS
        private val FLIRTY = listOf("😏", "😘", "😍", "🥰", "😉", "🙈", "❤", "😚", "🔥")
    }
}
