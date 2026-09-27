package com.vibe.keyboard.memory

import com.vibe.keyboard.engine.ChatMessage
import kotlinx.serialization.Serializable

@Serializable
enum class MemoryKind { PERSON, PLACE, EVENT, TOPIC, NOTE }

/** Mirrors `conversation_memories.source`: the user said it, or Vibe read it in the chat. */
@Serializable
enum class MemorySource { USER, CONVERSATION }

/**
 * One thing Vibe knows about one conversation. Same shape as a row of the
 * backend's `conversation_memories` (key, value, source), so syncing later is
 * a mapping, not a redesign.
 */
@Serializable
data class MemoryItem(
    val id: String,
    val kind: MemoryKind,
    /** Stable key, e.g. "person:randy". The same key the context alert uses. */
    val key: String,
    val value: String,
    val source: MemorySource,
    val createdAt: Long,
)

@Serializable
data class StyleReading(
    val languageMix: String,
    val shengRatio: Double,
    val avgWords: Double,
    val emojiRate: Double,
    val laughRate: Double,
    val questionRate: Double,
    val topEmojis: List<String>,
) {
    /** "short messages", "playful", "frequent 😂": only what the numbers support. */
    fun traits(): List<String> = buildList {
        add(languageMix)
        when {
            avgWords in 0.1..7.0 -> add("short messages")
            avgWords >= 18 -> add("long messages")
        }
        if (laughRate >= 0.2) add("playful")
        if (questionRate >= 0.35) add("asks a lot of questions")
        val top = topEmojis.firstOrNull()
        if (top != null && emojiRate >= 0.2) add("frequent $top") else if (emojiRate < 0.05) add("rarely uses emojis")
    }
}

/**
 * What a scan concluded, built only from messages Vibe actually has. Every
 * list here is extracted, not generated, so it cannot invent a person or an event.
 */
@Serializable
data class ConversationSummary(
    val basedOnMessages: Int,
    val theirStyle: StyleReading?,
    val topics: List<String>,
    val people: List<String>,
    val places: List<String>,
    val sharedReferences: List<String>,
    val recent: List<ChatMessage>,
    val firstMessageAt: Long?,
    val lastMessageAt: Long?,
    val builtAt: Long,
)

/**
 * One conversation, with one person, on one platform. The unit of isolation:
 * nothing in Vibe reads across two of these.
 */
@Serializable
data class ConversationRecord(
    /** Random, never derived from a name. Two "Sarah"s are two conversations. */
    val id: String,
    val platformId: String,
    /** Display only. Never used to look a conversation up. */
    val contactName: String,
    /** Sender names seen for them in imports/copies, used to catch the wrong chat being active. */
    val theirNames: List<String> = emptyList(),
    val myNames: List<String> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
    val summary: ConversationSummary? = null,
    val memories: List<MemoryItem> = emptyList(),
    /** Keys the user told Vibe to forget. A rescan must not bring them back. */
    val forgottenKeys: Set<String> = emptySet(),
    val createdAt: Long,
    val lastScanAt: Long? = null,
    /** When a Goodnight suggestion was used, for a next-morning suggestion. */
    val goodnightAt: Long? = null,
    val morningOffered: Boolean = false,
)

/** The user's own texting style, learned only from their messages. */
@Serializable
data class UserStyleProfile(
    val shengRatio: Double = 0.3,
    val avgWords: Double = 8.0,
    val emojiRate: Double = 0.35,
    val laughRate: Double = 0.2,
    val questionRate: Double = 0.25,
    val topEmojis: List<String> = emptyList(),
    val commonPhrases: List<String> = emptyList(),
    val learnedFrom: Int = 0,
    /**
     * A few of the user's own messages with nothing identifying in them (see
     * StyleSamples.isShareableAcrossChats), for when no chat is picked.
     */
    val samples: List<String> = emptyList(),
) {
    val hasLearned: Boolean get() = learnedFrom >= 10
}
