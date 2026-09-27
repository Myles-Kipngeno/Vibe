package com.vibe.keyboard.memory

import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Where conversations live. Every read and write names exactly one
 * conversation; the only call that sees more than one is [list], which the
 * chat picker uses and which never feeds a prompt.
 */
interface ConversationStore {
    fun list(): List<ConversationRecord>
    fun get(id: String): ConversationRecord?
    fun save(record: ConversationRecord)
    fun delete(id: String): Boolean
    fun loadStyle(): UserStyleProfile
    fun saveStyle(profile: UserStyleProfile)
    /** Everything: every conversation, every memory, the learned style. */
    fun deleteAll()

    companion object {
        /** Raw history kept per chat. The summary and memories are what prompts use. */
        const val MAX_MESSAGES = 1_500
    }
}

class InMemoryConversationStore : ConversationStore {
    private val records = LinkedHashMap<String, ConversationRecord>()
    private var style = UserStyleProfile()

    @Synchronized override fun list() = records.values.toList()
    @Synchronized override fun get(id: String) = records[id]
    @Synchronized override fun save(record: ConversationRecord) { records[record.id] = record.capped() }
    @Synchronized override fun delete(id: String) = records.remove(id) != null
    @Synchronized override fun loadStyle() = style
    @Synchronized override fun saveStyle(profile: UserStyleProfile) { style = profile }
    @Synchronized override fun deleteAll() { records.clear(); style = UserStyleProfile() }
}

/**
 * One JSON file per conversation in app-private, no-backup storage: nothing
 * here reaches a cloud backup or another app. Writes go to a temp file and
 * are moved into place, so a crash mid-write never leaves half a memory.
 */
class FileConversationStore(private val dir: File) : ConversationStore {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val cache = LinkedHashMap<String, ConversationRecord>()
    private var loaded = false
    private var style: UserStyleProfile? = null

    private val chatsDir get() = File(dir, "conversations")
    private val styleFile get() = File(dir, "style.json")

    @Synchronized override fun list(): List<ConversationRecord> {
        ensureLoaded()
        return cache.values.sortedByDescending { it.lastScanAt ?: it.createdAt }
    }

    @Synchronized override fun get(id: String): ConversationRecord? {
        ensureLoaded()
        return cache[id]
    }

    @Synchronized override fun save(record: ConversationRecord) {
        require(safeId.matches(record.id)) { "bad conversation id" }
        ensureLoaded()
        val capped = record.capped()
        writeAtomically(File(chatsDir, "${record.id}.json"), json.encodeToString(ConversationRecord.serializer(), capped))
        cache[record.id] = capped
    }

    @Synchronized override fun delete(id: String): Boolean {
        ensureLoaded()
        if (!safeId.matches(id)) return false
        val existed = cache.remove(id) != null
        File(chatsDir, "$id.json").delete()
        return existed
    }

    @Synchronized override fun loadStyle(): UserStyleProfile {
        style?.let { return it }
        val loadedStyle = runCatching {
            if (styleFile.exists()) json.decodeFromString(UserStyleProfile.serializer(), styleFile.readText()) else null
        }.getOrNull() ?: UserStyleProfile()
        style = loadedStyle
        return loadedStyle
    }

    @Synchronized override fun saveStyle(profile: UserStyleProfile) {
        writeAtomically(styleFile, json.encodeToString(UserStyleProfile.serializer(), profile))
        style = profile
    }

    @Synchronized override fun deleteAll() {
        chatsDir.deleteRecursively()
        styleFile.delete()
        cache.clear()
        style = null
    }

    private fun ensureLoaded() {
        if (loaded) return
        chatsDir.listFiles { f -> f.name.endsWith(".json") }?.forEach { f ->
            // A file that will not parse is skipped, not fatal: the keyboard must keep working.
            runCatching { json.decodeFromString(ConversationRecord.serializer(), f.readText()) }
                .getOrNull()?.let { cache[it.id] = it }
        }
        loaded = true
    }

    private fun writeAtomically(target: File, text: String) {
        target.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IOException("can't create ${it.name}") }
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(text)
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private companion object {
        val safeId = Regex("""^[a-zA-Z0-9-]{1,64}$""")
    }
}

internal fun ConversationRecord.capped(): ConversationRecord =
    if (messages.size <= ConversationStore.MAX_MESSAGES) this
    else copy(messages = messages.takeLast(ConversationStore.MAX_MESSAGES))
