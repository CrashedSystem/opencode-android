@file:PortedFrom("OpenCode/Transcript/HorizontalOverflowFade.swift", "553a407")

package ai.opencode.mobilecode

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun Modifier.horizontalOverflowFade(state: ScrollState, width: Dp = 40.dp): Modifier {
    val leadingTarget = if (state.value > 1) 1f else 0f
    val trailingTarget = if (state.maxValue - state.value > 1) 1f else 0f
    val leading by animateFloatAsState(leadingTarget, tween(150), label = "leading-fade")
    val trailing by animateFloatAsState(trailingTarget, tween(150), label = "trailing-fade")
    return graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        val fade = width.toPx()
        if (leading > 0.01f && fade > 0f) {
            drawRect(
                brush = Brush.horizontalGradient(
                    listOf(Color.Black.copy(alpha = 1f - leading), Color.Black),
                    endX = fade,
                ),
                size = androidx.compose.ui.geometry.Size(fade, size.height),
                blendMode = BlendMode.DstIn,
            )
        }
        if (trailing > 0.01f && fade > 0f) {
            drawRect(
                brush = Brush.horizontalGradient(
                    listOf(Color.Black, Color.Black.copy(alpha = 1f - trailing)),
                    startX = size.width - fade,
                    endX = size.width,
                ),
                topLeft = androidx.compose.ui.geometry.Offset(size.width - fade, 0f),
                size = androidx.compose.ui.geometry.Size(fade, size.height),
                blendMode = BlendMode.DstIn,
            )
        }
    }
}
