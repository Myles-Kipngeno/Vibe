package com.vibe.keyboard.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** The handful of glyphs Vibe needs that Material's core set does not have. */
object VibeIcons {

    val Sparkle: ImageVector by lazy {
        filled("Sparkle", "M12,2C12.9,8.2 15.8,11.1 22,12C15.8,12.9 12.9,15.8 12,22C11.1,15.8 8.2,12.9 2,12C8.2,11.1 11.1,8.2 12,2Z")
    }

    val Backspace: ImageVector by lazy {
        filled(
            "Backspace",
            "M22,3H7c-0.69,0 -1.23,0.35 -1.59,0.88L0,12l5.41,8.11c0.36,0.53 0.9,0.89 1.59,0.89h15c1.1,0 2,-0.9 2,-2V5c0,-1.1 -0.9,-2 -2,-2zM22,19H7.07L2.4,12l4.66,-7H22v14zM10.41,17L14,13.41 17.59,17 19,15.59 15.41,12 19,8.41 17.59,7 14,10.59 10.41,7 9,8.41 12.59,12 9,15.59z",
        )
    }

    val Return: ImageVector by lazy {
        filled("Return", "M19,7v4H5.83l3.58,-3.59L8,6l-6,6 6,6 1.41,-1.41L5.83,13H21V7z")
    }

    val ShiftOff: ImageVector by lazy { stroked("ShiftOff", "M12,3.8L3.8,12.6H8.4V20H15.6V12.6H20.2Z") }
    val ShiftOn: ImageVector by lazy { filled("ShiftOn", "M12,3L3,12.8H8V20.5H16V12.8H21Z") }
    val ShiftLocked: ImageVector by lazy { filled("ShiftLocked", "M12,2L3,11.6H8V17.5H16V11.6H21ZM8,19.5H16V22H8Z") }

    val Moon: ImageVector by lazy {
        filled("Moon", "M13.2,3.1A8.9,8.9 0,1 0,20.9 15.1A7.1,7.1 0,0 1,13.2 3.1Z")
    }

    val Camera: ImageVector by lazy {
        stroked(
            "Camera",
            "M4.5,7.5H7.6L9.2,5.2H14.8L16.4,7.5H19.5A1.5,1.5 0,0 1,21 9V18A1.5,1.5 0,0 1,19.5 19.5H4.5A1.5,1.5 0,0 1,3 18V9A1.5,1.5 0,0 1,4.5 7.5ZM12,10A3.3,3.3 0,1 0,12.001 10Z",
        )
    }

    /** A hand-raised "stop": used when they have set a boundary. */
    val Pause: ImageVector by lazy {
        stroked("Pause", "M12,3A9,9 0,1 0,12.001 3ZM9.5,8.5V15.5M14.5,8.5V15.5")
    }

    val Bell: ImageVector by lazy {
        stroked(
            "Bell",
            "M18,16.5H6L7.3,14.8V10.5A4.7,4.7 0,0 1,16.7 10.5V14.8ZM10.2,19.2A1.9,1.9 0,0 0,13.8 19.2",
        )
    }

    val Emoji: ImageVector by lazy {
        stroked(
            "Emoji",
            "M12,3A9,9 0,1 0,12.001 3ZM8.2,14.2C9.1,15.6 10.4,16.3 12,16.3C13.6,16.3 14.9,15.6 15.8,14.2M9,9.6V10.2M15,9.6V10.2",
        )
    }

    val Undo: ImageVector by lazy {
        stroked("Undo", "M9,6.5L4.5,11L9,15.5M5,11H14.5A5,5 0,0 1,14.5 21H11")
    }

    val Refresh: ImageVector by lazy {
        stroked("Refresh", "M19.5,12A7.5,7.5 0,1 1,16.9 6.3M19.8,3.8V7.6H16")
    }

    val Close: ImageVector by lazy { stroked("Close", "M6.5,6.5L17.5,17.5M17.5,6.5L6.5,17.5") }

    val Check: ImageVector by lazy { stroked("Check", "M5,12.5L10,17.5L19,7") }

    val ChevronDown: ImageVector by lazy { stroked("ChevronDown", "M6,9.5L12,15.5L18,9.5") }

    val Tune: ImageVector by lazy {
        stroked("Tune", "M4,7H14M18,7H20M4,17H6M10,17H20M16,4.5V9.5M8,14.5V19.5")
    }

    private fun filled(name: String, d: String) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(PathParser().parsePathString(d).toNodes(), fill = SolidColor(Color.Black))
            .build()

    private fun stroked(name: String, d: String) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(
                PathParser().parsePathString(d).toNodes(),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            .build()
}
