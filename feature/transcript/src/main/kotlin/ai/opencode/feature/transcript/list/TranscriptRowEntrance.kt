package ai.opencode.feature.transcript.list

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionTunables
import ai.opencode.core.motion.SpringSolver
import ai.opencode.feature.transcript.TranscriptTunables
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalGraphicsContext
import kotlin.math.PI
import kotlin.math.max

/**
 * How a new row arrives (iOS `RowEntrance.Style`). `Arrival`: fades in on `Motion.swap` while it
 * rises into place on `Motion.layout`. `Settling`: a quiet row landing among rows already read (a
 * model switch) fades evenly over the whole rise, so it's seen arriving rather than popping in.
 * `WithFlight`: a sent message's time stamp, held back until the flight's last stretch, then
 * settling down into place as its bubble lands, on the send flight's own clock.
 */
enum class RowEntranceStyle { Arrival, Settling, WithFlight }

/**
 * A new row at the end fades and rises a few points into place (iOS `RowEntrance`).
 * Presentation-only (a graphics layer): layout, reuse and self-sizing are unaffected.
 */
object RowEntrance {
    /** A row leaving the screen without a motion of its own fades out this fast. */
    const val EXIT_DURATION = 0.2
    val RISE: Float get() = Motion.entranceRise.value
    /** Larger insertions are a refresh or a page, not arrivals. */
    const val MAXIMUM_ROWS = 4
    /** How long a row inserted off screen may still fade in when it appears. */
    const val WINDOW = 0.5

    /** The send flight's length as a spring's perceptual duration (`2π / ω`), iOS `SendFlight.duration`. */
    val withFlightDuration: Double get() = 2 * PI / max(TranscriptTunables.sendFlightOmega, 0.01)
    /** iOS `.curveEaseOut` for `fadeOut(_:)`. */
    private val exitEasing = CubicBezierEasing(0f, 0f, 0.58f, 1f)

    /** Fade-out progress `0..1` along [EXIT_DURATION] (`0` at start, `1` gone). */
    fun exitAlpha(progress: Float): Float = exitEasing.transform(progress.coerceIn(0f, 1f))

    /** How long [style]'s entrance runs: its fade or its rise, whichever is longer. */
    fun duration(style: RowEntranceStyle): Double = when (style) {
        RowEntranceStyle.Arrival, RowEntranceStyle.Settling -> max(MotionTunables.swapInDuration.value, Motion.layout.curve().duration)
        RowEntranceStyle.WithFlight -> withFlightDuration
    }

    /** The longest any entrance runs. */
    val maximumDuration: Double get() = max(duration(RowEntranceStyle.Arrival), withFlightDuration)

    /** Opacity and rise (dp, positive = below) at [progress] `0..1` of [style]'s [duration]. */
    fun pose(progress: Float, style: RowEntranceStyle): Pair<Float, Float> {
        val elapsed = progress * duration(style)
        return when (style) {
            RowEntranceStyle.Arrival -> {
                val fade = Motion.EaseOut.transform(fraction(elapsed, MotionTunables.swapInDuration.value))
                fade to RISE * lift(elapsed)
            }
            RowEntranceStyle.Settling -> {
                // An even fade across the rise's beat.
                val fade = Motion.EaseInOut.transform(fraction(elapsed, Motion.layout.curve().duration))
                fade to RISE * lift(elapsed)
            }
            RowEntranceStyle.WithFlight -> {
                // Held until 35 %, then settles down 4 pt into place.
                val t = Motion.EaseOut.transform(progress.coerceIn(0f, 1f))
                val k = ((t - 0.35f) / 0.65f).coerceIn(0f, 1f)
                k to -4f * (1 - k)
            }
        }
    }

    /** What's left of the rise (`1 → 0`) [elapsed] seconds in, on `Motion.layout`. */
    private fun lift(elapsed: Double): Float {
        val parameters = Motion.layout.curve(MotionPhase.Enter).springParameters
        val (value, _) = SpringSolver.evaluate(from = 1.0, to = 0.0, omega = parameters.omega, zeta = parameters.zeta, elapsed = elapsed)
        return value.toFloat().coerceIn(0f, 1f)
    }

    private fun fraction(elapsed: Double, duration: Double): Float = (elapsed / max(duration, 0.01)).toFloat().coerceIn(0f, 1f)
}

/**
 * Applies a row's entrance pose while [entrance] is non-null, in a pure [DrawModifierNode] rather
 * than `Modifier.graphicsLayer`: `BlockGraphicsLayerModifier` implements `SemanticsModifierNode`
 * and reports `translationY` changes as layout bounds updates, which would turn every `LazyColumn`
 * item root into its own unmerged semantics node and fan out scroll content-change events per row.
 */
fun Modifier.rowEntrance(entrance: () -> Pair<Float, RowEntranceStyle>?): Modifier =
    this then RowEntranceElement(entrance)

private data class RowEntranceElement(val entrance: () -> Pair<Float, RowEntranceStyle>?) :
    ModifierNodeElement<RowEntranceNode>() {
    override fun create() = RowEntranceNode(entrance)
    override fun update(node: RowEntranceNode) {
        node.entrance = entrance
    }
}

