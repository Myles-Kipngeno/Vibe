package com.vibe.keyboard.app

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.vibe.keyboard.overlay.SessionMemory
import com.vibe.keyboard.settings.VibePreferences
import com.vibe.keyboard.settings.VibeSettings
import com.vibe.keyboard.ui.VibeIcons
import com.vibe.keyboard.ui.VibeMark
import com.vibe.keyboard.ui.theme.VibeColors
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(onPractice: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { VibeSettings(context) }
    val prefs by settings.preferences.collectAsState(initial = VibePreferences())
    val scope = rememberCoroutineScope()

    var enabled by remember { mutableStateOf(isVibeEnabled(context)) }
    var selected by remember { mutableStateOf(isVibeSelected(context)) }
    // Choosing a keyboard from the system picker does not pause this screen, so
    // re-check both on resume and whenever the window regains focus.
    val lifecycleOwner = LocalLifecycleOwner.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                enabled = isVibeEnabled(context)
                selected = isVibeSelected(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(windowFocused) {
        enabled = isVibeEnabled(context)
        selected = isVibeSelected(context)
    }

    var remembered by remember { mutableIntStateOf(SessionMemory.size()) }

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
            Header()

            if (!enabled || !selected) {
                Section("Set up") {
                    SetupStep(
                        number = 1,
                        title = "Turn on Vibe Keyboard",
                        detail = "Android will warn that a keyboard can see what you type. Vibe keeps it on your phone.",
                        done = enabled,
                        action = "Open settings",
                    ) { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    SetupStep(
                        number = 2,
                        title = "Switch to Vibe",
                        detail = "Pick Vibe Keyboard as your keyboard.",
                        done = selected,
                        action = "Choose keyboard",
                        enabled = enabled,
                    ) { context.getSystemService(InputMethodManager::class.java).showInputMethodPicker() }
                }
            } else {
                ReadyBanner()
            }

            TryItCard(onPractice)

            Section("How Vibe helps") {
                ToggleRow(
                    title = "Suggest when I copy a message",
                    detail = "Copy their message, open the chat box, and Vibe is ready. Off: Vibe only helps when you tap ✦.",
                    checked = prefs.suggestOnCopy,
                ) { scope.launch { settings.setSuggestOnCopy(it) } }
                Divider()
                ToggleRow(
                    title = "Auto Reply",
                    detail = if (prefs.autoReply) {
                        "On: Vibe puts its suggestion in the message box for you. You still press Send."
                    } else {
                        "Off: Vibe suggests, you decide what goes in the box."
                    },
                    checked = prefs.autoReply,
                ) { scope.launch { settings.setAutoReply(it) } }
                Text(
                    "Vibe never sends a message or a photo for you. No messaging app lets a keyboard press Send, and Vibe won't pretend otherwise.",
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = VibeColors.TextTertiary,
                    modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
                )
                Divider()
                ToggleRow(title = "Haptic feedback", detail = null, checked = prefs.haptics) {
                    scope.launch { settings.setHaptics(it) }
                }
            }

            Section("Privacy") {
                PrivacyLine("Runs on this phone. This version has no internet access at all.")
                PrivacyLine("Reads a message only when you copy it. It can't see your chats.")
                PrivacyLine("Switches off in password and incognito fields.")
                PrivacyLine("Context you give it stays in memory for the current chat and is forgotten when you switch apps.")
                Spacer(Modifier.height(8.dp))
                PillButton(
                    text = if (remembered > 0) "Forget chat context ($remembered)" else "Forget chat context",
                    filled = false,
                ) {
                    SessionMemory.clear()
                    remembered = 0
                }
            }

            Text(
                "Preview build. Suggestions come from built-in templates, not an AI model yet, and are labelled PREVIEW.",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = VibeColors.TextTertiary,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(listOf(VibeColors.CardTop, VibeColors.KeyboardBg)))
                .border(1.dp, VibeColors.CardBorder, RoundedCornerShape(14.dp)),
        ) { VibeMark(size = 22.dp) }
        Spacer(Modifier.width(14.dp))
        Column {
            Text("Vibe", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = VibeColors.TextPrimary)
            Text("Conversation help, right in your keyboard.", fontSize = 14.sp, color = VibeColors.TextSecondary)
        }
    }
}

