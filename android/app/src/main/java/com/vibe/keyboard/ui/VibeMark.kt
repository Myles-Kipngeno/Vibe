package com.vibe.keyboard.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vibe.keyboard.ui.theme.VibeColors
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

enum class MarkMood { Idle, Thinking, Arrived }

/**
 * The ✦ Vibe mark. It breathes while Vibe is thinking and gives one small
 * pop, with a faint ring, when something arrives. Never more than that.
 */
@Composable
fun VibeMark(
    modifier: Modifier = Modifier,
    size: Dp = 14.dp,
    tint: Color = VibeColors.Accent,
    mood: MarkMood = MarkMood.Idle,
    /** Changing this replays the arrival pop (e.g. a new suggestion's text). */
    arrivalKey: Any? = null,
) {
    val pop = remember { Animatable(1f) }
    val ring = remember { Animatable(0f) }
    LaunchedEffect(mood, arrivalKey) {
        if (mood == MarkMood.Arrived) {
            pop.snapTo(0.55f)
            ring.snapTo(0f)
            coroutineScope {
                launch { pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow)) }
                launch { ring.animateTo(1f, tween(520)) }
            }
        }
    }

    val breathing = rememberInfiniteTransition(label = "breath")
    val breath by breathing.animateFloat(
        initialValue = 0.82f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse),
        label = "scale",
    )
    val spin by breathing.animateFloat(
        initialValue = 0f,
        targetValue = 90f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "spin",
    )
    val thinking = mood == MarkMood.Thinking

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (mood == MarkMood.Arrived && ring.value < 1f) {
            Canvas(Modifier.size(size * 2.4f)) {
                val r = this.size.minDimension / 2 * (0.4f + 0.6f * ring.value)
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(tint.copy(alpha = 0.35f * (1 - ring.value)), Color.Transparent),
                        center = center,
                        radius = r.coerceAtLeast(1f),
                    ),
                    radius = r,
                )
            }
        }
        Icon(
            imageVector = VibeIcons.Sparkle,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .size(size)
                .graphicsLayer {
                    val s = if (thinking) breath else pop.value
                    scaleX = s
                    scaleY = s
                    rotationZ = if (thinking) spin else 0f
                },
        )
    }
}
