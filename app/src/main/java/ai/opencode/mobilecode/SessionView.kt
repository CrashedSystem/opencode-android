@file:PortedFrom("OpenCode/SessionView.swift", "553a407", partial = "titleShimmer")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.SystemColors
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/// The syncing look of a title: dimmed, with a highlight sweeping across
/// it. Used while a session resyncs and while the server writes a title.
@Composable
fun Modifier.titleShimmer(active: Boolean): Modifier {
    if (!active) return this
    if (LocalReduceMotion.current) return alpha(0.4f)
    val phase by rememberInfiniteTransition().animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_400, easing = LinearEasing)),
        label = "title-shimmer",
    )
    val highlight = SystemColors.label
    return alpha(0.4f).drawWithContent {
        drawContent()
        val band = size.width * 0.55f
        val x = size.width * (phase * 1.75f - 0.55f)
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(Color.Transparent, highlight.copy(alpha = 0.9f), Color.Transparent),
                start = Offset(x, 0f),
                end = Offset(x + band, 0f),
            ),
            blendMode = BlendMode.SrcAtop,
        )
    }
}
