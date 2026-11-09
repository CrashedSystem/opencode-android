package ai.opencode.feature.session

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.motionBlurReveal
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.ui.OpenCodeShapeGeometry
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.RollingNumber
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.openCodeTapTarget
import ai.opencode.feature.composer.ComposerTextInputTunables
import androidx.compose.animation.AnimatedContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.indication
import androidx.compose.material3.ripple
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Port of iOS `Session/ComposerViews.swift`: the composer's layout, controls, pills and banners.

/** iOS `ComposerTextStyle`: Dynamic Type text styles for the message field and placeholder. */
enum class ComposerTextStyle(val rawValue: String) {
    footnote("footnote"), subheadline("subheadline"), callout("callout"), body("body"), headline("headline");

    val textStyle: TextStyle
        get() = when (this) {
            footnote -> OpenCodeTextStyles.footnote
            subheadline -> OpenCodeTextStyles.subheadline
            callout -> OpenCodeTextStyles.callout
            body -> OpenCodeTextStyles.body
            headline -> OpenCodeTextStyles.headline
        }
}

/**
 * iOS `ComposerTunables`: the composer's size, spacing and control geometry, as the shipped
 * defaults (dp for iOS points, seconds for durations). The developer Tuning Panel isn't ported.
 * Visual sizes are separate from hit targets, which stay at least 48 dp.
 */
object ComposerTunables {
    const val barHeight = 56f
    const val cornerRadius = 28f
    val textStyle = ComposerTextStyle.body

    const val horizontalMargin = 20f

    /**
     * With the keyboard up the composer widens to this margin, the same as its gap above the
     * keys, so a tiny even margin rings it.
     */
    const val keyboardHorizontalMargin = 8f
    const val bottomGap = 0f
    const val keyboardGap = 8f
    const val innerHorizontal = 6f
    const val innerVertical = 6f
    const val textLeading = 52f
    const val expandedTextLeading = 11f
    const val expandedTopInset = 16f
    const val expandedRowGap = 12f
    const val pillGap = 10f
    /** New Plus Menu: the attachment tiles sit this far from the surface's top and leading edges (f0670c5d). */
    const val attachmentInset = 19f
    /** New Plus Menu's Send while the composer is empty (iOS `emptySendOpacity`). */
    const val emptySendOpacity = 0.3f

    /** New Plus Menu's tile corners: concentric with the composer's, [attachmentInset] in. */
    val attachmentRadius: Float get() = max(cornerRadius - attachmentInset, 6f)

    const val plusOffset = 28f
    /** Nudges the + glyph's ink inside its target (OC-477). */
    const val plusInkOffset = 0f
    /** A composer pill's glyph frame starts this far in. */
    const val pillGlyphLeading = 8f
    const val micInkRise = 1f
    /** Between a pill's glyph and its label. */
    const val pillGlyphGap = 5f
    /** The queued pill's speech bubble drops so its optical centre sits on the end-cap's. */
    const val queuedGlyphDrop = 1f
    const val plusScale = 17f
    const val buttonDiameter = 34f
    const val trailingGap = 11f
    const val micScale = 17f
    const val trailingSpacing = 4f
    const val leadingSpacing = 9f
    const val swipeUpDistance = 18f
    const val swipeUpVelocity = 260f

    /** The composer's mic glyph; the Call Tray's Mute wears the same one (OC-473). */
    const val micSymbol = "mic"

    /** iOS `micFont`: body medium at the default 17 pt. */
    val micFont: TextStyle get() = OpenCodeTextStyles.body.copy(fontWeight = FontWeight.Medium)
}

// region ComposerLayout

/** iOS `VerticalAlignment.composerFoldTop`: where a subview of a folded [ComposerFoldLayout] starts to stay. */
val ComposerFoldTop = HorizontalAlignmentLine(::min)

/**
 * Android: where a [ComposerLayout] squeezed past its field's floor draws its field from (above
 * its top, so negative), for [ComposerFoldLayout] to give that room before the attachments'.
 */
val ComposerOverflowTop = HorizontalAlignmentLine(::min)

/** iOS `ComposerLayout.Inline`: answering a pending request, the field stays on the control row. */
data class ComposerLayoutInline(val hasLeading: Boolean, val lineHeight: Float)

