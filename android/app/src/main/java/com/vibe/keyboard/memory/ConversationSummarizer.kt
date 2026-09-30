package com.vibe.keyboard.memory

import com.vibe.keyboard.engine.ChatMessage
import com.vibe.keyboard.engine.ContextDetector
import com.vibe.keyboard.engine.Lexicon
import com.vibe.keyboard.engine.Speaker
import com.vibe.keyboard.engine.TextStats
import java.util.UUID

/**
 * Raw messages in, a summary and memories out.
 *
 * Deterministic on purpose. Every person, place, topic and shared reference is
 * something that appears in the messages, quoted where it helps; nothing is
 * inferred about what it means. That is what lets the summary go into a prompt
 * without teaching a model a "fact" nobody said.
 */
object ConversationSummarizer {

    fun summarize(
        messages: List<ChatMessage>,
        excludeNames: Collection<String>,
        now: Long,
    ): ConversationSummary {
        val theirs = messages.filter { it.speaker == Speaker.THEM }.map { it.text }
        return ConversationSummary(
            basedOnMessages = messages.size,
            theirStyle = if (theirs.size >= 3) styleOf(theirs) else null,
            topics = topics(messages),
            people = people(messages, excludeNames).keys.take(8),
            places = places(messages).keys.take(6),
            sharedReferences = sharedReferences(messages).take(5),
            recent = messages.takeLast(6),
            firstMessageAt = messages.firstNotNullOfOrNull { it.sentAt },
            lastMessageAt = messages.lastOrNull { it.sentAt != null }?.sentAt,
            builtAt = now,
            callbacks = callbacks(messages),
        )
    }

    /**
     * Lines of his that she laughed at right after. The strongest flirting
     * tool is bringing one back later -- sparingly -- so the latest few are kept.
     */
    private fun callbacks(messages: List<ChatMessage>): List<String> =
        messages.zipWithNext()
            .filter { (his, hers) ->
                his.speaker == Speaker.ME && hers.speaker == Speaker.THEM &&
                    TextStats.laughRate(listOf(hers.text)) > 0 && TextStats.words(his.text).size >= 3
            }
            .map { quote(it.first.text) }
            .distinct()
            .takeLast(4)

    /** Summary and derived memories, recomputed from the record's messages alone. */
    fun rebuild(record: ConversationRecord, now: Long): ConversationRecord {
        val exclude = record.theirNames + record.myNames + record.contactName
        return record.copy(
            summary = summarize(record.messages, exclude, now),
            memories = derivedMemories(record.messages, exclude, record.memories, record.forgottenKeys, now),
            lastScanAt = now,
        )
    }

    fun styleOf(texts: List<String>): StyleReading {
        val ratio = TextStats.shengRatio(texts)
        return StyleReading(
            languageMix = TextStats.languageMix(ratio),
            shengRatio = ratio,
            avgWords = TextStats.avgWords(texts),
            emojiRate = TextStats.emojiRate(texts),
            laughRate = TextStats.laughRate(texts),
            questionRate = TextStats.questionRate(texts),
            topEmojis = TextStats.topEmojis(texts),
        )
    }

    /**
     * Memories a scan can support, each with the words it came from. Anything
     * the user wrote themselves wins over a derived item with the same key, and
     * anything they told Vibe to forget stays forgotten.
     */
    fun derivedMemories(
        messages: List<ChatMessage>,
        excludeNames: Collection<String>,
        existing: List<MemoryItem>,
        forgotten: Set<String>,
        now: Long,
    ): List<MemoryItem> {
        val userKeys = existing.filter { it.source == MemorySource.USER }.map { it.key }.toSet()
        val derived = buildList {
            people(messages, excludeNames).filter { it.value.count >= 2 }.forEach { (name, m) ->
                add(item(MemoryKind.PERSON, "person:${name.lowercase()}", "$name · mentioned ${m.count}× · “${m.quote}”", now))
            }
            places(messages).filter { it.value.count >= 2 }.forEach { (place, m) ->
                add(item(MemoryKind.PLACE, "place:${place.lowercase()}", "$place · “${m.quote}”", now))
            }
            sharedReferences(messages).forEach { quote ->
                add(item(MemoryKind.EVENT, "event:${ContextDetector.normalize(quote).take(48)}", "“$quote”", now))
            }
            topics(messages).forEach { topic -> add(item(MemoryKind.TOPIC, "topic:$topic", topic, now)) }
        }.filter { it.key !in forgotten && it.key !in userKeys }

        val previous = existing.filter { it.source == MemorySource.CONVERSATION }.associateBy { it.key }
        // Keep ids stable across rescans so an open memory view doesn't jump.
        val refreshed = derived.map { d -> previous[d.key]?.let { d.copy(id = it.id, createdAt = it.createdAt) } ?: d }
        return existing.filter { it.source == MemorySource.USER } + refreshed
    }

