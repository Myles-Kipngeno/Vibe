package com.vibe.keyboard.remote

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** One HTTP exchange. An interface so tests can stand in for the server. */
interface HttpTransport {
    /** @throws IOException when the server can't be reached at all. */
    fun send(method: String, url: String, headers: Map<String, String>, body: String?): HttpResult
}

data class HttpResult(val status: Int, val body: String)

/**
 * Reading waits up to a minute: a free Render service sleeps after 15 idle
 * minutes and takes about 50 seconds to answer its first request. Connecting
 * stays short, so an unreachable server still fails fast to the templates.
 */
class UrlConnectionTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
) : HttpTransport {
    override fun send(method: String, url: String, headers: Map<String, String>, body: String?): HttpResult {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return HttpResult(status, text)
        } finally {
            conn.disconnect()
        }
    }
}

// --- What the backend sends and receives (mirrors backend/app/schemas.py) -------

@Serializable
data class HealthDto(
    val status: String = "",
    val provider: String = "",
    @SerialName("is_mock") val isMock: Boolean = true,
    val model: String? = null,
    val warnings: List<String> = emptyList(),
    @SerialName("auth_required") val authRequired: Boolean = false,
    @SerialName("supabase_url") val supabaseUrl: String? = null,
    @SerialName("supabase_anon_key") val supabaseAnonKey: String? = null,
)

@Serializable
data class MessageDto(val speaker: String, val text: String, @SerialName("sent_at") val sentAt: String? = null)

@Serializable
data class SuggestRequestDto(
    val messages: List<MessageDto>,
    val goal: String = "keep_flowing",
    @SerialName("supplied_context") val suppliedContext: Map<String, String> = emptyMap(),
    val avoid: List<String> = emptyList(),
    val action: String? = null,
    @SerialName("local_time") val localTime: String? = null,
    @SerialName("memory_notes") val memoryNotes: List<String> = emptyList(),
    @SerialName("style_notes") val styleNotes: String? = null,
    @SerialName("style_samples") val styleSamples: List<String> = emptyList(),
)

@Serializable
data class FeedbackDto(
    @SerialName("suggestion_id") val suggestionId: String,
    @SerialName("suggestion_text") val suggestionText: String,
    val verdict: String,
    val note: String = "",
)

@Serializable
data class SuggestionDto(val id: String? = null, val text: String, val rationale: String = "", val approach: String = "")

@Serializable
data class SuggestResponseDto(
    val suggestions: List<SuggestionDto> = emptyList(),
    val blocked: Boolean = false,
    @SerialName("blocked_reason") val blockedReason: String? = null,
    val guidance: String? = null,
    val provider: String = "",
    @SerialName("is_mock") val isMock: Boolean = true,
    @SerialName("unresolved_alerts") val unresolvedAlerts: List<JsonElement> = emptyList(),
)

@Serializable
data class SupabaseSession(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long = 3600,
)

@Serializable
private data class PasswordGrant(val email: String, val password: String)

@Serializable
private data class RefreshGrant(@SerialName("refresh_token") val refreshToken: String)

@Serializable
private data class ErrorDto(val detail: String? = null, val msg: String? = null, @SerialName("error_description") val errorDescription: String? = null)

class BackendException(val status: Int, message: String) : Exception(message)

/**
 * The keyboard's only connection to the outside: this repo's backend, which
 * holds the model key, and Supabase Auth for signing in. The phone never holds
 * a model key.
 */
class BackendClient(
    private val http: HttpTransport = UrlConnectionTransport(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    suspend fun health(baseUrl: String): HealthDto = io {
        val r = http.send("GET", "${baseUrl.trimEnd('/')}/api/health", emptyMap(), null)
        decode(r, HealthDto.serializer())
    }

    suspend fun suggest(baseUrl: String, accessToken: String?, body: SuggestRequestDto): SuggestResponseDto = io {
        val headers = buildMap {
            put("Content-Type", "application/json")
            if (accessToken != null) put("Authorization", "Bearer $accessToken")
        }
        val r = http.send(
            "POST", "${baseUrl.trimEnd('/')}/api/conversation/suggest", headers,
            json.encodeToString(SuggestRequestDto.serializer(), body),
        )
        decode(r, SuggestResponseDto.serializer())
    }

    suspend fun feedback(baseUrl: String, accessToken: String?, body: FeedbackDto) = io {
        val headers = buildMap {
            put("Content-Type", "application/json")
            if (accessToken != null) put("Authorization", "Bearer $accessToken")
        }
        val r = http.send(
            "POST", "${baseUrl.trimEnd('/')}/api/conversation/feedback", headers,
            json.encodeToString(FeedbackDto.serializer(), body),
        )
        if (r.status >= 400) throw BackendException(r.status, "feedback not recorded")
    }

    suspend fun signIn(supabaseUrl: String, anonKey: String, email: String, password: String): SupabaseSession = io {
        val r = http.send(
            "POST", "${supabaseUrl.trimEnd('/')}/auth/v1/token?grant_type=password", authHeaders(anonKey),
            json.encodeToString(PasswordGrant.serializer(), PasswordGrant(email.trim(), password)),
        )
        decode(r, SupabaseSession.serializer())
    }

    suspend fun refresh(supabaseUrl: String, anonKey: String, refreshToken: String): SupabaseSession = io {
        val r = http.send(
            "POST", "${supabaseUrl.trimEnd('/')}/auth/v1/token?grant_type=refresh_token", authHeaders(anonKey),
            json.encodeToString(RefreshGrant.serializer(), RefreshGrant(refreshToken)),
        )
        decode(r, SupabaseSession.serializer())
    }

    private fun authHeaders(anonKey: String) = mapOf("Content-Type" to "application/json", "apikey" to anonKey)

    private fun <T> decode(r: HttpResult, serializer: kotlinx.serialization.KSerializer<T>): T {
        if (r.status >= 400) {
            val err = runCatching { json.decodeFromString(ErrorDto.serializer(), r.body) }.getOrNull()
            val message = err?.errorDescription ?: err?.msg ?: err?.detail ?: "HTTP ${r.status}"
            throw BackendException(r.status, message)
        }
        return try {
            json.decodeFromString(serializer, r.body)
        } catch (e: Exception) {
            throw BackendException(r.status, "Unexpected response from the server")
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(dispatcher) { block() }
}
