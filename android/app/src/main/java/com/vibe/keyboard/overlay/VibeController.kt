package com.vibe.keyboard.overlay

import com.vibe.keyboard.ai.AIProvider
import com.vibe.keyboard.ai.ReplyIntent
import com.vibe.keyboard.ai.SuggestionRequest
import com.vibe.keyboard.engine.ContextDetector
import com.vibe.keyboard.engine.Conversation
import com.vibe.keyboard.engine.ConversationParser
import com.vibe.keyboard.engine.VibeSignal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Decides when the Vibe card appears and what it says.
 *
 * Analysis happens on a *copy* (a new conversation to look at) or an explicit
 * tap on ✦ -- never per keystroke. Generation is separate from insertion, and
 * insertion is separate from sending: this class can put text in the box, and
 * nothing in Vibe can press Send.
 */
class VibeController(
    private val scope: CoroutineScope,
    private val provider: AIProvider,
    private val memory: SessionMemory = SessionMemory,
    private val hourOfDay: () -> Int = { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) },
    private val debounceMs: Long = 250,
    private val noticeMs: Long = 1_800,
) {
    private val _card = MutableStateFlow<CardState>(CardState.Hidden)
    val card: StateFlow<CardState> = _card.asStateFlow()

    private val _fieldActions = MutableSharedFlow<FieldAction>(extraBufferCapacity = 4)
    val fieldActions: SharedFlow<FieldAction> = _fieldActions

    /** Off: Vibe suggests, you insert. On: Vibe fills the box, you still press Send. */
    var autoReply: Boolean = false

    /** Lets Auto Reply avoid writing over something the user already started typing. */
    var fieldIsEmpty: () -> Boolean = { true }

    private var conversation: Conversation? = null
    private var lastIntent = ReplyIntent.REPLY
    private var lastCopiedFingerprint: Int? = null
    private var pendingContext: Map<String, String> = emptyMap()
    private val shown = mutableListOf<String>()
    private var job: Job? = null

    /** True while the context box is open, so the keyboard types there instead of into the app. */
    val isCapturingKeys: Boolean
        get() = (_card.value as? CardState.ContextNeeded)?.expanded == true

    // --- Triggers ---------------------------------------------------------

    /**
     * The user copied something. [automatic] triggers are de-duplicated, so the
     * same copied message never pops the card twice (including after dismissing it).
     */
    fun onCopied(text: String, automatic: Boolean) {
        val fingerprint = text.trim().hashCode()
        if (automatic && fingerprint == lastCopiedFingerprint) return
        lastCopiedFingerprint = fingerprint

        val parsed = ConversationParser.parse(text)
        if (parsed.isEmpty) return
        job?.cancel()
        job = scope.launch {
            delay(debounceMs) // a burst of copies settles on the last one
            analyse(parsed, manual = !automatic)
        }
    }

    /** ✦ tapped. Always answers -- with a suggestion, a question, or a hint. */
    fun onManualRequest(freshCopy: String?) {
        if (freshCopy != null) {
            onCopied(freshCopy, automatic = false)
            return
        }
        val known = conversation
        if (known == null) {
            notice("Copy their message, then tap ✦")
            return
        }
        job?.cancel()
        job = scope.launch { analyse(known, manual = true) }
    }

    private fun analyse(convo: Conversation, manual: Boolean) {
        conversation = convo
        shown.clear()
        pendingContext = emptyMap()
        val theirs = convo.lastFromThem
        if (theirs == null) {
            if (manual) notice("Nothing from them to reply to yet") else hide()
            return
        }
        when (val signal = ContextDetector.detect(theirs.text, hourOfDay(), memory.keys())) {
            VibeSignal.Quiet -> if (manual) generate(ReplyIntent.REPLY) else hide()
            VibeSignal.Reply -> generate(ReplyIntent.REPLY)
            is VibeSignal.NeedsContext -> _card.value = CardState.ContextNeeded(signal)
            is VibeSignal.PictureRequest -> _card.value = CardState.PictureRequest(signal.quote)
            is VibeSignal.Ending -> _card.value = CardState.Ending(signal.isNight)
            is VibeSignal.Boundary -> _card.value = CardState.Boundary(signal.quote)
        }
    }

    // --- Card actions -----------------------------------------------------

    fun use() {
        val s = _card.value as? CardState.Suggestion ?: return
        if (s.refreshing) return
        _fieldActions.tryEmit(FieldAction.Insert(s.text))
        hide()
    }

    fun regenerate() {
        val s = _card.value as? CardState.Suggestion
        if (s?.autoInserted == true) _fieldActions.tryEmit(FieldAction.Remove(s.text))
        generate(lastIntent)
    }

    fun undoAutoInsert() {
        val s = _card.value as? CardState.Suggestion ?: return
        if (!s.autoInserted) return
        _fieldActions.tryEmit(FieldAction.Remove(s.text))
        _card.value = s.copy(autoInserted = false)
    }

    fun dismiss() {
        job?.cancel()
        hide()
    }

    fun explain() = _card.update { (it as? CardState.ContextNeeded)?.copy(expanded = true) ?: it }

    fun typeIntoContext(text: String) = _card.update {
        if (it is CardState.ContextNeeded && it.expanded && it.draft.length < MAX_CONTEXT_CHARS) {
            it.copy(draft = (it.draft + text).take(MAX_CONTEXT_CHARS))
        } else it
    }

    fun deleteFromContext() = _card.update {
        if (it is CardState.ContextNeeded && it.draft.isNotEmpty()) {
            it.copy(draft = it.draft.dropLastCodePoint())
        } else it
    }

    fun toggleRemember() = _card.update { (it as? CardState.ContextNeeded)?.copy(remember = !it.remember) ?: it }

    fun submitContext() {
        val c = _card.value as? CardState.ContextNeeded ?: return
        val answer = c.draft.trim()
        if (answer.isEmpty()) return
        if (c.remember) memory.put(c.signal.key, answer)
        pendingContext = mapOf(c.signal.key to answer)
        generate(ReplyIntent.REPLY)
    }

    fun continueConversation() = generate(ReplyIntent.CONTINUE)

    fun wrapUp() {
        val night = (_card.value as? CardState.Ending)?.isNight ?: false
        generate(if (night) ReplyIntent.GOODNIGHT else ReplyIntent.WRAP_UP)
    }

    /** "Wait" means do nothing -- said once, briefly, then Vibe gets out of the way. */
    fun waitQuietly() = notice("Okay. Vibe will stay quiet.")

    fun replyToPicture() = generate(ReplyIntent.PICTURE_REPLY)

    fun replyToBoundary() = generate(ReplyIntent.BOUNDARY_EXIT)

    fun retry() = generate(lastIntent)

    // --- Session ----------------------------------------------------------

    /** A different app means a different conversation. Nothing carries over. */
    fun onAppChanged() {
        job?.cancel()
        conversation = null
        lastCopiedFingerprint = null
        pendingContext = emptyMap()
        shown.clear()
        memory.clear()
        hide()
    }

    // --- Internals --------------------------------------------------------

    private fun generate(intent: ReplyIntent) {
        val convo = conversation ?: return
        lastIntent = intent
        val previous = _card.value
        _card.value = if (previous is CardState.Suggestion) {
            previous.copy(refreshing = true, autoInserted = false)
        } else {
            CardState.Thinking(thinkingLabel(intent))
        }

        job?.cancel()
        job = scope.launch {
            try {
                val suggestion = provider.suggest(
                    SuggestionRequest(
                        conversation = convo,
                        intent = intent,
                        context = memory.all() + pendingContext,
                        avoid = shown.toList(),
                    ),
                )
                shown += suggestion.text
                val autoInsert = autoReply && intent == ReplyIntent.REPLY && fieldIsEmpty()
                if (autoInsert) _fieldActions.tryEmit(FieldAction.Insert(suggestion.text))
                _card.value = CardState.Suggestion(
                    text = suggestion.text,
                    label = suggestionLabel(intent),
                    isPreview = suggestion.isPreview,
                    autoInserted = autoInsert,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _card.value = CardState.Failed("Couldn't get a suggestion. Try again.")
            }
        }
    }

    private fun notice(text: String) {
        job?.cancel()
        val shownNotice = CardState.Notice(text)
        _card.value = shownNotice
        job = scope.launch {
            delay(noticeMs)
            if (_card.value == shownNotice) hide()
        }
    }

    private fun hide() {
        _card.value = CardState.Hidden
    }

    private fun thinkingLabel(intent: ReplyIntent) = when (intent) {
        ReplyIntent.CONTINUE -> "Finding a way forward…"
        ReplyIntent.WRAP_UP, ReplyIntent.GOODNIGHT -> "Wrapping up…"
        ReplyIntent.BOUNDARY_EXIT -> "Writing something respectful…"
        else -> "Reading the message…"
    }

    private fun suggestionLabel(intent: ReplyIntent) = when (intent) {
        ReplyIntent.CONTINUE -> "Keep it going"
        ReplyIntent.WRAP_UP -> "Wrap it up"
        ReplyIntent.GOODNIGHT -> "Goodnight"
        ReplyIntent.PICTURE_REPLY -> "Reply without a pic"
        ReplyIntent.BOUNDARY_EXIT -> "Step back respectfully"
        ReplyIntent.REPLY -> "Suggested reply"
    }

    private fun String.dropLastCodePoint(): String {
        if (isEmpty()) return this
        return substring(0, offsetByCodePoints(length, -1))
    }

    companion object {
        const val MAX_CONTEXT_CHARS = 280
    }
}
