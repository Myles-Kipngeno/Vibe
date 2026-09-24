package com.vibe.keyboard.ime

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.vibe.keyboard.keyboard.KeyboardActions
import com.vibe.keyboard.keyboard.KeyboardState
import com.vibe.keyboard.keyboard.VibeButtonState
import com.vibe.keyboard.keyboard.VibeKeyboard
import com.vibe.keyboard.overlay.CardState
import com.vibe.keyboard.overlay.VibeCardHost
import com.vibe.keyboard.overlay.VibeController
import com.vibe.keyboard.overlay.asCardActions
import com.vibe.keyboard.ui.theme.VibeColors
import kotlin.math.roundToInt

/**
 * The whole input view: a transparent slot where the card floats, then the
 * keyboard. [onVisibleTop] reports where the visible part starts (the card's
 * top when it is up, the keyboard's otherwise) so the service can tell Android
 * which part is touchable and how far to push the app up.
 */
@Composable
fun ImeRoot(
    keyboard: KeyboardState,
    controller: VibeController,
    actions: KeyboardActions,
    onVisibleTop: (Int) -> Unit,
) {
    val card by controller.card.collectAsState()
    val cardActions = remember(controller) { controller.asCardActions() }
    val config = LocalConfiguration.current
    val landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Room for the tallest card (context, expanded) in portrait. In landscape
    // there is barely any, so the card switches to its compact layout.
    val keyboardDp = if (landscape) 44 + 4 * 40 + 4 else 44 + 4 * 50 + 4
    val slotDp = (config.screenHeightDp - keyboardDp - 32).coerceIn(96, 300)
    val compact = slotDp < 200

    var cardTop by remember { mutableStateOf<Float?>(null) }
    var keyboardTop by remember { mutableStateOf(0f) }

    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().height(slotDp.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                Modifier.onGloballyPositioned { c ->
                    cardTop = if (c.size.height > 0) c.positionInRoot().y else null
                },
            ) {
                VibeCardHost(card, cardActions, compact)
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .background(VibeColors.KeyboardBg)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .onGloballyPositioned { keyboardTop = it.positionInRoot().y },
        ) {
            VibeKeyboard(
                state = keyboard,
                vibeButton = when {
                    card is CardState.Thinking -> VibeButtonState.Thinking
                    (card as? CardState.Suggestion)?.refreshing == true -> VibeButtonState.Thinking
                    card == CardState.Hidden -> VibeButtonState.Idle
                    else -> VibeButtonState.Active
                },
                capturingForVibe = (card as? CardState.ContextNeeded)?.expanded == true,
                actions = actions,
            )
        }
    }

    LaunchedEffect(cardTop, keyboardTop) {
        onVisibleTop((cardTop ?: keyboardTop).roundToInt())
    }
}
