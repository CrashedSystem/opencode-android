@file:PortedFrom("OpenCode/Voice/VoiceFeature.swift", "553a407", partial = "State, Action, Delegate, CancelID as placeholders for SessionFeature and HomeFeature, plus the members they reach for after 553a407 (CompactionMark, compactions, and compactionObserved from 0bda024; Delegate.openSession from 7a54834)")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.tca.Identifiable
import ai.opencode.mobilecode.tca.ObservableState
import ai.opencode.mobilecode.tca.Reduce
import ai.opencode.mobilecode.tca.Reducer
import ai.opencode.mobilecode.tca.Shared
import ai.opencode.mobilecode.tca.SharedKey
import ai.opencode.mobilecode.tca.StoreVar
import ai.opencode.mobilecode.tca.withLock
import java.io.File
import java.time.Instant

/// One voice call attached to the open session. It follows the screen: the
/// drawer moves it to another session, and New Session moves it to the draft,
/// where the first spoken request creates the session. The voice model
/// converses; the target session does the work; its reply comes back as speech.
class VoiceFeature : Reducer<VoiceFeature.State, VoiceFeature.Action> {
    class State private constructor(
        target: VoiceWorkTarget,
        sessionTitle: String,
        directoryName: String,
    ) : ObservableState() {
        sealed interface Phase {
            data object connecting : Phase

            data object live : Phase

            /// The model asked to hang up; `spoken` flips once its goodbye response
            /// has been delivered, so the drain that ends the call is the right one.
            data class endingAfterGoodbye(val spoken: Boolean) : Phase

            data object ended : Phase

            data class failed(val message: String) : Phase

            /// The connection was lost and couldn't be restored; the call can be rejoined.
            data object dropped : Phase

            /// A card above the composer replaces the call bar.
            val showsCard: Boolean
                get() = when (this) {
                    is failed, dropped -> true
                    connecting, live, is endingAfterGoodbye, ended -> false
                }

            val isTerminal: Boolean
                get() = when (this) {
                    ended, is failed, dropped -> true
                    connecting, live, is endingAfterGoodbye -> false
                }
        }

        data class Caption(
            override val id: String,
            val role: VoiceTranscriptTail.Role,
            val text: String,
        ) : Identifiable<String>

        data class PendingWork(
            val callID: String,
            val capture: VoiceTranscriptTail.Capture,
            val promptID: String? = null,
            val isClientDelegation: Boolean = false,
            val pendingLateText: String? = null,
            val superseded: Boolean = false,
        )

        /// The newest finished compaction the session screen has seen.
        data class CompactionMark(
            val sessionID: String,
            val compactionID: String,
        )

        var target by StoreVar(target)
        var sessionTitle by StoreVar(sessionTitle)
        var directoryName by StoreVar(directoryName)

        /// The session is waiting on a native approval or answer.
        var targetNeedsInput by StoreVar(false)
        var phase by StoreVar<Phase>(Phase.connecting)
        var isMuted by StoreVar(false)
        var isAssistantSpeaking by StoreVar(false)
        var pendingWork by StoreVar<List<PendingWork>>(emptyList())
        var captions by StoreVar<List<Caption>>(emptyList())
        var liveAssistantText by StoreVar("")
        var liveAssistantItemID by StoreVar<String?>(null)
        var tail by StoreVar(VoiceTranscriptTail())
        var previousEndedAt by StoreVar<Instant?>(null)
        var showsCaptions by StoreVar(false)

        /// Another app or call took the audio; the bar shows a pause glyph until it's back.
        var isAudioInterrupted by StoreVar(false)

        /// The call is moving to a new connection and it has taken over a second.
        var isReconnecting by StoreVar(false)

        /// When the call first connected; reconnects keep it, a new call clears it.
        var connectedAt by StoreVar<Instant?>(null)

        /// The target the model was configured with when the call started.
        var startedTarget by StoreVar<VoiceWorkTarget?>(null)

        /// Requests left behind when the call moved; the model is told they keep
        /// running in their own session.
        var abandonedCallIDs by StoreVar<List<String>>(emptyList())

        /// On a draft: the model the session the call creates starts with, as a
        /// typed first send would. Kept out of `target` so picking one isn't a move.
        var draftModel by StoreVar<OpenCodeModelRef?>(null)

        /// The voice engine locked in when the call starts.
        var activeEngine by StoreVar(VoiceEngine.gptLive)

        /// Session created by this draft call's first request; adopting it is the
        /// same conversation, not a user retarget.
        var adoptedDraftSessionID by StoreVar<String?>(null)

        /// The newest finished compaction the session screen has seen, by session ID.
        var compactions by StoreVar<Map<String, String>>(emptyMap())

        /// Where the call is and what it let go of. Home tells the voice model
        /// when either changes; New Session from a draft in the same folder
        /// leaves the target as it was but still lets work go.
        data class Placement(
            val target: VoiceWorkTarget,
            val abandonedCallIDs: List<String>,
        )

        val placement: Placement get() = Placement(target = target, abandonedCallIDs = abandonedCallIDs)
        var voiceEngine by Shared(SharedKey.voiceEngine)
        var apiKey by Shared(SharedKey.openAIAPIKey)
        var transcripts by Shared(SharedKey.voiceTranscripts)

        /// Nil on a draft, which has nothing to remember yet.
        val transcriptKey: String? get() = target.sessionID?.let { "${target.server.id}:$it" }
        val isOnDraft: Boolean get() = target.sessionID == null

        /// The lab's scripted call needs no key; unsigned simulator builds can't
        /// store one in the keychain.
        val callAPIKey: String?
            get() {
                if (BuildConfig.DEBUG) {
                    if (VoiceLabDemo.isEnabled || VoiceLabDemo.hasStandInServer) return "lab"
                }
                return apiKey
            }
        val resolvedEngine: VoiceEngine
            get() {
                if (BuildConfig.DEBUG) {
                    VoiceLabDemo.overrideEngine?.let { return it }
                }
                return voiceEngine
            }
        val appContextSummary: String
            get() {
                if (isOnDraft) {
                    return "New session draft in project \"$directoryName\"."
                }
                var summary = "Session \"$sessionTitle\" in project \"$directoryName\"."
                if (targetNeedsInput) {
                    summary += " Waiting on user input in the app."
                }
                return summary
            }
        val instructionsContext: VoiceInstructions.Context
            get() = VoiceInstructions.Context(
                sessionTitle = sessionTitle,
                directoryName = directoryName,
                recentMessages = emptyList(),
                previousTail = tail.lines,
                previousEndedAt = previousEndedAt,
                isNewSession = isOnDraft,
            )
        val isWorking: Boolean get() = pendingWork.isNotEmpty()
        val hasAdmittedWork: Boolean get() = pendingWork.any { it.promptID != null }

        /// Prompts the target admitted for this call and has not answered yet:
        /// the call's claim that its session is running. Ending the call drops
        /// its work, so a finished call never holds a run open.
        val admittedPromptIDs: List<String> get() = pendingWork.mapNotNull { it.promptID }

        constructor(server: OpenCodeServer, session: OpenCodeSession) : this(
            target = VoiceWorkTarget(server = server, sessionID = session.id, directory = session.directory),
            sessionTitle = usableTitle(session.title) ?: "New session",
            directoryName = File(session.directory).name,
        ) {
            val previous = transcripts["${server.id}:${session.id}"]
            tail = VoiceTranscriptTail(lines = previous?.tail ?: emptyList())
            previousEndedAt = previous?.endedAt?.let(Instant::ofEpochMilli)
        }

        /// A call started on a new session's draft in `directory`; its first
        /// request creates the session there.
        constructor(server: OpenCodeServer, draftDirectory: String, model: OpenCodeModelRef?) : this(
            target = VoiceWorkTarget(server = server, sessionID = null, directory = draftDirectory),
            sessionTitle = "New session",
            directoryName = File(draftDirectory).name,
        ) {
            draftModel = model
            tail = VoiceTranscriptTail(lines = emptyList())
        }

        /// Retargets an active call to another session when the user switches
        /// sessions in the drawer.
        fun retarget(to: OpenCodeSession, endedAt: Instant = Instant.now()) {
            val session = to
            sessionTitle = usableTitle(session.title) ?: "New session"
            if (target.sessionID == session.id) return
            adoptedDraftSessionID = null
            persistTranscript(endedAt = endedAt)
            abandonWork()
            captions = emptyList()
            clearLiveReply()
            target = target.copy(sessionID = session.id)
            target = target.copy(directory = session.directory)
            directoryName = File(session.directory).name
            targetNeedsInput = false
            val previous = transcriptKey?.let { transcripts[it] }
            tail = VoiceTranscriptTail(lines = previous?.tail ?: emptyList())
            previousEndedAt = previous?.endedAt?.let(Instant::ofEpochMilli)
        }

        /// Moves the call to a new session's draft in `directory`; the first
        /// request creates the session there. A new draft starts a new
        /// conversation, even when the call was already on one.
        fun retargetToDraft(directory: String, model: OpenCodeModelRef?, endedAt: Instant = Instant.now()) {
            adoptedDraftSessionID = null
            abandonWork()
            if (target.sessionID != null) persistTranscript(endedAt = endedAt)
            captions = emptyList()
            clearLiveReply()
            tail = VoiceTranscriptTail(lines = emptyList())
            previousEndedAt = null
            draftModel = model
            target = target.copy(sessionID = null)
            target = target.copy(directory = directory)
            sessionTitle = "New session"
            directoryName = File(directory).name
            targetNeedsInput = false
        }

        /// The draft the call is on changed project: the same conversation,
        /// created somewhere else. A request already creating the session there
        /// keeps going on its own.
        fun moveDraft(to: String) {
            val directory = to
            if (!isOnDraft || target.directory == directory) return
            adoptedDraftSessionID = null
            abandonWork()
            target = target.copy(directory = directory)
            directoryName = File(directory).name
        }

        /// Drops a reply still being spoken: it was cut off, or the call moved on.
        fun clearLiveReply() {
            liveAssistantText = ""
            liveAssistantItemID = null
        }

        /// Work already admitted keeps running in its session; the call just stops waiting for it.
        private fun abandonWork() {
            for (work in pendingWork.filter { it.promptID == null }) {
                tail.release(work.capture)
            }
            abandonedCallIDs += pendingWork.map { it.callID }
            pendingWork = emptyList()
        }

        fun persistTranscript(endedAt: Instant) {
            val key = transcriptKey ?: return
            val lines = captions.map { "${it.role.rawValue}: ${it.text}" }
            val tail = this.tail.lines
            this::transcripts.withLock {
                val record = (value[key] ?: VoiceTranscriptRecord(endedAt = endedAt.toEpochMilli(), tail = emptyList(), log = emptyList())).copy()
                record.append(tail = tail, log = lines, endedAt = endedAt.toEpochMilli())
                value += (key to record)
            }
        }

        val statusText: String
            get() = when (val phase = phase) {
                Phase.connecting -> "Connecting…"
                Phase.live if isReconnecting -> "Reconnecting…"
                Phase.live if isAudioInterrupted -> "Paused"
                Phase.live if targetNeedsInput -> "Needs your input"
                Phase.live if isWorking -> "Working"
                Phase.live if isAssistantSpeaking -> "Speaking"
                Phase.live -> if (isMuted) "Muted" else "Listening"
                is Phase.endingAfterGoodbye -> "Ending…"
                Phase.ended -> "Ended"
                Phase.dropped -> "Call ended"
                is Phase.failed -> phase.message
            }

        companion object {
            fun usableTitle(title: String): String? {
                val trimmed = title.trim()
                if (trimmed.isEmpty() || trimmed.equals("Untitled session", ignoreCase = true)) return null
                return trimmed
            }
        }
    }

