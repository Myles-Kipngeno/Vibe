package com.vibe.keyboard

import com.vibe.keyboard.ai.MockAIProvider
import com.vibe.keyboard.ai.ReplyContext
import com.vibe.keyboard.ai.ReplyIntent
import com.vibe.keyboard.ai.SuggestionRequest
import com.vibe.keyboard.engine.ChatMessage
import com.vibe.keyboard.engine.Conversation
import com.vibe.keyboard.engine.Speaker
import com.vibe.keyboard.memory.MemoryItem
import com.vibe.keyboard.memory.MemoryKind
import com.vibe.keyboard.memory.MemorySource
import com.vibe.keyboard.remote.BackendClient
import com.vibe.keyboard.remote.Connection
import com.vibe.keyboard.remote.InMemoryConnectionStore
import com.vibe.keyboard.remote.RemoteAIProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The phone's real HTTP code against a real running backend, to prove both
 * sides agree on the wire format. Skipped unless a backend in local mode is
 * running and VIBE_BACKEND_URL points at it, e.g.
 *
 *   SUPABASE_URL= SUPABASE_ANON_KEY= AI_PROVIDER=mock uvicorn app.main:app --port 8001
 *   VIBE_BACKEND_URL=http://127.0.0.1:8001 ./gradlew testDebugUnitTest
 */
class BackendIntegrationTest {

    private val url = System.getenv("VIBE_BACKEND_URL")

    @Test fun `a real backend accepts what the keyboard sends and returns options`() = runBlocking {
        assumeTrue("set VIBE_BACKEND_URL to run", !url.isNullOrBlank())
        val health = BackendClient().health(url!!)
        assumeTrue("needs a backend in local mode", !health.authRequired)

        val provider = RemoteAIProvider(
            InMemoryConnectionStore(Connection(backendUrl = url, serverIsMock = health.isMock)),
            fallback = MockAIProvider(latencyMs = 0L..0L),
        )
        val request = SuggestionRequest(
            conversation = Conversation(listOf(ChatMessage(Speaker.THEM, "😂 So what did Randy say?"))),
            intent = ReplyIntent.REPLY,
            reply = ReplyContext(
                recent = listOf(
                    ChatMessage(Speaker.ME, "sawa, tutaonana kesho", 1_758_740_000_000),
                    ChatMessage(Speaker.THEM, "😂 So what did Randy say?"),
                ),
                memories = listOf(MemoryItem("1", MemoryKind.PERSON, "person:randy", "Randy is my roommate", MemorySource.USER, 0)),
            ),
        )
        val s = provider.suggest(request)
        // Not the fallback: a fallback carries a note saying why it was used.
        assertNull("fell back instead of using the server: ${s.note}", s.note?.takeIf { it.endsWith("· preview") })
        assertTrue(s.options.isNotEmpty())
        assertEquals(health.isMock, s.isPreview)
    }
}
