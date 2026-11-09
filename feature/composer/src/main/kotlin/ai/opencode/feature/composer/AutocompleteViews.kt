package ai.opencode.feature.composer

import ai.opencode.core.motion.transitionBlur

import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

// MARK: - Kind icons

/**
 * Filled 24×24 kind icons ported from the Round 2 web study (`autocomplete.js` `ICON`), iOS
 * `AutocompleteKindIcon`: filled wrench (skill), person (subagent), file page (file), speech
 * bubble (session), slash square (command), magnifier (no matches). Every glyph is drawn on a
 * 24 dp grid with even-odd fill at the same optical weight. Rows use 22 dp, chips 14 dp
 * ([AutocompleteChipMetrics.iconSize]).
 */
@Composable
fun AutocompleteKindIcon(
    kind: AutocompleteItem.Kind,
    size: Double = 22.0,
    color: Color = OpenCodeTheme.colors.text,
    modifier: Modifier = Modifier,
) {
    val path = remember(kind) { AutocompleteKindPaths.path(kind) }
    Canvas(modifier.size(size.dp).clearAndSetSemantics {}) {
        scale(this.size.width / 24f, pivot = Offset.Zero) {
            drawPath(path, color)
        }
    }
}

/** The 24-unit paths behind [AutocompleteKindIcon] (iOS `AutocompleteKindIcon.cgPath(for:size:)`). */
object AutocompleteKindPaths {
    private val cache = HashMap<AutocompleteItem.Kind, Path>()

    /** The glyph for [kind] on the 24-unit grid, even-odd filled. */
    fun path(kind: AutocompleteItem.Kind): Path = synchronized(cache) { cache.getOrPut(kind) { build(kind) } }

    /** A `CGMutablePath`-like builder that tracks the current point for tangent arcs. */
    private class Builder(val path: Path = Path()) {
        var cx = 0f
        var cy = 0f

        fun move(x: Double, y: Double) {
            path.moveTo(x.toFloat(), y.toFloat()); cx = x.toFloat(); cy = y.toFloat()
        }

        fun line(x: Double, y: Double) {
            path.lineTo(x.toFloat(), y.toFloat()); cx = x.toFloat(); cy = y.toFloat()
        }

        fun curve(x: Double, y: Double, c1x: Double, c1y: Double, c2x: Double, c2y: Double) {
            path.cubicTo(c1x.toFloat(), c1y.toFloat(), c2x.toFloat(), c2y.toFloat(), x.toFloat(), y.toFloat())
            cx = x.toFloat(); cy = y.toFloat()
        }

        /**
         * `CGMutablePath.addArc(center:radius:startAngle:endAngle:clockwise:)`: `clockwise = false`
         * sweeps toward increasing angles, `true` toward decreasing ones (the path's own
         * coordinates; y points down here as in UIKit drawing). Joins with a line from the
         * current point.
         */
        fun arc(centerX: Double, centerY: Double, radius: Double, start: Double, end: Double, clockwise: Boolean) {
            var sweep = end - start
            if (!clockwise) {
                while (sweep < 0) sweep += 2 * PI
            } else {
                while (sweep > 0) sweep -= 2 * PI
            }
            val rect = Rect(
                (centerX - radius).toFloat(),
                (centerY - radius).toFloat(),
                (centerX + radius).toFloat(),
                (centerY + radius).toFloat(),
            )
            path.arcToRad(rect, start.toFloat(), sweep.toFloat(), forceMoveTo = false)
            cx = (centerX + radius * cos(end)).toFloat()
            cy = (centerY + radius * sin(end)).toFloat()
        }

        /** `CGMutablePath.addArc(tangent1End:tangent2End:radius:)`. */
        fun tangentArc(t1x: Double, t1y: Double, t2x: Double, t2y: Double, radius: Double) {
            val p0x = cx.toDouble()
            val p0y = cy.toDouble()
            val v1x = p0x - t1x
            val v1y = p0y - t1y
            val v2x = t2x - t1x
            val v2y = t2y - t1y
            val l1 = hypot(v1x, v1y)
            val l2 = hypot(v2x, v2y)
            if (l1 < 1e-9 || l2 < 1e-9 || radius <= 0) {
                line(t1x, t1y)
                return
            }
            val u1x = v1x / l1
            val u1y = v1y / l1
            val u2x = v2x / l2
            val u2y = v2y / l2
            val cosTheta = (u1x * u2x + u1y * u2y).coerceIn(-1.0, 1.0)
            val theta = kotlin.math.acos(cosTheta)
            if (theta < 1e-6 || abs(theta - PI) < 1e-6) {
                line(t1x, t1y)
                return
            }
            val d = radius / tan(theta / 2)
            val ax = t1x + u1x * d
            val ay = t1y + u1y * d
            val bx = t1x + u2x * d
            val by = t1y + u2y * d
            val bisX = u1x + u2x
            val bisY = u1y + u2y
            val bisL = hypot(bisX, bisY)
            val centerDistance = radius / sin(theta / 2)
            val ccx = t1x + bisX / bisL * centerDistance
            val ccy = t1y + bisY / bisL * centerDistance
            line(ax, ay)
            val start = atan2(ay - ccy, ax - ccx)
            val end = atan2(by - ccy, bx - ccx)
            var sweep = end - start
            while (sweep > PI) sweep -= 2 * PI
            while (sweep < -PI) sweep += 2 * PI
            val rect = Rect((ccx - radius).toFloat(), (ccy - radius).toFloat(), (ccx + radius).toFloat(), (ccy + radius).toFloat())
            path.arcToRad(rect, start.toFloat(), sweep.toFloat(), forceMoveTo = false)
            cx = bx.toFloat(); cy = by.toFloat()
        }

        fun close() = path.close()
    }

