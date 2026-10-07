package ai.opencode.mobilecode.system

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme

object SystemColors {
    val label: Color
        @Composable @ReadOnlyComposable get() = palette.label
    val secondaryLabel: Color
        @Composable @ReadOnlyComposable get() = palette.secondaryLabel
    val tertiaryLabel: Color
        @Composable @ReadOnlyComposable get() = palette.tertiaryLabel
    val quaternaryLabel: Color
        @Composable @ReadOnlyComposable get() = palette.quaternaryLabel
    val placeholderText: Color
        @Composable @ReadOnlyComposable get() = palette.placeholderText
    val link: Color
        @Composable @ReadOnlyComposable get() = palette.link
    val tint: Color
        @Composable @ReadOnlyComposable get() = palette.tint

    val systemBackground: Color
        @Composable @ReadOnlyComposable get() = palette.systemBackground
    val secondarySystemBackground: Color
        @Composable @ReadOnlyComposable get() = palette.secondarySystemBackground
    val tertiarySystemBackground: Color
        @Composable @ReadOnlyComposable get() = palette.tertiarySystemBackground
    val systemGroupedBackground: Color
        @Composable @ReadOnlyComposable get() = palette.systemGroupedBackground
    val secondarySystemGroupedBackground: Color
        @Composable @ReadOnlyComposable get() = palette.secondarySystemGroupedBackground
    val tertiarySystemGroupedBackground: Color
        @Composable @ReadOnlyComposable get() = palette.tertiarySystemGroupedBackground

    val systemFill: Color
        @Composable @ReadOnlyComposable get() = palette.systemFill
    val secondarySystemFill: Color
        @Composable @ReadOnlyComposable get() = palette.secondarySystemFill
    val tertiarySystemFill: Color
        @Composable @ReadOnlyComposable get() = palette.tertiarySystemFill
    val quaternarySystemFill: Color
        @Composable @ReadOnlyComposable get() = palette.quaternarySystemFill

    val separator: Color
        @Composable @ReadOnlyComposable get() = palette.separator
    val opaqueSeparator: Color
        @Composable @ReadOnlyComposable get() = palette.opaqueSeparator

    val systemBlue: Color
        @Composable @ReadOnlyComposable get() = palette.systemBlue
    val systemGreen: Color
        @Composable @ReadOnlyComposable get() = palette.systemGreen
    val systemIndigo: Color
        @Composable @ReadOnlyComposable get() = palette.systemIndigo
    val systemOrange: Color
        @Composable @ReadOnlyComposable get() = palette.systemOrange
    val systemPink: Color
        @Composable @ReadOnlyComposable get() = palette.systemPink
    val systemPurple: Color
        @Composable @ReadOnlyComposable get() = palette.systemPurple
    val systemRed: Color
        @Composable @ReadOnlyComposable get() = palette.systemRed
    val systemTeal: Color
        @Composable @ReadOnlyComposable get() = palette.systemTeal
    val systemYellow: Color
        @Composable @ReadOnlyComposable get() = palette.systemYellow
    val systemMint: Color
        @Composable @ReadOnlyComposable get() = palette.systemMint
    val systemCyan: Color
        @Composable @ReadOnlyComposable get() = palette.systemCyan
    val systemBrown: Color
        @Composable @ReadOnlyComposable get() = palette.systemBrown
    val systemGray: Color
        @Composable @ReadOnlyComposable get() = palette.systemGray
    val systemGray2: Color
        @Composable @ReadOnlyComposable get() = palette.systemGray2
    val systemGray3: Color
        @Composable @ReadOnlyComposable get() = palette.systemGray3
    val systemGray4: Color
        @Composable @ReadOnlyComposable get() = palette.systemGray4
    val systemGray5: Color
        @Composable @ReadOnlyComposable get() = palette.systemGray5
    val systemGray6: Color
        @Composable @ReadOnlyComposable get() = palette.systemGray6

    private val palette: SystemPalette
        @Composable @ReadOnlyComposable get() = if (isSystemInDarkTheme()) DarkPalette else LightPalette
}

internal data class SystemPalette(
    val label: Color,
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    val quaternaryLabel: Color,
    val placeholderText: Color,
    val link: Color,
    val tint: Color,
    val systemBackground: Color,
    val secondarySystemBackground: Color,
    val tertiarySystemBackground: Color,
    val systemGroupedBackground: Color,
    val secondarySystemGroupedBackground: Color,
    val tertiarySystemGroupedBackground: Color,
    val systemFill: Color,
    val secondarySystemFill: Color,
    val tertiarySystemFill: Color,
    val quaternarySystemFill: Color,
    val separator: Color,
    val opaqueSeparator: Color,
    val systemBlue: Color,
    val systemGreen: Color,
    val systemIndigo: Color,
    val systemOrange: Color,
    val systemPink: Color,
    val systemPurple: Color,
    val systemRed: Color,
    val systemTeal: Color,
    val systemYellow: Color,
    val systemMint: Color,
    val systemCyan: Color,
    val systemBrown: Color,
    val systemGray: Color,
    val systemGray2: Color,
    val systemGray3: Color,
    val systemGray4: Color,
    val systemGray5: Color,
    val systemGray6: Color,
)

