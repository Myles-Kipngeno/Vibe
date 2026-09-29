package com.vibe.keyboard

import com.vibe.keyboard.ai.AIProvider
import com.vibe.keyboard.ai.MockAIProvider
import com.vibe.keyboard.ai.ReplyIntent
import com.vibe.keyboard.ai.Suggestion
import com.vibe.keyboard.ai.SuggestionRequest
import com.vibe.keyboard.context.ScanState
import com.vibe.keyboard.engine.ChatMessage
import com.vibe.keyboard.engine.Speaker
import com.vibe.keyboard.memory.ConversationRecord
import com.vibe.keyboard.memory.ConversationStore
import com.vibe.keyboard.memory.ConversationSummarizer
import com.vibe.keyboard.memory.InMemoryConversationStore
import com.vibe.keyboard.memory.MemoryItem
import com.vibe.keyboard.memory.MemoryKind
import com.vibe.keyboard.memory.MemorySource
import com.vibe.keyboard.memory.UserStyleProfile
import com.vibe.keyboard.overlay.CardState
import com.vibe.keyboard.overlay.SessionMemory
import com.vibe.keyboard.overlay.VibeController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Per-conversation memory, end to end through the controller: what gets
 * asked, what gets remembered, what reaches the provider -- and what never does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationMemoryTest {

    private class RecordingProvider : AIProvider {
        private val inner = MockAIProvider(latencyMs = 0L..0L)
        val requests = mutableListOf<SuggestionRequest>()
        override val isPreview = true
        override suspend fun suggest(request: SuggestionRequest): Suggestion {
            requests += request
            return inner.suggest(request)
        }
    }

    private lateinit var provider: RecordingProvider
    private lateinit var store: InMemoryConversationStore
    private var clock = 1_800_000_000_000L

    @Before fun setUp() {
        SessionMemory.clear()
        provider = RecordingProvider()
        store = InMemoryConversationStore()
    }

    private fun TestScope.controller(s: ConversationStore = store, hour: Int = 15) =
        VibeController(this, provider, hourOfDay = { hour }, store = s, now = { clock })

    private fun them(text: String) = ChatMessage(Speaker.THEM, text)
    private fun me(text: String) = ChatMessage(Speaker.ME, text)

    /** Sarah on WhatsApp: a scanned history, plus something the user told Vibe about Randy. */
    private fun sarah(): ConversationRecord {
        val base = ConversationRecord(
            id = "sarah-1", platformId = "whatsapp", contactName = "Sarah", theirNames = listOf("Sarah K"),
            messages = listOf(
                them("Are we still on for the weekend?"), me("Yes! Naivas first then campus"),
                them("The weekend plan is solid 😂"), me("Haha weekend loading"),
                them("Remember when we got lost at Naivas 😂"), me("Never again"),
            ),
            createdAt = clock,
        )
        val built = ConversationSummarizer.rebuild(base, clock)
        val randy = MemoryItem("m-randy", MemoryKind.PERSON, "person:randy", "Randy is my roommate, he was meant to call the landlord", MemorySource.USER, clock)
        return built.copy(memories = built.memories + randy)
    }

    private fun jane() = ConversationRecord(id = "jane-1", platformId = "instagram", contactName = "Jane", createdAt = clock)

    // --- Isolation ------------------------------------------------------------

    @Test fun `Sarah's memory never appears in Jane's conversation`() = runTest {
        store.save(sarah())
        store.save(jane())
        val c = controller()

        c.onInputStarted("com.instagram.android", fieldDeclaresSend = false)
        c.selectConversation("jane-1")
        c.onCopied("😂 So what did Randy say?", automatic = true)
        advanceUntilIdle()
        // Randy is known in Sarah's chat, not Jane's: Jane's chat has to ask.
        assertTrue(c.card.value is CardState.ContextNeeded)
        assertTrue(provider.requests.isEmpty())

        c.onCopied("What are you doing later?", automatic = true)
        advanceUntilIdle()
        val req = provider.requests.single()
        assertEquals("Jane · Instagram", req.reply.conversationLabel)
        assertTrue(req.reply.memories.isEmpty())
        // Jane's chat now has its own summary, built from Jane's messages only.
        val janeSummary = req.reply.summary.toString()
        assertFalse(janeSummary.contains("Naivas") || janeSummary.contains("weekend"))
        val everything = (req.reply.recent + req.reply.snippets).joinToString { it.text } + req.context.values
        assertFalse(everything.contains("Naivas"))
        assertFalse(everything.contains("Randy is my roommate"))
    }

    @Test fun `voice samples come from the user's side of this chat only`() = runTest {
        store.save(sarah())
        store.save(jane())
        val c = controller()
        c.onInputStarted("com.whatsapp", false)
        c.selectConversation("sarah-1")
        c.onCopied("What are you doing later?", automatic = true)
        advanceUntilIdle()
        val sarahSamples = provider.requests.last().reply.styleSamples
        val sarahMine = sarah().messages.filter { it.speaker == Speaker.ME }.map { it.text }
        assertTrue(sarahSamples.all { it in sarahMine } || sarahSamples.isEmpty())

        // Jane's chat has no history: only de-identified samples could be used, never Sarah's lines.
        c.onInputStarted("com.instagram.android", false)
        c.selectConversation("jane-1")
        c.onCopied("What are you doing later?", automatic = true)
        advanceUntilIdle()
        val janeSamples = provider.requests.last().reply.styleSamples
        assertTrue(janeSamples.none { it.contains("Naivas") })
    }

    @Test fun `the same message in Sarah's chat uses Sarah's memory`() = runTest {
        store.save(sarah())
        val c = controller()
        c.onInputStarted("com.whatsapp", fieldDeclaresSend = false)
        c.selectConversation("sarah-1")
        c.onCopied("😂 So what did Randy say?", automatic = true)
        advanceUntilIdle()

        val card = c.card.value as CardState.Suggestion
        val req = provider.requests.last()
        assertEquals(listOf("person:randy"), req.reply.memories.map { it.key })
        assertTrue(card.text.contains("Randy is my roommate"))
        assertNotNull(req.reply.summary)
    }

    @Test fun `remembering context saves it to the picked chat and nowhere else`() = runTest {
        store.save(sarah())
        store.save(jane())
        val c = controller()
        c.onInputStarted("com.instagram.android", false)
        c.selectConversation("jane-1")
        c.onCopied("Did you tell Brian?", automatic = true)
        advanceUntilIdle()
        val ask = c.card.value as CardState.ContextNeeded
        assertEquals("Remember for Jane", ask.rememberLabel)
        c.explain()
        "Not yet".forEach { c.typeIntoContext(it.toString()) }
        c.submitContext()
        advanceUntilIdle()

        assertEquals(listOf("person:brian"), store.get("jane-1")!!.memories.filter { it.source == MemorySource.USER }.map { it.key })
        assertTrue(store.get("sarah-1")!!.memories.none { it.key == "person:brian" })
        assertTrue(SessionMemory.keys().isEmpty()) // persisted to Jane, not the session
    }

    @Test fun `switching apps drops the picked chat`() = runTest {
        store.save(sarah())
        val c = controller()
        c.onInputStarted("com.whatsapp", false)
        c.selectConversation("sarah-1")
        assertEquals("Sarah", c.status.value.conversationName)
        c.onInputStarted("com.instagram.android", false)
        assertNull(c.status.value.conversationName)
    }

    @Test fun `copied messages from someone else switch memory off`() = runTest {
        store.save(sarah())
        val c = controller()
        c.onInputStarted("com.whatsapp", false)
        c.selectConversation("sarah-1")
        c.onCopied(
            "[21:04, 24/09/2026] Jane Doe: hey you\n[21:05, 24/09/2026] Jane Doe: what are you up to tonight?",
            automatic = true,
        )
        advanceUntilIdle()
        assertNull(c.status.value.conversationId)
        val card = c.card.value as CardState.Suggestion
        assertTrue(card.note!!.startsWith("Different chat?"))
        assertTrue(provider.requests.last().reply.memories.isEmpty())
    }

    @Test fun `the active chat expires after the keyboard sits idle`() = runTest {
        store.save(sarah())
        val c = controller()
        c.onInputStarted("com.whatsapp", false)
        c.selectConversation("sarah-1")
        clock += 11 * 60_000L
        c.onInputStarted("com.whatsapp", false)
        assertNull(c.status.value.conversationId)
    }

    // --- The chat keeps going after the import --------------------------------

    @Test fun `messages copied after the import join the chat's memory without a Scan`() = runTest {
        store.save(sarah())
        val c = controller()
        c.onInputStarted("com.whatsapp", false)
        c.selectConversation("sarah-1")
        val before = store.get("sarah-1")!!.messages.size

        c.onCopied("Btw nimeanza job mpya leo 😃", automatic = true)
        advanceUntilIdle()
        assertEquals(before + 1, store.get("sarah-1")!!.messages.size)
        assertEquals("Btw nimeanza job mpya leo 😃", store.get("sarah-1")!!.messages.last().text)
        assertEquals(before + 1, c.status.value.messageCount)

        // The same message copied again is not stored twice.
        c.onCopied("Btw nimeanza job mpya leo 😃", automatic = false)
        advanceUntilIdle()
        assertEquals(before + 1, store.get("sarah-1")!!.messages.size)
    }

    @Test fun `what the user sends is remembered, so the next reply knows both sides`() = runTest {
        store.save(sarah())
        val c = controller()
        c.onInputStarted("com.whatsapp", false)
        c.selectConversation("sarah-1")
        c.onCopied("Btw nimeanza job mpya leo 😃", automatic = true)
        advanceUntilIdle()
        c.onMessageSent("Wueh congrats! Ni wapi?")
        advanceUntilIdle()
        c.onCopied("Ni kwa bank moja Westlands 😊", automatic = true)
        advanceUntilIdle()

        val history = store.get("sarah-1")!!.messages.takeLast(3)
        assertEquals(listOf(Speaker.THEM, Speaker.ME, Speaker.THEM), history.map { it.speaker })
        assertEquals("Wueh congrats! Ni wapi?", history[1].text)
        val recent = provider.requests.last().reply.recent.map { it.text }
        assertTrue(recent.containsAll(listOf("Btw nimeanza job mpya leo 😃", "Wueh congrats! Ni wapi?", "Ni kwa bank moja Westlands 😊")))
    }

    @Test fun `nothing is recorded without a picked chat, or with the setting off`() = runTest {
        store.save(sarah())
        val c = controller()
        c.onInputStarted("com.whatsapp", false)
        c.onMessageSent("hello there")
        advanceUntilIdle()
        assertTrue(store.get("sarah-1")!!.messages.none { it.text == "hello there" })

        c.selectConversation("sarah-1")
        c.rememberSent = false
        c.onMessageSent("hello there")
        advanceUntilIdle()
        assertTrue(store.get("sarah-1")!!.messages.none { it.text == "hello there" })
    }

    @Test fun `the summary refreshes itself as the chat grows`() = runTest {
        store.save(sarah())
        val c = controller()
        c.onInputStarted("com.whatsapp", false)
        c.selectConversation("sarah-1")
        repeat(10) { i ->
            c.onMessageSent("nilimwambia Brian $i")
            advanceUntilIdle()
        }
        val record = store.get("sarah-1")!!
        assertEquals(record.messages.size, record.summary!!.basedOnMessages)
        assertTrue("Brian" in record.summary!!.people)
    }

    // --- Retrieval and missing context -------------------------------------

    @Test fun `a person seen earlier in the history is not asked about`() = runTest {
        val history = ConversationRecord(
            id = "a", platformId = "whatsapp", contactName = "Sarah",
            messages = listOf(them("Randy said he'd bring the speaker"), me("Classic Randy")) +
                (1..14).map { if (it % 2 == 0) me("ok $it") else them("filler message number $it") },
            createdAt = clock,
        )
        store.save(history)
        val c = controller()
        c.onInputStarted("com.whatsapp", false)
        c.selectConversation("a")
        c.onCopied("So did Randy bring it?", automatic = true)
        advanceUntilIdle()
        assertTrue(c.card.value is CardState.Suggestion)
        assertEquals("Randy said he'd bring the speaker", provider.requests.last().reply.snippets.first().text)
    }

    @Test fun `an unknown person is asked about, never guessed`() = runTest {
        store.save(sarah())
        val c = controller()
        c.onInputStarted("com.whatsapp", false)
        c.selectConversation("sarah-1")
        c.onCopied("Did Kevin ever call you back?", automatic = true)
        advanceUntilIdle()
        val card = c.card.value as CardState.ContextNeeded
        assertEquals("person:kevin", card.signal.key)
        assertTrue(provider.requests.isEmpty())
    }

    // --- Deletion ------------------------------------------------------------

    @Test fun `forgotten memory is no longer retrieved`() = runTest {
        store.save(sarah())
        val c = controller()
        c.onInputStarted("com.whatsapp", false)
        c.selectConversation("sarah-1")
        c.openMemory()
        assertTrue((c.card.value as CardState.MemoryView).items.any { it.id == "m-randy" })
        c.forgetMemory("m-randy")
        assertTrue((c.card.value as CardState.MemoryView).items.none { it.id == "m-randy" })

        c.onCopied("😂 So what did Randy say?", automatic = true)
        advanceUntilIdle()
        assertTrue(c.card.value is CardState.ContextNeeded) // Vibe no longer knows Randy
    }

    @Test fun `a forgotten derived memory stays forgotten after a rescan`() = runTest {
        store.save(sarah())
        val c = controller()
        c.onInputStarted("com.whatsapp", false)
        c.selectConversation("sarah-1")
        val naivas = store.get("sarah-1")!!.memories.first { it.key == "place:naivas" }
        c.forgetMemory(naivas.id)

        c.freshClip = { "Naivas again this weekend?" }
        c.onScan()
        advanceUntilIdle()
        assertTrue(store.get("sarah-1")!!.memories.none { it.key == "place:naivas" })
    }

    @Test fun `clearing a chat's memory removes history, summary and memories`() = runTest {
        store.save(sarah())
        val c = controller()
        c.onInputStarted("com.whatsapp", false)
        c.selectConversation("sarah-1")
        c.askClearMemory()
        assertTrue((c.card.value as CardState.MemoryView).confirmClear)
        c.confirmClearMemory()
        val cleared = store.get("sarah-1")!!
        assertTrue(cleared.messages.isEmpty() && cleared.memories.isEmpty() && cleared.summary == null)
        assertEquals(0, c.status.value.memoryCount)
    }

    @Test fun `editing a memory makes it the user's`() = runTest {
        store.save(sarah())
        val c = controller()
        c.onInputStarted("com.whatsapp", false)
        c.selectConversation("sarah-1")
        val topic = store.get("sarah-1")!!.memories.first { it.source == MemorySource.CONVERSATION }
        c.editMemory(topic.id)
        repeat(topic.value.length) { c.deleteFromContext() }
        "our weekend plans".forEach { c.typeIntoContext(it.toString()) }
        c.submitContext()
        val edited = store.get("sarah-1")!!.memories.first { it.id == topic.id }
        assertEquals("our weekend plans", edited.value)
        assertEquals(MemorySource.USER, edited.source)
    }

    // --- Scanning ------------------------------------------------------------

    @Test fun `scan saves copied messages to the picked chat and counts are real`() = runTest {
        store.save(jane())
        val c = controller()
        c.onInputStarted("com.instagram.android", false)
        c.selectConversation("jane-1")
        assertEquals(ScanState.NOT_SCANNED, c.status.value.scanState)

        c.freshClip = { "What time are you getting to town tomorrow?" }
        c.onScan()
        advanceUntilIdle()
        assertEquals(ScanState.CONTEXT_READY, c.status.value.scanState)
        assertEquals(1, c.status.value.messageCount)
        assertEquals(1, store.get("jane-1")!!.messages.size)
        assertTrue(c.card.value is CardState.Suggestion)

        clock += 13 * 3_600_000L
        c.onInputStarted("com.instagram.android", false) // same app; chat expired while idle
        c.selectConversation("jane-1")
        assertEquals(ScanState.CONTEXT_STALE, c.status.value.scanState)
    }

    @Test fun `without a chat, a scan uses what was copied and saves nothing`() = runTest {
        val c = controller()
        c.onInputStarted("com.snapchat.android", false)
        c.freshClip = { "you actually went there? 😂" }
        c.onScan()
        advanceUntilIdle()
        assertEquals(ScanState.CONTEXT_READY, c.status.value.scanState)
        assertTrue(c.card.value is CardState.Suggestion)
        assertTrue(store.list().isEmpty())
    }

    // --- Platform unavailable ---------------------------------------------------

    @Test fun `no access and no import on Instagram says so and offers a way on`() = runTest {
        val c = controller()
        c.onInputStarted("com.instagram.android", false)
        c.onScan()
        advanceUntilIdle()
        val card = c.card.value as CardState.ContextUnavailable
        assertFalse(card.importSupported)
        assertTrue(card.reason.contains("Instagram"))
        assertEquals(ScanState.CONTEXT_UNAVAILABLE, c.status.value.scanState)
        assertTrue(provider.requests.isEmpty())

        c.continueWithoutContext()
        assertTrue(c.card.value is CardState.Notice)
        assertTrue(c.status.value.withoutContext)
    }

    @Test fun `WhatsApp offers an import instead`() = runTest {
        val c = controller()
        c.onInputStarted("com.whatsapp", false)
        c.onScan()
        advanceUntilIdle()
        val card = c.card.value as CardState.ContextUnavailable
        assertTrue(card.importSupported)
        assertEquals(ScanState.IMPORT_REQUIRED, c.status.value.scanState)
    }

    // --- Keyboard stability -----------------------------------------------------

    private class BrokenStore : ConversationStore {
        override fun list(): List<ConversationRecord> = error("disk gone")
        override fun get(id: String): ConversationRecord? = error("disk gone")
        override fun save(record: ConversationRecord) = error("disk gone")
        override fun delete(id: String): Boolean = error("disk gone")
        override fun loadStyle(): UserStyleProfile = error("disk gone")
        override fun saveStyle(profile: UserStyleProfile) = error("disk gone")
        override fun deleteAll() = error("disk gone")
    }

    @Test fun `a broken store never breaks the keyboard`() = runTest {
        val c = controller(BrokenStore())
        c.onInputStarted("com.whatsapp", false)
        c.chooseConversation()
        assertTrue(c.card.value is CardState.Failed)
        assertEquals(ScanState.ERROR, c.status.value.scanState)

        // Suggestions still work from what was copied.
        c.onCopied("What are you doing later?", automatic = true)
        advanceUntilIdle()
        assertTrue(c.card.value is CardState.Suggestion)
    }

    // --- Goodnight, then morning ---------------------------------------------------

    @Test fun `a used goodnight leads to a morning suggestion, offered once and never sent`() = runTest {
        store.save(sarah())
        var hour = 23
        val c = VibeController(this, provider, hourOfDay = { hour }, store = store, now = { clock })
        c.autoReply = true
        c.onInputStarted("com.whatsapp", fieldDeclaresSend = true)
        c.selectConversation("sarah-1")
        c.onCopied("Okay nalala sasa, goodnight 😴", automatic = true)
        advanceUntilIdle()
        c.wrapUp()
        advanceUntilIdle()
        c.use()
        advanceUntilIdle()
        assertNotNull(store.get("sarah-1")!!.goodnightAt)

        clock += 9 * 3_600_000L
        hour = 8
        c.onInputStarted("com.whatsapp", fieldDeclaresSend = true)
        c.selectConversation("sarah-1")
        advanceUntilIdle()
        val card = c.card.value as CardState.Suggestion
        assertEquals("Good morning?", card.label)
        assertEquals(ReplyIntent.MORNING, provider.requests.last().intent)
        assertFalse(card.autoInserted) // a suggestion, even in Auto mode

        c.dismiss()
        c.selectConversation("sarah-1")
        assertTrue(c.card.value is CardState.Panel) // offered once
    }
}
