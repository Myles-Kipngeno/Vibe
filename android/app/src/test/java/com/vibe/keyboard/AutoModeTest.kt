package com.vibe.keyboard

import com.vibe.keyboard.ai.MockAIProvider
import com.vibe.keyboard.ai.ReplyIntent
import com.vibe.keyboard.auto.AutoDecision
import com.vibe.keyboard.auto.AutoInput
import com.vibe.keyboard.auto.AutoRules
import com.vibe.keyboard.auto.AutoRulesConfig
import com.vibe.keyboard.auto.ReplyMode
import com.vibe.keyboard.engine.ChatMessage
import com.vibe.keyboard.engine.Speaker
import com.vibe.keyboard.engine.VibeSignal
import com.vibe.keyboard.memory.ConversationRecord
import com.vibe.keyboard.memory.ConversationSummarizer
import com.vibe.keyboard.memory.InMemoryConversationStore
import com.vibe.keyboard.overlay.CardState
import com.vibe.keyboard.overlay.FieldAction
import com.vibe.keyboard.overlay.SessionMemory
import com.vibe.keyboard.overlay.VibeCommand
import com.vibe.keyboard.overlay.VibeController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Generation, insertion and sending are separate, and sending is earned. */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoModeTest {

    private val clock = 1_800_000_000_000L
    private lateinit var store: InMemoryConversationStore

    @Before fun setUp() {
        SessionMemory.clear()
        store = InMemoryConversationStore()
        val chat = ConversationRecord(
            id = "sarah-1", platformId = "whatsapp", contactName = "Sarah",
            messages = listOf(ChatMessage(Speaker.THEM, "hey you"), ChatMessage(Speaker.ME, "heyy")),
            createdAt = clock,
        )
        store.save(ConversationSummarizer.rebuild(chat, clock))
    }

    private data class Rig(val c: VibeController, val actions: MutableList<FieldAction>, val commands: MutableList<VibeCommand>)

    /** A controller in WhatsApp with Sarah's (scanned) chat picked. */
    private fun TestScope.rig(mode: ReplyMode, canSend: Boolean, chatPicked: Boolean = true): Rig {
        val c = VibeController(this, MockAIProvider(latencyMs = 0L..0L), hourOfDay = { 15 }, store = store, now = { clock })
        val actions = mutableListOf<FieldAction>()
        val commands = mutableListOf<VibeCommand>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.fieldActions.toList(actions) }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.commands.toList(commands) }
        c.onInputStarted("com.whatsapp", fieldDeclaresSend = canSend)
        if (chatPicked) c.selectConversation("sarah-1")
        c.mode = mode
        return Rig(c, actions, commands)
    }

    @Test fun `suggest mode inserts on Use and never sends`() = runTest {
        val (c, actions) = rig(ReplyMode.SUGGEST, canSend = true)
        c.onCopied("What are you up to later?", automatic = true)
        advanceUntilIdle()
        val card = c.card.value as CardState.Suggestion
        assertTrue(actions.isEmpty()) // generated, not inserted
        c.use()
        advanceUntilIdle()
        assertEquals(listOf(FieldAction.Insert(card.text)), actions)
    }

    @Test fun `auto sends an allowed reply after a visible countdown`() = runTest {
        val (c, actions) = rig(ReplyMode.AUTO, canSend = true)
        c.onCopied("What are you up to later?", automatic = true)
        advanceTimeBy(300)
        runCurrent()
        val counting = c.card.value as CardState.Suggestion
        assertEquals(5, counting.sendIn)
        assertEquals(listOf(FieldAction.Insert(counting.text)), actions) // in the box, not sent yet

        advanceUntilIdle()
        assertEquals(FieldAction.Send(counting.text), actions.last())
        c.onSendResult(sent = true)
        assertTrue((c.card.value as CardState.Suggestion).sent)
    }

    @Test fun `cancelling the countdown sends nothing`() = runTest {
        val (c, actions) = rig(ReplyMode.AUTO, canSend = true)
        c.onCopied("What are you up to later?", automatic = true)
        advanceTimeBy(1_500)
        runCurrent()
        assertTrue(c.isCountingDown)
        c.cancelSend()
        advanceUntilIdle()
        assertTrue(actions.none { it is FieldAction.Send })
        assertEquals("Not sent · it's in your message box", (c.card.value as CardState.Suggestion).note)
    }

    @Test fun `switching Auto off mid-countdown sends nothing`() = runTest {
        val (c, actions) = rig(ReplyMode.AUTO, canSend = true)
        c.onCopied("What are you up to later?", automatic = true)
        advanceTimeBy(1_500)
        runCurrent()
        c.mode = ReplyMode.SUGGEST
        advanceUntilIdle()
        assertTrue(actions.none { it is FieldAction.Send })
    }

    @Test fun `with Auto disabled nothing is inserted or sent`() = runTest {
        val (c, actions) = rig(ReplyMode.SUGGEST, canSend = true)
        repeat(3) { i ->
            c.onCopied("What are you up to later, part $i?", automatic = true)
            advanceUntilIdle()
        }
        assertTrue(actions.isEmpty())
    }

    @Test fun `where the app has no Send action, Auto only fills the box`() = runTest {
        val (c, actions) = rig(ReplyMode.AUTO, canSend = false)
        c.onCopied("What are you up to later?", automatic = true)
        advanceUntilIdle()
        val card = c.card.value as CardState.Suggestion
        assertTrue(card.autoInserted)
        assertEquals(listOf(FieldAction.Insert(card.text)), actions)
        assertTrue(card.note!!.contains("press Send yourself"))
    }

    @Test fun `if the app did not actually send, the card says so`() = runTest {
        val (c, _) = rig(ReplyMode.AUTO, canSend = true)
        c.onCopied("What are you up to later?", automatic = true)
        advanceUntilIdle()
        c.onSendResult(sent = false)
        val card = c.card.value as CardState.Suggestion
        assertFalse(card.sent)
        assertEquals("The app didn't send it · it's in your message box", card.note)
    }

    @Test fun `without the chat's context Auto asks first`() = runTest {
        val (c, actions) = rig(ReplyMode.AUTO, canSend = true, chatPicked = false)
        c.onCopied("What are you up to later?", automatic = true)
        advanceUntilIdle()
        assertTrue(actions.isEmpty())
        assertEquals("Auto paused · no context for this chat yet", (c.card.value as CardState.Suggestion).note)
    }

    @Test fun `heavy messages, pictures, boundaries and private requests are never automatic`() = runTest {
        val (c, actions) = rig(ReplyMode.AUTO, canSend = true)
        c.onCopied("I lost my job today, I'm not okay", automatic = true)
        advanceUntilIdle()
        assertEquals("Auto paused · this sounds serious", (c.card.value as CardState.Suggestion).note)

        c.onCopied("Send me a pic 😂", automatic = true)
        advanceUntilIdle()
        assertTrue(c.card.value is CardState.PictureRequest)
        c.replyToPicture()
        advanceUntilIdle()

        c.onCopied("please stop texting me", automatic = true)
        advanceUntilIdle()
        assertTrue(c.card.value is CardState.Boundary)

        c.onCopied("What's your M-Pesa PIN?", automatic = true)
        advanceUntilIdle()
        assertTrue(c.card.value is CardState.Attention)

        assertTrue(actions.isEmpty())
    }

    @Test fun `turning Auto on is explained first and then saved`() = runTest {
        val (c, _, commands) = rig(ReplyMode.SUGGEST, canSend = false)
        c.toggleMode()
        val intro = c.card.value as CardState.AutoIntro
        assertFalse(intro.canSend)
        assertEquals(ReplyMode.SUGGEST, c.mode) // not yet
        c.confirmAuto()
        assertEquals(ReplyMode.AUTO, c.mode)
        assertEquals(VibeCommand.PersistMode(ReplyMode.AUTO), commands.last())
        c.toggleMode()
        assertEquals(ReplyMode.SUGGEST, c.mode) // off is immediate
    }

    // --- The rules on their own ---------------------------------------------------

    private fun input(
        signal: VibeSignal = VibeSignal.Reply,
        message: String = "what are you up to?",
        suggestion: String = "Not much, you?",
        contextReady: Boolean = true,
        fieldEmpty: Boolean = true,
        canSend: Boolean = true,
        intent: ReplyIntent = ReplyIntent.REPLY,
    ) = AutoInput(intent, signal, message, suggestion, contextReady, fieldEmpty, canSend)

    @Test fun `rules allow a casual reply with context in a sendable field`() {
        assertEquals(AutoDecision.Send(5), AutoRules.decide(input(), AutoRulesConfig()))
    }

    @Test fun `rules never auto-send private details in the reply`() {
        assertTrue(AutoRules.decide(input(suggestion = "My number is 0712 345 678"), AutoRulesConfig()) is AutoDecision.AskFirst)
        assertTrue(AutoRules.decide(input(suggestion = "my password is hunter2"), AutoRulesConfig()) is AutoDecision.AskFirst)
    }

    @Test fun `user rules can relax context and casual, never the fixed ones`() {
        val relaxed = AutoRulesConfig(onlyWithContext = false, onlyCasual = false)
        assertEquals(AutoDecision.Send(5), AutoRules.decide(input(contextReady = false), relaxed))
        assertTrue(AutoRules.decide(input(signal = VibeSignal.PictureRequest("pic?")), relaxed) is AutoDecision.AskFirst)
        assertTrue(AutoRules.decide(input(intent = ReplyIntent.GOODNIGHT), relaxed) is AutoDecision.AskFirst)
        assertTrue(AutoRules.decide(input(fieldEmpty = false), relaxed) is AutoDecision.AskFirst)
    }

    @Test fun `sending can be switched off while Auto still fills the box`() {
        assertTrue(AutoRules.decide(input(), AutoRulesConfig(sendWhereSupported = false)) is AutoDecision.InsertOnly)
        assertTrue(AutoRules.decide(input(canSend = false), AutoRulesConfig()) is AutoDecision.InsertOnly)
    }

    @Test fun `the countdown is never shorter than three seconds`() {
        assertEquals(AutoDecision.Send(3), AutoRules.decide(input(), AutoRulesConfig(sendDelaySeconds = 0)))
    }
}
