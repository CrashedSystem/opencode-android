@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package ai.opencode.core.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp

/** A button's role (iOS `ButtonRole`). Red is only for [Destructive]. */
enum class OpenCodeButtonRole { Normal, Destructive }

/**
 * Material 3 button sizes (https://m3.material.io/components/buttons/specs): [Small] is the
 * 40 dp default for dialogs, forms and paired actions; [Medium] is the 56 dp hero CTA that
 * carries a screen (onboarding, connect). Both keep a 48 dp touch target.
 */
enum class OpenCodeButtonSize { Small, Medium }

object OpenCodeButtonDefaults {
    /** The default button height (M3 small button, `ButtonDefaults.MinHeight`, 40 dp). */
    val height: Dp = ButtonDefaults.MinHeight

    /** The hero CTA height (M3 medium button, 56 dp). */
    val heroHeight: Dp = ButtonDefaults.MediumContainerHeight

    /** Padding for the default size (`ButtonDefaults.ContentPadding`). */
    val contentPadding: PaddingValues = ButtonDefaults.ContentPadding

    /** Padding for the hero size (`ButtonDefaults.MediumContentPadding`). */
    val heroContentPadding: PaddingValues = ButtonDefaults.MediumContentPadding

    fun height(size: OpenCodeButtonSize): Dp = if (size == OpenCodeButtonSize.Medium) heroHeight else height

    fun contentPadding(size: OpenCodeButtonSize): PaddingValues =
        if (size == OpenCodeButtonSize.Medium) heroContentPadding else contentPadding

    /** The label style for a size (labelLarge at 40 dp, titleMedium at 56 dp). */
    @Composable
    fun textStyle(size: OpenCodeButtonSize): TextStyle = ButtonDefaults.textStyleFor(height(size))
}

/**
 * The primary action: Next, Send, Connect, Done (iOS `openCodePrimaryButton()`). A Material
 * filled button in the theme's primary colour, or `error`/`onError` for a
 * [OpenCodeButtonRole.Destructive] action. Pressing morphs the corners (M3 Expressive shapes).
 *
 * Pair with [OpenCodeSecondaryButton] at the same size.
 */
@Composable
fun OpenCodePrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    role: OpenCodeButtonRole = OpenCodeButtonRole.Normal,
    size: OpenCodeButtonSize = OpenCodeButtonSize.Small,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = if (role == OpenCodeButtonRole.Destructive) {
        ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        )
    } else {
        ButtonDefaults.buttonColors()
    }
    val height = OpenCodeButtonDefaults.height(size)
    Button(
        onClick = onClick,
        shapes = ButtonDefaults.shapesFor(height),
        modifier = modifier.heightIn(min = height),
        enabled = enabled,
        colors = colors,
        contentPadding = OpenCodeButtonDefaults.contentPadding(size),
    ) {
        ProvideTextStyle(OpenCodeButtonDefaults.textStyle(size)) { content() }
    }
}

/** [OpenCodePrimaryButton] with a text label. */
@Composable
fun OpenCodePrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    role: OpenCodeButtonRole = OpenCodeButtonRole.Normal,
    size: OpenCodeButtonSize = OpenCodeButtonSize.Small,
) = OpenCodePrimaryButton(onClick, modifier, enabled, role, size) { ButtonLabel(text) }

/**
 * The neutral companion: Back, Skip, Cancel, Not Now (iOS `openCodeSecondaryButton()`). A
 * Material tonal button on `secondaryContainer`; a [OpenCodeButtonRole.Destructive] secondary
 * keeps the tonal fill with `error` text.
 */
@Composable
fun OpenCodeSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    role: OpenCodeButtonRole = OpenCodeButtonRole.Normal,
    size: OpenCodeButtonSize = OpenCodeButtonSize.Small,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = if (role == OpenCodeButtonRole.Destructive) {
        ButtonDefaults.filledTonalButtonColors(contentColor = MaterialTheme.colorScheme.error)
    } else {
        ButtonDefaults.filledTonalButtonColors()
    }
    val height = OpenCodeButtonDefaults.height(size)
    FilledTonalButton(
        onClick = onClick,
        shapes = ButtonDefaults.shapesFor(height),
        modifier = modifier.heightIn(min = height),
        enabled = enabled,
        colors = colors,
        contentPadding = OpenCodeButtonDefaults.contentPadding(size),
    ) {
        ProvideTextStyle(OpenCodeButtonDefaults.textStyle(size)) { content() }
    }
}

/** [OpenCodeSecondaryButton] with a text label. */
@Composable
fun OpenCodeSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    role: OpenCodeButtonRole = OpenCodeButtonRole.Normal,
    size: OpenCodeButtonSize = OpenCodeButtonSize.Small,
) = OpenCodeSecondaryButton(onClick, modifier, enabled, role, size) { ButtonLabel(text) }

@Composable
private fun ButtonLabel(text: String) {
    Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