private class RowEntranceNode(var entrance: () -> Pair<Float, RowEntranceStyle>?) :
    Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    private var layer: GraphicsLayer? = null
    private var layerOwner: GraphicsContext? = null

    override fun ContentDrawScope.draw() {
        val current = entrance()
        if (current == null || size.width <= 0f || size.height <= 0f) {
            dropLayer()
            drawContent()
            return
        }
        val graphics = currentValueOf(LocalGraphicsContext)
        if (layerOwner !== graphics) dropLayer()
        val activeLayer = layer ?: graphics.createGraphicsLayer().also {
            layer = it
            layerOwner = graphics
        }
        val (opacity, rise) = RowEntrance.pose(current.first, current.second)
        activeLayer.alpha = opacity
        activeLayer.translationY = rise * density
        activeLayer.record { this@draw.drawContent() }
        drawLayer(activeLayer)
    }

    override fun onReset() = dropLayer()

    override fun onDetach() = dropLayer()

    private fun dropLayer() {
        val existing = layer ?: return
        layer = null
        layerOwner?.releaseGraphicsLayer(existing)
        layerOwner = null
    }
}

/**
 * Visible rows fading out where they were after a small update removes them (iOS `fadingPictures`
 * / `fadeOut(_:)` in `TranscriptListViewController+RowPresentation.swift`).
 *
 * Decided at removal time in [TranscriptListController.applySnapshot] and stepped on the list's
 * own motion loop instead of `LazyItemScope.animateItem(fadeOutSpec)`: `animateItem` bakes
 * `fadeOutSpec` onto rows before the update that removes them (so a bulk replacement still started
 * `LazyLayoutItemAnimator` disappearance fades on the old rows), and `LazyListState.requestScrollToItem`
 * (`landOnLastItem`, `GrowthRide`, jumps) calls `LazyLayoutItemAnimator.reset()`, which in Compose
 * 1.12.1 releases the disappearing layers while `LazyColumn`'s outer `RenderNode` still holds them
 * without invalidating `displayingNode`, freezing the old rows over the list.
 */
@Stable
internal class TranscriptRowExits(private val graphics: GraphicsContext) {
    private class Exit(
        val key: String,
        val layer: GraphicsLayer,
        val top: Float,
        val start: Double,
        val anchor: Pair<String, Float>?,
    )

    private val rows = HashMap<String, RowExitSourceNode>()
    private val exits = ArrayList<Exit>()
    internal var overlay: RowExitOverlayNode? = null

    /** Read in [draw] so Compose's draw observer invalidates the outer layer alongside [overlay]. */
    private var version by mutableIntStateOf(0)

    val hasActive: Boolean get() = exits.isNotEmpty()

    internal fun createLayer(): GraphicsLayer = graphics.createGraphicsLayer()

    internal fun releaseLayer(layer: GraphicsLayer) = graphics.releaseGraphicsLayer(layer)

    internal fun attach(row: RowExitSourceNode) {
        rows[row.key] = row
    }

    internal fun detach(row: RowExitSourceNode) {
        if (rows[row.key] === row) rows.remove(row.key)
    }

    /** Captures [key]'s last drawn layer to fade out over [RowEntrance.EXIT_DURATION]. */
    fun fadeOut(key: String, top: Float, now: Double, anchor: Pair<String, Float>?): Boolean {
        val layer = rows[key]?.handOver() ?: return false
        if (layer.size.width <= 0 || layer.size.height <= 0) {
            releaseLayer(layer)
            return false
        }
        val iterator = exits.iterator()
        while (iterator.hasNext()) {
            val existing = iterator.next()
            if (existing.key == key) {
                iterator.remove()
                releaseLayer(existing.layer)
            }
        }
        exits += Exit(key = key, layer = layer, top = top, start = now, anchor = anchor)
        invalidate()
        return true
    }

    /** Drops any active exit whose row key returned in [keys]. */
    fun drop(keys: Set<String>) {
        if (exits.isEmpty()) return
        var changed = false
        val iterator = exits.iterator()
        while (iterator.hasNext()) {
            val exit = iterator.next()
            if (exit.key in keys) {
                iterator.remove()
                releaseLayer(exit.layer)
                changed = true
            }
        }
        if (changed) invalidate()
    }

    /** Advances active fades to [now]; returns true while any remain fading. */
    fun step(now: Double): Boolean {
        if (exits.isEmpty()) return false
        val iterator = exits.iterator()
        while (iterator.hasNext()) {
            val exit = iterator.next()
            if (now - exit.start >= RowEntrance.EXIT_DURATION) {
                iterator.remove()
                releaseLayer(exit.layer)
            }
        }
        invalidate()
        return exits.isNotEmpty()
    }

    /** Ends and releases every active exit immediately (bulk swap, session change, off-screen). */
    fun clear() {
        if (exits.isEmpty()) return
        exits.forEach { releaseLayer(it.layer) }
        exits.clear()
        invalidate()
    }

