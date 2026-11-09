package ai.opencode.feature.transcript.list

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionTunables
import ai.opencode.feature.transcript.TranscriptTunables
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.Constraints
import kotlin.math.E
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A row's height easing from where it was to its measured height (iOS `RowGrowth`): no faster
 * at the start or the end than [maximumFrameChange] allows, so what moves with it eases in. A
 * new target bends the motion from its current height and speed. Heights in dp.
 */
class RowGrowth(
    from: Float,
    target: Float,
    time: Double,
    /** The row's width when it started: at another width it lands. */
    val width: Float,
    springDuration: Double? = null,
) {
    var height: Float = from
        private set
    var target: Float = target
        private set
    private var velocity = 0f
    private var time: Double = time
    private var start = 0.0
    private var from = 0f
    private var startVelocity = 0f
    private var duration = 0.0
    private val capturedMaxFrameChange = maximumFrameChange
    private val capturedMinDuration = minimumDuration
    private val capturedMaxDuration = maximumDuration
    val capturedSpeed: Double = BottomSpring.ARRIVAL_SPEED
    /** A row already drawn whose content changed (not one arriving). */
    var changedRow = false
    private val springOmega: Double? = springDuration?.let { 2 * PI / max(0.05, it) }
    private var omega = 0.0

    init {
        retarget(target)
    }

    private fun spring(t: Double): Pair<Float, Float> {
        val a = (from - target).toDouble()
        val b = startVelocity + omega * a
        val decay = exp(-omega * t)
        return (target + ((a + b * t) * decay).toFloat()) to ((b - omega * (a + b * t)) * decay).toFloat()
    }

    val isFinished: Boolean get() = time >= start + duration

    fun continues(atWidth: Float): Boolean = abs(atWidth - width) < 0.5f

    /** Absorbs a removed neighbour's height while keeping the current velocity. */
    fun absorb(removed: Float, target: Float) {
        height += removed
        retarget(target)
    }

    /** Starts a new curve from the current height and speed. */
    fun retarget(target: Float) {
        this.target = target
        from = height
        startVelocity = velocity
        start = time
        val distance = target - height
        if (springOmega != null) {
            if (startVelocity * distance <= 0) startVelocity = 0f
            omega = if (abs(distance) > 0.5f) min(springOmega, E * capturedSpeed / abs(distance)) else springOmega
            duration = 14 / omega
            return
        }
        var duration = (1.0 / 60) * sqrt(6 * abs(distance) / max(0.1f, capturedMaxFrameChange)).toDouble()
        duration = min(max(duration, capturedMinDuration), capturedMaxDuration)
        duration = max(duration, 1.5 * abs(distance) / max(1.0, capturedSpeed))
        if (startVelocity * distance > 0) duration = min(duration, (3 * distance / startVelocity).toDouble())
        this.duration = max(duration, 1.0 / 120)
        if (startVelocity * distance <= 0) {
            startVelocity = 0f
        } else if (abs(startVelocity) * this.duration > 3 * abs(distance)) {
            startVelocity = (3 * distance / this.duration).toFloat()
        }
    }

    fun advance(frame: Double, upTo: Double) {
        time = max(time, min(upTo, time + frame))
        if (springOmega != null) {
            val (h, v) = spring(time - start)
            height = max(0f, h)
            velocity = v
            val passed = (height - target) * (from - target) < 0
            if (passed || (abs(height - target) < 0.25f && (abs(velocity) < 15 || time >= start + duration))) {
                height = target
                velocity = 0f
                duration = time - start
            }
            return
        }
        val s = min(max((time - start) / duration, 0.0), 1.0).toFloat()
        val span = target - from
        val lead = startVelocity * duration.toFloat()
        height = from + lead * (s - 2 * s * s + s * s * s) + span * (3 * s * s - 2 * s * s * s)
        velocity = (lead * (1 - 4 * s + 3 * s * s) + span * 6 * (s - s * s)) / duration.toFloat()
        if (s >= 1) {
            height = target
            velocity = 0f
        }
        height = max(0f, height)
    }

    companion object {
        val maximumFrameChange: Float get() = TranscriptTunables.growthMaxFrameChange
        val minimumDuration: Double get() = TranscriptTunables.growthMinDuration
        val maximumDuration: Double get() = max(minimumDuration, TranscriptTunables.growthMaxDuration)
    }
}

/**
 * How far the view rides rows growing at the end in one frame (iOS `GrowthRide`): the view
 * follows the growth so rows below stay put on screen, never faster than arrivals glide.
 */
class GrowthRide {
    /** What the growth has put between the reader and the end, still to ride. */
    var deficit = 0f
        private set

    fun offset(from: Float, endBefore: Float, end: Float, step: Float): Float {
        val riding = from >= endBefore - 0.5f || deficit > 0.5f
        val target = if (riding) max(from, end) else from
        val next = min(target, from + step)
        deficit = max(0f, target - next)
        return next
    }

    fun reset() {
        deficit = 0f
    }
}

