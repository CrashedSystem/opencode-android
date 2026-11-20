package ai.opencode.feature.transcript.widgets

import ai.opencode.core.log.AppLog
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.ui.OpenCodeTheme
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * A `canvas` node: the template's `draw` runs on the widget's lane when the canvas appears or
 * its state changes, then every frame while `fps > 0` (and `duration` hasn't run out) and the
 * row is on screen; frames come from `withFrameNanos`, so a hidden or stopped screen draws
 * nothing. Its commands are decoded into flat arrays and replayed on the native canvas. Three
 * frames over budget (8 ms or 2,000 commands) in a row stop the animation on its last frame.
 */
@Composable
fun AgentWidgetCanvas(node: AgentWidgetNode.Canvas, scope: AgentWidgetScope, modifier: Modifier = Modifier) {
    val controller = scope.controller
    val colors = OpenCodeTheme.colors
    val density = LocalDensity.current.density
    val palette = remember(colors) {
        AgentWidgetDrawList.Palette(
            intArrayOf(
                colors.text.toArgb(), colors.secondaryText.toArgb(), colors.accent.toArgb(), colors.canvas.toArgb(),
                colors.userBubble.toArgb(), colors.border.toArgb(), colors.diffAddedText.toArgb(), colors.warning.toArgb(), colors.danger.toArgb(),
            ),
        )
    }
    val dark = colors.canvas.luminance() < 0.5f
    val state = remember { CanvasState() }
    var revision by remember { mutableIntStateOf(0) }
    val drawState = controller?.phase?.state
    val currentScope by rememberUpdatedState(scope)

    LaunchedEffect(controller, drawState, node.fps, node.duration, palette, state.size, dark) {
        controller ?: return@LaunchedEffect
        if (state.size == IntSize.Zero) return@LaunchedEffect
        var last = 0L
        var overruns = 0
        val interval = if (node.fps > 0) 1_000_000_000L / node.fps else Long.MAX_VALUE
        var first = true
        while (true) {
            val now = withFrameNanos { it }
            if (state.startNanos == 0L) state.startNanos = now
            if (!first && now - last < interval - 2_000_000L) continue
            val t = (now - state.startNanos) / 1e9
            val dt = if (last == 0L) 0.0 else (now - last) / 1e9
            last = now
            val frame = state.frameJSON(node.id, t, dt, density, dark)
            val started = System.nanoTime()
            val text = try { controller.draw(frame) } catch (error: Exception) {
                AppLog.app.notice { "Widget canvas draw failed: ${error.javaClass.simpleName}" }
                null
            } ?: break
            val stepped = System.nanoTime()
            val decoded = withContext(Dispatchers.Default) {
                val next = state.spare
                runCatching { AgentWidgetDrawList.decode(text, palette, next) }.isSuccess
            }
            if (decoded) {
                state.swap()
                revision++
            }
            val stepMicros = (stepped - started) / 1_000
            state.noteFrame(stepMicros, (System.nanoTime() - stepped) / 1_000)
            overruns = if (stepMicros > DRAW_BUDGET_MICROS || state.shown.truncated) overruns + 1 else 0
            if (overruns >= 3) {
                AppLog.perf.notice { "Widget canvas stopped: over budget three frames in a row" }
                break
            }
            first = false
            val duration = node.duration
            if (node.fps <= 0 || (duration != null && t >= duration)) break
        }
    }

    val width = node.width
    val alt = node.alt
    Canvas(
        modifier
            .then(if (width != null) Modifier.width(width.dp) else Modifier.fillMaxWidth())
            .height(node.height.dp)
            .clip(OpenCodeShape.control)
            .onSizeChanged { state.size = it }
            .then(if (alt != null) Modifier.semantics { contentDescription = alt } else Modifier)
            .then(
                if (node.id != null) {
                    Modifier.pointerInput(node.id) {
                        awaitPointerEventScope {
                            while (true) {
                                val down = awaitPointerEvent()
                                val first = down.changes.firstOrNull { it.pressed && !it.previousPressed } ?: continue
                                val id = node.id
                                val w = size.width / density
                                val h = size.height / density
                                state.pointer = floatArrayOf(first.position.x / density, first.position.y / density)
                                currentScope.send(AgentWidgetEvent.pointer(id, "down", first.position.x / density, first.position.y / density, w, h))
                                var horizontal: Boolean? = null
                                var lastMove = 0L
                                val origin = first.position
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == first.id } ?: break
                                    val x = change.position.x / density
                                    val y = change.position.y / density
                                    if (!change.pressed) {
                                        state.pointer = null
                                        currentScope.send(AgentWidgetEvent.pointer(id, "up", x, y, w, h))
                                        break
                                    }
                                    val dx = abs(change.position.x - origin.x)
                                    val dy = abs(change.position.y - origin.y)
                                    if (horizontal == null && (dx > viewConfiguration.touchSlop || dy > viewConfiguration.touchSlop)) horizontal = dx >= dy
                                    if (horizontal == false) {
                                        // The list scrolls instead: the press ends here.
                                        state.pointer = null
                                        currentScope.send(AgentWidgetEvent.pointer(id, "up", x, y, w, h))
                                        break
                                    }
                                    if (horizontal == true) change.consume()
                                    state.pointer = floatArrayOf(x, y)
                                    if (change.uptimeMillis - lastMove >= 16) {
                                        lastMove = change.uptimeMillis
                                        currentScope.send(AgentWidgetEvent.pointer(id, "move", x, y, w, h))
                                    }
                                }
                            }
                        }
                    }
                } else Modifier,
            ),
    ) {
        @Suppress("UNUSED_EXPRESSION") revision
        replay(state.shown, state.renderer, density)
    }
}

