package com.vibe.keyboard

import com.vibe.keyboard.context.ContextScanner
import com.vibe.keyboard.engine.ChatMessage
import com.vibe.keyboard.engine.ContextDetector
import com.vibe.keyboard.engine.Flow
import com.vibe.keyboard.engine.Rhythm
import com.vibe.keyboard.engine.Speaker
import com.vibe.keyboard.engine.VibeSignal
import com.vibe.keyboard.memory.ConversationRecord
import com.vibe.keyboard.memory.ConversationSummarizer
import com.vibe.keyboard.memory.MemoryItem
import com.vibe.keyboard.memory.MemoryKind
import com.vibe.keyboard.memory.MemoryRetriever
import com.vibe.keyboard.memory.MemorySource
import com.vibe.keyboard.memory.StyleSamples
import com.vibe.keyboard.memory.UserStyleLearner
import com.vibe.keyboard.memory.UserStyleProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The new detections, rhythm, summarising, retrieval and style learning. */
class EngineTest {

    private fun them(t: String, at: Long? = null) = ChatMessage(Speaker.THEM, t, at)
    private fun me(t: String, at: Long? = null) = ChatMessage(Speaker.ME, t, at)

    // --- Detection ------------------------------------------------------------

    @Test fun `a contraction is not a person`() {
        assertTrue(ContextDetector.candidateNames("I lost my job today, I'm not okay").isEmpty())
        assertTrue(ContextDetector.detect("I lost my job today, I'm not okay", 15) is VibeSignal.Emotional)
    }

    @Test fun `requests for private details are flagged, keywords alone are not`() {
        val pin = ContextDetector.detect("What's your M-Pesa PIN?", 15) as VibeSignal.SensitiveRequest
        assertEquals("a PIN", pin.what)
        assertTrue(ContextDetector.detect("nitumie pesa kidogo", 15) is VibeSignal.SensitiveRequest)
        assertFalse(ContextDetector.detect("I'll pin the location later", 15) is VibeSignal.SensitiveRequest)
    }

    @Test fun `short ambiguous replies ask what they mean, and are not remembered by default`() {
        val s = ContextDetector.detect("labda, we'll see", 15) as VibeSignal.NeedsContext
        assertEquals("Vibe isn't sure what they mean.", s.headline)
        assertFalse(s.rememberByDefault)
        assertTrue(ContextDetector.detect("we'll see what the lecturer says about the exam tomorrow morning", 15) !is VibeSignal.NeedsContext)
    }

    // --- Rhythm ----------------------------------------------------------------

    @Test fun `three one-word replies in a row is slowing`() {
        val msgs = listOf(them("so how was the trip?"), me("amazing, we went hiking and saw the falls"), them("nice"), me("and you?"), them("ok"), me("?"), them("lol"))
        assertEquals(Flow.SLOWING, Rhythm.assess(msgs))
    }

    @Test fun `an engaged conversation is flowing`() {
        val msgs = listOf(them("haha no way, what did he say after?"), me("he just walked off"), them("stop 😂 I would have died"), me("same"), them("okay but are we still going saturday?"))
        assertEquals(Flow.FLOWING, Rhythm.assess(msgs))
    }

    @Test fun `replies taking much longer than usual is slowing`() {
        val min = 60_000L
        var t = 0L
        val msgs = mutableListOf<ChatMessage>()
        repeat(4) { msgs += me("question $it", t); t += 2 * min; msgs += them("quick answer here $it", t); t += min }
        msgs += me("so what now", t); t += 40 * min; msgs += them("sorry was busy then", t); t += min
        msgs += me("all good", t); t += 55 * min; msgs += them("yeah back now finally", t)
        assertEquals(Flow.SLOWING, Rhythm.assess(msgs))
    }

    @Test fun `without timestamps or enough messages it does not guess`() {
        assertEquals(Flow.UNKNOWN, Rhythm.assess(listOf(them("hey"), me("hi"))))
    }

    // --- Summaries -------------------------------------------------------------

    private val chat = listOf(
        them("Niaje! Tunaenda campus kesho?"), me("Yes, after the Naivas run"),
        them("Brian said he'll meet us at Naivas 😂"), me("Brian is always late"),
        them("Remember when Brian missed the matatu 😂"), me("hahaha classic"),
        them("Music at the concert was mad"), me("The concert was worth it"),
        them("Next concert we go early"), me("deal"),
    )

    @Test fun `the summary contains only what the messages say`() {
        val s = ConversationSummarizer.summarize(chat, excludeNames = listOf("Sarah"), now = 0)
        assertEquals(listOf("Brian"), s.people)
        assertTrue("Naivas" in s.places)
        assertEquals(listOf("concert"), s.topics)
        assertEquals("Remember when Brian missed the matatu 😂", s.sharedReferences.single())
        val text = s.toString()
        listOf("Randy", "Jane", "Mombasa").forEach { assertFalse("invented $it", text.contains(it)) }
    }

    @Test fun `a name that always opens a sentence is not a topic`() {
        val msgs = listOf(
            them("Brian said he'll bring the speaker this weekend"), me("Brian always says that"),
            them("Brian is the professor of excuses"), me("the weekend will tell"), them("weekend loading"),
        )
        assertEquals(listOf("weekend"), ConversationSummarizer.summarize(msgs, emptyList(), 0).topics)
    }

