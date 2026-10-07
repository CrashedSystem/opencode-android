@file:PortedFrom("OpenCode/Voice/VoiceTranscript.swift", "a858da2", partial = "VoiceTranscriptTail, VoiceHandoff format and parse, VoiceTranscriptRecord, VoiceInstructions.Context")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.tca.ObservableState
import ai.opencode.mobilecode.tca.StoreVar
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
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
        /// The version most recently delivered to the session, and its text.
        val sent: Int = 0,
        val sentText: String? = null,
        /// The version captured for an admission that has not yet been accepted, and its text.
        val reserved: Int? = null,
        val reservedText: String? = null,
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
        /// Captured version and text by entry key.
        internal val versions: Map<Int, Int>,
        internal val texts: Map<Int, String> = emptyMap(),
        /// What the user said since the previous delegation, in order: new
        /// utterances whole, and only the words an utterance gained since it was
        /// delivered (a revision that rewrote it travels whole).
        val userWords: List<String> = emptyList(),
    ) {
        /// The user's own words since the previous delegation: the request.
        val userText: String get() = userWords.joinToString(" ")
    }

    private var entries by StoreVar<List<Entry>>(emptyList())
    private var nextKey by StoreVar(0)
    private var evictedIDs by StoreVar<Set<String>>(emptySet())

    init {
        entries = lines.mapIndexed { key, line ->
            if (line.startsWith("assistant: ")) {
                val text = line.drop("assistant: ".length)
                return@mapIndexed Entry(key = key, role = Role.assistant, text = text, sent = 1, sentText = text)
            }
            val text = if (line.startsWith("user: ")) line.drop("user: ".length) else line
            Entry(key = key, role = Role.user, text = text, sent = 1, sentText = text)
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
        val texts = mutableMapOf<Int, String>()
        val userWords = mutableListOf<String>()
        val reserved = entries.toMutableList()
        for (index in entries.indices) {
            val entry = entries[index]
            val delivered = maxOf(entry.sent, entry.reserved ?: 0)
            if (entry.version <= delivered) continue
            val label = if (entry.sent > 0) "${entry.role.rawValue} (updated utterance)" else entry.role.rawValue
            lines += "$label: ${entry.text}"
            versions[entry.key] = entry.version
            texts[entry.key] = entry.text
            if (entry.role == Role.user) {
                var words = entry.text
                val previous = entry.reservedText ?: entry.sentText
                if (previous != null && entry.text.startsWith(previous)) {
                    words = entry.text.drop(previous.length).trim()
                }
                if (words.isNotEmpty()) userWords += words
            }
            reserved[index] = entry.copy(reserved = entry.version, reservedText = entry.text)
        }
        entries = reserved
        return Capture(lines = lines.toList(), versions = versions.toMap(), texts = texts.toMap(), userWords = userWords.toList())
    }

    fun accept(capture: Capture) {
        entries = entries.map { entry ->
            val version = capture.versions[entry.key] ?: return@map entry
            var updated = entry
            if (version > updated.sent) updated = updated.copy(sent = version, sentText = capture.texts[entry.key])
            if (updated.reserved == version) updated = updated.copy(reserved = null, reservedText = null)
            updated
        }
    }

    fun release(capture: Capture) {
        entries = entries.map { entry ->
            val version = capture.versions[entry.key] ?: return@map entry
            if (entry.reserved == version) entry.copy(reserved = null, reservedText = null) else entry
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

/// What the pinned session receives for one `delegate` call: the user's own
/// words as the request, the voice agent's framing for display, and the
/// surrounding conversation as background.
object VoiceHandoff {
    const val header = "Voice delegation. The user is on a voice call with a voice agent, which hands their requests to you. Instruction is the user's own spoken words since the previous delegation, transcribed from speech, so it may contain recognition errors and disfluencies: resolve meaning from the complete utterance, not a disfluent prefix. Voice, when present, is how the voice agent framed the request. Transcript delta is the surrounding voice conversation, both sides, for context, not additional commands. Lead your reply with one or two plain sentences suitable for reading aloud, then any detail. If this arrives while you are already working (a follow-up, nudge, or status check), answer it promptly: a clear status update, the finished result if you have enough, or the precise blocker or input you need; then continue on the fastest safe path."

    /// Later delegations in a call point back at the first one's header (D4a).
    const val continuedHeader = "Voice delegation (continued). Same fields as before."

    /// How long a session goes without a delegation before the next one
    /// carries the full header again. Within a conversation delegations come
    /// seconds or minutes apart; half an hour without one means the session has
    /// usually moved on (typed prompts, long tool output), leaving the
    /// explanation far back in its context. Repeating it costs about 200 tokens.
    val headerLapse: Duration = 30.minutes

    enum class Header {
        /// The explanation: a call's first delegation into a session, or after
        /// compaction or a lapse.
        full,

        /// A short marker for later delegations.
        continued,
    }

    @Serializable
    private data class Body(
        var context: String? = null,
        var instruction: String,
        var transcript_delta: List<String> = emptyList(),
        var voice: String? = null,
    )

    fun format(
        instruction: String,
        transcriptDelta: List<String>,
        context: String? = null,
        framing: String? = null,
        header: Header = Header.full,
    ): String {
        val trimmedContext = context?.trim()
        val trimmedFraming = framing?.trim()
        val json = Json {
            prettyPrint = true
            explicitNulls = false
            encodeDefaults = true
        }.encodeToString(
            Body(
                context = if (trimmedContext?.isEmpty() == false) trimmedContext else null,
                instruction = instruction,
                transcript_delta = transcriptDelta,
                voice = if (trimmedFraming?.isEmpty() == false) trimmedFraming else null,
            ),
        )
        return "${if (header == Header.full) this.header else continuedHeader}\n$json"
    }

    /// The voice agent's framing of a delegation, shown in the session as the
    /// Voice message: Realtime's `delegate` text, or GPT-Live's spoken
    /// acknowledgement. Nil when it names nothing ("On it", "Sure, I'll take a
    /// look"), so the session shows the user's words instead. A word count
    /// can't tell "Checking the README." from "Looking that up now."; whether
    /// any word names the task can.
    fun framing(text: String?): String? {
        val trimmed = text?.trim()
        if (trimmed.isNullOrEmpty()) return null
        val words = trimmed.lowercase()
            .replace("’", "'")
            .split(Regex("[^\\p{L}\\p{N}']+"))
            .filter { it.isNotEmpty() }
        return if (words.any { !genericWords.contains(it) }) trimmed else null
    }

    /// Acknowledgement filler, pronouns, and verbs that say work is happening
    /// without saying which.
    private val genericWords: Set<String> = setOf(
        "a", "about", "alright", "all", "an", "and", "at", "away", "be", "bit", "check", "checking", "do", "doing",
        "find", "finding", "for", "get", "getting", "go", "going", "good", "got", "great", "here", "i", "i'll", "i'm",
        "into", "is", "it", "it's", "just", "let", "let's", "look", "looking", "me", "moment", "now", "of", "ok",
        "okay", "on", "one", "out", "over", "quick", "quickly", "real", "right", "second", "see", "seeing", "sec",
        "so", "sounds", "start", "starting", "sure", "take", "taking", "that", "that's", "the", "there", "these",
        "thing", "this", "those", "to", "up", "we", "we'll", "what", "what's", "will", "with", "work", "working",
        "yeah", "yep", "yes", "you",
    )

    /// A prompt `format` wrote, read back for display. Nil for anything else,
    /// including text that merely mentions a voice delegation.
    data class Delegation(
        /// The user's own words.
        var instruction: String,
        var transcriptDelta: List<String>,
        var context: String? = null,
        /// The voice agent's framing; prompts from before it was sent have none.
        var framing: String? = null,
    ) {
        /// What the session shows: the voice agent prompting on the user's behalf.
        val displayText: String get() = framing ?: instruction
    }

    fun parse(text: String): Delegation? {
        // The header's wording may change; its opening and the body's shape won't.
        if (!text.startsWith("Voice delegation.") && !text.startsWith("Voice delegation (continued).")) return null
        val newline = text.indexOf('\n')
        if (newline < 0) return null
        val body = runCatching {
            protocolJson.decodeFromString(Body.serializer(), text.substring(newline + 1))
        }.getOrNull() ?: return null
        val instruction = body.instruction.trim()
        if (instruction.isEmpty()) return null
        val framing = body.voice?.trim()
        return Delegation(
            instruction = instruction,
            transcriptDelta = body.transcript_delta,
            context = body.context?.trim(),
            framing = if (framing?.isEmpty() == false) framing else null,
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
