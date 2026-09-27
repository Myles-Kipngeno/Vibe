package com.vibe.keyboard

import com.vibe.keyboard.engine.ChatMessage
import com.vibe.keyboard.engine.Speaker
import com.vibe.keyboard.memory.ChatExportParser
import com.vibe.keyboard.memory.ConversationImporter
import com.vibe.keyboard.memory.ConversationRecord
import com.vibe.keyboard.memory.ConversationStore
import com.vibe.keyboard.memory.FileConversationStore
import com.vibe.keyboard.memory.InMemoryConversationStore
import com.vibe.keyboard.memory.MemoryEdits
import com.vibe.keyboard.memory.MemoryItem
import com.vibe.keyboard.memory.MemoryKind
import com.vibe.keyboard.memory.MemorySource
import com.vibe.keyboard.memory.UserStyleProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset

class ImportAndStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private val utc = ZoneOffset.UTC
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        LocalDateTime.of(y, mo, d, h, mi).toInstant(utc).toEpochMilli()

    // --- WhatsApp's export formats --------------------------------------------------

    @Test fun `android export, 24-hour, with system lines and media skipped`() {
        val raw = """
            24/09/2026, 21:00 - Messages and calls are end-to-end encrypted. No one outside of this chat, not even WhatsApp, can read or listen to them.
            24/09/2026, 21:04 - Sarah K: Niaje! Uko?
            24/09/2026, 21:05 - Myles: Poa sana
            this is the second line
            24/09/2026, 21:06 - Sarah K: <Media omitted>
            24/09/2026, 21:07 - Sarah K changed her phone number
            25/09/2026, 08:10 - Sarah K: Morning 😊
        """.trimIndent()
        val p = ChatExportParser.parse(raw, utc)
        assertEquals(listOf("Niaje! Uko?", "Poa sana\nthis is the second line", "Morning 😊"), p.lines.map { it.text })
        assertEquals(at(2026, 9, 24, 21, 4), p.lines.first().sentAt)
        assertEquals(listOf("Sarah K", "Myles"), p.senders)
        assertEquals(3, p.skipped)

        val msgs = p.messagesAs("Myles")
        assertEquals(listOf(Speaker.THEM, Speaker.ME, Speaker.THEM), msgs.map { it.speaker })
    }

    @Test fun `ios export with seconds and brackets`() {
        val raw = "[24/09/2026, 21:04:10] Sarah: hey\n[24/09/2026, 21:05:02] Me Myself: hi"
        val p = ChatExportParser.parse(raw, utc)
        assertEquals(listOf("Sarah", "Me Myself"), p.lines.map { it.sender })
        assertEquals(at(2026, 9, 24, 21, 5), p.lines[1].sentAt)
    }

    @Test fun `12-hour US export with a narrow no-break space before PM`() {
        val raw = "9/24/26, 9:04 PM - Sarah: hey\n9/25/26, 12:15 AM - Sarah: still up?"
        val p = ChatExportParser.parse(raw, utc)
        assertEquals(at(2026, 9, 24, 21, 4), p.lines[0].sentAt)
        assertEquals(at(2026, 9, 25, 0, 15), p.lines[1].sentAt)
    }

    @Test fun `not an export parses to nothing`() {
        assertTrue(ChatExportParser.parse("just some text\nwith lines").isEmpty)
    }

    @Test fun `chat name comes from the share subject, and the other sender is probably you`() {
        assertEquals("Sarah K", ChatExportParser.chatNameFrom("WhatsApp Chat with Sarah K"))
        assertEquals("Sarah K", ChatExportParser.chatNameFrom("WhatsApp Chat with Sarah K.txt"))
        assertNull(ChatExportParser.chatNameFrom("holiday photos.zip"))
        val p = ChatExportParser.parse("24/09/2026, 21:04 - Sarah K: hey\n24/09/2026, 21:05 - Myles: hi", utc)
        assertEquals("Myles", ChatExportParser.guessMe(p, "Sarah K"))
        assertNull(ChatExportParser.guessMe(p, null)) // no subject: ask, don't guess
    }

    // --- Import ---------------------------------------------------------------------

    private val export = (1..20).joinToString("\n") { i ->
        if (i % 2 == 1) "24/09/2026, 21:${"%02d".format(i)} - Sarah K: The concert on Saturday? Naivas first 😂 ($i)"
        else "24/09/2026, 21:${"%02d".format(i)} - Myles: sawa sawa, tutaonana"
    }

    @Test fun `import builds a summary, memories and the user's style`() {
        val store = InMemoryConversationStore()
        val parsed = ChatExportParser.parse(export, utc)
        val record = ConversationImporter.import(store, parsed, me = "Myles", contactName = "Sarah", platformId = "whatsapp", now = 1)
        assertEquals(20, record.messages.size)
        assertNotNull(record.summary)
        assertTrue(record.memories.any { it.key == "place:naivas" })
        assertEquals(1L, record.lastScanAt)
        val style = store.loadStyle()
        assertEquals(10, style.learnedFrom)
        assertTrue(style.shengRatio > 0.5)
    }

    @Test fun `importing the same chat again updates it rather than duplicating`() {
        val store = InMemoryConversationStore()
        val parsed = ChatExportParser.parse(export, utc)
        val first = ConversationImporter.import(store, parsed, "Myles", "Sarah", "whatsapp", 1)
        val again = ConversationImporter.import(store, parsed, "Myles", "sarah", "whatsapp", 2)
        assertEquals(first.id, again.id)
        assertEquals(1, store.list().size)
        assertEquals(20, again.messages.size)
        // Same name on another app is another conversation.
        ConversationImporter.import(store, parsed, "Myles", "Sarah", "telegram", 3)
        assertEquals(2, store.list().size)
    }

    // --- The file store -----------------------------------------------------------

    private fun record(id: String, name: String) = ConversationRecord(
        id = id, platformId = "whatsapp", contactName = name, createdAt = 0,
        messages = listOf(ChatMessage(Speaker.THEM, "hi $name", 5L)),
        memories = listOf(MemoryItem("m", MemoryKind.NOTE, "note:x", "about $name", MemorySource.USER, 0)),
    )

    @Test fun `records survive a restart, one file per chat`() {
        val dir = tmp.newFolder()
        FileConversationStore(dir).apply {
            save(record("a1", "Sarah"))
            save(record("b2", "Jane"))
            saveStyle(UserStyleProfile(shengRatio = 0.7, learnedFrom = 30))
        }
        val reopened = FileConversationStore(dir)
        assertEquals("about Sarah", reopened.get("a1")!!.memories.single().value)
        assertEquals(5L, reopened.get("a1")!!.messages.single().sentAt)
        assertEquals(0.7, reopened.loadStyle().shengRatio, 0.0)
        assertEquals(setOf("a1.json", "b2.json"), File(dir, "conversations").list()!!.toSet())
    }

    @Test fun `deleting a chat removes its file, and delete-all removes everything`() {
        val dir = tmp.newFolder()
        val store = FileConversationStore(dir)
        store.save(record("a1", "Sarah"))
        store.save(record("b2", "Jane"))
        assertTrue(store.delete("a1"))
        assertNull(FileConversationStore(dir).get("a1"))
        assertNotNull(FileConversationStore(dir).get("b2"))
        store.deleteAll()
        assertTrue(FileConversationStore(dir).list().isEmpty())
        assertEquals(UserStyleProfile(), FileConversationStore(dir).loadStyle())
    }

    @Test fun `a damaged file is skipped, not fatal`() {
        val dir = tmp.newFolder()
        FileConversationStore(dir).save(record("a1", "Sarah"))
        File(dir, "conversations/zz.json").writeText("{ not json")
        assertEquals(listOf("a1"), FileConversationStore(dir).list().map { it.id })
    }

    @Test fun `ids cannot escape the store's folder`() {
        val store = FileConversationStore(tmp.newFolder())
        val bad = runCatching { store.save(record("../evil", "x")) }
        assertTrue(bad.isFailure)
        assertEquals(false, store.delete("../evil"))
    }

    @Test fun `history is capped, and edits are the same in keyboard and app`() {
        val store = InMemoryConversationStore()
        val long = record("a1", "Sarah").copy(messages = List(ConversationStore.MAX_MESSAGES + 50) { ChatMessage(Speaker.THEM, "m$it") })
        store.save(long)
        assertEquals(ConversationStore.MAX_MESSAGES, store.get("a1")!!.messages.size)
        assertEquals("m50", store.get("a1")!!.messages.first().text) // oldest dropped

        val forgotten = MemoryEdits.forget(store.get("a1")!!, "m")
        assertTrue(forgotten.memories.isEmpty() && "note:x" in forgotten.forgottenKeys)
    }
}
