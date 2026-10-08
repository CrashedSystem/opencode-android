package ai.opencode.mobilecode.system

import android.app.Activity
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur

object GlassSurface {
    val Capsule: Shape = RoundedCornerShape(50)
    val Circle: Shape = CircleShape
    val Panel: Shape = RoundedCornerShape(20.dp)

    fun rounded(radius: Dp): Shape = RoundedCornerShape(radius)
}

private val LocalGlassBackdrop = staticCompositionLocalOf<HazeState?> { null }

internal val LocalGlassBlurEnabled = staticCompositionLocalOf { true }

private val LocalGlassCapture = staticCompositionLocalOf<GlassCapture?> { null }

/// One downsampled copy of the backdrop, shared by every glass control.
/// Haze otherwise records the full screen on every draw.
private class GlassCapture {
    var layer: GraphicsLayer? = null
    var scale: Float = 0.25f
    var holdMs: Long = 0
    var backdrop: LayoutCoordinates? = null
    var recordedAt: Long = 0
    var generation: Int = 0
}

@Composable
fun GlassBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
    overlay: @Composable BoxScope.() -> Unit,
) {
    val state = rememberHazeState()
    val capture = remember { GlassCapture() }
    val mode = glassMode()
    val graphics = LocalGraphicsContext.current
    applyCaptureMode(capture, mode)
    DisposableEffect(graphics) {
        onDispose {
            capture.layer?.let { graphics.releaseGraphicsLayer(it) }
            capture.layer = null
        }
    }
    val logged = remember { mutableStateOf("") }
    SideEffect {
        if (logged.value == mode) return@SideEffect
        logged.value = mode
        Log.i(TAG, "mode=$mode backdrop=${BackdropBlur.available} detail=${BackdropBlur.detail}")
    }
    CompositionLocalProvider(LocalGlassBackdrop provides state, LocalGlassCapture provides capture) {
        Box(modifier) {
            Box(
                Modifier
                    .matchParentSize()
                    .onGloballyPositioned { capture.backdrop = it }
                    .then(if (capture.scale > 0f) Modifier.captureBackdrop(capture, graphics) else Modifier)
                    .hazeSource(state),
                content = content,
            )
            overlay()
        }
    }
}

@Composable
fun Modifier.glassEffect(
    shape: Shape = GlassSurface.Panel,
    interactive: Boolean = false,
    tint: Color = Color.Unspecified,
    interactionSource: MutableInteractionSource? = null,
): Modifier {
    val dark = isSystemInDarkTheme()
    val pressed = interactionSource?.collectIsPressedAsState()?.value == true
    val veil = if (tint != Color.Unspecified) tint else glassTint(dark, interactive, pressed)
    val rim = glassRim(dark, interactive, pressed)
    val state = LocalGlassBackdrop.current
    val capture = LocalGlassCapture.current
    val enabled = LocalGlassBlurEnabled.current
    val mode = glassMode()
    val blur = glassBlur(mode, state, capture, enabled, shape, veil, dark)
    val highlight = if (dark) 0.18f else 0.32f
    return this
        .clip(shape)
        .then(blur)
        .drawWithContent {
            drawContent()
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = highlight), Color.Transparent),
                    endY = size.height * 0.22f,
                ),
                size = Size(size.width, size.height * 0.22f),
            )
        }
        .border(1.dp, rim, shape)
}

@Composable
private fun glassMode(): String {
    val context = LocalContext.current
    val activity = context as? Activity ?: return "baseline"
    return activity.intent.getStringExtra("glass-mode") ?: "baseline"
}

private fun applyCaptureMode(capture: GlassCapture, mode: String) {
    capture.scale = when (mode) {
        "shared", "sharedhold" -> 0.25f
        "shared50" -> 0.5f
        else -> 0f
    }
    capture.holdMs = if (mode == "sharedhold") 100 else 0
}

@Composable
private fun glassBlur(
    mode: String,
    state: HazeState?,
    capture: GlassCapture?,
    enabled: Boolean,
    shape: Shape,
    veil: Color,
    dark: Boolean,
): Modifier {
    if (state == null || !enabled) return Modifier.background(veil, shape)
    if (mode == "backdrop" && BackdropBlur.available) return Modifier.backdropBlur(veil, dark)
    if (capture != null && capture.scale > 0f) return Modifier.sharedBlur(capture, veil, dark)
    val performance = if (mode == "scale" || mode == "tight") HazePerformanceMode.Performance else null
    val expand = mode != "tight"
    return Modifier.hazeBlur(
        input = HazeInput.Sources(state),
        style = HazeBlurStyle {
            blurRadius(if (dark) 36.dp else 32.dp)
            noiseFactor(0.04f)
            backgroundColor(Color.Transparent)
            colorEffects(listOf(HazeColorEffect.tint(veil)))
            fallbackColorEffect(HazeColorEffect.tint(veil))
            blurredEdgeTreatment(BlurredEdgeTreatment(shape))
        },
        performanceMode = performance,
        expandLayerBounds = expand,
    )
}

