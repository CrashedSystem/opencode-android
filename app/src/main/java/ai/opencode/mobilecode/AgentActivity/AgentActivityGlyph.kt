@file:PortedFrom("OpenCode/AgentActivity/AgentActivityGlyph.swift", "339260d")

package ai.opencode.mobilecode

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/// The agent activity glyph: a small round LED orb whose dots draw one of
/// Kit's designs per state (`ActivityGyro`), morphing with momentum from one
/// to the next. The dots and glow are the dot orb's
/// (`prototypes/ios-kit/dot-orb/layer.js`): a 7 × 7 grid masked to a disc.
/// It redraws only while something moves; Done and Failed settle and stop,
/// and Reduce Motion shows each state's still pose.
@Composable
fun AgentActivityGlyph(
    modifier: Modifier = Modifier,
    source: AgentActivitySource = AgentActivitySource.running,
    /// The layout box. The orb is 2 pt narrower (4 pt from 24 pt up), and its glow spills past it.
    size: Dp = 18.dp,
) {
    val reducedMotion = LocalReduceMotion.current
    val dark = isSystemInDarkTheme()
    val scale = LocalDensity.current.density
    val model = remember { AgentActivityGlyphModel() }
    val lab = AgentActivityLab.override
    val current = source.activity(at = AgentActivityLedger.now(), lab = lab)
    val latestSource by rememberUpdatedState(source)
    var frame by remember { mutableStateOf(model.advance(to = AgentActivityLedger.now(), source = source, lab = lab, reduceMotion = reducedMotion)) }
    // Done and Failed hold. Under Reduce Motion every state holds a still
    // pose; a slow tick still notices the next state.
    LaunchedEffect(model, current, reducedMotion, lab) {
        var now = AgentActivityLedger.now()
        while (true) {
            val activity = latestSource.activity(at = now, lab = lab)
            val resting = model.rests(`in` = activity)
            if (activity.isFinal && resting) break
            // Frame times share `System.nanoTime`'s base with the ledger.
            now = if (reducedMotion && resting) {
                delay(500)
                AgentActivityLedger.now()
            } else {
                withInfiniteAnimationFrameNanos { it / 1e9 }
            }
            frame = model.advance(to = now, source = latestSource, lab = lab, reduceMotion = reducedMotion)
        }
    }
    val margin = AgentActivityOrb.margin(size.value)
    Box(
        modifier
            .size(size)
            .semantics {
                contentDescription = if (current.isFinal) current.label else "Running"
                stateDescription = if (current.isFinal) "" else current.label
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.requiredSize(size + (2 * margin).dp)) {
            AgentActivityOrb.draw(frame, scope = this, layout = size.value, dark = dark, scale = scale)
        }
    }
}

/// The glyph's simulation, kept across recompositions: purely visual state.
class AgentActivityGlyphModel {
    data class Frame(
        val field: GyroField? = null,
        /// Nothing moves until the state changes or an event arrives.
        val resting: Boolean = false,
    )

    private var gyro: ActivityGyro? = null
    private var reduceMotion = false
    private var last: Double? = null
    private var counts = AgentActivityCounts()

    /// Showing `activity` and still.
    fun rests(`in`: AgentActivity): Boolean = gyro?.op == `in` && gyro?.isStatic == true

    fun advance(to: Double, source: AgentActivitySource, lab: AgentActivityOverride?, reduceMotion: Boolean): Frame {
        val now = to
        val activity = source.activity(at = now, lab = lab)
        val counts = source.counts(at = now, lab = lab)
        if (gyro == null || reduceMotion != this.reduceMotion) {
            // First sight: the state as it stands, settled, never an entrance.
            // Seeded by the source's text, not the instance as on iOS, so
            // snapshots repeat (enum hash codes differ from run to run).
            gyro = ActivityGyro(activity, reduceMotion = reduceMotion, seed = source.toString().hashCode().mod(97) / 13.0)
            this.reduceMotion = reduceMotion
            this.counts = counts
            last = now
        }
        val gyro = gyro ?: return Frame()
        if (gyro.op != activity) gyro.set(activity)
        val new = counts.since(this.counts)
        this.counts = counts
        // A burst after a pause arrives a few at a time, as it would have live.
        repeat(min(new.tool, 3)) { gyro.event(ActivityGyro.Event.tool) }
        repeat(min(new.output, 3)) { gyro.event(ActivityGyro.Event.output) }
        val dt = min(1.0 / 30, max(0.0, now - (last ?: now)))
        last = now
        if (dt > 0) gyro.step(dt)
        return Frame(field = gyro.fill(GyroCell.seven), resting = gyro.isStatic)
    }
}

