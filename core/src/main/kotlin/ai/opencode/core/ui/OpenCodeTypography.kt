package ai.opencode.core.ui

import ai.opencode.core.R
import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight

/** Font families. Everything is the system sans (Roboto / the device font); iOS uses SF. */
object OpenCodeFonts {
    /** The platform sans-serif, the Android counterpart of SF Pro. */
    val system: FontFamily = FontFamily.Default

    /** Monospace for code (iOS: SF Mono via `.monospaced()`). */
    val monospace: FontFamily = FontFamily.Monospace

    /**
     * Berkeley Mono (variable), only for onboarding's brand marks, exactly as iOS restricts
     * `BerkeleyMonoVariable` (the `opencode` wordmark, `opencode pair --remote`).
     */
    @OptIn(ExperimentalTextApi::class)
    val berkeleyMono: FontFamily = FontFamily(
        listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { weight ->
            Font(
                R.font.berkeley_mono,
                weight = weight,
                variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
            )
        },
    )
}

/**
 * The Material 3 type scale (https://m3.material.io/styles/typography/type-scale-tokens) on the
 * system sans. Material components read it through `MaterialTheme.typography`, including the
 * M3 Expressive `*Emphasized` styles.
 */
val OpenCodeTypography: Typography = Typography()

/**
 * The iOS Dynamic Type style names, each mapped to its nearest Material 3 role so a view ported
 * from `.font(.footnote)` keeps its place in the hierarchy but renders at the Android size
 * (iOS's 17 pt body reads heavy on Android). Sizes are sp, so they follow the system font scale.
 *
 * | iOS style | M3 role | size/line |
 * | --- | --- | --- |
 * | largeTitle | headlineLarge | 32/40 |
 * | title1 | headlineMedium | 28/36 |
 * | title2 | headlineSmall | 24/32 |
 * | title3 | titleLarge | 22/28 |
 * | headline | titleMedium | 16/24 medium |
 * | body, callout | bodyLarge | 16/24 |
 * | subheadline | bodyMedium | 14/20 |
 * | footnote, caption1 | bodySmall | 12/16 |
 * | caption2 | labelSmall | 11/16 medium |
 *
 * Buttons, menu items and dialog actions use labelLarge (14/20 medium) through their Material
 * components; reach for the `*Emphasized` roles in `MaterialTheme.typography` where iOS bolds
 * for emphasis.
 */
object OpenCodeTextStyles {
    /** `.largeTitle` → headlineLarge 32/40. */
    val largeTitle: TextStyle = OpenCodeTypography.headlineLarge
    /** `.title` (title1) → headlineMedium 28/36. */
    val title1: TextStyle = OpenCodeTypography.headlineMedium
    /** `.title2` → headlineSmall 24/32. */
    val title2: TextStyle = OpenCodeTypography.headlineSmall
    /** `.title3` → titleLarge 22/28. */
    val title3: TextStyle = OpenCodeTypography.titleLarge
    /** `.headline` → titleMedium 16/24 medium. */
    val headline: TextStyle = OpenCodeTypography.titleMedium
    /** `.body` → bodyLarge 16/24. */
    val body: TextStyle = OpenCodeTypography.bodyLarge
    /** `.callout` → bodyLarge 16/24 (M3 has no step between body and bodyMedium). */
    val callout: TextStyle = OpenCodeTypography.bodyLarge
    /** `.subheadline` → bodyMedium 14/20. */
    val subheadline: TextStyle = OpenCodeTypography.bodyMedium
    /** `.footnote` → bodySmall 12/16. */
    val footnote: TextStyle = OpenCodeTypography.bodySmall
    /** `.caption` (caption1) → bodySmall 12/16. */
    val caption1: TextStyle = OpenCodeTypography.bodySmall
    /** `.caption2` → labelSmall 11/16 medium. */
    val caption2: TextStyle = OpenCodeTypography.labelSmall
}
