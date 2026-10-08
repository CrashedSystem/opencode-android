@file:PortedFrom("OpenCode/Transcript/AgentMessageReveal.swift", "7fa8e81")

package ai.opencode.mobilecode

import android.graphics.BlurMaskFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import kotlin.math.ceil
import kotlin.math.pow

/// A message an agent sent (Voice, the delegator, another session, a parent
/// spawning a subagent) arriving bright and settling (`prototypes/ios-kit/phosphor-reveal`,
/// K4 Inner glow): the bubble fades in while a luminous edge inside it and a
/// slight lift of its fill rise in 70 ms, then decay like phosphor, fast and
/// then a slower tail, to exactly the plain bubble. Under Reduce Motion it
/// only fades in. Messages typed or dictated on this phone never reveal.
object AgentMessageReveal {
    data class Frame(
        /// The light envelope, 0...1.
        val light: Double = 0.0,
        /// The bubble's fill and the Voice indicator.
        val body: Double = 1.0,
        val text: Double = 1.0,
    ) {
        companion object {
            val rest = Frame()
        }
    }

    const val rise = 0.07
    const val halfLife = 0.18
    const val slowHalfLife = 0.32
    const val slowShare = 0.22
    const val innerGlow = 0.7
    const val fillLift = 0.08
    const val fadeIn = 0.14
    const val textStart = 0.06
    const val textDuration = 0.2

    /// About 1.39 s: the light below 2% of its peak, then a final ease to zero.
    val duration: Double = run {
        var end = rise
        while (end < 4 && envelope(end) > 0.02) end += 0.005
        ceil((maxOf(end + 0.04, fadeIn, textStart + textDuration) + 0.01) * 1000) / 1000
    }

    /// 0 → 1 at `rise`, then the two-half-life decay.
    fun envelope(t: Double): Double {
        if (t <= 0) return 0.0
        if (t < rise) return cubic(t / rise)
        val x = t - rise
        return (1 - slowShare) * 2.0.pow(-x / halfLife) + slowShare * 2.0.pow(-x / slowHalfLife)
    }

    /// `-lab-reveal-slowmo 20` plays it 20 times slower, to capture frames.
    val timeScale: Double =
        if (BuildConfig.DEBUG) maxOf(1.0, UserDefaults.standard.string(forKey = "lab-reveal-slowmo")?.toDoubleOrNull() ?: 0.0) else 1.0

    /// Under Reduce Motion the light stays off: the bubble only fades in.
    fun frame(at: Double, reduceMotion: Boolean = false): Frame {
        if (at >= duration) return Frame.rest
        val light = if (reduceMotion) 0.0 else envelope(at) * (1 - smootherstep(duration - 0.22, duration, at))
        return Frame(light = light, body = cubic(at / fadeIn), text = cubic((at - textStart) / textDuration))
    }

    data class Colors(val lift: Color, val glow: Color)

    /// The colour the fill lifts toward and the inner edge's light: the voice
    /// scope's cool pearl, a cool light (not a grey shade) in light mode.
    fun colors(dark: Boolean): Colors =
        if (dark) Colors(Color(0.30f, 0.33f, 0.39f), Color(0.72f, 0.80f, 0.98f)) else Colors(Color(0.90f, 0.93f, 1.0f), Color(0.748f, 0.822f, 1.0f))

    private fun cubic(x: Double): Double = 1 - (1 - x.coerceIn(0.0, 1.0)).pow(3)

    private fun smootherstep(a: Double, b: Double, x: Double): Double {
        val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
        return t * t * t * (t * (6 * t - 15) + 10)
    }
}

/// The light inside the bubble: its fill lifted, and an edge glowing inward
/// (the prototype's exp(-depth / 7 pt) falloff, from two blurred strokes).
fun DrawScope.drawAgentMessageRevealLight(light: Float, dark: Boolean, density: Float, alpha: Float = 1f) {
    if (light <= 0f) return
    val colors = AgentMessageReveal.colors(dark)
    val radius = TranscriptMetrics.bubbleCornerRadius * density
    val bubble = Path().apply { addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(radius))) }
    clipPath(bubble) {
        drawRoundRect(colors.lift, cornerRadius = CornerRadius(radius), alpha = (AgentMessageReveal.fillLift * light).toFloat() * alpha)
        val glow = (AgentMessageReveal.innerGlow * light).toFloat() * alpha
        for ((width, blur, opacity) in listOf(Triple(8f, 2.5f, 0.7f), Triple(16f, 9.5f, 0.6f))) {
            drawIntoCanvas { canvas ->
                val paint = Paint().apply {
                    color = colors.glow.copy(alpha = opacity * glow)
                    style = PaintingStyle.Stroke
                    strokeWidth = width * density
                    asFrameworkPaint().maskFilter = BlurMaskFilter(blur * density, BlurMaskFilter.Blur.NORMAL)
                }
                canvas.drawRoundRect(0f, 0f, size.width, size.height, radius, radius, paint)
            }
        }
    }
}

/// When each agent's message arrived, so its reveal plays once, on arrival. The
/// list owns it, like `TranscriptTextReveal`, and hands each row its arrival
/// as a value: a reused, reconfigured, or reloaded cell reads the same clock,
/// and a message it never saw arrive (opening a session, a page of history)
/// has no reveal. Times are `System.nanoTime`, the frame clock's base.
class TranscriptAgentMessageReveal {
    private val arrivals = mutableMapOf<String, Long>()

    /// Records `messageID` arriving now; false if it was already seen.
    fun arrive(messageID: String, at: Long): Boolean {
        if (arrivals[messageID] != null) return false
        arrivals[messageID] = at
        return true
    }

    fun arrival(of: String): Long? = arrivals[of]
}

/// Samples the reveal each frame while it runs, then rests: a finished or
/// absent reveal draws `.rest`, which is the plain bubble.
@Composable
fun AgentMessageRevealClock(arrival: Long?, content: @Composable (AgentMessageReveal.Frame) -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    val scale = AgentMessageReveal.timeScale
    val ends = arrival?.let { it + (AgentMessageReveal.duration * scale * 1e9).toLong() }
    var now by remember { mutableLongStateOf(System.nanoTime()) }
    LaunchedEffect(arrival) {
        if (ends == null) return@LaunchedEffect
        while (now < ends) withFrameNanos { now = it }
    }
    val active = ends != null && now < ends
    content(
        if (active) {
            AgentMessageReveal.frame(at = (now - arrival) / 1e9 / scale, reduceMotion = reduceMotion)
        } else {
            AgentMessageReveal.Frame.rest
        },
    )
}
