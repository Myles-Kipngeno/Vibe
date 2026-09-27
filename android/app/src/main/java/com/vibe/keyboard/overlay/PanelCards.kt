package com.vibe.keyboard.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vibe.keyboard.auto.ReplyMode
import com.vibe.keyboard.context.ScanState
import com.vibe.keyboard.memory.MemoryItem
import com.vibe.keyboard.memory.MemoryKind
import com.vibe.keyboard.memory.MemorySource
import com.vibe.keyboard.ui.VibeIcons
import com.vibe.keyboard.ui.VibeMark
import com.vibe.keyboard.ui.theme.VibeColors
import com.vibe.keyboard.ui.theme.VibeType

// --- The ✦ panel --------------------------------------------------------------

@Composable
internal fun PanelContent(card: CardState.Panel, actions: CardActions, compact: Boolean) {
    val s = card.status
    Column {
        CardHeader(
            label = s.conversationName?.let { "$it · ${s.platformLabel}" } ?: s.platformLabel.ifEmpty { "Controls" },
            onDismiss = actions::dismiss,
            icon = { VibeMark(size = 16.dp) },
        )
        StatusLine(s, Modifier.padding(bottom = 10.dp, end = 8.dp))
        if (compact) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("Scan", onClick = actions::scan)
                GhostButton(if (s.conversationName == null) "Pick chat" else "Change", onClick = actions::chooseConversation)
                Spacer(Modifier.weight(1f))
                ModeSwitch(s.mode, actions::setMode)
            }
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Scan conversation", onClick = actions::scan)
            GhostButton(if (s.conversationName == null) "Pick a chat" else "Change chat", onClick = actions::chooseConversation)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp, end = 8.dp)) {
            Text("Reply mode", style = VibeType.CardQuote, color = VibeColors.TextSecondary)
            Spacer(Modifier.weight(1f))
            ModeSwitch(s.mode, actions::setMode)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 10.dp),
        ) {
            if (s.conversationId != null) GhostButton("Memory", onClick = actions::openMemory)
            if (s.importSupported) GhostButton("Import", onClick = actions::openImport)
            GhostButton("Settings", onClick = actions::openSettings, icon = VibeIcons.Tune)
        }
    }
}

@Composable
private fun StatusLine(s: VibeStatus, modifier: Modifier = Modifier) {
    val (text, color) = when {
        s.withoutContext && s.conversationId == null -> "Without context · nothing is saved" to VibeColors.TextTertiary
        else -> when (s.scanState) {
            ScanState.CONTEXT_READY -> "● Context ready" to VibeColors.Accent
            ScanState.CONTEXT_STALE -> "◐ May be out of date" to VibeColors.Context
            ScanState.SCANNING -> "Scanning…" to VibeColors.TextSecondary
            ScanState.IMPORT_REQUIRED -> "⚠ Context needed" to VibeColors.Context
            ScanState.CONTEXT_UNAVAILABLE -> "⚠ Can't read chats here" to VibeColors.Context
            ScanState.ERROR -> "⚠ Memory unavailable" to VibeColors.Boundary
            ScanState.NOT_SCANNED ->
                (if (s.conversationId == null) "○ No chat picked · memory off" else "○ Not scanned yet") to VibeColors.TextTertiary
        }
    }
    Column(modifier) {
        Text(text, style = VibeType.CardLabel, color = color)
        if (s.conversationId != null) {
            Text(
                "${s.messageCount} messages · ${s.memoryCount} memories",
                style = VibeType.CardQuote,
                color = VibeColors.TextTertiary,
            )
        }
    }
}

/** Suggest | Auto. Auto is never switched on without its explanation card first. */
@Composable
private fun ModeSwitch(mode: ReplyMode, onChange: (ReplyMode) -> Unit) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.06f))
            .padding(3.dp),
    ) {
        for (m in ReplyMode.entries) {
            val selected = m == mode
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (selected) VibeColors.Accent else Color.Transparent)
                    .clickable(role = Role.RadioButton) { onChange(m) }
                    .heightIn(min = 32.dp)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    if (m == ReplyMode.SUGGEST) "Suggest" else "Auto",
                    style = VibeType.Button,
                    color = if (selected) VibeColors.OnAccent else VibeColors.TextSecondary,
                )
            }
        }
    }
}

// --- Which chat is this? --------------------------------------------------------

