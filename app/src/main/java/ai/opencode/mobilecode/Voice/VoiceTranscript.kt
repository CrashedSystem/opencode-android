@file:PortedFrom("OpenCode/Voice/VoiceTranscript.swift", "553a407", partial = "VoiceTranscriptTail, VoiceHandoff format and parse, VoiceTranscriptRecord, VoiceInstructions.Context")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.tca.ObservableState
import ai.opencode.mobilecode.tca.StoreVar
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/// The bounded conversation tail that travels with each delegation, ported
/// from opencode-voice's `transcript.ts`. Provider utterance IDs identify
/// turns; text equality never does. A re-transcribed utterance is a revision
/// of its line, not a new line.
// A state, not a data class: the reducer mutates it in place, as Swift mutates the struct.
class VoiceTranscriptTail(lines: List<String> = emptyList()) : ObservableState() {
    @Serializable
    enum class Role(val rawValue: String) {
        user("user"),
        assistant("assistant"),
    }

    private data class Entry(
        /// Stable local identity: captures outlive evictions that shift array
        /// positions, and not every line has a provider ID.
        val key: Int,
        val providerID: String? = null,
        val role: Role,
        val text: String,
        val version: Int = 1,
        /// The version most recently delivered to the session.
        val sent: Int = 0,
        /// The version captured for an admission that has not yet been accepted.
        val reserved: Int? = null,
    )

    enum class RecordOutcome {
        added,
        updated,
        duplicate,
        outsideTail,
        empty,
    }

    data class Capture(
        val lines: List<String>,
        /// Captured version by entry key.
        internal val versions: Map<Int, Int>,
        /// Provider utterance IDs included in this capture, so late updates to a
        /// captured utterance can be matched without swallowing newer turns.
        val providerIDs: Set<String> = emptySet(),
    ) {
        fun contains(providerID: String): Boolean = providerIDs.contains(providerID)

        /// The user's spoken words in this capture, in order, for client
        /// delegations that carry no separate tool instruction.
        val userText: String
            get() = lines.mapNotNull { line ->
                if (line.startsWith("user (updated utterance): ")) {
                    return@mapNotNull line.drop("user (updated utterance): ".length)
                }
                if (line.startsWith("user: ")) {
                    return@mapNotNull line.drop("user: ".length)
                }
                null
            }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(" ")
    }

    private var entries by StoreVar<List<Entry>>(emptyList())
    private var nextKey by StoreVar(0)
    private var evictedIDs by StoreVar<Set<String>>(emptySet())

    init {
        entries = lines.mapIndexed { key, line ->
            if (line.startsWith("assistant: ")) {
                return@mapIndexed Entry(key = key, role = Role.assistant, text = line.drop("assistant: ".length), sent = 1)
            }
            val text = if (line.startsWith("user: ")) line.drop("user: ".length) else line
            Entry(key = key, role = Role.user, text = text, sent = 1)
        }
        nextKey = entries.size
    }

    fun record(role: Role, text: String, id: String? = null): RecordOutcome {
        val providerID = id
        val text = text.trim()
        if (text.isEmpty()) return RecordOutcome.empty
        if (providerID != null) {
            val index = entries.indexOfFirst { it.providerID == providerID }
            if (index >= 0) {
                if (entries[index].text == text) return RecordOutcome.duplicate
                entries = entries.toMutableList().also { it[index] = it[index].copy(text = text, version = it[index].version + 1) }
                evict()
                return RecordOutcome.updated
            }
            if (evictedIDs.contains(providerID)) return RecordOutcome.outsideTail
        }
        entries += Entry(key = nextKey, providerID = providerID, role = role, text = text)
        nextKey += 1
        evict()
        return RecordOutcome.added
    }

    /// Lines changed since the last accepted capture, reserved until the
    /// caller either accepts (admitted) or releases (failed) them.
    fun capture(): Capture {
        val lines = mutableListOf<String>()
        val versions = mutableMapOf<Int, Int>()
        val providerIDs = mutableSetOf<String>()
        val reserved = entries.toMutableList()
        for (index in entries.indices) {
            val entry = entries[index]
            val delivered = maxOf(entry.sent, entry.reserved ?: 0)
            if (entry.version <= delivered) continue
            val label = if (entry.sent > 0) "${entry.role.rawValue} (updated utterance)" else entry.role.rawValue
            lines += "$label: ${entry.text}"
            versions[entry.key] = entry.version
            entry.providerID?.let { providerIDs += it }
            reserved[index] = entry.copy(reserved = entry.version)
        }
        entries = reserved
        return Capture(lines = lines.toList(), versions = versions.toMap(), providerIDs = providerIDs.toSet())
    }

    fun accept(capture: Capture) {
        entries = entries.map { entry ->
            val version = capture.versions[entry.key] ?: return@map entry
            entry.copy(
                sent = maxOf(entry.sent, version),
                reserved = if (entry.reserved == version) null else entry.reserved,
            )
        }
    }

    fun release(capture: Capture) {
        entries = entries.map { entry ->
            val version = capture.versions[entry.key] ?: return@map entry
            if (entry.reserved == version) entry.copy(reserved = null) else entry
        }
    }

    /// Plain `role: text` lines for persistence and next-call continuity.
    val lines: List<String>
        get() = entries.map { "${it.role.rawValue}: ${it.text}" }

    private fun evict() {
        while (entries.size > maxLines || characterCount > maxCharacters) {
            val removed = entries.first()
            entries = entries.drop(1)
            removed.providerID?.let { evictedIDs += it }
        }
    }

    private val characterCount: Int
        get() = entries.fold(maxOf(0, entries.size - 1)) { total, entry ->
            total + entry.role.rawValue.length + 2 + entry.text.length
        }

    companion object {
        const val maxLines = 12
        const val maxCharacters = 2_000
    }
}

/// What the pinned session receives for one `delegate` call.
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

object VoiceInstructions {
    data class Context(
        val sessionTitle: String,
        val directoryName: String,
        val recentMessages: List<String>,
        val previousTail: List<String>,
        val previousEndedAt: Instant? = null,
        /// A new session's draft: nothing exists until the first delegation.
        val isNewSession: Boolean = false,
    )
}