private fun rgb(red: Int, green: Int, blue: Int, alpha: Float = 1f) =
    Color(red / 255f, green / 255f, blue / 255f, alpha)

internal val LightPalette = SystemPalette(
    label = rgb(0, 0, 0),
    secondaryLabel = rgb(60, 60, 67, 0.60f),
    tertiaryLabel = rgb(60, 60, 67, 0.30f),
    quaternaryLabel = rgb(60, 60, 67, 0.18f),
    placeholderText = rgb(60, 60, 67, 0.30f),
    link = rgb(0, 122, 255),
    tint = rgb(0, 122, 255),
    systemBackground = rgb(255, 255, 255),
    secondarySystemBackground = rgb(242, 242, 247),
    tertiarySystemBackground = rgb(255, 255, 255),
    systemGroupedBackground = rgb(242, 242, 247),
    secondarySystemGroupedBackground = rgb(255, 255, 255),
    tertiarySystemGroupedBackground = rgb(242, 242, 247),
    systemFill = rgb(120, 120, 128, 0.20f),
    secondarySystemFill = rgb(120, 120, 128, 0.16f),
    tertiarySystemFill = rgb(118, 118, 128, 0.12f),
    quaternarySystemFill = rgb(116, 116, 128, 0.08f),
    separator = rgb(60, 60, 67, 0.29f),
    opaqueSeparator = rgb(198, 198, 200),
    systemBlue = rgb(0, 122, 255),
    systemGreen = rgb(52, 199, 89),
    systemIndigo = rgb(88, 86, 214),
    systemOrange = rgb(255, 149, 0),
    systemPink = rgb(255, 45, 85),
    systemPurple = rgb(175, 82, 222),
    systemRed = rgb(255, 59, 48),
    systemTeal = rgb(48, 176, 199),
    systemYellow = rgb(255, 204, 0),
    systemMint = rgb(0, 199, 190),
    systemCyan = rgb(50, 173, 230),
    systemBrown = rgb(162, 132, 94),
    systemGray = rgb(142, 142, 147),
    systemGray2 = rgb(174, 174, 178),
    systemGray3 = rgb(199, 199, 204),
    systemGray4 = rgb(209, 209, 214),
    systemGray5 = rgb(229, 229, 234),
    systemGray6 = rgb(242, 242, 247),
)

internal val DarkPalette = SystemPalette(
    label = rgb(255, 255, 255),
    secondaryLabel = rgb(235, 235, 245, 0.60f),
    tertiaryLabel = rgb(235, 235, 245, 0.30f),
    quaternaryLabel = rgb(235, 235, 245, 0.18f),
    placeholderText = rgb(235, 235, 245, 0.30f),
    link = rgb(10, 132, 255),
    tint = rgb(10, 132, 255),
    systemBackground = rgb(0, 0, 0),
    secondarySystemBackground = rgb(28, 28, 30),
    tertiarySystemBackground = rgb(44, 44, 46),
    systemGroupedBackground = rgb(0, 0, 0),
    secondarySystemGroupedBackground = rgb(28, 28, 30),
    tertiarySystemGroupedBackground = rgb(44, 44, 46),
    systemFill = rgb(120, 120, 128, 0.36f),
    secondarySystemFill = rgb(120, 120, 128, 0.32f),
    tertiarySystemFill = rgb(118, 118, 128, 0.24f),
    quaternarySystemFill = rgb(118, 118, 128, 0.18f),
    separator = rgb(84, 84, 88, 0.60f),
    opaqueSeparator = rgb(56, 56, 58),
    systemBlue = rgb(10, 132, 255),
    systemGreen = rgb(48, 209, 88),
    systemIndigo = rgb(94, 92, 230),
    systemOrange = rgb(255, 159, 10),
    systemPink = rgb(255, 55, 95),
    systemPurple = rgb(191, 90, 242),
    systemRed = rgb(255, 69, 58),
    systemTeal = rgb(64, 200, 224),
    systemYellow = rgb(255, 214, 10),
    systemMint = rgb(99, 230, 226),
    systemCyan = rgb(100, 210, 255),
    systemBrown = rgb(172, 142, 104),
    systemGray = rgb(142, 142, 147),
    systemGray2 = rgb(99, 99, 102),
    systemGray3 = rgb(72, 72, 74),
    systemGray4 = rgb(58, 58, 60),
    systemGray5 = rgb(44, 44, 46),
    systemGray6 = rgb(28, 28, 30),
)
