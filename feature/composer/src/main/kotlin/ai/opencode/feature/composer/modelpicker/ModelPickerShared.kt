package ai.opencode.feature.composer.modelpicker

import ai.opencode.core.strings.localized
import ai.opencode.feature.composer.R
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Port of iOS `Composer/ModelPicker/ModelPickerShared.swift`: the pieces of the deleted Sheet and
// Grow from Composer pickers (iOS 0fbb99f8) that One Picker, the photo sheet and the composer still
// use. Plus [predictedEndTranslation], Android's stand-in for SwiftUI's drag projection, which
// lived beside the deleted `ModelPickerAnchors`.

/** iOS `ComposerFocusRestore`: one pending "give the message field its keyboard back". */
class ComposerFocusRestore(private val scope: CoroutineScope) {
    private var task: Job? = null

    fun schedule(after: Duration, restore: () -> Unit) {
        cancel()
        task = scope.launch {
            delay(after)
            task = null
            restore()
        }
    }

    fun cancel() {
        task?.cancel()
        task = null
    }
}

/**
 * iOS `ProgressiveBlur.Profile`: full strength at one edge fading out, or a band peaking in the
 * middle. Only its strengths are ported here; Android draws no backdrop blur (Material scrims
 * instead), so the strengths only shape dims.
 */
sealed interface ProgressiveBlurProfile {
    /** Strongest at the top ([top]) or the bottom edge. */
    data class Edge(val top: Boolean) : ProgressiveBlurProfile

    /** Full strength between [lower] and [upper] (fractions of the height), easing out to both edges. */
    data class Band(val lower: Double, val upper: Double) : ProgressiveBlurProfile
}

/** iOS `ProgressiveBlur.strengths(_:samples:)`: the blur's strength top to bottom, smoothstepped. */
fun progressiveBlurStrengths(profile: ProgressiveBlurProfile, samples: Int = 32): List<Double> {
    fun smooth(x: Double): Double {
        val t = x.coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }
    val strengths = ArrayList<Double>(samples + 1)
    for (i in 0..samples) {
        val t = i.toDouble() / samples
        val strength = when (profile) {
            is ProgressiveBlurProfile.Edge -> if (profile.top) smooth(1 - t) else smooth(t)
            is ProgressiveBlurProfile.Band -> when {
                t < profile.lower -> smooth(t / max(profile.lower, 0.001))
                t > profile.upper -> smooth((1 - t) / max(1 - profile.upper, 0.001))
                else -> 1.0
            }
        }
        strengths.add(strength)
    }
    return strengths
}

/** Port of iOS `LevelArc` (`Shape`): the level glyph's arc geometry. */
object LevelArc {
    const val TRACK_OPACITY = 0.28

    data class Pose(val fill: Double, val lit: Double)

    fun pose(index: Int, count: Int): Pose {
        val levels = max(count - 1, 1)
        if (index <= 0) return Pose((levels + 1).toDouble() / (2 * levels).toDouble(), 0.45)
        return Pose(min(index, levels).toDouble() / levels.toDouble(), 1.0)
    }

    /** Stroke width in dp for a glyph [size] dp across. */
    fun stroke(size: Double, needle: Boolean): Double = LevelScrubberTunables.arcStroke * size / 20 * (if (needle) 0.7 else 1.0)

    fun center(size: Size): Offset = Offset(size.width / 2, size.height * 0.55f)

    fun radius(size: Size): Float = min(size.width, size.height) * 0.37f

    /** Degrees from 3 o'clock, clockwise (y down), as iOS's `start(span:)` in a flipped space. */
    fun start(span: Double): Double = 90 + (360 - span) / 2

    fun draw(scope: DrawScope, fill: Double, span: Double, color: Color, stroke: Float) {
        val clamped = fill.coerceIn(0.0, 1.0)
        if (clamped <= 0.001) return
        val center = center(scope.size)
        val r = radius(scope.size)
        scope.drawArc(
            color = color,
            startAngle = start(span).toFloat(),
            sweepAngle = (span * clamped).toFloat(),
            useCenter = false,
            topLeft = Offset(center.x - r, center.y - r),
            size = Size(r * 2, r * 2),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

/** iOS `ModelListDetent`: One Picker's two rests. */
enum class ModelListDetent {
    Medium, Large;

    data class Drag(val start: Double, val translation: Double)

    data class Rests(val large: Double) {
        val medium: Double
            get() = min(large, max(ModelPickerTunables.mediumMinimum, roundHalfAwayFromZero(large * ModelPickerTunables.mediumFraction)))

        fun extent(detent: ModelListDetent): Double = if (detent == Large) large else medium
    }

    val accessibilityValue: String get() = if (this == Large) localized(R.string.composer_model_list_full_description) else localized(R.string.composer_model_list_half_description)

    /** iOS `adjusted(_:)`: TalkBack's increment/decrement (+1 / −1, 0 keeps it). */
    fun adjusted(direction: Int): ModelListDetent = when {
        direction > 0 -> Large
        direction < 0 -> Medium
        else -> this
    }

    companion object {
        fun drag(drag: Drag?, drawnReach: Double, translation: Double): Drag =
            Drag(drag?.start ?: drawnReach, translation)

        fun settle(drag: Drag, predictedTranslation: Double?, rests: Rests): ModelListDetent? =
            rest(drag.start - (predictedTranslation ?: drag.translation), rests)

        fun reach(resting: Double, drag: Double, full: Double): Double {
            val raw = resting - drag
            if (raw <= full) return max(raw, 0.0)
            val over = raw - full
            return full + ModelPickerTunables.rubberMax * (1 - 1 / (over / max(ModelPickerTunables.rubberSoftness, 1.0) + 1))
        }

        fun rest(projected: Double, rests: Rests): ModelListDetent? {
            if (projected < rests.medium * ModelPickerTunables.closeBelow) return null
            return if (abs(projected - rests.large) < abs(projected - rests.medium)) Large else Medium
        }
    }
}

/**
 * iOS `LevelScrubberTunables` (`composer.level.*`): the level arc's stroke and span, at their
 * shipped defaults (Android has no tuning store, so `sectionTitles`, `register` and `unregister`
 * are n/a).
 */
object LevelScrubberTunables {
    /** `arcStroke` "Arc stroke (pt at 20 pt)". */
    const val arcStroke = 2.45

    /** `arcSpan` "Arc span (°)". */
    const val arcSpan = 240.0
}

/** `UIScrollView.DecelerationRate.normal`: the share of velocity kept per millisecond. */
const val UIKIT_DECELERATION_RATE_NORMAL = 0.998

/**
 * UIKit's throw projection, the one SwiftUI's `DragGesture.Value.predictedEndTranslation` applies
 * (Apple, WWDC18 “Designing Fluid Interfaces” `project(initialVelocity:decelerationRate:)`):
 * how far a throw at [velocity] (per second) coasts while decelerating at [decelerationRate]
 * per millisecond, `(v / 1000) · r / (1 − r)`.
 */
fun projectedTravel(velocity: Double, decelerationRate: Double = UIKIT_DECELERATION_RATE_NORMAL): Double =
    velocity / 1000.0 * decelerationRate / (1.0 - decelerationRate)

/** SwiftUI `predictedEndTranslation`: where a drag at [translation] released at [velocity] would come to rest. */
fun predictedEndTranslation(translation: Double, velocity: Double): Double = translation + projectedTravel(velocity)