/** iOS `ComposerLayout`'s statics. */
object ComposerLayoutMetrics {
    /** Expanded, the text starts where the +'s ink does. */
    val expandedTextInset: Float get() = ComposerTunables.expandedTextLeading
    val collapsedTextInset: Float get() = ComposerTunables.textLeading
    val expandedRowGap: Float get() = ComposerTunables.expandedRowGap
    val configuredRowHeight: Float get() = max(32f, ComposerTunables.barHeight - 2 * ComposerTunables.innerVertical)

    /**
     * iOS `pickerTargetDrop`: how far the row's pickers (model, agent) sit their targets below
     * their ink, down to the glass's bottom edge (the composer pads its bottom by
     * `innerVertical`), away from the text above. Taps aimed at the text kept opening the model
     * picker.
     *
     * Only the pickers: they're bare text with no visible edge, so where their target sits can't
     * be seen. + and the mic, Send and Call are glass or circle faces, and their target stays on
     * the face you can see: don't lower them to match.
     *
     * Android: the 48 dp targets already overhang the 44 dp row by 2 dp, so they drop that much
     * less (4 dp) to end on the same edge.
     */
    val pickerTargetDrop: Float
        get() {
            val overhang = max(0f, (48f - configuredRowHeight) / 2)
            return max(0f, ComposerTunables.innerVertical - overhang)
        }

    fun inputX(progress: Float, inline: ComposerLayoutInline?): Float {
        // On the request panel's edge: titles 12 dp after the marks, or the panel's 20 dp edge.
        if (inline != null) return if (inline.hasLeading) 48f else 14f
        return collapsedTextInset + (expandedTextInset - collapsedTextInset) * progress
    }

    /** Above and below the inline field's lines, so one line centres on the controls. */
    fun inlinePad(controls: Float, inline: ComposerLayoutInline?): Float = max(0f, (controls - (inline?.lineHeight ?: 0f)) / 2)
}

/**
 * iOS `ComposerLayout`: three stable children (input, leading controls, trailing control). Only
 * their placement changes, so keyboard focus never moves to a replacement field. Provides
 * [ComposerFoldTop] where the control row starts, so a folded [ComposerFoldLayout] keeps only it.
 *
 * [progress] is the row's expansion, 0 collapsed … 1 expanded, read frame by frame from the
 * keyboard morph (iOS `KeyboardDrivenComposerLayout`); the field insets its first line by
 * `expandedTopInset` × progress itself (`topInsetFraction`), so its lines are measured without it.
 *
 * Android: control slots are measured at least 48 dp tall (touch targets) and centre on the
 * 44 dp visual row, overhanging it by 2 dp each side, as iOS's targets do past its glass.
 *
 * Android: offered less height than it wants (a short window with the keys up), the field gives
 * way down to [inputLineHeight] (one line, dp), and scrolls; the control row never does. Past
 * that the field overflows above the layout's top ([ComposerOverflowTop]), never the row below
 * its bottom.
 */
