package ai.opencode.feature.transcript.rows

import ai.opencode.core.motion.AmbientClock
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.tuning.AnimationTimeScale
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.feature.media.TranscriptMetrics
import ai.opencode.feature.transcript.LocalTranscriptLayoutTuning
import ai.opencode.feature.transcript.list.LocalTranscriptTimelinePaused
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** iOS `TranscriptMetrics` fonts (`codeFont`, `toolFont`, `metaFont`). */
object TranscriptFonts {
    /** Markdown code blocks and request commands: one mono family, sized to sit beside 17 pt body text. */
    val codeFont: TextStyle = OpenCodeTextStyles.subheadline.copy(fontFamily = FontFamily.Monospace)

    /** Denser tool commands, arguments, and output (the desktop app's 13 pt). */
    val toolFont: TextStyle = OpenCodeTextStyles.footnote.copy(fontFamily = FontFamily.Monospace)

    /** Used N, answered question rows, and Working (`metaSize`; body size, 16 sp, by default). */
    @Composable
    fun metaFont(weight: FontWeight = FontWeight.Normal): TextStyle {
        val size = LocalTranscriptLayoutTuning.current.metaSize
        val body = OpenCodeTextStyles.body
        val sized = if (size == body.fontSize.value) body else body.copy(fontSize = size.sp, lineHeight = (size * 1.5f).sp)
        return if (weight == FontWeight.Normal) sized else sized.copy(fontWeight = weight)
    }
}

/**
 * iOS `dynamicTypeSize.isAccessibilitySize` (AX1 and up, about 1.65× body): rows stack or wrap
 * instead of truncating.
 */
@Composable
internal fun isAccessibilityTextSize(): Boolean = LocalDensity.current.fontScale >= 1.6f

/** iOS `MinimumHitRect`: the view's own rectangle, grown evenly to 44 pt (48 dp) on a short side. */
object MinimumHitRect {
    val side: Dp = TranscriptMetrics.minimumHitSide

    /** [rect] grown to [side]; [topReach] caps how far it grows upward, the rest goes below. */
    fun rect(rect: Rect, side: Float, topReach: Float = Float.POSITIVE_INFINITY): Rect {
        val dx = max(0f, (side - rect.width) / 2)
        val grow = max(0f, side - rect.height)
        val up = min(grow / 2, topReach)
        return Rect(rect.left - dx, rect.top - up, rect.right + dx, rect.bottom + grow - up)
    }
}

/**
 * Lays [target] (a `clickable`, `combinedClickable`, …) out [left]/[top]/[right]/[bottom] beyond
 * the content, while the frame stays the content's own: Compose delivers touches to a child
 * placed outside its parent's bounds, so the target fills the row's halves without changing its
 * height.
 */
private fun Modifier.expandedTarget(left: Dp, top: Dp, right: Dp, bottom: Dp, target: Modifier): Modifier =
    this.layout { measurable, constraints ->
        val h = left.roundToPx() + right.roundToPx()
        val v = top.roundToPx() + bottom.roundToPx()
        val placeable = measurable.measure(constraints.offset(h, v))
        val width = (placeable.width - h).coerceIn(constraints.minWidth, max(constraints.minWidth, constraints.maxWidth))
        val height = (placeable.height - v).coerceIn(constraints.minHeight, max(constraints.minHeight, constraints.maxHeight))
        layout(width, height) { placeable.place(-left.roundToPx(), -top.roundToPx()) }
    }.then(target).layout { measurable, constraints ->
        val l = left.roundToPx()
        val t = top.roundToPx()
        val h = l + right.roundToPx()
        val v = t + bottom.roundToPx()
        val placeable = measurable.measure(constraints.offset(-h, -v))
        layout(placeable.width + h, placeable.height + v) { placeable.place(l, t) }
    }

/**
 * iOS `rowHitTarget(top:bottom:leading:)`: keeps a quiet row's frame at its text's line box (so
 * gaps around it match the paragraphs') while [target] fills the row's own halves. `leading`
 * keeps the reply copy target within the screen edge. Pass the interaction as [target]:
 * `Modifier.rowHitTarget(target = Modifier.clickable { … })`.
 */