@Composable
private fun ReadyBanner() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(VibeColors.Accent.copy(alpha = 0.08f))
            .border(1.dp, VibeColors.Accent.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        Icon(VibeIcons.Check, null, tint = VibeColors.Accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text("Vibe Keyboard is on and selected.", fontSize = 14.sp, color = VibeColors.TextPrimary)
    }
}

@Composable
private fun TryItCard(onPractice: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(VibeColors.CardTop, VibeColors.CardBottom)))
            .border(1.dp, VibeColors.CardBorder, RoundedCornerShape(20.dp))
            .clickable(role = Role.Button, onClick = onPractice)
            .padding(18.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Try it", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = VibeColors.TextPrimary)
            Text(
                "A practice chat with every kind of moment Vibe handles.",
                fontSize = 13.sp,
                color = VibeColors.TextSecondary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text("›", fontSize = 26.sp, color = VibeColors.Accent)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(VibeColors.CardBottom)
            .border(1.dp, VibeColors.CardBorder, RoundedCornerShape(20.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Text(
            title.uppercase(),
            fontSize = 11.sp,
            letterSpacing = 1.sp,
            fontWeight = FontWeight.SemiBold,
            color = VibeColors.TextTertiary,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        content()
    }
}

@Composable
private fun SetupStep(
    number: Int,
    title: String,
    detail: String,
    done: Boolean,
    action: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 8.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(if (done) VibeColors.Accent else Color.White.copy(alpha = 0.06f)),
        ) {
            if (done) Icon(VibeIcons.Check, null, tint = VibeColors.OnAccent, modifier = Modifier.size(16.dp))
            else Text("$number", fontSize = 13.sp, color = VibeColors.TextSecondary)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = VibeColors.TextPrimary)
            Text(detail, fontSize = 13.sp, lineHeight = 18.sp, color = VibeColors.TextSecondary, modifier = Modifier.padding(top = 2.dp))
            if (!done) {
                PillButton(action, filled = true, enabled = enabled, modifier = Modifier.padding(top = 10.dp), onClick = onClick)
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch) { onChange(!checked) }
            .padding(vertical = 10.dp),
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = VibeColors.TextPrimary)
            if (detail != null) {
                Text(detail, fontSize = 13.sp, lineHeight = 18.sp, color = VibeColors.TextSecondary, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = VibeColors.OnAccent,
                checkedTrackColor = VibeColors.Accent,
                uncheckedThumbColor = VibeColors.TextSecondary,
                uncheckedTrackColor = VibeColors.Key,
                uncheckedBorderColor = VibeColors.CardBorder,
            ),
        )
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(VibeColors.CardBorder))
}

@Composable
private fun PrivacyLine(text: String) {
    Row(Modifier.padding(vertical = 5.dp)) {
        Box(
            Modifier
                .padding(top = 7.dp)
                .size(5.dp)
                .clip(CircleShape)
                .background(VibeColors.Accent.copy(alpha = 0.7f)),
        )
        Spacer(Modifier.width(12.dp))
        Text(text, fontSize = 14.sp, lineHeight = 19.sp, color = VibeColors.TextSecondary)
    }
}

@Composable
fun PillButton(
    text: String,
    filled: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(CircleShape)
            .background(
                when {
                    !enabled -> VibeColors.Key
                    filled -> VibeColors.Accent
                    else -> Color.White.copy(alpha = 0.07f)
                },
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(
            text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = when {
                !enabled -> VibeColors.TextTertiary
                filled -> VibeColors.OnAccent
                else -> VibeColors.TextPrimary
            },
        )
    }
}

private fun isVibeEnabled(context: Context): Boolean =
    context.getSystemService(InputMethodManager::class.java)
        .enabledInputMethodList.any { it.packageName == context.packageName }

private fun isVibeSelected(context: Context): Boolean =
    Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        ?.startsWith(context.packageName + "/") == true
