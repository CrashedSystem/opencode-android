@file:PortedFrom("OpenCode/SessionContextRing.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.SystemColors
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

val LocalIncreasedContrast = staticCompositionLocalOf { false }
val LocalReduceMotion = staticCompositionLocalOf { false }

object SessionContextRing {
    fun percent(fraction: Double): Int = (fraction * 100).roundToInt()

    @Composable
    fun tint(fraction: Double) = when {
        fraction >= 0.95 -> SystemColors.systemRed
        fraction >= 0.8 -> SystemColors.systemOrange
        else -> SystemColors.label
    }

    @Composable
    operator fun invoke(
        fraction: Double,
        modifier: Modifier = Modifier,
        lineWidth: Dp = 3.5.dp,
        trackOpacity: Double = 0.2,
    ) {
        val reducedMotion = LocalReduceMotion.current
        val contrast = LocalIncreasedContrast.current
        val clamped = fraction.coerceIn(0.0, 1.0).toFloat()
        val animated by animateFloatAsState(
            clamped,
            animationSpec = tween(if (reducedMotion) 0 else 450),
            label = "context-ring",
        )
        val track = (if (contrast) trackOpacity * 2 else trackOpacity).toFloat()
        val strokeColor = tint(fraction)
        val trackColor = SystemColors.label.copy(alpha = track)
        Canvas(
            modifier
                .padding(lineWidth / 2)
                .semantics { invisibleToUser() },
        ) {
            val stroke = Stroke(width = lineWidth.toPx(), cap = StrokeCap.Butt)
            drawCircle(color = trackColor, style = stroke)
            drawArc(
                color = strokeColor,
                startAngle = -90f,
                sweepAngle = 360f * animated,
                useCenter = false,
                style = stroke,
            )
        }
    }
}

object SessionContextText {
    fun summary(tokens: Int, limit: Int?): String {
        if (limit == null || limit <= 0) return "${grouped(tokens)} tokens"
        val percent = SessionContextRing.percent(tokens.toDouble() / limit.toDouble())
        return "$percent% of ${compact(limit)}"
    }

    fun accessibilityValue(tokens: Int, limit: Int?): String {
        if (limit == null || limit <= 0) return "${grouped(tokens)} tokens"
        val percent = SessionContextRing.percent(tokens.toDouble() / limit.toDouble())
        return "$percent% used of ${grouped(limit)} tokens"
    }

    private fun grouped(value: Int) = NumberFormat.getIntegerInstance(Locale.getDefault()).format(value)

    private fun compact(value: Int): String {
        val abs = kotlin.math.abs(value.toDouble())
        val scaled = when {
            abs >= 1_000_000_000 -> abs / 1_000_000_000 to "B"
            abs >= 1_000_000 -> abs / 1_000_000 to "M"
            abs >= 1_000 -> abs / 1_000 to "K"
            else -> return grouped(value)
        }
        val number = scaled.first
        val digits = if (number >= 10 || number == number.toInt().toDouble()) number.roundToInt().toString() else ((number * 10).roundToInt() / 10.0).toString()
        return digits + scaled.second
    }
}
