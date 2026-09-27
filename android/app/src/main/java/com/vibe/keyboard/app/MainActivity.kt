package com.vibe.keyboard.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vibe.keyboard.ui.theme.VibeTheme

/**
 * The companion app. Deliberately small: turn the keyboard on, set a few
 * switches, try it, import a chat, look after memory. The keyboard is the
 * product; this is its settings page.
 */
class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_SCREEN = "com.vibe.keyboard.SCREEN"
        const val SCREEN_IMPORT = "import"
    }

    private enum class Screen { HOME, PRACTICE, IMPORT, MEMORY, CONNECT }

    private var screen by mutableStateOf(Screen.HOME)
    private var shared by mutableStateOf<SharedChat?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) route(intent)
        setContent {
            VibeTheme {
                BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }
                AnimatedContent(
                    targetState = screen,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "screen",
                ) { current ->
                    when (current) {
                        Screen.HOME -> HomeScreen(
                            onPractice = { screen = Screen.PRACTICE },
                            onImport = { shared = null; screen = Screen.IMPORT },
                            onMemory = { screen = Screen.MEMORY },
                            onConnect = { screen = Screen.CONNECT },
                        )
                        Screen.PRACTICE -> PracticeScreen(onBack = { screen = Screen.HOME })
                        Screen.IMPORT -> ImportScreen(
                            shared = shared,
                            onDone = { shared = null; screen = Screen.HOME },
                            onOpenMemory = { shared = null; screen = Screen.MEMORY },
                        )
                        Screen.CONNECT -> ConnectScreen(onBack = { screen = Screen.HOME })
                        Screen.MEMORY -> MemoryScreen(
                            onBack = { screen = Screen.HOME },
                            onImport = { shared = null; screen = Screen.IMPORT },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        route(intent)
    }

    /** WhatsApp's "Export chat" arrives here through the share sheet, as text or a file. */
    private fun route(intent: Intent?) {
        intent ?: return
        if (intent.action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            val uri = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            }
            val text = if (uri == null) intent.getStringExtra(Intent.EXTRA_TEXT) else null
            shared = SharedChat(text, uri, intent.getStringExtra(Intent.EXTRA_SUBJECT))
            screen = Screen.IMPORT
        } else if (intent.getStringExtra(EXTRA_SCREEN) == SCREEN_IMPORT) {
            shared = null
            screen = Screen.IMPORT
        }
    }
}
