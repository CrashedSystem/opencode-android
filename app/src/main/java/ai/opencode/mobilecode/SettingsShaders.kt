@file:PortedFrom("OpenCode/SettingsShaders.metal", "494e433")

package ai.opencode.mobilecode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntSize
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/// Seconds since the header appeared, advancing every frame unless paused,
/// like `TimelineView(.animation(paused:))`.
@Composable
fun settingsAuraTime(paused: Boolean): Float {
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(paused) {
        if (paused) return@LaunchedEffect
        // The frame clock, so a test's clock drives it like the animations.
        val start = withFrameNanos { it } - (time * 1e9f).toLong()
        while (true) withFrameNanos { time = (it - start) / 1e9f }
    }
    return time
}

/// `colorEffect(ShaderLibrary.settingsAura(…))` behind the content: a slow
/// field of soft light in the connection's color, drawn as a dot matrix like
/// the call's scope. `phase` 0 is still. Metal shades every pixel; this draws
/// what the shader computes for each 7 pt cell: its round dot over a soft
/// glow, the glow from one pixel per cell scaled up smoothly.
fun Modifier.settingsAura(time: Float, tint: Color, phase: Float, dark: Boolean): Modifier = drawWithCache {
    val scale = density
    val width = size.width / scale
    val height = size.height / scale
    val columns = max(1, (width / cellSize).toInt() + 1)
    val rows = max(1, (height / cellSize).toInt() + 1)
    val glowField = ImageBitmap(columns, rows)
    val pixels = IntArray(columns * rows)
    val glows = FloatArray(columns * rows)
    val base = if (dark) Color(0.06f, 0.06f, 0.07f) else Color(0.97f, 0.97f, 0.98f)
    val amount = if (dark) 0.9f else 0.75f
    onDrawBehind {
        val t = time * 0.18f * phase
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val u = (column + 0.5f) * cellSize / width
                val v = (row + 0.5f) * cellSize / height
                // Two drifting blobs and a gentle wave.
                val a = 0.5f + 0.5f * sin(u * 3.1f + t * 1.7f + sin(v * 2.3f - t))
                val b = 0.5f + 0.5f * sin(v * 4.0f - t * 1.3f + cos(u * 1.7f + t * 0.6f))
                val glow = smoothstep(0.15f, 1f, a * 0.6f + b * 0.55f) * (1f - v * 0.55f)
                glows[row * columns + column] = glow
                pixels[row * columns + column] = base.mix(tint, 0.10f * glow * amount).toArgb()
            }
        }
        glowField.asAndroidBitmap().setPixels(pixels, 0, columns, 0, 0, columns, rows)
        drawImage(
            glowField,
            dstSize = IntSize((columns * cellSize * scale).toInt(), (rows * cellSize * scale).toInt()),
            filterQuality = FilterQuality.Low,
        )
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val glow = glows[row * columns + column]
                // Dot matrix: 7 pt cells, round dots that swell where the light is.
                drawCircle(
                    tint,
                    radius = mix(0.7f, 2.3f, glow) * scale,
                    center = Offset((column + 0.5f) * cellSize * scale, (row + 0.5f) * cellSize * scale),
                    alpha = ((0.10f + 0.55f * glow) * amount).coerceIn(0f, 1f),
                )
            }
        }
    }
}

private const val cellSize = 7f

private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3 - 2 * t)
}

private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t

private fun Color.mix(other: Color, t: Float) = Color(
    red + (other.red - red) * t,
    green + (other.green - green) * t,
    blue + (other.blue - blue) * t,
    1f,
)
