@file:PortedFrom("OpenCode/Glass.swift", "4ab457c")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.GlassSurface
import ai.opencode.mobilecode.system.SystemColors
import ai.opencode.mobilecode.system.TextStyles
import ai.opencode.mobilecode.system.glassEffect
import ai.opencode.mobilecode.system.medium
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

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

/// `Button(title).openCodePrimaryButton()` or `.openCodeSecondaryButton()`.
@Composable
fun OpenCodeButton(
    title: String,
    prominent: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: TextStyle = TextStyles.body.medium,
    action: () -> Unit,
) {
    Box(
        modifier
            .then(if (prominent) Modifier.openCodePrimaryButton() else Modifier.openCodeSecondaryButton())
            .clickable(enabled = enabled, role = Role.Button, onClick = action)
            .padding(horizontal = 20.dp)
            .widthIn(min = 44.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title,
            style = style,
            color = when {
                !enabled -> SystemColors.tertiaryLabel
                prominent -> Color.White
                else -> SystemColors.label
            },
            maxLines = 1,
        )
    }
}
