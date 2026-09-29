package com.vibe.keyboard.overlay

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibe.keyboard.ai.ReplyGoal
import com.vibe.keyboard.auto.ReplyMode
import com.vibe.keyboard.ui.MarkMood
import com.vibe.keyboard.ui.VibeIcons
import com.vibe.keyboard.ui.VibeMark
import com.vibe.keyboard.ui.theme.VibeColors
import com.vibe.keyboard.ui.theme.VibeType
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Everything a card can ask the controller to do. */
interface CardActions {
    fun use()
    fun regenerate()
    fun dismiss()
    fun undoAutoInsert()
    fun explain()
    fun toggleRemember()
    fun submitContext()
    fun continueConversation()
    fun wrapUp()
    fun waitQuietly()
    fun replyToPicture()
    fun replyToBoundary()
    fun retry()
    fun cancelSend()
    fun selectOption(index: Int)
    fun chooseGoal(goal: ReplyGoal)
    fun openPanel()
    fun scan()
    fun chooseConversation()
    fun newConversation()
    fun selectConversation(id: String)
    fun continueWithoutContext()
    fun openImport()
    fun openSettings()
    fun setMode(mode: ReplyMode)
    fun confirmAuto()
    fun openMemory()
    fun forgetMemory(id: String)
    fun editMemory(id: String)
    fun askClearMemory()
    fun confirmClearMemory()
}

fun VibeController.asCardActions(): CardActions = object : CardActions {
    override fun use() = this@asCardActions.use()
    override fun regenerate() = this@asCardActions.regenerate()
    override fun dismiss() = this@asCardActions.dismiss()
    override fun undoAutoInsert() = this@asCardActions.undoAutoInsert()
    override fun explain() = this@asCardActions.explain()
    override fun toggleRemember() = this@asCardActions.toggleRemember()
    override fun submitContext() = this@asCardActions.submitContext()
    override fun continueConversation() = this@asCardActions.continueConversation()
    override fun wrapUp() = this@asCardActions.wrapUp()
    override fun waitQuietly() = this@asCardActions.waitQuietly()
    override fun replyToPicture() = this@asCardActions.replyToPicture()
    override fun replyToBoundary() = this@asCardActions.replyToBoundary()
    override fun retry() = this@asCardActions.retry()
    override fun cancelSend() = this@asCardActions.cancelSend()
    override fun selectOption(index: Int) = this@asCardActions.selectOption(index)
    override fun chooseGoal(goal: ReplyGoal) = this@asCardActions.chooseGoal(goal)
    override fun openPanel() = this@asCardActions.openPanel()
    override fun scan() = this@asCardActions.onScan()
    override fun chooseConversation() = this@asCardActions.chooseConversation()
    override fun newConversation() = this@asCardActions.newConversation()
    override fun selectConversation(id: String) = this@asCardActions.selectConversation(id)
    override fun continueWithoutContext() = this@asCardActions.continueWithoutContext()
    override fun openImport() = this@asCardActions.openImport()
    override fun openSettings() = this@asCardActions.openSettings()
    override fun setMode(mode: ReplyMode) = this@asCardActions.requestMode(mode)
    override fun confirmAuto() = this@asCardActions.confirmAuto()
    override fun openMemory() = this@asCardActions.openMemory()
    override fun forgetMemory(id: String) = this@asCardActions.forgetMemory(id)
    override fun editMemory(id: String) = this@asCardActions.editMemory(id)
    override fun askClearMemory() = this@asCardActions.askClearMemory()
    override fun confirmClearMemory() = this@asCardActions.confirmClearMemory()
}

private val CardShape = RoundedCornerShape(22.dp)

private class LastShown {
    var state: CardState? = null
}

/**
 * Hosts the card above the keyboard: slides and fades in, fades down and out,
 * and animates its own height when its content changes (e.g. Explain).
 */
@Composable
fun VibeCardHost(
    state: CardState,
    actions: CardActions,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    // Keep rendering the last real state while the exit animation runs. A plain
    // holder rather than snapshot state: writing state during composition would
    // schedule another composition for no reason.
    val last = remember { LastShown() }
    if (state != CardState.Hidden) last.state = state
    val lastShown = last.state

    AnimatedVisibility(
        visible = state != CardState.Hidden,
        modifier = modifier,
        enter = fadeIn(tween(180)) +
            slideInVertically(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) { it / 3 } +
            scaleIn(spring(stiffness = Spring.StiffnessMediumLow), initialScale = 0.96f),
        exit = fadeOut(tween(140)) +
            slideOutVertically(tween(180)) { it / 5 } +
            scaleOut(tween(180), targetScale = 0.97f),
    ) {
        val shown = lastShown ?: return@AnimatedVisibility
        SwipeToDismiss(onDismiss = actions::dismiss) {
            CardSurface {
                AnimatedContent(
                    targetState = shown,
                    contentKey = { it::class },
                    transitionSpec = {
                        (fadeIn(tween(200, delayMillis = 60)) togetherWith fadeOut(tween(90)))
                            .using(SizeTransform(clip = false) { _, _ -> spring(stiffness = Spring.StiffnessMediumLow) })
                    },
                    label = "card-content",
                ) { card ->
                    CardContent(card, actions, compact)
                }
            }
        }
    }
}

