package com.vibe.keyboard.memory

/**
 * The user's controls over one conversation's memory, as pure functions, so
 * the keyboard and the companion app apply exactly the same rules.
 */
object MemoryEdits {

    /** Forgotten means forgotten: the key is recorded so a later scan does not re-derive it. */
    fun forget(record: ConversationRecord, itemId: String): ConversationRecord {
        val item = record.memories.firstOrNull { it.id == itemId } ?: return record
        return record.copy(memories = record.memories - item, forgottenKeys = record.forgottenKeys + item.key)
    }

    /** Anything the user edits becomes theirs, and wins over what a scan derives. */
    fun edit(record: ConversationRecord, itemId: String, value: String): ConversationRecord {
        val clean = value.trim()
        if (clean.isEmpty()) return forget(record, itemId)
        return record.copy(
            memories = record.memories.map { if (it.id == itemId) it.copy(value = clean, source = MemorySource.USER) else it },
        )
    }

    /** History, summary and memories, all of it. The chat itself stays listed until deleted. */
    fun clear(record: ConversationRecord): ConversationRecord = record.copy(
        messages = emptyList(), summary = null, memories = emptyList(),
        forgottenKeys = emptySet(), lastScanAt = null, goodnightAt = null, morningOffered = false,
    )
}