    @Test fun `their style is read from their messages only`() {
        val style = ConversationSummarizer.summarize(chat, emptyList(), 0).theirStyle!!
        assertTrue(style.traits().contains("short messages"))
        assertEquals("😂", style.topEmojis.first())
    }

    @Test fun `the contact's own name is never a person they mentioned`() {
        val msgs = listOf(them("lol Sarah here"), me("hi Sarah"), them("Sarah says hi"), me("haha ok Sarah"))
        val s = ConversationSummarizer.summarize(msgs, excludeNames = listOf("Sarah"), now = 0)
        assertTrue(s.people.isEmpty())
    }

    @Test fun `derived memories respect what the user forgot and what they wrote`() {
        val mine = MemoryItem("u1", MemoryKind.PERSON, "person:brian", "Brian is my cousin", MemorySource.USER, 0)
        val out = ConversationSummarizer.derivedMemories(chat, emptyList(), listOf(mine), forgotten = setOf("place:naivas"), now = 0)
        assertEquals("Brian is my cousin", out.single { it.key == "person:brian" }.value)
        assertTrue(out.none { it.key == "place:naivas" })
        assertTrue(out.any { it.key == "topic:concert" })
    }

    // --- Retrieval ---------------------------------------------------------------

    @Test fun `retrieval finds the memory about the person named`() {
        val record = ConversationRecord(
            id = "x", platformId = "whatsapp", contactName = "Sarah", createdAt = 0,
            messages = chat,
            memories = listOf(
                MemoryItem("1", MemoryKind.PERSON, "person:randy", "Randy is my roommate", MemorySource.USER, 0),
                MemoryItem("2", MemoryKind.TOPIC, "topic:concert", "concert", MemorySource.CONVERSATION, 0),
            ),
        )
        val r = MemoryRetriever.retrieve(record, "😂 So what did Randy say?")
        assertEquals(listOf("person:randy"), r.memories.map { it.key })
        assertTrue("person:randy" in r.knownKeys)

        val none = MemoryRetriever.retrieve(record, "what are you doing tonight?")
        assertTrue(none.memories.isEmpty())
    }

    @Test fun `the message being answered does not count as having seen a name before`() {
        val record = ConversationRecord(
            id = "x", platformId = "whatsapp", contactName = "Sarah", createdAt = 0,
            messages = listOf(them("hey"), them("So what did Randy say?")),
        )
        assertFalse("person:randy" in MemoryRetriever.retrieve(record, "So what did Randy say?").knownKeys)
    }

    // --- Merging copied messages ----------------------------------------------------

    @Test fun `copied messages are appended without repeating the overlap`() {
        val history = listOf(them("a"), me("b"), them("c"))
        assertEquals(listOf("a", "b", "c", "d"), ContextScanner.mergeTail(history, listOf(me("b"), them("c"), me("d"))).map { it.text })
        assertEquals(history, ContextScanner.mergeTail(history, listOf(me("b"))))
        assertEquals(4, ContextScanner.mergeTail(history, listOf(them("new"))).size)
    }

    // --- Style samples -------------------------------------------------------------

    @Test fun `samples prefer messages close to this moment, then recent ones`() {
        val mine = listOf("weekend niko free manze", "sawa sawa", "job imenibana leo", "aii niko tu", "tutaonana kesho basi")
        val picked = StyleSamples.pick(mine, current = "Weekend uko free?", alreadyShown = listOf("aii niko tu"), max = 3)
        assertEquals("weekend niko free manze", picked.first())
        assertFalse("aii niko tu" in picked) // already in the transcript
        assertEquals(3, picked.size)
    }

    @Test fun `only messages with nothing identifying are kept across chats`() {
        assertTrue(StyleSamples.isShareableAcrossChats("aii niko tu manze 😂"))
        assertFalse(StyleSamples.isShareableAcrossChats("tell Randy nimefika"))
        assertFalse(StyleSamples.isShareableAcrossChats("my number is 0712345678"))
        assertFalse(StyleSamples.isShareableAcrossChats("tukutane Naivas saa nane"))
        assertFalse(StyleSamples.isShareableAcrossChats("ok"))
    }

    @Test fun `a used suggestion is never kept as a sample of the user's own writing`() {
        val learned = UserStyleLearner.learn(UserStyleProfile(), List(10) { "sawa sawa tutaonana $it" }.map { it.filter(Char::isLetter).plus(" poa sana") })
        val after = UserStyleLearner.learnFromUsed(learned, "model wrote this line")
        assertEquals(learned.samples, after.samples)
    }

    // --- Style ---------------------------------------------------------------------

    @Test fun `style is learned from the user's messages, blending rather than replacing`() {
        val mine = List(12) { if (it % 2 == 0) "sawa sawa, tutaonana kesho" else "poa, niko njiani" }
        val learned = UserStyleLearner.learn(UserStyleProfile(), mine)
        assertTrue(learned.shengRatio > 0.6)
        assertTrue(learned.emojiRate < 0.01)
        assertTrue("sawa sawa" in learned.commonPhrases)
        assertTrue(learned.hasLearned)

        val nudged = UserStyleLearner.learnFromUsed(learned, "😂😂 okay")
        assertTrue(nudged.emojiRate < 0.1) // one used suggestion is a small nudge
    }
}
