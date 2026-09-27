package com.vibe.keyboard.remote

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Where the keyboard sends requests, and who it is signed in as. Empty
 * [backendUrl] means "not connected": Vibe stays fully on the phone.
 */
data class Connection(
    val backendUrl: String = "",
    val authRequired: Boolean = false,
    val supabaseUrl: String = "",
    val anonKey: String = "",
    val email: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    /** Epoch millis when [accessToken] stops working. */
    val expiresAt: Long = 0,
    /** What the server said it runs, for the settings screen: "groq · llama-3.3-70b". */
    val modelLabel: String = "",
    val serverIsMock: Boolean = true,
) {
    val isConfigured: Boolean get() = backendUrl.isNotBlank()
    val isSignedIn: Boolean get() = !authRequired || accessToken.isNotBlank()
    val isReady: Boolean get() = isConfigured && isSignedIn
}

interface ConnectionStore {
    suspend fun load(): Connection
    suspend fun save(connection: Connection)
}

class InMemoryConnectionStore(private var value: Connection = Connection()) : ConnectionStore {
    override suspend fun load() = value
    override suspend fun save(connection: Connection) { value = connection }
}

private val Context.connectionDataStore: DataStore<Preferences> by preferencesDataStore(name = "vibe_connection")

/**
 * App-private, and the app opts out of backup, so the sign-in tokens never
 * leave the phone. No model key is ever stored here -- the server holds that.
 */
class DataStoreConnectionStore(context: Context) : ConnectionStore {
    private val store = context.applicationContext.connectionDataStore

    val connection: Flow<Connection> = store.data.map { p ->
        Connection(
            backendUrl = p[URL].orEmpty(),
            authRequired = p[AUTH_REQUIRED] == "1",
            supabaseUrl = p[SUPABASE_URL].orEmpty(),
            anonKey = p[ANON_KEY].orEmpty(),
            email = p[EMAIL].orEmpty(),
            accessToken = p[ACCESS].orEmpty(),
            refreshToken = p[REFRESH].orEmpty(),
            expiresAt = p[EXPIRES] ?: 0,
            modelLabel = p[MODEL].orEmpty(),
            serverIsMock = p[MOCK] != "0",
        )
    }

    override suspend fun load(): Connection = connection.first()

    override suspend fun save(connection: Connection) {
        store.edit {
            it[URL] = connection.backendUrl
            it[AUTH_REQUIRED] = if (connection.authRequired) "1" else "0"
            it[SUPABASE_URL] = connection.supabaseUrl
            it[ANON_KEY] = connection.anonKey
            it[EMAIL] = connection.email
            it[ACCESS] = connection.accessToken
            it[REFRESH] = connection.refreshToken
            it[EXPIRES] = connection.expiresAt
            it[MODEL] = connection.modelLabel
            it[MOCK] = if (connection.serverIsMock) "1" else "0"
        }
    }

    private companion object {
        val URL = stringPreferencesKey("backend_url")
        val AUTH_REQUIRED = stringPreferencesKey("auth_required")
        val SUPABASE_URL = stringPreferencesKey("supabase_url")
        val ANON_KEY = stringPreferencesKey("anon_key")
        val EMAIL = stringPreferencesKey("email")
        val ACCESS = stringPreferencesKey("access_token")
        val REFRESH = stringPreferencesKey("refresh_token")
        val EXPIRES = longPreferencesKey("expires_at")
        val MODEL = stringPreferencesKey("model_label")
        val MOCK = stringPreferencesKey("server_is_mock")
    }
}