    private fun build(kind: AutocompleteItem.Kind): Path {
        val base = Builder()
        when (kind) {
            AutocompleteItem.Kind.Skill -> {
                // Upright adjustable wrench (`d="M10 1.88V5.5a2 2 0 0 0 4 0V1.88A5.5 5.5 0 0 1 13.9 12.16V20.1a1.9 1.9 0 0 1-3.8 0V12.16A5.5 5.5 0 0 1 10 1.88Z"`)
                // rotated 45° around (12, 12), translated (-0.48, -0.22), scaled 1.04.
                val wrench = Builder()
                val hx = 12.0
                val hy = 7.02
                val headRadius = 5.5
                val startRight = atan2(1.88 - hy, 14.0 - hx)
                val endRight = atan2(12.16 - hy, 13.9 - hx)
                val startLeft = atan2(12.16 - hy, 10.1 - hx)
                val endLeft = atan2(1.88 - hy, 10.0 - hx)
                wrench.move(10.0, 1.88)
                wrench.line(10.0, 5.5)
                wrench.arc(12.0, 5.5, 2.0, PI, 0.0, clockwise = true)
                wrench.line(14.0, 1.88)
                wrench.arc(hx, hy, headRadius, startRight, endRight, clockwise = false)
                wrench.line(13.9, 20.1)
                wrench.arc(12.0, 20.1, 1.9, 0.0, PI, clockwise = false)
                wrench.line(10.1, 12.16)
                wrench.arc(hx, hy, headRadius, startLeft, endLeft, clockwise = false)
                wrench.close()
                // T(12,12) · R(45°) · T(-12,-12) · T(-0.48,-0.22) · S(1.04), as CG composes it.
                val angle = PI / 4
                val s = 1.04
                val c = cos(angle)
                val n = sin(angle)
                val matrix = Matrix()
                matrix.values[Matrix.ScaleX] = (s * c).toFloat()
                matrix.values[Matrix.SkewX] = (-s * n).toFloat()
                matrix.values[Matrix.SkewY] = (s * n).toFloat()
                matrix.values[Matrix.ScaleY] = (s * c).toFloat()
                matrix.values[Matrix.TranslateX] = (c * -12.48 - n * -12.22 + 12).toFloat()
                matrix.values[Matrix.TranslateY] = (n * -12.48 + c * -12.22 + 12).toFloat()
                wrench.path.transform(matrix)
                base.path.addPath(wrench.path)
            }

            AutocompleteItem.Kind.Agent -> {
                // `<circle cx="12" cy="7.6" r="4.1"/><path d="M4.6 19.4c0-3.9 3.3-6.4 7.4-6.4s7.4 2.5 7.4 6.4a1.1 1.1 0 0 1-1.1 1.1H5.7a1.1 1.1 0 0 1-1.1-1.1Z"/>`
                base.path.addOval(Rect(12f - 4.1f, 7.6f - 4.1f, 12f + 4.1f, 7.6f + 4.1f))
                base.move(4.6, 19.4)
                base.curve(12.0, 13.0, 4.6, 15.5, 7.9, 13.0)
                base.curve(19.4, 19.4, 16.1, 13.0, 19.4, 15.5)
                base.arc(18.3, 19.4, 1.1, 0.0, PI / 2, clockwise = false)
                base.line(5.7, 20.5)
                base.arc(5.7, 19.4, 1.1, PI / 2, PI, clockwise = false)
                base.close()
            }

            AutocompleteItem.Kind.File -> {
                base.move(7.2, 3.0)
                base.line(13.5, 3.0)
                base.line(13.5, 7.7)
                base.curve(15.1, 9.3, 13.5, 8.6, 14.2, 9.3)
                base.line(19.5, 9.3)
                base.line(19.5, 19.0)
                base.arc(17.5, 19.0, 2.0, 0.0, PI / 2, clockwise = false)
                base.line(7.2, 21.0)
                base.arc(7.2, 19.0, 2.0, PI / 2, PI, clockwise = false)
                base.line(5.2, 5.0)
                base.arc(7.2, 5.0, 2.0, PI, -PI / 2, clockwise = false)
                base.close()

                base.move(15.0, 3.4)
                base.line(19.1, 7.8)
                base.line(15.5, 7.8)
                base.arc(15.5, 7.3, 0.5, PI / 2, PI, clockwise = false)
                base.close()
            }

            AutocompleteItem.Kind.Session -> {
                // Speech bubble: a rounded body with its tail at the lower left, one contour.
                base.move(7.6, 4.0)
                base.tangentArc(20.6, 4.0, 20.6, 16.6, 4.2)
                base.tangentArc(20.6, 16.6, 3.4, 16.6, 4.2)
                base.line(12.4, 16.6)
                base.line(7.4, 20.6)
                base.line(8.2, 16.6)
                base.tangentArc(3.4, 16.6, 3.4, 4.0, 4.2)
                base.tangentArc(3.4, 4.0, 20.6, 4.0, 4.2)
                base.close()
            }

            AutocompleteItem.Kind.Command -> {
                base.path.addRoundRect(RoundRect(3.6f, 3.6f, 3.6f + 16.8f, 3.6f + 16.8f, CornerRadius(4.8f, 4.8f)))
                base.move(13.9, 7.1)
                base.line(15.6, 7.9)
                base.line(10.1, 16.9)
                base.line(8.4, 16.1)
                base.close()
            }

            AutocompleteItem.Kind.Empty -> {
                base.path.addOval(Rect(3.5f, 3.5f, 17.5f, 17.5f))
                base.path.addOval(Rect(6.0f, 6.0f, 15.0f, 15.0f))
                base.move(16.5, 14.8)
                base.line(20.8, 19.1)
                base.arc(19.95, 19.95, 1.2, -PI / 4, 3 * PI / 4, clockwise = false)
                base.line(14.8, 16.5)
                base.close()
            }
        }
        base.path.fillType = PathFillType.EvenOdd
        return base.path
    }
}

// MARK: - Shared colours

private fun rgb(r: Int, g: Int, b: Int) = Color(r, g, b)

private fun tone(value: Double): Color {
    val t = value.roundToInt().coerceIn(0, 255)
    return Color(t, t, min(255, t + 3))
}

// MARK: - Panel

/**
 * The growing autocomplete list mounted in the composer surface's panel slot (iOS
 * `ComposerAutocompletePanel`, mounted in `ComposerSurface`'s `panel`). Rows run bottom-up
 * from the composer text; the highlight sits concentric with the glass; edge fades show only
 * where rows overflow. One pointer handler owns the list (OC-474): a touch scrolls once it
 * passes the tap slop and never picks a row after that; a touch that stays within it picks the
 * row it lifts on. TalkBack activates rows through their click action.
 */
@Composable
fun ComposerAutocompletePanel(
    controller: AutocompleteMotionController,
    onSelectRow: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scale = max(density.density.toDouble(), 1.0)
    LaunchedEffect(scale) { controller.updateDisplayScale(scale) }
    val frame = controller.currentFrame
    val params = controller.simulation.params
    val panelHeight = AutocompleteSimulation.snapAnchoredSpan(0.0, max(frame.glassLift, frame.restingLift), scale).span
    val pad = AutocompleteSimulation.snapEdge(frame.padding, scale)
    Box(
        modifier
            .fillMaxWidth()
            .height(panelHeight.dp)
            .semantics {
                isTraversalGroup = true
                contentDescription = "Autocomplete suggestions"
            }
            .testTag("composer-autocomplete-list"),
        contentAlignment = Alignment.BottomStart,
    ) {
        if (frame.isShown && frame.listHeight > 0.5) {
            Box(
                Modifier
                    .padding(start = pad.dp, end = pad.dp, top = pad.dp)
                    .graphicsLayer {
                        scaleX = frame.contentScale.toFloat()
                        scaleY = frame.contentScale.toFloat()
                        transformOrigin = TransformOrigin(0.5f, 1f)
                        alpha = frame.contentAlpha.toFloat()
                    }
                    .then(if (frame.contentBlur > 0.01) Modifier.transitionBlur(frame.contentBlur.dp) else Modifier),
            ) {
                AutocompleteListWindow(controller, frame, params, scale, onSelectRow)
            }
        }
    }
}

