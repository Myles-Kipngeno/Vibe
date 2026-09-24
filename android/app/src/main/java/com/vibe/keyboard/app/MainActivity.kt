package com.vibe.keyboard.app

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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.vibe.keyboard.ui.theme.VibeTheme

/**
 * The companion app. Deliberately small: turn the keyboard on, set a few
 * switches, try it. The keyboard is the product; this is its settings page.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VibeTheme {
                var practicing by rememberSaveable { mutableStateOf(false) }
                BackHandler(enabled = practicing) { practicing = false }
                AnimatedContent(
                    targetState = practicing,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "screen",
                ) { showPractice ->
                    if (showPractice) PracticeScreen(onBack = { practicing = false })
                    else HomeScreen(onPractice = { practicing = true })
                }
            }
        }
    }
}
