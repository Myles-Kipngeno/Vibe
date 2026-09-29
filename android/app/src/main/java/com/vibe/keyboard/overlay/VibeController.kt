package com.vibe.keyboard.overlay

import com.vibe.keyboard.ai.AIProvider
import com.vibe.keyboard.ai.ReplyContext
import com.vibe.keyboard.ai.ReplyGoal
import com.vibe.keyboard.ai.ReplyIntent
import com.vibe.keyboard.ai.SuggestionRequest
import com.vibe.keyboard.ai.Verdict
import com.vibe.keyboard.auto.AutoDecision
import com.vibe.keyboard.auto.AutoInput
import com.vibe.keyboard.auto.AutoRules
import com.vibe.keyboard.auto.AutoRulesConfig
import com.vibe.keyboard.auto.ReplyMode
import com.vibe.keyboard.context.ConversationSession
import com.vibe.keyboard.context.Platform
import com.vibe.keyboard.context.ReplyBundle
import com.vibe.keyboard.context.ScanState
import com.vibe.keyboard.engine.ContextDetector
import com.vibe.keyboard.engine.Conversation
import com.vibe.keyboard.engine.ConversationParser
import com.vibe.keyboard.engine.Flow
import com.vibe.keyboard.engine.Rhythm
import com.vibe.keyboard.engine.VibeSignal
import com.vibe.keyboard.memory.ConversationStore
import com.vibe.keyboard.memory.InMemoryConversationStore
import com.vibe.keyboard.memory.MemorySource
import com.vibe.keyboard.remote.ReplyBlockedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
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
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Decides when the Vibe card appears and what it says.
 *
 * Analysis happens on a *copy* (a new conversation to look at), a Scan, or an
 * explicit request -- never per keystroke. Generation, insertion and sending
 * are three separate steps: Suggest mode only ever generates, the user inserts,
 * the user sends. Auto mode may insert and, where the app's own message box
 * has a Send action and every rule passes, send after a visible countdown.
 */