private const val DRAW_BUDGET_MICROS = 8_000L

/** One canvas's frames: the list on screen and the one being decoded, the pointer, timing. */
private class CanvasState {
    var shown = AgentWidgetDrawList()
    var spare = AgentWidgetDrawList()
    val renderer = CanvasRenderer()
    var size = IntSize.Zero
    var startNanos = 0L
    @Volatile var pointer: FloatArray? = null
    private var frames = 0
    private var stepTotal = 0L
    private var decodeTotal = 0L
    private var stepMax = 0L

    fun swap() {
        val previous = shown
        shown = spare
        spare = previous
    }

    fun frameJSON(id: String?, t: Double, dt: Double, scale: Float, dark: Boolean): String {
        val p = pointer
        val w = size.width / scale
        val h = size.height / scale
        return buildString(160) {
            append("{\"id\":")
            append(if (id == null) "null" else kotlinx.serialization.json.JsonPrimitive(id).toString())
            append(",\"t\":").append(t).append(",\"dt\":").append(dt)
            append(",\"width\":").append(w).append(",\"height\":").append(h).append(",\"scale\":").append(scale)
            append(",\"pointer\":")
            if (p == null) append("null") else append("{\"x\":").append(p[0]).append(",\"y\":").append(p[1]).append(",\"down\":true}")
            append(",\"dark\":").append(dark).append('}')
        }
    }

    fun noteFrame(stepMicros: Long, decodeMicros: Long) {
        frames++
        stepTotal += stepMicros
        decodeTotal += decodeMicros
        if (stepMicros > stepMax) stepMax = stepMicros
        if (frames == 120) {
            AppLog.perf.info {
                "Widget canvas: 120 frames, draw step avg ${stepTotal / frames} µs (max $stepMax), decode avg ${decodeTotal / frames} µs, ${shown.commandCount} commands"
            }
            frames = 0
            stepTotal = 0
            decodeTotal = 0
            stepMax = 0
        }
    }
}

/** Replays a [AgentWidgetDrawList] on the native canvas with reused paints and path. */
private class CanvasRenderer {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val alphas = FloatArray(64)
    private val typefaces = HashMap<Int, Typeface>()
    var replayMicros = 0L
        private set

    fun typeface(weight: Int, mono: Boolean): Typeface = typefaces.getOrPut(weight * 2 + if (mono) 1 else 0) {
        Typeface.create(if (mono) Typeface.MONOSPACE else Typeface.SANS_SERIF, weight.coerceIn(100, 900), false)
    }

