package com.vibe.keyboard.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Vibe's palette. Near-black with a violet cast, one restrained accent, and
 * state colours kept muted so nothing on the card shouts.
 */
object VibeColors {
    val Background = Color(0xFF0C0B10)

    // Keyboard
    val KeyboardBg = Color(0xFF111016)
    val Key = Color(0xFF1F1D27)
    val KeyPressed = Color(0xFF2E2B3A)
    val KeyFunction = Color(0xFF17161E)
    val KeyText = Color(0xFFEDEBF5)

    // Card
    val CardTop = Color(0xFF1C1926)
    val CardBottom = Color(0xFF15131C)
    val CardBorder = Color(0x14FFFFFF)
    val Field = Color(0xFF0F0E14)

    // Text
    val TextPrimary = Color(0xFFF3F1FA)
    val TextSecondary = Color(0xFFA29FB3)
    val TextTertiary = Color(0xFF6E6B7E)

    // Accent
    val Accent = Color(0xFFA594FF)
    val AccentStrong = Color(0xFF8B78F5)
    val AccentDeep = Color(0xFF6A58D6)
    val OnAccent = Color(0xFF0E0B1A)
    val AccentGlow = Color(0x338B78F5)

    // States, deliberately desaturated
    val Context = Color(0xFFE9C27A)
    val Night = Color(0xFF9DB4FF)
    val Picture = Color(0xFFF2A0C0)
    val Boundary = Color(0xFFF09A8A)
}

object VibeType {
    val CardBrand = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp)
    val CardLabel = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.1.sp)
    val CardBody = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal)
    val CardQuote = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val Button = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp)
    val Tag = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
    val Key = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.Normal)
    val KeySmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium)
}

@Composable
fun VibeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = VibeColors.Accent,
            onPrimary = VibeColors.OnAccent,
            secondary = VibeColors.AccentStrong,
            background = VibeColors.Background,
            onBackground = VibeColors.TextPrimary,
            surface = VibeColors.CardBottom,
            onSurface = VibeColors.TextPrimary,
            surfaceVariant = VibeColors.Key,
            onSurfaceVariant = VibeColors.TextSecondary,
            outline = VibeColors.CardBorder,
        ),
        content = content,
    )
}