class VibeController(
    private val scope: CoroutineScope,
    private val provider: AIProvider,
    private val memory: SessionMemory = SessionMemory,
    private val hourOfDay: () -> Int = { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) },
    private val debounceMs: Long = 250,
    private val noticeMs: Long = 1_800,
    store: ConversationStore = InMemoryConversationStore(),
    now: () -> Long = System::currentTimeMillis,
    /** Where disk work runs. Null runs it in place, which is what tests want. */
    private val io: CoroutineDispatcher? = null,
) {
    private val session = ConversationSession(store, memory, now)

    private val _card = MutableStateFlow<CardState>(CardState.Hidden)
    val card: StateFlow<CardState> = _card.asStateFlow()

    private val _status = MutableStateFlow(VibeStatus())
    val status: StateFlow<VibeStatus> = _status.asStateFlow()

    private val _fieldActions = MutableSharedFlow<FieldAction>(extraBufferCapacity = 4)
    val fieldActions: SharedFlow<FieldAction> = _fieldActions

    private val _commands = MutableSharedFlow<VibeCommand>(extraBufferCapacity = 4)
    val commands: SharedFlow<VibeCommand> = _commands

    /** Suggest (the default) or Auto. Set from saved settings; changed from the panel. */
    var mode: ReplyMode = ReplyMode.SUGGEST
        set(value) {
            field = value
            if (value == ReplyMode.SUGGEST) cancelSend()
            refreshStatus()
        }

    /** Kept for callers from before modes: on means Auto. */
    var autoReply: Boolean
        get() = mode == ReplyMode.AUTO
        set(value) { mode = if (value) ReplyMode.AUTO else ReplyMode.SUGGEST }

    var autoRules: AutoRulesConfig = AutoRulesConfig()

    /** Lets Auto avoid writing over something the user already started typing. */
    var fieldIsEmpty: () -> Boolean = { true }

    /** A message copied in the last few minutes, if any. Set by the keyboard. */
    var freshClip: () -> String? = { null }

    /** What is in the app's message box right now. Set by the keyboard. */
    var fieldText: () -> String = { "" }

    private var conversation: Conversation? = null
    private var lastSignal: VibeSignal = VibeSignal.Quiet
    private var lastIntent = ReplyIntent.REPLY
    private var lastCopiedFingerprint: Int? = null
    private var pendingContext: Map<String, String> = emptyMap()
    /** The goal chip, kept for this app session: someone flirting keeps flirting. */
    private var goal: ReplyGoal? = null
    private var scanning = false
    private val shown = mutableListOf<String>()
    private var job: Job? = null

    /** True while a card is taking typing, so the keyboard types there instead of into the app. */
    val isCapturingKeys: Boolean
        get() = when (val c = _card.value) {
            is CardState.ContextNeeded -> c.expanded
            is CardState.NameConversation, is CardState.EditMemory -> true
            else -> false
        }

    /** What has been typed into the card, for auto-capitalisation. */
    val captureDraft: String get() = (_card.value as? CapturesText)?.draft.orEmpty()

    /** An Auto countdown is running: the user touching the keys cancels it. */
    val isCountingDown: Boolean get() = (_card.value as? CardState.Suggestion)?.sendIn != null

    // --- Triggers ---------------------------------------------------------

    /** The keyboard opened on a field. A different app means a different conversation. */
    fun onInputStarted(packageName: String?, fieldDeclaresSend: Boolean) {
        val changed = safely { session.onInputStarted(packageName, fieldDeclaresSend) } ?: false
        if (changed) onAppChanged() else refreshStatus()
    }

    /**
     * The user copied something. [automatic] triggers are de-duplicated, so the
     * same copied message never pops the card twice (including after dismissing it).
     */
    fun onCopied(text: String, automatic: Boolean) {
        val fingerprint = text.trim().hashCode()
        if (automatic && fingerprint == lastCopiedFingerprint) return
        lastCopiedFingerprint = fingerprint

        val parsed = parse(text)
        if (parsed.isEmpty) return
        job?.cancel()
        job = scope.launch {
            delay(debounceMs) // a burst of copies settles on the last one
            analyse(parsed, manual = !automatic)
        }
    }

    /** Asked by hand (no panel involved). Always answers -- with a suggestion, a question, or a hint. */
    fun onManualRequest(freshCopy: String?) {
        if (freshCopy != null) {
            onCopied(freshCopy, automatic = false)
            return
        }
        val known = conversation ?: safely { session.recentConversation() }
        if (known == null) {
            notice("Copy their message, then tap Scan")
            return
        }
        job?.cancel()
        job = scope.launch { analyse(known, manual = true) }
    }

    /**
     * Scan: gather what context is actually available here, save it to the
     * picked chat, and act on the newest message from them.
     */
    fun onScan() {
        val clip = freshClip()
        val copied = clip?.let(::parse)?.takeIf { !it.isEmpty }
        if (clip != null) lastCopiedFingerprint = clip.trim().hashCode()
        val draft = fieldText()
        job?.cancel()
        scanning = true
        refreshStatus()
        _card.value = CardState.Thinking("Scanning conversation…")
        job = scope.launch {
            val outcome = try {
                off { session.scan(copied, draft) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } finally {
                scanning = false
            }
            if (outcome == null) {
                session.markError()
                refreshStatus()
                _card.value = CardState.Failed("Vibe couldn't read its memory. The keyboard still works.")
                return@launch
            }
            refreshStatus()
            when (outcome.state) {
                ScanState.IMPORT_REQUIRED, ScanState.CONTEXT_UNAVAILABLE -> {
                    _card.value = CardState.ContextUnavailable(
                        platformLabel = session.platform.label,
                        importSupported = session.capabilities.importSupported,
                        importHint = session.capabilities.importHint,
                        reason = if (outcome.record != null) {
                            "Nothing saved for ${outcome.record.contactName} yet, and nothing copied."
                        } else {
                            outcome.context.unavailable.firstOrNull() ?: "Conversation access unavailable here."
                        },
                    )
                }
                else -> {
                    if (morningDue()) return@launch
                    val convo = copied ?: safely { session.recentConversation() }
                    if (convo?.lastFromThem != null) analyse(convo, manual = true, afterScan = outcome.newMessages)
                    else notice(readyLine(outcome.newMessages))
                }
            }
        }
    }

    private fun readyLine(added: Int): String {
        val s = _status.value
        return when {
            s.scanState == ScanState.CONTEXT_STALE -> "Nothing new found. Copy their latest messages to update."
            s.conversationName != null -> "Context ready · ${s.messageCount} messages · ${s.memoryCount} memories"
            added > 0 -> "Using the $added message${if (added == 1) "" else "s"} you copied"
            else -> "Context ready"
        }
    }

    private suspend fun analyse(convo: Conversation, manual: Boolean, afterScan: Int = 0) {
        conversation = convo
        shown.clear()
        pendingContext = emptyMap()
        val theirs = convo.lastFromThem
        if (theirs == null) {
            if (manual) notice("Nothing from them to reply to yet") else hide()
            return
        }
        var note: String? = null
        if (safely { session.isDifferentChat(convo) } == true) {
            val name = _status.value.conversationName
            safely { session.deselect() }
            refreshStatus()
            note = "Different chat? ${name ?: "Chat"} memory is off"
        } else {
            // The chat is still going on after the import: what was copied
            // joins its history now, so the next reply knows it too.
            off { safely { session.append(convo.messages, convo.theirNames) } }
            refreshStatus()
        }
        val bundle = off { safely { session.contextFor(convo) } } ?: ReplyBundle(ReplyContext.None, memory.keys())
        var signal = ContextDetector.detect(theirs.text, hourOfDay(), bundle.knownKeys)
        if (signal == VibeSignal.Quiet && Rhythm.assess(bundle.reply.recent) == Flow.SLOWING) {
            signal = VibeSignal.Ending(isNight = ContextDetector.isNight(hourOfDay()), slowing = true)
        }
        lastSignal = signal
        val scanNote = note ?: if (afterScan > 0 && _status.value.conversationName != null) {
            "Scanned · $afterScan new message${if (afterScan == 1) "" else "s"} saved"
        } else null
        when (signal) {
            VibeSignal.Quiet -> if (manual) generate(ReplyIntent.REPLY, scanNote) else hide()
            VibeSignal.Reply -> generate(ReplyIntent.REPLY, scanNote, auto = true)
            is VibeSignal.Emotional -> generate(ReplyIntent.REPLY, "Sounds serious · read before using", auto = true)
            is VibeSignal.NeedsContext -> _card.value = CardState.ContextNeeded(
                signal,
                remember = signal.rememberByDefault,
                rememberLabel = _status.value.conversationName?.let { "Remember for $it" } ?: "Remember for this chat",
            )
            is VibeSignal.PictureRequest -> _card.value = CardState.PictureRequest(signal.quote)
            is VibeSignal.Ending -> _card.value = CardState.Ending(signal.isNight, signal.slowing)
            is VibeSignal.Boundary -> _card.value = CardState.Boundary(signal.quote)
            is VibeSignal.SensitiveRequest -> _card.value = CardState.Attention(
                title = "They asked for ${signal.what}",
                quote = signal.quote,
                detail = "Vibe won't answer this one. It never shares private details for you.",
            )
        }
    }

    // --- Suggestion actions -------------------------------------------------

    fun use() {
        val s = _card.value as? CardState.Suggestion ?: return
        if (s.refreshing) return
        _fieldActions.tryEmit(FieldAction.Insert(s.text))
        hide()
        afterUse(s.text)
        report(s, s.selected, Verdict.USED)
    }

    /** Best effort, off the card's path: the backend learns what the user actually sends. */
    private fun report(card: CardState.Suggestion, index: Int, verdict: Verdict) {
        val text = card.options.getOrNull(index) ?: return
        val id = card.optionIds.getOrNull(index) ?: return
        scope.launch { runCatching { provider.feedback(text, id, verdict) } }
    }

    /** Using a suggestion is the user choosing it: a (weak) style signal, and the goodnight marker. */
    private fun afterUse(text: String) {
        val intent = lastIntent
        scope.launch {
            off {
                safely {
                    session.learnFromUsed(text)
                    when (intent) {
                        ReplyIntent.GOODNIGHT -> session.markGoodnight()
                        ReplyIntent.MORNING -> session.markMorningOffered()
                        else -> Unit
                    }
                }
            }
        }
    }

    /** Pick another of the model's options. Nothing is inserted until Use. */
    fun selectOption(index: Int) = _card.update {
        if (it is CardState.Suggestion && !it.autoInserted && index in it.options.indices) {
            it.copy(selected = index, text = it.options[index])
        } else it
    }

    /**
     * A goal chip. Picking one rewrites the reply toward it; picking it again
     * goes back to just keeping the conversation going.
     */
    fun chooseGoal(next: ReplyGoal) {
        val s = _card.value as? CardState.Suggestion ?: return
        if (s.refreshing || s.sent || s.sendIn != null) return
        goal = if (goal == next) null else next
        if (s.autoInserted) _fieldActions.tryEmit(FieldAction.Remove(s.text))
        generate(lastIntent)
    }

    /** Something heavy gets a supportive reply unless the user picked otherwise. */
    private fun goalFor(intent: ReplyIntent, signal: VibeSignal): ReplyGoal? = when {
        intent != ReplyIntent.REPLY && intent != ReplyIntent.CONTINUE -> null
        goal != null -> goal
        signal is VibeSignal.Emotional -> ReplyGoal.COMFORT
        else -> null
    }

    fun regenerate() {
        val s = _card.value as? CardState.Suggestion
        // Asking for new ones is throwing these out -- unless one was already sent.
        if (s != null && !s.sent && !s.refreshing) s.options.indices.forEach { report(s, it, Verdict.REJECTED) }
        if (s?.sendIn != null) job?.cancel()
        if (s?.autoInserted == true && !s.sent) _fieldActions.tryEmit(FieldAction.Remove(s.text))
        generate(lastIntent)
    }

    fun undoAutoInsert() {
        val s = _card.value as? CardState.Suggestion ?: return
        if (!s.autoInserted || s.sent) return
        if (s.sendIn != null) job?.cancel()
        _fieldActions.tryEmit(FieldAction.Remove(s.text))
        _card.value = s.copy(autoInserted = false, sendIn = null, note = null)
    }

    /** Stop an Auto send. The text stays in the box for the user to send, edit or delete. */
    fun cancelSend() {
        val s = _card.value as? CardState.Suggestion ?: return
        if (s.sendIn == null) return
        job?.cancel()
        _card.value = s.copy(sendIn = null, note = "Not sent · it's in your message box")
    }

    /** The keyboard reports whether the app's Send action actually emptied the box. */
    fun onSendResult(sent: Boolean) {
        val s = _card.value as? CardState.Suggestion ?: return
        if (sent) {
            _card.value = s.copy(sendIn = null, sent = true, note = "Sent by Auto")
            afterUse(s.text)
            report(s, s.selected, Verdict.USED)
            job = scope.launch {
                delay(noticeMs)
                if ((_card.value as? CardState.Suggestion)?.sent == true) hide()
            }
        } else {
            _card.value = s.copy(sendIn = null, note = "The app didn't send it · it's in your message box")
        }
    }

    fun dismiss() {
        job?.cancel()
        hide()
    }

    // --- Context alerts -------------------------------------------------------

    fun explain() = _card.update { (it as? CardState.ContextNeeded)?.copy(expanded = true) ?: it }

    fun typeIntoContext(text: String) = _card.update {
        when {
            it is CardState.ContextNeeded && it.expanded -> it.copy(draft = (it.draft + text).take(MAX_CONTEXT_CHARS))
            it is CardState.NameConversation -> it.copy(draft = (it.draft + text).take(MAX_NAME_CHARS))
            it is CardState.EditMemory -> it.copy(draft = (it.draft + text).take(MAX_CONTEXT_CHARS))
            else -> it
        }
    }

    fun deleteFromContext() = _card.update {
        when {
            it is CardState.ContextNeeded && it.draft.isNotEmpty() -> it.copy(draft = it.draft.dropLastCodePoint())
            it is CardState.NameConversation && it.draft.isNotEmpty() -> it.copy(draft = it.draft.dropLastCodePoint())
            it is CardState.EditMemory && it.draft.isNotEmpty() -> it.copy(draft = it.draft.dropLastCodePoint())
            else -> it
        }
    }

    fun toggleRemember() = _card.update { (it as? CardState.ContextNeeded)?.copy(remember = !it.remember) ?: it }

    /** Enter or Continue on whichever card is taking typing. */
    fun submitContext() {
        when (val c = _card.value) {
            is CardState.ContextNeeded -> submitAnswer(c)
            is CardState.NameConversation -> createConversation(c.draft)
            is CardState.EditMemory -> saveMemoryEdit(c)
            else -> Unit
        }
    }

    private fun submitAnswer(c: CardState.ContextNeeded) {
        val answer = c.draft.trim()
        if (answer.isEmpty()) return
        if (c.remember) {
            safely { session.remember(c.signal.key, answer) }
            refreshStatus()
        }
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

    // --- The panel ------------------------------------------------------------

    fun openPanel() {
        job?.cancel()
        refreshStatus()
        _card.value = CardState.Panel(_status.value)
    }

    fun chooseConversation() {
        val options = safely {
            val activeId = _status.value.conversationId
            session.choices().map {
                val p = Platform.fromId(it.platformId)
                ConversationOption(
                    id = it.id, name = it.contactName, platformLabel = p.label,
                    messages = it.messages.size, memories = it.memories.size,
                    active = it.id == activeId, otherApp = it.platformId != session.platform.id,
                )
            }
        } ?: return failStore()
        _card.value = CardState.ChooseConversation(session.platform.label, options)
    }

    fun newConversation() {
        _card.value = CardState.NameConversation()
    }

    private fun createConversation(name: String) {
        if (name.isBlank()) return
        safely { session.create(name) } ?: return failStore()
        openPanel()
    }

    fun selectConversation(id: String) {
        safely { session.select(id) } ?: return failStore()
        conversation = null
        refreshStatus()
        if (!morningDue()) openPanel()
    }

    /** No chat, no memory: help with what was copied, and nothing is saved. */
    fun continueWithoutContext() {
        safely { session.continueWithoutContext() }
        refreshStatus()
        val clip = freshClip()
        when {
            clip != null -> onCopied(clip, automatic = false)
            conversation != null -> onManualRequest(null)
            else -> notice("Okay. Copy their message and Vibe will help.")
        }
    }

    fun openImport() {
        _commands.tryEmit(VibeCommand.OpenImport(session.platform.id))
        hide()
    }

    fun openSettings() {
        _commands.tryEmit(VibeCommand.OpenSettings)
        hide()
    }

    /** Toolbar tap: Auto switches straight back to Suggest; Suggest → Auto is explained first. */
    fun toggleMode() = requestMode(if (mode == ReplyMode.AUTO) ReplyMode.SUGGEST else ReplyMode.AUTO)

    fun requestMode(target: ReplyMode) {
        if (target == mode) return
        if (target == ReplyMode.AUTO) {
            _card.value = CardState.AutoIntro(session.platform.label, session.capabilities.canSend, autoRules)
        } else {
            mode = ReplyMode.SUGGEST
            _commands.tryEmit(VibeCommand.PersistMode(ReplyMode.SUGGEST))
            if (_card.value is CardState.Panel) openPanel() else notice("Suggest mode · Vibe won't send anything")
        }
    }

    fun confirmAuto() {
        mode = ReplyMode.AUTO
        _commands.tryEmit(VibeCommand.PersistMode(ReplyMode.AUTO))
        openPanel()
    }

    // --- Memory -----------------------------------------------------------------

    fun openMemory(confirmClear: Boolean = false) {
        val record = safely { session.active() }
        if (record == null) {
            notice("Pick a chat to see its memory")
            return
        }
        _card.value = CardState.MemoryView(
            conversationName = record.contactName,
            items = record.memories.sortedWith(compareBy({ it.source != MemorySource.USER }, { it.kind })),
            messageCount = record.messages.size,
            confirmClear = confirmClear,
        )
    }

    fun forgetMemory(itemId: String) {
        safely { session.forget(itemId) } ?: return failStore()
        refreshStatus()
        openMemory()
    }

    fun editMemory(itemId: String) {
        val item = safely { session.active() }?.memories?.firstOrNull { it.id == itemId } ?: return
        _card.value = CardState.EditMemory(item, item.value)
    }

    private fun saveMemoryEdit(c: CardState.EditMemory) {
        safely { session.edit(c.item.id, c.draft) } ?: return failStore()
        refreshStatus()
        openMemory()
    }

    fun askClearMemory() = openMemory(confirmClear = true)

    fun confirmClearMemory() {
        safely { session.clearMemory() } ?: return failStore()
        refreshStatus()
        openMemory()
    }

    /** Also record what the user sends in a picked chat. Setting; on by default. */
    var rememberSent: Boolean = true

    /**
     * The keyboard saw the message box empty right after the user wrote
     * something: it was sent. It joins the picked chat's history, so Vibe
     * knows both sides of the conversation that followed the import.
     */
    fun onMessageSent(text: String) {
        if (!rememberSent || text.isBlank()) return
        scope.launch {
            if (off { safely { session.recordSent(text) } } == true) refreshStatus()
        }
    }

    // --- Session ----------------------------------------------------------

    /** A different app means a different conversation. Nothing carries over. */
    fun onAppChanged() {
        job?.cancel()
        goal = null
        conversation = null
        lastCopiedFingerprint = null
        pendingContext = emptyMap()
        shown.clear()
        safely { session.reset() } ?: memory.clear()
        refreshStatus()
        hide()
    }

    // --- Internals --------------------------------------------------------

    /**
     * [auto] is true only for a reply Vibe decided to write on its own, from an
     * incoming message. Anything the user asked for by tapping (Continue,
     * Goodnight, an answered question, Regenerate) is theirs to use.
     */
    private fun generate(intent: ReplyIntent, note: String? = null, auto: Boolean = false) {
        val convo = conversation ?: return
        lastIntent = intent
        val previous = _card.value
        _card.value = if (previous is CardState.Suggestion) {
            previous.copy(refreshing = true, autoInserted = false, sendIn = null, note = null)
        } else {
            CardState.Thinking(thinkingLabel(intent))
        }
        val signal = lastSignal

        job?.cancel()
        job = scope.launch {
            try {
                val reply = off { safely { session.contextFor(convo).reply } } ?: ReplyContext.None
                val suggestion = provider.suggest(
                    SuggestionRequest(
                        conversation = convo,
                        intent = intent,
                        context = memory.all() + pendingContext,
                        goal = goalFor(intent, signal),
                        avoid = shown.toList(),
                        reply = reply,
                    ),
                )
                shown += suggestion.options
                val base = CardState.Suggestion(
                    text = suggestion.text,
                    label = suggestionLabel(intent),
                    isPreview = suggestion.isPreview,
                    options = suggestion.options.ifEmpty { listOf(suggestion.text) },
                    optionIds = suggestion.optionIds,
                    goal = goalFor(intent, signal),
                    steerable = intent == ReplyIntent.REPLY || intent == ReplyIntent.CONTINUE,
                    note = note ?: suggestion.note,
                )
                if (mode != ReplyMode.AUTO || !auto) {
                    _card.value = base
                    return@launch
                }
                val decision = AutoRules.decide(
                    AutoInput(
                        intent = intent,
                        signal = signal,
                        theirMessage = convo.lastFromThem?.text.orEmpty(),
                        suggestion = suggestion.text,
                        contextReady = _status.value.scanState == ScanState.CONTEXT_READY && _status.value.conversationId != null,
                        fieldEmpty = fieldIsEmpty(),
                        canSend = session.capabilities.canSend,
                    ),
                    autoRules,
                )
                when (decision) {
                    is AutoDecision.AskFirst -> _card.value = base.copy(note = "Auto paused · ${decision.reason}")
                    is AutoDecision.InsertOnly -> {
                        _fieldActions.tryEmit(FieldAction.Insert(suggestion.text))
                        _card.value = base.copy(autoInserted = true, note = "${decision.reason} · press Send yourself")
                    }
                    is AutoDecision.Send -> {
                        _fieldActions.tryEmit(FieldAction.Insert(suggestion.text))
                        countDownThenSend(base.copy(autoInserted = true), decision.delaySeconds)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ReplyBlockedException) {
                // Not a failure: the server's rules said no, and why.
                _card.value = CardState.Failed(e.message ?: "", title = "Vibe won't write this one")
            } catch (e: Exception) {
                _card.value = CardState.Failed("Couldn't get a suggestion. Try again.")
            }
        }
    }

    /** Runs inside the generation job, so a new message, a tap or Cancel stops it. */
    private suspend fun countDownThenSend(card: CardState.Suggestion, seconds: Int) {
        for (left in seconds downTo 1) {
            _card.value = card.copy(sendIn = left)
            delay(1_000)
        }
        _card.value = card.copy(sendIn = 0)
        _fieldActions.tryEmit(FieldAction.Send(card.text))
    }

    private fun morningDue(): Boolean {
        if (safely { session.morningDue(hourOfDay()) } != true) return false
        val convo = safely { session.recentConversation() } ?: return false
        conversation = convo
        shown.clear()
        pendingContext = emptyMap()
        lastSignal = VibeSignal.Quiet
        safely { session.markMorningOffered() }
        generate(ReplyIntent.MORNING, note = "You said goodnight last night")
        return true
    }

    private fun parse(text: String): Conversation {
        val myNames = safely { session.active()?.myNames }.orEmpty()
        return ConversationParser.parse(text, myNames)
    }

    private fun refreshStatus() {
        val next = safely {
            val record = session.active()
            VibeStatus(
                platformLabel = session.platform.label,
                conversationId = record?.id,
                conversationName = record?.contactName,
                scanState = session.state(scanning),
                messageCount = record?.messages?.size ?: 0,
                memoryCount = record?.memories?.size ?: 0,
                mode = mode,
                canSend = session.capabilities.canSend,
                importSupported = session.capabilities.importSupported,
                importHint = session.capabilities.importHint,
                withoutContext = session.withoutContext,
            )
        } ?: _status.value.copy(scanState = ScanState.ERROR, mode = mode)
        _status.value = next
        _card.update { if (it is CardState.Panel) CardState.Panel(next) else it }
    }

    private fun failStore() {
        safely { session.markError() }
        refreshStatus()
        _card.value = CardState.Failed("Vibe couldn't read its memory. The keyboard still works.")
    }

    /**
     * Memory is never allowed to break the keyboard: a store error becomes an
     * ERROR state and a card, and typing carries on.
     */
    private inline fun <T> safely(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun <T> off(block: () -> T): T = if (io != null) withContext(io) { block() } else block()

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
        ReplyIntent.MORNING -> "Saying good morning…"
        else -> "Reading the message…"
    }

    private fun suggestionLabel(intent: ReplyIntent) = when (intent) {
        ReplyIntent.CONTINUE -> "Keep it going"
        ReplyIntent.WRAP_UP -> "Wrap it up"
        ReplyIntent.GOODNIGHT -> "Goodnight"
        ReplyIntent.PICTURE_REPLY -> "Reply without a pic"
        ReplyIntent.BOUNDARY_EXIT -> "Step back respectfully"
        ReplyIntent.MORNING -> "Good morning?"
        ReplyIntent.REPLY -> "Suggested reply"
    }

    private fun String.dropLastCodePoint(): String {
        if (isEmpty()) return this
        return substring(0, offsetByCodePoints(length, -1))
    }

    companion object {
        const val MAX_CONTEXT_CHARS = 280
        const val MAX_NAME_CHARS = 40
    }
}
