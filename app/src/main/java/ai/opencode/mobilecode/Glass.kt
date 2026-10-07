@file:PortedFrom("OpenCode/Glass.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.GlassSurface
import ai.opencode.mobilecode.system.SystemColors
import ai.opencode.mobilecode.system.glassEffect
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/// Groups nearby glass controls so they render, blend, and morph together.
@Composable
fun OpenCodeGlassContainer(
    spacing: Dp? = null,
    content: @Composable () -> Unit,
) {
    content()
}

/// Liquid Glass, optionally tinted or interactive.
@Composable
fun Modifier.openCodeGlass(
    shape: Shape,
    interactive: Boolean = false,
    tint: Color? = null,
): Modifier = glassEffect(shape, interactive = interactive, tint = tint ?: Color.Unspecified)

/// Native prominent glass. Keeping this here prevents custom blue rectangles
/// from drifting away from the platform's interaction and accessibility states.
@Composable
fun Modifier.openCodePrimaryButton(): Modifier =
    heightIn(min = 50.dp).glassEffect(GlassSurface.Capsule, interactive = true, tint = SystemColors.tint)

/// Neutral companion to the primary action.
@Composable
fun Modifier.openCodeSecondaryButton(): Modifier =
    heightIn(min = 50.dp).glassEffect(GlassSurface.Capsule, interactive = true)