    sealed interface Action {
        data object start : Action

        data object rejoinTapped : Action

        data object callDropped : Action

        data object reconnectingShown : Action

        data class callEvent(val event: VoiceCallEvent) : Action

        data class callEnded(val errorMessage: String?) : Action

        data class workAdmitted(val callID: String, val promptID: String) : Action

        /// The session screen saw a finished compaction in the call's session.
        data class compactionObserved(val mark: State.CompactionMark) : Action

        data class lateUtteranceFinished(val callID: String, val capture: VoiceTranscriptTail.Capture, val promptID: String?) : Action

        data class sessionStarted(val callID: String, val session: OpenCodeSession) : Action

        data class sessionRenamed(val title: String) : Action

        /// The call moved to another session or a draft: refresh the model's instructions.
        data object targetChanged : Action

        data class workReplied(val callID: String, val reply: String?) : Action

        data class workFailed(val callID: String, val message: String) : Action

        data object muteTapped : Action

        data object endTapped : Action

        data object captionsTapped : Action

        data object captionsDismissed : Action

        data class delegate(val action: VoiceFeature.Delegate) : Action
    }

    /// Swift nests this in `Action`. It lives here because `delegate` and `Delegate` are one file on a case-insensitive disk.
    sealed interface Delegate {
        data object checkAPIKey : Delegate

        data class sessionCreated(val session: OpenCodeSession) : Delegate

        /// The delegator put a session on the user's screen.
        data class openSession(val session: OpenCodeSession) : Delegate

        data object ended : Delegate
    }

    private sealed interface CancelID {
        data object call : CancelID

        data object goodbye : CancelID

        data object work : CancelID

        data object instructions : CancelID

        data object reconnecting : CancelID

        data class workAdmission(val id: String) : CancelID

        data class workCall(val id: String) : CancelID

        data class lateUtterance(val id: String) : CancelID
    }

    override val body: Reducer<State, Action> =
        Reduce { _ -> TODO("VoiceFeature's reducer is not ported yet") }
}
