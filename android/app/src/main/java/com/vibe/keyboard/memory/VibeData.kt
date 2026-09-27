package com.vibe.keyboard.memory

import android.content.Context
import java.io.File

/**
 * The one conversation store for this process. The keyboard and the companion
 * app run in the same process, so they share it (and its cache) and never see
 * two versions of a chat's memory.
 *
 * It lives in `noBackupFilesDir`: app-private, and excluded from cloud backup
 * and device transfer, so imported chats never leave the phone that way.
 */
object VibeData {
    @Volatile private var store: ConversationStore? = null

    fun store(context: Context): ConversationStore =
        store ?: synchronized(this) {
            store ?: FileConversationStore(File(context.applicationContext.noBackupFilesDir, "vibe")).also { store = it }
        }
}
