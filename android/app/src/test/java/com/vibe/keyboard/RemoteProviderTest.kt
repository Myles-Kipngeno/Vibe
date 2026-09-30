package com.vibe.keyboard

import com.vibe.keyboard.ai.AIProvider
import com.vibe.keyboard.ai.MockAIProvider
import com.vibe.keyboard.ai.ReplyContext
import com.vibe.keyboard.ai.ReplyGoal
import com.vibe.keyboard.ai.ReplyIntent
import com.vibe.keyboard.ai.Suggestion
import com.vibe.keyboard.ai.SuggestionRequest
import com.vibe.keyboard.engine.ChatMessage
import com.vibe.keyboard.engine.Conversation
import com.vibe.keyboard.engine.Speaker
import com.vibe.keyboard.memory.ConversationSummary
import com.vibe.keyboard.memory.MemoryItem
import com.vibe.keyboard.memory.MemoryKind
import com.vibe.keyboard.memory.MemorySource
import com.vibe.keyboard.overlay.CardState
import com.vibe.keyboard.overlay.FieldAction
import com.vibe.keyboard.overlay.SessionMemory
import com.vibe.keyboard.overlay.VibeController
import com.vibe.keyboard.remote.BackendClient
import com.vibe.keyboard.remote.Connection
import com.vibe.keyboard.remote.HttpResult
import com.vibe.keyboard.remote.HttpTransport
import com.vibe.keyboard.remote.InMemoryConnectionStore
import com.vibe.keyboard.remote.ReplyBlockedException
import com.vibe.keyboard.remote.RemoteAIProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteProviderTest {

    /** A pretend backend + Supabase, recording every request. */
    private class FakeServer : HttpTransport {
        val requests = mutableListOf<Triple<String, Map<String, String>, String?>>()
        var suggest: (Map<String, String>) -> HttpResult = { HttpResult(200, THREE) }
        var refresh: () -> HttpResult = { HttpResult(200, """{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600}""") }
        var down = false

        override fun send(method: String, url: String, headers: Map<String, String>, body: String?): HttpResult {
            if (down) throw IOException("no route")
            requests += Triple(url, headers, body)
            return when {
                url.endsWith("/api/conversation/suggest") -> suggest(headers)
                url.contains("grant_type=refresh_token") -> refresh()
                url.endsWith("/api/conversation/feedback") -> HttpResult(204, "")
                else -> HttpResult(404, "{}")
            }
        }

        fun suggestBodies() = requests.filter { it.first.endsWith("/suggest") }.map { Json.parseToJsonElement(it.third!!).jsonObject }
    }

    private class CountingFallback : AIProvider {
        var calls = 0
        override val isPreview = true
        override suspend fun suggest(request: SuggestionRequest): Suggestion {
            calls++
            return Suggestion("template reply", isPreview = true)
        }
    }

    private val clock = 1_800_000_000_000L
    private val signedIn = Connection(
        backendUrl = "http://127.0.0.1:8000", authRequired = true, supabaseUrl = "https://x.supabase.co",
        anonKey = "anon", accessToken = "old-access", refreshToken = "old-refresh", expiresAt = clock + 3_600_000,
        serverIsMock = false,
    )
    private lateinit var server: FakeServer
    private lateinit var fallback: CountingFallback

    @Before fun setUp() {
        SessionMemory.clear()
        server = FakeServer()
        fallback = CountingFallback()
    }

    private fun provider(conn: Connection = signedIn, store: InMemoryConnectionStore = InMemoryConnectionStore(conn)) =
        RemoteAIProvider(store, fallback, BackendClient(server, Dispatchers.Unconfined), now = { clock }, zone = ZoneOffset.UTC)

    private val request = SuggestionRequest(
        conversation = Conversation(listOf(ChatMessage(Speaker.THEM, "😂 So what did Randy say?"))),
        intent = ReplyIntent.REPLY,
        context = mapOf("event:naivas" to "we got lost there"),
        reply = ReplyContext(
            recent = listOf(
                ChatMessage(Speaker.ME, "Randy was meant to call", 1_700_000_000_000),
                ChatMessage(Speaker.THEM, "😂 So what did Randy say?"),
            ),
            memories = listOf(MemoryItem("1", MemoryKind.PERSON, "person:randy", "Randy is my roommate", MemorySource.USER, 0)),
            summary = ConversationSummary(
                basedOnMessages = 143, theirStyle = null, topics = listOf("weekend"), people = listOf("Randy"),
                places = emptyList(), sharedReferences = listOf("Remember when we got lost at Naivas"),
                recent = emptyList(), firstMessageAt = null, lastMessageAt = null, builtAt = 0,
            ),
        ),
    )

    @Test fun `not connected, it stays on the phone and sends nothing`() = runTest {
        val s = provider(Connection()).suggest(request)
        assertEquals("template reply", s.text)
        assertNull(s.note)
        assertTrue(server.requests.isEmpty())
    }

    @Test fun `connected, it gets three real options and sends the chat context`() = runTest {
        val s = provider().suggest(request)
        assertEquals(listOf("😂 You know I had to", "Haha wacha tu", "Would you not have?"), s.options)
        assertFalse(s.isPreview)
        assertEquals(0, fallback.calls)

        val (url, headers, _) = server.requests.single()
        assertEquals("http://127.0.0.1:8000/api/conversation/suggest", url)
        assertEquals("Bearer old-access", headers["Authorization"])
        val body = server.suggestBodies().single()
        assertEquals("keep_flowing", body["goal"]!!.jsonPrimitive.content)
        assertEquals(listOf("me", "them"), body["messages"]!!.jsonArray.map { it.jsonObject["speaker"]!!.jsonPrimitive.content })
        assertEquals("2023-11-14T22:13:20", body["messages"]!!.jsonArray[0].jsonObject["sent_at"]!!.jsonPrimitive.content)
        val notes = body["memory_notes"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("Randy is my roommate" in notes)
        assertTrue(notes.any { it.contains("weekend") })
        val supplied = body["supplied_context"]!!.jsonObject
        assertEquals("Randy is my roommate", supplied["person:randy"]!!.jsonPrimitive.content)
        assertEquals("we got lost there", supplied["event:naivas"]!!.jsonPrimitive.content)
    }

    @Test fun `the user's own messages travel as style samples`() = runTest {
        provider().suggest(request.copy(reply = request.reply.copy(styleSamples = listOf("aii niko tu manze", "sawa sawa"))))
        val samples = server.suggestBodies().single()["style_samples"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("aii niko tu manze", "sawa sawa"), samples)
    }

    @Test fun `using an option tells the server, regenerating rejects the rest`() = runTest {
        val c = VibeController(this, provider(), hourOfDay = { 15 })
        c.onCopied("you actually went there? 😂", automatic = true)
        advanceUntilIdle()
        c.regenerate()
        advanceUntilIdle()
        c.selectOption(1)
        c.use()
        advanceUntilIdle()
        val feedback = server.requests.filter { it.first.endsWith("/feedback") }
            .map { Json.parseToJsonElement(it.third!!).jsonObject }
            .map { it["suggestion_id"]!!.jsonPrimitive.content to it["verdict"]!!.jsonPrimitive.content }
        assertEquals(listOf("a" to "rejected", "b" to "rejected", "c" to "rejected", "b" to "used"), feedback)
        assertEquals("Bearer old-access", server.requests.first { it.first.endsWith("/feedback") }.second["Authorization"])
    }

    @Test fun `templates send no feedback anywhere`() = runTest {
        val c = VibeController(this, provider(Connection()), hourOfDay = { 15 })
        c.onCopied("you actually went there? 😂", automatic = true)
        advanceUntilIdle()
        c.use()
        advanceUntilIdle()
        assertTrue(server.requests.isEmpty())
    }

    @Test fun `a goal chip steers the reply, sticks, and toggles off`() = runTest {
        val c = VibeController(this, provider(), hourOfDay = { 15 })
        c.onCopied("you actually went there? 😂", automatic = true)
        advanceUntilIdle()
        assertTrue((c.card.value as CardState.Suggestion).steerable)

        c.chooseGoal(ReplyGoal.FLIRT)
        advanceUntilIdle()
        assertEquals(ReplyGoal.FLIRT, (c.card.value as CardState.Suggestion).goal)

        // The next message keeps the goal: someone flirting keeps flirting.
        c.onCopied("haha stop it", automatic = false)
        advanceUntilIdle()
        c.chooseGoal(ReplyGoal.FLIRT) // tapped again: off
        advanceUntilIdle()

        val goals = server.suggestBodies().map { it["goal"]!!.jsonPrimitive.content }
        assertEquals(listOf("keep_flowing", "flirt", "flirt", "keep_flowing"), goals)
    }

    @Test fun `sign-offs are never steered by a chip`() = runTest {
        provider().suggest(request.copy(intent = ReplyIntent.GOODNIGHT, goal = ReplyGoal.FLIRT))
        assertEquals("end_naturally", server.suggestBodies().single()["goal"]!!.jsonPrimitive.content)
    }

    @Test fun `something heavy gets a supportive reply unless another goal was picked`() = runTest {
        val c = VibeController(this, provider(), hourOfDay = { 15 })
        c.onCopied("I lost my job today, I'm not okay", automatic = true)
        advanceUntilIdle()
        assertEquals("comfort", server.suggestBodies().last()["goal"]!!.jsonPrimitive.content)
    }

    @Test fun `the server's labels reach the card, anything else is dropped`() = runTest {
        server.suggest = { HttpResult(200, THREE.replace("\"approach\":\"own it\"", "\"approach\":\"Best\"").replace("\"approach\":\"brush off\"", "\"approach\":\"Funny\"")) }
        val s = provider().suggest(request)
        assertEquals(listOf("Best", "Funny", ""), s.optionLabels)
    }

    @Test fun `callbacks and outcome signals travel as memory notes`() = runTest {
        val summary = request.reply.summary!!.copy(callbacks = listOf("I'd carry you but I'm charging in hugs"))
        provider().suggest(request.copy(reply = request.reply.copy(summary = summary, outcomeNote = "In this chat… playful 3/4. A signal, not proof.")))
        val notes = server.suggestBodies().single()["memory_notes"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(notes.any { it.startsWith("Callback material") && "charging in hugs" in it })
        assertTrue(notes.any { it.contains("A signal, not proof") })
    }

    @Test fun `intents map to the backend's goals and actions`() = runTest {
        val p = provider()
        p.suggest(request.copy(intent = ReplyIntent.GOODNIGHT))
        p.suggest(request.copy(intent = ReplyIntent.BOUNDARY_EXIT))
        p.suggest(request.copy(intent = ReplyIntent.MORNING))
        val bodies = server.suggestBodies()
        assertEquals("stop", bodies[0]["action"]!!.jsonPrimitive.content)
        assertEquals("stop", bodies[1]["action"]!!.jsonPrimitive.content)
        assertEquals("next_day", bodies[2]["goal"]!!.jsonPrimitive.content)
    }

    @Test fun `an expired session is refreshed once and the new tokens are kept`() = runTest {
        val store = InMemoryConnectionStore(signedIn)
        var first = true
        server.suggest = { headers ->
            if (first) { first = false; HttpResult(401, """{"detail":"expired"}""") }
            else { assertEquals("Bearer new-access", headers["Authorization"]); HttpResult(200, THREE) }
        }
        val s = provider(store = store).suggest(request)
        assertEquals(3, s.options.size)
        assertEquals("new-refresh", store.load().refreshToken)
        assertEquals("anon", server.requests.first { it.first.contains("refresh_token") }.second["apikey"])
    }

    @Test fun `a token about to expire is refreshed before use`() = runTest {
        provider(signedIn.copy(expiresAt = clock + 10_000)).suggest(request)
        assertTrue(server.requests.first().first.contains("grant_type=refresh_token"))
    }

    @Test fun `server unreachable falls back to templates and says so`() = runTest {
        server.down = true
        val s = provider().suggest(request)
        assertEquals("template reply", s.text)
        assertEquals("Can't reach your Vibe server · preview", s.note)
        assertTrue(s.isPreview)
    }

    @Test fun `rate limits and dead sessions fall back with the reason`() = runTest {
        server.suggest = { HttpResult(429, """{"detail":"slow down"}""") }
        assertEquals("Rate limit reached · preview", provider().suggest(request).note)

        server.suggest = { HttpResult(401, "{}") }
        server.refresh = { HttpResult(400, """{"error_description":"Invalid Refresh Token"}""") }
        assertEquals("Signed out · sign in again in the Vibe app · preview", provider().suggest(request).note)
    }

    @Test fun `a server with no model key is labelled as templates`() = runTest {
        server.suggest = { HttpResult(200, THREE.replace("\"is_mock\":false", "\"is_mock\":true")) }
        val s = provider().suggest(request)
        assertTrue(s.isPreview)
        assertEquals("Server has no model key · templates", s.note)
    }

    @Test(expected = ReplyBlockedException::class)
    fun `a blocked reply is the server's answer, not an error to paper over`() = runTest {
        server.suggest = { HttpResult(200, """{"suggestions":[],"blocked":true,"blocked_reason":"She set a boundary.","provider":"groq","is_mock":false}""") }
        provider().suggest(request)
    }

    // --- Through the controller -----------------------------------------------------

    @Test fun `the card offers every option and Use inserts the one picked`() = runTest {
        val c = VibeController(this, provider(), hourOfDay = { 15 })
        val actions = mutableListOf<FieldAction>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.fieldActions.toList(actions) }
        c.onCopied("you actually went there? 😂", automatic = true)
        advanceUntilIdle()
        val card = c.card.value as CardState.Suggestion
        assertEquals(3, card.options.size)
        c.selectOption(2)
        c.use()
        assertEquals(listOf(FieldAction.Insert("Would you not have?")), actions)
    }

    @Test fun `regenerate asks the server to avoid every option already shown`() = runTest {
        val c = VibeController(this, provider(), hourOfDay = { 15 })
        c.onCopied("you actually went there? 😂", automatic = true)
        advanceUntilIdle()
        c.regenerate()
        advanceUntilIdle()
        val avoid = server.suggestBodies().last()["avoid"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("😂 You know I had to", "Haha wacha tu", "Would you not have?"), avoid)
    }

    @Test fun `a blocked reply shows why instead of an error`() = runTest {
        server.suggest = { HttpResult(200, """{"suggestions":[],"blocked":true,"blocked_reason":"She referred to something only you two know.","provider":"groq","is_mock":false}""") }
        val c = VibeController(this, provider(), hourOfDay = { 15 })
        c.onCopied("you actually went there? 😂", automatic = true)
        advanceUntilIdle()
        val card = c.card.value as CardState.Failed
        assertEquals("Vibe won't write this one", card.title)
        assertEquals("She referred to something only you two know.", card.message)
    }

    @Test fun `the on-phone templates still work as the fallback`() = runTest {
        server.down = true
        val real = RemoteAIProvider(InMemoryConnectionStore(signedIn), MockAIProvider(latencyMs = 0L..0L), BackendClient(server, Dispatchers.Unconfined))
        val c = VibeController(this, real, hourOfDay = { 15 })
        c.onCopied("what are you doing later?", automatic = true)
        advanceUntilIdle()
        val card = c.card.value as CardState.Suggestion
        assertTrue(card.isPreview)
        assertEquals("Can't reach your Vibe server · preview", card.note)
    }

    private companion object {
        const val THREE = """{"suggestions":[
            {"text":"😂 You know I had to","rationale":"r","tone":"playful","approach":"own it","id":"a"},
            {"text":"Haha wacha tu","rationale":"r","tone":"playful","approach":"brush off","id":"b"},
            {"text":"Would you not have?","rationale":"r","tone":"playful","approach":"asks back","id":"c"}
        ],"blocked":false,"provider":"groq","is_mock":false}"""
    }
}
