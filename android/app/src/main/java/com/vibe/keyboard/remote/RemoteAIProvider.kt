package com.vibe.keyboard.remote

import com.vibe.keyboard.ai.AIException
import com.vibe.keyboard.ai.AIProvider
import com.vibe.keyboard.ai.ReplyContext
import com.vibe.keyboard.ai.ReplyIntent
import com.vibe.keyboard.ai.Suggestion
import com.vibe.keyboard.ai.SuggestionRequest
import com.vibe.keyboard.ai.Verdict
import com.vibe.keyboard.engine.ChatMessage
import com.vibe.keyboard.engine.ContextDetector
import com.vibe.keyboard.engine.Speaker
import com.vibe.keyboard.memory.MemoryKind
import com.vibe.keyboard.memory.MemorySource
import com.vibe.keyboard.memory.UserStyleLearner
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The server answered, and its answer is "not this": a boundary, or context it needs. */
class ReplyBlockedException(message: String) : AIException(message)

/**
 * Real replies, from this repo's backend and whichever model it is configured
 * with. The phone sends the recent messages and only the memory that bears on
 * this message; the backend adds the rules, the example library and the model.
 *
 * Anything that goes wrong on the way -- no connection, signed out, rate
 * limit, the model failing -- falls back to [fallback] with a note saying so,
 * so the keyboard always has something and never pretends the fallback is AI.
 */
