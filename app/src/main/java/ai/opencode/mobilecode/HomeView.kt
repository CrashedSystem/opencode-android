@file:PortedFrom("OpenCode/HomeView.swift", "553a407", partial = "DrawerShortcutLabel")
@file:PortedFrom("OpenCode/HomeView.swift", "553a407", partial = "AgentActivityGlyph")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.SystemColors
import ai.opencode.mobilecode.system.SystemImage
import ai.opencode.mobilecode.system.TextStyles
import ai.opencode.mobilecode.system.symbolSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos

/// A drawer shortcut row in the session rows’ style: a tinted symbol, the
/// title, and an accessory in the session indicators’ column.
@Composable
fun DrawerShortcutLabel(
    title: String,
    systemImage: String,
    modifier: Modifier = Modifier,
    accessory: @Composable () -> Unit,
) {
    val symbolWidth = with(LocalDensity.current) { 20.sp.toDp() }

    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Leading-aligned so each symbol starts on the session titles’ edge.
        Box(Modifier.width(symbolWidth), contentAlignment = Alignment.CenterStart) {
            SystemImage(systemImage, contentDescription = null, modifier = Modifier.symbolSize(TextStyles.body), tint = SystemColors.tint)
        }
        Text(title, style = TextStyles.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.weight(1f))
        Box(Modifier.widthIn(min = 18.dp), contentAlignment = Alignment.Center) {
            accessory()
        }
    }
}

/// The desktop app's subagent progress glyph: a 5×5 dot matrix with a
/// diagonal wave. Reduce Motion shows one still center dot.
@Composable
fun AgentActivityGlyph(modifier: Modifier = Modifier, size: Dp = 15.dp) {
    if (LocalReduceMotion.current) {
        Box(modifier.size(size), contentAlignment = Alignment.Center) {
            Box(Modifier.size(size * 0.3f).background(LocalContentColor.current, CircleShape).semantics { contentDescription = "Running" })
        }
        return
    }
    val time by rememberInfiniteTransition().animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_100, easing = LinearEasing)),
        label = "agent-activity",
    )
    val color = LocalContentColor.current
    Canvas(modifier.size(size).semantics { contentDescription = "Running" }) {
        val pitch = this.size.width / 5f
        val dot = pitch * 0.62f
        for (row in 0 until 5) {
            for (column in 0 until 5) {
                val wave = 0.5 + 0.5 * cos(((row + column) / 8.0 - time) * 2 * PI)
                val alpha = (0.16 + 0.84 * wave * wave * wave).toFloat()
                drawCircle(
                    color = color.copy(alpha = alpha),
                    radius = dot / 2f,
                    center = Offset(column * pitch + pitch / 2f, row * pitch + pitch / 2f),
                )
            }
        }
    }
}