@Composable
private fun AutocompleteListWindow(
    controller: AutocompleteMotionController,
    frame: AutocompleteSimulation.Frame,
    params: AutocompleteParameters,
    scale: Double,
    onSelectRow: (String) -> Unit,
) {
    val isDark = OpenCodeTheme.colors.isDark
    val listWidth = max(1.0, AutocompleteSimulation.snapEdge(frame.listWidth, scale))
    val listHeight = max(1.0, AutocompleteSimulation.snapAnchoredSpan(0.0, frame.listHeight, scale).span)
    val currentOnSelect by rememberUpdatedState(onSelectRow)
    val edge = frame.edgeFade
    val fades = edge.topEdgeAlpha < 1 || edge.bottomEdgeAlpha < 1
    Box(
        Modifier
            .requiredSize(listWidth.dp, listHeight.dp)
            .graphicsLayer {
                clip = true
                if (fades) compositingStrategy = CompositingStrategy.Offscreen
            }
            .then(
                if (fades) {
                    Modifier.drawWithContent {
                        drawContent()
                        val h = max(size.height, 1f)
                        val fade = max(1.0, min(edge.fadeLength * density, h / 2.0)).toFloat()
                        val topStop = min(0.49f, max(0.001f, fade / h))
                        val bottomStop = max(0.51f, min(0.999f, 1 - fade / h))
                        drawRect(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = edge.topEdgeAlpha.toFloat()),
                                topStop to Color.Black,
                                bottomStop to Color.Black,
                                1f to Color.Black.copy(alpha = edge.bottomEdgeAlpha.toFloat()),
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                } else {
                    Modifier
                },
            )
            .pointerInput(controller) {
                val tracker = AutocompleteTouchTracker()
                fun Offset.point() = AutocompletePoint((x / density).toDouble(), (y / density).toDouble())
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val start = down.position.point()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null) {
                            controller.endScroll()
                            break
                        }
                        val location = change.position.point()
                        if (!change.pressed) {
                            controller.endScroll()
                            val tap = tracker.end(start, location)
                            if (tap != null) {
                                AutocompleteTouchTracker.selectableRow(tap, controller.currentFrame.rows)?.let { currentOnSelect(it) }
                            }
                            change.consume()
                            break
                        }
                        tracker.move(start, location)?.let { controller.scroll(it) }
                        if (tracker.isScrolling) change.consume()
                    }
                }
            },
    ) {
        val hi = frame.highlight
        if (hi != null && hi.alpha > 0.002) {
            val hiRect = AutocompleteSimulation.snapRectEdges(hi.rect, scale)
            if (hiRect.width > 0.5 && hiRect.height > 0.5) {
                Box(
                    Modifier
                        .offset(hiRect.minX.dp, hiRect.minY.dp)
                        .requiredSize(hiRect.width.dp, hiRect.height.dp)
                        .alpha(hi.alpha.toFloat())
                        .background(
                            if (isDark) Color.White.copy(alpha = 0.11f) else Color.Black.copy(alpha = 0.07f),
                            RoundedCornerShape(hi.cornerRadius.dp),
                        )
                        .clearAndSetSemantics {},
                )
            }
        }
        // Leaving rows draw beneath entering ones (iOS `zIndex(row.isLeaving ? 0 : 1)`).
        val ordered = frame.rows.filter { it.isLeaving } + frame.rows.filter { !it.isLeaving }
        for (row in ordered) {
            if (row.alpha > 0.002 && row.rect.width > 0.2 && row.rect.height > 0.2) {
                key(row.id) {
                    AutocompleteRowSlot(row, params, scale, isDark) { currentOnSelect(row.id) }
                }
            }
        }
    }
}

@Composable
private fun AutocompleteRowSlot(
    row: AutocompleteSimulation.DrawnRow,
    params: AutocompleteParameters,
    scale: Double,
    isDark: Boolean,
    onSelect: () -> Unit,
) {
    val slotRect = AutocompleteSimulation.snapRectEdges(row.rect, scale)
    val fullWidth = max(1.0, AutocompleteSimulation.snapEdge(row.fullSize.width, scale))
    val fullHeight = max(1.0, AutocompleteSimulation.snapEdge(row.fullSize.height, scale))
    val innerOffsetX = AutocompleteSimulation.snapEdge((slotRect.width - fullWidth) / 2, scale)
    val innerOffsetY = AutocompleteSimulation.snapEdge((slotRect.height - fullHeight) / 2, scale)
    val activatable = row.item.isSelectable && !row.isLeaving
    val label = row.item.accessibilityLabel
    Box(
        Modifier
            .offset(slotRect.minX.dp, slotRect.minY.dp)
            .requiredSize(slotRect.width.dp, slotRect.height.dp)
            .clip(RoundedCornerShape(row.cornerRadius.dp))
            .then(if (row.blur > 0.01) Modifier.transitionBlur(row.blur.dp) else Modifier)
            .alpha(row.alpha.toFloat())
            .clearAndSetSemantics {
                contentDescription = label
                if (activatable) {
                    role = Role.Button
                    selected = row.isHighlighted
                    onClick { onSelect(); true }
                }
            }
            .testTag("autocomplete-row-${row.id}"),
        contentAlignment = Alignment.TopStart,
    ) {
        Box(
            Modifier
                .offset(innerOffsetX.dp, innerOffsetY.dp)
                .requiredSize(fullWidth.dp, fullHeight.dp)
                .graphicsLayer {
                    scaleX = row.scale.toFloat()
                    scaleY = row.scale.toFloat()
                },
        ) {
            AutocompleteRowContent(row.item, params, isDark)
        }
    }
}

