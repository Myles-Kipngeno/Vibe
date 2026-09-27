package com.vibe.keyboard.memory

import com.vibe.keyboard.context.ContextScanner
import com.vibe.keyboard.engine.Speaker
import java.util.UUID

/**
 * Turns a chat export the user chose to share with Vibe into a conversation
 * record: history, summary, memories, and -- from the user's own side only --
 * a better idea of how they text.
 */
object ConversationImporter {

    fun import(
        store: ConversationStore,
        parsed: ChatExportParser.Parsed,
        me: String,
        contactName: String,
        platformId: String,
        now: Long,
    ): ConversationRecord {
        val messages = parsed.messagesAs(me)
        val theirNames = parsed.senders.filter { it != me }
        // Same app, same name: this is an update of that chat, not a second copy of it.
        val existing = store.list().firstOrNull {
            it.platformId == platformId && it.contactName.equals(contactName.trim(), ignoreCase = true)
        }
        val base = existing?.copy(
            messages = ContextScanner.mergeTail(existing.messages, messages),
            theirNames = (existing.theirNames + theirNames).distinct(),
            myNames = (existing.myNames + me).distinct(),
        ) ?: ConversationRecord(
            id = UUID.randomUUID().toString(),
            platformId = platformId,
            contactName = contactName.trim().take(40),
            theirNames = theirNames,
            myNames = listOf(me),
            messages = messages,
            createdAt = now,
        )
        val built = ConversationSummarizer.rebuild(base, now)
        store.save(built)
        val mine = messages.filter { it.speaker == Speaker.ME }.map { it.text }
        store.saveStyle(UserStyleLearner.learn(store.loadStyle(), mine.takeLast(400)))
        return store.get(built.id) ?: built
    }
}
