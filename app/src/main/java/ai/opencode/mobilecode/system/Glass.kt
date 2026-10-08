package ai.opencode.mobilecode.system

import android.os.Build
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.runtimeShaderEffect
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur

object GlassSurface {
    val Capsule: Shape = RoundedCornerShape(50)
    val Circle: Shape = CircleShape
    val Panel: Shape = RoundedCornerShape(20.dp)

    fun rounded(radius: Dp): Shape = RoundedCornerShape(radius)
}

private val LocalGlassBackdrop = staticCompositionLocalOf<HazeState?> { null }

private val LocalGlassLens = staticCompositionLocalOf<LayerBackdrop?> { null }

internal val LocalGlassBlurEnabled = staticCompositionLocalOf { true }

/// Lab switch between the two glass candidates: `--es -lab-glass lens` takes
/// the refracting lens on API 33+, anything else the frosted glass.
internal val glassLens: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ProcessInfo.processInfo.argumentValue("lab-glass") == "lens"

@Composable
fun GlassBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
    overlay: @Composable BoxScope.() -> Unit,
) {
    if (glassLens) {
        val background = SystemColors.systemBackground
        val lens = rememberLayerBackdrop(onDraw = remember(background) { { drawRect(background); drawContent() } })
        CompositionLocalProvider(LocalGlassLens provides lens) {
            Box(modifier) {
                Box(Modifier.matchParentSize().layerBackdrop(lens), content = content)
                overlay()
            }
        }
        return
    }
    val state = rememberHazeState()
    CompositionLocalProvider(LocalGlassBackdrop provides state) {
        Box(modifier) {
            Box(Modifier.matchParentSize().hazeSource(state), content = content)
            overlay()
        }
    }
}

/// A sheet or dialog is its own window: its glass sits over the sheet, not
/// over the content of the window below.
@Composable
internal fun GlassWindow(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalGlassBackdrop provides null, LocalGlassLens provides null, content = content)
}

@Composable
fun Modifier.glassEffect(
    shape: Shape = GlassSurface.Panel,
    interactive: Boolean = false,
    tint: Color = Color.Unspecified,
    interactionSource: MutableInteractionSource? = null,
): Modifier {
    val material = if (isSystemInDarkTheme()) GlassMaterial.dark else GlassMaterial.light
    val pressed = interactive && interactionSource?.collectIsPressedAsState()?.value == true
    val blurs = LocalGlassBlurEnabled.current
    val sampled = LocalGlassBackdrop.current != null || LocalGlassLens.current != null
    val state = LocalGlassBackdrop.current?.takeIf { blurs }
    val lens = LocalGlassLens.current?.takeIf { blurs }
    val veil = Veil(
        tint = if (pressed && !tint.isSpecified) material.pressed else tint,
        material = material,
        sampled = sampled,
        blurred = blurs,
    )
    val background = SystemColors.systemBackground
    val backdrop = when {
        lens != null -> Modifier.lensBackdrop(lens, shape, veil)
        state != null -> Modifier.hazeBlur(
            input = HazeInput.Sources(state),
            style = HazeBlurStyle {
                blurRadius(material.blur)
                noiseFactor(0.04f)
                // Opaque, so the sharp content under the control never shows through its blur.
                backgroundColor(background)
                blurredEdgeTreatment(BlurredEdgeTreatment(shape))
            },
        )
        else -> Modifier
    }
    return this
        .then(material.shadow?.let { Modifier.dropShadow(shape, it) } ?: Modifier)
        .clip(shape)
        .then(backdrop)
        .glassSurface(shape, material, if (lens != null) null else veil)
}

/// iOS 27 regular glass as the reference captures draw it. Light frosts the
/// backdrop toward white; dark lifts it, so glass over glass reads lighter.
/// A hairline darkens the sides in light, a specular line and a soft glow
/// light the top and bottom edges, and a faint shadow lifts it in light.
private class GlassMaterial(
    /// Controls, and glass in a window with no backdrop, over a sheet or a card.
    val control: Color,
    /// Panels, and a backdrop drawn without its blur.
    val panel: Color,
    val pressed: Color,
    val blur: Dp,
    val hairlineSide: Color,
    val hairlineLit: Color,
    val specular: Color,
    val specularWidth: Dp,
    val glow: Color,
    val glowHeight: Dp,
    val shadow: Shadow?,
) {
    companion object {
        val light = GlassMaterial(
            control = Color(0xFFFAFAFA).copy(alpha = 0.72f),
            panel = Color(0xFFFAFAFA).copy(alpha = 0.88f),
            pressed = Color(0xFFE5E5EA).copy(alpha = 0.88f),
            blur = 32.dp,
            hairlineSide = Color.Black.copy(alpha = 0.31f),
            hairlineLit = Color.Black.copy(alpha = 0.1f),
            specular = Color.White,
            specularWidth = 0.7.dp,
            glow = Color.White.copy(alpha = 0.5f),
            glowHeight = 7.dp,
            shadow = Shadow(radius = 5.dp, color = Color.Black, offset = DpOffset(0.dp, 3.dp), alpha = 0.06f),
        )
        val dark = GlassMaterial(
            control = Color.White.copy(alpha = 0.12f),
            panel = Color(0xFF222224).copy(alpha = 0.88f),
            pressed = Color(0xFF3A3A3C).copy(alpha = 0.88f),
            blur = 36.dp,
            hairlineSide = Color.Transparent,
            hairlineLit = Color.Transparent,
            specular = Color.White.copy(alpha = 0.16f),
            specularWidth = 1.2.dp,
            glow = Color.White.copy(alpha = 0.08f),
            glowHeight = 5.dp,
            shadow = null,
        )
    }
}