    fun replay(scope: DrawScope, list: AgentWidgetDrawList, density: Float) {
        val started = System.nanoTime()
        scope.drawIntoCanvas { composeCanvas ->
            val canvas = composeCanvas.nativeCanvas
            val base = canvas.save()
            canvas.scale(density, density)
            val ints = list.ints
            val floats = list.floats
            var ip = 0
            var fp = 0
            var sp = 0
            var depth = 0
            var alpha = 1f
            val end = list.intCount
            while (ip < end) {
                when (ints[ip++]) {
                    AgentWidgetDrawList.SAVE -> {
                        canvas.save()
                        if (depth < alphas.size) alphas[depth] = alpha
                        depth++
                    }
                    AgentWidgetDrawList.RESTORE -> if (depth > 0) {
                        canvas.restore()
                        depth--
                        if (depth < alphas.size) alpha = alphas[depth]
                    }
                    AgentWidgetDrawList.TRANSLATE -> { canvas.translate(floats[fp], floats[fp + 1]); fp += 2 }
                    AgentWidgetDrawList.ROTATE -> { canvas.rotate(Math.toDegrees(floats[fp].toDouble()).toFloat()); fp += 1 }
                    AgentWidgetDrawList.SCALE -> { canvas.scale(floats[fp], floats[fp + 1]); fp += 2 }
                    AgentWidgetDrawList.ALPHA -> { alpha *= floats[fp].coerceIn(0f, 1f); fp += 1 }
                    AgentWidgetDrawList.RECT -> {
                        val x = floats[fp]; val y = floats[fp + 1]; val w = floats[fp + 2]; val h = floats[fp + 3]
                        fp += 4
                        val (nip, nfp) = paints(ints, floats, ip, fp, alpha)
                        ip = nip; fp = nfp
                        if (hasFill) canvas.drawRect(x, y, x + w, y + h, fill)
                        if (hasStroke) canvas.drawRect(x, y, x + w, y + h, stroke)
                    }
                    AgentWidgetDrawList.RRECT -> {
                        val x = floats[fp]; val y = floats[fp + 1]; val w = floats[fp + 2]; val h = floats[fp + 3]; val r = floats[fp + 4]
                        fp += 5
                        val (nip, nfp) = paints(ints, floats, ip, fp, alpha)
                        ip = nip; fp = nfp
                        if (hasFill) canvas.drawRoundRect(x, y, x + w, y + h, r, r, fill)
                        if (hasStroke) canvas.drawRoundRect(x, y, x + w, y + h, r, r, stroke)
                    }
                    AgentWidgetDrawList.CIRCLE -> {
                        val cx = floats[fp]; val cy = floats[fp + 1]; val r = floats[fp + 2]
                        fp += 3
                        val (nip, nfp) = paints(ints, floats, ip, fp, alpha)
                        ip = nip; fp = nfp
                        if (hasFill) canvas.drawCircle(cx, cy, r, fill)
                        if (hasStroke) canvas.drawCircle(cx, cy, r, stroke)
                    }
                    AgentWidgetDrawList.LINE -> {
                        val x1 = floats[fp]; val y1 = floats[fp + 1]; val x2 = floats[fp + 2]; val y2 = floats[fp + 3]
                        fp += 4
                        val (nip, nfp) = paints(ints, floats, ip, fp, alpha)
                        ip = nip; fp = nfp
                        if (hasStroke) canvas.drawLine(x1, y1, x2, y2, stroke)
                    }
                    AgentWidgetDrawList.PATH -> {
                        path.rewind()
                        val count = ints[ip++]
                        repeat(count) {
                            when (ints[ip++]) {
                                AgentWidgetDrawList.SEG_M -> { path.moveTo(floats[fp], floats[fp + 1]); fp += 2 }
                                AgentWidgetDrawList.SEG_L -> { path.lineTo(floats[fp], floats[fp + 1]); fp += 2 }
                                AgentWidgetDrawList.SEG_Q -> { path.quadTo(floats[fp], floats[fp + 1], floats[fp + 2], floats[fp + 3]); fp += 4 }
                                AgentWidgetDrawList.SEG_C -> {
                                    path.cubicTo(floats[fp], floats[fp + 1], floats[fp + 2], floats[fp + 3], floats[fp + 4], floats[fp + 5])
                                    fp += 6
                                }
                                AgentWidgetDrawList.SEG_Z -> path.close()
                            }
                        }
                        val (nip, nfp) = paints(ints, floats, ip, fp, alpha)
                        ip = nip; fp = nfp
                        if (hasFill) canvas.drawPath(path, fill)
                        if (hasStroke) canvas.drawPath(path, stroke)
                    }
                    AgentWidgetDrawList.TEXT -> {
                        val x = floats[fp]; val y = floats[fp + 1]; val size = floats[fp + 2]
                        fp += 3
                        val weight = ints[ip]; val align = ints[ip + 1]; val color = ints[ip + 2]; val mono = ints[ip + 3] == 1
                        ip += 4
                        val string = list.strings[sp++]
                        text.textSize = size
                        text.typeface = typeface(weight, mono)
                        text.color = withAlpha(color, alpha)
                        text.textAlign = when (align) {
                            AgentWidgetDrawList.ALIGN_CENTER -> Paint.Align.CENTER
                            AgentWidgetDrawList.ALIGN_RIGHT -> Paint.Align.RIGHT
                            else -> Paint.Align.LEFT
                        }
                        canvas.drawText(string, x, y, text)
                    }
                    AgentWidgetDrawList.IMAGE -> {
                        sp++
                        fp += 4
                    }
                }
            }
            canvas.restoreToCount(base)
        }
        replayMicros = (System.nanoTime() - started) / 1_000
    }