@Composable
fun ComposerLayout(
    progress: () -> Float,
    inline: ComposerLayoutInline?,
    input: @Composable () -> Unit,
    leading: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    inputLineHeight: Float = 0f,
) {
    Layout(contents = listOf(input, leading, trailing), modifier = modifier) { (inputs, leadings, trailings), constraints ->
        val p = if (inline != null) 0f else progress().coerceIn(0f, 1f)
        val expanded = p > 0f
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else 320.dp.roundToPx()
        val px = { v: Float -> v.dp.toPx() }
        val trailingPlaceable = trailings.map { it.measure(Constraints()) }
        val trailingWidth = trailingPlaceable.maxOfOrNull { it.width } ?: 0
        val inputX = px(ComposerLayoutMetrics.inputX(p, inline))
        val collapsedRightPad = px(ComposerLayoutMetrics.collapsedTextInset) + px(4f)
        val collapsedWidth = width - collapsedRightPad - trailingWidth
        val expandedWidth = width - inputX - px(ComposerLayoutMetrics.expandedTextInset)
        val inputWidth = if (inline != null) {
            width - inputX - px(4f) - trailingWidth
        } else {
            collapsedWidth + (expandedWidth - collapsedWidth) * p
        }.roundToInt().coerceAtLeast(0)
        val controls = px(ComposerLayoutMetrics.configuredRowHeight)
        val inlinePad = px(ComposerLayoutMetrics.inlinePad(ComposerLayoutMetrics.configuredRowHeight, inline))
        // The field's lines, without the top inset it adds itself as the row opens.
        val topInset = ComposerTextInputTunables.expandedTopInset.toPx()
        // Short of room, the field takes what the row leaves it, down to one line.
        val inputMax = if (!constraints.hasBoundedHeight) {
            Constraints.Infinity
        } else {
            val reserve = if (inline != null) 2 * inlinePad else (px(ComposerLayoutMetrics.expandedRowGap) + controls) * p
            val floor = ceil(px(inputLineHeight) + if (inline != null) 0f else topInset * p)
            max(floor, constraints.maxHeight - reserve).roundToInt()
        }
        val inputPlaceable = inputs.map { it.measure(Constraints(minWidth = inputWidth, maxWidth = inputWidth, maxHeight = inputMax)) }
        val inputHeight = inputPlaceable.maxOfOrNull { it.height } ?: 0
        // Hit targets stay at least 48 dp tall even when the visual bar is compact.
        val targetHeight = max(48.dp.roundToPx(), controls.roundToInt())
        val leadingWidth = if (inline == null) max(px(44f), width - trailingWidth - px(4f)).roundToInt() else 48.dp.roundToPx()
        val leadingPlaceable = leadings.map { it.measure(Constraints(maxWidth = leadingWidth, maxHeight = targetHeight)) }
        val lines = if (inline != null) inputHeight.toFloat() else inputHeight - topInset * p
        val height = if (inline != null) {
            max(controls, inputHeight + 2 * inlinePad)
        } else {
            val collapsed = max(lines, controls)
            val open = lines + topInset + px(ComposerLayoutMetrics.expandedRowGap) + controls
            collapsed + (open - collapsed) * p
        }.roundToInt()
        // Folded for dictation, the composer keeps only the control row.
        // Past the field's floor the overflow goes above the top, keeping the row in place.
        val laidOut = min(height, constraints.maxHeight)
        val overflow = height - laidOut
        val foldTop: Int? = if (expanded || inline != null) (laidOut - controls).roundToInt() else null
        val alignment = buildMap<androidx.compose.ui.layout.AlignmentLine, Int> {
            if (foldTop != null) put(ComposerFoldTop, foldTop)
            if (overflow > 0) put(ComposerOverflowTop, -overflow)
        }
        layout(width, laidOut, alignment) {
            val controlsY = laidOut - controls / 2
            val inputY = if (inline != null) {
                laidOut - inlinePad - inputHeight
            } else {
                (controls - lines) / 2 * (1 - p) - overflow
            }
            inputPlaceable.forEach { it.place(inputX.roundToInt(), inputY.roundToInt()) }
            // The leading row ends 4 dp short of the trailing control: a mic at either end sits in
            // the slot the dictation bar's ■ takes, so tapping it changes nothing's place.
            leadingPlaceable.forEach { it.place(0, (controlsY - it.height / 2f).roundToInt()) }
            trailingPlaceable.forEach { it.place(width - it.width, (controlsY - it.height / 2f).roundToInt()) }
        }
    }
}

// endregion

// region ComposerFoldLayout

private object ComposerFoldsMarker

/** iOS `composerFolds()`: folds away with the composer while dictating (a [ComposerFoldLayout] child). */
fun Modifier.composerFolds(): Modifier = layoutId(ComposerFoldsMarker)

/**
 * iOS `ComposerFoldLayout`: the composer's column (attachments, the field and its controls, status
 * lines), stacked like a leading column. Folded, it's only as tall as what stays: the children not
 * marked [composerFolds], from their [ComposerFoldTop] line down. Everything else keeps its place,
 * outside the bounds, for the composer's (clipped) edge to pass over.
 *
 * @param foldProgress 0 unfolded … 1 folded; animate it (iOS animates the layout itself).
 * @param attachmentRevealProgress the location row's reveal (iOS `Animatable` data).
 */