@Composable
private fun AutocompleteRowContent(item: AutocompleteItem, params: AutocompleteParameters, isDark: Boolean) {
    val colors = OpenCodeTheme.colors
    val isMuted = item.kind == AutocompleteItem.Kind.Empty
    val iconColor = when {
        isMuted -> colors.secondaryText
        isDark -> rgb(214, 214, 218)
        else -> colors.text.copy(alpha = colors.text.alpha * 0.82f)
    }
    val titleColor = when {
        isMuted -> colors.secondaryText
        isDark -> rgb(242, 242, 244)
        else -> colors.text
    }
    val kindColor = if (isDark) rgb(139, 139, 144) else colors.secondaryText
    Row(
        Modifier.fillMaxSize().padding(horizontal = params.rowInset.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AutocompleteKindIcon(item.kind, params.iconSize, iconColor)
        Spacer(Modifier.width(10.dp))
        // iOS `HStack { title; Spacer(minLength: 8); label }`: SwiftUI offers the room to the
        // least flexible text first, an equal share each, so a short command name stays whole
        // and a long description truncates (a weighted title let the label take it all).
        TitleAndLabel(
            title = {
                Text(
                    item.title,
                    style = OpenCodeTextStyles.body,
                    color = titleColor,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            label = if (item.label.isEmpty()) null else ({
                Text(
                    item.label,
                    style = OpenCodeTextStyles.subheadline.copy(fontFeatureSettings = "tnum"),
                    color = kindColor,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }),
            // HStack(spacing: 10) around Spacer(minLength: 8): at least 10 + 8 + 10.
            gap = 28.dp,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Lays out [title] leading and [label] trailing on one line, as SwiftUI's HStack sizes two
 * one-line texts: the one with the narrower ideal width is offered half the room first and
 * takes what it needs; the other gets the rest. Either truncates only past that.
 */
@Composable
private fun TitleAndLabel(
    title: @Composable () -> Unit,
    label: (@Composable () -> Unit)?,
    gap: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    androidx.compose.ui.layout.Layout(
        contents = listOf(title, label ?: {}),
        modifier = modifier,
    ) { (titles, labels), constraints ->
        val width = constraints.maxWidth
        val t = titles.first()
        val l = labels.firstOrNull()
        if (l == null) {
            val placeable = t.measure(constraints.copy(minWidth = 0))
            return@Layout layout(width, placeable.height) { placeable.place(0, 0) }
        }
        val room = (width - gap.roundToPx()).coerceAtLeast(0)
        val titleIdeal = t.maxIntrinsicWidth(Int.MAX_VALUE)
        val labelIdeal = l.maxIntrinsicWidth(Int.MAX_VALUE)
        val (titleWidth, labelWidth) = if (titleIdeal <= labelIdeal) {
            val first = minOf(titleIdeal, room / 2)
            first to minOf(labelIdeal, room - first)
        } else {
            val first = minOf(labelIdeal, room / 2)
            minOf(titleIdeal, room - first) to first
        }
        val tp = t.measure(androidx.compose.ui.unit.Constraints(maxWidth = titleWidth.coerceAtLeast(0)))
        val lp = l.measure(androidx.compose.ui.unit.Constraints(maxWidth = labelWidth.coerceAtLeast(0)))
        val height = maxOf(tp.height, lp.height)
        layout(width, height) {
            tp.place(0, (height - tp.height) / 2)
            lp.place(width - lp.width, (height - lp.height) / 2)
        }
    }
}

// MARK: - Chip flight

/**
 * Renders in-flight chips lifting off from the chosen highlight row and landing on their
 * inline chip slot in the composer (iOS `AutocompleteFlightOverlay`). Solid opaque throughout.
 * Flight rects are composer-local dp (rows sit above, at negative y); the overlay shifts them
 * by where the composer ([AutocompleteMotionController.composerAnchor]) sits inside it, so it
 * can be mounted over the whole surface (the rows included). Never clips; not hit-testable.
 */
@Composable
fun AutocompleteFlightOverlay(controller: AutocompleteMotionController, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val scale = max(density.density.toDouble(), 1.0)
    val frame = controller.currentFrame
    val params = controller.simulation.params
    var origin by remember { mutableStateOf(Offset.Zero) }
    if (frame.flights.isEmpty()) return
    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { overlay ->
                val composer = controller.composerAnchor?.invoke()
                origin = if (composer != null && overlay.isAttached) overlay.localPositionOf(composer, Offset.Zero) else Offset.Zero
            }
            .graphicsLayer { translationX = origin.x; translationY = origin.y }
            .clearAndSetSemantics {},
    ) {
        for (flight in frame.flights) {
            key(flight.id) { AutocompleteFlightView(flight, params, scale) }
        }
    }
}

@Composable
private fun AutocompleteFlightView(flight: AutocompleteSimulation.ChipFlight, params: AutocompleteParameters, scale: Double) {
    val colors = OpenCodeTheme.colors
    val isDark = colors.isDark
    val rect = flight.rect(scale)
    val t = flight.smoothProgress
    val inset = params.rowInset + (AutocompleteChipMetrics.leadingPad - params.rowInset) * t
    val iconSize = params.iconSize + (AutocompleteChipMetrics.iconSize - params.iconSize) * t
    val gap = 10 + (AutocompleteChipMetrics.gap - 10) * t
    // The composer's body size to the chip's; sp scales with the font size setting like iOS's @ScaledMetric.
    val bodySize = OpenCodeTextStyles.body.fontSize.value
    val fontSize = bodySize + (AutocompleteChipMetrics.fontSize - bodySize) * t
    val hiTone = if (isDark) AutocompleteChipMetrics.highlightToneDark else AutocompleteChipMetrics.highlightToneLight
    val chipTone = if (isDark) AutocompleteChipMetrics.chipToneDark else AutocompleteChipMetrics.chipToneLight
    val fill = tone(hiTone + (chipTone - hiTone) * t)
    val iconColor = if (isDark) rgb(212, 212, 216) else colors.text.copy(alpha = colors.text.alpha * 0.8f)
    val textColor = if (isDark) rgb(242, 242, 244) else colors.text
    val kindColor = if (isDark) rgb(139, 139, 144) else colors.secondaryText
    Row(
        Modifier
            .offset { IntOffset((rect.minX * density).roundToInt(), (rect.minY * density).roundToInt()) }
            .requiredSize(max(0.0, rect.width).dp, max(0.0, rect.height).dp)
            .clip(RoundedCornerShape(flight.currentRadius.dp))
            .background(fill)
            .padding(start = inset.dp, end = AutocompleteChipMetrics.trailingPad.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AutocompleteKindIcon(flight.item.kind, iconSize, iconColor)
        Spacer(Modifier.width(gap.dp))
        Text(
            flight.item.title,
            style = TextStyle(fontSize = fontSize.sp, fontWeight = FontWeight.Medium),
            color = textColor,
            maxLines = 1,
            softWrap = false,
        )
        Spacer(Modifier.weight(1f))
        if (flight.item.label.isNotEmpty() && flight.kindAlpha > 0.01) {
            Spacer(Modifier.width(gap.dp))
            Text(
                flight.item.label,
                modifier = Modifier.alpha(flight.kindAlpha.toFloat()),
                style = OpenCodeTextStyles.subheadline,
                color = kindColor,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

// MARK: - Inline chips

/** The inline chip font: 15 sp medium (iOS `AutocompleteChipMetrics.scaledFont`, subheadline-scaled). */
val AutocompleteChipTextStyle: TextStyle = TextStyle(fontSize = AutocompleteChipMetrics.fontSize.sp, fontWeight = FontWeight.Medium)

/**
 * A solid opaque inline chip placed over its token range in the composer text (iOS
 * `AutocompleteChipView`). Tapping it removes the chip; TalkBack reads its kind and title.
 */
@Composable
fun AutocompleteChip(
    chip: ComposerAutocompleteChip,
    onActivate: (UUID) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = OpenCodeTheme.colors
    val isDark = colors.isDark
    val background = tone(if (isDark) AutocompleteChipMetrics.chipToneDark else AutocompleteChipMetrics.chipToneLight)
    val iconColor = if (isDark) rgb(212, 212, 216) else colors.text.copy(alpha = colors.text.alpha * 0.82f)
    val titleColor = if (isDark) rgb(242, 242, 244) else colors.text
    val label = chip.item.accessibilityLabel
    Row(
        modifier
            .clip(RoundedCornerShape(AutocompleteChipMetrics.cornerRadius.dp))
            .background(background)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = "Remove",
                role = Role.Button,
            ) { onActivate(chip.id) }
            .semantics(mergeDescendants = true) { contentDescription = label }
            .testTag("composer-chip-${chip.item.id}")
            .padding(start = AutocompleteChipMetrics.leadingPad.dp, end = AutocompleteChipMetrics.trailingPad.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AutocompleteKindIcon(chip.item.kind, AutocompleteChipMetrics.iconSize, iconColor)
        Spacer(Modifier.width(AutocompleteChipMetrics.gap.dp))
        Text(
            chip.item.title,
            modifier = Modifier.clearAndSetSemantics {},
            style = AutocompleteChipTextStyle,
            color = titleColor,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * How the draft is drawn while Composer Autocomplete is on: the active trigger tinted, and
 * each chip token's ink hidden, kept on one line and padded to its chip's exact width so the
 * chip overlay covers it (iOS `applyTextStyling(in:)`).
 */
data class AutocompleteTextStyling(
    val text: String,
    val trigger: AutocompleteChipRun?,
    val triggerColor: Color,
    val chips: List<AutocompleteChipRun>,
    /** Chips drawn from the start of the next line: the space before each becomes a line break. */
    val breaksBefore: Set<UUID> = emptySet(),
    /** The width of the space before a chip in the base font (px), for [ComposerAutocompleteTextBridge.updateLineBreaks]. */
    val spaceWidthPx: Float = 0f,
) {
    /**
     * The text as the field lays it out: [text] with the same-length substitutions the
     * transformations make (non-breaking chip tokens, line breaks before [breaksBefore]).
     */
    val visualText: String by lazy {
        val chars = text.toCharArray()
        for (run in chips) {
            if (run.end > chars.size || run.end <= run.start) continue
            text.substring(run.start, run.end).toNonBreaking().forEachIndexed { i, ch -> chars[run.start + i] = ch }
            if (run.chip?.id in breaksBefore && run.start > 0 && text[run.start - 1] == ' ') chars[run.start - 1] = '\n'
        }
        String(chars)
    }
}

/** One styled run: a trigger or a chip token at UTF-16 `start until end`. */
data class AutocompleteChipRun(
    val chip: ComposerAutocompleteChip?,
    val start: Int,
    val end: Int,
    /** The chip's full width in px (0 for a trigger). */
    val chipWidthPx: Float = 0f,
    /** The token's shrunk font size when it was wider than its chip, else null. */
    val fontSize: androidx.compose.ui.unit.TextUnit? = null,
    /** Extra advance after the token's last character, so layout reserves the chip's width. */
    val extraSpacing: androidx.compose.ui.unit.TextUnit = 0.sp,
)

/**
 * Bridges the composer's text field with Composer Autocomplete (iOS
 * `ComposerAutocompleteTextBridge`):
 * - tints the active `@` or `/` trigger (`#9cc0ff` dark, system blue light);
 * - keeps chip tokens whole on one line (non-breaking characters) and reserves each chip's
 *   exact width ([outputTransformation] / [visualTransformation]);
 * - draws the solid chips over their token ranges ([AutocompleteInlineChips]);
 * - deletes a chip whole on a single backspace and confirms Return/Tab while the list is
 *   open ([inputTransformation] / [shouldChangeText]);
 * - handles hardware ↑/↓, Escape, Tab and Return ([handleKeyEvent]);
 * - reports the caret ([selectionDidChange] / [notifyCaretAfterEdit]).
 *
 * The text input wires it as described on each member; the session view configures it from
 * the reducer state with [configure] (iOS `configuredAutocompleteBridge`).
 */
@Stable
class ComposerAutocompleteTextBridge {
    var isEnabled: Boolean by mutableStateOf(false)
    var isOpen: Boolean by mutableStateOf(false)
    var hasSelectableRow: Boolean by mutableStateOf(false)
    var activeTrigger: AutocompleteTrigger? by mutableStateOf(null)
    var chips: List<ComposerAutocompleteChip> by mutableStateOf(emptyList())
    var caretUTF16Offset: Int? = null
    var motionController: AutocompleteMotionController? = null

    var onMoveHighlight: ((Int) -> Unit)? = null
    var onConfirmSelection: (() -> Unit)? = null
    var onDismiss: (() -> Unit)? = null
    var onDeleteChip: ((UUID) -> Unit)? = null
    var onCaretChanged: ((Int?) -> Unit)? = null

    /**
     * Chips that would wrap mid-token where they stand, so they start the next line instead
     * (iOS `shouldBreakLineByWordBeforeCharacterAt` refuses every break inside a chip, so TextKit
     * breaks before it). Kept by [updateLineBreaks] from each layout.
     */
    var breaksBefore: Set<UUID> by mutableStateOf(emptySet())
        internal set

    /** The latest styling, published by [rememberAutocompleteTextStyling]; read by the transformations. */
    var styling: AutocompleteTextStyling? by mutableStateOf(null)
        internal set

    /** The text field's latest layout (pass it from `onTextLayout`). */
    var textLayout: TextLayoutResult? by mutableStateOf(null)

    /** The inner text field's coordinates ([autocompleteTextAnchor]). */
    var textCoordinates: LayoutCoordinates? by mutableStateOf(null)

    /** The composer's coordinates, the origin of flights ([autocompleteComposerAnchor]). */
    var composerCoordinates: LayoutCoordinates? = null

    private var isUpdatingProgrammaticText = false
    private var lastSentCaretOffset: Int? = null

    /** iOS `configuredAutocompleteBridge`: mirrors the reducer state and routes callbacks to [send]. */
    fun configure(
        state: ComposerAutocompleteState,
        isEnabled: Boolean,
        motionController: AutocompleteMotionController?,
        send: (ComposerAutocompleteAction) -> Unit,
    ) {
        this.isEnabled = isEnabled
        isOpen = state.isOpen
        hasSelectableRow = state.rows.any { it.isSelectable }
        activeTrigger = state.activeTrigger
        chips = state.chips
        caretUTF16Offset = state.caretUTF16Offset
        this.motionController = motionController
        onMoveHighlight = { send(ComposerAutocompleteAction.MoveHighlight(it)) }
        onConfirmSelection = { send(ComposerAutocompleteAction.ConfirmSelection) }
        onDismiss = { send(ComposerAutocompleteAction.Dismissed) }
        onDeleteChip = { send(ComposerAutocompleteAction.ChipDeleted(it)) }
        onCaretChanged = { send(ComposerAutocompleteAction.CaretChanged(it)) }
    }

    /** Wrap a programmatic text replacement (draft set from the reducer) so it reports no caret. */
    fun beginProgrammaticTextUpdate() {
        isUpdatingProgrammaticText = true
    }

    fun endProgrammaticTextUpdate() {
        isUpdatingProgrammaticText = false
    }

    fun syncLastSentCaretOffset() {
        lastSentCaretOffset = caretUTF16Offset
    }

    /** Call after each user edit (iOS `textViewDidChange` → `notifyCaretAfterEditIfNeeded`). */
    fun notifyCaretAfterEdit(text: String, selection: TextRange, composition: TextRange?) {
        emitCaretIfNeeded(text, selection, composition, requireDiffFromState = true)
    }

    /**
     * Where an edit left the caret, for the edit's own action ([ComposerAutocompleteAction.TextEdited]):
     * what [notifyCaretAfterEdit] would have sent on its own.
     */
    fun caretUpdateAfterEdit(text: String, selection: TextRange, composition: TextRange?): ComposerAutocompleteAction.CaretUpdate {
        if (!isEnabled || isUpdatingProgrammaticText || composition != null) return ComposerAutocompleteAction.CaretUpdate.Unchanged
        val actual = normalizedCaretOffset(selection, text.length)
        if (actual == caretUTF16Offset || actual == lastSentCaretOffset) return ComposerAutocompleteAction.CaretUpdate.Unchanged
        lastSentCaretOffset = actual
        return ComposerAutocompleteAction.CaretUpdate.Moved(actual)
    }

    /** Call when the selection moves (iOS `textViewDidChangeSelection`). */
    fun selectionDidChange(text: String, selection: TextRange, composition: TextRange?, committedText: String) {
        if (text != committedText) return
        emitCaretIfNeeded(committedText, selection, composition, requireDiffFromState = false)
    }

    private fun emitCaretIfNeeded(text: String, selection: TextRange, composition: TextRange?, requireDiffFromState: Boolean) {
        if (!isEnabled || isUpdatingProgrammaticText || composition != null) return
        val actual = normalizedCaretOffset(selection, text.length)
        if (requireDiffFromState && actual == caretUTF16Offset) return
        if (actual == lastSentCaretOffset) return
        lastSentCaretOffset = actual
        onCaretChanged?.invoke(actual)
    }

    private fun normalizedCaretOffset(selection: TextRange, textLength: Int): Int? {
        if (selection.length > 0) return 0
        return if (selection.start < textLength) selection.start else null
    }

    /**
     * Intercepts Return, Tab and single-character Backspace over inline chips before the field
     * mutates text (iOS `shouldChangeText(in:range:replacementText:)`). [start] and [length]
     * are the replaced UTF-16 range in [text]. Returns false when it handled the edit.
     */
    fun shouldChangeText(text: String, start: Int, length: Int, replacement: String): Boolean {
        if (!isEnabled) return true
        if (isOpen && hasSelectableRow && (replacement == "\n" || replacement == "\r" || replacement == "\t")) {
            onConfirmSelection?.invoke()
            return false
        }
        // Single-character backspace at or inside an inline chip deletes the whole chip at once;
        // wider ranges delete normally and prune chips in `draftChanged`.
        if (replacement.isEmpty() && length == 1) {
            val resolved = AutocompleteTrigger.resolvedChipRanges(text, chips)
            for (entry in resolved) {
                val intersection = min(start + length, entry.end) - max(start, entry.start)
                if (intersection > 0 && !(start == entry.start && length == entry.length)) {
                    onDeleteChip?.invoke(entry.chip.id)
                    return false
                }
            }
        }
        return true
    }

    /**
     * For a value-based `BasicTextField`: whether to accept [new] in place of [old] (single
     * contiguous edits go through [shouldChangeText]).
     */
    fun shouldAcceptValueChange(old: String, new: String): Boolean {
        if (old == new) return true
        var prefix = 0
        val maxPrefix = min(old.length, new.length)
        while (prefix < maxPrefix && old[prefix] == new[prefix]) prefix++
        var suffix = 0
        while (suffix < min(old.length, new.length) - prefix &&
            old[old.length - 1 - suffix] == new[new.length - 1 - suffix]
        ) suffix++
        val replaced = old.length - prefix - suffix
        val replacement = new.substring(prefix, new.length - suffix)
        return shouldChangeText(old, prefix, replaced, replacement)
    }

    /** An [InputTransformation] for a `TextFieldState` field that applies [shouldChangeText]. */
    val inputTransformation: InputTransformation = object : InputTransformation {
        @OptIn(ExperimentalFoundationApi::class)
        override fun TextFieldBuffer.transformInput() {
            if (!isEnabled || changes.changeCount != 1) return
            val range = changes.getRange(0)
            val original = changes.getOriginalRange(0)
            val replacement = asCharSequence().subSequence(range.start, range.end).toString()
            if (!shouldChangeText(originalText.toString(), original.start, original.length, replacement)) {
                revertAllChanges()
            }
        }
    }

    /**
     * Hardware keys while the list is open (iOS `keyCommands`): ↑/↓ move the highlight,
     * Escape dismisses, Tab and Return choose when a row is selectable. Use from
     * `Modifier.onPreviewKeyEvent`. Returns true when consumed.
     */
    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (!isEnabled || !isOpen) return false
        if (event.isShiftPressed || event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return false
        val isDown = event.type == KeyEventType.KeyDown
        when (event.key) {
            Key.DirectionUp -> { if (isDown) onMoveHighlight?.invoke(-1); return true }
            Key.DirectionDown -> { if (isDown) onMoveHighlight?.invoke(1); return true }
            Key.Escape -> { if (isDown) onDismiss?.invoke(); return true }
            Key.Tab, Key.Enter, Key.NumPadEnter -> {
                if (!hasSelectableRow) return false
                if (isDown) onConfirmSelection?.invoke()
                return true
            }
            else -> return false
        }
    }

    /**
     * Builds the styling for [text] (iOS `applyTextStyling(in:)`): measures each chip's title in
     * [AutocompleteChipTextStyle] and its token in [baseStyle], shrinking the token's font when
     * it is wider than its chip and padding its last character's advance to the chip's width.
     */
    fun buildStyling(
        text: String,
        baseStyle: TextStyle,
        measurer: TextMeasurer,
        density: Density,
        maxTextWidthPx: Float,
        isDark: Boolean,
    ): AutocompleteTextStyling {
        val triggerColor = if (isDark) Color(156, 192, 255) else Color(0, 122, 255)
        val trigger = activeTrigger?.takeIf { isOpen && it.utf16Start >= 0 && it.utf16End <= text.length && it.utf16End > it.utf16Start }
            ?.let { AutocompleteChipRun(null, it.utf16Start, it.utf16End) }
        val maxTextWidthDp = (maxTextWidthPx / density.density).toDouble()
        val runs = AutocompleteTrigger.resolvedChipRanges(text, chips)
            .filter { it.end <= text.length && it.length > 0 }
            .map { entry ->
                val titleWidthDp = measurer.measure(entry.chip.item.title, AutocompleteChipTextStyle, softWrap = false, maxLines = 1, density = density)
                    .size.width / density.density
                val targetWidthPx = (AutocompleteChipMetrics.width(titleWidthDp.toDouble(), maxTextWidthDp) * density.density).toFloat()
                val token = text.substring(entry.start, entry.end).toNonBreaking()
                var style = baseStyle
                var rawWidth = ceil(measurer.measure(token, style, softWrap = false, maxLines = 1, density = density).size.width.toDouble()).toFloat()
                var fontSize: androidx.compose.ui.unit.TextUnit? = null
                val baseSize = baseStyle.fontSize.takeIf { it.isSp }?.value ?: OpenCodeTextStyles.body.fontSize.value
                if (rawWidth > targetWidthPx && rawWidth > 1) {
                    val scaled = max(8f, floor(baseSize * (targetWidthPx / rawWidth)))
                    fontSize = scaled.sp
                    style = style.copy(fontSize = scaled.sp)
                    rawWidth = ceil(measurer.measure(token, style, softWrap = false, maxLines = 1, density = density).size.width.toDouble()).toFloat()
                }
                val extraPx = max(0f, targetWidthPx - rawWidth)
                val extra = with(density) { extraPx.toSp() }
                AutocompleteChipRun(entry.chip, entry.start, entry.end, targetWidthPx, fontSize, extra)
            }
        val spaceWidthPx = measurer.measure(" ", baseStyle, softWrap = false, maxLines = 1, density = density).size.width.toFloat()
        val chipIDs = runs.mapNotNull { it.chip?.id }.toSet()
        return AutocompleteTextStyling(text, trigger, triggerColor, runs, breaksBefore.intersect(chipIDs), spaceWidthPx)
    }

    /**
     * Keeps each chip whole on one line (call it from the field's `onTextLayout`). The
     * non-breaking substitutions remove the common break opportunities inside a token; any
     * other (after `/`, `?`, an en dash, between CJK characters…) still lets a chip split at a
     * line's end. Such a chip starts the next line instead: the space before it is drawn as a
     * line break (same length, so offsets and the caret are unchanged) until the chip fits
     * after the previous line again.
     */
    fun updateLineBreaks(layout: TextLayoutResult) {
        val current = styling ?: return
        if (!isEnabled || layout.layoutInput.text.length != current.text.length) return
        val constraints = layout.layoutInput.constraints
        if (!constraints.hasBoundedWidth) return
        val maxWidth = constraints.maxWidth.toFloat()
        val next = current.breaksBefore.toMutableSet()
        for (run in current.chips) {
            val id = run.chip?.id ?: continue
            if (run.start <= 0 || current.text[run.start - 1] != ' ' || run.end > current.text.length) {
                next -= id
                continue
            }
            val startLine = layout.getLineForOffset(run.start)
            if (id in current.breaksBefore) {
                // Restored to a space, would it sit whole after the previous line's text?
                val fits = startLine == 0 ||
                    layout.getLineRight(startLine - 1) + current.spaceWidthPx + run.chipWidthPx <= maxWidth - 1f
                if (fits) next -= id
            } else if (layout.getLineForOffset(run.end - 1) != startLine) {
                next += id
            }
        }
        if (next != breaksBefore) breaksBefore = next
    }

    /** Applies [styling] to a buffer or builder: styles plus same-length non-breaking replacements. */
    private inline fun applyStyling(
        styling: AutocompleteTextStyling,
        length: Int,
        addStyle: (SpanStyle, Int, Int) -> Unit,
        replace: (Int, Int, String) -> Unit,
    ) {
        styling.trigger?.let { if (it.end <= length) addStyle(SpanStyle(color = styling.triggerColor), it.start, it.end) }
        for (run in styling.chips) {
            if (run.end > length) continue
            addStyle(SpanStyle(color = Color.Transparent, fontSize = run.fontSize ?: androidx.compose.ui.unit.TextUnit.Unspecified), run.start, run.end)
            if (run.extraSpacing.value > 0f) addStyle(SpanStyle(letterSpacing = run.extraSpacing), run.end - 1, run.end)
            replace(run.start, run.end, styling.text.substring(run.start, run.end).toNonBreaking())
            if (run.chip?.id in styling.breaksBefore && run.start > 0 && styling.text[run.start - 1] == ' ') {
                replace(run.start - 1, run.start, "\n")
            }
        }
    }

    /** For a `TextFieldState` field: hides chip ink, keeps tokens whole and tints the trigger. */
    val outputTransformation: OutputTransformation = object : OutputTransformation {
        override fun TextFieldBuffer.transformOutput() {
            val current = styling ?: return
            if (!isEnabled || current.text != asCharSequence().toString()) return
            applyStyling(current, length, { style, s, e -> addStyle(style, s, e) }, { s, e, r -> replace(s, e, r) })
        }
    }

    /** For a value-based field: the same styling as a [VisualTransformation] (identity offsets). */
    val visualTransformation: VisualTransformation = VisualTransformation { input ->
        val current = styling
        if (!isEnabled || current == null || current.text != input.text) {
            TransformedText(input, OffsetMapping.Identity)
        } else {
            val chars = input.text.toCharArray()
            val builder = AnnotatedString.Builder()
            val spans = mutableListOf<Triple<SpanStyle, Int, Int>>()
            applyStyling(current, chars.size, { style, s, e -> spans += Triple(style, s, e) }) { s, _, r ->
                r.forEachIndexed { i, ch -> chars[s + i] = ch }
            }
            builder.append(String(chars))
            for (span in input.spanStyles) builder.addStyle(span.item, span.start, span.end)
            for ((style, s, e) in spans) builder.addStyle(style, s, e)
            TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
        }
    }

    /** The chip's rect in the text field's layout, in px (iOS `chipRect(for:in:maxTextWidth:)`). */
    fun chipRectPx(run: AutocompleteChipRun, layout: TextLayoutResult, density: Density): Rect? {
        if (run.end > layout.layoutInput.text.length || run.end <= run.start) return null
        val first = layout.getBoundingBox(run.start)
        val last = layout.getBoundingBox(run.end - 1)
        val line = layout.getLineForOffset(run.start)
        val midY = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f
        val minX = min(first.left, last.left)
        val fieldWidth = layout.size.width.toFloat()
        val width = run.chipWidthPx
        val height = (AutocompleteChipMetrics.scaledHeight(chipLineHeightDp) * density.density).toFloat()
        val y = midY - height / 2f
        val clampedX = min(max(0f, minX), max(0f, fieldWidth - width))
        val scale = density.density.toDouble()
        val snapped = AutocompleteSimulation.snapRectEdges(
            AutocompleteRect(clampedX / scale, y / scale, width / scale, height / scale),
            scale,
        )
        return Rect(
            (snapped.minX * scale).toFloat(),
            (snapped.minY * scale).toFloat(),
            (snapped.maxX * scale).toFloat(),
            (snapped.maxY * scale).toFloat(),
        )
    }

    /** The chip font's line height in dp, measured by [rememberAutocompleteTextStyling]. */
    internal var chipLineHeightDp: Double = 18.0

    /**
     * The chip's slot in composer-local dp for the flight to land on (iOS
     * `chipSlotInWindow(chipID:)` converted to the composer, as `chipSlotProvider` does).
     */
    fun chipSlotInComposer(chipID: UUID, density: Density): AutocompleteRect? {
        val layout = textLayout ?: return null
        val text = textCoordinates?.takeIf { it.isAttached } ?: return null
        val composer = composerCoordinates?.takeIf { it.isAttached } ?: return null
        val run = styling?.chips?.firstOrNull { it.chip?.id == chipID } ?: return null
        val local = chipRectPx(run, layout, density) ?: return null
        if (local.isEmpty) return null
        val origin = composer.localPositionOf(text, Offset.Zero)
        val d = density.density.toDouble()
        return AutocompleteRect(
            (local.left + origin.x) / d,
            (local.top + origin.y) / d,
            local.width / d,
            local.height / d,
        )
    }
}

private fun String.toNonBreaking(): String = buildString(length) {
    for (ch in this@toNonBreaking) {
        append(
            when (ch) {
                ' ' -> '\u00A0'
                '-' -> '\u2011'
                else -> ch
            },
        )
    }
}

/** Records the inner text field's coordinates for chip slots (put it on the text field). */
fun Modifier.autocompleteTextAnchor(bridge: ComposerAutocompleteTextBridge): Modifier =
    onGloballyPositioned { bridge.textCoordinates = it }

/** Records the composer's coordinates, the origin of chip flights (put it on the composer surface). */
fun Modifier.autocompleteComposerAnchor(bridge: ComposerAutocompleteTextBridge): Modifier =
    onGloballyPositioned { bridge.composerCoordinates = it }

/**
 * Measures and publishes the draft's styling to [bridge] (iOS `applyTextStyling`), so its
 * [ComposerAutocompleteTextBridge.outputTransformation] / `visualTransformation` and
 * [AutocompleteInlineChips] agree. Call it in the text input with its text and style.
 */
@Composable
fun rememberAutocompleteTextStyling(
    bridge: ComposerAutocompleteTextBridge,
    text: String,
    baseStyle: TextStyle,
): AutocompleteTextStyling? {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val isDark = OpenCodeTheme.colors.isDark
    val widthPx = bridge.textCoordinates?.size?.width?.toFloat() ?: 0f
    val maxTextWidthPx = max(80f * density.density, widthPx)
    val enabled = bridge.isEnabled
    val chips = bridge.chips
    val trigger = bridge.activeTrigger
    val open = bridge.isOpen
    val breaks = bridge.breaksBefore
    val styling = remember(enabled, text, chips, trigger, open, baseStyle, density, maxTextWidthPx, isDark, breaks) {
        if (!enabled) null else bridge.buildStyling(text, baseStyle, measurer, density, maxTextWidthPx, isDark)
    }
    val lineHeightDp = remember(density) {
        measurer.measure("Ag", AutocompleteChipTextStyle, density = density).size.height / density.density.toDouble()
    }
    SideEffect {
        bridge.chipLineHeightDp = lineHeightDp
        if (bridge.styling != styling) bridge.styling = styling
    }
    return styling
}

/**
 * Draws the solid inline chips over their token ranges (iOS `layoutChips(in:)`). Place it in
 * the text field's decoration box, over the inner text field, at its origin. A chip in flight
 * stays hidden until it lands.
 */
@Composable
fun AutocompleteInlineChips(bridge: ComposerAutocompleteTextBridge, modifier: Modifier = Modifier) {
    val styling = bridge.styling ?: return
    val layout = bridge.textLayout ?: return
    if (!bridge.isEnabled || styling.chips.isEmpty() || layout.layoutInput.text.text != styling.visualText) return
    val density = LocalDensity.current
    val controller = bridge.motionController
    Box(modifier) {
        for (run in styling.chips) {
            val chip = run.chip ?: continue
            val rect = bridge.chipRectPx(run, layout, density) ?: continue
            if (rect.isEmpty) continue
            val inFlight = controller?.isChipHidden(chip.id) == true
            key(chip.id) {
                AutocompleteChip(
                    chip = chip,
                    onActivate = { bridge.onDeleteChip?.invoke(it) },
                    modifier = Modifier
                        .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
                        .requiredSize(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
                        .alpha(if (inFlight) 0f else 1f),
                )
            }
        }
    }
}

/**
 * The session view's `syncComposerAutocomplete(state:)`: wires [bridge] and the flight
 * landing slot, then mirrors [state] into [controller] whenever it or the composer's size
 * changes. [composerSize] is the composer's measured size in dp.
 */
@Composable
fun SyncComposerAutocomplete(
    state: ComposerAutocompleteState,
    isEnabled: Boolean,
    controller: AutocompleteMotionController,
    bridge: ComposerAutocompleteTextBridge,
    composerSize: () -> Size,
    reducedMotion: Boolean,
    send: (ComposerAutocompleteAction) -> Unit,
) {
    // The size is read here, not by the caller: it changes on every frame the composer grows (the
    // keyboard's morph), and only this small scope should recompose for it.
    val size = composerSize()
    val composerWidth = size.width.toDouble()
    val composerHeight = size.height.toDouble()
    val density = LocalDensity.current
    val currentSend by rememberUpdatedState(send)
    val last = remember { arrayOfNulls<Any>(1) }
    SideEffect {
        bridge.configure(state, isEnabled, controller) { currentSend(it) }
        controller.chipSlotProvider = { id -> bridge.chipSlotInComposer(id, density) }
        controller.composerAnchor = { bridge.composerCoordinates?.takeIf { it.isAttached } }
        // iOS `.onChange(of: store.autocomplete / composerFrame / usesComposerAutocomplete)`.
        val inputs = listOf(state, isEnabled, composerWidth, composerHeight, density, reducedMotion)
        if (last[0] == inputs) return@SideEffect
        val wasEnabled = (last[0] as? List<*>)?.get(1) as? Boolean
        last[0] = inputs
        if (wasEnabled == true && !isEnabled) controller.reset()
        if (!isEnabled) return@SideEffect
        controller.sync(
            state = state,
            composerWidth = if (composerWidth > 40) composerWidth else 350.0,
            composerHeight = if (composerHeight > 20) composerHeight else 52.0,
            displayScale = max(density.density.toDouble(), 1.0),
            reducedMotion = reducedMotion,
        )
    }
}
