package com.vibe.keyboard.app

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibe.keyboard.ui.VibeMark
import com.vibe.keyboard.ui.theme.VibeColors
import kotlinx.coroutines.delay

private data class Sample(val text: String, val expect: String)

private val samples = listOf(
    Sample("Just got home, today was so long 😩", "Suggested reply"),
    Sample("Ulienda town na Randy?", "Needs context"),
    Sample("How did your sister take it?", "Needs context"),
    Sample("Remember what happened at Naivas? 😂", "Needs context"),
    Sample("Send me a pic 😂", "Picture request"),
    Sample("Okay nalala sasa, goodnight 😴", "Winding down"),
    Sample("lol", "Vibe stays quiet"),
    Sample("I'm not interested, please stop texting me", "They asked for space"),
)

/**
 * A pretend chat for trying Vibe. Tapping a message copies it -- exactly what
 * you would do in WhatsApp -- so this exercises the real path, not a shortcut.
 */
@Composable
fun PracticeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var reply by remember { mutableStateOf("") }
    var copiedHint by remember { mutableStateOf(false) }
    LaunchedEffect(copiedHint) {
        if (copiedHint) {
            delay(2_200)
            copiedHint = false
        }
    }

    Box(Modifier.fillMaxSize().background(VibeColors.Background), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClick = onBack),
                ) { Text("‹", fontSize = 28.sp, color = VibeColors.TextSecondary) }
                Column {
                    Text("Practice chat", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = VibeColors.TextPrimary)
                    Text("Tap a message to copy it, then tap the box below.", fontSize = 12.sp, color = VibeColors.TextSecondary)
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                items(samples) { sample ->
                    Column(Modifier.padding(vertical = 5.dp)) {
                        Text(
                            sample.text,
                            fontSize = 15.sp,
                            lineHeight = 20.sp,
                            color = VibeColors.TextPrimary,
                            modifier = Modifier
                                .widthIn(max = 300.dp)
                                .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 18.dp))
                                .background(VibeColors.Key)
                                .clickable {
                                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                                    clipboard.setPrimaryClip(ClipData.newPlainText("message", sample.text))
                                    copiedHint = true
                                }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                        Text(
                            sample.expect,
                            fontSize = 11.sp,
                            color = VibeColors.TextTertiary,
                            modifier = Modifier.padding(start = 6.dp, top = 3.dp),
                        )
                    }
                }
            }

            AnimatedVisibility(copiedHint, enter = fadeIn(), exit = fadeOut()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    VibeMark(size = 12.dp)
                    Text(
                        "  Copied. Tap the box, or ✦ if the keyboard is already open.",
                        fontSize = 12.sp,
                        color = VibeColors.TextSecondary,
                    )
                }
            }

            BasicTextField(
                value = reply,
                onValueChange = { reply = it },
                textStyle = TextStyle(fontSize = 16.sp, color = VibeColors.TextPrimary),
                cursorBrush = SolidColor(VibeColors.Accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(VibeColors.CardBottom)
                    .border(1.dp, VibeColors.CardBorder, RoundedCornerShape(24.dp))
                    .padding(horizontal = 18.dp, vertical = 13.dp),
                decorationBox = { inner ->
                    if (reply.isEmpty()) Text("Message", fontSize = 16.sp, color = VibeColors.TextTertiary)
                    inner()
                },
            )
            Spacer(Modifier.size(4.dp))
        }
    }
}