@Composable
fun ComposerFoldLayout(
    foldProgress: Float,
    modifier: Modifier = Modifier,
    attachmentRevealProgress: Float = 1f,
    spacing: Dp = 8.dp,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val folds = measurables.map { it.folds }
        val firstStaying = folds.indexOfFirst { !it }
        val gap = spacing.toPx()
        // Android: short of room (a short window with the keys up), the field and its controls
        // get what the rest leaves them, so the field's lines give way first; past its floor the
        // column's top (the attachments) is clipped, never the control row ([ComposerLayout]).
        val squeezes = constraints.hasBoundedHeight && firstStaying >= 0
        val measured = arrayOfNulls<Placeable>(measurables.size)
        measurables.forEachIndexed { index, measurable ->
            if (!squeezes || index != firstStaying) measured[index] = measurable.measure(Constraints(minWidth = 0, maxWidth = width))
        }
        if (squeezes) {
            val rest = measured.sumOf { it?.height ?: 0 } + gap * (measurables.size - 1)
            val room = (constraints.maxHeight - rest).roundToInt().coerceAtLeast(0)
            measured[firstStaying] = measurables[firstStaying].measure(Constraints(minWidth = 0, maxWidth = width, maxHeight = room))
        }
        val placeables = measured.map { it!! }
        // Past its field's floor the field reaches above its top: that reach takes the room
        // above it, so the attachments go before the field does.
        val reaches = placeables.mapIndexed { index, placeable ->
            if (index != firstStaying) 0 else {
                placeable[ComposerOverflowTop].takeIf { it != androidx.compose.ui.layout.AlignmentLine.Unspecified }?.let { -it } ?: 0
            }
        }
        val firstStayingFoldTop = if (firstStaying >= 0) {
            (placeables[firstStaying][ComposerFoldTop].takeIf { it != androidx.compose.ui.layout.AlignmentLine.Unspecified } ?: 0) + reaches[firstStaying]
        } else {
            0
        }
        val sizes = placeables.mapIndexed { index, placeable -> (placeable.height + reaches[index]).toFloat() }
        val p = foldProgress.coerceIn(0f, 1f)
        val result = ComposerFoldFrames.frames(
            sizes, folds, firstStayingFoldTop.toFloat(), isFolded = p >= 1f, foldProgress = p,
            attachmentRevealProgress = attachmentRevealProgress, spacing = gap,
        )
        val upper = result.visible.endInclusive
        val lower = if (constraints.hasBoundedHeight) max(result.visible.start, upper - constraints.maxHeight) else result.visible.start
        val height = (upper - lower).roundToInt().coerceAtLeast(0)
        layout(width, height) {
            placeables.forEachIndexed { index, placeable ->
                placeable.place(0, (result.frames[index] + reaches[index] - lower).roundToInt())
            }
        }
    }
}

private val Measurable.folds: Boolean get() = layoutId == ComposerFoldsMarker

/**
 * iOS `ComposerFoldLayout`'s static geometry: each child's top, and the visible range.
 * `foldProgress` (default: `isFolded`'s end) blends the unfolded and the folded ranges, so the
 * column's height passes through every height in between (iOS f0670c5d).
 */
object ComposerFoldFrames {
    data class Result(val frames: List<Float>, val visible: ClosedFloatingPointRange<Float>)

    fun attachmentRevealProgress(
        locationMounted: Boolean,
        locationPresented: Boolean,
        hasNonLocationAttachments: Boolean,
        reduceMotion: Boolean,
    ): Float = if (reduceMotion || hasNonLocationAttachments || (locationMounted && locationPresented)) 1f else 0f

    fun frames(
        sizes: List<Float>,
        folds: List<Boolean>,
        firstStayingFoldTop: Float = 0f,
        isFolded: Boolean,
        foldProgress: Float? = null,
        attachmentRevealProgress: Float = 1f,
        spacing: Float = 8f,
    ): Result {
        var y = 0f
        val tops = ArrayList<Float>(sizes.size)
        for (size in sizes) {
            if (tops.isNotEmpty()) y += spacing
            tops += y
            y += size
        }
        val fold = (foldProgress ?: if (isFolded) 1f else 0f).coerceIn(0f, 1f)
        val clampedReveal = attachmentRevealProgress.coerceIn(0f, 1f)
        val staying = tops.indices.filter { !folds[it] }
        val first = staying.firstOrNull()
        val unfolded = if (first != null && clampedReveal < 1f) tops[first] * (1 - clampedReveal)..y else 0f..y
        if (fold <= 0f || first == null) return Result(tops, unfolded)
        val last = staying.last()
        val folded = (tops[first] + firstStayingFoldTop)..(tops[last] + sizes[last])
        if (fold >= 1f) return Result(tops, folded)
        val lower = unfolded.start + (folded.start - unfolded.start) * fold
        val upper = unfolded.endInclusive + (folded.endInclusive - unfolded.endInclusive) * fold
        return Result(tops, lower..maxOf(lower, upper))
    }
}

