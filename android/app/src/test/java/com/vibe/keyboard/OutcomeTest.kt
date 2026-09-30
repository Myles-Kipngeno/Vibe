package com.vibe.keyboard

import com.vibe.keyboard.ai.AIProvider
import com.vibe.keyboard.ai.Suggestion
import com.vibe.keyboard.ai.SuggestionRequest
import com.vibe.keyboard.memory.ConversationRecord
import com.vibe.keyboard.memory.InMemoryConversationStore
import com.vibe.keyboard.overlay.CardState
import com.vibe.keyboard.overlay.SessionMemory
import com.vibe.keyboard.overlay.VibeController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Learning what lands with her, per chat, as a signal and never as proof. */
@OptIn(ExperimentalCoroutinesApi::class)
class OutcomeTest {

    private class Labelled : AIProvider {
        val requests = mutableListOf<SuggestionRequest>()
        override val isPreview = false
        override suspend fun suggest(request: SuggestionRequest): Suggestion {
            requests += request
            val n = requests.size
            return Suggestion("playful $n", false, options = listOf("playful $n", "flirty $n"), optionLabels = listOf("Playful", "Flirty"))
        }
    }

    private val clock = 1_800_000_000_000L
    private lateinit var store: InMemoryConversationStore
    private lateinit var provider: Labelled

    @Before fun setUp() {
        SessionMemory.clear()
        store = InMemoryConversationStore()
        store.save(ConversationRecord(id = "c1", platformId = "whatsapp", contactName = "Sarah", createdAt = clock))
        provider = Labelled()
    }

    private fun TestScope.controller(): VibeController {
        val c = VibeController(this, provider, hourOfDay = { 15 }, store = store, now = { clock })
        c.onInputStarted("com.whatsapp", false)
        c.selectConversation("c1")
        return c
    }

    private suspend fun TestScope.round(c: VibeController, her: String, pick: Int = 0) {
        c.onCopied(her, automatic = true)
        advanceUntilIdle()
        c.selectOption(pick)
        c.use()
        advanceUntilIdle()
    }

    @Test fun `her next message after a used style is tallied for that style`() = runTest {
        val c = controller()
        round(c, "what are you doing later?")
        c.onCopied("😂😂 you're crazy, who even says that?", automatic = true)
        advanceUntilIdle()
        assertEquals(1, store.get("c1")!!.outcomes["Playful"]!!.warm)
        assertNull(store.get("c1")!!.pendingUse)
    }

    @Test fun `a one-word reply is not counted as warm`() = runTest {
        val c = controller()
        round(c, "what are you doing later?")
        c.onCopied("ok", automatic = true)
        advanceUntilIdle()
        val t = store.get("c1")!!.outcomes["Playful"]!!
        assertEquals(0, t.warm)
        assertEquals(1, t.total)
    }

    @Test fun `after a few uses the pattern reaches the model, labelled as a signal`() = runTest {
        val c = controller()
        listOf("😂 stop", "haha you wish, what else?", "lol ok").forEach { reply ->
            round(c, "so what's the plan for later tonight?")
            c.onCopied(reply, automatic = true)
            advanceUntilIdle()
        }
        c.onCopied("anyway what are you doing tomorrow?", automatic = true)
        advanceUntilIdle()
        val note = provider.requests.last().reply.outcomeNote!!
        assertTrue(note.contains("playful 2/3"))
        assertTrue(note.contains("not proof"))
    }

    @Test fun `picking an unlabelled template teaches nothing`() = runTest {
        val c = controller()
        c.onCopied("what are you doing later?", automatic = true)
        advanceUntilIdle()
        val card = c.card.value as CardState.Suggestion
        assertEquals(listOf("Playful", "Flirty"), card.optionLabels)
        assertNull(store.get("c1")!!.pendingUse)
    }
}