/**
 * A growing row's soft edge (iOS `TranscriptHostingCell`'s growth mask), in dp: a fade at the
 * row's growing edge that only ever brings in what the growth shows. It never reaches above the
 * height the row stood at when the growth began (a streamed line wrapping onto a new one never
 * dims the line above), widens no faster than the row grows (a new line arriving mid-growth
 * can't darken the line still easing in), and a growth that ends with its edge still soft eases
 * it away over [LEAVE_DURATION] instead of brightening in one frame. Plain object, stepped by
 * the list's frame loop.
 */
class GrowthSoftEdge {
    private var start: Float? = null
    private var width = 0f
    private var fadeHeight = 0f
    private var fade = 0f
    private var leaveFrom = 0f
    private var leaveStart: Double? = null

    val isLeaving: Boolean get() = leaveStart != null

    /** The fade as drawn at [now], leaving included (iOS `drawnGrowthFade`). */
    fun drawn(now: Double): Float {
        val started = leaveStart ?: return fade
        val t = ((now - started) / LEAVE_DURATION).coerceIn(0.0, 1.0).toFloat()
        return leaveFrom * (1 - LEAVE_EASING.transform(t))
    }

    /** Nothing drawn and nothing to ease: the list may forget it. */
    fun isIdle(now: Double): Boolean = leaveStart?.let { now - it >= LEAVE_DURATION } ?: (fade == 0f)

    /**
     * The row lays out at [height] of its [measured] content this frame (iOS `updateGrowthMask`).
     * [onScreen] false drops a leaving edge at once: nothing would see it ease.
     */
    fun update(height: Float, measured: Float, now: Double, maxFade: Float, onScreen: Boolean = true) {
        if (measured - height <= 0.5f) {
            start = null
            width = 0f
            leave(height, now, onScreen)
            return
        }
        if (leaveStart != null) {
            // Growing again while the last edge clears: carry on from it.
            val shown = drawn(now)
            leaveStart = null
            start = max(0f, height - shown)
            width = shown
            fadeHeight = height
        }
        val begins = start == null
        val from = min(start ?: height, height)
        if (begins) fadeHeight = height
        start = from
        val room = max(0f, height - fadeHeight)
        val next = minOf(maxFade, measured - height, height - from, width + room)
        width = max(0f, next)
        fadeHeight = height
        if (next > 0.5f) {
            fade = next
        } else {
            // Nothing new on show yet: a hard edge at what was already there. An edge still
            // showing (a promotion took the growth over) eases out.
            leave(height, now, onScreen)
        }
    }

    private fun leave(height: Float, now: Double, onScreen: Boolean) {
        if (leaveStart != null) {
            if (!onScreen || height <= 0.5f) remove()
            return
        }
        if (fade == 0f) return
        if (!onScreen || height <= 0.5f || fade <= 1f) {
            remove()
            return
        }
        leaveFrom = fade
        leaveStart = now
        fade = 0f
    }

    private fun remove() {
        leaveStart = null
        fade = 0f
    }

    companion object {
        /** The faded line brightens as `Motion.swap` brings a change in. */
        val LEAVE_DURATION: Double get() = MotionTunables.swapInDuration.value
        private val LEAVE_EASING = Motion.EaseOut
    }
}

/**
 * A row laid out at its measured height but reported at the height [TranscriptListController]
 * grows it to (iOS `TopAnchoredRow` + `fittedHeight`), clipped to that height in the same pass
 * whenever its content stands taller (iOS `clipsToBounds = height < measured - 0.5` in
 * `preferredLayoutAttributesFitting`). A growth starts inside this measure, before the frame loop
 * ticks, so a clip read later from the controller missed the first frame of every wrap and the
 * new line painted over the row below.
 */
internal fun Modifier.topAnchoredRow(controller: TranscriptListController, key: String): Modifier =
    this then TopAnchoredRowElement(controller, key)

private data class TopAnchoredRowElement(val controller: TranscriptListController, val key: String) :
    ModifierNodeElement<TopAnchoredRowNode>() {
    override fun create() = TopAnchoredRowNode(controller, key)
    override fun update(node: TopAnchoredRowNode) {
        node.controller = controller
        node.key = key
    }
}

private class TopAnchoredRowNode(var controller: TranscriptListController, var key: String) :
    Modifier.Node(), LayoutModifierNode, DrawModifierNode {
    /** The reported height in px while the content stands taller (-1: no clip). */
    private var clipHeight = -1

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        val height = controller.fittedHeight(key, placeable.height / density, placeable.width / density)
        val px = (height * density).roundToInt().coerceAtLeast(0)
        val clip = if (placeable.height - px > 0.5f * density) px else -1
        if (clip != clipHeight) {
            clipHeight = clip
            invalidateDraw()
        }
        return layout(placeable.width, px) { placeable.place(0, 0) }
    }

    // Drawn at the content's measured size, so the clip names the reported height.
    override fun ContentDrawScope.draw() {
        val clip = clipHeight
        if (clip >= 0) clipRect(bottom = clip.toFloat()) { this@draw.drawContent() } else drawContent()
    }
}