    /** Releases every active exit when the list leaves composition; row nodes release their own layers on detach. */
    fun release() {
        clear()
        rows.clear()
    }

    fun draw(scope: DrawScope, startPaddingPx: Float, layoutInfo: LazyListLayoutInfo, now: Double) {
        version
        if (exits.isEmpty()) return
        val visible = layoutInfo.visibleItemsInfo
        val viewportStart = layoutInfo.viewportStartOffset
        scope.clipRect(0f, 0f, scope.size.width, scope.size.height) {
            for (exit in exits) {
                val progress = ((now - exit.start) / RowEntrance.EXIT_DURATION).toFloat().coerceIn(0f, 1f)
                val alpha = 1f - RowEntrance.exitAlpha(progress)
                if (alpha <= 0.001f) continue
                val shift = exit.anchor?.let { (anchorKey, anchorTop) ->
                    val current = visible.firstOrNull { it.key == anchorKey } ?: return@let null
                    (current.offset - viewportStart) - anchorTop
                } ?: 0f
                exit.layer.alpha = alpha
                translate(left = startPaddingPx, top = exit.top + shift) {
                    drawLayer(exit.layer)
                }
            }
        }
    }

    private fun invalidate() {
        version++
        overlay?.invalidateDraw()
    }
}

/** Records a row's latest drawing for [TranscriptRowExits] so a small removal can fade it out. */
internal fun Modifier.rowExitSource(exits: TranscriptRowExits?, key: String): Modifier =
    if (exits == null) this else this then RowExitSourceElement(exits, key)

private data class RowExitSourceElement(val exits: TranscriptRowExits, val key: String) : ModifierNodeElement<RowExitSourceNode>() {
    override fun create() = RowExitSourceNode(exits, key)
    override fun update(node: RowExitSourceNode) = node.update(exits, key)
}

internal class RowExitSourceNode(var exits: TranscriptRowExits, var key: String) : Modifier.Node(), DrawModifierNode {
    private var layer: GraphicsLayer? = null

    fun handOver(): GraphicsLayer? {
        val taken = layer ?: return null
        layer = null
        invalidateDraw()
        return taken
    }

    fun update(exits: TranscriptRowExits, key: String) {
        if (exits === this.exits && key == this.key) return
        if (isAttached) this.exits.detach(this)
        if (exits !== this.exits) dropLayer()
        this.exits = exits
        this.key = key
        if (isAttached) {
            exits.attach(this)
            invalidateDraw()
        }
    }

    override fun ContentDrawScope.draw() {
        if (size.width <= 0f || size.height <= 0f) {
            dropLayer()
            return
        }
        val layer = layer ?: exits.createLayer().also { layer = it }
        layer.record { this@draw.drawContent() }
        drawLayer(layer)
    }

    override fun onAttach() {
        exits.attach(this)
        invalidateDraw()
    }

    override fun onReset() {
        exits.detach(this)
        dropLayer()
    }

    override fun onDetach() {
        exits.detach(this)
        dropLayer()
    }

    private fun dropLayer() {
        layer?.let(exits::releaseLayer)
        layer = null
    }
}

/** Draws [exits] behind the live `LazyColumn` items, in the same painter's order as item exits. */
internal fun Modifier.drawRowExits(
    exits: TranscriptRowExits,
    startPaddingPx: Float,
    layoutInfo: () -> LazyListLayoutInfo,
    now: () -> Double,
): Modifier = this then RowExitOverlayElement(exits, startPaddingPx, layoutInfo, now)

private data class RowExitOverlayElement(
    val exits: TranscriptRowExits,
    val startPaddingPx: Float,
    val layoutInfo: () -> LazyListLayoutInfo,
    val now: () -> Double,
) : ModifierNodeElement<RowExitOverlayNode>() {
    override fun create() = RowExitOverlayNode(exits, startPaddingPx, layoutInfo, now)
    override fun update(node: RowExitOverlayNode) = node.update(exits, startPaddingPx, layoutInfo, now)
}

internal class RowExitOverlayNode(
    var exits: TranscriptRowExits,
    var startPaddingPx: Float,
    var layoutInfo: () -> LazyListLayoutInfo,
    var now: () -> Double,
) : Modifier.Node(), DrawModifierNode {
    fun update(
        exits: TranscriptRowExits,
        startPaddingPx: Float,
        layoutInfo: () -> LazyListLayoutInfo,
        now: () -> Double,
    ) {
        if (this.exits !== exits) {
            if (this.exits.overlay === this) this.exits.overlay = null
            this.exits = exits
            if (isAttached) exits.overlay = this
        }
        this.startPaddingPx = startPaddingPx
        this.layoutInfo = layoutInfo
        this.now = now
    }

    override fun onAttach() {
        exits.overlay = this
    }

    override fun onDetach() {
        if (exits.overlay === this) exits.overlay = null
    }

    override fun ContentDrawScope.draw() {
        exits.draw(this, startPaddingPx, layoutInfo(), now())
        drawContent()
    }
}
