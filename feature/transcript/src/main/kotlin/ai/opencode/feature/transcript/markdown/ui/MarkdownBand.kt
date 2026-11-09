package ai.opencode.feature.transcript.markdown.ui

import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeColors
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.feature.transcript.LocalTranscriptLayoutTuning
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ai.opencode.core.motion.Motion

/** What every transcript band shares: code, tables, and opened inline rows (iOS `TranscriptBandStyle`). */
object TranscriptBandStyle {
    /** From a hairline to the content's first line box. */
    val verticalPadding: Dp = 12.dp

    fun fill(colors: OpenCodeColors): Color = colors.codeBand
}

/**
 * Runs the content out past the row's gutters to the screen's edges (iOS
 * `.padding(.horizontal, -TranscriptMetrics.contentGutter)`): measured [gutter] wider on each
 * side and placed [gutter] to the left.
 */
fun Modifier.bleedHorizontal(gutter: Dp): Modifier = layout { measurable, constraints ->
    val extra = gutter.roundToPx()
    val maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + 2 * extra else constraints.maxWidth
    val placeable = measurable.measure(constraints.copy(minWidth = (constraints.minWidth + 2 * extra).coerceAtMost(maxWidth), maxWidth = maxWidth))
    val width = (placeable.width - 2 * extra).coerceAtLeast(0)
    layout(width, placeable.height) { placeable.place(-extra, 0) }
}

/**
 * One device pixel (iOS `HairlineRule`). Compose places layouts on whole pixels, so the rule
 * never renders two pixels wide or vanishes at a fractional position.
 */
@Composable
fun HairlineRule(modifier: Modifier = Modifier, alpha: Float = 1f) {
    val hairline = with(LocalDensity.current) { 1f.toDp() }
    Box(modifier.fillMaxWidth().height(hairline).background(OpenCodeTheme.colors.border.copy(alpha = OpenCodeTheme.colors.border.alpha * alpha)))
}

/**
 * Fades a horizontal scroll's edges where more content lies past them: the trailing edge
 * until the end, the leading edge once scrolled (iOS `horizontalOverflowFade(width:)`).
 * The fades ease over 0.15 s; only drawing changes.
 */
@Composable
fun Modifier.horizontalOverflowFade(state: ScrollState, width: Dp = 40.dp): Modifier {
    val fade = Motion.swap.animation<Float>(reduceMotion = rememberReduceMotion())
    val leading by animateFloatAsState(if (state.value > 1) 1f else 0f, fade, label = "leadingFade")
    val trailing by animateFloatAsState(if (state.maxValue - state.value > 1) 1f else 0f, fade, label = "trailingFade")
    return graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val w = width.toPx().coerceAtMost(size.width / 2)
            if (leading > 0f) {
                drawRect(
                    Brush.horizontalGradient(0f to Color.Black.copy(alpha = leading), 1f to Color.Transparent, startX = 0f, endX = w),
                    topLeft = Offset.Zero, size = Size(w, size.height), blendMode = BlendMode.DstOut,
                )
            }
            if (trailing > 0f) {
                drawRect(
                    Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = trailing), startX = size.width - w, endX = size.width),
                    topLeft = Offset(size.width - w, 0f), size = Size(w, size.height), blendMode = BlendMode.DstOut,
                )
            }
        }
}

/** Where a table's full-width rules sit, reported by the grid's layout and read only while drawing. */
@androidx.compose.runtime.Stable
class TableRules {
    /** Y of each rule in the scroll's content, header rule first. Written in layout, read in draw (a redraw, never a relayout). */
    var offsets: List<Float> by androidx.compose.runtime.mutableStateOf(emptyList())
}

/**
 * Tables run the screen's full width on a faint band between hairlines (iOS
 * `MarkdownBand`; code and display math are on [CodeBlockCard] on Android). Content starts at the prose edge and scrolls out to the screen edges,
 * fading where more lies past them. A table's rules keep the viewport's width while its
 * columns scroll underneath.
 *
 * @param bandModifier applied to the band at its full width, past the gutters: a drag source
 *   there lifts the whole band.
 */
@Composable
fun MarkdownBand(
    modifier: Modifier = Modifier,
    rules: TableRules? = null,
    bandModifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = OpenCodeTheme.colors
    val gutter = LocalTranscriptLayoutTuning.current.contentGutter.dp
    val scroll = rememberScrollState()
    val hairline = with(LocalDensity.current) { 1f.toDp() }
    Column(modifier.bleedHorizontal(gutter).then(bandModifier)) {
        HairlineRule()
        Box(
            Modifier
                .fillMaxWidth()
                .background(TranscriptBandStyle.fill(colors))
                .padding(vertical = TranscriptBandStyle.verticalPadding)
                .drawWithContent {
                    drawContent()
                    val offsets = rules?.offsets ?: return@drawWithContent
                    val px = hairline.toPx()
                    for ((index, y) in offsets.withIndex()) {
                        drawRect(
                            colors.border.copy(alpha = colors.border.alpha * (if (index == 0) 1f else 0.6f)),
                            topLeft = Offset(0f, y),
                            size = Size(size.width, px),
                        )
                    }
                },
        ) {
            Box(
                Modifier
                    .horizontalOverflowFade(scroll, width = 32.dp)
                    .horizontalScroll(scroll)
                    .padding(horizontal = gutter),
            ) { content() }
        }
        HairlineRule()
    }
}