// endregion

// region Trailing control

/** iOS `ComposerTrailingControl`. */
sealed interface ComposerTrailingControl {
    data class Send(val enabled: Boolean) : ComposerTrailingControl
    data object Stop : ComposerTrailingControl
    data object Mic : ComposerTrailingControl
    /** Starts a voice call; holds Send's place while the field is empty. */
    data object Call : ComposerTrailingControl

    val symbolName: String
        get() = when (this) {
            is Send -> "arrow.up"
            Stop -> "stop.fill"
            Mic -> "mic"
            Call -> "phone.fill"
        }

    val accessibilityLabel: String
        get() = when (this) {
            is Send -> "Send message"
            Stop -> "Stop"
            Mic -> "Dictate message"
            Call -> "Start voice call"
        }

    @Composable
    fun foreground(): Color = when (this) {
        Mic -> OpenCodeTheme.colors.secondaryText
        is Send -> if (enabled) OpenCodeTheme.colors.sendButtonForeground else OpenCodeTheme.colors.secondaryText
        Stop, Call -> OpenCodeTheme.colors.canvas
    }

    @Composable
    fun background(): Color = when (this) {
        is Send -> if (enabled) OpenCodeTheme.colors.sendButtonBackground else Color.Transparent
        Stop, Call -> OpenCodeTheme.colors.text
        Mic -> Color.Transparent
    }
}

/** iOS `ComposerControls`: the trailing control and where dictation sits beside it. */
data class ComposerControls(
    val trailing: ComposerTrailingControl,
    val secondaryMic: MicPlacement,
    val dictation: DictationControls?,
) {
    enum class MicPlacement { none, leading, trailing }
    data class DictationControls(val isFinishing: Boolean)

    companion object {
        fun make(
            isDictating: Boolean,
            isFinishing: Boolean,
            isRunning: Boolean,
            canSend: Boolean,
            expanded: Boolean,
            canCall: Boolean = false,
            /** New Plus Menu: an empty composer keeps a dimmed Send in its place instead of the mic. */
            keepsSendVisible: Boolean = false,
            /** New Plus Menu: a call owns the audio, so the tray has the call controls. */
            isInCall: Boolean = false,
        ): ComposerControls {
            val dictation = if (isDictating || isFinishing) DictationControls(isFinishing) else null
            // Empty and idle: the call button holds Send's place and dictation sits beside it.
            // During a run Stop takes the place and the call waits.
            val trailing: ComposerTrailingControl = when {
                canSend -> ComposerTrailingControl.Send(enabled = true)
                isRunning -> ComposerTrailingControl.Stop
                // The tray owns call controls. Keep the composer's Send slot available for typing
                // instead of choosing a Call button the tray then hides.
                isInCall && dictation == null -> ComposerTrailingControl.Send(enabled = false)
                canCall && dictation == null -> ComposerTrailingControl.Call
                keepsSendVisible && dictation == null -> ComposerTrailingControl.Send(enabled = false)
                else -> ComposerTrailingControl.Mic
            }
            val secondaryMic = when {
                dictation != null || trailing == ComposerTrailingControl.Mic -> MicPlacement.none
                expanded -> MicPlacement.leading
                else -> MicPlacement.trailing
            }
            return ComposerControls(trailing, secondaryMic, dictation)
        }
    }
}

// endregion

// region Folded controls

/**
 * iOS `composerHidden(_:fadesOnReveal:)` (`ComposerFoldedControl`): folded away once faded out
 * (under the dictation bar, or a request's + until it's the recording's ×). Its space stays; it
 * leaves TalkBack and stops taking touches. Unfolding fades it in with the row (`Motion.swap`,
 * after `Motion.contentDelay`) unless [fadesOnReveal] is false.
 */
