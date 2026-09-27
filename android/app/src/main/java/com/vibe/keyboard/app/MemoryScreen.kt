package com.vibe.keyboard.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibe.keyboard.context.Platform
import com.vibe.keyboard.memory.ConversationRecord
import com.vibe.keyboard.memory.MemoryEdits
import com.vibe.keyboard.memory.MemoryItem
import com.vibe.keyboard.memory.MemorySource
import com.vibe.keyboard.memory.UserStyleLearner
import com.vibe.keyboard.memory.UserStyleProfile
import com.vibe.keyboard.memory.VibeData
import com.vibe.keyboard.ui.theme.VibeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Every chat Vibe holds memory for, what it holds, and the controls to take
 * any of it back. Each chat is its own box; nothing here merges two of them.
 */
@Composable
fun MemoryScreen(onBack: () -> Unit, onImport: () -> Unit) {
    val context = LocalContext.current
    val store = remember { VibeData.store(context) }
    val scope = rememberCoroutineScope()
    var chats by remember { mutableStateOf<List<ConversationRecord>>(emptyList()) }
    var style by remember { mutableStateOf(UserStyleProfile()) }
    var open by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) }

    fun reload() = scope.launch {
        val (c, s) = withContext(Dispatchers.IO) { store.list() to store.loadStyle() }
        chats = c
        style = s
    }

    fun change(block: () -> Unit) = scope.launch {
        withContext(Dispatchers.IO) { block() }
        confirm = null
        reload()
    }
    LaunchedEffect(Unit) { reload() }

    Box(Modifier.fillMaxSize().background(VibeColors.Background), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .safeDrawingPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BackRow("Memory", onBack)
            Text(
                "Stored on this phone only. Each chat's memory is used for that chat and no other.",
                fontSize = 13.sp, lineHeight = 18.sp, color = VibeColors.TextSecondary,
            )
            if (chats.isEmpty()) {
                Section("No chats yet") {
                    Text(
                        "Import a WhatsApp chat, or pick a chat from the keyboard's ✦ panel and tap Scan.",
                        fontSize = 14.sp, lineHeight = 19.sp, color = VibeColors.TextSecondary,
                    )
                    PillButton("Import a chat", filled = true, modifier = Modifier.padding(top = 10.dp), onClick = onImport)
                }
            }
            for (chat in chats) {
                val isOpen = open == chat.id
                Section("${chat.contactName} · ${Platform.fromId(chat.platformId).label}") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { open = if (isOpen) null else chat.id },
                    ) {
                        Text(
                            "${chat.messages.size} messages · ${chat.memories.size} memories",
                            fontSize = 15.sp, color = VibeColors.TextPrimary, modifier = Modifier.weight(1f),
                        )
                        Text(if (isOpen) "Hide" else "Show", fontSize = 14.sp, color = VibeColors.Accent)
                    }
                    if (isOpen) {
                        ChatDetail(chat) { item -> change { store.save(MemoryEdits.forget(chat, item.id)) } }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 12.dp)) {
                            if (confirm == chat.id) {
                                PillButton("Delete ${chat.contactName}", filled = true) { change { store.delete(chat.id) } }
                                PillButton("Keep", filled = false) { confirm = null }
                            } else {
                                PillButton("Clear memory", filled = false) { change { store.save(MemoryEdits.clear(chat)) } }
                                PillButton("Delete chat", filled = false) { confirm = chat.id }
                            }
                        }
                    }
                }
            }
            if (style.learnedFrom > 0) {
                Section("Your style") {
                    Text(UserStyleLearner.describe(style).joinToString(" · "), fontSize = 14.sp, lineHeight = 19.sp, color = VibeColors.TextSecondary)
                    Text(
                        "Learned from ${style.learnedFrom} of your messages.",
                        fontSize = 12.sp, color = VibeColors.TextTertiary, modifier = Modifier.padding(top = 4.dp),
                    )
                    PillButton("Reset my style", filled = false, modifier = Modifier.padding(top = 10.dp)) {
                        change { store.saveStyle(UserStyleProfile()) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatDetail(chat: ConversationRecord, onForget: (MemoryItem) -> Unit) {
    val s = chat.summary
    Column(Modifier.padding(top = 8.dp)) {
        s?.theirStyle?.let { Line("Style", it.traits().joinToString(" · ")) }
        s?.topics?.takeIf { it.isNotEmpty() }?.let { Line("Topics", it.joinToString(", ")) }
        s?.people?.takeIf { it.isNotEmpty() }?.let { Line("People", it.joinToString(", ")) }
        s?.places?.takeIf { it.isNotEmpty() }?.let { Line("Places", it.joinToString(", ")) }
        if (chat.memories.isEmpty()) {
            Text("No memories.", fontSize = 13.sp, color = VibeColors.TextTertiary, modifier = Modifier.padding(top = 8.dp))
        }
        for (m in chat.memories.sortedBy { it.source != MemorySource.USER }) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text(
                    if (m.source == MemorySource.USER) "YOU" else m.kind.name,
                    fontSize = 10.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.SemiBold,
                    color = if (m.source == MemorySource.USER) VibeColors.Accent else VibeColors.TextTertiary,
                    modifier = Modifier.widthIn(min = 64.dp),
                )
                Text(m.value, fontSize = 13.sp, lineHeight = 18.sp, color = VibeColors.TextSecondary, modifier = Modifier.weight(1f))
                Text(
                    "Forget",
                    fontSize = 13.sp,
                    color = VibeColors.TextTertiary,
                    modifier = Modifier.clip(CircleShape).clickable(role = Role.Button) { onForget(m) }.padding(8.dp),
                )
            }
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.padding(top = 4.dp)) {
        Text(label, fontSize = 13.sp, color = VibeColors.TextTertiary, modifier = Modifier.widthIn(min = 64.dp))
        Text(value, fontSize = 13.sp, lineHeight = 18.sp, color = VibeColors.TextSecondary)
    }
}
