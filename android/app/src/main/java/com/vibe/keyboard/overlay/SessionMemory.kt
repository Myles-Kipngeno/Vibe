package com.vibe.keyboard.overlay

/**
 * Context the user chose to "remember for this chat".
 *
 * In memory only, and cleared when the user switches app: a keyboard cannot
 * tell which contact a chat belongs to, so keeping this any longer would risk
 * one person's private context turning up in someone else's conversation.
 * Per-contact memory that persists needs a real contact identity first.
 */
object SessionMemory {
    private val entries = LinkedHashMap<String, String>()

    @Synchronized fun put(key: String, value: String) { entries[key] = value }
    @Synchronized fun keys(): Set<String> = entries.keys.toSet()
    @Synchronized fun all(): Map<String, String> = LinkedHashMap(entries)
    @Synchronized fun clear() = entries.clear()
    @Synchronized fun size(): Int = entries.size
}
