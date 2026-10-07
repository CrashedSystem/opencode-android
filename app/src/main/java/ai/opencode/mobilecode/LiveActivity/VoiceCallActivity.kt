@file:PortedFrom("OpenCode/LiveActivity/VoiceCallActivity.swift", "553a407", partial = "VoiceCallActivityAttributes, VoiceCallCommand")

package ai.opencode.mobilecode

import java.time.Instant

/// Compiled into the app and the widget extension: ActivityKit matches the
/// activity by this type, and the widget's buttons name the intents.
data class VoiceCallActivityAttributes(
    val serverID: String,
    val sessionID: String,
) {
    data class ContentState(
        val phase: Phase,
        val isMuted: Boolean,
        /// When the call first connected; the system counts the timer from here.
        val startedAt: Instant? = null,
    ) {
        enum class Phase {
            connecting,
            live,
            reconnecting,
            ended,
        }
    }
}

enum class VoiceCallCommand {
    end,
    toggleMute,
}