/// The color a surface lays over its backdrop: a tint as given, or the
/// material's, which turns from the control veil to the panel veil as the
/// surface grows, so a panel diffuses the transcript fully.
private class Veil(val tint: Color, val material: GlassMaterial, val sampled: Boolean, val blurred: Boolean) {
    fun at(shortestSide: Dp): Color = when {
        tint.isSpecified -> tint
        !sampled -> material.control
        !blurred -> material.panel
        else -> lerp(material.control, material.panel, smoothstep(ControlSide, PanelSide, shortestSide))
    }

    private companion object {
        val ControlSide = 64.dp
        val PanelSide = 176.dp
    }
}

private fun smoothstep(from: Dp, to: Dp, value: Dp): Float {
    val t = ((value - from) / (to - from)).coerceIn(0f, 1f)
    return t * t * (3 - 2 * t)
}

/// Over the backdrop and under the content. The strokes straddle the
/// outline, so the clip keeps their inner half.
private fun Modifier.glassSurface(shape: Shape, material: GlassMaterial, veil: Veil?): Modifier = drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val fill = veil?.at(size.minDimension.toDp())
    // Where the outline turns from horizontal to vertical: the light falls on
    // the top and bottom, and fades by the square of the turn toward the sides.
    val band = if (size.height > 0f) (cornerRadius(outline, size) / size.height).coerceIn(0.01f, 0.5f) else 0.5f
    fun lit(edge: Color, side: Color): Brush {
        val half = lerp(side, edge, 0.25f)
        return Brush.verticalGradient(
            0f to edge, band / 2 to half, band to side,
            1f - band to side, 1f - band / 2 to half, 1f to edge,
        )
    }
    val hairline = 1f
    val specular = material.specularWidth.toPx()
    val glow = material.glowHeight.toPx().coerceAtMost(size.height / 2)
    val hairlineBrush = lit(material.hairlineLit, material.hairlineSide)
    val specularBrush = lit(material.specular, Color.Transparent)
    val topGlow = Brush.verticalGradient(listOf(material.glow, Color.Transparent), endY = glow)
    val bottomGlow = Brush.verticalGradient(listOf(Color.Transparent, material.glow), startY = size.height - glow, endY = size.height)
    onDrawWithContent {
        if (fill != null) drawRect(fill)
        drawRect(topGlow, size = Size(size.width, glow))
        drawRect(bottomGlow, topLeft = Offset(0f, size.height - glow), size = Size(size.width, glow))
        drawOutline(outline, specularBrush, style = Stroke(2 * (hairline + specular)))
        drawOutline(outline, hairlineBrush, style = Stroke(2 * hairline))
        drawContent()
    }
}

private fun cornerRadius(outline: Outline, size: Size): Float = when (outline) {
    is Outline.Rounded -> outline.roundRect.topLeftCornerRadius.y
    is Outline.Rectangle -> 0f
    is Outline.Generic -> size.minDimension / 2
}

/// Candidate B: bends the backdrop near the rim, where the veil thins so the
/// refracted content shows, and frosts it with the panel veil inside.
private fun Modifier.lensBackdrop(backdrop: LayerBackdrop, shape: Shape, veil: Veil): Modifier =
    drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            blur(LensBlur.toPx())
            lens(refractionHeight = LensBand.toPx(), refractionAmount = LensDepth.toPx(), depthEffect = true)
            val radius = (shape as? CornerBasedShape)?.topStart?.toPx(size, this) ?: (size.minDimension / 2)
            val inside = if (veil.tint.isSpecified) veil.tint else veil.material.panel
            val rim = if (veil.tint.isSpecified) veil.tint else veil.material.control
            runtimeShaderEffect("veil", VeilShader, "content") {
                setFloatUniform("size", size.width, size.height)
                setFloatUniform("offset", -padding, -padding)
                setFloatUniform("radius", radius.coerceAtMost(size.minDimension / 2))
                setFloatUniform("rimVeil", rim.red, rim.green, rim.blue, rim.alpha * LensRimVeil)
                setFloatUniform("insideVeil", inside.red, inside.green, inside.blue, inside.alpha)
                setFloatUniform("band", LensBand.toPx())
            }
        },
        highlight = null,
        shadow = null,
    )

private val LensBlur = 6.dp
private val LensBand = 12.dp
private val LensDepth = 16.dp
private const val LensRimVeil = 0.7f

private const val VeilShader = """
uniform shader content;
uniform float2 size;
uniform float2 offset;
uniform float radius;
uniform float4 rimVeil;
uniform float4 insideVeil;
uniform float band;

half4 main(float2 coord) {
    half4 color = content.eval(coord);
    float2 halfSize = size * 0.5;
    float2 q = abs(coord + offset - halfSize) - (halfSize - float2(radius));
    float sd = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
    float4 veil = mix(float4(rimVeil.rgb * rimVeil.a, rimVeil.a), float4(insideVeil.rgb * insideVeil.a, insideVeil.a), smoothstep(0.0, band, -sd));
    return half4(color.rgb * half(1.0 - veil.a) + half3(veil.rgb), color.a);
}
"""
