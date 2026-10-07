@file:PortedFrom("OpenCode/Voice/VoiceCallClient.swift", "553a407", partial = "VoiceCallEvent")

package ai.opencode.mobilecode

/// Semantic view of one call: what was said, what the model asked for, and
/// how the audio path is doing. Bytes, base64, and timing stay below this.
sealed interface VoiceCallEvent {
    data object connected : VoiceCallEvent

    data object userSpeechStarted : VoiceCallEvent

    data class userTranscript(val itemID: String, val text: String, val final: Boolean) : VoiceCallEvent

    data class assistantDelta(val itemID: String?, val delta: String) : VoiceCallEvent

    data class assistantDone(val itemID: String?, val text: String) : VoiceCallEvent

    data object assistantPlaybackFinished : VoiceCallEvent

    data class functionCall(val callID: String, val name: String, val arguments: RealtimeJSON) : VoiceCallEvent

    data object audioInterrupted : VoiceCallEvent

    data object audioResumed : VoiceCallEvent

    data object routeChanged : VoiceCallEvent

    /// The session dropped and a replacement is being opened.
    data object reconnecting : VoiceCallEvent

    data object reconnected : VoiceCallEvent

    data class serviceError(val message: String) : VoiceCallEvent
}
