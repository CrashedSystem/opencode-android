@file:PortedFrom("OpenCode/Voice/VoiceLabDemo.swift", "553a407", partial = "isEnabled, realtimeURL, liveURL, hasStandInServer, overrideEngine")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/// `OPENCODE_LAB_VOICE_DEMO=1`: a scripted call for the simulator lab. You
/// speak, the agent replies, and you cut in mid-reply, so the call bar's
/// visual and captions can be checked without an OpenAI key or a microphone.
/// `OPENCODE_LAB_VOICE_SCRIPT=<path>` plays a recorded conversation instead
/// (see `Script`), once, for demo recordings.
object VoiceLabDemo {
    val isEnabled: Boolean get() = System.getenv("OPENCODE_LAB_VOICE_DEMO") != null

    /// `OPENCODE_LAB_REALTIME_URL=ws://…`: the real call path against the lab's
    /// stand-in Realtime server (`scripts/realtime-lab.ts`), which can expire
    /// sessions in seconds and drop sockets on demand.
    val realtimeURL: URL?
        get() = System.getenv("OPENCODE_LAB_REALTIME_URL")?.let(URL::parse)

    /// `OPENCODE_LAB_LIVE_URL=ws://…`: the real call path against the lab's
    /// stand-in GPT-Live server (`scripts/live-lab.ts` or `scripts/realtime-lab.ts`).
    val liveURL: URL?
        get() = System.getenv("OPENCODE_LAB_LIVE_URL")?.let(URL::parse)

    val hasStandInServer: Boolean get() = realtimeURL != null || liveURL != null

    /// Optional lab engine override (`OPENCODE_LAB_VOICE_ENGINE=gpt-live|realtime`),
    /// or inferred when only one engine's stand-in URL is set.
    val overrideEngine: VoiceEngine?
        get() {
            val raw = System.getenv("OPENCODE_LAB_VOICE_ENGINE")
            val engine = raw?.let { runCatching { Json.decodeFromJsonElement(VoiceEngine.serializer(), JsonPrimitive(it)) }.getOrNull() }
            if (engine != null) {
                return engine
            }
            if (liveURL != null && realtimeURL == null) return VoiceEngine.gptLive
            if (realtimeURL != null && liveURL == null) return VoiceEngine.realtime
            return null
        }
}
