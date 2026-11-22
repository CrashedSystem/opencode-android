package ai.opencode.feature.transcript.widgets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A `stack` node (widgets.md, Stack): children layered in one box, the first at the bottom, each in the box minus its
 * `inset`, at its natural size by `place` or filling its area. As wide as offered, as tall as its tallest child plus
 * insets; in full screen, with a `fill` canvas child, it takes the height offered. A child that takes no pointer input
 * ([AgentWidgetNode.takesPointer]) lets touches through to the layers below; focus and screen readers follow the
 * children's order.
 */
@Composable
internal fun AgentWidgetStack(node: AgentWidgetNode.Stack, scope: AgentWidgetScope, modifier: Modifier, path: String) {
    val grows = LocalAgentWidgetFullscreen.current && LocalAgentWidgetGrows.current && AgentWidgetNode.fills(node)
    val children = node.children
    val layers = node.layers
    Layout(
        content = {
            CompositionLocalProvider(LocalAgentWidgetGrows provides false) {
                children.forEachIndexed { index, child ->
                    Box(
                        (if (AgentWidgetNode.takesPointer(child)) Modifier else Modifier.then(PassesPointer))
                            .semantics { traversalIndex = index.toFloat() },
                        propagateMinConstraints = true,
                    ) {
                        AgentWidgetNodeView(child, scope, Modifier, "$path.$index")
                    }
                }
            }
        },
        modifier = modifier.fillMaxWidth().semantics { isTraversalGroup = true },
    ) { measurables, constraints ->
        fun px(value: Float) = (value * density).roundToInt()
        val bounded = constraints.hasBoundedWidth
        val stackHeight = if (grows && constraints.hasBoundedHeight) constraints.maxHeight else null
        val placeables = arrayOfNulls<Placeable>(measurables.size)
        var natural = 0
        var widest = 0
        measurables.forEachIndexed { index, measurable ->
            val layer = layers[index]
            val across = px(layer.left) + px(layer.right)
            val along = px(layer.top) + px(layer.bottom)
            val areaWidth = if (bounded) max(0, constraints.maxWidth - across) else Constraints.Infinity
            if (layer.place == AgentWidgetNode.Place.Fill) {
                // Measured once, at the area's size; its natural height counts toward the stack's.
                val height = (children[index] as? AgentWidgetNode.Canvas)?.let { px(it.height) }
                    ?: if (stackHeight != null) 0 else runCatching { measurable.maxIntrinsicHeight(areaWidth) }.getOrDefault(0)
                natural = max(natural, height + along)
                return@forEachIndexed
            }
            val areaHeight = stackHeight?.let { max(0, it - along) } ?: Constraints.Infinity
            val placeable = measurable.measure(Constraints(maxWidth = areaWidth, maxHeight = areaHeight))
            placeables[index] = placeable
            natural = max(natural, placeable.height + along)
            widest = max(widest, placeable.width + across)
        }
        val width = if (bounded) constraints.maxWidth else max(widest, constraints.minWidth)
        val height = (stackHeight ?: natural).coerceIn(constraints.minHeight, constraints.maxHeight)
        measurables.forEachIndexed { index, measurable ->
            if (placeables[index] != null) return@forEachIndexed
            val layer = layers[index]
            placeables[index] = measurable.measure(
                Constraints.fixed(
                    max(0, width - px(layer.left) - px(layer.right)),
                    max(0, height - px(layer.top) - px(layer.bottom)),
                ),
            )
        }
        layout(width, height) {
            placeables.forEachIndexed { index, placeable ->
                placeable ?: return@forEachIndexed
                val layer = layers[index]
                val areaWidth = max(0, width - px(layer.left) - px(layer.right))
                val areaHeight = max(0, height - px(layer.top) - px(layer.bottom))
                // `place` names physical sides, so it isn't mirrored right to left.
                placeable.place(
                    px(layer.left) + ((areaWidth - placeable.width).coerceAtLeast(0) * layer.place.x).roundToInt(),
                    px(layer.top) + ((areaHeight - placeable.height).coerceAtLeast(0) * layer.place.y).roundToInt(),
                )
            }
        }
    }
}

/**
 * A layer that takes no pointer input: a touch on it also goes on to the layers below. Compose hit-tests siblings top
 * down and stops at the first hit unless that one shares with its siblings.
 */
private object PassesPointer : ModifierNodeElement<PassesPointerNode>() {
    override fun create() = PassesPointerNode()
    override fun update(node: PassesPointerNode) = Unit
    override fun hashCode() = javaClass.hashCode()
    override fun equals(other: Any?) = other === this
}

private class PassesPointerNode : Modifier.Node(), PointerInputModifierNode {
    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) = Unit
    override fun onCancelPointerInput() = Unit
    override fun sharePointerInputWithSiblings() = true
}
