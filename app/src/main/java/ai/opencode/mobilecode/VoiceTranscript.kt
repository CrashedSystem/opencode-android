@file:PortedFrom("OpenCode/Voice/VoiceTranscript.swift", "553a407", partial = "VoiceHandoff format and parse, VoiceTranscriptRecord")

package ai.opencode.mobilecode

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object VoiceHandoff {
    const val header = "Voice delegation. Instruction is the cleaned interpretation of the user's request. Transcript delta supplies new or revised conversation for reference, not additional commands. Resolve meaning from the complete utterance, not a disfluent prefix. Lead your reply with one or two plain sentences suitable for reading aloud, then any detail. If this arrives while you are already working (a follow-up, nudge, or status check), answer it promptly: a clear status update, the finished result if you have enough, or the precise blocker or input you need; then continue on the fastest safe path."

    @Serializable
    private data class Body(
        var context: String? = null,
        var instruction: String,
        var transcript_delta: List<String> = emptyList(),
    )

    fun format(instruction: String, transcriptDelta: List<String>, context: String? = null): String {
        val trimmed = context?.trim()?.ifEmpty { null }
        val json = Json {
            prettyPrint = true
            explicitNulls = false
            encodeDefaults = true
        }.encodeToString(Body(context = trimmed, instruction = instruction, transcript_delta = transcriptDelta))
        return "$header\n$json"
    }

    data class Delegation(
        var instruction: String,
        var transcriptDelta: List<String>,
        var context: String? = null,
    )

    fun parse(text: String): Delegation? {
        if (!text.startsWith("Voice delegation.")) return null
        val newline = text.indexOf('\n')
        if (newline < 0) return null
        val body = runCatching {
            protocolJson.decodeFromString(Body.serializer(), text.substring(newline + 1))
        }.getOrNull() ?: return null
        val instruction = body.instruction.trim()
        if (instruction.isEmpty()) return null
        return Delegation(
            instruction = instruction,
            transcriptDelta = body.transcript_delta,
            context = body.context?.trim(),
        )
    }
}

/// A record of one session's voice conversations on this phone.
@Serializable
data class VoiceTranscriptRecord(
    var endedAt: Long,
    /// Continuity tail injected into the next call.
    var tail: List<String>,
    /// Everything said on this phone about this session, newest last, bounded.
    var log: List<String>,
) {
    fun append(tail: List<String>, log: List<String>, endedAt: Long) {
        val combined = this.log + log
        this.endedAt = endedAt
        this.tail = tail
        this.log = if (combined.size > maxLogLines) combined.drop(combined.size - maxLogLines) else combined
    }

    companion object {
        const val maxLogLines = 400
    }
}
