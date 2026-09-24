package com.vibe.keyboard.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class VibePreferences(
    /** Look at a message the moment it is copied. Off: only when ✦ is tapped. */
    val suggestOnCopy: Boolean = true,
    /** Put the suggestion in the box automatically. Never sends. */
    val autoReply: Boolean = false,
    val haptics: Boolean = true,
)

private val Context.vibeDataStore: DataStore<Preferences> by preferencesDataStore(name = "vibe_settings")

/** Small, non-sensitive switches only. Nothing from a conversation is ever written here. */
class VibeSettings(context: Context) {
    private val store = context.applicationContext.vibeDataStore

    val preferences: Flow<VibePreferences> = store.data.map { p ->
        VibePreferences(
            suggestOnCopy = p[SUGGEST_ON_COPY] ?: true,
            autoReply = p[AUTO_REPLY] ?: false,
            haptics = p[HAPTICS] ?: true,
        )
    }

    suspend fun setSuggestOnCopy(on: Boolean) = store.edit { it[SUGGEST_ON_COPY] = on }
    suspend fun setAutoReply(on: Boolean) = store.edit { it[AUTO_REPLY] = on }
    suspend fun setHaptics(on: Boolean) = store.edit { it[HAPTICS] = on }

    private companion object {
        val SUGGEST_ON_COPY = booleanPreferencesKey("suggest_on_copy")
        val AUTO_REPLY = booleanPreferencesKey("auto_reply")
        val HAPTICS = booleanPreferencesKey("haptics")
    }
}
