package ai.opencode.feature.drawer.catchup

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ai.opencode.core.motion.MotionRole
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Catch Up's swipe, spring, stack and card tunables (iOS `CatchUpTunables`, `CatchUpStack.swift`),
 * as their baked defaults. iOS exposes them in Developer Mode's Tuning Panel with presets; Android
 * has no Tuning Panel (`:devtools` is Android-native), so only the defaults are ported.
 *
 * Distances are iOS points, used as dp.
 */
object CatchUpTunables {
    // Swipe
    /** Commit distance (× width). */
    const val commitFraction = 0.24f
    /** Projection deceleration (per millisecond, `UIScrollView.DecelerationRate.normal`-like). */
    const val decelerationRate = 0.994f
    /** Flick velocity (pt/s → dp/s). */
    const val velocityThreshold = 500f
    /** Haptic threshold (× commit). */
    const val hapticThreshold = 1.0f
    /** Vertical resistance. */
    const val rubberBand = 0.3f

    // Stack
    /** Back card scale. */
    const val backScale = 0.92f
    /** Back card offset (pt). */
    const val backOffset = 12f
    /** Back card lift (× height). */
    const val backLiftRatio = 0.04f
    /** Back card rotation (rad). */
    const val backRotation = 0f
    /** Back card reveal (× width). */
    const val revealFraction = 0.78f

    // Card
    /** Card corner radius. */
    const val cornerRadius = 40f
    /** Drag rotation (rad). */
    const val dragRotation = 0.16f
    /** Card side inset. */
    const val sideInset = 10f

    fun layoutSnapshot(): CatchUpLayoutTuning = CatchUpLayoutTuning()
}

/**
 * Snapshot of layout- and stack-pose-affecting Catch Up tunables (iOS `CatchUpLayoutTuning`).
 * Kept as a value so a future tuning surface can update the resting stack in place.
 */
data class CatchUpLayoutTuning(
    val backScale: Float = CatchUpTunables.backScale,
    val backOffset: Float = CatchUpTunables.backOffset,
    val backLiftRatio: Float = CatchUpTunables.backLiftRatio,
    val backRotation: Float = CatchUpTunables.backRotation,
    val revealFraction: Float = CatchUpTunables.revealFraction,
    val cornerRadius: Float = CatchUpTunables.cornerRadius,
    val dragRotation: Float = CatchUpTunables.dragRotation,
    val sideInset: Float = CatchUpTunables.sideInset,
)

/** Projected-landing math and release decision for Catch Up card swipes (iOS `CatchUpSwipeMath`). */
object CatchUpSwipeMath {
    /**
     * Standard `UIScrollView` projection: distance travelled by [initialVelocity] (units/s) before
     * coming to rest at [decelerationRate] (per millisecond).
     */
    fun project(initialVelocity: Float, decelerationRate: Float = CatchUpTunables.decelerationRate): Float {
        val rate = min(max(decelerationRate, 0.9f), 0.9995f)
        return (initialVelocity / 1_000f) * rate / (1 - rate)
    }

    /** Projected horizontal landing coordinate from [translationX] and [velocityX]. */
    fun projectedLanding(
        translationX: Float,
        velocityX: Float,
        decelerationRate: Float = CatchUpTunables.decelerationRate,
    ): Float = translationX + project(velocityX, decelerationRate)

    /**
     * Decides whether a drag released at [translation] with [velocity] in a container of [width]
     * commits to `markRead` (right), `keepUnread` (left), or snaps back (`null`). All values in
     * the same unit (dp and dp/s, as iOS uses points).
     */
    fun decision(
        translation: Offset,
        velocity: Offset,
        width: Float,
        commitFraction: Float = CatchUpTunables.commitFraction,
        decelerationRate: Float = CatchUpTunables.decelerationRate,
        velocityThreshold: Float = CatchUpTunables.velocityThreshold,
    ): CatchUpDisposition? {
        val dir = when {
            abs(translation.x) >= 1 -> if (translation.x > 0) 1f else -1f
            abs(velocity.x) >= 1 -> if (velocity.x > 0) 1f else -1f
            else -> 0f
        }
        if (dir == 0f) return null
        val signedX = translation.x * dir
        val signedV = velocity.x * dir
        if (signedX <= 0) return null

        // A flick back toward center cancels the commit.
        if (signedV <= -velocityThreshold) return null

        val commitDistance = max(width * commitFraction, 1f)
        val projected = signedX + project(signedV, decelerationRate)
        val commitsByProjection = projected >= commitDistance
        val commitsByFlick = signedX >= 12 && signedV >= velocityThreshold
        if (!commitsByProjection && !commitsByFlick) return null
        return if (dir > 0) CatchUpDisposition.markRead else CatchUpDisposition.keepUnread
    }
}

/**
 * Damped 2D spring stepped per frame for Catch Up card snap-back (iOS `CatchUpSpring`). Starts
 * from the finger's release position and velocity and steps every visual property (card
 * transform, rotation, badge ring and back-card peek) in lockstep so catching a card mid-flight
 * takes over from where it's drawn. Units: dp and dp/s (iOS points).
 */
