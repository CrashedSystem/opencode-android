@file:PortedFrom("OpenCode/HeaderBlurParameters.swift", "553a407")

package ai.opencode.mobilecode

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/// Everything that shapes the floating header's backdrop.
data class HeaderBlurParameters(
    /// Total height of the backdrop band, including the status bar.
    var bandHeight: Dp = 110.dp,
    /// Location where the Reduce Transparency gradient starts to fade
    /// (fraction of the band).
    var middleStop: Float = 0.4f,
    /// Location where the Reduce Transparency gradient reaches zero.
    var endStop: Float = 0.9f,
    /// Use UIKit's soft scroll-edge effect under the header.
    var usesSystemEdge: Boolean = true,
) {
    companion object {
        val shipped = HeaderBlurParameters()
    }
}
