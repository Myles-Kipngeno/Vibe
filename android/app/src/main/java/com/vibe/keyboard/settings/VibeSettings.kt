package com.vibe.keyboard.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vibe.keyboard.auto.AutoRulesConfig
import com.vibe.keyboard.auto.ReplyMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class VibePreferences(
    /** Look at a message the moment it is copied. Off: only when Scan is tapped. */
    val suggestOnCopy: Boolean = true,
    /** Auto mode. Off (Suggest) by default, and only ever turned on by the user. */
    val autoReply: Boolean = false,
    val haptics: Boolean = true,
    val autoRules: AutoRulesConfig = AutoRulesConfig(),
) {
    val mode: ReplyMode get() = if (autoReply) ReplyMode.AUTO else ReplyMode.SUGGEST
}

private val Context.vibeDataStore: DataStore<Preferences> by preferencesDataStore(name = "vibe_settings")

/** Small, non-sensitive switches only. Nothing from a conversation is ever written here. */
class VibeSettings(context: Context) {
    private val store = context.applicationContext.vibeDataStore

    val preferences: Flow<VibePreferences> = store.data.map { p ->
        VibePreferences(
            suggestOnCopy = p[SUGGEST_ON_COPY] ?: true,
            autoReply = p[AUTO_REPLY] ?: false,
            haptics = p[HAPTICS] ?: true,
            autoRules = AutoRulesConfig(
                onlyWithContext = p[AUTO_ONLY_WITH_CONTEXT] ?: true,
                onlyCasual = p[AUTO_ONLY_CASUAL] ?: true,
                sendWhereSupported = p[AUTO_SEND] ?: true,
                sendDelaySeconds = p[AUTO_DELAY] ?: 5,
            ),
        )
    }

    suspend fun setSuggestOnCopy(on: Boolean) = store.edit { it[SUGGEST_ON_COPY] = on }
    suspend fun setAutoReply(on: Boolean) = store.edit { it[AUTO_REPLY] = on }
    suspend fun setMode(mode: ReplyMode) = setAutoReply(mode == ReplyMode.AUTO)
    suspend fun setHaptics(on: Boolean) = store.edit { it[HAPTICS] = on }
    suspend fun setAutoOnlyWithContext(on: Boolean) = store.edit { it[AUTO_ONLY_WITH_CONTEXT] = on }
    suspend fun setAutoOnlyCasual(on: Boolean) = store.edit { it[AUTO_ONLY_CASUAL] = on }
    suspend fun setAutoSend(on: Boolean) = store.edit { it[AUTO_SEND] = on }
    suspend fun setAutoDelay(seconds: Int) = store.edit { it[AUTO_DELAY] = seconds.coerceIn(3, 15) }

    private companion object {
        val SUGGEST_ON_COPY = booleanPreferencesKey("suggest_on_copy")
        // Not "auto_reply": that older switch only ever filled the box. Auto can now
        // send, so it starts off for everyone and has to be turned on again.
        val AUTO_REPLY = booleanPreferencesKey("reply_mode_auto")
        val HAPTICS = booleanPreferencesKey("haptics")
        val AUTO_ONLY_WITH_CONTEXT = booleanPreferencesKey("auto_only_with_context")
        val AUTO_ONLY_CASUAL = booleanPreferencesKey("auto_only_casual")
        val AUTO_SEND = booleanPreferencesKey("auto_send")
        val AUTO_DELAY = intPreferencesKey("auto_delay_seconds")
    }
}
