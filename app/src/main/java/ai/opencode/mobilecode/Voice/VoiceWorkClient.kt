@file:PortedFrom("OpenCode/Voice/VoiceWorkClient.swift", "553a407", partial = "VoiceWorkTarget")

package ai.opencode.mobilecode

/// The bridge from a voice delegation to the OpenCode server. The voice
/// feature never talks to `OpenCodeClient` directly, so this seam stays
/// independently testable and decoupled from the rest of the app.
data class VoiceWorkTarget(
    val server: OpenCodeServer,
    /// Nil while the call sits on a new session's draft: the first request
    /// creates the session in `directory`.
    val sessionID: String? = null,
    val directory: String,
    /// Set only on a submission from a draft: the model the created session
    /// starts with. The call's own target never carries one.
    val model: OpenCodeModelRef? = null,
)
