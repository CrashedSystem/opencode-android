package ai.opencode.mobilecode.system

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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
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

@Composable
fun GlassBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
    overlay: @Composable BoxScope.() -> Unit,
) {
    val state = rememberHazeState()
    CompositionLocalProvider(LocalGlassBackdrop provides state) {
        Box(modifier) {
            Box(Modifier.matchParentSize().hazeSource(state), content = content)
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
    val blur = if (state == null || !LocalGlassBlurEnabled.current) {
        Modifier.background(veil, shape)
    } else {
        Modifier.hazeBlur(
            input = HazeInput.Sources(state),
            style = HazeBlurStyle {
                blurRadius(if (dark) 36.dp else 32.dp)
                noiseFactor(0.04f)
                backgroundColor(Color.Transparent)
                colorEffects(listOf(HazeColorEffect.tint(veil)))
                fallbackColorEffect(HazeColorEffect.tint(veil))
                blurredEdgeTreatment(BlurredEdgeTreatment(shape))
            },
        )
    }
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
                size = androidx.compose.ui.geometry.Size(size.width, size.height * 0.22f),
            )
        }
        .border(1.dp, rim, shape)
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