/// Draws the orb: its soft radial glow, then each dot with its white-hot
/// core and a faint halo, as the prototype's shader does per pixel.
object AgentActivityOrb {
    fun margin(size: Float): Float = (size * 0.42f).roundToInt().toFloat()

    fun diameter(size: Float): Float = size - if (size >= 24) 4 else 2

    // `prototypes/ios-kit/dot-orb/index.js`'s palette: the scope's cool pearl and four phosphor accents.
    class Palette(val base: Vec3, val core: Vec3, val accents: Map<ActivityAccent, Pair<Vec3, Vec3>>)

    val dark = Palette(
        base = Vec3(0.70, 0.75, 0.84),
        core = Vec3(0.92, 0.95, 1.0),
        accents = mapOf(
            ActivityAccent.orange to (Vec3(1.0, 0.64, 0.30) to Vec3(1.0, 0.88, 0.70)),
            ActivityAccent.red to (Vec3(1.0, 0.40, 0.38) to Vec3(1.0, 0.78, 0.75)),
            ActivityAccent.green to (Vec3(0.46, 0.86, 0.58) to Vec3(0.82, 1.0, 0.86)),
            ActivityAccent.blue to (Vec3(0.42, 0.66, 1.0) to Vec3(0.80, 0.90, 1.0)),
        ),
    )

    // A step darker than the scope's light ink, to carry beside 15 pt text.
    val light = Palette(
        base = Vec3(0.27, 0.32, 0.42),
        core = Vec3(0.09, 0.12, 0.2),
        accents = mapOf(
            ActivityAccent.orange to (Vec3(0.95, 0.52, 0.12) to Vec3(0.78, 0.36, 0.02)),
            ActivityAccent.red to (Vec3(0.92, 0.22, 0.24) to Vec3(0.72, 0.08, 0.12)),
            ActivityAccent.green to (Vec3(0.18, 0.64, 0.33) to Vec3(0.06, 0.46, 0.2)),
            ActivityAccent.blue to (Vec3(0.1, 0.45, 0.95) to Vec3(0.02, 0.28, 0.72)),
        ),
    )

    // The layer's look: glow sigma (cells), unlit grid, dot scale, glow strength and its light-mode share.
    const val sigma = 2.2
    const val unlit = 1.0
    const val dotScale = 1.0
    const val glowStrength = 1.5
    const val lightGlow = 0.45
    const val shaderGlow = 0.55

    private fun saturate(c: Vec3, k: Double): Vec3 {
        val l = c.x * 0.299 + c.y * 0.587 + c.z * 0.114
        return Vec3.repeating(l) + (c - Vec3.repeating(l)) * k
    }

    private fun mix(a: Vec3, b: Vec3, t: Double): Vec3 = a + (b - a) * t

    /// Glow colour, as the scope derives it from a trace's two colours.
    private fun glowColor(a: Vec3, b: Vec3, dark: Boolean): Vec3 {
        val c = if (dark) saturate(mix(a, b, 0.5), 1.5) else mix(saturate(a, 1.95), Vec3(1.0, 1.0, 1.0), 0.15)
        return Vec3(c.x.coerceIn(0.0, 1.0), c.y.coerceIn(0.0, 1.0), c.z.coerceIn(0.0, 1.0))
    }

    private fun color(c: Vec3, opacity: Double): Color =
        Color(c.x.toFloat(), c.y.toFloat(), c.z.toFloat(), opacity.coerceIn(0.0, 1.0).toFloat())