@Composable
fun ComposerHidden(
    hidden: Boolean,
    modifier: Modifier = Modifier,
    fadesOnReveal: Boolean = true,
    content: @Composable () -> Unit,
) {
    val reduceMotion = rememberReduceMotion()
    val alpha = remember { Animatable(if (!hidden || !fadesOnReveal) 1f else 0f) }
    LaunchedEffect(hidden, fadesOnReveal) {
        if (!fadesOnReveal) return@LaunchedEffect alpha.snapTo(1f)
        if (hidden) {
            alpha.snapTo(0f)
        } else if (alpha.value < 1f) {
            alpha.animateTo(1f, Motion.swap.animation(reduceMotion = reduceMotion, delaySeconds = Motion.contentDelay))
        }
    }
    val folded = if (hidden) {
        Modifier
            .alpha(0f)
            .clearAndSetSemantics { }
            .pointerInput(Unit) {
                // Swallow everything aimed at the folded control.
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }
    } else {
        Modifier.alpha(alpha.value)
    }
    Box(modifier.then(folded)) { content() }
}

/** Swallows every touch aimed at this element (iOS `.allowsHitTesting(false)` over controls). */
internal fun Modifier.blockTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
    }
}

// endregion

// region Control label

/**
 * iOS `ComposerControlLabel`: a trailing control's glyph in its circle. The circle grows with the
 * font scale up to 44 dp; the 48 dp target comes from the enclosing control.
 */
@Composable
fun ComposerControlLabel(
    systemImage: String,
    showsProgress: Boolean,
    foreground: Color,
    background: Color,
    modifier: Modifier = Modifier,
    glyphSize: Dp = 18.dp,
    visualOffsetX: Float = 0f,
    glyphRise: Float = 0f,
) {
    val reduceMotion = rememberReduceMotion()
    val fontScale = LocalDensity.current.fontScale
    val diameter = min(34f * fontScale * (ComposerTunables.buttonDiameter / 34f), 44f)
    // Body grows 28/17 by Accessibility 1, where the glyph stops.
    val textScale = min(fontScale, 28f / 17f)
    Box(
        modifier
            .offset(x = visualOffsetX.dp)
            .size(diameter.dp)
            .background(background, OpenCodeShape.circle),
        contentAlignment = Alignment.Center,
    ) {
        if (showsProgress) {
            CircularProgressIndicator(Modifier.size(16.dp), color = foreground, strokeWidth = 2.dp)
        } else {
            AnimatedContent(
                targetState = systemImage,
                transitionSpec = {
                    val spec = if (reduceMotion) tween<Float>(0) else Motion.easeOut<Float>(Motion.fadeFast)
                    fadeIn(spec) togetherWith fadeOut(spec)
                },
                label = "composerControlGlyph",
            ) { symbol ->
                OpenCodeIcon(
                    symbol,
                    contentDescription = null,
                    size = (glyphSize.value * textScale).dp,
                    tint = foreground,
                    modifier = Modifier.offset(y = (-glyphRise * textScale).dp),
                )
            }
        }
    }
}

// endregion

// region Pills

/** iOS `ComposerPillLabel`: a pill above the composer (background work or pending messages). */
@Composable
fun ComposerPillLabel(
    title: String,
    modifier: Modifier = Modifier,
    rolls: Boolean = false,
    onClick: (() -> Unit)? = null,
    /** iOS `accessibilityHint`: TalkBack's click label (“Double tap to …”). */
    onClickLabel: String? = null,
    /** iOS `accessibilityLabel`: read instead of [title], not after it. */
    accessibilityLabel: String? = null,
    leading: @Composable () -> Unit,
) {
    // Slim surface, full 48 dp target; the press shows on the capsule.
    val interactions = remember { MutableInteractionSource() }
    Box(
        modifier
            .then(if (onClick != null) Modifier.clickable(interactionSource = interactions, indication = null, role = Role.Button, onClickLabel = onClickLabel, onClick = onClick) else Modifier)
            .then(if (accessibilityLabel != null) Modifier.semantics { contentDescription = accessibilityLabel } else Modifier)
            .openCodeTapTarget(accessibility = false),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .then(if (accessibilityLabel != null) Modifier.clearAndSetSemantics {} else Modifier)
                .padding(vertical = 5.dp)
                .clip(OpenCodeShape.capsule)
                .indication(interactions, ripple()),
            shape = OpenCodeShape.capsule,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 2.dp,
            shadowElevation = 2.dp,
        ) {
            ComposerPillContent(title = title, rolls = rolls, leading = leading)
        }
    }
}

