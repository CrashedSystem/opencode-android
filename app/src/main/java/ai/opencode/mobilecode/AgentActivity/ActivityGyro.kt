@file:PortedFrom("OpenCode/AgentActivity/ActivityGyro.swift", "339260d")

package ai.opencode.mobilecode

import kotlin.math.E
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// The activity glyph's engine, ported from `prototypes/activity-glyph/gyro.js`
// (round 3, Kit's set in `kit-set.json`). Keep it in step with the prototype:
// the per-state designs live in one table, `ActivityGyro.designs`, written
// as close to the JavaScript as Swift allows so tweaks copy straight over.
//
// A glyph is up to four strokes on an LED dot sphere. A stroke is the set of
// dots where dot(p, n) ≈ s: a great circle (s = 0), a smaller circle, or, seen
// edge-on, a straight line. Windows along it make dashes; a pitch term tilts
// it into a spiral stripe. Every design is a pose of these same numbers, so a
// change of state morphs the dots rather than cross-fading them.
//
// Angles never double back. Each is unwrapped: it either spins (its speed a
// spring) or holds a pose. Holding a new pose while moving picks the next
// equivalent angle ahead, far enough that a critically damped spring
// decelerates into it without overshooting.

/// What a glyph shows. The labels are the prototype's.
enum class AgentActivity(val rawValue: String) {
    idle("idle"),
    startup("startup"),
    thinking("thinking"),
    longWork("longWork"),
    tool("tool"),
    streaming("streaming"),
    waiting("waiting"),
    handoff("handoff"),
    done("done"),
    failed("failed"),
    ;

    val label: String
        get() = when (this) {
            idle -> "Idle"
            startup -> "Starting Up"
            thinking -> "Thinking"
            longWork -> "Long Work"
            tool -> "Tool Burst"
            streaming -> "Streaming Output"
            waiting -> "Waiting for Input"
            handoff -> "Handing Off"
            done -> "Done"
            failed -> "Failed"
        }

    /// Done and Failed settle and hold; everything else keeps moving.
    val isFinal: Boolean get() = this == done || this == failed
}

enum class ActivityAccent { orange, red, green, blue }

/// `SIMD3<Double>`.
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Double) = Vec3(x * k, y * k, z * k)
    operator fun div(k: Double) = Vec3(x / k, y / k, z / k)

    /// `(a * b).sum()`.
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z

    companion object {
        val zero = Vec3(0.0, 0.0, 0.0)

        fun repeating(v: Double) = Vec3(v, v, v)
    }
}

// MARK: Springs (prototypes/ios-kit/spring.js)

data class GyroSpring(val response: Double, val damping: Double) {
    /// Advances a damped oscillator by `t` from displacement `x0` and velocity `v0`.
    fun solve(x0: Double, v0: Double, t: Double): Pair<Double, Double> {
        val w = 2 * PI / max(1e-4, response)
        val z = damping
        if (z < 1) {
            val wd = w * sqrt(1 - z * z)
            val e = exp(-z * w * t)
            val c = cos(wd * t)
            val s = sin(wd * t)
            val b = (v0 + z * w * x0) / wd
            return Pair(e * (x0 * c + b * s), e * ((-z * w) * (x0 * c + b * s) + (-x0 * wd * s + b * wd * c)))
        }
        if (z == 1.0) {
            val e = exp(-w * t)
            val b = v0 + w * x0
            return Pair(e * (x0 + b * t), e * (b - w * (x0 + b * t)))
        }
        val r = sqrt(z * z - 1)
        val r1 = -w * (z - r)
        val r2 = -w * (z + r)
        val c1 = (v0 - r2 * x0) / (r1 - r2)
        val c2 = x0 - c1
        val e1 = exp(r1 * t)
        val e2 = exp(r2 * t)
        return Pair(c1 * e1 + c2 * e2, c1 * r1 * e1 + c2 * r2 * e2)
    }

    companion object {
        fun smooth(duration: Double) = GyroSpring(response = duration, damping = 1.0)
    }
}

/// A spring-driven value. A class, so `stroke[key].to(…)` changes it in place
/// as Swift's mutating subscript does; `copy()` is the struct's value copy.
class GyroValue(
    var value: Double = 0.0,
    var config: GyroSpring = Gyro.MOVE,
    val precision: Double = 0.005,
) {
    var target = value
    var velocity = 0.0

    fun copy(): GyroValue = GyroValue(value, config, precision).also {
        it.target = target
        it.velocity = velocity
    }

    fun to(target: Double, config: GyroSpring? = null, immediate: Boolean = false) {
        if (config != null) this.config = config
        this.target = target
        if (immediate) {
            value = target
            velocity = 0.0
        }
    }

    fun set(value: Double) {
        this.value = value
        target = value
        velocity = 0.0
    }

    val settled: Boolean get() = abs(value - target) < precision && abs(velocity) < precision * 10

    /// False once settled.
    fun step(dt: Double): Boolean {
        if (settled) {
            value = target
            velocity = 0.0
            return false
        }
        val (x, v) = config.solve(value - target, velocity, dt)
        value = target + x
        velocity = v
        return true
    }
}

// MARK: Constants and helpers

object Gyro {
    const val TAU = PI * 2
    const val HALF = PI / 2
    val MOVE = GyroSpring.smooth(0.6)
    val SLOW = GyroSpring.smooth(1.2)
    val FAST = GyroSpring.smooth(0.3)
    val FADE = GyroSpring.smooth(0.45)
    val RM = GyroSpring.smooth(0.35)
    const val W_MOVE = TAU / 0.6
    const val W_MIN = TAU / 1.6
    const val W_MAX = TAU / 0.22