private fun Modifier.captureBackdrop(capture: GlassCapture, graphics: androidx.compose.ui.graphics.GraphicsContext): Modifier =
    drawWithContent {
        val scale = capture.scale
        val width = (size.width * scale).toInt().coerceAtLeast(1)
        val height = (size.height * scale).toInt().coerceAtLeast(1)
        val layer = capture.layer ?: graphics.createGraphicsLayer().also { capture.layer = it }
        val now = SystemClock.uptimeMillis()
        if (capture.holdMs == 0L || now - capture.recordedAt >= capture.holdMs) {
            layer.record(IntSize(width, height)) {
                scale(scale, scale, pivot = Offset.Zero) {
                    this@drawWithContent.drawContent()
                }
            }
            capture.recordedAt = now
            capture.generation += 1
        }
        drawContent()
    }

@Composable
private fun Modifier.sharedBlur(capture: GlassCapture, veil: Color, dark: Boolean): Modifier {
    val graphics = LocalGraphicsContext.current
    val sample = remember(graphics) { graphics.createGraphicsLayer() }
    DisposableEffect(sample) {
        onDispose { graphics.releaseGraphicsLayer(sample) }
    }
    var control by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val built = remember { intArrayOf(-1) }
    val radius = if (dark) 36.dp else 32.dp
    return onGloballyPositioned { control = it }.drawBehind {
        val source = capture.layer
        val backdrop = capture.backdrop
        val placed = control
        if (source == null || backdrop == null || placed == null || !backdrop.isAttached || !placed.isAttached) {
            drawRect(veil)
            return@drawBehind
        }
        // Rebuild only when the shared backdrop is re-recorded. A 32 dp blur
        // does not need a new sample on every transcript frame.
        if (built[0] != capture.generation) {
            val local = backdrop.localPositionOf(placed, Offset.Zero)
            val pad = radius.toPx()
            val factor = 1f / capture.scale
            sample.record(IntSize((size.width + pad * 2).toInt().coerceAtLeast(1), (size.height + pad * 2).toInt().coerceAtLeast(1))) {
                translate(left = pad - local.x, top = pad - local.y) {
                    scale(factor, factor, pivot = Offset.Zero) {
                        drawLayer(source)
                    }
                }
            }
            sample.renderEffect = BlurEffect(pad, pad, TileMode.Clamp)
            built[0] = capture.generation
        }
        val pad = radius.toPx()
        translate(left = -pad, top = -pad) {
            drawLayer(sample)
        }
        drawRect(veil)
    }
}

@Composable
private fun Modifier.backdropBlur(veil: Color, dark: Boolean): Modifier {
    val node = remember { RenderNode("opencode-glass") }
    val radius = if (dark) 36.dp else 32.dp
    return drawBehind {
        val radiusPx = radius.toPx()
        val width = size.width.toInt().coerceAtLeast(1)
        val height = size.height.toInt().coerceAtLeast(1)
        val effect = RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP)
        if (BackdropBlur.apply(node, effect)) {
            node.setPosition(0, 0, width, height)
            val recording = node.beginRecording(width, height)
            recording.drawColor(android.graphics.Color.TRANSPARENT)
            node.endRecording()
            drawContext.canvas.nativeCanvas.drawRenderNode(node)
        }
        drawRect(veil)
    }
}

private object BackdropBlur {
    var detail: String = "unset"
        private set
    private val method = resolve()

    val available: Boolean get() = method != null

    fun apply(node: RenderNode, effect: RenderEffect): Boolean {
        val method = method ?: return false
        return try {
            method.invoke(node, effect) != false
        } catch (error: Throwable) {
            detail = error.javaClass.simpleName + ":" + error.message
            false
        }
    }

    private fun resolve(): java.lang.reflect.Method? {
        val type = RenderNode::class.java
        val names = type.declaredMethods.map { it.name }.filter { "Backdrop" in it || "backdrop" in it || "Blur" in it }
        detail = "sdk=" + android.os.Build.VERSION.SDK_INT + " declared=" + names.joinToString(",")
        Log.i(TAG, detail)
        val found = try {
            type.getDeclaredMethod("setBackdropRenderEffect", RenderEffect::class.java)
        } catch (error: Throwable) {
            detail = detail + " declaredFail=" + error.javaClass.simpleName
            null
        }
        found?.isAccessible = true
        return found
    }
}

private fun glassTint(dark: Boolean, interactive: Boolean, pressed: Boolean): Color {
    val alpha = when {
        dark && pressed -> 0.24f
        dark && interactive -> 0.18f
        dark -> 0.14f
        pressed -> 0.42f
        interactive -> 0.34f
        else -> 0.28f
    }
    return Color.White.copy(alpha = alpha)
}

private fun glassRim(dark: Boolean, interactive: Boolean, pressed: Boolean): Color {
    val alpha = when {
        pressed -> 0.85f
        interactive && dark -> 0.40f
        interactive -> 0.80f
        dark -> 0.28f
        else -> 0.70f
    }
    return Color.White.copy(alpha = alpha)
}

private const val TAG = "GlassPerf"
