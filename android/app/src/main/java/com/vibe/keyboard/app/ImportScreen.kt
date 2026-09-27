package com.vibe.keyboard.app

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibe.keyboard.context.Platform
import com.vibe.keyboard.memory.ChatExportParser
import com.vibe.keyboard.memory.ConversationImporter
import com.vibe.keyboard.memory.ConversationRecord
import com.vibe.keyboard.memory.UserStyleLearner
import com.vibe.keyboard.memory.VibeData
import com.vibe.keyboard.ui.theme.VibeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.text.DateFormat
import java.util.Date
import java.util.zip.ZipInputStream

/** What another app handed Vibe through the share sheet. */
data class SharedChat(val text: String?, val uri: Uri?, val subject: String?)

private sealed interface ImportState {
    data object Idle : ImportState
    data object Reading : ImportState
    data class Parsed(val parsed: ChatExportParser.Parsed, val chatName: String?) : ImportState
    data class Saved(val record: ConversationRecord) : ImportState
    data class Error(val message: String) : ImportState
}

/**
 * Importing a chat. The only way in is the user exporting it from WhatsApp
 * (or picking the exported file), and nothing is saved until they confirm who
 * is who. It stays on this phone.
 */
@Composable
fun ImportScreen(shared: SharedChat?, onDone: () -> Unit, onOpenMemory: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<ImportState>(ImportState.Idle) }

    fun load(text: String?, uri: Uri?, subject: String?) {
        state = ImportState.Reading
        scope.launch {
            state = try {
                val raw = text ?: uri?.let { readExport(context, it) } ?: ""
                val parsed = withContext(Dispatchers.Default) { ChatExportParser.parse(raw) }
                if (parsed.isEmpty) {
                    ImportState.Error("That doesn't look like a WhatsApp chat export. In WhatsApp: open the chat → ⋮ → More → Export chat.")
                } else {
                    ImportState.Parsed(parsed, ChatExportParser.chatNameFrom(subject ?: uri?.lastPathSegment))
                }
            } catch (e: Exception) {
                ImportState.Error("Couldn't read that file.")
            }
        }
    }

    LaunchedEffect(shared) {
        if (shared != null) load(shared.text, shared.uri, shared.subject)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) load(null, uri, null)
    }

    Box(Modifier.fillMaxSize().background(VibeColors.Background), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .safeDrawingPadding()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BackRow("Import a chat", onDone)
            when (val s = state) {
                ImportState.Idle, is ImportState.Error -> {
                    Section("How") {
                        Step("1", "In WhatsApp, open the chat.")
                        Step("2", "Tap ⋮ → More → Export chat → Without media.")
                        Step("3", "Choose Vibe in the share sheet. Or save the file, then pick it below.")
                        PillButton("Choose exported file", filled = true, modifier = Modifier.padding(top = 10.dp)) {
                            picker.launch(arrayOf("text/plain", "application/zip", "application/octet-stream"))
                        }
                    }
                    if (s is ImportState.Error) Text(s.message, fontSize = 14.sp, color = VibeColors.Boundary)
                    Section("What happens") {
                        PrivacyLine("Vibe reads the file on this phone. Nothing is uploaded; this version has no internet access.")
                        PrivacyLine("It keeps the last 1,500 messages, a summary, and memories you can see, edit and delete.")
                        PrivacyLine("It learns your texting style from your messages only.")
                        PrivacyLine("Instagram, Snapchat and most other apps have no chat export, so there you copy messages and tap Scan.")
                    }
                }
                ImportState.Reading -> Text("Reading…", fontSize = 15.sp, color = VibeColors.TextSecondary)
                is ImportState.Parsed -> ConfirmImport(s) { me, name ->
                    scope.launch {
                        state = try {
                            val record = withContext(Dispatchers.IO) {
                                ConversationImporter.import(
                                    VibeData.store(context), s.parsed, me, name, "whatsapp", System.currentTimeMillis(),
                                )
                            }
                            ImportState.Saved(record)
                        } catch (e: Exception) {
                            ImportState.Error("Couldn't save it. Nothing was changed.")
                        }
                    }
                }
                is ImportState.Saved -> Imported(s.record, onDone, onOpenMemory)
            }
        }
    }
}

