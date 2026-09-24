package com.vibe.keyboard.ime

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.SystemClock

/**
 * The honest way a keyboard can see a message: the user copies it.
 *
 * Android only lets the active keyboard (or the app in front) read the
 * clipboard, which is exactly this case. Vibe only reads clips that are fresh,
 * plain text and not marked sensitive, and never while a password field is open.
 */
class ClipboardSource(context: Context, private val onCopied: (String) -> Unit) {

    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    private var listening = false

    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        freshText(maxAgeMs = 5_000)?.let(onCopied)
    }

    fun start() {
        if (listening) return
        clipboard.addPrimaryClipChangedListener(listener)
        listening = true
    }

    fun stop() {
        if (!listening) return
        clipboard.removePrimaryClipChangedListener(listener)
        listening = false
    }

    /** The clipboard's text if it was copied within [maxAgeMs], else null. */
    fun freshText(maxAgeMs: Long): String? {
        val clip = runCatching { clipboard.primaryClip }.getOrNull() ?: return null
        val description = clip.description
        if (!description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) &&
            !description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
        ) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true
        ) return null
        if (ageMs(description.timestamp) > maxAgeMs) return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_CHARS)
    }

    /**
     * The platform has stamped clips with both wall-clock and elapsed-realtime
     * values across versions; anything over a year's worth of milliseconds is
     * wall-clock. Either way a clip from the future counts as old, not new.
     */
    private fun ageMs(stamp: Long): Long {
        if (stamp <= 0) return Long.MAX_VALUE
        val now = if (stamp > 31_536_000_000L) System.currentTimeMillis() else SystemClock.elapsedRealtime()
        val age = now - stamp
        return if (age < 0) Long.MAX_VALUE else age
    }

    private companion object {
        const val MAX_CHARS = 4_000
    }
}
