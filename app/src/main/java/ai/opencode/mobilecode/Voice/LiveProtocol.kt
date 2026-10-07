@file:PortedFrom("OpenCode/Voice/LiveProtocol.swift", "4084466", partial = "VoiceEngine")

package ai.opencode.mobilecode

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/// Which OpenAI voice engine new calls use.
@Serializable
enum class VoiceEngine {
    @SerialName("gpt-live")
    gptLive,

    @SerialName("realtime")
    realtime,

    ;

    val id: VoiceEngine get() = this

    val title: String
        get() = when (this) {
            gptLive -> "GPT-Live"
            realtime -> "Realtime"
        }

    val modelID: String
        get() = when (this) {
            gptLive -> "gpt-live-1"
            realtime -> "gpt-realtime-2.1"
        }
}