    /// Below this angular speed (rad/s) a pose change may take the nearest equivalent, either way.
    const val MOVING = 0.6
    const val ROW = 1 / 3.6

    fun clamp(x: Double, a: Double, b: Double): Double = if (x < a) a else if (x > b) b else x

    fun sstep(a: Double, b: Double, x: Double): Double {
        val t = clamp((x - a) / (b - a), 0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    fun wrap(a: Double): Double = a - TAU * floor((a + PI) / TAU)

    fun gauss(x: Double, s: Double): Double = exp(-(x * x) / (2 * s * s))

    fun sign(x: Double): Double = if (x > 0) 1.0 else if (x < 0) -1.0 else 0.0

    /// Swift's `rounded()`: to nearest, halves away from zero.
    fun rounded(x: Double): Double = if (x >= 0) floor(x + 0.5) else ceil(x - 0.5)

    // Latitude as an angle: S(φ) falls from 1.1 to −1.1 over each turn, so a
    // stroke rolling past one pole comes back from the other.
    fun S(phi: Double): Double = 1.1 - 2.2 * (phi / TAU - floor(phi / TAU))

    fun Sinv(s: Double): Double = TAU * (1.1 - s) / 2.2

    /// Strokes fade out towards the wrap at |s| = 1.1 over their own width and pitch.
    fun visS(s: Double, w: Double, pitch: Double): Double {
        val fw = min(0.6, 0.06 + 2.2 * w + 0.5 * pitch)
        return sstep(1.1, 1.1 - fw, abs(s))
    }

    fun ringS(rho: Double): Double = sqrt(1 - rho * rho)

    /// The equivalent of x (mod period) nearest the angle's current value.
    fun near(ang: GyroAngle, x: Double, period: Double = TAU): Double = x + period * rounded((ang.value - x) / period)

    data class Frame(val n: Vec3, val u: Vec3, val v: Vec3)

    /// n, u, v for angles (θ, e, a, r): n = Rz(r)·Rx(a)·(cos e cos θ, sin e, cos e sin θ); u, v span its circle.
    fun frame(th: Double, e: Double, a: Double, r: Double): Frame {
        val ce = cos(e)
        val se = sin(e)
        val ct = cos(th)
        val st = sin(th)
        val ca = cos(a)
        val sa = sin(a)
        val cr = cos(r)
        val sr = sin(r)
        fun rot(x: Double, y: Double, z: Double): Vec3 {
            val y1 = y * ca - z * sa
            val z1 = y * sa + z * ca
            return Vec3(x * cr - y1 * sr, x * sr + y1 * cr, z1)
        }
        return Frame(rot(ce * ct, se, ce * st), rot(-st, 0.0, ct), rot(-se * ct, ce, -se * st))
    }
}

// MARK: Unwrapped angle with momentum

class GyroAngle(var value: Double = 0.0) {
    enum class Mode { hold, spin }

    var vel = 0.0
    var mode = Mode.hold
    var anchor = value
    var cfg = Gyro.MOVE
    var speed = GyroValue(0.0, config = Gyro.MOVE, precision = 1e-3)
    var last: Pair<Double, Double> = Pair(value, Gyro.TAU)

    fun set(v: Double) {
        value = v
        anchor = v
        vel = 0.0
        mode = Mode.hold
        speed.set(0.0)
        last = Pair(v, Gyro.TAU)
    }

    fun copy(o: GyroAngle) {
        value = o.value
        vel = o.vel
        mode = o.mode
        anchor = o.anchor
        cfg = o.cfg
        speed = o.speed.copy()
        last = o.last
    }

    /// Spin at w rad/s. A spin never reverses a visible motion: against the current direction it takes w's magnitude, not its sign.
    fun spin(w: Double, cfg: GyroSpring = Gyro.MOVE, immediate: Boolean = false) {
        var w = w
        if (abs(vel) > Gyro.MOVING && w != 0.0 && Gyro.sign(w) != Gyro.sign(vel)) w = -w
        if (mode != Mode.spin) {
            speed.set(vel)
            mode = Mode.spin
        }
        speed.to(w, cfg)
        if (immediate) {
            speed.set(w)
            vel = w
        }
    }

    /// Come to rest on `angle` or any angle `period` away from it: moving, the
    /// first equivalent ahead by at least the spring's natural stopping
    /// distance, approached by a critically damped spring that never overshoots.
    fun hold(angle: Double, period: Double = Gyro.TAU, immediate: Boolean = false, cfg: GyroSpring? = null, decel: Boolean = true) {
        if (mode == Mode.spin) vel = speed.value
        mode = Mode.hold
        last = Pair(angle, period)
        val v = vel
        if (immediate) {
            anchor = angle + period * Gyro.rounded((value - angle) / period)
            value = anchor
            vel = 0.0
            return
        }
        if (abs(v) < Gyro.MOVING) {
            anchor = angle + period * Gyro.rounded((value - angle) / period)
            this.cfg = cfg ?: Gyro.MOVE
            return
        }
        val dir = Gyro.sign(v)
        val lead = abs(v) / Gyro.W_MOVE
        val k = if (dir > 0) ceil((value + lead - angle) / period) else floor((value - lead - angle) / period)
        anchor = angle + k * period
        val d = abs(anchor - value)
        var w = cfg?.let { Gyro.TAU / it.response } ?: Gyro.W_MOVE
        if (decel) w = Gyro.clamp(1.5 * abs(v) / d, Gyro.W_MIN, Gyro.W_MAX)
        w = max(w, 1.05 * abs(v) / d)
        this.cfg = GyroSpring(response = Gyro.TAU / w, damping = 1.0)
    }

    /// Start spinning from `value` already moving at `vel`.
    fun launch(value: Double, vel: Double) {
        this.value = value
        this.vel = vel
        mode = Mode.spin
        speed.set(vel)
    }

    /// Track a moving target as a spring.
    fun follow(target: Double, cfg: GyroSpring = Gyro.MOVE) {
        if (mode == Mode.spin) vel = speed.value
        mode = Mode.hold
        anchor = target
        this.cfg = cfg
    }

    /// Push forward by about `d` radians: in a spin, extra travel; holding, on to the next equivalent pose.
    fun kick(d: Double, cfg: GyroSpring? = null) {
        if (mode == Mode.spin) {
            val w = Gyro.TAU / speed.config.response
            speed.velocity += d * w * w
            return
        }
        vel = Gyro.sign(d) * max(abs(vel), Gyro.MOVING + 0.1)
        hold(last.first, last.second, cfg = cfg, decel = cfg == null)
    }

    val moving: Boolean
        get() {
            if (mode == Mode.spin) return abs(speed.value) > 1e-3 || !speed.settled
            return abs(value - anchor) > 1e-4 || abs(vel) > 1e-3
        }

    fun step(dt: Double) {
        if (mode == Mode.spin) {
            val v0 = speed.value
            speed.step(dt)
            value += 0.5 * (v0 + speed.value) * dt
            vel = speed.value
            return
        }
        val (x, v) = cfg.solve(value - anchor, vel, dt)
        value = anchor + x
        vel = v
        if (abs(x) < 1e-4 && abs(v) < 1e-3) {
            value = anchor
            vel = 0.0
        }
    }
}

// MARK: Strokes

enum class GyroAngleKey { theta, e, a, r, phi, psi, psiD, psiG, psiN, rho }

enum class GyroSpringKey { w, k, pitch, arc, floor, tail, dash, glint, notch, clip, cone }

class GyroStroke {
    val angles = GyroAngleKey.entries.map { GyroAngle() }
    val springs = GyroSpringKey.entries.map { GyroValue(neutral[it] ?: 0.0, config = Gyro.MOVE, precision = 0.002) }
    var m = 11.0
    var pitch0 = 0.0

    init {
        this[GyroAngleKey.a].set(0.45)
        this[GyroAngleKey.phi].set(Gyro.Sinv(0.0))
    }

    operator fun get(key: GyroAngleKey): GyroAngle = angles[key.ordinal]

    operator fun get(key: GyroSpringKey): GyroValue = springs[key.ordinal]

    fun copyPose(o: GyroStroke) {
        for (key in GyroAngleKey.entries) this[key].copy(o[key])
        for (key in GyroSpringKey.entries) {
            if (key == GyroSpringKey.k) continue
            this[key].value = o[key].value
            this[key].velocity = o[key].velocity
            this[key].target = o[key].target
        }
        m = o.m
        pitch0 = o.pitch0
    }

    val s: Double get() = Gyro.S(this[GyroAngleKey.phi].value)
    val visible: Boolean get() = this[GyroSpringKey.k].value > 0.02

    companion object {
        val neutral: Map<GyroSpringKey, Double> = mapOf(
            GyroSpringKey.w to 0.2, GyroSpringKey.pitch to 0.0, GyroSpringKey.arc to 3.6, GyroSpringKey.floor to 0.0,
            GyroSpringKey.tail to 0.0, GyroSpringKey.dash to 0.0, GyroSpringKey.glint to 0.0, GyroSpringKey.notch to 0.0,
            GyroSpringKey.clip to 2.0, GyroSpringKey.cone to 0.0,
        )
    }
}

/// A target for one of a stroke's angles.
sealed interface GyroPose {
    data class hold(val value: Double, val period: Double = Gyro.TAU, val cfg: GyroSpring? = null) : GyroPose

    /// Spins at the speed; under Reduce Motion it holds `still`.
    data class spin(val w: Double, val still: Double? = null, val cfg: GyroSpring? = null) : GyroPose
}

/// One stroke's retarget, like the prototype's spec objects.
data class GyroSpec(
    val angles: Map<GyroAngleKey, GyroPose> = emptyMap(),
    /// Latitude: a number holds it there; a pose rolls it.
    val s: GyroPose? = null,
    val springs: Map<GyroSpringKey, Double> = emptyMap(),
    val k: Double? = null,
    val m: Double? = null,
    val pitch0: Double? = null,
    val cfg: GyroSpring? = null,
) {
    /// `{ ...self, ...other }`.
    fun with(
        angles: Map<GyroAngleKey, GyroPose> = emptyMap(),
        s: GyroPose? = null,
        latitude: Double? = null,
        springs: Map<GyroSpringKey, Double> = emptyMap(),
        k: Double? = null,
        m: Double? = null,
        pitch0: Double? = null,
        cfg: GyroSpring? = null,
    ): GyroSpec {
        var spec = copy(angles = this.angles + angles, springs = this.springs + springs)
        if (latitude != null) spec = spec.copy(s = GyroPose.hold(Gyro.Sinv(latitude)))
        if (s != null) spec = spec.copy(s = s)
        return spec.copy(k = k ?: spec.k, m = m ?: spec.m, pitch0 = pitch0 ?: spec.pitch0, cfg = cfg ?: spec.cfg)
    }

    companion object {
        val FACE = GyroSpec(
            angles = mapOf(GyroAngleKey.theta to GyroPose.hold(Gyro.HALF), GyroAngleKey.e to GyroPose.hold(0.0), GyroAngleKey.a to GyroPose.hold(0.0), GyroAngleKey.r to GyroPose.hold(0.0)),
        )
        val LEVEL = GyroSpec(
            angles = mapOf(GyroAngleKey.theta to GyroPose.hold(Gyro.HALF), GyroAngleKey.e to GyroPose.hold(Gyro.HALF), GyroAngleKey.a to GyroPose.hold(0.0), GyroAngleKey.r to GyroPose.hold(0.0)),
        )

        fun MERIDIAN(w: Double = Gyro.TAU / 1.8) = GyroSpec(
            angles = mapOf(GyroAngleKey.theta to GyroPose.spin(w), GyroAngleKey.e to GyroPose.hold(0.0), GyroAngleKey.a to GyroPose.hold(0.45), GyroAngleKey.r to GyroPose.hold(0.0)),
            s = GyroPose.hold(Gyro.Sinv(0.0)),
        )
    }
}

// MARK: Designs

/// One state's design: how it enters, what it does every frame, and how it
/// answers tool calls and output.
class GyroDesign(
    val id: String,
    val enter: (ActivityGyro) -> Unit,
    val tick: ((ActivityGyro, Double) -> Unit)? = null,
    val onTool: ((ActivityGyro) -> Unit)? = null,
    val onOutput: ((ActivityGyro) -> Unit)? = null,
    /// Where a tick settles under Reduce Motion.
    val stillAge: Double? = null,
)

// MARK: The glyph

class ActivityGyro(op: AgentActivity = AgentActivity.idle, val reduceMotion: Boolean = false, seed: Double = 0.0) {
    enum class Global {
        body, flash, glow, ring, comet, orange, red, green, blue;

        companion object {
            fun accentOf(accent: ActivityAccent): Global = when (accent) {
                ActivityAccent.orange -> orange
                ActivityAccent.red -> red
                ActivityAccent.green -> green
                ActivityAccent.blue -> blue
            }
        }
    }

    var op: AgentActivity = op
        private set
    private var design: GyroDesign = designs.getValue(op)
    val strokes = (0 until 4).map { GyroStroke() }
    val globals = Global.entries.map { GyroValue(0.0, config = Gyro.FADE, precision = 0.002) }
    var immediate = false
    private var snap = false
    var age = 0.0
    private var t: Double = seed

    /// Nothing will change until the next `set` or `event`.
    var isStatic = false
        private set

    // Rim chase's entry state.
    var chaseFacing: Double? = null
    var chaseRolls: Boolean? = null
    var chaseS0: Double? = null

    init {
        for ((i, b) in strokes.withIndex()) b[GyroAngleKey.theta].set(seed + i * 1.1)
        this[Global.flash].config = GyroSpring.smooth(0.32)
        set(op, immediate = true)
    }

    operator fun get(key: Global): GyroValue = globals[key.ordinal]

    fun set(op: AgentActivity, immediate: Boolean = false) {
        this.op = op
        design = designs.getValue(op)
        age = 0.0
        this.immediate = immediate
        for (accent in ActivityAccent.entries) {
            global(mapOf(Global.accentOf(accent) to if (stateAccent[op] == accent) 1.0 else 0.0))
        }
        // Afterglow decays rather than switching off: it shrinks with the band's speed as it goes.
        global(mapOf(Global.comet to 0.0), Gyro.SLOW)
        for (b in strokes) {
            for ((key, value) in GyroStroke.neutral) b[key].to(value, if (reduceMotion) Gyro.RM else Gyro.MOVE, immediate = immediate)
        }
        design.enter(this)
        if (reduceMotion || immediate) design.tick?.invoke(this, if (reduceMotion) design.stillAge ?: 0.5 else 0.0)
        if (immediate) {
            for (b in strokes) {
                for (angle in b.angles) {
                    if (angle.mode == GyroAngle.Mode.hold) {
                        angle.value = angle.anchor
                        angle.vel = 0.0
                    }
                }
                for (spring in b.springs) spring.set(spring.target)
            }
        }
        this.immediate = false
        isStatic = false
    }

    /// Retargets one stroke. A hidden stroke about to show starts as a copy of
    /// stroke 0, so new strokes split off the band rather than fading in.
    fun stroke(i: Int, spec: GyroSpec) {
        val b = strokes[i]
        if (!b.visible && (spec.k ?: 0.0) > 0 && !immediate) {
            val lead = strokes[0]
            if (i > 0 && lead.visible) {
                b.copyPose(lead)
                b[GyroSpringKey.k].set(0.0)
            } else {
                snap = true
            }
        }
        val cfg = if (reduceMotion) Gyro.RM else spec.cfg ?: Gyro.MOVE
        val now = immediate || snap
        spec.m?.let { b.m = it }
        spec.pitch0?.let { b.pitch0 = it }
        for ((key, pose) in spec.angles.entries.sortedBy { it.key.ordinal }) angle(b[key], pose, cfg, now, i)
        val s = spec.s
        if (s != null) {
            // A number holds the latitude with the stroke's spring; a pose rolls it.
            if (s is GyroPose.hold && s.cfg == null) {
                b[GyroAngleKey.phi].hold(s.value, Gyro.TAU, immediate = now, cfg = cfg)
            } else {
                angle(b[GyroAngleKey.phi], s, cfg, now, i)
            }
        }
        for ((key, value) in spec.springs) b[key].to(value, cfg, immediate = now)
        spec.k?.let { b[GyroSpringKey.k].to(it, if (reduceMotion) Gyro.RM else Gyro.FADE, immediate = immediate) }
        snap = false
    }

    private fun angle(ang: GyroAngle, pose: GyroPose, cfg: GyroSpring, now: Boolean, i: Int) {
        when (pose) {
            is GyroPose.spin -> if (reduceMotion) {
                ang.hold(pose.still ?: (0.55 + i * 0.9), Gyro.TAU, immediate = now, cfg = Gyro.RM)
            } else {
                ang.spin(pose.w, pose.cfg ?: cfg, immediate = now)
            }
            is GyroPose.hold -> ang.hold(pose.value, pose.period, immediate = now, cfg = pose.cfg ?: if (reduceMotion) Gyro.RM else null)
        }
    }

    /// Fades strokes out while drawing them toward stroke 0, so they merge rather than vanish in place.
    fun off(vararg indices: Int) {
        val lead = strokes[0]
        for (i in indices) {
            val b = strokes[i]
            b[GyroSpringKey.k].to(0.0, if (reduceMotion) Gyro.RM else Gyro.FADE, immediate = immediate)
            if (!b.visible || immediate) continue
            for (key in listOf(GyroAngleKey.e, GyroAngleKey.a, GyroAngleKey.r)) b[key].hold(lead[key].anchor, Gyro.TAU)
            if (lead[GyroAngleKey.theta].mode == GyroAngle.Mode.spin) {
                b[GyroAngleKey.theta].spin(lead[GyroAngleKey.theta].speed.target)
            } else {
                b[GyroAngleKey.theta].hold(lead[GyroAngleKey.theta].anchor, Gyro.TAU)
            }
            // Stripes carry on the way they were going and settle onto the lead's latitude.
            if (lead[GyroAngleKey.phi].mode == GyroAngle.Mode.hold) b[GyroAngleKey.phi].hold(lead[GyroAngleKey.phi].anchor, Gyro.TAU)
        }
    }

    fun global(values: Map<Global, Double>, cfg: GyroSpring? = null) {
        for ((key, value) in values) this[key].to(value, if (reduceMotion) Gyro.RM else cfg ?: Gyro.FADE, immediate = immediate)
    }

    /// Adds roughly `peak` to a global spring and lets it decay.
    fun impulse(key: Global, peak: Double) {
        if (reduceMotion || immediate) return
        val w = Gyro.TAU / this[key].config.response
        this[key].velocity += peak * E * w
    }

    enum class Event { tool, output }

    fun event(kind: Event) {
        if (reduceMotion) return
        isStatic = false
        when (kind) {
            Event.tool -> design.onTool?.invoke(this)
            Event.output -> design.onOutput?.invoke(this)
        }
    }

    /// Advances by dt. False once nothing will change until the next `set` or `event`.
    fun step(dt: Double): Boolean {
        if (isStatic) return false
        t += dt
        age += dt
        if (!reduceMotion) design.tick?.invoke(this, age)
        var moving = !reduceMotion && design.tick != null
        for (b in strokes) {
            for (angle in b.angles) {
                angle.step(dt)
                if (b[GyroSpringKey.k].value > 0.003 || b[GyroSpringKey.k].target > 0) moving = angle.moving || moving
            }
            for (spring in b.springs) moving = spring.step(dt) || moving
        }
        for (value in globals) moving = value.step(dt) || moving
        isStatic = !moving
        return true
    }

    fun fill(cells: List<GyroCell>): GyroField {
        class Live(
            val b: GyroStroke, val k: Double, val s: Double, val w: Double, val n: Vec3, val u: Vec3, val v: Vec3,
            val taps: List<Vec3>, val pitch: Double, val arc: Double, val floor: Double, val tail: Double, val dir: Double,
            val dash: Double, val glint: Double, val notch: Double, val clip: Double, val psi: Double, val psiD: Double, val psiG: Double, val psiN: Double,
        )
        val comet = max(0.0, this[Global.comet].value)
        val live = mutableListOf<Live>()
        for (b in strokes) {
            val k = b[GyroSpringKey.k].value * Gyro.visS(b.s, b[GyroSpringKey.w].value, b[GyroSpringKey.pitch].value)
            if (k < 0.003) continue
            val cone = b[GyroSpringKey.cone].value
            val a = b[GyroAngleKey.a].value + cone * sin(b[GyroAngleKey.rho].value)
            val r = b[GyroAngleKey.r].value + cone * cos(b[GyroAngleKey.rho].value)
            val frame = Gyro.frame(b[GyroAngleKey.theta].value, b[GyroAngleKey.e].value, a, r)
            val taps = mutableListOf<Vec3>()
            if (comet > 0.01) {
                for (j in 1..3) {
                    val lag = j * 0.05
                    taps += Gyro.frame(
                        b[GyroAngleKey.theta].value - b[GyroAngleKey.theta].vel * lag,
                        b[GyroAngleKey.e].value - b[GyroAngleKey.e].vel * lag,
                        a - b[GyroAngleKey.a].vel * lag,
                        r - b[GyroAngleKey.r].vel * lag,
                    ).n
                }
            }
            live += Live(
                b = b, k = k, s = b.s, w = b[GyroSpringKey.w].value, n = frame.n, u = frame.u, v = frame.v, taps = taps,
                pitch = b[GyroSpringKey.pitch].value, arc = b[GyroSpringKey.arc].value, floor = b[GyroSpringKey.floor].value, tail = b[GyroSpringKey.tail].value,
                dir = if (Gyro.sign(b[GyroAngleKey.psi].vel) == 0.0) -1.0 else Gyro.sign(b[GyroAngleKey.psi].vel),
                dash = b[GyroSpringKey.dash].value, glint = b[GyroSpringKey.glint].value, notch = b[GyroSpringKey.notch].value, clip = b[GyroSpringKey.clip].value,
                psi = b[GyroAngleKey.psi].value, psiD = b[GyroAngleKey.psiD].value, psiG = b[GyroAngleKey.psiG].value, psiN = b[GyroAngleKey.psiN].value,
            )
        }
        val ring = this[Global.ring].value
        val flash = max(0.0, this[Global.flash].value)
        val body = this[Global.body].value
        val ringN = if (ring > 1e-3) Gyro.frame(0.0, Gyro.HALF, strokes[0][GyroAngleKey.a].value, 0.0).n else Vec3.zero
        val accent = Gyro.clamp(ActivityAccent.entries.sumOf { max(0.0, this[Global.accentOf(it)].value) }, 0.0, 1.0)
        val dots = ArrayList<GyroField.Dot>(cells.size)
        for (cell in cells) {
            val p = Vec3(cell.nx, cell.ny, cell.nz)
            var lit = 0.0
            var fresh = 0.0
            for (L in live) {
                val dn = p.dot(L.n)
                val needLam = L.pitch > 1e-3 || L.arc < 3.4 || L.dash > 1e-3 || L.glint > 1e-3 || L.notch > 1e-3
                val pu = p.dot(L.u)
                val pv = p.dot(L.v)
                val lam = if (needLam) atan2(pv, pu) else 0.0
                // Angle along the stroke is undefined on its axis; windows fade out there instead of flipping.
                val on = if (needLam) Gyro.sstep(0.04, 0.3, sqrt(pu * pu + pv * pv)) else 1.0
                val d = dn - L.s + if (L.pitch > 1e-3) 0.5 * on * L.pitch * cos(lam - L.b.pitch0) else 0.0
                var g = Gyro.gauss(d, L.w) * L.k * (0.65 + 0.35 * cell.nz)
                if (L.arc < 3.4) {
                    val ds = Gyro.wrap(lam - L.psi)
                    var win = Gyro.sstep(L.arc + 0.12, L.arc - 0.12, abs(ds))
                    if (L.tail > 1e-3 && ds * L.dir < 0) win = max(win, L.tail * exp(-max(0.0, abs(ds) - L.arc) / 0.55))
                    g *= 1 - on * (1 - max(L.floor, win))
                }
                // A few dashes are clusters a couple of dots long; many are single dots.
                if (L.dash > 1e-3) {
                    g *= 1 - on * L.dash * (1 - (0.5 + 0.5 * cos(L.b.m * (lam - L.psiD))).pow(if (L.b.m <= 4) 1.5 else 4.0))
                }
                if (L.glint > 1e-3) g *= 1 - on * L.glint * (1 - Gyro.gauss(Gyro.wrap(lam - L.psiG), 0.42))
                if (L.notch > 1e-3) g *= 1 - on * L.notch * Gyro.gauss(Gyro.wrap(lam - L.psiN), 0.5)
                if (L.clip < 1.5) g *= Gyro.sstep(0.12, -0.12, cell.nx - L.clip)
                for ((j, tap) in L.taps.withIndex()) {
                    val dt = p.dot(tap) - L.s
                    g = max(g, Gyro.gauss(dt, L.w) * L.k * comet * 0.5.pow(j + 1) * 0.9)
                }
                lit = 1 - (1 - lit) * (1 - min(1.0, g))
                fresh = max(fresh, g)
            }
            var extra = 0.0
            if (ring > 1e-3) {
                val d = p.dot(ringN)
                extra += ring * exp(-d * d / 0.045)
            }
            val P = (0.14 + 0.4 * cell.shade.pow(1.2)) * body + 0.95 * lit + extra + flash * (0.25 + 0.45 * cell.shade)
            dots += GyroField.Dot(light = P, fresh = fresh * 0.8, accent = accent * Gyro.sstep(0.3, 0.65, P), size = cell.size)
        }
        val weights = ActivityAccent.entries.associateWith { max(0.0, this[Global.accentOf(it)].value) }
        return GyroField(dots = dots, glow = this[Global.glow].value + 0.6 * flash, weights = weights)
    }

    companion object {
        /// Kit's set (`prototypes/activity-glyph/kit-set.json`), one design per state.
        val designs: Map<AgentActivity, GyroDesign> by lazy { makeDesigns() }

        val stateAccent: Map<AgentActivity, ActivityAccent> = mapOf(
            AgentActivity.waiting to ActivityAccent.orange,
            AgentActivity.failed to ActivityAccent.red,
            AgentActivity.done to ActivityAccent.green,
            AgentActivity.handoff to ActivityAccent.blue,
        )

        /// Rim chase entering from a band: the band lies down onto the rim, then
        /// draws in to its ring and breaks into clusters. Nothing sweeps through the centre.
        private fun chaseGate(g: ActivityGyro) {
            val b = g.strokes[0]
            // Only ever grows, so a band that must turn the long way round doesn't push the ring out and back.
            val now = Gyro.sstep(0.55, 0.97, cos(Gyro.wrap(b[GyroAngleKey.theta].value - Gyro.HALF)) * cos(b[GyroAngleKey.a].value) * cos(b[GyroAngleKey.e].value))
            val facing = if (g.age == 0.0) now else max(g.chaseFacing ?: 0.0, now)
            g.chaseFacing = facing
            // Entering while the stripes roll: commit once to the ring's latitude ahead of them and decelerate
            // into it. Otherwise follow the band down onto the rim and in to its ring.
            if (g.age == 0.0 || g.chaseRolls == null) g.chaseRolls = abs(b[GyroAngleKey.phi].vel) > Gyro.MOVING
            if (g.chaseRolls == true) {
                if (b[GyroAngleKey.phi].mode != GyroAngle.Mode.hold || g.age == 0.0) b[GyroAngleKey.phi].hold(Gyro.Sinv(Gyro.ringS(0.8)), Gyro.TAU)
            } else {
                if (g.age == 0.0) g.chaseS0 = Gyro.clamp(b.s, -1.0, 1.0)
                val s0 = g.chaseS0 ?: 0.0
                b[GyroAngleKey.phi].follow(Gyro.near(b[GyroAngleKey.phi], Gyro.Sinv(s0 + (Gyro.ringS(0.8) - s0) * facing)), Gyro.FAST)
            }
            b[GyroSpringKey.dash].to(0.8 * Gyro.sstep(0.4, 1.0, facing), Gyro.FAST)
        }

        /// Rings only read face-on: until the band has turned to face the viewer, its latitude waits.
        private fun basePingGate(g: ActivityGyro) {
            val b = g.strokes[0]
            if (g.reduceMotion || b[GyroAngleKey.phi].mode != GyroAngle.Mode.spin) return
            val facing = abs(Gyro.wrap(b[GyroAngleKey.theta].value - Gyro.HALF)) < 0.3 && abs(b[GyroAngleKey.theta].vel) < 2
            val w = if (facing) Gyro.TAU / 0.9 else 0.0
            if (b[GyroAngleKey.phi].speed.target != w) b[GyroAngleKey.phi].spin(w, if (facing) GyroSpring.smooth(0.5) else Gyro.FAST, immediate = g.immediate)
        }

        /// Strokes 1…n−1 follow stroke 0's latitude, each `offset` further on. Trains only run forward.
        private fun train(g: ActivityGyro, n: Int, offset: Double, cfg: GyroSpring = Gyro.FAST) {
            val lead = g.strokes[0][GyroAngleKey.phi].value
            val w = Gyro.TAU / cfg.response
            for (i in 1 until n) {
                val b = g.strokes[i][GyroAngleKey.phi]
                val target = Gyro.near(b, lead + i * offset)
                b.follow(max(target, b.value + max(0.0, b.vel) / w), cfg)
            }
        }

        private fun makeDesigns(): Map<AgentActivity, GyroDesign> {
            val G = Gyro
            val TAU = G.TAU
            val HALF = G.HALF
            return mapOf(
                // Dim band: a dim meridian turning slowly.
                AgentActivity.idle to GyroDesign("band", enter = { g ->
                    g.stroke(0, GyroSpec.MERIDIAN(TAU / 6).with(springs = mapOf(GyroSpringKey.w to 0.2), k = 0.45)); g.off(1, 2, 3)
                    g.global(mapOf(Global.body to 1.0, Global.glow to 0.2, Global.ring to 0.08))
                }),
                // Rings: rings open from a centre dot to the rim, one after another.
                AgentActivity.startup to GyroDesign("ping", enter = { g ->
                    g.stroke(0, GyroSpec.FACE.with(s = GyroPose.spin(TAU / 0.9, still = G.Sinv(0.8)), springs = mapOf(GyroSpringKey.w to 0.11), k = 1.0)); g.off(1, 2, 3)
                    g.global(mapOf(Global.body to 0.8, Global.glow to 0.55, Global.ring to 0.0)); basePingGate(g)
                }, tick = { g, _ -> basePingGate(g) }),
                // Comet: the band trails phosphor afterglow behind its motion.
                AgentActivity.thinking to GyroDesign("comet", enter = { g ->
                    g.stroke(0, GyroSpec.MERIDIAN().with(k = 0.95)); g.off(1, 2, 3)
                    g.global(mapOf(Global.body to 1.0, Global.glow to 0.5, Global.ring to 0.1, Global.comet to 1.0))
                }),
                // Spiral churn: a three-stripe spiral turns on a tilted axis, its pitch slowly breathing.
                AgentActivity.longWork to GyroDesign("helix", enter = { g ->
                    for (i in 0 until 3) {
                        g.stroke(
                            i,
                            GyroSpec(
                                angles = mapOf(GyroAngleKey.theta to GyroPose.hold(HALF), GyroAngleKey.e to GyroPose.hold(HALF), GyroAngleKey.a to GyroPose.hold(0.4), GyroAngleKey.r to GyroPose.hold(0.2)),
                                springs = mapOf(GyroSpringKey.w to 0.13, GyroSpringKey.pitch to 0.5),
                                k = 0.8,
                            ),
                        )
                    }
                    g.stroke(0, GyroSpec(s = GyroPose.spin(TAU / 3, still = G.Sinv(0.2))))
                    g.off(3); g.global(mapOf(Global.body to 0.9, Global.glow to 0.55, Global.ring to 0.0))
                }, tick = { g, age ->
                    train(g, 3, TAU / 3)
                    for (i in 0 until 3) g.strokes[i][GyroSpringKey.pitch].to(0.5 + 0.25 * sin(age * 0.7), G.SLOW)
                }, stillAge = 2.0),
                // Spin kick: each call kicks the turning band faster and flashes the orb.
                AgentActivity.tool to GyroDesign("kick", enter = { g ->
                    g.stroke(0, GyroSpec.MERIDIAN().with(k = 0.95)); g.off(1, 2, 3)
                    g.global(mapOf(Global.body to 1.0, Global.glow to 0.55, Global.ring to 0.2))
                }, onTool = { g ->
                    g.strokes[0][GyroAngleKey.theta].kick(TAU * 0.45); g.impulse(Global.flash, 0.08)
                }),
                // Spiral: tilted stripes rise out of the lower pole and into the upper, faster as output pours in.
                AgentActivity.streaming to GyroDesign("spiral", enter = { g ->
                    for (i in 0 until 3) g.stroke(i, GyroSpec.LEVEL.with(springs = mapOf(GyroSpringKey.w to 0.12, GyroSpringKey.pitch to 0.55), k = 0.85))
                    g.stroke(0, GyroSpec(s = GyroPose.spin(TAU / 2.2, still = G.Sinv(0.3))))
                    g.off(3); g.global(mapOf(Global.body to 0.85, Global.glow to 0.45, Global.ring to 0.0))
                }, tick = { g, _ -> train(g, 3, TAU / 3) }, onOutput = { g -> g.strokes[0][GyroAngleKey.phi].kick(0.35) }),
                // Rim chase: the band turns face-on and breaks into three bright clusters that chase slowly round the rim.
                AgentActivity.waiting to GyroDesign("chase", enter = { g ->
                    // A turning band's front arc moves left for θ increasing; the clusters peel off at the top
                    // going the same way (anticlockwise), at the band's speed, then ease down to the chase.
                    val b = g.strokes[0]
                    val w = if (b[GyroAngleKey.theta].mode == GyroAngle.Mode.spin) b[GyroAngleKey.theta].speed.value else b[GyroAngleKey.theta].vel
                    val dir = if (abs(w) > G.MOVING) G.sign(w) else -1.0
                    if (!g.immediate && !g.reduceMotion && b.visible && b[GyroSpringKey.dash].value < 0.05 && abs(w) > G.MOVING) {
                        b[GyroAngleKey.psiD].launch(-HALF, dir * min(abs(w), TAU / 0.8))
                    }
                    g.stroke(
                        0,
                        GyroSpec.FACE.with(
                            angles = mapOf(GyroAngleKey.psiD to GyroPose.spin(dir * TAU / 4.5, still = -HALF, cfg = G.SLOW)),
                            latitude = G.ringS(0.8),
                            springs = mapOf(GyroSpringKey.w to 0.24, GyroSpringKey.dash to 0.8),
                            k = 1.0,
                            m = 3.0,
                        ),
                    )
                    g.off(1, 2, 3); g.global(mapOf(Global.body to 0.9, Global.glow to 0.45, Global.ring to 0.0))
                    if (!g.immediate && !g.reduceMotion) chaseGate(g)
                }, tick = { g, _ -> if (!g.reduceMotion) chaseGate(g) }),
                // Slide right: upright bands slide off to the right, one after another.
                AgentActivity.handoff to GyroDesign("slide", enter = { g ->
                    val upright = mapOf(GyroAngleKey.theta to GyroPose.hold(PI), GyroAngleKey.e to GyroPose.hold(0.0), GyroAngleKey.a to GyroPose.hold(0.0), GyroAngleKey.r to GyroPose.hold(0.16))
                    g.stroke(0, GyroSpec(angles = upright, s = GyroPose.spin(TAU / 1.15, still = G.Sinv(0.2)), springs = mapOf(GyroSpringKey.w to 0.17), k = 0.9))
                    g.stroke(1, GyroSpec(angles = upright, springs = mapOf(GyroSpringKey.w to 0.17), k = 0.9))
                    g.off(2, 3); g.global(mapOf(Global.body to 0.9, Global.glow to 0.45, Global.ring to 0.0))
                }, tick = { g, _ -> train(g, 2, PI) }),
                // Ring: the band sweeps on, face-on, into a green ring.
                AgentActivity.done to GyroDesign("ring", enter = { g ->
                    g.stroke(0, GyroSpec.FACE.with(latitude = 0.5, springs = mapOf(GyroSpringKey.w to 0.2), k = 1.0)); g.off(1, 2, 3)
                    g.global(mapOf(Global.body to 0.75, Global.glow to 0.7, Global.ring to 0.0)); g.impulse(Global.flash, 0.06)
                }),
                // ×: the band levels, splits and rolls into a red ×.
                AgentActivity.failed to GyroDesign("cross", enter = { g ->
                    g.stroke(0, GyroSpec.LEVEL.with(angles = mapOf(GyroAngleKey.r to GyroPose.hold(PI / 4, period = PI)), latitude = 0.0, springs = mapOf(GyroSpringKey.w to 0.11), k = 0.95))
                    g.stroke(1, GyroSpec.LEVEL.with(angles = mapOf(GyroAngleKey.r to GyroPose.hold(-PI / 4, period = PI)), latitude = 0.0, springs = mapOf(GyroSpringKey.w to 0.11), k = 0.95))
                    g.off(2, 3); g.global(mapOf(Global.body to 0.8, Global.glow to 0.45, Global.ring to 0.0)); g.impulse(Global.flash, 0.04)
                }),
            )
        }
    }
}

// MARK: The LED field

/// One dot of the orb: its place in the grid, the sphere normal under it, a
/// top-left key light, and a size that shrinks towards the limb.
data class GyroCell(
    val column: Int,
    val row: Int,
    val nx: Double,
    val ny: Double,
    val nz: Double,
    val shade: Double,
    val size: Double,
) {
    companion object {
        /// The orb's cells for an n × n grid (n odd), masked to a disc.
        fun orb(n: Int): List<GyroCell> {
            val half = (n - 1) / 2.0
            val R = n / 2.0 + 0.1
            val L = Vec3(-0.42, -0.52, 0.74)
            val ln = sqrt(L.dot(L))
            val cells = mutableListOf<GyroCell>()
            for (row in 0 until n) {
                for (column in 0 until n) {
                    val x = column - half
                    val y = row - half
                    if (sqrt(x * x + y * y) > n / 2.0) continue
                    val nx = x / R
                    val ny = y / R
                    val nz = sqrt(max(0.0, 1 - nx * nx - ny * ny))
                    val shade = max(0.0, (nx * L.x + ny * L.y + nz * L.z) / ln)
                    cells += GyroCell(column = column, row = row, nx = nx, ny = ny, nz = nz, shade = shade, size = 0.7 + 0.3 * nz)
                }
            }
            return cells
        }

        val seven = orb(7)
    }
}

/// What one frame draws: per dot its light, freshness, accent and size, and
/// the orb's glow and accent colour weights.
data class GyroField(val dots: List<Dot>, val glow: Double, val weights: Map<ActivityAccent, Double>) {
    data class Dot(val light: Double, val fresh: Double, val accent: Double, val size: Double)
}