@Composable
private fun CardContent(card: CardState, actions: CardActions, compact: Boolean) {
    when (card) {
        is CardState.Suggestion -> SuggestionContent(card, actions, compact)
        is CardState.ContextNeeded -> ContextContent(card, actions, compact)
        is CardState.Ending -> EndingContent(card, actions)
        is CardState.PictureRequest -> PictureContent(card, actions, compact)
        is CardState.Boundary -> BoundaryContent(card, actions, compact)
        is CardState.Thinking -> ThinkingContent(card)
        is CardState.Notice -> NoticeContent(card, actions)
        is CardState.Failed -> FailedContent(card, actions)
        is CardState.Attention -> AttentionContent(card, actions, compact)
        is CardState.Panel -> PanelContent(card, actions, compact)
        is CardState.ChooseConversation -> ChooseContent(card, actions, compact)
        is CardState.NameConversation -> NameContent(card, actions)
        is CardState.ContextUnavailable -> UnavailableContent(card, actions, compact)
        is CardState.MemoryView -> MemoryContent(card, actions, compact)
        is CardState.EditMemory -> EditMemoryContent(card, actions)
        is CardState.AutoIntro -> AutoIntroContent(card, actions, compact)
        CardState.Hidden -> Unit
    }
}

// --- States -----------------------------------------------------------------

@Composable
private fun SuggestionContent(card: CardState.Suggestion, actions: CardActions, compact: Boolean) {
    Column {
        CardHeader(
            label = card.label,
            onDismiss = actions::dismiss,
            icon = { VibeMark(mood = if (card.refreshing) MarkMood.Thinking else MarkMood.Arrived, arrivalKey = card.text) },
            trailing = { if (card.isPreview) PreviewTag() },
        )
        if (card.steerable && !compact && !card.sent && card.sendIn == null) {
            GoalChips(card.goal, enabled = !card.refreshing, onPick = actions::chooseGoal)
        }
        if (card.options.size > 1 && !card.autoInserted && !card.sent) {
            OptionList(card, actions, compact)
        } else AnimatedContent(
            targetState = card.text,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
            label = "suggestion-text",
        ) { text ->
            Text(
                text = text,
                style = VibeType.CardBody,
                color = VibeColors.TextPrimary,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(end = 8.dp, top = 2.dp, bottom = 10.dp)
                    .graphicsLayer { alpha = if (card.refreshing) 0.45f else 1f },
            )
        }
        if (card.note != null) {
            Text(
                card.note,
                style = VibeType.CardQuote,
                color = if (card.note.startsWith("Auto paused")) VibeColors.Context else VibeColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 8.dp, bottom = 8.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                card.sent -> {
                    SmallIcon(VibeIcons.Check, VibeColors.Accent)
                    Spacer(Modifier.width(8.dp))
                    Text("Sent", style = VibeType.CardLabel, color = VibeColors.TextSecondary)
                }
                card.sendIn != null -> {
                    PrimaryButton("Cancel", onClick = actions::cancelSend)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (card.sendIn > 0) "Sending in ${card.sendIn}…" else "Sending…",
                        style = VibeType.CardQuote,
                        color = VibeColors.Accent,
                    )
                }
                card.autoInserted -> {
                    GhostButton("Undo", onClick = actions::undoAutoInsert, icon = VibeIcons.Undo)
                    Spacer(Modifier.width(10.dp))
                    Text("Added to your message", style = VibeType.CardQuote, color = VibeColors.TextTertiary)
                }
                else -> PrimaryButton("Use", onClick = actions::use, enabled = !card.refreshing)
            }
            Spacer(Modifier.weight(1f))
            if (!card.sent && card.sendIn == null) {
                RoundIconButton(VibeIcons.Refresh, "New suggestion", onClick = actions::regenerate, enabled = !card.refreshing)
            }
        }
        if (card.refreshing) ShimmerLine(Modifier.padding(top = 8.dp, end = 8.dp))
    }
}