@Composable
internal fun ChooseContent(card: CardState.ChooseConversation, actions: CardActions, compact: Boolean) {
    Column {
        CardHeader(label = "Which chat is this?", onDismiss = actions::openPanel, icon = { VibeMark(size = 16.dp) })
        if (!compact) {
            Text(
                "Keyboards can't see who you're talking to, so you pick. Memory stays with that chat only.",
                style = VibeType.CardQuote,
                color = VibeColors.TextTertiary,
                modifier = Modifier.padding(end = 8.dp, bottom = 6.dp),
            )
        }
        Column(
            Modifier
                .heightIn(max = if (compact) 72.dp else 132.dp)
                .verticalScroll(rememberScrollState())
                .padding(end = 8.dp),
        ) {
            if (card.options.isEmpty()) {
                Text("No chats yet.", style = VibeType.CardQuote, color = VibeColors.TextTertiary, modifier = Modifier.padding(vertical = 8.dp))
            }
            for (o in card.options) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (o.active) VibeColors.Accent.copy(alpha = 0.10f) else Color.Transparent)
                        .clickable(role = Role.Button) { actions.selectConversation(o.id) }
                        .heightIn(min = 40.dp)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(o.name, style = VibeType.CardLabel, color = VibeColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${o.platformLabel} · ${o.messages} messages · ${o.memories} memories",
                            style = VibeType.CardQuote,
                            color = if (o.otherApp) VibeColors.TextTertiary.copy(alpha = 0.7f) else VibeColors.TextTertiary,
                            maxLines = 1,
                        )
                    }
                    if (o.active) SmallIcon(VibeIcons.Check, VibeColors.Accent)
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            PrimaryButton("New chat", onClick = actions::newConversation)
            GhostButton("Without context", onClick = actions::continueWithoutContext)
        }
    }
}

@Composable
internal fun NameContent(card: CardState.NameConversation, actions: CardActions) {
    Column {
        CardHeader(label = "New chat", onDismiss = actions::chooseConversation, icon = { VibeMark(size = 16.dp) })
        ContextField(card.draft, Modifier.padding(end = 8.dp), placeholder = "Their name…")
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Text(
                "Just a label for this chat's memory",
                style = VibeType.CardQuote,
                color = VibeColors.TextTertiary,
                modifier = Modifier.weight(1f),
            )
            PrimaryButton("Save", onClick = actions::submitContext, enabled = card.draft.isNotBlank())
            Spacer(Modifier.width(8.dp))
        }
    }
}

// --- When Vibe can't see the conversation --------------------------------------------

@Composable
internal fun UnavailableContent(card: CardState.ContextUnavailable, actions: CardActions, compact: Boolean) {
    Column {
        CardHeader(
            label = "Context needed",
            onDismiss = actions::dismiss,
            icon = { SmallIcon(VibeIcons.Bell, VibeColors.Context) },
        )
        Text(card.reason, style = VibeType.CardBody, color = VibeColors.TextPrimary, modifier = Modifier.padding(end = 8.dp))
        if (!compact) {
            Text(
                card.importHint?.let { "Import: $it" } ?: "Copy their recent messages, then Scan again.",
                style = VibeType.CardQuote,
                color = VibeColors.TextTertiary,
                modifier = Modifier.padding(top = 4.dp, end = 8.dp),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 10.dp),
        ) {
            if (card.importSupported) PrimaryButton("Import chat", onClick = actions::openImport)
            GhostButton("Continue without", onClick = actions::continueWithoutContext)
            if (!card.importSupported) GhostButton("Pick a chat", onClick = actions::chooseConversation)
        }
    }
}

// --- Memory ---------------------------------------------------------------------

