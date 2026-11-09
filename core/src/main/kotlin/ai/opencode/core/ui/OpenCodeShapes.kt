package ai.opencode.core.ui

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * The Material 3 corner radius scale (https://m3.material.io/styles/shape/corner-radius-scale),
 * named for the roles the iOS radii played so ported call sites keep their meaning. iOS's
 * 6/10/14/20/22/26/28 continuous corners land on M3's 4/8/12/16/20/28/28.
 */
object OpenCodeRadius {
    /** extraSmall (4): tiny marks, inline code, checkbox insets. */
    val xs: Dp = 4.dp
    /** small (8): small fills and thumbnails. */
    val sm: Dp = 8.dp
    /** medium (12): filled fields and rows. */
    val fill: Dp = 12.dp
    /** large (16): inline controls and trays. */
    val control: Dp = 16.dp
    /** large (16): cards (server list, error report). */
    val card: Dp = 16.dp
    /** largeIncreased (20): attachment chips, clipped media, settings tiles. */
    val tile: Dp = 20.dp
    /** extraLarge (28): large panels (connect, server details, What's New cards). */
    val panel: Dp = 28.dp
    /** extraLarge (28): sheets, scanner frame, model list, voice panel. */
    val sheet: Dp = 28.dp
}

/** Shapes for the iOS forms: capsules and circles stay capsules and circles. */
object OpenCodeShape {
    val capsule = RoundedCornerShape(percent = 50)
    val circle = CircleShape
    val card = RoundedCornerShape(OpenCodeRadius.card)
    val panel = RoundedCornerShape(OpenCodeRadius.panel)
    val sheet = RoundedCornerShape(OpenCodeRadius.sheet)
    val fill = RoundedCornerShape(OpenCodeRadius.fill)

    /** Top-only rounding for bottom sheets. */
    val sheetTop = RoundedCornerShape(topStart = OpenCodeRadius.sheet, topEnd = OpenCodeRadius.sheet)
}

/**
 * The Material 3 shape scale: extraSmall 4, small 8, medium 12, large 16, largeIncreased 20,
 * extraLarge 28, extraLargeIncreased 32, extraExtraLarge 48. Material components read these
 * (menus extraSmall/large, cards medium, dialogs and sheets extraLarge).
 */
val OpenCodeShapes = Shapes()

/**
 * Port of `GlassGeometry` (`Glass.swift`): a rounded rectangle's radius must be at most half its
 * height. Material shapes clamp on their own, but animated shapes computed by hand (morphs,
 * growing panels) should clamp with this so every intermediate frame stays a valid
 * capsule-or-less (docs/native-ui-guidance.md). iOS removed the unused `GlassShape.clamped`
 * wrapper in 18771799.
 */
object OpenCodeShapeGeometry {
    fun radius(radius: Float, height: Float): Float = min(max(0f, radius), max(0f, height) / 2)
}

/**
 * Spacing values the iOS app uses (its common `spacing:` and `.padding` values), in dp.
 * Prefer these to literals so layouts stay on the same grid as iOS.
 */
object OpenCodeSpacing {
    val s1: Dp = 1.dp
    val s2: Dp = 2.dp
    val s4: Dp = 4.dp
    val s6: Dp = 6.dp
    val s8: Dp = 8.dp
    val s10: Dp = 10.dp
    val s12: Dp = 12.dp
    val s14: Dp = 14.dp
    val s16: Dp = 16.dp
    val s18: Dp = 18.dp
    val s20: Dp = 20.dp
    val s24: Dp = 24.dp
    val s32: Dp = 32.dp

    /** Screen and list-row horizontal inset (iOS `.padding(.horizontal, 16)`). */
    val screenHorizontal: Dp = s16

    /** Default stack spacing between related items (iOS `spacing: 8`). */
    val stack: Dp = s8

    /** Between groups/sections (iOS `spacing: 12`). */
    val group: Dp = s12
}