    private fun item(kind: MemoryKind, key: String, value: String, now: Long) =
        MemoryItem(UUID.randomUUID().toString(), kind, key, value, MemorySource.CONVERSATION, now)

    private data class Mention(val count: Int, val quote: String)

    private fun people(messages: List<ChatMessage>, excludeNames: Collection<String>): Map<String, Mention> {
        val exclude = excludeNames.flatMap { ContextDetector.normalize(it).split(' ') }.filter { it.isNotBlank() }.toSet()
        return mentions(messages) { text -> ContextDetector.candidateNames(text).filter { it.lowercase() !in exclude } }
    }

    private fun places(messages: List<ChatMessage>): Map<String, Mention> =
        mentions(messages) { text ->
            TextStats.words(text).filter { it in Lexicon.knownPlaces && it != "town" }
                .map { it.replaceFirstChar(Char::uppercase) }.distinct()
        }

    /** Counts per name, quoting the most recent message that used it. Most mentioned first. */
    private fun mentions(messages: List<ChatMessage>, find: (String) -> List<String>): Map<String, Mention> {
        val counts = LinkedHashMap<String, Mention>()
        for (m in messages) {
            for (name in find(m.text)) {
                val prev = counts[name]
                counts[name] = Mention((prev?.count ?: 0) + 1, quote(m.text))
            }
        }
        return counts.entries.sortedByDescending { it.value.count }.associate { it.key to it.value }
    }

    /** A word is a topic only if it keeps coming back: three different messages at least. */
    private fun topics(messages: List<ChatMessage>): List<String> {
        val inMessages = HashMap<String, Int>()
        val firstSeen = HashMap<String, Int>()
        messages.forEachIndexed { i, m ->
            TextStats.words(m.text).filter { it.length >= 4 && it !in Lexicon.topicStopwords && !it.all(Char::isDigit) }
                .distinct().forEach { w ->
                    inMessages[w] = (inMessages[w] ?: 0) + 1
                    firstSeen.putIfAbsent(w, i)
                }
        }
        val names = messages.flatMap { ContextDetector.candidateNames(it.text) }.map { it.lowercase() }.toSet() +
            alwaysCapitalised(messages)
        val minCount = if (messages.size >= 60) 4 else 3
        return inMessages.filter { (w, n) -> n >= minCount && w !in names && w !in Lexicon.lowEffortReplies && w !in Lexicon.shengMarkers }
            .keys.sortedWith(compareByDescending<String> { inMessages[it] }.thenBy { firstSeen[it] })
            .take(5)
    }

    /**
     * Words never written in lowercase: "Brian" at the start of every message
     * he comes up in is still a name, not a topic.
     */
    private fun alwaysCapitalised(messages: List<ChatMessage>): Set<String> {
        val seen = HashMap<String, Boolean>()
        for (m in messages) for (token in Regex("""[A-Za-z]{4,}""").findAll(m.text)) {
            val w = token.value.lowercase()
            seen[w] = (seen[w] ?: true) && token.value[0].isUpperCase()
        }
        return seen.filterValues { it }.keys
    }

    /** "Remember when…", "like you said…": the other person pointing at something shared. */
    private fun sharedReferences(messages: List<ChatMessage>): List<String> =
        messages.filter { m ->
            val norm = " ${ContextDetector.normalize(m.text)} "
            Lexicon.sharedEventPhrases.any { norm.contains(" $it ") }
        }.map { quote(it.text) }.distinct().takeLast(5).reversed()

    private fun quote(text: String): String {
        val flat = text.replace('\n', ' ').trim()
        return if (flat.length <= 90) flat else flat.take(87).trimEnd() + "…"
    }
}
