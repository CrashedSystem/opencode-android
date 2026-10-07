@file:PortedFrom("OpenCode/SessionFeature.swift", "553a407", partial = "PendingPrompt")

package ai.opencode.mobilecode

class SessionFeature {
    /// A send whose connection failed: the server may still have it. If the
    /// message turns up, its notice and the draft restored for a retry go.
    data class PendingPrompt(
        var messageID: String,
        var text: String,
        var attachments: List<OpenCodeOutgoingAttachment>,
        var files: List<ComposerFileAttachment> = emptyList(),
        var optimisticIDs: Set<String>,
        var wasRunning: Boolean = false,
        var delivery: OpenCodeDelivery = OpenCodeDelivery.steer,
        var requestEpoch: Int,
    )
}
