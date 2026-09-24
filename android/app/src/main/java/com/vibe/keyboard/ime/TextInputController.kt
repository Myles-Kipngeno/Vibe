package com.vibe.keyboard.ime

import android.icu.text.BreakIterator
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.vibe.keyboard.keyboard.EnterAction

/** Everything Vibe does to the app's text field goes through here. */
class TextInputController(private val connection: () -> InputConnection?) {

    fun commit(text: String) {
        connection()?.commitText(text, 1)
    }

    fun backspace() {
        val ic = connection() ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
            return
        }
        val before = ic.getTextBeforeCursor(16, 0)?.toString()
        if (before.isNullOrEmpty()) {
            // Nothing we can see; let the app decide (some fields handle DEL themselves).
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
            return
        }
        // One visible character: "❤️" or "👍🏽" is several code units, not one.
        val graphemes = BreakIterator.getCharacterInstance().apply { setText(before) }
        val end = graphemes.last()
        val start = graphemes.previous().takeIf { it != BreakIterator.DONE } ?: (end - 1)
        ic.deleteSurroundingText((end - start).coerceAtLeast(1), 0)
    }

    fun enter(action: EnterAction, info: EditorInfo?) {
        val ic = connection() ?: return
        if (action == EnterAction.NEWLINE || info == null) {
            ic.commitText("\n", 1)
        } else {
            ic.performEditorAction(info.imeOptions and EditorInfo.IME_MASK_ACTION)
        }
    }

    /** Inserts at the cursor, with a space if it would otherwise touch a word. */
    fun insertSuggestion(text: String) {
        val ic = connection() ?: return
        val before = ic.getTextBeforeCursor(1, 0)
        val prefix = if (!before.isNullOrEmpty() && !before.last().isWhitespace()) " " else ""
        ic.beginBatchEdit()
        ic.commitText(prefix + text, 1)
        ic.endBatchEdit()
    }

    /** Takes back an Auto Reply insertion, but only if the user hasn't touched it since. */
    fun removeIfLastInserted(text: String) {
        val ic = connection() ?: return
        val before = ic.getTextBeforeCursor(text.length + 1, 0)?.toString() ?: return
        if (!before.endsWith(text)) return
        val extraSpace = if (before.length > text.length && before[before.length - text.length - 1] == ' ') 1 else 0
        ic.deleteSurroundingText(text.length + extraSpace, 0)
    }

    fun isFieldEmpty(): Boolean {
        val ic = connection() ?: return true
        val before = ic.getTextBeforeCursor(1, 0)
        val after = ic.getTextAfterCursor(1, 0)
        return before.isNullOrEmpty() && after.isNullOrEmpty()
    }

    fun capsModeActive(info: EditorInfo?): Boolean {
        val ic = connection() ?: return false
        val type = info?.inputType ?: return false
        if (type and EditorInfo.TYPE_MASK_CLASS != EditorInfo.TYPE_CLASS_TEXT) return false
        return ic.getCursorCapsMode(type) != 0
    }
}
