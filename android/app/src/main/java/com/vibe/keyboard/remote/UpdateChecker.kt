package com.vibe.keyboard.remote

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A published build newer than the one installed. */
data class AppUpdate(val versionCode: Long, val versionName: String, val downloadUrl: String)

/**
 * Asks GitHub whether a newer build of the app has been published.
 *
 * Every published build puts a small version.json next to the APK at the same
 * fixed link, so this reads a few bytes, never the app itself. Anything that
 * goes wrong -- offline, GitHub unreachable, an older release without the
 * file -- simply means "no update shown".
 */
class UpdateChecker(
    private val http: HttpTransport = UrlConnectionTransport(connectTimeoutMs = 8_000, readTimeoutMs = 8_000),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    @Serializable
    private data class Published(val versionCode: Long, val versionName: String)

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun check(installedVersionCode: Long): AppUpdate? = withContext(dispatcher) {
        runCatching {
            val r = http.send("GET", VERSION_URL, mapOf("Accept" to "application/json"), null)
            if (r.status != 200) return@runCatching null
            val p = json.decodeFromString(Published.serializer(), r.body)
            if (p.versionCode > installedVersionCode) AppUpdate(p.versionCode, p.versionName, APK_URL) else null
        }.getOrNull()
    }

    companion object {
        private const val BASE = "https://github.com/Myles-Kipngeno/Vibe/releases/download/app-latest"
        const val VERSION_URL = "$BASE/version.json"
        const val APK_URL = "$BASE/vibe.apk"
    }
}
