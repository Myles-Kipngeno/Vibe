package com.vibe.keyboard.keyboard

import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class KeyboardMode { LETTERS, SYMBOLS, EMOJI }

enum class ShiftState { OFF, ONCE, LOCKED }

/** What the Enter key does in the current field, and so what it looks like. */
enum class EnterAction { NEWLINE, SEND, SEARCH, GO, NEXT, DONE }

/** Keyboard UI state, observed by Compose. Owned by the service. */
class KeyboardState {
    var mode by mutableStateOf(KeyboardMode.LETTERS)
    var shift by mutableStateOf(ShiftState.OFF)
    var enterAction by mutableStateOf(EnterAction.NEWLINE)

    /** False in password and incognito fields: Vibe steps aside entirely there. */
    var vibeAllowed by mutableStateOf(true)
    var autoReply by mutableStateOf(false)
    var haptics by mutableStateOf(true)

    private var lastShiftTap = 0L

    fun onShiftTapped(now: Long) {
        shift = when {
            shift == ShiftState.LOCKED -> ShiftState.OFF
            now - lastShiftTap < DOUBLE_TAP_MS -> ShiftState.LOCKED
            shift == ShiftState.ONCE -> ShiftState.OFF
            else -> ShiftState.ONCE
        }
        lastShiftTap = now
    }

    /** After a letter, a one-shot shift falls back to lowercase. */
    fun onLetterTyped() {
        if (shift == ShiftState.ONCE) shift = ShiftState.OFF
    }

    fun applyAutoCaps(capsModeActive: Boolean) {
        if (shift == ShiftState.LOCKED) return
        shift = if (capsModeActive) ShiftState.ONCE else ShiftState.OFF
    }

    fun configureFor(info: EditorInfo) {
        mode = when (info.inputType and EditorInfo.TYPE_MASK_CLASS) {
            EditorInfo.TYPE_CLASS_NUMBER, EditorInfo.TYPE_CLASS_PHONE, EditorInfo.TYPE_CLASS_DATETIME -> KeyboardMode.SYMBOLS
            else -> KeyboardMode.LETTERS
        }
        enterAction = enterActionFor(info)
        vibeAllowed = !isSensitive(info)
    }

    companion object {
        private const val DOUBLE_TAP_MS = 300L

        fun enterActionFor(info: EditorInfo): EnterAction {
            val multiline = info.inputType and EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE != 0
            if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0 || multiline) return EnterAction.NEWLINE
            return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
                EditorInfo.IME_ACTION_SEND -> EnterAction.SEND
                EditorInfo.IME_ACTION_SEARCH -> EnterAction.SEARCH
                EditorInfo.IME_ACTION_GO -> EnterAction.GO
                EditorInfo.IME_ACTION_NEXT -> EnterAction.NEXT
                EditorInfo.IME_ACTION_DONE -> EnterAction.DONE
                else -> EnterAction.NEWLINE
            }
        }

        /**
         * Passwords, and fields where the app asked for no learning (incognito tabs).
         * Vibe reads nothing and shows nothing in these.
         */
        fun isSensitive(info: EditorInfo): Boolean {
            val cls = info.inputType and EditorInfo.TYPE_MASK_CLASS
            val variation = info.inputType and EditorInfo.TYPE_MASK_VARIATION
            val password = (cls == EditorInfo.TYPE_CLASS_TEXT && variation in setOf(
                EditorInfo.TYPE_TEXT_VARIATION_PASSWORD,
                EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            )) || (cls == EditorInfo.TYPE_CLASS_NUMBER && variation == EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD)
            val incognito = info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0
            return password || incognito
        }
    }
}