class CatchUpSpring(
    var position: Offset,
    var velocity: Offset,
    val target: Offset = Offset.Zero,
) {
    /** The release role's spring (iOS 03adf07a: drag releases land on `MotionRole.Release`). */
    val omega: Float
    val zeta: Float
    private val initialSignX: Float
    private val initialSignY: Float

    init {
        val release = MotionRole.Release.curve().springParameters
        omega = release.omega.toFloat()
        zeta = release.zeta.toFloat()
        val dx = position.x - target.x
        val dy = position.y - target.y
        initialSignX = if (abs(dx) > 0.001f) (if (dx > 0) 1f else -1f) else 0f
        initialSignY = if (abs(dy) > 0.001f) (if (dy > 0) 1f else -1f) else 0f
    }

    val isSettled: Boolean
        get() = hypot(position.x - target.x, position.y - target.y) < 0.5f && hypot(velocity.x, velocity.y) < 12f

    /** Advances by [dt] seconds; true once settled (snapped exactly onto the target). */
    fun step(dt: Float): Boolean {
        if (dt <= 0) return isSettled
        val clampedDt = min(dt, 1f / 20f)
        val (nx, nvx) = stepAxis(position.x - target.x, velocity.x, omega, zeta, clampedDt, initialSignX)
        val (ny, nvy) = stepAxis(position.y - target.y, velocity.y, omega, zeta, clampedDt, initialSignY)
        position = Offset(target.x + nx, target.y + ny)
        velocity = Offset(nvx, nvy)
        if (isSettled) {
            position = target
            velocity = Offset.Zero
            return true
        }
        return false
    }

    private fun stepAxis(x0: Float, v0: Float, omega: Float, zeta: Float, dt: Float, initialSign: Float): Pair<Float, Float> {
        var x: Float
        var v: Float
        if (abs(zeta - 1) < 1e-3f) {
            val decay = exp(-omega * dt)
            val a = v0 + omega * x0
            x = (x0 + a * dt) * decay
            v = (v0 - omega * a * dt) * decay
        } else if (zeta < 1) {
            val omegaD = omega * sqrt(1 - zeta * zeta)
            val decay = exp(-zeta * omega * dt)
            val c1 = x0
            val c2 = (v0 + zeta * omega * x0) / omegaD
            val cosTerm = cos(omegaD * dt)
            val sinTerm = sin(omegaD * dt)
            x = decay * (c1 * cosTerm + c2 * sinTerm)
            v = decay * ((c2 * omegaD - zeta * omega * c1) * cosTerm - (c1 * omegaD + zeta * omega * c2) * sinTerm)
        } else {
            val gamma = omega * sqrt(zeta * zeta - 1)
            val r1 = -omega * zeta + gamma
            val r2 = -omega * zeta - gamma
            val c1 = (v0 - r2 * x0) / (2 * gamma)
            val c2 = x0 - c1
            val e1 = exp(r1 * dt)
            val e2 = exp(r2 * dt)
            x = c1 * e1 + c2 * e2
            v = c1 * r1 * e1 + c2 * r2 * e2
        }
        // Critically damped or overdamped springs never cross the target when driven inward
        // faster than natural decay: clamp on arrival at the target.
        if (zeta >= 0.99f && initialSign != 0f && x * initialSign < 0) {
            x = 0f
            v = 0f
        }
        return x to v
    }
}

/** Layout shared by the Catch Up chrome and the inset cards (iOS `CatchUpMetrics`). */
object CatchUpMetrics {
    val readColor = Color(red = 0.08f, green = 0.57f, blue = 0.42f, alpha = 1f)
    val readControlColor = Color(red = 0.06f, green = 0.46f, blue = 0.34f, alpha = 1f)
    val keepColor = Color(red = 0.34f, green = 0.38f, blue = 0.52f, alpha = 1f)
    /**
     * Ink on the [readColor] and [keepColor] washes. They are fixed mid-dark tones in both
     * themes (not theme roles), so their on-colour is white: 28 sp bold labels at ≥ 3:1.
     */
    val onToneColor = Color.White
    val headerHeight: Dp = 52.dp
    val footerHeight: Dp = 74.dp
    val sideInset: Dp get() = CatchUpTunables.sideInset.dp
    val composerInset: Dp = 12.dp

    /** Concentric with the 28 pt composer capsule inset 12 pt from the card's edges (`28 + 12 = 40`). */
    val cornerRadius: Dp get() = CatchUpTunables.cornerRadius.dp

    /**
     * A specular rim in dark mode so the inset card stands clear of the dark backdrop; the
     * standard separator in light mode. [separator] is the theme's separator colour.
     */
    fun borderColor(isDark: Boolean, separator: Color, factor: Float = 1f): Color {
        val base = if (isDark) Color.White.copy(alpha = 0.16f) else separator
        return base.copy(alpha = base.alpha * factor)
    }
}
