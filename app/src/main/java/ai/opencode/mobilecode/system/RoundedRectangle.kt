package ai.opencode.mobilecode.system

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * SwiftUI's `RoundedRectangle(cornerRadius:)`, or `.rect(cornerRadius:)`, which iOS draws with
 * continuous corners. Each corner eases out of its edges before it bends, where a circular
 * corner meets them at the arc's tangent. The curve is Figma's corner smoothing at 60%, which
 * matches iOS. On a shape too short for the eased corner, the easing gives way and the radius
 * stays, as it does on iOS.
 */
data class RoundedRectangle(val cornerRadius: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val budget = min(size.width, size.height) / 2
        val radius = with(density) { cornerRadius.toPx() }.coerceAtMost(budget)
        if (radius <= 0f) return Outline.Rectangle(size.toRect())
        val smoothing = (budget / radius - 1).coerceIn(0f, 0.6f)
        val extent = min((1 + smoothing) * radius, budget)
        val arc = 90 * (1 - smoothing)
        val chord = sin(arc / 2 * degree) * radius * sqrt(2f)
        val c = radius * tan((90 - arc) / 4 * degree) * cos(45 * smoothing * degree)
        val d = c * tan(45 * smoothing * degree)
        val b = (extent - chord - c - d) / 3
        val a = 2 * b
        val path = Path()
        corners(size).forEachIndexed { index, corner ->
            val start = corner.at(extent, 0f)
            if (index == 0) path.moveTo(start.x, start.y) else path.lineTo(start.x, start.y)
            path.cubicTo(corner.at(extent - a, 0f), corner.at(extent - a - b, 0f), corner.at(extent - a - b - c, d))
            path.arcTo(Rect(corner.at(radius, radius), radius), 90f * index - 45 - arc / 2, arc, false)
            path.cubicTo(corner.at(0f, extent - a - b), corner.at(0f, extent - a), corner.at(0f, extent))
        }
        path.close()
        return Outline.Generic(path)
    }
}

/** A corner of the shape, clockwise from the top right, and the directions of the edge that
 * reaches it and the edge that leaves it. */
private class Corner(val point: Offset, val incoming: Offset, val outgoing: Offset) {
    /** The point [back] before the corner on its incoming edge, moved [on] along the outgoing one. */
    fun at(back: Float, on: Float) = point - incoming * back + outgoing * on
}

private fun corners(size: Size) = listOf(
    Corner(Offset(size.width, 0f), Offset(1f, 0f), Offset(0f, 1f)),
    Corner(Offset(size.width, size.height), Offset(0f, 1f), Offset(-1f, 0f)),
    Corner(Offset(0f, size.height), Offset(-1f, 0f), Offset(0f, -1f)),
    Corner(Offset.Zero, Offset(0f, -1f), Offset(1f, 0f)),
)

private fun Path.cubicTo(first: Offset, second: Offset, end: Offset) = cubicTo(first.x, first.y, second.x, second.y, end.x, end.y)

private const val degree = (PI / 180).toFloat()