    private var hasFill = false
    private var hasStroke = false

    /** Reads a paint into [fill] and [stroke]; returns where the streams continue. */
    private fun paints(ints: IntArray, floats: FloatArray, ipStart: Int, fpStart: Int, alpha: Float): Pair<Int, Int> {
        var ip = ipStart
        var fp = fpStart
        val fillKind = ints[ip]; val fillValue = ints[ip + 1]; val strokeKind = ints[ip + 2]; val strokeColor = ints[ip + 3]; val cap = ints[ip + 4]
        ip += 5
        val width = floats[fp++]
        fill.shader = null
        hasFill = fillKind != AgentWidgetDrawList.FILL_NONE
        when (fillKind) {
            AgentWidgetDrawList.FILL_COLOR -> fill.color = withAlpha(fillValue, alpha)
            AgentWidgetDrawList.FILL_LINEAR, AgentWidgetDrawList.FILL_RADIAL -> {
                val stops = fillValue
                val geometry = if (fillKind == AgentWidgetDrawList.FILL_LINEAR) 4 else 3
                val g = fp
                fp += geometry
                val offsets = FloatArray(stops) { floats[fp + it] }
                fp += stops
                val colors = IntArray(stops) { ints[ip + it] }
                ip += stops
                fill.color = withAlpha(0xFFFFFFFF.toInt(), alpha)
                fill.shader = if (fillKind == AgentWidgetDrawList.FILL_LINEAR) {
                    LinearGradient(floats[g], floats[g + 1], floats[g + 2], floats[g + 3], colors, offsets, Shader.TileMode.CLAMP)
                } else {
                    RadialGradient(floats[g], floats[g + 1], maxOf(0.001f, floats[g + 2]), colors, offsets, Shader.TileMode.CLAMP)
                }
            }
        }
        hasStroke = strokeKind == 1
        if (hasStroke) {
            stroke.color = withAlpha(strokeColor, alpha)
            stroke.strokeWidth = width
            stroke.strokeCap = if (cap == 1) Paint.Cap.ROUND else Paint.Cap.BUTT
        }
        return ip to fp
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        if (alpha >= 1f) return color
        val a = ((color ushr 24) * alpha).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (a shl 24)
    }
}

private fun DrawScope.replay(list: AgentWidgetDrawList, renderer: CanvasRenderer, density: Float) = renderer.replay(this, list, density)
