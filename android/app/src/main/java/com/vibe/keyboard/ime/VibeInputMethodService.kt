package com.vibe.keyboard.ime

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.vibe.keyboard.ai.MockAIProvider
import com.vibe.keyboard.app.MainActivity
import com.vibe.keyboard.keyboard.KeyboardActions
import com.vibe.keyboard.keyboard.KeyboardMode
import com.vibe.keyboard.keyboard.KeyboardState
import com.vibe.keyboard.overlay.CardState
import com.vibe.keyboard.overlay.FieldAction
import com.vibe.keyboard.overlay.VibeController
import com.vibe.keyboard.settings.VibePreferences
import com.vibe.keyboard.settings.VibeSettings
import com.vibe.keyboard.ui.theme.VibeTheme
import kotlinx.coroutines.launch

/**
 * The Vibe keyboard.
 *
 * Compose needs a lifecycle and saved-state owner, which a Service does not
 * have, so this provides both and attaches them to the input window.
 */
class VibeInputMethodService : InputMethodService(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private val keyboard = KeyboardState()
    private lateinit var input: TextInputController
    private lateinit var controller: VibeController
    private lateinit var clipboard: ClipboardSource
    private var prefs = VibePreferences()

    private var inputRoot: View? = null
    private var visibleTopPx = 0
    private var lastPackage: String? = null

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        input = TextInputController { currentInputConnection }
        // MOCK: swap for RemoteAIProvider once the backend call is wired in.
        controller = VibeController(lifecycleScope, MockAIProvider())
        controller.fieldIsEmpty = { input.isFieldEmpty() }
        clipboard = ClipboardSource(this) { text ->
            if (prefs.suggestOnCopy && keyboard.vibeAllowed) controller.onCopied(text, automatic = true)
        }

        lifecycleScope.launch {
            VibeSettings(this@VibeInputMethodService).preferences.collect {
                prefs = it
                keyboard.autoReply = it.autoReply
                keyboard.haptics = it.haptics
                controller.autoReply = it.autoReply
            }
        }
        lifecycleScope.launch {
            controller.fieldActions.collect { action ->
                when (action) {
                    is FieldAction.Insert -> input.insertSuggestion(action.text)
                    is FieldAction.Remove -> input.removeIfLastInserted(action.text)
                }
            }
        }
    }

    override fun onCreateInputView(): View {
        window.window?.decorView?.let { decor ->
            decor.setViewTreeLifecycleOwner(this)
            decor.setViewTreeSavedStateRegistryOwner(this)
        }
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED

        return ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@VibeInputMethodService)
            setViewTreeSavedStateRegistryOwner(this@VibeInputMethodService)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                VibeTheme {
                    ImeRoot(keyboard, controller, keyboardActions) { top ->
                        if (top != visibleTopPx) {
                            visibleTopPx = top
                            requestLayout()
                        }
                    }
                }
            }
        }.also { inputRoot = it }
    }

    /** Landscape "extract" mode would hide the app and the card. Vibe never uses it. */
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        keyboard.configureFor(info)

        if (info.packageName != lastPackage) {
            controller.onAppChanged()
            lastPackage = info.packageName
        }
        refreshAutoCaps()

        if (keyboard.vibeAllowed) {
            clipboard.start()
            // A message copied just before opening the keyboard is the one to reply to.
            if (prefs.suggestOnCopy) clipboard.freshText(maxAgeMs = 90_000)?.let { controller.onCopied(it, automatic = true) }
        } else {
            clipboard.stop()
            controller.dismiss()
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        clipboard.stop()
        controller.dismiss()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (!controller.isCapturingKeys) refreshAutoCaps()
    }

    /**
     * Only the region from the card (or keyboard) down is touchable, and only
     * that much pushes the app up -- the empty slot above the card lets touches
     * through to the conversation.
     */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val root = inputRoot ?: return
        if (!isInputViewShown || !root.isAttachedToWindow) return
        val location = IntArray(2).also { root.getLocationInWindow(it) }
        val top = location[1] + visibleTopPx
        outInsets.contentTopInsets = top
        outInsets.visibleTopInsets = top
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(0, top, root.width, location[1] + root.height)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && isInputViewShown && controller.card.value != CardState.Hidden) {
            controller.dismiss()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        clipboard.stop()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    private fun refreshAutoCaps() {
        keyboard.applyAutoCaps(input.capsModeActive(currentInputEditorInfo))
    }

    private val keyboardActions = object : KeyboardActions {
        override fun onText(text: String) {
            if (controller.isCapturingKeys) {
                controller.typeIntoContext(text)
                val draft = (controller.card.value as? CardState.ContextNeeded)?.draft.orEmpty()
                keyboard.applyAutoCaps(draft.isEmpty() || draft.trimEnd().endsWith('.'))
                return
            }
            input.commit(text)
            if (text.length == 1 && text[0].isLetter()) keyboard.onLetterTyped()
        }

        override fun onBackspace() {
            if (controller.isCapturingKeys) controller.deleteFromContext() else input.backspace()
        }

        override fun onEnter() {
            if (controller.isCapturingKeys) controller.submitContext()
            else input.enter(keyboard.enterAction, currentInputEditorInfo)
        }

        override fun onShift() = keyboard.onShiftTapped(SystemClock.uptimeMillis())

        override fun onMode(mode: KeyboardMode) {
            keyboard.mode = mode
        }

        override fun onVibeTap() {
            if (!keyboard.vibeAllowed) return
            if (controller.card.value != CardState.Hidden) {
                controller.dismiss()
            } else {
                // An explicit tap: a message copied in the last few minutes still counts.
                controller.onManualRequest(clipboard.freshText(maxAgeMs = 5 * 60_000))
            }
        }

        override fun onSpaceLongPress() {
            getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        }

        override fun onHide() = requestHideSelf(0)

        override fun onOpenSettings() {
            startActivity(
                Intent(this@VibeInputMethodService, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }
    }
}