/** iOS `ComposerPillContent`: a pill's glyph and title, without its surface. No chevron. */
@Composable
fun ComposerPillContent(title: String, rolls: Boolean = false, leading: @Composable () -> Unit) {
    val style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")
    Row(
        Modifier
            .heightIn(min = 34.dp)
            .padding(start = ComposerTunables.pillGlyphLeading.dp, end = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(ComposerTunables.pillGlyphGap.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(18.dp).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
            CompositionLocalProvider(LocalContentColor provides OpenCodeTheme.colors.text) { leading() }
        }
        if (rolls) {
            RollingNumber(text = title, style = style, color = OpenCodeTheme.colors.text)
        } else {
            Text(title, style = style, color = OpenCodeTheme.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * iOS `JumpToLatestPill`: scales and fades in and out on a spring, retargeting mid-flight; it
 * leaves the tree (and TalkBack) once its outro settles. In a pill row beside the queued pill its
 * slot (the gap before it included) opens and closes on the same spring. Reduce Motion cross-fades.
 * While [veiled] (read when drawn) it keeps its state but isn't drawn, touched or read out (AI-01).
 */
@Composable
fun JumpToLatestPill(
    isShown: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    collapsesSlot: Boolean = false,
    veiled: () -> Boolean = { false },
) {
    val reduceMotion = rememberReduceMotion()
    val presence = remember { Animatable(if (isShown) 1f else 0f) }
    var mounted by remember { mutableStateOf(isShown) }
    LaunchedEffect(isShown, reduceMotion) {
        if (isShown) mounted = true
        val spec = Motion.microIn.animation<Float>(if (isShown) MotionPhase.Enter else MotionPhase.Exit, reduceMotion)
        presence.animateTo(if (isShown) 1f else 0f, spec)
        if (!isShown) mounted = false
    }
    val slotOpen = if (reduceMotion) (if (isShown) 1f else 0f) else presence.value.coerceIn(0f, 1f)
    val interactions = remember { MutableInteractionSource() }
    val slotWidth = if (collapsesSlot) (48f + JumpToLatestPillMotion.gap) * slotOpen else 48f
    // iOS `allowsHitTesting(isShown)`: only a shown pill takes touches. Leaving (its fade-out) or
    // gone, a tap or a drag reaches the transcript under it; the 48 dp slot never takes any, as
    // iOS's empty frame. A finger already down on the pill keeps [modifier] (the borrowed drag)
    // to release as the pill leaves under it (iOS `PassesDragsToTranscript.isDragging`).
    var touched by remember { mutableStateOf(false) }
    val tracksTouch = remember {
        Modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                touched = true
                try {
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                    } while (event.changes.any { it.pressed })
                } finally {
                    touched = false
                }
            }
        }
    }
    Box(Modifier.width(slotWidth.dp).heightIn(min = 48.dp), contentAlignment = Alignment.CenterEnd) {
        Box(if (isShown || touched) modifier.then(tracksTouch) else Modifier) {
            if (mounted) {
                val p = presence.value
                Box(
                    Modifier
                        .graphicsLayer { if (veiled()) alpha = 0f }
                        .scale(if (reduceMotion) 1f else 0.5f + 0.5f * p)
                        .alpha(p.coerceIn(0f, 1f))
                        .coveredByBackgroundWork()
                        .then(
                            if (isShown && !veiled()) {
                                Modifier
                                    .clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onClick)
                                    .semantics { contentDescription = "Scroll to latest" }
                                    .testTag("scroll-to-latest")
                            } else {
                                Modifier.clearAndSetSemantics { }
                            },
                        )
                        .openCodeTapTarget(accessibility = false),
                    contentAlignment = Alignment.Center,
                ) {
                    // Android: drawn as an M3 small FAB (40 dp, secondary container) rather than
                    // iOS's grey glass circle. Not an M3 Surface or FAB composable: those swallow
                    // every touch on them, fading or not.
                    Box(
                        Modifier
                            .size(40.dp)
                            .shadow(1.dp, FloatingActionButtonDefaults.smallShape)
                            .clip(FloatingActionButtonDefaults.smallShape)
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .indication(interactions, ripple()),
                        contentAlignment = Alignment.Center,
                    ) {
                        OpenCodeIcon(SFSymbol.ArrowDown, contentDescription = null, size = 24.dp, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
        }
    }
}

/** [JumpToLatestPill]'s slot gap; it scales and fades in and out on `microIn` and `microOut`. */
object JumpToLatestPillMotion {
    const val gap = 8f
}

/** iOS `ComposerBanner`: a full-width bar that takes the composer's place, on its same surface. */
@Composable
fun ComposerBanner(
    symbol: SFSymbol,
    title: String,
    action: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconTint: Color = OpenCodeTheme.colors.secondaryText,
    detail: String? = null,
) {
    // The pressed radius must fit even the minimum-height banner.
    val radius = OpenCodeShapeGeometry.radius(28f, 52f)
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = RoundedCornerShape(radius.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OpenCodeIcon(symbol, contentDescription = null, size = 18.dp, tint = iconTint, modifier = Modifier.width(18.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.Medium), color = OpenCodeTheme.colors.text)
                if (detail != null) {
                    Text(detail, style = OpenCodeTextStyles.caption1, color = OpenCodeTheme.colors.secondaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.widthIn(min = 8.dp))
            Text(action, style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.primary)
        }
    }
}

// endregion

/**
 * iOS `HorizontalScrollEdgeFade`: fades a horizontal list's leading and trailing edges, each only
 * while content runs past it, instead of clipping hard.
 */
@Composable
fun Modifier.horizontalScrollEdgeFade(state: LazyListState, width: Dp = 20.dp, isEnabled: Boolean = true): Modifier {
    if (!isEnabled) return this
    val fade = Motion.swap.animation<Float>(reduceMotion = rememberReduceMotion())
    val leading by animateFloatAsState(if (state.canScrollBackward) 1f else 0f, fade, label = "edgeFadeLeading")
    val trailing by animateFloatAsState(if (state.canScrollForward) 1f else 0f, fade, label = "edgeFadeTrailing")
    return graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val edge = width.toPx().coerceAtMost(size.width / 2)
            if (leading > 0f) {
                drawRect(
                    Brush.horizontalGradient(0f to Color.Black.copy(alpha = 1f - leading), 1f to Color.Black, startX = 0f, endX = edge),
                    size = Size(edge, size.height),
                    blendMode = BlendMode.DstIn,
                )
            }
            if (trailing > 0f) {
                drawRect(
                    Brush.horizontalGradient(0f to Color.Black, 1f to Color.Black.copy(alpha = 1f - trailing), startX = size.width - edge, endX = size.width),
                    topLeft = Offset(size.width - edge, 0f),
                    size = Size(edge, size.height),
                    blendMode = BlendMode.DstIn,
                )
            }
        }
}

/**
 * iOS `ComposerSendLabel`: New Plus Menu's one fixed Send. The enabled disc and arrow are composited
 * before dimming, so the arrow stays its colour as the empty button fades to [ComposerTunables.emptySendOpacity].
 */
@Composable
fun ComposerSendLabel(enabled: Boolean, showsProgress: Boolean, visualOffsetX: Float, modifier: Modifier = Modifier) {
    val reduceMotion = rememberReduceMotion()
    val alpha by animateFloatAsState(
        if (enabled) 1f else ComposerTunables.emptySendOpacity,
        Motion.swap.animation(if (enabled) MotionPhase.Enter else MotionPhase.Exit, reduceMotion),
        label = "sendLabelAlpha",
    )
    ComposerControlLabel(
        systemImage = "arrow.up",
        showsProgress = showsProgress,
        foreground = OpenCodeTheme.colors.sendButtonForeground,
        background = OpenCodeTheme.colors.sendButtonBackground,
        visualOffsetX = visualOffsetX,
        modifier = modifier.graphicsLayer {
            this.alpha = alpha
            compositingStrategy = CompositingStrategy.ModulateAlpha
        },
    )
}