class RemoteAIProvider(
    private val connections: ConnectionStore,
    private val fallback: AIProvider,
    private val client: BackendClient = BackendClient(),
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : AIProvider {

    override val isPreview: Boolean = false

    override suspend fun suggest(request: SuggestionRequest): Suggestion {
        var conn = connections.load()
        if (!conn.isConfigured) return fallback.suggest(request)
        if (!conn.isSignedIn) return fallbackWith(request, "Sign in in the Vibe app for real replies")

        return try {
            conn = freshToken(conn)
            val response = try {
                client.suggest(conn.backendUrl, conn.accessToken.ifBlank { null }, toDto(request))
            } catch (e: BackendException) {
                if (e.status != 401 || conn.refreshToken.isBlank()) throw e
                conn = refreshed(conn)
                client.suggest(conn.backendUrl, conn.accessToken, toDto(request))
            }
            val usable = response.suggestions.filter { it.text.isNotBlank() }.distinctBy { it.text.trim() }
            val options = usable.map { it.text.trim() }
            when {
                options.isNotEmpty() -> Suggestion(
                    text = options.first(),
                    isPreview = response.isMock,
                    options = options,
                    optionIds = usable.map { it.id },
                    note = if (response.isMock) "Server has no model key · templates" else null,
                )
                response.blocked -> throw ReplyBlockedException(response.blockedReason ?: "Vibe won't write this one.")
                else -> throw ReplyBlockedException(response.guidance ?: "Nothing to send right now.")
            }
        } catch (e: ReplyBlockedException) {
            throw e
        } catch (e: BackendException) {
            fallbackWith(request, reasonFor(e))
        } catch (e: IOException) {
            fallbackWith(request, "Can't reach your Vibe server")
        } catch (e: AIException) {
            throw e
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            fallbackWith(request, "Server error")
        }
    }

    /**
     * Used and thrown-out suggestions teach the backend what the user actually
     * sends. Never blocks, never fails anything: it is a hint, not a record.
     */
    override suspend fun feedback(text: String, id: String?, verdict: Verdict) {
        if (id == null) return
        try {
            val conn = connections.load()
            if (!conn.isReady || conn.serverIsMock) return
            val token = freshToken(conn).accessToken.ifBlank { null }
            client.feedback(
                conn.backendUrl, token,
                FeedbackDto(id, text.take(2_000), if (verdict == Verdict.USED) "used" else "rejected"),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Best effort only.
        }
    }

    private suspend fun fallbackWith(request: SuggestionRequest, why: String): Suggestion =
        fallback.suggest(request).copy(note = "$why · preview")

    private fun reasonFor(e: BackendException) = when (e.status) {
        401, 403 -> "Signed out · sign in again in the Vibe app"
        429 -> "Rate limit reached"
        502 -> e.message ?: "The model failed"
        else -> "Server error (${e.status})"
    }

    /** Refresh a token about to expire before using it, rather than after a 401. */
    private suspend fun freshToken(conn: Connection): Connection {
        if (!conn.authRequired || conn.refreshToken.isBlank()) return conn
        return if (conn.expiresAt - now() < 60_000) refreshed(conn) else conn
    }

    /** A refresh the sign-in service refuses means signed out: the dead token is dropped. */
    private suspend fun refreshed(conn: Connection): Connection {
        val session = try {
            client.refresh(conn.supabaseUrl, conn.anonKey, conn.refreshToken)
        } catch (e: BackendException) {
            connections.save(conn.copy(accessToken = "", refreshToken = "", expiresAt = 0))
            throw BackendException(401, "Signed out")
        }
        val next = conn.copy(
            accessToken = session.accessToken,
            refreshToken = session.refreshToken,
            expiresAt = now() + session.expiresIn * 1000,
        )
        connections.save(next)
        return next
    }

    // --- The request -------------------------------------------------------------

    fun toDto(request: SuggestionRequest): SuggestRequestDto {
        val reply = request.reply
        val messages = reply.recent.ifEmpty { request.conversation.messages }.takeLast(30)
        val (goal, action) = goalFor(request.intent)
        return SuggestRequestDto(
            messages = messages.map { it.toDto() },
            goal = goal,
            suppliedContext = suppliedContext(request),
            avoid = request.avoid.takeLast(20).map { it.take(2_000) },
            action = action,
            localTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(now()), zone).format(ISO),
            memoryNotes = memoryNotes(reply),
            styleNotes = reply.userStyle?.takeIf { it.hasLearned }
                ?.let { UserStyleLearner.describe(it).joinToString(", ").take(600) },
            styleSamples = reply.styleSamples.take(10).map { it.take(300) },
        )
    }

    private fun ChatMessage.toDto() = MessageDto(
        speaker = if (speaker == Speaker.ME) "me" else "them",
        text = text.take(4_000),
        sentAt = sentAt?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), zone).format(ISO) },
    )

    /**
     * What the user has told Vibe counts as answered, so the backend's own
     * "ask, don't guess" gate does not stop on something already explained.
     */
    private fun suppliedContext(request: SuggestionRequest): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        request.reply.memories.filter { it.source == MemorySource.USER }.forEach { out[it.key] = it.value }
        // A name seen earlier in this chat's own history is known, with the evidence.
        val theirs = request.conversation.lastFromThem?.text.orEmpty()
        for (name in ContextDetector.candidateNames(theirs)) {
            val key = "person:${name.lowercase()}"
            if (key in out) continue
            request.reply.snippets.firstOrNull { name.lowercase() in it.text.lowercase() }
                ?.let { out[key] = "Mentioned earlier in this chat: “${it.text.take(300)}”" }
        }
        out.putAll(request.context)
        return out.entries.take(40).associate { it.key to it.value.take(2_000) }
    }

    /** Only what bears on this message: retrieved memories, summary facts, older mentions. */
    private fun memoryNotes(reply: ReplyContext): List<String> {
        val notes = mutableListOf<String>()
        reply.memories.forEach { m ->
            notes += when {
                m.source == MemorySource.USER -> m.value
                m.kind == MemoryKind.EVENT -> "Shared reference: ${m.value}"
                else -> "${m.kind.name.lowercase().replaceFirstChar(Char::uppercase)}: ${m.value}"
            }
        }
        reply.summary?.let { s ->
            if (s.topics.isNotEmpty()) notes += "Keeps coming up in this chat: ${s.topics.joinToString(", ")}"
            if (s.people.isNotEmpty()) notes += "People mentioned in this chat: ${s.people.joinToString(", ")}"
            s.sharedReferences.take(2).forEach { notes += "Shared reference: “$it”" }
            s.theirStyle?.let { notes += "How the other person texts, measured over ${s.basedOnMessages} messages: ${it.traits().joinToString(", ")}" }
        }
        reply.snippets.forEach { notes += "Earlier, ${if (it.speaker == Speaker.ME) "the user" else "the other person"} wrote: “${it.text}”" }
        return notes.distinct().take(24).map { it.take(400) }
    }

    private fun goalFor(intent: ReplyIntent): Pair<String, String?> = when (intent) {
        ReplyIntent.REPLY -> "keep_flowing" to null
        ReplyIntent.CONTINUE -> "keep_flowing" to "continue"
        ReplyIntent.WRAP_UP, ReplyIntent.GOODNIGHT -> "end_naturally" to "stop"
        ReplyIntent.PICTURE_REPLY -> "playful" to null
        ReplyIntent.BOUNDARY_EXIT -> "end_naturally" to "stop"
        ReplyIntent.MORNING -> "next_day" to null
    }

    private companion object {
        val ISO: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    }
}