@Composable
internal fun MemoryContent(card: CardState.MemoryView, actions: CardActions, compact: Boolean) {
    Column {
        CardHeader(label = "${card.conversationName} · memory", onDismiss = actions::openPanel, icon = { VibeMark(size = 16.dp) })
        Text(
            "${card.messageCount} messages · ${card.items.size} memories · used only in this chat",
            style = VibeType.CardQuote,
            color = VibeColors.TextTertiary,
            modifier = Modifier.padding(bottom = 6.dp, end = 8.dp),
        )
        Column(
            Modifier
                .heightIn(max = if (compact) 64.dp else 128.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (card.items.isEmpty()) {
                Text(
                    "Nothing yet. Scan, import, or answer when Vibe asks.",
                    style = VibeType.CardQuote,
                    color = VibeColors.TextSecondary,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
            for (item in card.items) MemoryRow(item, actions)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            if (card.confirmClear) {
                Text("Clear all of it?", style = VibeType.CardQuote, color = VibeColors.Boundary)
                GhostButton("Clear", onClick = actions::confirmClearMemory)
                GhostButton("Keep", onClick = actions::openMemory)
            } else if (card.items.isNotEmpty() || card.messageCount > 0) {
                GhostButton("Clear chat memory", onClick = actions::askClearMemory)
            }
        }
    }
}

@Composable
private fun MemoryRow(item: MemoryItem, actions: CardActions) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button) { actions.editMemory(item.id) }
            .padding(start = 2.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Text(
            kindLabel(item),
            style = VibeType.Tag,
            color = if (item.source == MemorySource.USER) VibeColors.Accent else VibeColors.TextTertiary,
            modifier = Modifier.width(52.dp),
        )
        Text(
            item.value,
            style = VibeType.CardQuote,
            color = VibeColors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        RoundIconButton(VibeIcons.Close, "Forget", onClick = { actions.forgetMemory(item.id) }, iconSize = 14.dp, tint = VibeColors.TextTertiary)
    }
}

private fun kindLabel(item: MemoryItem) = when {
    item.source == MemorySource.USER -> "YOU SAID"
    item.kind == MemoryKind.PERSON -> "PERSON"
    item.kind == MemoryKind.PLACE -> "PLACE"
    item.kind == MemoryKind.EVENT -> "SHARED"
    item.kind == MemoryKind.TOPIC -> "TOPIC"
    else -> "NOTE"
}

@Composable
internal fun EditMemoryContent(card: CardState.EditMemory, actions: CardActions) {
    Column {
        CardHeader(label = "Edit memory", onDismiss = actions::openMemory, icon = { VibeMark(size = 16.dp) })
        ContextField(card.draft, Modifier.padding(end = 8.dp), placeholder = "What should Vibe know?")
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            PrimaryButton("Save", onClick = actions::submitContext)
            GhostButton("Forget", onClick = { actions.forgetMemory(card.item.id) })
        }
    }
}

// --- Auto, explained before it is on --------------------------------------------------

@Composable
internal fun AutoIntroContent(card: CardState.AutoIntro, actions: CardActions, compact: Boolean) {
    Column {
        CardHeader(label = "Auto mode", onDismiss = actions::openPanel, icon = { VibeMark(size = 16.dp) })
        Text(
            if (card.canSend && card.rules.sendWhereSupported) {
                "Here, Vibe can press ${card.platformLabel}'s Send for you after a ${card.rules.sendDelaySeconds}s countdown you can cancel."
            } else {
                "${card.platformLabel} gives keyboards no Send action here, so Auto fills the box and you press Send."
            },
            style = VibeType.CardQuote,
            color = VibeColors.TextPrimary,
            modifier = Modifier.padding(end = 8.dp),
        )
        if (!compact) {
            Text(
                "Never automatic: photos, private details, missing context, boundaries, anything Vibe isn't sure of.",
                style = VibeType.CardQuote,
                color = VibeColors.TextTertiary,
                modifier = Modifier.padding(top = 4.dp, end = 8.dp),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 10.dp),
        ) {
            PrimaryButton("Turn on Auto", onClick = actions::confirmAuto)
            GhostButton("Not now", onClick = actions::openPanel)
        }
    }
}

// --- Something Vibe won't handle ------------------------------------------------------------

@Composable
internal fun AttentionContent(card: CardState.Attention, actions: CardActions, compact: Boolean) {
    Column {
        CardHeader(
            label = "Needs your attention",
            onDismiss = actions::dismiss,
            icon = { SmallIcon(VibeIcons.Pause, VibeColors.Boundary) },
        )
        Text(card.title, style = VibeType.CardBody, color = VibeColors.TextPrimary)
        Quote(card.quote, Modifier.padding(end = 8.dp))
        if (!compact) {
            Text(
                card.detail,
                style = VibeType.CardQuote,
                color = VibeColors.TextSecondary,
                modifier = Modifier.padding(top = 4.dp, end = 8.dp),
            )
        }
        GhostButton("Got it", onClick = actions::dismiss, modifier = Modifier.padding(top = 10.dp))
    }
}
