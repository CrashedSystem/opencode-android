@file:PortedFrom("OpenCode/HeaderBlurParameters.swift", "ebb011d")

package ai.opencode.mobilecode

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/// Constants that shape the floating header's Reduce Transparency backdrop.
object HeaderBlurParameters {
    /// Total height of the backdrop band, including the status bar.
    val bandHeight: Dp = 110.dp

    /// Location where the Reduce Transparency gradient starts to fade
    /// (fraction of the band).
    const val middleStop: Float = 0.4f

    /// Location where the Reduce Transparency gradient reaches zero.
    const val endStop: Float = 0.9f
}
