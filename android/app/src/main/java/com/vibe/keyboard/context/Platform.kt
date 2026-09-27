package com.vibe.keyboard.context

/**
 * The app the keyboard is typing into, from `EditorInfo.packageName`.
 *
 * This is the only identity Android gives a keyboard. It says which app, never
 * which chat: WhatsApp's message box looks the same in every conversation.
 * That is why a conversation's identity is chosen by the user (or by an
 * import), and why the core engine only ever sees [id], never a hardcoded app.
 */
data class Platform(val id: String, val label: String, val packageName: String) {

    companion object {
        private val known = mapOf(
            "com.whatsapp" to ("whatsapp" to "WhatsApp"),
            "com.whatsapp.w4b" to ("whatsapp" to "WhatsApp"),
            "com.instagram.android" to ("instagram" to "Instagram"),
            "com.snapchat.android" to ("snapchat" to "Snapchat"),
            "org.telegram.messenger" to ("telegram" to "Telegram"),
            "org.thunderdog.challegram" to ("telegram" to "Telegram"),
            "com.facebook.orca" to ("messenger" to "Messenger"),
            "com.facebook.mlite" to ("messenger" to "Messenger"),
            "com.google.android.apps.messaging" to ("sms" to "Messages"),
            "com.samsung.android.messaging" to ("sms" to "Messages"),
            "com.android.mms" to ("sms" to "Messages"),
            "com.vibe.keyboard" to ("practice" to "Practice"),
        )

        fun fromPackage(packageName: String?): Platform {
            val pkg = packageName.orEmpty()
            val (id, label) = known[pkg] ?: ("app:$pkg" to "this app")
            return Platform(id, label, pkg)
        }

        /** For conversations restored from storage or created by an import. */
        fun fromId(id: String): Platform {
            val entry = known.entries.firstOrNull { it.value.first == id }
            return if (entry != null) Platform(id, entry.value.second, entry.key) else Platform(id, "Other app", "")
        }

        val Unknown = Platform("unknown", "this app", "")
    }
}

/**
 * What is actually possible in an app, decided at runtime. Nothing here is
 * assumed from the app's name alone except what the app itself documents.
 */
data class PlatformCapabilities(
    /** Can a keyboard read the chat history on screen? On Android: never. */
    val canReadHistory: Boolean,
    /** Does the app have an official way to export a chat as text, on the phone? */
    val importSupported: Boolean,
    /** How to export, when it can. Shown on the card. */
    val importHint: String?,
    /**
     * The focused field declares IME_ACTION_SEND, so the keyboard's own Send
     * key sends. Detected from EditorInfo each time; true in WhatsApp or
     * Telegram only when "Enter is send" is on, for example.
     */
    val canSend: Boolean,
) {
    companion object {
        fun detect(platform: Platform, fieldDeclaresSend: Boolean) = PlatformCapabilities(
            canReadHistory = false,
            importSupported = platform.id == "whatsapp" || platform.id == "practice",
            importHint = when (platform.id) {
                "whatsapp" -> "In the chat: ⋮ → More → Export chat → Without media → Vibe"
                "practice" -> "Try it with a WhatsApp export"
                else -> null
            },
            canSend = fieldDeclaresSend,
        )
    }
}