/**
 * Where to take this reply. One row, scrolls sideways; the picked chip stays
 * lit and sticks for the next messages until tapped again.
 */
@Composable
private fun GoalChips(selected: ReplyGoal?, enabled: Boolean, onPick: (ReplyGoal) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(end = 8.dp, top = 2.dp, bottom = 8.dp),
    ) {
        for (g in ReplyGoal.entries) {
            val on = g == selected
            Text(
                g.label,
                style = VibeType.CardQuote,
                color = if (on) VibeColors.OnAccent else VibeColors.TextSecondary,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (on) VibeColors.Accent else Color.White.copy(alpha = 0.06f))
                    .clickable(enabled = enabled, role = Role.Button) { onPick(g) }
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
    }
}

/**
 * The model's options, one tap to pick. Short enough to read at a glance: two
 * lines each. In landscape there is room for one, so a tap moves to the next.
 */
@Composable
private fun OptionList(card: CardState.Suggestion, actions: CardActions, compact: Boolean) {
    val dim = Modifier.graphicsLayer { alpha = if (card.refreshing) 0.45f else 1f }
    Column(dim.padding(end = 8.dp, top = 2.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (compact) {
            OptionRow(card.text, selected = true) { actions.selectOption((card.selected + 1) % card.options.size) }
            Text(
                "Option ${card.selected + 1} of ${card.options.size} · tap for the next",
                style = VibeType.CardQuote,
                color = VibeColors.TextTertiary,
            )
        } else {
            card.options.forEachIndexed { i, option ->
                OptionRow(option, selected = i == card.selected) { actions.selectOption(i) }
            }
        }
    }
}

@Composable
private fun OptionRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        style = VibeType.CardBody.copy(fontSize = 15.sp, lineHeight = 20.sp),
        color = if (selected) VibeColors.TextPrimary else VibeColors.TextSecondary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) VibeColors.Accent.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.04f))
            .border(1.dp, if (selected) VibeColors.Accent.copy(alpha = 0.45f) else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun ContextContent(card: CardState.ContextNeeded, actions: CardActions, compact: Boolean) {
    Column {
        CardHeader(
            label = card.signal.bellLabel,
            onDismiss = actions::dismiss,
            icon = { SmallIcon(VibeIcons.Bell, VibeColors.Context) },
        )
        if (!card.expanded) {
            Text(card.signal.headline, style = VibeType.CardBody, color = VibeColors.TextPrimary)
            Quote(card.signal.quote, Modifier.padding(top = 2.dp, bottom = 10.dp, end = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton("Explain", onClick = actions::explain)
                Spacer(Modifier.width(12.dp))
                Text("Vibe won't guess", style = VibeType.CardQuote, color = VibeColors.TextTertiary)
            }
        } else {
            if (!compact) {
                Text(
                    card.signal.question,
                    style = VibeType.CardBody,
                    color = VibeColors.TextPrimary,
                    modifier = Modifier.padding(end = 8.dp, bottom = 8.dp),
                )
            }
            ContextField(card.draft, Modifier.padding(end = 8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                RememberToggle(card.remember, card.rememberLabel, actions::toggleRemember)
                Spacer(Modifier.weight(1f))
                PrimaryButton("Continue", onClick = actions::submitContext, enabled = card.draft.isNotBlank())
                Spacer(Modifier.width(8.dp))
            }
        }
    }
}

@Composable
private fun EndingContent(card: CardState.Ending, actions: CardActions) {
    Column {
        CardHeader(
            label = when {
                card.isNight -> "It's getting late"
                card.slowing -> "Slowing down"
                else -> "Winding down"
            },
            onDismiss = actions::dismiss,
            icon = { SmallIcon(VibeIcons.Moon, VibeColors.Night) },
        )
        Text(
            if (card.slowing) "Their replies are getting shorter." else "Conversation slowing down.",
            style = VibeType.CardBody,
            color = VibeColors.TextPrimary,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton("Continue", onClick = actions::continueConversation)
            GhostButton(if (card.isNight) "Goodnight" else "Wrap up", onClick = actions::wrapUp)
            GhostButton("Wait", onClick = actions::waitQuietly)
        }
    }
}

@Composable
private fun PictureContent(card: CardState.PictureRequest, actions: CardActions, compact: Boolean) {
    Column {
        CardHeader(
            label = "Picture request",
            onDismiss = actions::dismiss,
            icon = { SmallIcon(VibeIcons.Camera, VibeColors.Picture) },
        )
        Quote(card.quote, Modifier.padding(end = 8.dp))
        if (!compact) {
            Text(
                "Vibe never sends photos. Want a playful reply?",
                style = VibeType.CardQuote,
                color = VibeColors.TextSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        PrimaryButton("Reply", onClick = actions::replyToPicture, modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun BoundaryContent(card: CardState.Boundary, actions: CardActions, compact: Boolean) {
    Column {
        CardHeader(
            label = "They asked for space",
            onDismiss = actions::dismiss,
            icon = { SmallIcon(VibeIcons.Pause, VibeColors.Boundary) },
        )
        Quote(card.quote, Modifier.padding(end = 8.dp))
        if (!compact) {
            Text(
                "Vibe won't help keep this going. It can help you step back.",
                style = VibeType.CardQuote,
                color = VibeColors.TextSecondary,
                modifier = Modifier.padding(top = 4.dp, end = 8.dp),
            )
        }
        GhostButton("Reply respectfully", onClick = actions::replyToBoundary, modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun ThinkingContent(card: CardState.Thinking) {
    Column(Modifier.padding(end = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 36.dp)) {
            VibeMark(mood = MarkMood.Thinking, size = 16.dp)
            Spacer(Modifier.width(10.dp))
            Text(card.label, style = VibeType.CardLabel, color = VibeColors.TextSecondary)
        }
        ShimmerLine(Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun NoticeContent(card: CardState.Notice, actions: CardActions) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .heightIn(min = 36.dp)
            .clickable(onClick = actions::dismiss, indication = null, interactionSource = null),
    ) {
        VibeMark(size = 14.dp)
        Spacer(Modifier.width(10.dp))
        Text(card.text, style = VibeType.CardLabel, color = VibeColors.TextSecondary)
    }
}

@Composable
private fun FailedContent(card: CardState.Failed, actions: CardActions) {
    Column {
        CardHeader(
            label = card.title,
            onDismiss = actions::dismiss,
            icon = { VibeMark(tint = VibeColors.TextTertiary) },
        )
        Text(card.message, style = VibeType.CardQuote, color = VibeColors.TextSecondary)
        GhostButton("Try again", onClick = actions::retry, modifier = Modifier.padding(top = 10.dp))
    }
}

// --- Building blocks --------------------------------------------------------

@Composable
private fun CardSurface(content: @Composable () -> Unit) {
    Box(
        Modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .shadow(
                elevation = 18.dp,
                shape = CardShape,
                ambientColor = VibeColors.AccentDeep.copy(alpha = 0.35f),
                spotColor = Color.Black,
            )
            .clip(CardShape)
            .background(Brush.verticalGradient(listOf(VibeColors.CardTop, VibeColors.CardBottom)))
            .drawBehind {
                // One soft violet bloom in the corner, behind the mark. That is the whole glow.
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(VibeColors.AccentGlow, Color.Transparent),
                        center = Offset(size.width * 0.08f, 0f),
                        radius = size.width * 0.55f,
                    ),
                    radius = size.width * 0.55f,
                    center = Offset(size.width * 0.08f, 0f),
                )
            }
            .border(1.dp, VibeColors.CardBorder, CardShape)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 12.dp),
    ) {
        content()
    }
}

/** Horizontal swipe dismisses; a short swipe springs back. */
@Composable
private fun SwipeToDismiss(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val offset = remember { Animatable(0f) }
    var width by remember { mutableStateOf(1f) }
    val scope = rememberCoroutineScope()
    Box(
        Modifier
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .graphicsLayer {
                translationX = offset.value
                alpha = 1f - (abs(offset.value) / width).coerceIn(0f, 1f) * 0.9f
            }
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta -> scope.launch { offset.snapTo(offset.value + delta) } },
                onDragStopped = { velocity ->
                    val flung = abs(velocity) > 1800f
                    if (abs(offset.value) > width * 0.33f || flung) {
                        val direction = if (offset.value + velocity / 10 >= 0) 1 else -1
                        offset.animateTo(direction * width, tween(160))
                        onDismiss()
                    } else {
                        offset.animateTo(0f, spring(dampingRatio = 0.7f))
                    }
                },
            ),
    ) {
        content()
    }
}

@Composable
internal fun CardHeader(
    label: String,
    onDismiss: () -> Unit,
    icon: @Composable () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        icon()
        Spacer(Modifier.width(8.dp))
        Text("Vibe", style = VibeType.CardBrand, color = VibeColors.TextPrimary)
        Text(
            "  ·  $label",
            style = VibeType.CardLabel,
            color = VibeColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.weight(1f))
        trailing()
        RoundIconButton(VibeIcons.Close, "Dismiss", onClick = onDismiss, iconSize = 16.dp, tint = VibeColors.TextTertiary)
    }
}

@Composable
internal fun SmallIcon(icon: ImageVector, tint: Color) =
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))

@Composable
internal fun Quote(text: String, modifier: Modifier = Modifier) {
    Text(
        "“${text.trim()}”",
        style = VibeType.CardQuote,
        color = VibeColors.TextTertiary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** Mock output is labelled wherever it appears. */
@Composable
internal fun PreviewTag() {
    Text(
        "PREVIEW",
        style = VibeType.Tag,
        color = VibeColors.TextTertiary,
        modifier = Modifier
            .padding(end = 2.dp)
            .border(1.dp, VibeColors.CardBorder, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * The context box. Keys typed while it is open land here, not in the chat --
 * it is drawn rather than a real text field, because inside a keyboard a real
 * field would ask for a keyboard.
 */
@Composable
internal fun ContextField(draft: String, modifier: Modifier = Modifier, placeholder: String = "Type context…") {
    val caret = rememberInfiniteTransition(label = "caret")
    val caretAlpha by caret.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(530, easing = LinearEasing), RepeatMode.Reverse),
        label = "caret-alpha",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(VibeColors.Field)
            .border(1.dp, VibeColors.Accent.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (draft.isEmpty()) {
            Box(
                Modifier
                    .width(1.5.dp)
                    .height(18.dp)
                    .graphicsLayer { alpha = caretAlpha }
                    .background(VibeColors.Accent),
            )
            Spacer(Modifier.width(4.dp))
            Text(placeholder, style = VibeType.CardQuote, color = VibeColors.TextTertiary)
        } else {
            Text(
                draft,
                style = VibeType.CardQuote.copy(color = VibeColors.TextPrimary),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Box(
                Modifier
                    .padding(start = 1.dp)
                    .width(1.5.dp)
                    .height(18.dp)
                    .graphicsLayer { alpha = caretAlpha }
                    .background(VibeColors.Accent),
            )
        }
    }
}

@Composable
internal fun RememberToggle(checked: Boolean, label: String, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Checkbox, onClick = onToggle)
            .heightIn(min = 40.dp)
            .padding(end = 8.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (checked) VibeColors.Accent else Color.Transparent)
                .border(1.5.dp, if (checked) VibeColors.Accent else VibeColors.TextTertiary, RoundedCornerShape(5.dp)),
        ) {
            if (checked) Icon(VibeIcons.Check, null, tint = VibeColors.OnAccent, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(label, style = VibeType.CardQuote, color = VibeColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun ShimmerLine(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(
        initialValue = -0.4f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "shimmer-x",
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(2.dp)
            .clip(CircleShape)
            .drawBehind {
                drawRect(VibeColors.Accent.copy(alpha = 0.10f))
                drawRect(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, VibeColors.Accent.copy(alpha = 0.9f), Color.Transparent),
                        startX = size.width * (x - 0.3f),
                        endX = size.width * (x + 0.3f),
                    ),
                )
            },
    )
}

/** Shrinks slightly while pressed: the only "bounce" Vibe allows itself. */
@Composable
internal fun Modifier.pressScale(source: MutableInteractionSource): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, spring(stiffness = Spring.StiffnessHigh), label = "press")
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

@Composable
internal fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val source = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .pressScale(source)
            .heightIn(min = 38.dp)
            .clip(CircleShape)
            .background(
                if (enabled) Brush.horizontalGradient(listOf(VibeColors.Accent, VibeColors.AccentStrong))
                else Brush.horizontalGradient(listOf(VibeColors.Key, VibeColors.Key)),
            )
            .clickable(interactionSource = source, indication = ripple(), enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Text(text, style = VibeType.Button, color = if (enabled) VibeColors.OnAccent else VibeColors.TextTertiary)
    }
}

@Composable
internal fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val source = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .pressScale(source)
            .heightIn(min = 38.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.07f))
            .clickable(interactionSource = source, indication = ripple(), role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (icon != null) {
            Icon(icon, null, tint = VibeColors.TextPrimary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = VibeType.Button, color = VibeColors.TextPrimary)
    }
}

@Composable
internal fun RoundIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    iconSize: androidx.compose.ui.unit.Dp = 20.dp,
    tint: Color = VibeColors.TextSecondary,
) {
    val source = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .pressScale(source)
            .size(40.dp)
            .clip(CircleShape)
            .clickable(interactionSource = source, indication = ripple(), enabled = enabled, role = Role.Button, onClick = onClick),
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (enabled) tint else tint.copy(alpha = 0.35f),
            modifier = Modifier.size(iconSize),
        )
    }
}