    fun draw(frame: AgentActivityGlyphModel.Frame, scope: DrawScope, layout: Float, dark: Boolean, scale: Float) {
        val field = frame.field ?: return
        val palette = if (dark) this.dark else this.light
        // The accent blended by the state's colour weights.
        var sum = 0.0
        var accent = Vec3.zero
        var accentCore = Vec3.zero
        for ((key, weight) in field.weights) {
            if (weight <= 1e-3) continue
            val pair = palette.accents[key] ?: continue
            sum += weight
            accent += pair.first * weight
            accentCore += pair.second * weight
        }
        val tint = min(1.0, sum)
        if (sum >= 1e-3) {
            accent /= sum
            accentCore /= sum
        } else {
            accent = palette.base
            accentCore = palette.core
        }
        val glowRGB = mix(glowColor(palette.base, palette.core, dark), glowColor(accent, accentCore, dark), if (sum >= 1e-3) tint else 0.0)

        // Canvas units are pixels; the prototype's sizes are points.
        val area = scope.size
        // The grid snaps to device pixels: a whole number of pixels per cell.
        val n = 7.0
        val pitchPixels = max(2.0, Gyro.rounded(diameter(layout) * scale / n))
        val gridSide = n * pitchPixels
        val origin = Offset(floor((area.width - gridSide) / 2).toFloat(), floor((area.height - gridSide) / 2).toFloat())
        val centre = Offset((origin.x + gridSide / 2).toFloat(), (origin.y + gridSide / 2).toFloat())
        val pitch = pitchPixels

        // Glow: min(1, amount × gaussian) × peak, around the grid's centre.
        val peak = (if (dark) 0.3 else 0.19) * shaderGlow * glowStrength * (if (dark) 1.0 else lightGlow)
        if (field.glow > 1e-3) {
            val reach = 3.6 * sigma
            val stops = (0..9).map { i ->
                val x = reach * i / 9
                val a = min(1.0, field.glow * exp(-x * x / (2 * sigma * sigma))) * peak
                (i / 9f) to color(glowRGB, a)
            }.toTypedArray()
            val radius = (reach * pitch).toFloat()
            scope.drawCircle(Brush.radialGradient(*stops, center = centre, radius = radius), radius = radius, center = centre)
        }

        val a0 = (if (dark) 0.06 else 0.075) * unlit
        val top = if (dark) 0.96 else 1.0
        for ((cell, dot) in GyroCell.seven.zip(field.dots)) {
            val P = Gyro.clamp(dot.light, 0.0, 1.0)
            val fresh = Gyro.clamp(dot.fresh, 0.0, 1.0)
            val alpha = a0 + (top - a0) * max(P, 1e-5).pow((if (dark) 0.9 else 0.75) * (1.4 + (1.0 - 1.4) * fresh))
            val shrink = 1 + ((0.82 + 0.18 * fresh) - 1) * Gyro.sstep(0.03, 0.2, P)
            val radius = (0.25 + 0.17 * max(P, 1e-5).pow(0.6)) * shrink * pitch * dotScale * dot.size
            val core = Gyro.clamp(P * P * 1.1, 0.0, 0.9) * (0.3 + 0.7 * fresh)
            val rgb = mix(mix(palette.base, palette.core, core), mix(accent, accentCore, core), Gyro.clamp(dot.accent, 0.0, 1.0))
            val c = Offset((origin.x + (cell.column + 0.5) * pitch).toFloat(), (origin.y + (cell.row + 0.5) * pitch).toFloat())
            // Halo: 0.22 P² falling off over a few tenths of a cell outside the dot.
            val halo = 0.22 * P * P
            if (halo > 0.008) {
                val outer = radius + 0.5 * pitch
                val stops = listOf(max(0.0, radius / outer - 0.01).toFloat() to color(rgb, 0.0)) + (0..5).map { i ->
                    val d = 0.5 * pitch * i / 5
                    ((radius + d) / outer).toFloat() to color(rgb, halo * exp(-d / (0.12 * pitch)))
                }
                scope.drawCircle(Brush.radialGradient(*stops.toTypedArray(), center = c, radius = outer.toFloat()), radius = outer.toFloat(), center = c)
            }
            scope.drawCircle(color(rgb, alpha), radius = radius.toFloat(), center = c)
        }
    }
}
