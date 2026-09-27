package com.vibe.keyboard.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibe.keyboard.remote.BackendClient
import com.vibe.keyboard.remote.BackendException
import com.vibe.keyboard.remote.Connection
import com.vibe.keyboard.remote.DataStoreConnectionStore
import com.vibe.keyboard.remote.HealthDto
import com.vibe.keyboard.ui.theme.VibeColors
import kotlinx.coroutines.launch

/**
 * Connecting the keyboard to your own Vibe server, which holds the model key
 * and the rules. The phone keeps only the address and a sign-in token.
 */
@Composable
fun ConnectScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { DataStoreConnectionStore(context) }
    val client = remember { BackendClient() }
    val scope = rememberCoroutineScope()
    val saved by store.connection.collectAsState(initial = Connection())

    var url by remember(saved.backendUrl) { mutableStateOf(saved.backendUrl.ifBlank { "http://127.0.0.1:8000" }) }
    var health by remember { mutableStateOf<HealthDto?>(null) }
    var email by remember(saved.email) { mutableStateOf(saved.email) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun check() {
        busy = true
        error = null
        scope.launch {
            try {
                val h = client.health(url.trim())
                health = h
                store.save(
                    saved.copy(
                        backendUrl = url.trim(),
                        authRequired = h.authRequired,
                        supabaseUrl = h.supabaseUrl.orEmpty(),
                        anonKey = h.supabaseAnonKey.orEmpty(),
                        modelLabel = listOfNotNull(h.provider, h.model).joinToString(" · "),
                        serverIsMock = h.isMock,
                        // A different server means a different account.
                        accessToken = if (saved.backendUrl == url.trim()) saved.accessToken else "",
                        refreshToken = if (saved.backendUrl == url.trim()) saved.refreshToken else "",
                    ),
                )
            } catch (e: BackendException) {
                error = "The server answered with an error: ${e.message}"
            } catch (e: Exception) {
                error = "Couldn't reach $url. Is the server running, and is the address right?"
            } finally {
                busy = false
            }
        }
    }

    fun signIn() {
        busy = true
        error = null
        scope.launch {
            try {
                val s = client.signIn(saved.supabaseUrl, saved.anonKey, email, password)
                store.save(
                    saved.copy(
                        email = email.trim(),
                        accessToken = s.accessToken,
                        refreshToken = s.refreshToken,
                        expiresAt = System.currentTimeMillis() + s.expiresIn * 1000,
                    ),
                )
                password = ""
            } catch (e: BackendException) {
                error = "Sign-in failed: ${e.message}"
            } catch (e: Exception) {
                error = "Couldn't reach the sign-in service."
            } finally {
                busy = false
            }
        }
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
            BackRow("AI replies", onBack)
            Section("Status") {
                val status = when {
                    !saved.isConfigured -> "Not connected. Replies come from built-in templates (PREVIEW)."
                    !saved.isSignedIn -> "Server found. Sign in below to start."
                    saved.serverIsMock -> "Connected, but the server has no model key yet, so replies are still templates."
                    else -> "Connected · ${saved.modelLabel}" + if (saved.email.isNotBlank()) " · ${saved.email}" else ""
                }
                Text(status, fontSize = 15.sp, lineHeight = 20.sp, color = VibeColors.TextPrimary)
            }

            Section("Server") {
                Field(url, onChange = { url = it }, keyboard = KeyboardType.Uri)
                Text(
                    "Over USB: run `adb reverse tcp:8000 tcp:8000` and keep 127.0.0.1:8000. " +
                        "On home Wi-Fi: your computer's address, e.g. http://192.168.1.20:8000. " +
                        "Deployed: its https:// address.",
                    fontSize = 12.sp, lineHeight = 17.sp, color = VibeColors.TextTertiary,
                    modifier = Modifier.padding(top = 6.dp),
                )
                PillButton(if (busy) "Checking…" else "Check server", filled = true, enabled = !busy, modifier = Modifier.padding(top = 10.dp)) { check() }
                health?.let { h ->
                    Text(
                        "Model: ${h.provider}${h.model?.let { " · $it" } ?: ""}" + if (h.isMock) " (templates only)" else "",
                        fontSize = 13.sp, color = VibeColors.TextSecondary, modifier = Modifier.padding(top = 10.dp),
                    )
                    if (h.isMock) {
                        Text(
                            "No model key on the server. A free Groq key works: put GROQ_API_KEY=… and AI_PROVIDER=auto in backend/.env and restart it.",
                            fontSize = 13.sp, lineHeight = 18.sp, color = VibeColors.Context, modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    h.warnings.forEach { Text(it, fontSize = 13.sp, lineHeight = 18.sp, color = VibeColors.Boundary, modifier = Modifier.padding(top = 4.dp)) }
                }
            }

            if (saved.isConfigured && saved.authRequired) {
                Section("Sign in") {
                    Text("The same account as the Vibe web app.", fontSize = 13.sp, color = VibeColors.TextSecondary, modifier = Modifier.padding(bottom = 8.dp))
                    Field(email, onChange = { email = it }, keyboard = KeyboardType.Email, placeholder = "Email")
                    Field(password, onChange = { password = it }, keyboard = KeyboardType.Password, placeholder = "Password", secret = true, modifier = Modifier.padding(top = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
                        PillButton("Sign in", filled = true, enabled = !busy && email.isNotBlank() && password.isNotBlank()) { signIn() }
                        if (saved.accessToken.isNotBlank()) {
                            PillButton("Sign out", filled = false) {
                                scope.launch { store.save(saved.copy(accessToken = "", refreshToken = "", expiresAt = 0)) }
                            }
                        }
                    }
                }
            }

            error?.let { Text(it, fontSize = 14.sp, lineHeight = 19.sp, color = VibeColors.Boundary) }

            Section("What gets sent") {
                PrivacyLine("Only when a suggestion is asked for: the recent messages of that chat, and the memories that bear on the message.")
                PrivacyLine("Never what you type, and nothing from password or incognito fields.")
                PrivacyLine("Your server passes it to its model provider. Free tiers have their own data terms, so check them.")
                if (saved.isConfigured) {
                    PillButton("Disconnect", filled = false, modifier = Modifier.padding(top = 8.dp)) {
                        scope.launch { store.save(Connection()) }
                        health = null
                    }
                }
            }
        }
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    keyboard: KeyboardType,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    secret: Boolean = false,
) {
    Box(
        modifier
            .fillMaxWidth()
            .background(VibeColors.Field, RoundedCornerShape(12.dp))
            .border(1.dp, VibeColors.CardBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        if (value.isEmpty()) Text(placeholder, fontSize = 16.sp, color = VibeColors.TextTertiary)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(fontSize = 16.sp, color = VibeColors.TextPrimary, fontWeight = FontWeight.Normal),
            cursorBrush = SolidColor(VibeColors.Accent),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