@Composable
private fun ConfirmImport(s: ImportState.Parsed, onImport: (me: String, name: String) -> Unit) {
    val guessMe = ChatExportParser.guessMe(s.parsed, s.chatName)
    var me by remember { mutableStateOf(guessMe) }
    var name by remember(me) {
        mutableStateOf(s.chatName ?: s.parsed.senders.firstOrNull { it != me }.orEmpty())
    }
    val lines = s.parsed.lines
    val first = lines.firstNotNullOfOrNull { it.sentAt }
    val last = lines.lastOrNull { it.sentAt != null }?.sentAt

    Section("Found") {
        Text("${lines.size} messages", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = VibeColors.TextPrimary)
        if (first != null && last != null) {
            val f = DateFormat.getDateInstance(DateFormat.MEDIUM)
            Text("${f.format(Date(first))} – ${f.format(Date(last))}", fontSize = 13.sp, color = VibeColors.TextSecondary)
        }
    }
    Section("Which one is you?") {
        Text(
            "WhatsApp exports use profile names, so Vibe can't tell. Your messages teach it your style; theirs never do.",
            fontSize = 13.sp, lineHeight = 18.sp, color = VibeColors.TextSecondary,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        for (sender in s.parsed.senders.take(8)) {
            Choice(sender, selected = sender == me) { me = sender }
        }
    }
    Section("Save as") {
        BasicTextField(
            value = name,
            onValueChange = { name = it.take(40) },
            singleLine = true,
            textStyle = TextStyle(fontSize = 16.sp, color = VibeColors.TextPrimary),
            cursorBrush = SolidColor(VibeColors.Accent),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(VibeColors.Field)
                .border(1.dp, VibeColors.CardBorder, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        )
        Text("WhatsApp · a chat with this name is updated, not duplicated", fontSize = 12.sp, color = VibeColors.TextTertiary, modifier = Modifier.padding(top = 6.dp))
    }
    val chosen = me
    PillButton("Import and analyse", filled = true, enabled = chosen != null && name.isNotBlank()) {
        if (chosen != null) onImport(chosen, name)
    }
}

@Composable
private fun Imported(record: ConversationRecord, onDone: () -> Unit, onOpenMemory: () -> Unit) {
    val summary = record.summary
    Section("Saved · ${record.contactName} · ${Platform.fromId(record.platformId).label}") {
        Text("${record.messages.size} messages · ${record.memories.size} memories", fontSize = 15.sp, color = VibeColors.TextPrimary)
        summary?.theirStyle?.let { Fact("Their style", it.traits().joinToString(" · ")) }
        summary?.topics?.takeIf { it.isNotEmpty() }?.let { Fact("Keeps coming up", it.joinToString(", ")) }
        summary?.people?.takeIf { it.isNotEmpty() }?.let { Fact("People mentioned", it.joinToString(", ")) }
        summary?.places?.takeIf { it.isNotEmpty() }?.let { Fact("Places", it.joinToString(", ")) }
        summary?.sharedReferences?.firstOrNull()?.let { Fact("Shared reference", "“$it”") }
    }
    val style = VibeData.store(LocalContext.current).loadStyle()
    if (style.learnedFrom > 0) {
        Section("Your style") {
            Text(UserStyleLearner.describe(style).joinToString(" · "), fontSize = 14.sp, lineHeight = 19.sp, color = VibeColors.TextSecondary)
        }
    }
    Text(
        "In ${Platform.fromId(record.platformId).label}, tap ✦ on the keyboard → Pick a chat → ${record.contactName}.",
        fontSize = 13.sp, lineHeight = 18.sp, color = VibeColors.TextTertiary,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PillButton("Done", filled = true, onClick = onDone)
        PillButton("See memory", filled = false, onClick = onOpenMemory)
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Column(Modifier.padding(top = 10.dp)) {
        Text(label.uppercase(), fontSize = 10.sp, letterSpacing = 0.8.sp, color = VibeColors.TextTertiary)
        Text(value, fontSize = 14.sp, lineHeight = 19.sp, color = VibeColors.TextSecondary)
    }
}

@Composable
private fun Step(n: String, text: String) {
    Row(Modifier.padding(vertical = 4.dp)) {
        Text(n, fontSize = 14.sp, color = VibeColors.Accent, modifier = Modifier.widthIn(min = 20.dp))
        Text(text, fontSize = 14.sp, lineHeight = 19.sp, color = VibeColors.TextSecondary)
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) VibeColors.Accent.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        Box(
            Modifier
                .padding(end = 12.dp)
                .clip(CircleShape)
                .background(if (selected) VibeColors.Accent else Color.Transparent)
                .border(1.5.dp, if (selected) VibeColors.Accent else VibeColors.TextTertiary, CircleShape)
                .padding(6.dp),
        )
        Text(label, fontSize = 15.sp, color = VibeColors.TextPrimary)
    }
}

@Composable
internal fun BackRow(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Text(
            "‹",
            fontSize = 28.sp,
            color = VibeColors.Accent,
            modifier = Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onBack).padding(horizontal = 10.dp),
        )
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = VibeColors.TextPrimary)
    }
}

private const val MAX_EXPORT_BYTES = 8 * 1024 * 1024

/** A WhatsApp export arrives as a .txt, or a .zip holding one. Either way, the text inside. */
private suspend fun readExport(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    val stream = context.contentResolver.openInputStream(uri) ?: return@withContext ""
    BufferedInputStream(stream).use { input ->
        input.mark(4)
        val magic = ByteArray(2).also { input.read(it) }
        input.reset()
        if (magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte()) {
            ZipInputStream(input).use { zip ->
                generateSequence { zip.nextEntry }.firstOrNull { it.name.endsWith(".txt", ignoreCase = true) }
                    ?: return@withContext ""
                String(zip.readLimited(), Charsets.UTF_8)
            }
        } else {
            String(input.readLimited(), Charsets.UTF_8)
        }
    }
}

private fun java.io.InputStream.readLimited(): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    while (out.size() < MAX_EXPORT_BYTES) {
        val n = read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
