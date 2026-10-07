@file:PortedFrom("OpenCode/Voice/DotScopeSimulation.swift", "553a407", partial = "VoiceCallHaptic")

package ai.opencode.mobilecode

/// Haptic events for a voice call's lifecycle and controls.
enum class VoiceCallHaptic {
    callStarted,
    sweepPass,
    connected,
    muted,
    unmuted,
    ended,
    ;

    companion object {
        /// Maps a mute toggle to its haptic feedback.
        fun forMute(isMuted: Boolean): VoiceCallHaptic = if (isMuted) muted else unmuted
    }
}
