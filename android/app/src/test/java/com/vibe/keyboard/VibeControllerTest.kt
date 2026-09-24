package com.vibe.keyboard

import com.vibe.keyboard.ai.AIException
import com.vibe.keyboard.ai.AIProvider
import com.vibe.keyboard.ai.MockAIProvider
import com.vibe.keyboard.ai.ReplyIntent
import com.vibe.keyboard.ai.Suggestion
import com.vibe.keyboard.ai.SuggestionRequest
import com.vibe.keyboard.overlay.CardState
import com.vibe.keyboard.overlay.FieldAction
import com.vibe.keyboard.overlay.SessionMemory
import com.vibe.keyboard.overlay.VibeController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VibeControllerTest {

    /** Records what was asked for, so tests can check the context reached the provider. */
    private class RecordingProvider(private val inner: AIProvider = MockAIProvider(latencyMs = 0L..0L)) : AIProvider {
        val requests = mutableListOf<SuggestionRequest>()
        var fail = false
        override val isPreview = true
        override suspend fun suggest(request: SuggestionRequest): Suggestion {
            requests += request
            if (fail) throw AIException("offline")
            return inner.suggest(request)
        }
    }

    private lateinit var provider: RecordingProvider

    @Before fun setUp() {
        SessionMemory.clear()
        provider = RecordingProvider()
    }

    private fun TestScope.controller(hour: Int = 15) =
        VibeController(this, provider, hourOfDay = { hour })

    private fun TestScope.collectActions(c: VibeController): MutableList<FieldAction> {
        val actions = mutableListOf<FieldAction>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.fieldActions.toList(actions) }
        return actions
    }

    @Test fun `copy, suggest, use inserts the text and hides the card`() = runTest {
        val c = controller()
        val actions = collectActions(c)
        c.onCopied("Just got home, today was so long 😩", automatic = true)
        advanceUntilIdle()

        val card = c.card.value as CardState.Suggestion
        assertTrue(card.isPreview)
        c.use()
        assertEquals(listOf(FieldAction.Insert(card.text)), actions)
        assertEquals(CardState.Hidden, c.card.value)
    }

    @Test fun `regenerate gives a different suggestion`() = runTest {
        val c = controller()
        c.onCopied("what are you doing?", automatic = true)
        advanceUntilIdle()
        val first = (c.card.value as CardState.Suggestion).text

        c.regenerate()
        assertTrue((c.card.value as CardState.Suggestion).refreshing)
        advanceUntilIdle()
        val second = (c.card.value as CardState.Suggestion).text
        assertNotEquals(first, second)
        assertEquals(listOf(first), provider.requests.last().avoid)
    }

    @Test fun `stays quiet for small talk unless asked`() = runTest {
        val c = controller()
        c.onCopied("lol", automatic = true)
        advanceUntilIdle()
        assertEquals(CardState.Hidden, c.card.value)
        assertTrue(provider.requests.isEmpty())

        c.onManualRequest(freshCopy = null)
        advanceUntilIdle()
        assertTrue(c.card.value is CardState.Suggestion)
    }

    @Test fun `the same copied message never pops up twice on its own`() = runTest {
        val c = controller()
        c.onCopied("what are you doing?", automatic = true)
        advanceUntilIdle()
        c.dismiss()
        c.onCopied("what are you doing?", automatic = true)
        advanceUntilIdle()
        assertEquals(CardState.Hidden, c.card.value)
    }

    @Test fun `tapping the mark with nothing copied explains how`() = runTest {
        val c = controller()
        c.onManualRequest(freshCopy = null)
        assertEquals(CardState.Notice("Copy their message, then tap ✦"), c.card.value)
        advanceUntilIdle()
        assertEquals(CardState.Hidden, c.card.value)
    }

    @Test fun `missing context is asked for, then used, then remembered`() = runTest {
        val c = controller()
        c.onCopied("Ulienda town na Randy?", automatic = true)
        advanceUntilIdle()
        assertTrue(c.card.value is CardState.ContextNeeded)
        assertTrue(provider.requests.isEmpty()) // nothing generated before the gap is filled

        c.explain()
        assertTrue(c.isCapturingKeys)
        "Randy is my cousin".forEach { c.typeIntoContext(it.toString()) }
        c.typeIntoContext("x")
        c.deleteFromContext()
        assertEquals("Randy is my cousin", (c.card.value as CardState.ContextNeeded).draft)

        c.submitContext()
        advanceUntilIdle()
        val suggestion = c.card.value as CardState.Suggestion
        assertTrue(suggestion.text.contains("Randy is my cousin"))
        assertEquals(mapOf("person:randy" to "Randy is my cousin"), provider.requests.last().context)

        // Asked about Randy again in this chat: Vibe already knows.
        c.onCopied("Kwani Randy alisema nini?", automatic = true)
        advanceUntilIdle()
        assertTrue(c.card.value !is CardState.ContextNeeded)
    }

    @Test fun `context not remembered is used once and not stored`() = runTest {
        val c = controller()
        c.onCopied("Did you tell Brian?", automatic = true)
        advanceUntilIdle()
        c.explain()
        c.toggleRemember()
        c.typeIntoContext("Not yet")
        c.submitContext()
        advanceUntilIdle()
        assertTrue(c.card.value is CardState.Suggestion)
        assertTrue(SessionMemory.keys().isEmpty())
    }

    @Test fun `continue, wrap up and wait on a conversation that is ending`() = runTest {
        val c = controller(hour = 23)
        c.onCopied("Okay nalala sasa, goodnight 😴", automatic = true)
        advanceUntilIdle()
        assertEquals(CardState.Ending(isNight = true), c.card.value)

        c.wrapUp()
        advanceUntilIdle()
        assertEquals("Goodnight", (c.card.value as CardState.Suggestion).label)
        assertEquals(ReplyIntent.GOODNIGHT, provider.requests.last().intent)

        c.onManualRequest(freshCopy = null)
        advanceUntilIdle()
        c.continueConversation()
        advanceUntilIdle()
        assertEquals(ReplyIntent.CONTINUE, provider.requests.last().intent)

        c.onManualRequest(freshCopy = null)
        advanceUntilIdle()
        val calls = provider.requests.size
        c.waitQuietly()
        assertTrue(c.card.value is CardState.Notice)
        advanceUntilIdle()
        assertEquals(CardState.Hidden, c.card.value)
        assertEquals(calls, provider.requests.size) // Wait writes nothing
    }

    @Test fun `a boundary only ever gets a respectful exit`() = runTest {
        val c = controller()
        c.onCopied("I'm not interested, please stop texting me", automatic = true)
        advanceUntilIdle()
        assertTrue(c.card.value is CardState.Boundary)
        assertTrue(provider.requests.isEmpty())

        c.replyToBoundary()
        advanceUntilIdle()
        assertEquals(ReplyIntent.BOUNDARY_EXIT, provider.requests.single().intent)
    }

    @Test fun `picture requests get a text reply`() = runTest {
        val c = controller()
        c.onCopied("Send me a pic 😂", automatic = true)
        advanceUntilIdle()
        assertTrue(c.card.value is CardState.PictureRequest)
        c.replyToPicture()
        advanceUntilIdle()
        assertEquals(ReplyIntent.PICTURE_REPLY, provider.requests.single().intent)
    }

    @Test fun `auto reply fills an empty box and can be undone`() = runTest {
        val c = controller()
        c.autoReply = true
        c.fieldIsEmpty = { true }
        val actions = collectActions(c)
        c.onCopied("what are you doing?", automatic = true)
        advanceUntilIdle()

        val card = c.card.value as CardState.Suggestion
        assertTrue(card.autoInserted)
        c.undoAutoInsert()
        assertEquals(listOf(FieldAction.Insert(card.text), FieldAction.Remove(card.text)), actions)
    }

    @Test fun `auto reply never writes over what the user started typing`() = runTest {
        val c = controller()
        c.autoReply = true
        c.fieldIsEmpty = { false }
        val actions = collectActions(c)
        c.onCopied("what are you doing?", automatic = true)
        advanceUntilIdle()
        assertTrue(actions.isEmpty())
        assertTrue(!(c.card.value as CardState.Suggestion).autoInserted)
    }

    @Test fun `a failure says so and can be retried`() = runTest {
        val c = controller()
        provider.fail = true
        c.onCopied("what are you doing?", automatic = true)
        advanceUntilIdle()
        assertTrue(c.card.value is CardState.Failed)

        provider.fail = false
        c.retry()
        advanceUntilIdle()
        assertTrue(c.card.value is CardState.Suggestion)
    }

    @Test fun `switching app forgets the chat`() = runTest {
        val c = controller()
        SessionMemory.put("person:randy", "cousin")
        c.onCopied("what are you doing?", automatic = true)
        advanceUntilIdle()
        c.onAppChanged()
        assertEquals(CardState.Hidden, c.card.value)
        assertTrue(SessionMemory.keys().isEmpty())
        c.onManualRequest(freshCopy = null)
        assertTrue(c.card.value is CardState.Notice)
    }
}
