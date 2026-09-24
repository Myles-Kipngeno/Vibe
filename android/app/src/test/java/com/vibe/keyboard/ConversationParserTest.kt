package com.vibe.keyboard

import com.vibe.keyboard.engine.ConversationParser
import com.vibe.keyboard.engine.Speaker
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationParserTest {

    @Test fun `a single copied message is theirs`() {
        val convo = ConversationParser.parse("  Ulienda town na Randy?  ")
        assertEquals(1, convo.messages.size)
        assertEquals(Speaker.THEM, convo.messages[0].speaker)
        assertEquals("Ulienda town na Randy?", convo.lastFromThem?.text)
    }

    @Test fun `several messages copied from WhatsApp keep who said what`() {
        val copied = """
            [21:02, 24/09/2026] Myles: Niaje, uko aje?
            [21:04, 24/09/2026] Wanjiru: Poa sana
            [21:04, 24/09/2026] Wanjiru: Ulienda town na Randy?
        """.trimIndent()
        val convo = ConversationParser.parse(copied)
        assertEquals(listOf(Speaker.ME, Speaker.THEM, Speaker.THEM), convo.messages.map { it.speaker })
        assertEquals("Ulienda town na Randy?", convo.lastFromThem?.text)
    }

    @Test fun `a message that wraps onto a second line stays one message`() {
        val copied = "[9:15 PM, 24/09/2026] Wanjiru: first line\nsecond line"
        val convo = ConversationParser.parse(copied)
        assertEquals(1, convo.messages.size)
        assertEquals("first line\nsecond line", convo.messages[0].text)
    }

    @Test fun `nothing copied is nothing to reply to`() {
        assertEquals(true, ConversationParser.parse("   \n ").isEmpty)
    }
}