fun Modifier.rowHitTarget(
    top: Dp = TranscriptMetrics.flowHalf,
    bottom: Dp = TranscriptMetrics.flowHalf,
    leading: Dp = TranscriptMetrics.flowHalf,
    target: Modifier,
): Modifier = expandedTarget(leading, top, TranscriptMetrics.flowHalf, bottom, target)

/**
 * iOS `minimumHitTarget(side:topReach:)`: a 48 × 48 dp target at least (or [side]), grown evenly
 * about the view without moving it or anything beside it. [topReach] caps how far it grows
 * upward; the rest goes below.
 */
fun Modifier.minimumHitTarget(side: Dp = MinimumHitRect.side, topReach: Dp = Dp.Infinity, target: Modifier): Modifier {
    // The content's measure decides the growth; the outer pass reads it after measuring.
    val grow = IntArray(4)
    var original = Constraints()
    return this.layout { measurable, constraints ->
        original = constraints
        val s = side.roundToPx()
        val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0).offset(s, s))
        val width = (placeable.width - grow[0] - grow[2]).coerceIn(constraints.minWidth, max(constraints.minWidth, constraints.maxWidth))
        val height = (placeable.height - grow[1] - grow[3]).coerceIn(constraints.minHeight, max(constraints.minHeight, constraints.maxHeight))
        layout(width, height) { placeable.place(-grow[0], -grow[1]) }
    }.then(target).layout { measurable, _ ->
        val placeable = measurable.measure(original)
        val reach = if (topReach == Dp.Infinity) Float.POSITIVE_INFINITY else topReach.toPx()
        val rect = MinimumHitRect.rect(Rect(0f, 0f, placeable.width.toFloat(), placeable.height.toFloat()), side.toPx(), reach)
        grow[0] = (-rect.left).roundToInt()
        grow[1] = (-rect.top).roundToInt()
        grow[2] = (rect.right - placeable.width).roundToInt()
        grow[3] = (rect.bottom - placeable.height).roundToInt()
        layout(placeable.width + grow[0] + grow[2], placeable.height + grow[1] + grow[3]) { placeable.place(grow[0], grow[1]) }
    }
}

/** iOS `InlineRowPulse`: a running row's calm pulse, still under Reduce Motion. */
object InlineRowPulse {
    /** 1…0 and back over one `AmbientClock` breath, eased. */
    fun level(atSeconds: Double): Double = 1 - AmbientClock.breath(atSeconds)

    /**
     * iOS `AnimationTimeScale.referenceTime(date)`: wall time in `timeIntervalSinceReferenceDate`
     * seconds (since 2001, so the phase matches iOS), advanced on the slow-mo clock while it's scaled.
     */
    fun referenceSeconds(wallMillis: Long): Double {
        val realSeconds = System.nanoTime() / 1e9
        val virtualAhead = AnimationTimeScale.shared.now(realSeconds) - realSeconds
        return wallMillis / 1000.0 - 978_307_200.0 + virtualAhead
    }
}

/** Applies [InlineRowPulse] while [isActive]: opacity `0.45 + 0.55 × level`, drawn per frame (no recomposition). */
@Composable
fun Modifier.inlineRowPulse(isActive: Boolean): Modifier {
    val reduceMotion = rememberReduceMotion()
    if (!isActive || reduceMotion) return this
    val paused = LocalTranscriptTimelinePaused.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(paused) {
        if (paused) return@LaunchedEffect
        // ~30 Hz like iOS's `TimelineView(.animation(minimumInterval: 1 / 30))`.
        var last = 0L
        while (true) {
            withFrameMillis { frame ->
                if (frame - last >= 33) {
                    last = frame
                    now = System.currentTimeMillis()
                }
            }
        }
    }
    return this.graphicsLayer { alpha = (0.45 + 0.55 * InlineRowPulse.level(InlineRowPulse.referenceSeconds(now))).toFloat() }
}
