package com.vibe.keyboard.memory

import com.vibe.keyboard.engine.ChatMessage
import com.vibe.keyboard.engine.Speaker
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Reads WhatsApp's own "Export chat" text file -- the one official way to get
 * a chat's full history onto the phone as text, and one the user has to start
 * themselves. Both the Android (`24/09/2026, 21:04 - Sarah: hi`) and iOS
 * (`[24/09/2026, 21:04:10] Sarah: hi`) layouts, 12- and 24-hour clocks, and
 * either day/month order.
 */
object ChatExportParser {

    data class Line(val sender: String, val text: String, val sentAt: Long?)

    data class Parsed(
        val lines: List<Line>,
        /** Everyone who wrote, most active first. One of them is the user. */
        val senders: List<String>,
        val skipped: Int,
    ) {
        val isEmpty get() = lines.isEmpty()

        fun messagesAs(me: String): List<ChatMessage> = lines.map {
            ChatMessage(if (it.sender == me) Speaker.ME else Speaker.THEM, it.text, it.sentAt)
        }
    }

    private val lineRe = Regex(
        """^\[?(\d{1,4})[/.\-](\d{1,2})[/.\-](\d{2,4}),?\s+(\d{1,2}):(\d{2})(?::\d{2})?\s*([AaPp]\.?\s?[Mm]\.?)?]?\s*[-–]?\s*([^:\[\]]{1,40}?):\s?(.*)$""",
    )

    private val headerRe = Regex("""^\[?\d{1,4}[/.\-]\d{1,2}[/.\-]\d{2,4},?\s+\d{1,2}:\d{2}""")

    /** Placeholders WhatsApp writes instead of content. Not messages. */
    private val placeholders = listOf(
        "<media omitted>", "image omitted", "video omitted", "audio omitted", "sticker omitted",
        "gif omitted", "document omitted", "<attached:", "this message was deleted",
        "you deleted this message", "null", "missed voice call", "missed video call",
        "<this message was edited>", "waiting for this message",
    )

    fun parse(raw: String, zone: ZoneId = ZoneId.systemDefault()): Parsed {
        val cleaned = raw.lineSequence().map(::clean).filter { it.isNotBlank() }.toList()
        val matches = cleaned.map { lineRe.matchEntire(it) }
        val order = dateOrder(matches.filterNotNull())

        val out = mutableListOf<Line>()
        var skipped = 0
        var lastWasMessage = false
        for ((i, line) in cleaned.withIndex()) {
            val m = matches[i]
            if (m == null) {
                // A dated line with no "Name:" is WhatsApp talking ("Sarah changed her
                // number"), not a message. Anything else continues a multi-line message.
                if (headerRe.containsMatchIn(line)) {
                    skipped++
                    lastWasMessage = false
                } else if (lastWasMessage && out.isNotEmpty()) {
                    val prev = out.last()
                    out[out.lastIndex] = prev.copy(text = prev.text + "\n" + line.trim())
                } else skipped++
                continue
            }
            val g = m.groupValues
            val text = g[8].trim()
            if (text.isEmpty() || placeholders.any { text.lowercase().startsWith(it) }) {
                skipped++
                lastWasMessage = false
                continue
            }
            out += Line(g[7].trim(), text, timestamp(g, order, zone))
            lastWasMessage = true
        }
        val senders = out.groupingBy { it.sender }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
        return Parsed(out, senders, skipped)
    }

    /** "WhatsApp Chat with Sarah" (the share subject or file name) -> "Sarah". */
    fun chatNameFrom(subjectOrFileName: String?): String? {
        val s = subjectOrFileName?.substringBeforeLast(".txt")?.substringBeforeLast(".zip")?.trim() ?: return null
        val m = Regex("""(?i)whatsapp chat (?:with|-)\s*(.+)$""").find(s) ?: return null
        return m.groupValues[1].trim().takeIf { it.isNotEmpty() }
    }

    /**
     * The export's sender names for the user are whatever their own WhatsApp
     * profile says, so Vibe cannot know which is them. With the chat named in
     * the subject, the other sender is the best guess; the import screen asks.
     */
    fun guessMe(parsed: Parsed, chatName: String?): String? {
        if (parsed.senders.size < 2) return null
        val others = parsed.senders.filterNot { chatName != null && it.equals(chatName, ignoreCase = true) }
        return if (chatName != null && others.size == 1) others.single() else null
    }

    private fun clean(line: String) = line
        .replace(Regex("[‎‏‪-‮﻿]"), "")
        .replace(' ', ' ').replace(' ', ' ')
        .trimEnd()

    private enum class DateOrder { DMY, MDY, YMD }

    private fun dateOrder(ms: List<MatchResult>): DateOrder {
        if (ms.any { it.groupValues[1].length == 4 }) return DateOrder.YMD
        if (ms.any { it.groupValues[1].toInt() > 12 }) return DateOrder.DMY
        if (ms.any { it.groupValues[2].toInt() > 12 }) return DateOrder.MDY
        return DateOrder.DMY
    }

    private fun timestamp(g: List<String>, order: DateOrder, zone: ZoneId): Long? = runCatching {
        val a = g[1].toInt(); val b = g[2].toInt(); val c = g[3].toInt()
        val (y, mo, d) = when (order) {
            DateOrder.DMY -> Triple(c, b, a)
            DateOrder.MDY -> Triple(c, a, b)
            DateOrder.YMD -> Triple(a, b, c)
        }
        val year = if (y < 100) 2000 + y else y
        var hour = g[4].toInt()
        val ampm = g[6].lowercase().filter { it == 'a' || it == 'p' }
        if (ampm == "p" && hour < 12) hour += 12
        if (ampm == "a" && hour == 12) hour = 0
        LocalDateTime.of(year, mo, d, hour, g[5].toInt()).atZone(zone).toInstant().toEpochMilli()
    }.getOrNull()
}
