package com.vibe.keyboard.keyboard

import android.content.res.Configuration
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibe.keyboard.ui.MarkMood
import com.vibe.keyboard.ui.VibeIcons
import com.vibe.keyboard.ui.VibeMark
import com.vibe.keyboard.ui.theme.VibeColors
import com.vibe.keyboard.ui.theme.VibeType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

interface KeyboardActions {
    fun onText(text: String)
    fun onBackspace()
    fun onEnter()
    fun onShift()
    fun onMode(mode: KeyboardMode)
    fun onVibeTap()
    fun onSpaceLongPress()
    fun onHide()
    fun onOpenSettings()
}

/** How the ✦ button looks, which mirrors what the card is doing. */
enum class VibeButtonState { Idle, Thinking, Active }

@Composable
fun VibeKeyboard(
    state: KeyboardState,
    vibeButton: VibeButtonState,
    capturingForVibe: Boolean,
    actions: KeyboardActions,
    modifier: Modifier = Modifier,
) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val keyHeight = if (landscape) 40.dp else 50.dp

    Column(modifier.fillMaxWidth().background(VibeColors.KeyboardBg).padding(horizontal = 3.dp)) {
        Toolbar(state, vibeButton, capturingForVibe, actions)
        when (state.mode) {
            KeyboardMode.LETTERS -> LetterRows(state, keyHeight, actions)
            KeyboardMode.SYMBOLS -> SymbolRows(state, keyHeight, actions)
            KeyboardMode.EMOJI -> EmojiPanel(state, keyHeight, actions)
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun Toolbar(
    state: KeyboardState,
    vibeButton: VibeButtonState,
    capturing: Boolean,
    actions: KeyboardActions,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 4.dp),
    ) {
        VibeButton(state.vibeAllowed, vibeButton, state.haptics, actions::onVibeTap)
        Spacer(Modifier.width(10.dp))
        AnimatedVisibility(capturing, enter = fadeIn(), exit = fadeOut()) {
            Text("Typing to Vibe · not sent", style = VibeType.CardQuote, color = VibeColors.Accent)
        }
        if (!state.vibeAllowed) {
            Text("Vibe is off in private fields", style = VibeType.CardQuote, color = VibeColors.TextTertiary)
        }
        Spacer(Modifier.weight(1f))
        if (state.autoReply && state.vibeAllowed) {
            Text(
                "AUTO",
                style = VibeType.Tag,
                color = VibeColors.Accent,
                modifier = Modifier
                    .border(1.dp, VibeColors.Accent.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        ToolbarIcon(VibeIcons.Tune, "Vibe settings", state.haptics, actions::onOpenSettings)
        ToolbarIcon(VibeIcons.ChevronDown, "Hide keyboard", state.haptics, actions::onHide)
    }
}

@Composable
private fun VibeButton(enabled: Boolean, state: VibeButtonState, haptics: Boolean, onTap: () -> Unit) {
    val bg by animateColorAsState(
        when {
            !enabled -> Color.Transparent
            state == VibeButtonState.Idle -> VibeColors.Accent.copy(alpha = 0.10f)
            else -> VibeColors.Accent.copy(alpha = 0.22f)
        },
        tween(200),
        label = "vibe-button",
    )
    KeyBox(
        modifier = Modifier.size(36.dp),
        color = bg,
        pressedColor = VibeColors.Accent.copy(alpha = 0.30f),
        shape = CircleShape,
        haptics = haptics,
        enabled = enabled,
        onTap = onTap,
        padding = 0.dp,
        description = "Ask Vibe",
    ) {
        VibeMark(
            size = 17.dp,
            tint = if (enabled) VibeColors.Accent else VibeColors.TextTertiary,
            mood = if (state == VibeButtonState.Thinking) MarkMood.Thinking else MarkMood.Idle,
        )
    }
}

@Composable
private fun ToolbarIcon(icon: ImageVector, description: String, haptics: Boolean, onTap: () -> Unit) {
    KeyBox(
        modifier = Modifier.size(40.dp),
        color = Color.Transparent,
        pressedColor = VibeColors.KeyPressed,
        shape = CircleShape,
        haptics = haptics,
        onTap = onTap,
        padding = 0.dp,
        description = description,
    ) {
        Icon(icon, contentDescription = null, tint = VibeColors.TextSecondary, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun LetterRows(state: KeyboardState, keyHeight: Dp, actions: KeyboardActions) {
    val upper = state.shift != ShiftState.OFF
    val rows = KeyboardLayouts.letters
    KeyRow(keyHeight) { rows[0].forEach { c -> CharKey(c, upper, state, actions) } }
    KeyRow(keyHeight) {
        Spacer(Modifier.weight(0.5f))
        rows[1].forEach { c -> CharKey(c, upper, state, actions) }
        Spacer(Modifier.weight(0.5f))
    }
    KeyRow(keyHeight) {
        FunctionKey(
            icon = when (state.shift) {
                ShiftState.OFF -> VibeIcons.ShiftOff
                ShiftState.ONCE -> VibeIcons.ShiftOn
                ShiftState.LOCKED -> VibeIcons.ShiftLocked
            },
            description = "Shift",
            weight = 1.5f,
            state = state,
            highlighted = state.shift != ShiftState.OFF,
            onTap = actions::onShift,
        )
        rows[2].forEach { c -> CharKey(c, upper, state, actions) }
        FunctionKey(VibeIcons.Backspace, "Delete", 1.5f, state, repeat = true, onTap = actions::onBackspace)
    }
    BottomRow(state, keyHeight, actions, modeLabel = "?123", modeTarget = KeyboardMode.SYMBOLS)
}

@Composable
private fun SymbolRows(state: KeyboardState, keyHeight: Dp, actions: KeyboardActions) {
    val rows = KeyboardLayouts.symbols
    KeyRow(keyHeight) { rows[0].forEach { TextKey(it, state, actions) } }
    KeyRow(keyHeight) { rows[1].forEach { TextKey(it, state, actions) } }
    KeyRow(keyHeight) {
        Spacer(Modifier.weight(0.5f))
        rows[2].forEach { TextKey(it, state, actions) }
        FunctionKey(VibeIcons.Backspace, "Delete", 1.5f, state, repeat = true, onTap = actions::onBackspace)
    }
    BottomRow(state, keyHeight, actions, modeLabel = "ABC", modeTarget = KeyboardMode.LETTERS)
}

@Composable
private fun EmojiPanel(state: KeyboardState, keyHeight: Dp, actions: KeyboardActions) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(8),
        modifier = Modifier.fillMaxWidth().height(keyHeight * 3),
    ) {
        items(KeyboardLayouts.emoji) { emoji ->
            KeyBox(
                modifier = Modifier.height(keyHeight),
                color = Color.Transparent,
                pressedColor = VibeColors.KeyPressed,
                haptics = state.haptics,
                onTap = { actions.onText(emoji) },
                description = emoji,
            ) {
                Text(emoji, style = TextStyle(fontSize = 24.sp))
            }
        }
    }
    KeyRow(keyHeight) {
        FunctionLabelKey("ABC", 1.5f, state) { actions.onMode(KeyboardMode.LETTERS) }
        SpaceKey(state, actions, weight = 5f)
        FunctionKey(VibeIcons.Backspace, "Delete", 1.5f, state, repeat = true, onTap = actions::onBackspace)
    }
}

@Composable
private fun BottomRow(
    state: KeyboardState,
    keyHeight: Dp,
    actions: KeyboardActions,
    modeLabel: String,
    modeTarget: KeyboardMode,
) {
    KeyRow(keyHeight) {
        FunctionLabelKey(modeLabel, 1.5f, state) { actions.onMode(modeTarget) }
        FunctionKey(VibeIcons.Emoji, "Emoji", 1f, state) { actions.onMode(KeyboardMode.EMOJI) }
        TextKey(",", state, actions)
        SpaceKey(state, actions, weight = 4f)
        TextKey(".", state, actions)
        EnterKey(state, actions)
    }
}

@Composable
private fun KeyRow(height: Dp, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(height), content = content)
}

@Composable
private fun RowScope.CharKey(c: Char, upper: Boolean, state: KeyboardState, actions: KeyboardActions) {
    val label = if (upper) c.uppercaseChar().toString() else c.toString()
    KeyBox(
        modifier = Modifier.weight(1f),
        haptics = state.haptics,
        onTap = { actions.onText(label) },
        description = label,
    ) {
        Text(label, style = VibeType.Key, color = VibeColors.KeyText)
    }
}

@Composable
private fun RowScope.TextKey(text: String, state: KeyboardState, actions: KeyboardActions) {
    KeyBox(
        modifier = Modifier.weight(1f),
        haptics = state.haptics,
        onTap = { actions.onText(text) },
        description = text,
    ) {
        Text(text, style = if (text.length > 1) VibeType.KeySmall else VibeType.Key, color = VibeColors.KeyText)
    }
}

@Composable
private fun RowScope.SpaceKey(state: KeyboardState, actions: KeyboardActions, weight: Float) {
    KeyBox(
        modifier = Modifier.weight(weight),
        haptics = state.haptics,
        onTap = { actions.onText(" ") },
        onLongPress = actions::onSpaceLongPress,
        description = "Space",
    ) {
        Text("English · Sheng", style = VibeType.CardQuote, color = VibeColors.TextTertiary)
    }
}

@Composable
private fun RowScope.EnterKey(state: KeyboardState, actions: KeyboardActions) {
    KeyBox(
        modifier = Modifier.weight(1.5f),
        color = VibeColors.AccentDeep.copy(alpha = 0.55f),
        pressedColor = VibeColors.AccentDeep,
        haptics = state.haptics,
        onTap = actions::onEnter,
        description = "Enter",
    ) {
        val icon = when (state.enterAction) {
            EnterAction.NEWLINE -> VibeIcons.Return
            EnterAction.SEND -> Icons.AutoMirrored.Filled.Send
            EnterAction.SEARCH -> Icons.Filled.Search
            EnterAction.GO, EnterAction.NEXT -> Icons.AutoMirrored.Filled.ArrowForward
            EnterAction.DONE -> VibeIcons.Check
        }
        Icon(icon, contentDescription = null, tint = VibeColors.KeyText, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun RowScope.FunctionKey(
    icon: ImageVector,
    description: String,
    weight: Float,
    state: KeyboardState,
    highlighted: Boolean = false,
    repeat: Boolean = false,
    onTap: () -> Unit,
) {
    KeyBox(
        modifier = Modifier.weight(weight),
        color = if (highlighted) VibeColors.KeyPressed else VibeColors.KeyFunction,
        haptics = state.haptics,
        repeat = repeat,
        onTap = onTap,
        description = description,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (highlighted) VibeColors.Accent else VibeColors.KeyText,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun RowScope.FunctionLabelKey(label: String, weight: Float, state: KeyboardState, onTap: () -> Unit) {
    KeyBox(
        modifier = Modifier.weight(weight),
        color = VibeColors.KeyFunction,
        haptics = state.haptics,
        onTap = onTap,
        description = label,
    ) {
        Text(label, style = VibeType.KeySmall, color = VibeColors.KeyText)
    }
}

/**
 * One key. Normal keys fire on release; [repeat] keys fire on press and then
 * repeat while held (backspace); [onLongPress] replaces the tap when held.
 */
@Composable
private fun KeyBox(
    modifier: Modifier,
    color: Color = VibeColors.Key,
    pressedColor: Color = VibeColors.KeyPressed,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(9.dp),
    haptics: Boolean,
    enabled: Boolean = true,
    repeat: Boolean = false,
    padding: Dp = 3.dp,
    description: String,
    onTap: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val tap by rememberUpdatedState(onTap)
    val longPress by rememberUpdatedState(onLongPress)
    val hapticsOn by rememberUpdatedState(haptics)
    val active by rememberUpdatedState(enabled)

    Box(
        modifier
            .fillMaxHeight()
            .padding(horizontal = padding, vertical = if (padding > 0.dp) 4.dp else 0.dp)
            .clip(shape)
            .background(if (pressed) pressedColor else color)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    if (!active) return@awaitEachGesture
                    pressed = true
                    if (hapticsOn) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    var longFired = false
                    val job = when {
                        repeat -> {
                            tap()
                            scope.launch {
                                delay(400)
                                while (true) {
                                    tap()
                                    delay(55)
                                }
                            }
                        }
                        longPress != null -> scope.launch {
                            delay(450)
                            longFired = true
                            if (hapticsOn) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            longPress?.invoke()
                        }
                        else -> null
                    }
                    val up = waitForUpOrCancellation()
                    job?.cancel()
                    pressed = false
                    if (!repeat && !longFired && up != null) tap()
                }
            }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
    }
}
