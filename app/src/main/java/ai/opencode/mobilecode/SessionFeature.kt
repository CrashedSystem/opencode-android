@file:PortedFrom("OpenCode/SessionFeature.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.tca.BindableAction
import ai.opencode.mobilecode.tca.BindingAction
import ai.opencode.mobilecode.tca.BindingReducer
import ai.opencode.mobilecode.tca.Case
import ai.opencode.mobilecode.tca.Dependency
import ai.opencode.mobilecode.tca.DependencyValues
import ai.opencode.mobilecode.tca.Effect
import ai.opencode.mobilecode.tca.Identifiable
import ai.opencode.mobilecode.tca.ObservableState
import ai.opencode.mobilecode.tca.PresentationAction
import ai.opencode.mobilecode.tca.Presents
import ai.opencode.mobilecode.tca.Reduce
import ai.opencode.mobilecode.tca.Reducer
import ai.opencode.mobilecode.tca.Shared
import ai.opencode.mobilecode.tca.SharedKey
import ai.opencode.mobilecode.tca.StoreVar
import ai.opencode.mobilecode.tca.Send
import ai.opencode.mobilecode.tca.cancellable
import ai.opencode.mobilecode.tca.combine
import ai.opencode.mobilecode.tca.continuousClock
import ai.opencode.mobilecode.tca.date
import ai.opencode.mobilecode.tca.ifLet
import ai.opencode.mobilecode.tca.isTesting
import ai.opencode.mobilecode.tca.onChange
import ai.opencode.mobilecode.tca.uuid
import ai.opencode.mobilecode.tca.wallClock
import ai.opencode.mobilecode.tca.withLock
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

class SessionFeature : Reducer<SessionFeature.State, SessionFeature.Action> {
    class State private constructor(server: OpenCodeServer) : ObservableState(), Identifiable<Int> {
        /// Home assigns a fresh value per opened detail. TCA cancels a presented
        /// child's effects only when its identity changes, so without this a
        /// drawer switch that swaps one session for another in place would leave
        /// the old session's polls and dictation running.
        var navigationID by StoreVar(0)
        override val id: Int get() = navigationID
        var isVisible by StoreVar(false)

        /// False for a catch-up card: the reader's swipe decides whether it was read.
        var readsOnView by StoreVar(true)

        /// Whether a pending-request snapshot has arrived for this screen yet.
        var hasLoadedPendingRequests by StoreVar(false)
        var viewedIdle by StoreVar<Map<String, Long>>(emptyMap())

        data class DraftState(
            val text: String = "",
            val attachments: List<OpenCodeOutgoingAttachment> = emptyList(),
            val files: List<ComposerFileAttachment> = emptyList(),
        )

        var server by StoreVar(server)

        /// nil while drafting a new session; set once the first message creates it.
        var session by StoreVar<OpenCodeSession?>(null)
        var draftProject by StoreVar<OpenCodeProject?>(null)
        var projects by StoreVar<List<OpenCodeProject>>(emptyList())
        var items by StoreVar<List<OpenCodeTranscriptItem>>(emptyList())
        var isLoading by StoreVar(false)
        var initialTranscriptRequestIsExternal by StoreVar(false)
        var initialTranscriptIsFresh by StoreVar(false)
        var hasRenderedFirstContentFrame by StoreVar(false)
        var nextRefreshID by StoreVar(0)

        /// The authoritative read in flight. Its unique ID rejects the response
        /// of any read abandoned before it.
        data class Refresh(
            val id: Int,
            val reason: RefreshReason,
            /// A newer request that may have raced this read earns one follow-up.
            val followup: RefreshReason? = null,
            /// Live events that raced this read, replayed onto its result.
            val racedEvents: List<OpenCodeLiveContentEvent> = emptyList(),
        )

        var refresh by StoreVar<Refresh?>(null)
        var streamIsHealthy by StoreVar(false)

        /// The cadence of the running pending-work poll; nil while it is stopped.
        var pendingWorkPollSeconds by StoreVar<Int?>(null)
        var streamSupportsProjection by StoreVar(false)
        var watchdogEnabledInTests by StoreVar(false)

        /// Raw live text is retained separately from rendered markdown so batches
        /// append exactly once and `text.ended` can replace the complete value.
        var liveText by StoreVar<Map<String, String>>(emptyMap())
        var projectedMessageIDs by StoreVar<Set<String>>(emptySet())
        var lastProjectedMessageID by StoreVar<String?>(null)

        data class ResyncState(
            val refreshID: Int,
            val isShown: Boolean = false,
            val responseReceived: Boolean = false,
            val minimumElapsed: Boolean = false,
        )

        var resync by StoreVar<ResyncState?>(null)
        val isResyncing: Boolean get() = resync?.isShown == true
        var refreshFailed by StoreVar(false)
        var errorMessage by StoreVar<String?>(null)
        var visibleLimit by StoreVar(20)

        /// The leading edge of the window when the reader leaves the live tail.
        /// Appends may grow that window, but cannot evict the rows being read.
        var browsingWindowStartID by StoreVar<String?>(null)

        sealed interface EarlierHistory {
            /// The newest authoritative window has not started a cursor chain yet.
            data object unknownEdge : EarlierHistory

            /// Pages are walking backward in search of an item already loaded.
            data class seekingOverlap(val cursor: String?) : EarlierHistory

            /// An overlap was found, so subsequent pages are known to be older.
            data class paging(val cursor: String?) : EarlierHistory

            data class loading(val cursor: String?, val reachedLoadedItems: Boolean) : EarlierHistory

            data object exhausted : EarlierHistory
        }

        var earlierHistory by StoreVar<EarlierHistory>(EarlierHistory.unknownEdge)
        var earlierHistoryError by StoreVar<String?>(null)

        /// An automatic page failed because the server was unreachable; page
        /// again quietly once the connection is back instead of showing an error.
        var earlierHistoryRetriesOnReconnect by StoreVar(false)
        var historyGeneration by StoreVar(0)

        /// Invalidates cursor reads independently of the transcript visit. A gap
        /// repair must not reset UIKit's reading position or disclosure state.
        var earlierHistoryGeneration by StoreVar(0)

        /// The leading row of the last cursor page. Retained older windows can
        /// precede it when a refresh skipped an interval, so the next page inserts
        /// here rather than blindly before the entire transcript.
        var earlierHistoryInsertionID by StoreVar<String?>(null)

        /// Identifies the optimistic row most recently appended by an explicit
        /// send. UIKit uses the generation as an edge-trigger for choreography.
        var lastSentItemID by StoreVar<String?>(null)
        var sendGeneration by StoreVar(0)
        var draft by StoreVar("")
        var isAwaitingReply by StoreVar(false)
        var isInterrupting by StoreVar(false)

        sealed interface DictationPhase {
            data object idle : DictationPhase

            /// Mic is on but the engine is still setting up (possibly downloading
            /// the on-device model on first use) — no words can appear yet.
            data object preparing : DictationPhase

            data object recording : DictationPhase

            /// The mic was stopped intentionally and the engine is finalizing; its
            /// last (often only) transcript is still welcome in the draft.
            data class finishing(val sendWhenComplete: Boolean) : DictationPhase
        }

        var dictationPhase by StoreVar<DictationPhase>(DictationPhase.idle)
        var dictationError by StoreVar<String?>(null)

        /// Recording a message by voice, Telegram style: the mic was held (and
        /// maybe locked), words collect here instead of in the draft, and they
        /// are sent when recording ends. Nil for VoiceOver's tap-to-dictate,
        /// which streams into the draft.
        var dictationHold by StoreVar<DictationHold?>(null)

        data class DictationHold(
            /// The draft as it will read with the words so far, typed text first.
            val text: String,
            /// Slid up or tapped: recording continues hands-free until Send or Cancel.
            val isLocked: Boolean = false,
            /// When the mic went down, for the recording timer.
            val startedAt: Instant,
        )

        /// The server is running this session (any client or agent) — driven by
        /// live execution events. Shows the working indicator for external runs.
        var isExternallyActive by StoreVar(false)

        /// Admitted-but-unpromoted work: what you (or another client) sent while a
        /// run was in flight. Projected history omits it, so without this the
        /// message vanishes until the runner promotes it.
        var queuedInputs by StoreVar<List<OpenCodePendingInput>>(emptyList())

        data class PendingPromptPosition(
            val precedingPromptIDs: Set<String>,
            val precedingMessageIDs: Set<String>,
            val requestEpoch: Int,
            val isBeforeWindow: Boolean = false,
        )

        /// Placement for prompts first seen on this screen, including voice and
        /// other clients. Kept until a transcript echo or explicit cancellation.
        var pendingPromptPositions by StoreVar<Map<String, PendingPromptPosition>>(emptyMap())

        /// Withdrawn here; hidden from inbox reads that started before the cancel.
        var cancelledInputIDs by StoreVar<Set<String>>(emptySet())

        /// Keyed by prompt ID; see `UnconfirmedSendFailure`.
        var unconfirmedSendFailures by StoreVar<Map<String, UnconfirmedSendFailure>>(emptyMap())

        /// Prompt IDs whose client-rendered transcript parts have not appeared in
        /// an authoritative history window yet.
        var optimisticPromptIDs by StoreVar<Set<String>>(emptySet())

        /// Durable per-message echoes waiting for a page that includes them. They
        /// keep their history position, unlike an undelivered optimistic tail.
        var echoedPromptIDs by StoreVar<Set<String>>(emptySet())

        data class PromptState(
            val requestEpoch: Int,
            val admission: Admission = Admission.sent,
        ) {
            enum class Admission { sent, admitted }
        }

        /// Prompts sent from this screen that have not reached the transcript.
        var promptStates by StoreVar<Map<String, PromptState>>(emptyMap())
        var nextPromptRequestEpoch by StoreVar(0)

        /// Short tombstone window rejecting inbox responses that raced a prompt's
        /// authoritative transcript promotion.
        var promotedPromptIDs by StoreVar<List<String>>(emptyList())

        /// Local-only image presentation keyed by the authoritative prompt ID.
        var queuedInputImages by StoreVar<Map<String, List<OpenCodeImageAttachment>>>(emptyMap())
        var models by StoreVar<List<OpenCodeModel>>(emptyList())
        var isLoadingModels by StoreVar(false)
        var modelFilter by StoreVar("")
        var currentModel by StoreVar<OpenCodeModelRef?>(null)
        var projectFilter by StoreVar("")
        var pendingAttachments by StoreVar<List<OpenCodeOutgoingAttachment>>(emptyList())

        /// Non-image files: uploading, uploaded (sent as path references), or failed.
        var pendingFiles by StoreVar<List<ComposerFileAttachment>>(emptyList())

        /// Send was tapped while files were still uploading; the message goes
        /// with this delivery once they land.
        var sendAfterUploads by StoreVar<OpenCodeDelivery?>(null)

        /// Shared files absorbed into this composer whose uploads haven't started.
        var unstartedUploadIDs by StoreVar<List<UUID>>(emptyList())
        var permissionRequests by StoreVar<List<OpenCodePermissionRequest>>(emptyList())
        var formRequests by StoreVar<List<OpenCodeFormRequest>>(emptyList())
        var formAnswers by StoreVar<Map<String, Map<String, OpenCodeFormValue>>>(emptyMap())
        var familySessions by StoreVar<List<OpenCodeSession>>(emptyList())
        var familyRootID by StoreVar<String?>(null)
        var activeFamilySessionIDs by StoreVar<Set<String>>(emptySet())
        var familyTranscriptCache by StoreVar<Map<String, List<OpenCodeTranscriptItem>>>(emptyMap())
        var familyDrafts by StoreVar<Map<String, DraftState>>(emptyMap())
        var respondingRequestIDs by StoreVar<Set<String>>(emptySet())
        var questionPageIndices by StoreVar<Map<String, Int>>(emptyMap())
        var requestLoadError by StoreVar<String?>(null)

        enum class RequestLoadAttempt { initial, reconnect, manual }

        var requestLoadAttempt by StoreVar<RequestLoadAttempt?>(null)
        var requestFailureAwaitingReconnect by StoreVar(false)
        var requestRetryEnabledInTests by StoreVar(false)
        var requestsConnectionIsAvailable by StoreVar(true)
        var requestErrors by StoreVar<Map<String, String>>(emptyMap())
        var selectedRequestID by StoreVar<String?>(null)
        var requestNotice by StoreVar<String?>(null)

        // A successful reply must not become actionable again through an older poll.
        var completedRequestIDs by StoreVar<Set<String>>(emptySet())

        /// Mutually exclusive sheets: an optional enum can never present both.
        var activePicker by StoreVar<ActivePicker?>(null)

        /// A voice call that starts on this session and can retarget across sessions.
        var voice by Presents<VoiceFeature.State>()
        var favoriteModelIDs by Shared(SharedKey.favoriteModelIDs)
        var voiceModeEnabled by Shared(SharedKey.voiceModeEnabled)
        var openAIAPIKey by Shared(SharedKey.openAIAPIKey)
        var uploadUnsupportedServerIDs by Shared(SharedKey.uploadUnsupportedServerIDs)

        /// Voice is an opt-in Advanced feature and requires the user's own OpenAI
        /// key (Settings → Advanced); without both, the call button stays hidden.
        val isVoiceAvailable: Boolean
            get() {
                if (BuildConfig.DEBUG) {
                    if (VoiceLabDemo.isEnabled || VoiceLabDemo.hasStandInServer) return true
                }
                return voiceModeEnabled && (openAIAPIKey ?: "").trim().isNotEmpty()
            }

        enum class ActivePicker(val rawValue: String) : Identifiable<String> {
            model("model"),
            project("project"),
            requests("requests"),
            session("session"),
            ;

            override val id: String get() = rawValue
        }

        /// The Session sheet's work; kept after dismissal so a fork or export
        /// in flight still lands. Presented while `activePicker == .session`.
        var sessionSheet by StoreVar<SessionSheetFeature.State?>(null)

        /// What the latest reply filled; nil when unknown or nothing fills it.
        var contextUsage by StoreVar<OpenCodeContextUsage?>(null)

        /// A step ended since the last context read, so the next refresh reads it.
        var contextNeedsRead by StoreVar(false)

        val contextLimit: Int?
            get() {
                val usage = contextUsage ?: return null
                return models.firstOrNull { it.matches(usage.model) }?.contextLimit
            }

        /// The share of the window used, as the TUI's percent; nil hides the ring.
        val contextFraction: Double?
            get() {
                val usage = contextUsage ?: return null
                val limit = contextLimit ?: return null
                return usage.tokens.toDouble() / limit.toDouble()
            }

        /// A root's spend includes its subagents', as in the TUI.
        val sessionCost: Double?
            get() {
                val session = session ?: return null
                if (session.parentID != null) return session.cost
                val family = familySessions.filter { it.id != session.id }
                val total = (session.cost ?: 0.0) + family.fold(0.0) { sum, member -> sum + (member.cost ?: 0.0) }
                return if (total > 0) total else null
            }

        constructor(
            server: OpenCodeServer,
            session: OpenCodeSession,
            cachedItems: List<OpenCodeTranscriptItem>? = null,
            initialTranscriptRequestIsExternal: Boolean = false,
            isRunning: Boolean = false,
            navigationID: Int = 0,
        ) : this(server) {
            this.navigationID = navigationID
            this.session = session
            familySessions = listOf(session)
            familyRootID = session.id
            activeFamilySessionIDs = if (isRunning) setOf(session.id) else emptySet()
            items = cachedItems ?: emptyList()
            isLoading = cachedItems == null
            this.initialTranscriptRequestIsExternal = initialTranscriptRequestIsExternal
            currentModel = session.model
            isExternallyActive = isRunning
        }

        constructor(
            server: OpenCodeServer,
            draftProject: OpenCodeProject?,
            projects: List<OpenCodeProject>,
            inheritedModel: OpenCodeModelRef? = null,
            navigationID: Int = 0,
        ) : this(server) {
            this.navigationID = navigationID
            session = null
            this.draftProject = draftProject
            this.projects = projects
            items = emptyList()
            isLoading = false
            // A draft has no history to page; the flag stays true once created.
            earlierHistory = EarlierHistory.exhausted
            // The server's own default resolution is unreliable (falls back to the
            // catalog head), so new sessions inherit the model you last used.
            currentModel = inheritedModel
        }

        val isDraft: Boolean get() = session == null

        /// Typed admission and queued prompts bridge gaps in server execution;
        /// a voice call also owns work until its delegated replies arrive.
        val isRunning: Boolean get() = isAwaitingReply || isExternallyActive || isRunningVoiceWork

        /// Busy descendants are visible even after this session's own run ends.
        /// Keep `isRunning` local: sending, interruption and voice target one run.
        val activeSubagents: List<OpenCodeSession>
            get() {
                val selectedID = session?.id ?: return emptyList()
                val parents = familySessions.mapNotNull { member -> member.parentID?.let { member.id to it } }.toMap()
                return familySessions.filter {
                    it.id != selectedID && activeFamilySessionIDs.contains(it.id) &&
                        SessionActivity.ancestors(of = setOf(it.id), parents = parents).contains(selectedID)
                }.sortedBy { it.id }
            }

        val isBusy: Boolean get() = isRunning || activeSubagents.isNotEmpty()
        val hasBackgroundWork: Boolean get() = activeSubagents.isNotEmpty() || backgroundShells.isNotEmpty()

        /// A voice call vouches for its pinned session from admission until the
        /// last delegated prompt answers or the call ends.
        private val isRunningVoiceWork: Boolean
            get() {
                val voice = voice
                if (voice == null || voice.target.sessionID != session?.id) return false
                return voice.hasAdmittedWork
            }

        fun presentRequestReview(id: String?) {
            selectedRequestID = id
            requestNotice = null
            activePicker = ActivePicker.requests
        }

        fun ensureSessionSheet(): Boolean {
            val session = session ?: return false
            if (sessionSheet?.sessionID != session.id) {
                sessionSheet = SessionSheetFeature.State(server = server, sessionID = session.id)
            }
            return true
        }

        /// Records a session in this family as read up to its idle watermark.
        fun markViewed(session: OpenCodeSession) {
            val idle = session.idleMilliseconds ?: return
            val shown = this.session
            if (shown != null && shown.id == session.id) {
                this.session = shown.copy(viewedMilliseconds = maxOf(shown.viewedMilliseconds ?: 0L, idle))
            }
            val index = familySessions.indexOfFirst { it.id == session.id }
            if (index >= 0) {
                familySessions = familySessions.toMutableList().also {
                    it[index] = it[index].copy(viewedMilliseconds = maxOf(it[index].viewedMilliseconds ?: 0L, idle))
                }
            }
        }

        fun updateObservedSession(observed: OpenCodeSession) {
            session = observed
            val index = familySessions.indexOfFirst { it.id == observed.id }
            if (index >= 0) {
                familySessions = familySessions.toMutableList().also { it[index] = observed }
            }
            renameVoiceTarget(sessionID = observed.id, title = observed.title)
        }

        /// The call's bar and Live Activity name its session.
        fun renameVoiceTarget(sessionID: String, title: String) {
            val resolved = VoiceFeature.State.usableTitle(title) ?: return
            if (voice?.target?.sessionID != sessionID || voice?.sessionTitle == resolved) return
            voice?.sessionTitle = resolved
        }

        fun resetLiveProjection() {
            liveText = emptyMap()
            projectedMessageIDs = emptySet()
            lastProjectedMessageID = null
        }

        /// Running shells of the selected session; reset when the selection changes.
        var shells by StoreVar<List<OpenCodeShell>>(emptyList())

        /// An exit can beat the list response that still reports the shell.
        var endedShellIDs by StoreVar<Set<String>>(emptySet())

        /// Shells that outlive their tool call, matching the TUI's shell count
        /// minus commands the agent is still waiting on in the foreground. A
        /// live-started shell counts only once its tool call returns, so a short
        /// command never flashes the indicator before its progress event lands.
        val backgroundShells: List<OpenCodeShell>
            get() {
                val sessionID = session?.id
                if (sessionID == null || shells.isEmpty()) return emptyList()
                return shells.filter { shell ->
                    if (shell.sessionID != sessionID) return@filter false
                    if (shell.isUserCommand) return@filter true
                    val owner = items.lastOrNull { item ->
                        if (item !is OpenCodeTranscriptItem.tool) return@lastOrNull false
                        item.call.metadata["shellID"]?.string == shell.id
                    }
                    if (owner !is OpenCodeTranscriptItem.tool) return@filter shell.isListed
                    owner.call.status == OpenCodeToolCall.Status.completed || owner.call.status == OpenCodeToolCall.Status.error
                }
            }

        /// The live output sheet for one shell.
        var shellOutput by Presents<ShellOutputFeature.State>()

        /// A running shell or subagent tool the current run is waiting on, as in
        /// the TUI's background hint.
        data class ForegroundWork(
            val key: String,
            val startedAt: Instant? = null,
        )

        /// Only the transcript's tail can hold the run's current tool.
        val foregroundWork: ForegroundWork?
            get() {
                if (!isRunning || session == null) return null
                for (item in items.takeLast(64).asReversed()) {
                    if (item !is OpenCodeTranscriptItem.tool) continue
                    val call = item.call
                    if (call.messageID == null || call.status != OpenCodeToolCall.Status.running ||
                        !(call.isShell || call.name == "subagent" || call.name == "task")
                    ) {
                        continue
                    }
                    return ForegroundWork(key = item.id, startedAt = call.startedAt?.let(Instant::ofEpochMilli))
                }
                return null
            }

        /// The foreground work that has blocked the run long enough to offer moving it.
        var movableWorkKey by StoreVar<String?>(null)

        /// Work already moved (or being moved) from here; never offered again.
        var movedWorkKeys by StoreVar<Set<String>>(emptySet())

        val showsMoveToBackground: Boolean
            get() {
                val key = movableWorkKey
                if (key == null || movedWorkKeys.contains(key)) return false
                return foregroundWork?.key == key
            }

        val isSubmittingPrompt: Boolean get() = promptStates.values.any { it.admission == PromptState.Admission.sent }
        val hasPendingLocalPrompt: Boolean
            get() = isSubmittingPrompt || queuedInputs.any { promptStates[it.id] != null }

        val supportsFileUploads: Boolean get() = !uploadUnsupportedServerIDs.contains(server.persistenceID)
        val isUploadingFiles: Boolean get() = pendingFiles.any { it.isUploading }
        val isWaitingForUploads: Boolean get() = sendAfterUploads != null

        /// Something to send: text, inline media, or files.
        val hasPromptContent: Boolean
            get() = draft.trim().isNotEmpty() || pendingAttachments.isNotEmpty() || pendingFiles.isNotEmpty()

        /// The composer's text, inline media, and files, saved while elsewhere.
        var draftState: DraftState
            get() = DraftState(text = draft, attachments = pendingAttachments, files = pendingFiles)
            set(newValue) {
                draft = newValue.text
                pendingAttachments = newValue.attachments
                pendingFiles = newValue.files
            }

        /// Restores a saved composer. An upload still in flight when it was saved
        /// lost its effect, so it comes back as failed and retryable.
        fun restoreDraft(saved: DraftState) {
            draft = saved.text
            pendingAttachments = saved.attachments
            pendingFiles = saved.files.map { file ->
                if (!file.isUploading) return@map file
                file.copy(status = ComposerFileAttachment.Status.failed("Couldn’t upload"))
            }
        }

        /// A failed upload blocks sending until it is retried or removed.
        val canSendPrompt: Boolean
            get() = !isSubmittingPrompt && !isWaitingForUploads && hasPromptContent && !pendingFiles.any { it.isFailed }

        /// Whether the selected model reads `input` ("pdf", "image") natively.
        fun currentModelReads(input: String): Boolean {
            val currentModel = currentModel ?: return false
            return models.firstOrNull { it.ref == currentModel }?.inputs?.contains(input) == true
        }

        val isDictating: Boolean
            get() = dictationPhase == DictationPhase.preparing || dictationPhase == DictationPhase.recording

        val isPreparingDictation: Boolean get() = dictationPhase == DictationPhase.preparing

        val isFinishingDictation: Boolean get() = dictationPhase is DictationPhase.finishing

        /// Dictation or a call's bar has the composer, so a Send Report? offer
        /// waits until it's done. A dropped or failed call's card leaves room.
        val defersReportOffer: Boolean
            get() = isDictating || isFinishingDictation || voice?.let { !it.phase.showsCard } == true

        val isLoadingEarlier: Boolean get() = earlierHistory is EarlierHistory.loading

        /// Some request dock is (or should be) replacing the composer.
        val hasPendingRequests: Boolean
            get() = permissionRequests.isNotEmpty() || formRequests.isNotEmpty()

        val reviewRequestIDs: List<String> get() = formRequests.map { it.id } + permissionRequests.map { it.id }

        /// Transient request failures stay quiet while connectivity is already
        /// explaining the outage. A failure becomes actionable only after the
        /// reconnect-triggered retry itself has settled unsuccessfully.
        val settledRequestLoadError: String?
            get() {
                if (!requestsConnectionIsAvailable || requestLoadAttempt != null) return null
                return requestLoadError
            }

        val currentModelName: String
            get() {
                val currentModel = currentModel ?: return "Model"
                return models.firstOrNull { it.ref == currentModel }?.name ?: currentModel.id
            }

        val filteredModels: List<OpenCodeModel>
            get() = models.filtered(by = modelFilter) { listOf(it.name, it.providerID, it.modelID) }

        val isFilteringModels: Boolean
            get() = modelFilter.trim { it == '\t' || it.category == CharCategory.SPACE_SEPARATOR }.isNotEmpty()

        val favoriteModels: List<OpenCodeModel>
            get() = filteredModels.filter { favoriteModelIDs.contains(it.id) }

        val unfavoriteModels: List<OpenCodeModel>
            get() = filteredModels.filter { !favoriteModelIDs.contains(it.id) }

        val filteredProjects: List<OpenCodeProject>
            get() = projects.filtered(by = projectFilter) { listOf(it.name, it.directory) }

        val queuedInputRows: List<OpenCodeQueuedInput>
            get() = queuedInputs.map { OpenCodeQueuedInput(input = it, images = queuedInputImages[it.id] ?: emptyList()) }

        val visibleItems: List<OpenCodeTranscriptItem>
            get() {
                val latestStart = maxOf(0, items.size - visibleLimit)
                val browsingStart = browsingWindowStartID?.let { id ->
                    items.indexOfFirst { it.id == id }.takeIf { it >= 0 }
                }
                return items.subList(minOf(latestStart, browsingStart ?: latestStart), items.size)
            }

        val hasEarlierItems: Boolean
            get() = items.size > visibleLimit ||
                // A restored cache can be browsed immediately, but do not start the
                // cursor chain from its stale edge. Wait for the authoritative newest
                // window to overlap it first; otherwise every page can be skipped while
                // searching for overlap and the final page reports a false conflict.
                (earlierHistory != EarlierHistory.exhausted && !isLoading &&
                    (!hasRenderedFirstContentFrame || initialTranscriptIsFresh))
    }

    sealed interface Action : BindableAction<State> {
        data class binding(override val binding: BindingAction<State>) : Action

        data object task : Action

        data object firstContentFrameRendered : Action

        data class visibilityChanged(val visible: Boolean) : Action

        data class resyncDelayElapsed(val refreshID: Int) : Action

        data class resyncMinimumElapsed(val refreshID: Int) : Action

        data class viewAcknowledged(val sessionID: String, val idle: Long) : Action

        data class refreshRequested(val reason: RefreshReason) : Action

        data class refreshResponse(val sessionID: String, val requestID: Int, val result: Result<AuthoritativeRefresh>) : Action

        data class acceptedTranscriptUpdated(val sessionID: String, val items: List<OpenCodeTranscriptItem>) : Action

        data class eventStreamConnected(val server: OpenCodeServer) : Action

        data class contentBatch(val sessionID: String, val events: List<OpenCodeLiveContentEvent>) : Action

        data class messageInvalidated(val sessionID: String) : Action

        data class externalRunStateChanged(val isRunning: Boolean) : Action

        data class transcriptBrowsingChanged(val isBrowsing: Boolean) : Action

        data object showEarlierTapped : Action

        data object fillEarlierHistory : Action

        data class earlierHistoryResponse(
            val sessionID: String,
            val generation: Int,
            val cursor: String?,
            val result: Result<OpenCodeTranscriptPage>,
        ) : Action

        data object sendTapped : Action

        data object queueTapped : Action

        data object returnKeyTapped : Action

        data object micTapped : Action

        /// Touch down on the mic: dictate while held.
        data object micPressBegan : Action

        /// Let go of a held mic: finish and send.
        data object micPressReleased : Action

        /// Slid up while holding (or a quick tap): keep recording hands-free.
        data object micPressLocked : Action

        /// Slid left while holding, or tapped Cancel: drop the words, send nothing.
        data object micPressCancelled : Action

        /// Tapped Stop in the dictation bar: finish and place the transcription in the draft.
        data object dictationStopTapped : Action

        data object dictationSendTapped : Action

        /// The scene went to the background (not merely inactive).
        data object sceneEnteredBackground : Action

        data object voiceTapped : Action

        data object voiceExitFinished : Action

        data class voice(val action: PresentationAction<VoiceFeature.Action>) : Action {
            companion object : Case<Action, PresentationAction<VoiceFeature.Action>>({ voice(it) }, { (it as? voice)?.action })
        }

        data object dictationReady : Action

        data class dictationUpdated(val text: String) : Action

        data class dictationStopped(val errorMessage: String?) : Action

        data object interruptTapped : Action

        data class interruptFinished(val sessionID: String, val error: OpenCodeClientError?) : Action

        data class sessionCreated(val session: OpenCodeSession) : Action

        data class promptFailed(val sessionID: String?, val error: OpenCodeClientError, val pending: PendingPrompt) : Action

        data class promptAdmitted(val sessionID: String, val messageID: String, val requestEpoch: Int) : Action

        data class promptEchoResponse(
            val sessionID: String,
            val messageID: String,
            val requestEpoch: Int,
            val items: List<OpenCodeTranscriptItem>,
        ) : Action

        data class remotePromptMissing(val sessionID: String, val messageID: String, val requestEpoch: Int) : Action

        data class replyCompleted(val sessionID: String) : Action

        data class transcriptResponse(val sessionID: String, val result: Result<List<OpenCodeTranscriptItem>>) : Action

        data object modelButtonTapped : Action

        data class modelsResponse(val result: Result<OpenCodeModelCatalog>) : Action

        data class modelPicked(val model: OpenCodeModel) : Action

        data class toggleFavoriteModel(val model: OpenCodeModel) : Action

        data class switchModelFailed(val sessionID: String, val error: OpenCodeClientError, val revertTo: OpenCodeModelRef?) : Action

        data object projectButtonTapped : Action

        data class projectPicked(val project: OpenCodeProject) : Action

        data class familyPicked(val id: String) : Action

        data object familyRefreshRequested : Action

        data class familyResponse(val result: Result<OpenCodeSessionFamily>) : Action

        data class sessionSnapshotUpdated(val session: OpenCodeSession) : Action

        data class familyRunStateChanged(val sessionID: String, val isRunning: Boolean) : Action

        data class familySessionRenamed(val sessionID: String, val title: String) : Action

        data class familySessionDeleted(val sessionID: String) : Action

        data class shellsResponse(val sessionID: String, val result: Result<List<OpenCodeShell>>) : Action

        data class shellStarted(val shell: OpenCodeShell) : Action

        data class shellEnded(val shellID: String) : Action

        data class shellOutputTapped(val shellID: String) : Action

        data class shellOutput(val action: PresentationAction<ShellOutputFeature.Action>) : Action {
            companion object : Case<Action, PresentationAction<ShellOutputFeature.Action>>({ shellOutput(it) }, { (it as? shellOutput)?.action })
        }

        data class backgroundHintElapsed(val key: String) : Action

        data object moveToBackgroundTapped : Action

        data class moveToBackgroundFinished(val sessionID: String, val key: String, val error: OpenCodeClientError?) : Action

        data class attachmentAdded(val attachment: OpenCodeOutgoingAttachment) : Action

        data class attachmentRemoved(val index: Int) : Action

        /// Try Again on a failed send: its draft is already back in the composer.
        data class retrySendTapped(val failureID: String) : Action

        data class filesStaged(val files: List<ComposerStagedFile>) : Action

        data class fileUploadProgress(val id: UUID, val sent: Long) : Action

        data class fileUploadFinished(val id: UUID, val result: Result<String>) : Action

        data class fileRetryTapped(val id: UUID) : Action

        data class fileRemoved(val id: UUID) : Action

        /// Starts uploads for shared files absorbed into the composer.
        data object queuedUploadsStarted : Action

        data class pendingRequestsResponse(val sessionID: String, val result: Result<OpenCodePendingRequests>) : Action

        data class requestsConnectionChanged(val isAvailable: Boolean) : Action

        data class pendingInputsResponse(val sessionID: String, val inputs: List<OpenCodePendingInput>) : Action

        data class pendingInputsRequested(val sessionID: String) : Action

        /// A live `session.input.admitted`/`.promoted` for this session.
        data object inputQueueChanged : Action

        data class pendingInputSendNowTapped(val id: String) : Action

        data class pendingInputCancelTapped(val id: String) : Action

        data class pendingInputUpdateFailed(
            val sessionID: String,
            val inputID: String,
            val cancelled: Boolean,
            val error: OpenCodeClientError,
        ) : Action

        data object inputEnqueued : Action

        data class inputCancelled(val id: String) : Action

        /// A live permission/question/form event; the request list is stale.
        data object requestsChanged : Action

        data class permissionReplyTapped(val id: String, val reply: OpenCodePermissionReply) : Action

        data class requestResponseFinished(val sessionID: String, val requestID: String, val error: OpenCodeClientError?) : Action

        data object reviewRequestsTapped : Action

        data class reviewRequestSelected(val id: String) : Action

        data object refreshRequestsTapped : Action

        data object requestsAutoRetry : Action

        data class questionPageChanged(val requestID: String, val index: Int) : Action

        data class formValueChanged(val formID: String, val key: String, val value: OpenCodeFormValue?) : Action

        data class formSubmitTapped(val id: String) : Action

        data class formCancelTapped(val id: String) : Action

        data object sessionInfoTapped : Action

        /// A header-menu item: the Session sheet's action without opening it.
        data class sessionMenu(val action: SessionSheetFeature.Action) : Action

        data class sessionSheet(val action: SessionSheetFeature.Action) : Action {
            companion object : Case<Action, SessionSheetFeature.Action>({ sessionSheet(it) }, { (it as? sessionSheet)?.action })
        }

        data class delegate(val action: SessionFeature.Delegate) : Action
    }

    /// List-level changes Home owns.
    /// Swift nests this in `Action`. It lives here because `delegate` and `Delegate` are one file on a case-insensitive disk.
    sealed interface Delegate {
        data class openSession(val session: OpenCodeSession) : Delegate

        data class togglePin(val sessionID: String) : Delegate

        data class toggleRead(val sessionID: String) : Delegate

        data class deleteSession(val sessionID: String) : Delegate
    }

    sealed interface RefreshReason {
        data object open : RefreshReason

        data object resume : RefreshReason

        data object reconnect : RefreshReason

        data object runState : RefreshReason

        data object invalidation : RefreshReason

        data class settlement(override val messageID: String) : RefreshReason

        data object watchdog : RefreshReason

        data object snapshot : RefreshReason

        val label: String
            get() = when (this) {
                open -> "open"
                resume -> "resume"
                reconnect -> "reconnect"
                runState -> "runState"
                invalidation -> "invalidation"
                is settlement -> "settlement"
                watchdog -> "watchdog"
                snapshot -> "snapshot"
            }

        val messageID: String?
            get() = (this as? settlement)?.messageID

        val requiresFollowup: Boolean
            get() = when (this) {
                invalidation, is settlement, runState -> true
                else -> false
            }
    }

    data class AuthoritativeRefresh(
        val observed: OpenCodeSession,
        val items: List<OpenCodeTranscriptItem>,
        val scope: Scope,
        val context: OpenCodeContextReading = OpenCodeContextReading.inconclusive,
    ) {
        sealed interface Scope {
            data object page : Scope

            data class message(val messageID: String) : Scope
        }
    }

    sealed interface DeliveryOutcome {
        data class sent(val sessionID: String, val created: OpenCodeSession?) : DeliveryOutcome

        data class failed(val sessionID: String?, val created: OpenCodeSession?, val error: OpenCodeClientError) : DeliveryOutcome
    }

    /// A send whose connection failed: the server may still have it. If the
    /// message turns up, its notice and the draft restored for a retry go.
    data class UnconfirmedSendFailure(
        val infoID: String,
        val text: String,
        val attachmentIDs: Set<String>,
        val fileIDs: Set<UUID>,
    )

    data class PendingPrompt(
        val messageID: String,
        val text: String,
        val attachments: List<OpenCodeOutgoingAttachment>,
        val files: List<ComposerFileAttachment> = emptyList(),
        val optimisticIDs: Set<String>,
        val wasRunning: Boolean = false,
        val delivery: OpenCodeDelivery = OpenCodeDelivery.steer,
        val requestEpoch: Int,
    )

    private sealed interface SendTarget {
        data class existing(val sessionID: String) : SendTarget

        data class newSession(val directory: String, val model: OpenCodeModelRef?) : SendTarget
    }

    private val openCodeClient by Dependency(DependencyValues::openCodeClient)
    private val clock by Dependency(DependencyValues::continuousClock)
    private val uuid by Dependency(DependencyValues::uuid)
    private val undeliveredPrompts by Dependency(DependencyValues::undeliveredPrompts)
    private val haptics by Dependency(DependencyValues::haptics)
    private val dictation by Dependency(DependencyValues::dictation)
    private val date by Dependency(DependencyValues::date)
    private val wallClock by Dependency(DependencyValues::wallClock)

    private sealed interface CancelID {
        data object requests : CancelID

        data object inbox : CancelID

        data object inboxFetch : CancelID

        data object promptEcho : CancelID

        data object requestsRetry : CancelID

        data object requestsChanged : CancelID

        data object freshness : CancelID

        data object watchdog : CancelID

        data object earlierHistory : CancelID

        data object familyRefresh : CancelID

        data object shells : CancelID

        data object interrupt : CancelID

        data object backgroundHint : CancelID

        data object moveToBackground : CancelID

        data object dictation : CancelID

        data object viewReceipt : CancelID

        data object resyncDelay : CancelID

        data object resyncMinimum : CancelID

        data object voiceExit : CancelID

        data class upload(val id: UUID) : CancelID
    }

    /// Local items need IDs that survive server refreshes replacing `items`;
    /// count-based IDs would collide after a refresh resets the count.
    private fun appendInfo(state: State, text: String) {
        state.items += OpenCodeTranscriptItem.info(id = "info-${uuid().toString().uppercase()}", text = text)
    }

    override val body: Reducer<State, Action> =
        combine(
            BindingReducer(),
            Reduce<State, Action> { action ->
                when (action) {
                    Action.voiceTapped -> {
                        if (state.voice != null) {
                            return@Reduce Effect.send(Action.voice(PresentationAction.presented(VoiceFeature.Action.endTapped)))
                        }
                        val session = state.session
                        if (session != null) {
                            val stop = stopDictation(state)
                            state.voice = VoiceFeature.State(server = state.server, session = session)
                            return@Reduce stop
                        }
                        // On a draft the call starts without a session; its first request
                        // creates one in the draft's project, like a first send.
                        val project = state.draftProject
                        if (project == null) {
                            state.activePicker = State.ActivePicker.project
                            return@Reduce Effect.none
                        }
                        val stop = stopDictation(state)
                        state.voice = VoiceFeature.State(server = state.server, draftDirectory = project.directory, model = state.currentModel)
                        stop
                    }

                    is Action.voice if action.voiceDelegate is VoiceFeature.Delegate.sessionCreated -> {
                        // The call's first request on this draft created its session.
                        if (state.session != null) return@Reduce Effect.none
                        Effect.send(Action.sessionCreated((action.voiceDelegate as VoiceFeature.Delegate.sessionCreated).session))
                    }

                    is Action.voice if action.voiceDelegate == VoiceFeature.Delegate.ended -> {
                        // A call hung up from its bar stays while the scope sweeps out, then
                        // the composer returns; a dismissed failure was never showing the bar.
                        if (state.voice?.phase != VoiceFeature.State.Phase.ended) {
                            state.voice = null
                            return@Reduce Effect.none
                        }
                        Effect.run { send ->
                            clock.sleep(DotScopeSimulation.exitDuration.seconds)
                            send(Action.voiceExitFinished)
                        }
                            .cancellable(CancelID.voiceExit, cancelInFlight = true)
                    }

                    is Action.voice if action.action == PresentationAction.presented(VoiceFeature.Action.start) -> {
                        // Try Again on a failed call: like starting one, dictation stops first.
                        Effect.merge(Effect.cancel(CancelID.voiceExit), stopDictation(state))
                    }

                    Action.voiceExitFinished -> {
                        if (state.voice?.phase == VoiceFeature.State.Phase.ended) state.voice = null
                        Effect.none
                    }

                    is Action.voice -> Effect.none

                    is Action.binding if action.binding.keyPath == State::draft -> {
                        // A keyboard edit mid-dictation wins: stop transcribing rather than
                        // letting the next volatile result clobber what the user typed.
                        stopDictation(state)
                    }

                    is Action.binding -> Effect.none

                    is Action.eventStreamConnected -> {
                        state.server = action.server
                        state.streamIsHealthy = true
                        val retune = retunePendingWork(state)
                        state.streamSupportsProjection = false
                        state.initialTranscriptIsFresh = false
                        // A new socket epoch invalidates delta accumulators, not rendered
                        // parts: unfinished text/tools are still absent from REST. Keep their
                        // row protection until reconciliation can echo them or the run ends.
                        state.liveText = emptyMap()
                        state.lastProjectedMessageID = null
                        // Shell events are ephemeral: an exit may have been missed. The
                        // reconnect's re-list replaces every listed shell.
                        state.shells = state.shells.map { it.copy(isListed = true) }
                        // A resume read that beat the socket handshake is already the REST
                        // hydration for this epoch. Do not cancel it and start the same bytes
                        // again; subsequent events are buffered against that in-flight read.
                        if (state.refresh != null) return@Reduce Effect.merge(retune, watchdog(state))
                        Effect.merge(Effect.send(Action.refreshRequested(RefreshReason.reconnect)), retune, watchdog(state))
                    }

                    is Action.contentBatch -> {
                        val sessionID = action.sessionID
                        val events = action.events
                        if (state.session?.id != sessionID) return@Reduce Effect.none
                        state.isExternallyActive = true
                        state.streamIsHealthy = true
                        state.streamSupportsProjection = true
                        val retune = retunePendingWork(state)
                        // Streaming text survives a refresh through `liveText`; replaying its
                        // deltas afterwards would append them a second time.
                        state.refresh = state.refresh?.let { refresh ->
                            refresh.copy(racedEvents = refresh.racedEvents + events.filter { it !is OpenCodeLiveContentEvent.textDelta })
                        }
                        val count = state.refresh?.racedEvents?.size
                        if (count != null && count > 512) {
                            state.refresh = state.refresh?.let { it.copy(racedEvents = it.racedEvents.drop(count - 512)) }
                        }
                        for (event in events) {
                            if (event.projectsRows) {
                                state.projectedMessageIDs += event.messageID
                                state.lastProjectedMessageID = event.messageID
                            }
                            if (event is OpenCodeLiveContentEvent.stepEnded) state.contextNeedsRead = true
                            applyLiveEvent(event, state)
                        }
                        LabMarks.mark("stream_delta_batch", sessionID = sessionID)
                        // A flush can contain text.ended followed by step.ended for a
                        // different bookkeeping message. Prefer the durable boundary that
                        // actually projected a row; that is the message REST must settle.
                        val settledEvent = events.lastOrNull { it.isDurableBoundary && it.projectsRows }
                            ?: events.lastOrNull { it.isDurableBoundary }
                        val settledMessageID = if (settledEvent != null) {
                            if (settledEvent.projectsRows) settledEvent.messageID else (state.lastProjectedMessageID ?: settledEvent.messageID)
                        } else {
                            null
                        }
                        val settles = settledMessageID != null
                        if (settles) LabMarks.mark("stream_settled", sessionID = sessionID)
                        Effect.merge(
                            settledMessageID?.let { Effect.send(Action.refreshRequested(RefreshReason.settlement(messageID = it))) } ?: Effect.none,
                            retune,
                            watchdog(state),
                        )
                    }

                    is Action.messageInvalidated -> {
                        if (state.session?.id != action.sessionID) return@Reduce Effect.none
                        Effect.send(Action.refreshRequested(RefreshReason.invalidation))
                    }

                    is Action.refreshRequested -> {
                        val reason = action.reason
                        val session = state.session ?: return@Reduce Effect.none
                        val refresh = state.refresh
                        if (refresh != null) {
                            // A terminal page read must not be downgraded by a later part or
                            // inbox invalidation while this older request is still in flight.
                            if (refresh.followup == RefreshReason.runState) return@Reduce Effect.none
                            // Lifecycle duplicates coalesce into the read already in flight.
                            // Only a content/run invalidation proves that read may have raced a
                            // newer server mutation and therefore earns one follow-up.
                            if (reason is RefreshReason.settlement && refresh.reason == RefreshReason.settlement(messageID = reason.messageID)) {
                                // Text/tool/step boundaries for one assistant message commonly
                                // arrive together. The in-flight read has a 200 ms coalescing
                                // delay and will observe all of them.
                            } else if (refresh.reason is RefreshReason.settlement) {
                                // A later request for a complete window must run after the
                                // targeted read; neither scope may overwrite a newer read.
                                state.refresh = state.refresh?.copy(followup = reason)
                            } else if (reason.requiresFollowup) {
                                state.refresh = state.refresh?.copy(followup = reason)
                            }
                            return@Reduce Effect.none
                        }
                        state.nextRefreshID += 1
                        val requestID = state.nextRefreshID
                        state.refresh = State.Refresh(id = requestID, reason = reason)
                        LabMarks.mark("refresh_start(${reason.label})", sessionID = session.id)
                        val cue = if (state.isVisible &&
                            listOf(RefreshReason.open, RefreshReason.resume, RefreshReason.reconnect).contains(reason) &&
                            !state.initialTranscriptIsFresh
                        ) {
                            beginResync(state, refreshID = requestID)
                        } else {
                            Effect.none
                        }
                        Effect.merge(
                            cue,
                            authoritativeRefresh(
                                server = state.server,
                                session = session,
                                requestID = requestID,
                                reason = reason,
                                messageIsInWindow = reason.messageID?.let {
                                    containsMessage(it, `in` = state.items)
                                } ?: false,
                                readsContext = state.contextNeedsRead,
                            ),
                        )
                    }

                    is Action.refreshResponse -> {
                        val sessionID = action.sessionID
                        val requestID = action.requestID
                        val inFlight = state.refresh
                        if (state.session?.id != sessionID || inFlight == null || inFlight.id != requestID) {
                            LabMarks.mark("refresh_rejected_stale", sessionID = sessionID)
                            return@Reduce Effect.none
                        }
                        state.refresh = null
                        val followup: Effect<Action> = inFlight.followup?.let { Effect.send(Action.refreshRequested(it)) } ?: Effect.none
                        LabMarks.mark("refresh_end(${inFlight.reason.label})", sessionID = sessionID)
                        val settleCue = settleResync(state, refreshID = requestID)
                        val refresh = action.result.getOrElse { error ->
                            error as OpenCodeClientError
                            state.isLoading = false
                            state.refreshFailed = state.items.isNotEmpty()
                            if (state.items.isEmpty()) state.errorMessage = error.message
                            return@Reduce Effect.merge(settleCue, followup)
                        }
                        if (!state.isExternallyActive) {
                            // Any read can be the first to echo a prompt delivered after idle.
                            // Retire its wait before deciding whether projection can settle.
                            reconcilePromptEchoes(refresh.items, state)
                            if (!state.hasPendingLocalPrompt) state.isAwaitingReply = false
                        }
                        val preserveProjection = state.isRunning || inFlight.followup == RefreshReason.runState
                        if (!preserveProjection) state.resetLiveProjection()
                        val previousIDs = state.items.map { it.id }.toSet()
                        when (val scope = refresh.scope) {
                            AuthoritativeRefresh.Scope.page ->
                                applyAuthoritative(refresh.items, state, preserveProjection = preserveProjection)
                            is AuthoritativeRefresh.Scope.message ->
                                applyAuthoritativeMessage(refresh.items, messageID = scope.messageID, state = state, preserveProjection = preserveProjection)
                        }
                        state.refreshFailed = false
                        // A carried row already contains the events received during this
                        // read. Replay only onto replaced/missing parts; otherwise a tool's
                        // input deltas append twice (or input.started resets its payload).
                        val retainedIDs = previousIDs.intersect(state.items.map { it.id }.toSet()) - refresh.items.map { it.id }.toSet()
                        for (event in inFlight.racedEvents) {
                            if (!preserveProjection || event.transcriptItemID?.let(retainedIDs::contains) == true) continue
                            applyLiveEvent(event, state)
                        }
                        when (val context = refresh.context) {
                            is OpenCodeContextReading.measured -> {
                                state.contextUsage = context.usage
                                state.contextNeedsRead = false
                            }
                            OpenCodeContextReading.empty -> {
                                state.contextUsage = null
                                state.contextNeedsRead = false
                            }
                            OpenCodeContextReading.inconclusive -> Unit
                        }
                        state.updateObservedSession(refresh.observed)
                        Effect.merge(
                            settleCue,
                            Effect.send(Action.acceptedTranscriptUpdated(sessionID = sessionID, items = state.items)),
                            acknowledgeViewed(refresh.observed, state),
                            followup,
                        )
                    }

                    is Action.visibilityChanged -> {
                        val visible = action.visible
                        if (visible == state.isVisible) return@Reduce Effect.none
                        val isForegroundResume = visible && !state.isVisible && state.hasRenderedFirstContentFrame
                        state.isVisible = visible
                        if (isForegroundResume) {
                            LabMarks.mark("session_resume", sessionID = state.session?.id)
                        }
                        if (!visible) {
                            return@Reduce Effect.merge(Effect.cancel(CancelID.viewReceipt), abandonRefresh(state))
                        }
                        if (!state.hasRenderedFirstContentFrame) return@Reduce Effect.none
                        val refresh = state.refresh
                        if (refresh != null && !state.initialTranscriptIsFresh) {
                            return@Reduce beginResync(state, refreshID = refresh.id)
                        }
                        Effect.send(Action.refreshRequested(if (isForegroundResume) RefreshReason.resume else RefreshReason.open))
                    }

                    is Action.resyncDelayElapsed -> {
                        val refreshID = action.refreshID
                        val resync = state.resync
                        if (resync == null || resync.refreshID != refreshID) return@Reduce Effect.none
                        if (resync.responseReceived) {
                            state.resync = null
                        } else {
                            state.resync = resync.copy(isShown = true)
                            return@Reduce Effect.run { send ->
                                clock.sleep(300.milliseconds)
                                send(Action.resyncMinimumElapsed(refreshID = refreshID))
                            }
                                .cancellable(CancelID.resyncMinimum, cancelInFlight = true)
                        }
                        Effect.none
                    }

                    is Action.resyncMinimumElapsed -> {
                        val resync = state.resync
                        if (resync == null || resync.refreshID != action.refreshID) return@Reduce Effect.none
                        if (resync.responseReceived) state.resync = null else state.resync = resync.copy(minimumElapsed = true)
                        Effect.none
                    }

                    is Action.acceptedTranscriptUpdated -> Effect.none

                    is Action.viewAcknowledged -> {
                        val sessionID = action.sessionID
                        val idle = action.idle
                        state.viewedIdle += (sessionID to maxOf(state.viewedIdle[sessionID] ?: 0L, idle))
                        if (state.session?.id == sessionID) {
                            val viewed = maxOf(state.session?.viewedMilliseconds ?: 0L, idle)
                            state.session = state.session?.copy(viewedMilliseconds = viewed)
                        }
                        Effect.none
                    }

                    Action.task -> {
                        val restore = restoreUndeliveredPrompt(state)
                        if (state.session == null) {
                            val loadModels = loadModelsIfNeeded(state)
                            // A live call carried into a new draft keeps this screen's identity,
                            // so the previous session's loops must stop here.
                            val stopSessionWork: Effect<Action> = if (state.voice == null) {
                                Effect.none
                            } else {
                                Effect.merge(
                                    listOf(
                                        CancelID.requests, CancelID.inbox, CancelID.inboxFetch, CancelID.promptEcho, CancelID.requestsRetry,
                                        CancelID.requestsChanged, CancelID.freshness, CancelID.watchdog, CancelID.earlierHistory,
                                        CancelID.familyRefresh, CancelID.shells, CancelID.backgroundHint, CancelID.viewReceipt,
                                        CancelID.resyncDelay, CancelID.resyncMinimum,
                                    )
                                        .map { Effect.cancel(it) },
                                )
                            }
                            return@Reduce Effect.merge(stopSessionWork, loadModels, Effect.run { _ -> dictation.prepare() }, restore)
                        }
                        state.errorMessage = null
                        if (state.initialTranscriptRequestIsExternal) return@Reduce restore
                        // Navigation activation owns freshness; cached paint does not wait for
                        // visibility or the first UIKit frame.
                        Effect.merge(Effect.send(Action.refreshRequested(RefreshReason.open)), restore)
                    }

                    Action.firstContentFrameRendered -> {
                        if (state.hasRenderedFirstContentFrame || state.session == null) return@Reduce Effect.none
                        state.hasRenderedFirstContentFrame = true
                        state.initialTranscriptRequestIsExternal = false
                        val loadModels = loadModelsIfNeeded(state)
                        // Pending work polls once the family is known (see familyResponse);
                        // record the attempt so a failure there counts as the initial one.
                        state.requestLoadAttempt = State.RequestLoadAttempt.initial
                        state.requestLoadError = null
                        Effect.merge(
                            Effect.run { _ -> dictation.prepare() },
                            refreshFamily(state),
                            refreshShells(state),
                            loadModels,
                            if (state.initialTranscriptIsFresh) Effect.none else Effect.send(Action.refreshRequested(RefreshReason.open)),
                        )
                    }

                    Action.inputQueueChanged -> {
                        // Admission adds queued work; promotion moves it into history. Both
                        // land with one refresh each — the transcript guard absorbs no-ops.
                        val session = state.session ?: return@Reduce Effect.none
                        Effect.merge(
                            fetchQueuedInputs(server = state.server, sessionID = session.id),
                            Effect.send(Action.refreshRequested(RefreshReason.invalidation)),
                        )
                    }

                    Action.inputEnqueued -> {
                        val session = state.session ?: return@Reduce Effect.none
                        fetchQueuedInputs(server = state.server, sessionID = session.id)
                    }

                    is Action.pendingInputsRequested -> {
                        if (state.session?.id != action.sessionID) return@Reduce Effect.none
                        fetchQueuedInputs(server = state.server, sessionID = action.sessionID)
                    }

                    is Action.inputCancelled -> {
                        val id = action.id
                        state.queuedInputs = state.queuedInputs.filterNot { it.id == id }
                        state.pendingPromptPositions -= id
                        state.promptStates -= id
                        state.queuedInputImages -= id
                        state.optimisticPromptIDs -= id
                        state.echoedPromptIDs -= id
                        state.cancelledInputIDs += id
                        if (!state.isExternallyActive && !state.hasPendingLocalPrompt) {
                            state.isAwaitingReply = false
                            if (!state.isRunning) state.resetLiveProjection()
                        }
                        Effect.none
                    }

                    is Action.pendingInputSendNowTapped -> {
                        val id = action.id
                        val session = state.session
                        val index = state.queuedInputs.indexOfFirst { it.id == id }
                        if (session == null || index < 0 || state.queuedInputs[index].delivery != OpenCodeDelivery.queue ||
                            state.promptStates[id]?.admission == State.PromptState.Admission.sent
                        ) {
                            return@Reduce Effect.none
                        }
                        // It leaves the pill for a dim steering row in the transcript now; the
                        // inbox refetch confirms the server's mode.
                        state.queuedInputs = state.queuedInputs.toMutableList().also { it[index] = it[index].copy(delivery = OpenCodeDelivery.steer) }
                        haptics.impact()
                        val precedingIDs = pendingPredecessors(state)
                        state.pendingPromptPositions = state.pendingPromptPositions.updating(id) { it.copy(precedingPromptIDs = it.precedingPromptIDs + precedingIDs) }
                        val precedingMessages = windowMessageIDs(`in` = state.items)
                        state.pendingPromptPositions = state.pendingPromptPositions.updating(id) { it.copy(precedingMessageIDs = it.precedingMessageIDs + precedingMessages) }
                        state.pendingPromptPositions = state.pendingPromptPositions.updating(id) { it.copy(isBeforeWindow = false) }
                        recordSteeringPredecessor(id, state)
                        val server = state.server
                        Effect.run { send ->
                            try {
                                openCodeClient.setPendingInputDelivery(server, session.id, id, OpenCodeDelivery.steer)
                            } catch (error: OpenCodeClientError) {
                                send(Action.pendingInputUpdateFailed(sessionID = session.id, inputID = id, cancelled = false, error = error))
                            }
                            send(Action.pendingInputsRequested(sessionID = session.id))
                        }
                    }

                    is Action.pendingInputCancelTapped -> {
                        val id = action.id
                        val session = state.session
                        if (session == null || !state.queuedInputs.any { it.id == id } ||
                            state.promptStates[id]?.admission == State.PromptState.Admission.sent
                        ) {
                            return@Reduce Effect.none
                        }
                        state.queuedInputs = state.queuedInputs.filterNot { it.id == id }
                        state.pendingPromptPositions -= id
                        state.queuedInputImages -= id
                        state.promptStates -= id
                        state.optimisticPromptIDs -= id
                        state.echoedPromptIDs -= id
                        state.cancelledInputIDs += id
                        if (!state.isExternallyActive && !state.hasPendingLocalPrompt) {
                            state.isAwaitingReply = false
                            if (!state.isRunning) state.resetLiveProjection()
                        }
                        val server = state.server
                        Effect.run { send ->
                            try {
                                openCodeClient.cancelPendingInput(server, session.id, id)
                            } catch (error: OpenCodeClientError) {
                                send(Action.pendingInputUpdateFailed(sessionID = session.id, inputID = id, cancelled = true, error = error))
                            }
                            send(Action.pendingInputsRequested(sessionID = session.id))
                        }
                    }

                    is Action.pendingInputUpdateFailed -> {
                        if (state.session?.id != action.sessionID) return@Reduce Effect.none
                        // Undo the optimistic change; a cancelled row returns with the next inbox read.
                        val index = state.queuedInputs.indexOfFirst { it.id == action.inputID }
                        if (action.cancelled) {
                            state.cancelledInputIDs -= action.inputID
                        } else if (index >= 0) {
                            state.queuedInputs = state.queuedInputs.toMutableList().also { it[index] = it[index].copy(delivery = OpenCodeDelivery.queue) }
                        }
                        appendInfo(
                            state,
                            if (action.cancelled) {
                                "Couldn’t cancel the pending message: ${action.error.message}"
                            } else {
                                "Couldn’t send the pending message now: ${action.error.message}"
                            },
                        )
                        Effect.none
                    }

                    Action.requestsChanged -> {
                        // The pending-work poll slows to 12 s once the stream is healthy, so
                        // requests answered elsewhere must not wait for it.
                        refreshRequests(state).cancellable(CancelID.requestsChanged, cancelInFlight = true)
                    }

                    is Action.pendingInputsResponse -> {
                        val sessionID = action.sessionID
                        if (state.session?.id != sessionID) return@Reduce Effect.none
                        settleUnconfirmedSends(action.inputs.map { it.id }.toSet(), state)
                        val delivered: Set<String> = promptIDs(`in` = state.items) - state.optimisticPromptIDs
                        val promoted: Set<String> = state.promotedPromptIDs.toSet() + delivered
                        val inputs = action.inputs.filter { !promoted.contains(it.id) && !state.cancelledInputIDs.contains(it.id) }
                        val serverIDs = inputs.map { it.id }.toSet()
                        val userInputs = inputs.filter { !it.isCompaction && it.notice == null }
                        var precedingIDs = promptIDs(`in` = state.items)
                        val precedingMessages = windowMessageIDs(`in` = state.items)
                        for (input in userInputs) {
                            precedingIDs = precedingIDs + input.id
                            if (state.pendingPromptPositions[input.id] == null) {
                                state.nextPromptRequestEpoch += 1
                                state.pendingPromptPositions += input.id to State.PendingPromptPosition(
                                    precedingPromptIDs = precedingIDs,
                                    precedingMessageIDs = precedingMessages,
                                    requestEpoch = state.promptStates[input.id]?.requestEpoch ?: state.nextPromptRequestEpoch,
                                )
                            } else {
                                // A prompt still listed in the inbox may follow any prompt that
                                // has reached the transcript since we first saw it.
                                state.pendingPromptPositions = state.pendingPromptPositions.updating(input.id) { it.copy(precedingPromptIDs = it.precedingPromptIDs + precedingIDs) }
                                state.pendingPromptPositions = state.pendingPromptPositions.updating(input.id) { it.copy(precedingMessageIDs = it.precedingMessageIDs + precedingMessages) }
                                state.pendingPromptPositions = state.pendingPromptPositions.updating(input.id) { it.copy(isBeforeWindow = false) }
                            }
                        }
                        // Inbox reads can complete out of order with prompt admission and
                        // promotion. Keep every displayed user prompt until the transcript
                        // echoes it; if it leaves inbox first, the queued→transcript
                        // presentation remains one atomic reducer update rather than a
                        // disappearance. Notices and compactions follow the inbox directly.
                        val pendingLocal = state.queuedInputs.filter {
                            !serverIDs.contains(it.id) && (state.promptStates[it.id] != null || state.pendingPromptPositions[it.id] != null)
                        }
                        val byID = inputs.associateBy { it.id }
                        val previousIDs = state.queuedInputs.map { it.id }.toSet()
                        val retainedIDs = pendingLocal.map { it.id }.toSet()
                        state.queuedInputs = state.queuedInputs.mapNotNull { byID[it.id] ?: (if (retainedIDs.contains(it.id)) it else null) } +
                            inputs.filter { !previousIDs.contains(it.id) }
                        // Only queued prompts may be overtaken by later steering prompts.
                        for (input in userInputs.filter { it.delivery == OpenCodeDelivery.steer }) {
                            recordSteeringPredecessor(input.id, state)
                        }
                        val queuedIDs = state.queuedInputs.map { it.id }.toSet()
                        state.queuedInputImages = state.queuedInputImages.filter { queuedIDs.contains(it.key) }
                        // A busy session can move past a prompt before the newest-page read
                        // ever sees it. Confirm missing admitted prompts by ID instead of
                        // keeping their dim queue rows forever. Never infer delivery from an
                        // empty inbox alone (a pre-admission poll can arrive late).
                        val missing = pendingLocal.mapNotNull { input ->
                            val prompt = state.promptStates[input.id]
                            if (prompt != null) {
                                return@mapNotNull if (prompt.admission == State.PromptState.Admission.admitted) input.id to prompt.requestEpoch else null
                            }
                            state.pendingPromptPositions[input.id]?.let { input.id to it.requestEpoch }
                        }
                        if (missing.isEmpty()) return@Reduce Effect.cancel(CancelID.promptEcho)
                        val localIDs = state.promptStates.keys
                        val server = state.server
                        Effect.run { send ->
                            for ((id, epoch) in missing.take(4)) {
                                if (!currentCoroutineContext().isActive) return@run
                                try {
                                    val items = openCodeClient.message(server, sessionID, id)
                                    if (!currentCoroutineContext().isActive) return@run
                                    send(Action.promptEchoResponse(sessionID = sessionID, messageID = id, requestEpoch = epoch, items = items))
                                } catch (error: OpenCodeClientError) {
                                    // A remote cancellation may have happened while disconnected.
                                    // Only an authoritative absence settles it, never a read failure
                                    // or a local send whose admission may still be racing this poll.
                                    if (currentCoroutineContext().isActive && !localIDs.contains(id) && error.statusCode == 404) {
                                        send(Action.remotePromptMissing(sessionID = sessionID, messageID = id, requestEpoch = epoch))
                                    }
                                }
                            }
                        }
                            .cancellable(CancelID.promptEcho, cancelInFlight = true)
                    }

                    is Action.remotePromptMissing -> {
                        val messageID = action.messageID
                        if (state.session?.id != action.sessionID || state.promptStates[messageID] != null ||
                            state.pendingPromptPositions[messageID]?.requestEpoch != action.requestEpoch
                        ) {
                            return@Reduce Effect.none
                        }
                        // A missing message can be a stale inbox read, not a cancellation.
                        // Retire this observation without poisoning later authoritative reads.
                        state.queuedInputs = state.queuedInputs.filterNot { it.id == messageID }
                        state.pendingPromptPositions -= messageID
                        state.queuedInputImages -= messageID
                        Effect.none
                    }

                    is Action.promptEchoResponse -> {
                        val messageID = action.messageID
                        val items = action.items
                        if (state.session?.id != action.sessionID ||
                            (state.promptStates[messageID]?.requestEpoch ?: state.pendingPromptPositions[messageID]?.requestEpoch) != action.requestEpoch ||
                            !promptIDs(`in` = items).contains(messageID)
                        ) {
                            return@Reduce Effect.none
                        }
                        // The per-message read can beat the newest window. Promote a prompt
                        // seen at this tail atomically rather than removing its only row.
                        // If newer user messages have passed it, it belongs to paged history.
                        val position = state.pendingPromptPositions[messageID]
                        val canAppend = position?.let {
                            !it.isBeforeWindow && it.precedingPromptIDs.containsAll(promptIDs(`in` = state.items))
                        } ?: false
                        val appendEcho = canAppend && !containsMessage(messageID, `in` = state.items)
                        if (appendEcho) state.items += items
                        applyAuthoritativeMessage(items, messageID = messageID, state = state)
                        reconcilePromptEchoes(items, state)
                        // Retain this row through a window response captured before delivery.
                        if (appendEcho) state.echoedPromptIDs += messageID
                        settleUnconfirmedSends(promptIDs(`in` = items), state)
                        if (!state.isExternallyActive && !state.hasPendingLocalPrompt) {
                            state.isAwaitingReply = false
                            if (!state.isRunning && state.refresh?.followup != RefreshReason.runState) state.resetLiveProjection()
                        }
                        Effect.none
                    }

                    is Action.externalRunStateChanged -> {
                        // Execution boundaries reconcile once and retune the loss-recovery
                        // watchdog; content between them arrives through projection batches.
                        val isRunning = action.isRunning
                        val wasRunning = state.isRunning
                        state.isExternallyActive = isRunning
                        if (state.session == null) return@Reduce Effect.none
                        if (isRunning) return@Reduce Effect.merge(Effect.send(Action.refreshRequested(RefreshReason.runState)), watchdog(state))
                        if (!state.hasPendingLocalPrompt) {
                            state.isAwaitingReply = false
                        }
                        // Protect R1 before the effect queues its terminal read: R1's response
                        // can arrive between this action and refreshRequested below.
                        val needsRefresh = wasRunning || state.refresh?.reason != RefreshReason.runState
                        if (needsRefresh) state.refresh = state.refresh?.copy(followup = RefreshReason.runState)
                        state.isInterrupting = false
                        Effect.merge(
                            if (needsRefresh) Effect.send(Action.refreshRequested(RefreshReason.runState)) else Effect.none,
                            watchdog(state),
                            Effect.cancel(CancelID.interrupt),
                        )
                    }

                    Action.sendTapped, Action.queueTapped -> {
                        if (state.isSubmittingPrompt) return@Reduce Effect.none
                        // A lingering keyboard submit must not turn an answer into chat input.
                        if (state.hasPendingRequests) return@Reduce Effect.none
                        LabMarks.mark("send_tap", sessionID = state.session?.id)
                        // Both delivery modes are admitted to the inbox. Steering promotes at
                        // the next step; an explicit queue waits until the response completes.
                        val text = state.draft.trim()
                        val attachments = state.pendingAttachments
                        val files = state.pendingFiles
                        val wasRunning = state.isRunning
                        val delivery = if (action == Action.queueTapped) OpenCodeDelivery.queue else OpenCodeDelivery.steer
                        if (!state.hasPromptContent) return@Reduce Effect.none
                        if (state.session == null && state.draftProject == null) {
                            state.activePicker = State.ActivePicker.project
                            return@Reduce Effect.none
                        }
                        if (files.any { it.isFailed }) {
                            state.sendAfterUploads = null
                            haptics.error()
                            return@Reduce Effect.none
                        }
                        if (files.any { it.isUploading }) {
                            // The message goes as soon as the last upload lands.
                            haptics.impact()
                            state.sendAfterUploads = delivery
                            return@Reduce Effect.none
                        }
                        state.sendAfterUploads = null
                        haptics.impact()
                        val stopDictating = stopDictation(state)
                        state.dictationError = null
                        state.draftState = State.DraftState()
                        state.isAwaitingReply = true
                        val messageID = "msg_" + uuid().toString().uppercase().replace("-", "")
                        state.nextPromptRequestEpoch += 1
                        val requestEpoch = state.nextPromptRequestEpoch
                        state.promptStates += (messageID to State.PromptState(requestEpoch = requestEpoch))
                        state.optimisticPromptIDs += messageID
                        val optimisticIDs = mutableSetOf<String>()
                        var lastOptimisticID: String? = null
                        val images = attachments.filter { it.isImage }.map { attachment ->
                            OpenCodeImageAttachment(id = "local-img-${uuid().toString().uppercase()}", data = attachment.data, mime = attachment.mime)
                        }
                        val references = files.mapNotNull { it.reference }
                        val transcriptFiles = references.mapIndexed { index, file ->
                            OpenCodeTranscriptFile(id = "$messageID-file-$index", name = file.name, mime = file.mime, path = file.path)
                        } + attachments.filter { !it.isImage }.mapIndexed { index, attachment ->
                            OpenCodeTranscriptFile(id = "$messageID-file-inline-$index", name = attachment.name ?: "File", mime = attachment.mime)
                        }
                        if (wasRunning) {
                            // Keep mid-run messages in the pending pill until delivery, with
                            // their chosen mode and images, instead of a speculative user bubble.
                            optimisticIDs += messageID
                            state.queuedInputs += OpenCodePendingInput(id = messageID, text = text, delivery = delivery, files = references)
                            state.pendingPromptPositions += messageID to State.PendingPromptPosition(
                                precedingPromptIDs = pendingPredecessors(state),
                                precedingMessageIDs = windowMessageIDs(`in` = state.items),
                                requestEpoch = requestEpoch,
                            )
                            if (delivery == OpenCodeDelivery.steer) recordSteeringPredecessor(messageID, state)
                            if (images.isNotEmpty()) state.queuedInputImages += (messageID to images)
                        } else {
                            if (images.isNotEmpty()) {
                                val id = "$messageID-images"
                                optimisticIDs += id
                                state.items += OpenCodeTranscriptItem.userImages(id = id, images = images)
                                lastOptimisticID = id
                            }
                            if (transcriptFiles.isNotEmpty()) {
                                val id = "$messageID-files"
                                optimisticIDs += id
                                state.items += OpenCodeTranscriptItem.userFiles(id = id, files = transcriptFiles)
                                lastOptimisticID = id
                            }
                            if (text.isNotEmpty()) {
                                val textID = messageID
                                optimisticIDs += textID
                                state.items += OpenCodeTranscriptItem.userText(id = textID, text = text)
                                lastOptimisticID = textID
                            }
                        }
                        if (lastOptimisticID != null) {
                            state.lastSentItemID = lastOptimisticID
                            state.sendGeneration += 1
                        }
                        val pending = PendingPrompt(
                            messageID = messageID,
                            text = text,
                            attachments = attachments,
                            files = files,
                            optimisticIDs = optimisticIDs.toSet(),
                            wasRunning = wasRunning,
                            delivery = delivery,
                            requestEpoch = requestEpoch,
                        )

                        val session = state.session
                        if (session != null) {
                            return@Reduce Effect.merge(
                                stopDictating,
                                deliver(pending = pending, to = SendTarget.existing(sessionID = session.id), server = state.server),
                            )
                        }
                        val project = state.draftProject ?: return@Reduce stopDictating
                        Effect.merge(
                            stopDictating,
                            deliver(
                                pending = pending,
                                to = SendTarget.newSession(directory = project.directory, model = state.currentModel),
                                server = state.server,
                            ),
                        )
                    }

                    is Action.retrySendTapped -> {
                        state.items = state.items.filterNot { it.id == action.failureID }
                        if (!state.hasPromptContent) return@Reduce Effect.none
                        Effect.send(Action.sendTapped)
                    }

                    Action.returnKeyTapped -> {
                        // A vertical-axis TextField inserts "\n" instead of firing onSubmit;
                        // the view detects that single appended newline and routes it here.
                        if (!state.draft.endsWith("\n")) return@Reduce Effect.none
                        state.draft = state.draft.dropLast(1)
                        if (!state.hasPromptContent) return@Reduce Effect.none
                        Effect.send(Action.sendTapped)
                    }

                    Action.micTapped -> {
                        haptics.impact()
                        if (state.isDictating) {
                            return@Reduce finishDictation(state, sendWhenComplete = false)
                        }
                        // A tap while the engine is still finalizing is almost always a
                        // double-tap on stop; restarting would race the pending teardown.
                        if (state.isFinishingDictation) return@Reduce Effect.none
                        startDictation(state)
                    }

                    Action.micPressBegan -> {
                        if (state.isDictating || state.isFinishingDictation) return@Reduce Effect.none
                        haptics.impact()
                        val start = startDictation(state)
                        state.dictationHold = State.DictationHold(text = state.draft, startedAt = date.now)
                        start
                    }

                    Action.micPressReleased -> {
                        val hold = state.dictationHold
                        if (hold == null || hold.isLocked) return@Reduce Effect.none
                        if (state.isPreparingDictation) {
                            // Let go before the mic was ready: the permission prompt took the
                            // touch, or setup was slow. Stopping now would abort setup, so
                            // recording goes on hands-free, as after a tap.
                            state.dictationHold = state.dictationHold?.copy(isLocked = true)
                            return@Reduce Effect.none
                        }
                        if (state.dictationPhase != State.DictationPhase.recording) return@Reduce Effect.none
                        haptics.impact()
                        finishDictation(state, sendWhenComplete = true)
                    }

                    Action.micPressLocked -> {
                        val hold = state.dictationHold
                        if (hold == null || hold.isLocked || !state.isDictating) return@Reduce Effect.none
                        state.dictationHold = state.dictationHold?.copy(isLocked = true)
                        haptics.selection()
                        Effect.none
                    }

                    Action.micPressCancelled -> {
                        if (state.dictationHold == null || !(state.isDictating || state.isFinishingDictation)) return@Reduce Effect.none
                        state.dictationHold = null
                        haptics.selection()
                        // The draft was never touched, so stopping is the whole cancel.
                        stopDictation(state)
                    }

                    Action.dictationStopTapped -> {
                        if (!state.isDictating) return@Reduce Effect.none
                        haptics.impact()
                        finishDictation(state, sendWhenComplete = false)
                    }

                    Action.sceneEnteredBackground -> {
                        // With background audio, iOS posts no interruption when the app
                        // leaves, so the mic (and its orange dot) would stay on. Leaving is
                        // Stop: the words stay in the draft to review, never sent. Only on
                        // background: the permission prompt and Control Center just make
                        // the scene inactive.
                        if (!state.isDictating) return@Reduce Effect.none
                        finishDictation(state, sendWhenComplete = false)
                    }

                    Action.dictationSendTapped -> {
                        if (!state.isDictating) return@Reduce Effect.none
                        finishDictation(state, sendWhenComplete = true)
                    }

                    Action.dictationReady -> {
                        if (state.dictationPhase != State.DictationPhase.preparing) return@Reduce Effect.none
                        state.dictationPhase = State.DictationPhase.recording
                        Effect.none
                    }

                    is Action.dictationUpdated -> {
                        if (!(state.isDictating || state.isFinishingDictation)) return@Reduce Effect.none
                        if (state.dictationHold != null) {
                            state.dictationHold = state.dictationHold?.copy(text = action.text)
                        } else {
                            state.draft = action.text
                        }
                        Effect.none
                    }

                    is Action.dictationStopped -> {
                        val errorMessage = action.errorMessage
                        if (!(state.isDictating || state.isFinishingDictation)) return@Reduce Effect.none
                        val shouldSend = (state.dictationPhase as? State.DictationPhase.finishing)?.sendWhenComplete ?: false
                        state.dictationError = errorMessage
                        if (errorMessage != null) haptics.error()
                        // A held mic's words reach the draft only now: sent on success, kept
                        // for editing if the engine failed.
                        val hold = state.dictationHold
                        if (hold != null) {
                            state.dictationHold = null
                            state.draft = hold.text
                        }
                        val stop = stopDictation(state)
                        val hasPrompt = state.hasPromptContent
                        if (shouldSend && errorMessage == null && hasPrompt) Effect.merge(stop, Effect.send(Action.sendTapped)) else stop
                    }

                    Action.interruptTapped -> {
                        val session = state.session
                        if (session == null || !state.isRunning || state.isInterrupting || state.isSubmittingPrompt) return@Reduce Effect.none
                        state.isInterrupting = true
                        haptics.impact()
                        val server = state.server
                        Effect.run { send ->
                            try {
                                openCodeClient.interrupt(server, session.id)
                                send(Action.interruptFinished(sessionID = session.id, error = null))
                            } catch (error: OpenCodeClientError) {
                                send(Action.interruptFinished(sessionID = session.id, error = error))
                            }
                        }
                            .cancellable(CancelID.interrupt, cancelInFlight = true)
                    }

                    is Action.interruptFinished -> {
                        if (state.session?.id != action.sessionID) return@Reduce Effect.none
                        val error = action.error
                        if (error != null) {
                            state.isInterrupting = false
                            haptics.error()
                            appendInfo(state, "Couldn’t interrupt: ${error.message}")
                        }
                        Effect.none
                    }

                    is Action.sessionCreated -> {
                        val session = action.session
                        state.session = session
                        state.familySessions = listOf(session)
                        state.familyRootID = session.id
                        startPendingWork(state, attempt = State.RequestLoadAttempt.initial)
                    }

                    is Action.promptFailed -> {
                        val sessionID = action.sessionID
                        val error = action.error
                        val pending = action.pending
                        if (sessionID != null && state.session?.id != sessionID) return@Reduce Effect.none
                        // This screen shows the failure, so the kept copy is no longer needed.
                        undeliveredPrompts.discard(messageID = pending.messageID)
                        state.promptStates -= pending.messageID
                        state.pendingPromptPositions -= pending.messageID
                        state.isAwaitingReply = false
                        state.items = state.items.filterNot { pending.optimisticIDs.contains(it.id) }
                        state.queuedInputs = state.queuedInputs.filterNot { pending.optimisticIDs.contains(it.id) }
                        state.optimisticPromptIDs -= pending.messageID
                        state.queuedInputImages -= pending.messageID
                        if (state.lastSentItemID?.let(pending.optimisticIDs::contains) == true) {
                            state.lastSentItemID = null
                        }
                        state.draft = SharedComposerDraft.joined(pending.text, state.draft)
                        state.pendingAttachments = pending.attachments + state.pendingAttachments
                        state.pendingFiles = pending.files + state.pendingFiles
                        val infoID = "info-send-${pending.messageID}"
                        state.items = state.items.filterNot { it.id == infoID }
                        state.items += OpenCodeTranscriptItem.sendFailure(id = infoID, message = error.message)
                        haptics.error()
                        // No HTTP status means the connection failed, not the server: the
                        // message may still arrive, and then this failure is settled.
                        if (error.statusCode == null) {
                            state.unconfirmedSendFailures += pending.messageID to UnconfirmedSendFailure(
                                infoID = infoID,
                                text = pending.text,
                                attachmentIDs = pending.attachments.map { it.id }.toSet(),
                                fileIDs = pending.files.map { it.id }.toSet(),
                            )
                        }
                        Effect.none
                    }

                    is Action.promptAdmitted -> {
                        val sessionID = action.sessionID
                        val messageID = action.messageID
                        if (state.session?.id != sessionID) return@Reduce Effect.none
                        if (state.promptStates[messageID]?.requestEpoch != action.requestEpoch) return@Reduce Effect.none
                        LabMarks.mark("prompt_admitted", sessionID = sessionID)
                        state.promptStates = state.promptStates.updating(messageID) { it.copy(admission = State.PromptState.Admission.admitted) }
                        Effect.none
                    }

                    is Action.replyCompleted -> {
                        if (state.session?.id != action.sessionID || !state.isRunning) return@Reduce Effect.none
                        // A successful idle wait is the same boundary as the SSE execution
                        // ending. Serialize its final read behind any older refresh too.
                        Effect.send(Action.externalRunStateChanged(isRunning = false))
                    }

                    Action.modelButtonTapped -> {
                        state.activePicker = State.ActivePicker.model
                        state.modelFilter = ""
                        loadModelsIfNeeded(state)
                    }

                    is Action.modelsResponse -> {
                        state.isLoadingModels = false
                        val catalog = action.result.getOrNull() ?: return@Reduce Effect.none
                        state.models = catalog.models
                        if (state.currentModel == null) {
                            state.currentModel = catalog.defaultModel?.ref
                        }
                        Effect.none
                    }

                    is Action.modelPicked -> {
                        val model = action.model
                        val previous = state.currentModel
                        state.currentModel = model.ref
                        state.activePicker = null
                        // Drafts remember the choice and apply it right after creation.
                        val session = state.session ?: return@Reduce Effect.none
                        val server = state.server
                        Effect.run { send ->
                            try {
                                openCodeClient.switchModel(server, session.id, model.ref)
                            } catch (error: OpenCodeClientError) {
                                send(Action.switchModelFailed(sessionID = session.id, error = error, revertTo = previous))
                            }
                        }
                    }

                    is Action.toggleFavoriteModel -> {
                        val model = action.model
                        state::favoriteModelIDs.withLock {
                            if (value.contains(model.id)) {
                                value -= model.id
                            } else {
                                value += model.id
                            }
                        }
                        Effect.none
                    }

                    is Action.switchModelFailed -> {
                        if (state.session?.id != action.sessionID) return@Reduce Effect.none
                        state.currentModel = action.revertTo
                        haptics.error()
                        appendInfo(state, "Couldn’t switch model: ${action.error.message}")
                        Effect.none
                    }

                    Action.projectButtonTapped -> {
                        if (!state.isDraft) return@Reduce Effect.none
                        state.activePicker = State.ActivePicker.project
                        state.projectFilter = ""
                        Effect.none
                    }

                    is Action.projectPicked -> {
                        val project = action.project
                        state.draftProject = project
                        state.activePicker = null
                        state.voice?.moveDraft(to = project.directory)
                        Effect.none
                    }

                    is Action.familyPicked -> {
                        if (state.isSubmittingPrompt) return@Reduce Effect.none
                        val current = state.session
                        val selected = state.familySessions.firstOrNull { it.id == action.id }
                        if (current == null || selected == null || selected.id == current.id) {
                            state.activePicker = null
                            return@Reduce Effect.none
                        }
                        // Read the target first: caching the outgoing transcript may evict it.
                        val selectedItems = state.familyTranscriptCache[selected.id]
                        cacheFamilyTranscript(state.items, sessionID = current.id, state = state)
                        state.familyDrafts += current.id to state.draftState
                        state.sendAfterUploads = null
                        // The sheet acts on one session: its in-flight work must not land on
                        // the next one. The server still finishes it and announces the result.
                        // A call that already ended doesn't follow the screen to another session.
                        val endedCall: Effect<Action> = if (state.voice?.phase?.isTerminal == true) Effect.cancel(CancelID.voiceExit) else Effect.none
                        if (state.voice?.phase?.isTerminal == true) state.voice = null
                        state.sessionSheet = null
                        state.session = selected
                        val idle = selected.idleMilliseconds
                        if (state.readsOnView && idle != null) {
                            state.session = state.session?.copy(viewedMilliseconds = maxOf(selected.viewedMilliseconds ?: 0L, idle))
                        }
                        state.items = selectedItems ?: emptyList()
                        state.isLoading = selectedItems == null
                        state.currentModel = selected.model
                        state.contextUsage = null
                        state.contextNeedsRead = false
                        state.isExternallyActive = state.activeFamilySessionIDs.contains(selected.id)
                        state.isAwaitingReply = false
                        state.isInterrupting = false
                        state.draftState = state.familyDrafts[selected.id] ?: State.DraftState()
                        state.queuedInputs = emptyList()
                        state.pendingPromptPositions = emptyMap()
                        state.echoedPromptIDs = emptySet()
                        state.queuedInputImages = emptyMap()
                        state.cancelledInputIDs = emptySet()
                        state.unconfirmedSendFailures = emptyMap()
                        state.shells = emptyList()
                        state.endedShellIDs = emptySet()
                        state.shellOutput = null
                        state.movableWorkKey = null
                        state.movedWorkKeys = emptySet()
                        state.errorMessage = null
                        state.activePicker = null
                        state.visibleLimit = 20
                        state.browsingWindowStartID = null
                        state.earlierHistory = State.EarlierHistory.unknownEdge
                        state.earlierHistoryInsertionID = null
                        state.earlierHistoryError = null
                        state.earlierHistoryRetriesOnReconnect = false
                        state.historyGeneration += 1
                        state.earlierHistoryGeneration += 1
                        state.refreshFailed = false
                        state.resetLiveProjection()
                        state.lastSentItemID = null
                        val abandon = abandonRefresh(state)
                        val requests = startPendingWork(state, attempt = State.RequestLoadAttempt.initial)
                        Effect.merge(
                            Effect.cancel(CancelID.earlierHistory),
                            Effect.cancel(CancelID.promptEcho),
                            Effect.cancel(CancelID.inboxFetch),
                            Effect.cancel(SessionSheetFeature.CancelID.autoRenameFailure),
                            endedCall,
                            abandon,
                            Effect.send(Action.refreshRequested(RefreshReason.open)),
                            requests,
                            refreshShells(state),
                            watchdog(state),
                        )
                    }

                    Action.familyRefreshRequested -> Effect.merge(refreshFamily(state), refreshShells(state))

                    is Action.shellsResponse -> {
                        val sessionID = action.sessionID
                        val listed = action.result.getOrNull() ?: return@Reduce Effect.none
                        if (state.session?.id != sessionID) return@Reduce Effect.none
                        val live = listed.filter { it.sessionID == sessionID && !state.endedShellIDs.contains(it.id) }
                        val listedIDs = live.map { it.id }.toSet()
                        // A shell can start while the list is in flight; keep live arrivals.
                        state.shells = live + state.shells.filter { !it.isListed && !listedIDs.contains(it.id) }
                        Effect.none
                    }

                    is Action.shellStarted -> {
                        val shell = action.shell
                        if (shell.sessionID != state.session?.id || state.shells.any { it.id == shell.id }) return@Reduce Effect.none
                        state.shells += shell
                        Effect.none
                    }

                    is Action.shellEnded -> {
                        state.shells = state.shells.filterNot { it.id == action.shellID }
                        state.endedShellIDs += action.shellID
                        Effect.none
                    }

                    Action.sessionInfoTapped -> {
                        if (!state.ensureSessionSheet()) return@Reduce Effect.none
                        state.activePicker = State.ActivePicker.session
                        Effect.none
                    }

                    is Action.sessionMenu -> {
                        if (!state.ensureSessionSheet()) return@Reduce Effect.none
                        Effect.send(Action.sessionSheet(action.action))
                    }

                    is Action.sessionSheet if action.action is SessionSheetFeature.Action.delegate -> {
                        val sessionID = state.sessionSheet?.sessionID ?: return@Reduce Effect.none
                        when (val delegate = action.action.action) {
                            is SessionSheetFeature.Delegate.renamed ->
                                Effect.send(Action.familySessionRenamed(sessionID = sessionID, title = delegate.title))
                            SessionSheetFeature.Delegate.compacted -> {
                                if (state.activePicker == State.ActivePicker.session) state.activePicker = null
                                // The queued compaction shows as a pending row, then the
                                // transcript's compaction rule as it runs and settles.
                                if (state.session?.id == sessionID) Effect.send(Action.inputQueueChanged) else Effect.none
                            }
                            is SessionSheetFeature.Delegate.forked -> {
                                if (state.activePicker == State.ActivePicker.session) state.activePicker = null
                                Effect.send(Action.delegate(Delegate.openSession(delegate.session)))
                            }
                            SessionSheetFeature.Delegate.togglePin ->
                                Effect.send(Action.delegate(Delegate.togglePin(sessionID)))
                            SessionSheetFeature.Delegate.toggleRead ->
                                Effect.send(Action.delegate(Delegate.toggleRead(sessionID)))
                            SessionSheetFeature.Delegate.deleteSession -> {
                                if (state.activePicker == State.ActivePicker.session) state.activePicker = null
                                Effect.send(Action.delegate(Delegate.deleteSession(sessionID)))
                            }
                        }
                    }

                    is Action.sessionSheet, is Action.delegate -> Effect.none

                    is Action.shellOutputTapped -> {
                        val session = state.session
                        val shell = state.shells.firstOrNull { it.id == action.shellID }
                        if (session == null || shell == null) return@Reduce Effect.none
                        state.shellOutput = ShellOutputFeature.State(server = state.server, directory = session.directory, shell = shell)
                        Effect.none
                    }

                    is Action.shellOutput -> Effect.none

                    is Action.backgroundHintElapsed -> {
                        if (state.foregroundWork?.key != action.key) return@Reduce Effect.none
                        state.movableWorkKey = action.key
                        Effect.none
                    }

                    Action.moveToBackgroundTapped -> {
                        val session = state.session
                        val key = state.movableWorkKey
                        if (session == null || !state.showsMoveToBackground || key == null) return@Reduce Effect.none
                        state.movedWorkKeys += key
                        haptics.impact()
                        val server = state.server
                        Effect.run { send ->
                            try {
                                openCodeClient.moveToBackground(server, session.id)
                                send(Action.moveToBackgroundFinished(sessionID = session.id, key = key, error = null))
                            } catch (error: OpenCodeClientError) {
                                send(Action.moveToBackgroundFinished(sessionID = session.id, key = key, error = error))
                            }
                        }
                            .cancellable(CancelID.moveToBackground, cancelInFlight = true)
                    }

                    is Action.moveToBackgroundFinished -> {
                        if (state.session?.id != action.sessionID) return@Reduce Effect.none
                        val error = action.error
                        if (error != null) {
                            state.movedWorkKeys -= action.key
                            haptics.error()
                            appendInfo(state, "Couldn’t move to background: ${error.message}")
                            return@Reduce Effect.none
                        }
                        // The moved shell now outlives its tool call; list it for the capsule.
                        refreshShells(state)
                    }

                    is Action.familyResponse -> {
                        val family = action.result.getOrNull() ?: return@Reduce pollPendingWork(state)
                        val selectedWasActive = state.isExternallyActive
                        // Discovery requests can overlap invalidations. A narrower response
                        // must not temporarily forget an already-discovered child: that would
                        // disable its task card and reject the child request response already
                        // being submitted. Explicit deletion events retire family members.
                        state.familySessions = family.sessions + state.familySessions.filter { previous ->
                            !family.sessions.any { it.id == previous.id }
                        }
                        state.activeFamilySessionIDs = family.activeSessionIDs
                        val selected = state.session ?: return@Reduce Effect.none
                        val rootID = state.familyRootID
                        if (!family.sessions.any { it.id == selected.id } && rootID != null && family.sessions.any { it.id == rootID }) {
                            return@Reduce Effect.send(Action.familyPicked(rootID))
                        }
                        val selectedIsActive = family.activeSessionIDs.contains(selected.id)
                        val poll = pollPendingWork(state)
                        if (selectedIsActive == selectedWasActive) {
                            poll
                        } else {
                            Effect.merge(poll, Effect.send(Action.externalRunStateChanged(isRunning = selectedIsActive)))
                        }
                    }

                    is Action.sessionSnapshotUpdated -> {
                        val session = action.session
                        val selected = state.session
                        if (selected == null || selected.id != session.id) return@Reduce Effect.none
                        state.updateObservedSession(session)
                        // Home's list snapshot is metadata only. The event/reconnect/resume
                        // that caused it already routes through the single content owner.
                        if (state.streamIsHealthy) Effect.none else Effect.send(Action.refreshRequested(RefreshReason.snapshot))
                    }

                    is Action.familyRunStateChanged -> {
                        val sessionID = action.sessionID
                        if (action.isRunning) state.activeFamilySessionIDs += sessionID else state.activeFamilySessionIDs -= sessionID
                        if (state.session?.id != sessionID) return@Reduce Effect.none
                        Effect.send(Action.externalRunStateChanged(isRunning = action.isRunning))
                    }

                    is Action.familySessionRenamed -> {
                        val sessionID = action.sessionID
                        val title = action.title
                        state.renameVoiceTarget(sessionID = sessionID, title = title)
                        val index = state.familySessions.indexOfFirst { it.id == sessionID }
                        if (index < 0) return@Reduce Effect.none
                        state.familySessions = state.familySessions.toMutableList().also { it[index] = it[index].copy(title = title) }
                        if (state.session?.id == sessionID) state.session = state.session?.copy(title = title)
                        Effect.none
                    }

                    is Action.familySessionDeleted -> {
                        val sessionID = action.sessionID
                        state.familySessions = state.familySessions.filterNot { it.id == sessionID }
                        state.activeFamilySessionIDs -= sessionID
                        state.familyTranscriptCache -= sessionID
                        val rootID = state.familyRootID
                        if (state.session?.id == sessionID && rootID != null && state.familySessions.any { it.id == rootID }) {
                            return@Reduce Effect.send(Action.familyPicked(rootID))
                        }
                        pollPendingWork(state)
                    }

                    is Action.attachmentAdded -> {
                        state.pendingAttachments += action.attachment
                        haptics.selection()
                        Effect.none
                    }

                    is Action.attachmentRemoved -> {
                        val index = action.index
                        if (index !in state.pendingAttachments.indices) return@Reduce Effect.none
                        state.pendingAttachments = state.pendingAttachments.filterIndexed { position, _ -> position != index }
                        Effect.none
                    }

                    is Action.filesStaged -> {
                        val effects = mutableListOf<Effect<Action>>()
                        for (file in action.files) {
                            // Like desktop, media the model reads natively travels inline.
                            if (file.mime == AttachmentFileType.pdfMime && file.size <= AttachmentFileType.maxInlineBytes &&
                                state.currentModelReads("pdf")
                            ) {
                                effects += Effect.run { send ->
                                    val data = readStagedFile(file.url)
                                    if (data != null) {
                                        send(Action.attachmentAdded(OpenCodeOutgoingAttachment(id = file.id.toString(), data = data, mime = file.mime, name = file.name)))
                                    }
                                    ComposerFileStaging.discard(file.url)
                                }
                                continue
                            }
                            state.pendingFiles += ComposerFileAttachment(file)
                            effects += upload(file.id, state)
                        }
                        if (action.files.isNotEmpty()) haptics.selection()
                        Effect.merge(effects)
                    }

                    Action.queuedUploadsStarted -> {
                        val ids = state.unstartedUploadIDs
                        state.unstartedUploadIDs = emptyList()
                        Effect.merge(ids.map { upload(it, state) })
                    }

                    is Action.fileUploadProgress -> {
                        updateFile(action.id, state) { file ->
                            if (!file.isUploading) return@updateFile file
                            file.copy(status = ComposerFileAttachment.Status.uploading(action.sent))
                        }
                        Effect.none
                    }

                    is Action.fileUploadFinished -> {
                        val id = action.id
                        val path = action.result.getOrNull()
                        if (path != null) {
                            updateFile(id, state) { it.copy(status = ComposerFileAttachment.Status.uploaded(path)) }
                            return@Reduce sendIfUploadsFinished(state)
                        }
                        val error = action.result.exceptionOrNull() as OpenCodeClientError
                        if (error.isUnsupported) {
                            val serverID = state.server.persistenceID
                            state::uploadUnsupportedServerIDs.withLock { value += serverID }
                        }
                        updateFile(id, state) { file ->
                            file.copy(status = ComposerFileAttachment.Status.failed(if (error.isUnsupported) "Server doesn’t support files" else "Couldn’t upload"))
                        }
                        if (state.pendingFiles.any { it.id == id }) {
                            state.sendAfterUploads = null
                        }
                        haptics.error()
                        Effect.none
                    }

                    is Action.fileRetryTapped -> {
                        val id = action.id
                        val file = state.pendingFiles.firstOrNull { it.id == id }
                        if (!state.supportsFileUploads || file == null || !file.isFailed) return@Reduce Effect.none
                        upload(id, state)
                    }

                    is Action.fileRemoved -> {
                        val id = action.id
                        val index = state.pendingFiles.indexOfFirst { it.id == id }
                        if (index < 0) return@Reduce Effect.none
                        val file = state.pendingFiles[index]
                        state.pendingFiles = state.pendingFiles.filterIndexed { position, _ -> position != index }
                        val url = file.localURL
                        Effect.merge(
                            Effect.cancel(CancelID.upload(id)),
                            Effect.run { _ -> ComposerFileStaging.discard(url) },
                            sendIfUploadsFinished(state),
                        )
                    }

                    is Action.pendingRequestsResponse -> {
                        val sessionID = action.sessionID
                        if (state.session?.id != sessionID) return@Reduce Effect.none
                        val requests = action.result.getOrElse { error ->
                            error as OpenCodeClientError
                            val attempt = state.requestLoadAttempt
                            state.requestLoadAttempt = null
                            if (!state.requestsConnectionIsAvailable) return@Reduce Effect.none
                            when (attempt) {
                                State.RequestLoadAttempt.initial -> {
                                    // A first failure while connected may be a warm-up hiccup. Retry once
                                    // before treating it as settled; a reconnect also retries (below).
                                    state.requestFailureAwaitingReconnect = true
                                    if (isTesting && !state.requestRetryEnabledInTests) return@Reduce Effect.none
                                    return@Reduce Effect.run { send ->
                                        clock.sleep(2.seconds)
                                        send(Action.requestsAutoRetry)
                                    }
                                        .cancellable(CancelID.requestsRetry, cancelInFlight = true)
                                }
                                State.RequestLoadAttempt.reconnect, State.RequestLoadAttempt.manual -> {
                                    state.requestFailureAwaitingReconnect = true
                                    state.requestLoadError = error.message
                                }
                                null -> {
                                    // Periodic projection failures stay quiet. Keep a previously settled
                                    // failure visible until a successful authoritative snapshot lands.
                                }
                            }
                            return@Reduce Effect.none
                        }
                        state.requestLoadAttempt = null
                        state.requestFailureAwaitingReconnect = false
                        val oldIDs = state.reviewRequestIDs.toSet()
                        val completedIDs = state.completedRequestIDs
                        val protectedIDs = state.respondingRequestIDs + state.requestErrors.keys
                        val coveredOwners = state.familySessions.map { it.id }.toSet() + "global"
                        fun <Request : PendingRequest> reconcile(fetched: List<Request>, requests: List<Request>): List<Request> {
                            val live = fetched.filter { !completedIDs.contains(it.id) }
                            val reconciled = live + requests.filter { previous ->
                                (protectedIDs.contains(previous.id) || !coveredOwners.contains(previous.sessionID)) &&
                                    !live.any { it.id == previous.id }
                            }
                            return if (requests != reconciled) reconciled else requests
                        }
                        state.permissionRequests = reconcile(requests.permissions, state.permissionRequests)
                        state.formRequests = reconcile(requests.forms, state.formRequests)
                        state.requestLoadError = null
                        for (form in state.formRequests.filter { state.formAnswers[it.id] == null }) {
                            state.formAnswers += form.id to form.defaultAnswers
                        }
                        pruneRequestState(state)
                        val liveReviewIDs = state.reviewRequestIDs.toSet()
                        val selected = state.selectedRequestID
                        if (selected != null && !liveReviewIDs.contains(selected) && !state.respondingRequestIDs.contains(selected)) {
                            state.selectedRequestID = null
                            state.requestNotice = "This request is no longer pending. It may have been answered elsewhere or interrupted."
                        }
                        // A catch-up card lists what was already waiting in its request bar,
                        // so the stack stays in view; requests that arrive later still open.
                        val presentsArrivals = state.readsOnView || state.hasLoadedPendingRequests
                        if (!state.readsOnView) state.hasLoadedPendingRequests = true
                        if (presentsArrivals && (liveReviewIDs - oldIDs).isNotEmpty() && state.activePicker == null) {
                            state.presentRequestReview(id = state.reviewRequestIDs.firstOrNull())
                            haptics.attention()
                        }
                        Effect.none
                    }

                    is Action.requestsConnectionChanged -> {
                        val isAvailable = action.isAvailable
                        if (state.requestsConnectionIsAvailable == isAvailable) return@Reduce Effect.none
                        state.requestsConnectionIsAvailable = isAvailable
                        state.streamIsHealthy = isAvailable && state.streamIsHealthy
                        state.requestLoadError = null
                        if (!isAvailable) {
                            state.requestLoadAttempt = null
                            state.requestFailureAwaitingReconnect = true
                            state.pendingWorkPollSeconds = null
                            // Without the stream, reconciliation is the only content path:
                            // restart the watchdog at its unhealthy cadence.
                            return@Reduce Effect.merge(
                                Effect.cancel(CancelID.requests),
                                Effect.cancel(CancelID.inbox),
                                Effect.cancel(CancelID.inboxFetch),
                                Effect.cancel(CancelID.requestsRetry),
                                watchdog(state),
                            )
                        }
                        val attempt = if (state.requestFailureAwaitingReconnect) State.RequestLoadAttempt.reconnect else State.RequestLoadAttempt.initial
                        // Whatever failed for lack of a server gets one quiet second chance
                        // now that Home has heard from it again.
                        val recovery = mutableListOf<Effect<Action>>()
                        if (state.earlierHistoryRetriesOnReconnect) {
                            state.earlierHistoryRetriesOnReconnect = false
                            recovery += Effect.send(Action.showEarlierTapped)
                        }
                        if (state.errorMessage != null && state.items.isEmpty() && state.session != null) {
                            state.errorMessage = null
                            state.isLoading = true
                            recovery += Effect.send(Action.refreshRequested(RefreshReason.reconnect))
                        }
                        Effect.merge(listOf(startPendingWork(state, attempt = attempt)) + recovery)
                    }

                    Action.reviewRequestsTapped -> {
                        state.presentRequestReview(id = state.reviewRequestIDs.firstOrNull())
                        state.requestLoadAttempt = State.RequestLoadAttempt.manual
                        state.requestLoadError = null
                        refreshRequests(state)
                    }

                    is Action.reviewRequestSelected -> {
                        if (!state.reviewRequestIDs.contains(action.id) || state.respondingRequestIDs.isNotEmpty()) return@Reduce Effect.none
                        state.presentRequestReview(id = action.id)
                        Effect.none
                    }

                    Action.refreshRequestsTapped -> {
                        state.requestLoadAttempt = State.RequestLoadAttempt.manual
                        state.requestLoadError = null
                        refreshRequests(state)
                    }

                    Action.requestsAutoRetry -> {
                        if (!state.requestsConnectionIsAvailable || state.requestLoadAttempt != null || !state.requestFailureAwaitingReconnect) {
                            return@Reduce Effect.none
                        }
                        startPendingWork(state, attempt = State.RequestLoadAttempt.reconnect)
                    }

                    is Action.permissionReplyTapped -> {
                        val id = action.id
                        val reply = action.reply
                        val request = state.permissionRequests.firstOrNull { it.id == id }
                        if (request == null || state.completedRequestIDs.contains(id) || state.respondingRequestIDs.contains(id) ||
                            !(reply != OpenCodePermissionReply.always || request.save?.isEmpty() == false)
                        ) {
                            return@Reduce Effect.none
                        }
                        val client = openCodeClient
                        respond(to = id, sessionID = request.sessionID, state = state) { server ->
                            client.replyPermission(server, request.sessionID, id, reply)
                        }
                    }

                    is Action.questionPageChanged -> {
                        val requestID = action.requestID
                        if (state.respondingRequestIDs.contains(requestID)) return@Reduce Effect.none
                        val count = state.formRequests.firstOrNull { it.id == requestID }?.activeFields(answers = state.formAnswers[requestID] ?: emptyMap())?.size ?: 0
                        if (action.index !in 0 until count) return@Reduce Effect.none
                        state.questionPageIndices += requestID to action.index
                        Effect.none
                    }

                    is Action.formValueChanged -> {
                        val formID = action.formID
                        if (state.formAnswers[formID] == null || state.respondingRequestIDs.contains(formID)) return@Reduce Effect.none
                        state.formAnswers = state.formAnswers.updating(formID) { answers ->
                            val value = action.value
                            if (value == null) answers - action.key else answers + (action.key to value)
                        }
                        state.requestErrors -= formID
                        Effect.none
                    }

                    is Action.formSubmitTapped -> {
                        val id = action.id
                        val form = state.formRequests.firstOrNull { it.id == id }
                        if (form == null || state.completedRequestIDs.contains(id) || state.respondingRequestIDs.contains(id)) {
                            return@Reduce Effect.none
                        }
                        val result = form.validatedAnswer(state.formAnswers[id] ?: emptyMap())
                        val answer = result.getOrElse { error ->
                            error as OpenCodeFormRequest.ValidationError
                            state.requestErrors += id to error.message
                            state.questionPageIndices += id to error.fieldIndex
                            haptics.error()
                            return@Reduce Effect.none
                        }
                        val client = openCodeClient
                        respond(to = id, sessionID = form.sessionID, state = state) { server ->
                            client.replyForm(server, form.sessionID, id, answer)
                        }
                    }

                    is Action.formCancelTapped -> {
                        val id = action.id
                        val form = state.formRequests.firstOrNull { it.id == id }
                        if (form == null || state.completedRequestIDs.contains(id) || state.respondingRequestIDs.contains(id)) {
                            return@Reduce Effect.none
                        }
                        val client = openCodeClient
                        respond(to = id, sessionID = form.sessionID, state = state) { server ->
                            client.cancelForm(server, form.sessionID, id)
                        }
                    }

                    is Action.requestResponseFinished -> {
                        val sessionID = action.sessionID
                        val id = action.requestID
                        val error = action.error
                        val wasResponding = state.respondingRequestIDs.contains(id)
                        state.respondingRequestIDs -= id
                        if (error == null) haptics.success() else haptics.error()
                        val requestIsKnown = state.permissionRequests.any { it.id == id && it.sessionID == sessionID } ||
                            state.formRequests.any { it.id == id && it.sessionID == sessionID }
                        if (!(wasResponding || requestIsKnown || sessionID == "global" || state.familySessions.any { it.id == sessionID })) {
                            return@Reduce Effect.none
                        }
                        if (error != null) {
                            state.requestErrors += id to error.message
                        } else {
                            state.completedRequestIDs += id
                            state.permissionRequests = state.permissionRequests.filterNot { it.id == id }
                            state.formRequests = state.formRequests.filterNot { it.id == id }
                            pruneRequestState(state)
                            if (state.selectedRequestID == id) {
                                state.selectedRequestID = null
                                state.requestNotice = null
                                if (state.activePicker == State.ActivePicker.requests) state.activePicker = null
                            }
                        }
                        refreshRequests(state)
                    }

                    is Action.transcriptBrowsingChanged -> {
                        if (action.isBrowsing) {
                            if (state.browsingWindowStartID != null) return@Reduce Effect.none
                            state.browsingWindowStartID = state.visibleItems.firstOrNull()?.id
                        } else {
                            state.browsingWindowStartID = null
                        }
                        Effect.none
                    }

                    Action.showEarlierTapped -> {
                        if (state.isLoadingEarlier || !state.hasEarlierItems || state.session == null) return@Reduce Effect.none
                        state.visibleLimit += 40
                        loadEarlierHistory(state)
                    }

                    Action.fillEarlierHistory -> loadEarlierHistory(state)

                    is Action.earlierHistoryResponse -> {
                        val sessionID = action.sessionID
                        val cursor = action.cursor
                        val loading = state.earlierHistory as? State.EarlierHistory.loading
                        if (state.session?.id != sessionID || state.earlierHistoryGeneration != action.generation ||
                            loading == null || loading.cursor != cursor
                        ) {
                            return@Reduce Effect.none
                        }
                        val reachedLoadedItems = loading.reachedLoadedItems
                        val retryPhase: State.EarlierHistory = if (reachedLoadedItems) {
                            State.EarlierHistory.paging(cursor = cursor)
                        } else {
                            State.EarlierHistory.seekingOverlap(cursor = cursor)
                        }
                        state.earlierHistory = retryPhase
                        val page = action.result.getOrNull()
                        if (page == null) {
                            val error = action.result.exceptionOrNull() as OpenCodeClientError
                            // No HTTP status means the request never reached the server. The
                            // connection state already covers that; don't open a chat on an
                            // error banner for a page nobody asked for yet.
                            if (error.statusCode == null) {
                                state.earlierHistoryRetriesOnReconnect = true
                            } else {
                                state.earlierHistoryError = error.message
                            }
                        } else {
                            if (!(page.next == null || page.next != cursor)) {
                                state.earlierHistoryError = "The server returned the same history page. Try again."
                                return@Reduce Effect.none
                            }
                            // Older pages never overwrite a newer snapshot or optimistic local rows.
                            val existingIDs = state.items.map { it.id }.toSet()
                            if (page.items.any { existingIDs.contains(it.id) }) {
                                // Refreshes may leap beyond the loaded window. A cursor page can
                                // bridge that interior gap as well as prepend older history.
                                // Work backwards from known anchors, keeping every newer value
                                // we already hold and ignoring unknown rows after the last one.
                                var insertion = state.earlierHistoryInsertionID?.let { anchor ->
                                    state.items.indexOfFirst { it.id == anchor }.takeIf { it >= 0 }
                                }
                                val items = state.items.toMutableList()
                                for (item in page.items.asReversed()) {
                                    val index = items.indexOfFirst { it.id == item.id }
                                    if (index >= 0) {
                                        insertion = index
                                    } else if (insertion != null) {
                                        items.add(insertion, item)
                                    }
                                }
                                state.items = items
                                state.earlierHistoryInsertionID = page.items.firstOrNull()?.id ?: state.earlierHistoryInsertionID
                                state.earlierHistory = page.next?.let { State.EarlierHistory.paging(cursor = it) } ?: State.EarlierHistory.exhausted
                            } else if (reachedLoadedItems || state.items.isEmpty()) {
                                val insertion = state.earlierHistoryInsertionID?.let { anchor ->
                                    state.items.indexOfFirst { it.id == anchor }.takeIf { it >= 0 }
                                } ?: 0
                                state.items = state.items.toMutableList().also { it.addAll(insertion, page.items) }
                                state.earlierHistoryInsertionID = page.items.firstOrNull()?.id ?: state.earlierHistoryInsertionID
                                state.earlierHistory = page.next?.let { State.EarlierHistory.paging(cursor = it) } ?: State.EarlierHistory.exhausted
                            } else {
                                // A busy session can advance past the initial window while paging starts.
                                // Walk back to an overlap before treating unknown rows as older content.
                                if (page.next == null && page.items.isNotEmpty()) {
                                    state.earlierHistory = State.EarlierHistory.seekingOverlap(cursor = null)
                                    state.earlierHistoryError = "The conversation changed while loading history. Try again."
                                    return@Reduce Effect.none
                                }
                                state.earlierHistory = page.next?.let { State.EarlierHistory.seekingOverlap(cursor = it) } ?: State.EarlierHistory.exhausted
                            }
                            state.earlierHistoryError = null
                        }
                        // The first cursor page mostly overlaps the newest REST window. Keep
                        // filling the visible limit the user already requested instead of
                        // relying on another top-edge callback during the prepend transition.
                        if (action.result.isFailure) return@Reduce Effect.none
                        Effect.merge(
                            Effect.send(Action.acceptedTranscriptUpdated(sessionID = sessionID, items = state.items)),
                            if (state.earlierHistory != State.EarlierHistory.exhausted && state.visibleLimit >= state.items.size) {
                                Effect.send(Action.fillEarlierHistory)
                            } else {
                                Effect.none
                            },
                        )
                    }

                    is Action.transcriptResponse -> {
                        val sessionID = action.sessionID
                        if (state.session?.id != sessionID) return@Reduce Effect.none
                        val items = action.result.getOrElse { error ->
                            error as OpenCodeClientError
                            state.initialTranscriptRequestIsExternal = false
                            state.isLoading = false
                            if (state.items.isEmpty()) {
                                state.errorMessage = error.message
                                return@Reduce Effect.send(Action.firstContentFrameRendered)
                            }
                            return@Reduce Effect.none
                        }
                        val completedInitialRequest = state.initialTranscriptRequestIsExternal
                        if (completedInitialRequest) state.initialTranscriptIsFresh = true
                        acceptWindow(items, state)
                        Effect.merge(
                            Effect.send(Action.acceptedTranscriptUpdated(sessionID = sessionID, items = state.items)),
                            // An empty transcript never renders a content frame of its own.
                            if (completedInitialRequest && state.items.isEmpty()) Effect.send(Action.firstContentFrameRendered) else Effect.none,
                        )
                    }
                }
            }
                .onChange(of = { it.foregroundWork }) { _, _ ->
                    Reduce { _ ->
                        val work = state.foregroundWork
                        if (work == null) {
                            state.movableWorkKey = null
                            return@Reduce Effect.cancel(CancelID.backgroundHint)
                        }
                        // A refresh that only adds the server's start time keeps a shown hint.
                        if (state.movableWorkKey == work.key) return@Reduce Effect.none
                        state.movableWorkKey = null
                        val elapsed = work.startedAt?.let { java.time.Duration.between(it, date.now).toMillis() / 1_000.0 } ?: 0.0
                        val hintDelay = backgroundHintDelay.inWholeMilliseconds / 1_000.0
                        val delay = minOf(hintDelay, maxOf(0.0, hintDelay - elapsed))
                        Effect.run { send ->
                            clock.sleep((delay * 1_000).toInt().milliseconds)
                            send(Action.backgroundHintElapsed(work.key))
                        }
                            .cancellable(CancelID.backgroundHint, cancelInFlight = true)
                    }
                }
                .ifLet(State::shellOutput, Action.shellOutput) {
                    ShellOutputFeature()
                }
                .ifLet(State::sessionSheet, Action.sessionSheet) {
                    SessionSheetFeature()
                }
                .ifLet(State::voice, Action.voice) {
                    VoiceFeature()
                }
                // Each admission and answer of a voice prompt re-reads the pinned
                // session and retunes the watchdog, as a typed send and its reply do.
                // The reply itself lands through the normal transcript path.
                .onChange(of = { it.voice?.admittedPromptIDs ?: emptyList() }) { _, _ ->
                    Reduce { _ ->
                        val session = state.session
                        if (session == null || state.voice?.target?.sessionID != session.id) return@Reduce Effect.none
                        Effect.merge(Effect.send(Action.refreshRequested(RefreshReason.runState)), watchdog(state))
                    }
                }
                // The call mirrors whether the family is blocked on a native approval
                // the call cannot settle.
                .onChange(of = { if (it.voice == null) null else it.hasPendingRequests }) { _, _ ->
                    Reduce { _ ->
                        val needsInput = state.hasPendingRequests
                        state.voice?.targetNeedsInput = needsInput
                        Effect.none
                    }
                }
                // A call on a draft creates its session with the draft's model.
                .onChange(of = { it.currentModel }) { _, _ ->
                    Reduce { _ ->
                        val model = state.currentModel
                        if (state.session == null) state.voice?.draftModel = model
                        Effect.none
                    }
                }
                // When the attached session gets a generated or edited title, tell the voice model.
                .onChange(of = { VoiceSessionName(sessionID = it.voice?.target?.sessionID, title = it.voice?.sessionTitle) }) { old, _ ->
                    Reduce { _ ->
                        val sessionID = state.voice?.target?.sessionID
                        val title = state.voice?.sessionTitle
                        if (sessionID == null || old.sessionID != sessionID || title == null || old.title == title) return@Reduce Effect.none
                        Effect.send(Action.voice(PresentationAction.presented(VoiceFeature.Action.sessionRenamed(title))))
                    }
                },
        )

    private data class VoiceSessionName(
        val sessionID: String? = null,
        val title: String? = null,
    )

    /// Marks a request as being answered, then reports how the reply settled.
    /// Callers own validation; `requestResponseFinished` owns the outcome.
    private fun respond(
        to: String,
        sessionID: String,
        state: State,
        reply: suspend (OpenCodeServer) -> Unit,
    ): Effect<Action> {
        val requestID = to
        state.requestErrors -= requestID
        state.respondingRequestIDs += requestID
        val server = state.server
        return Effect.run { send ->
            try {
                reply(server)
                send(Action.requestResponseFinished(sessionID = sessionID, requestID = requestID, error = null))
            } catch (error: OpenCodeClientError) {
                send(Action.requestResponseFinished(sessionID = sessionID, requestID = requestID, error = error))
            }
        }
    }

    /// Discards dictation: tears down the mic and cancels the transcript
    /// stream so nothing else lands in the draft. For sends and manual edits,
    /// where a late result would clobber what the user committed to. Safe to
    /// call unconditionally: a no-op unless dictation is active.
    private fun startDictation(state: State): Effect<Action> {
        state.dictationError = null
        state.dictationPhase = State.DictationPhase.preparing
        // The engine streams whole-session transcripts; splice them after
        // whatever was already typed.
        val typed = state.draft
        val base = if (typed.isEmpty() || typed.endsWith(" ") || typed.endsWith("\n")) typed else "$typed "
        return Effect.run<Action>(
            catch = { error, send ->
                send(Action.dictationStopped(errorMessage = if (error is CancellationException) null else error.message ?: error.toString()))
            },
        ) { send ->
            val transcripts = dictation.start()
            send(Action.dictationReady)
            transcripts.collect { transcript ->
                send(Action.dictationUpdated(base + transcript))
            }
            send(Action.dictationStopped(errorMessage = null))
        }
            .cancellable(CancelID.dictation, cancelInFlight = true)
    }

    private fun stopDictation(state: State): Effect<Action> {
        val phase = state.dictationPhase
        state.dictationPhase = State.DictationPhase.idle
        state.dictationHold = null
        if (phase == State.DictationPhase.idle) return Effect.none
        return Effect.merge(
            Effect.cancel(CancelID.dictation),
            Effect.run { _ -> dictation.stop() },
        )
    }

    private fun loadEarlierHistory(state: State): Effect<Action> {
        val sessionID = state.session?.id
        if (state.isLoadingEarlier || !state.hasEarlierItems || sessionID == null ||
            state.visibleLimit < state.items.size || state.earlierHistory == State.EarlierHistory.exhausted
        ) {
            return Effect.none
        }
        val cursor: String?
        val reachedLoadedItems: Boolean
        when (val earlierHistory = state.earlierHistory) {
            State.EarlierHistory.unknownEdge -> {
                cursor = null
                reachedLoadedItems = false
            }
            is State.EarlierHistory.seekingOverlap -> {
                cursor = earlierHistory.cursor
                reachedLoadedItems = false
            }
            is State.EarlierHistory.paging -> {
                cursor = earlierHistory.cursor
                reachedLoadedItems = true
            }
            is State.EarlierHistory.loading, State.EarlierHistory.exhausted -> return Effect.none
        }
        state.earlierHistory = State.EarlierHistory.loading(cursor = cursor, reachedLoadedItems = reachedLoadedItems)
        state.earlierHistoryError = null
        val server = state.server
        val generation = state.earlierHistoryGeneration
        return Effect.run { send ->
            try {
                val page = openCodeClient.historyPage(server, sessionID, cursor)
                if (!currentCoroutineContext().isActive) return@run
                send(Action.earlierHistoryResponse(sessionID = sessionID, generation = generation, cursor = cursor, result = Result.success(page)))
            } catch (error: OpenCodeClientError) {
                if (!currentCoroutineContext().isActive) return@run
                send(Action.earlierHistoryResponse(sessionID = sessionID, generation = generation, cursor = cursor, result = Result.failure(error)))
            }
        }
            .cancellable(CancelID.earlierHistory, cancelInFlight = true)
    }

    /// Drops the in-flight read and its resync cue. A late response no longer
    /// matches `refresh` and is rejected as stale.
    private fun abandonRefresh(state: State): Effect<Action> {
        state.refresh = null
        state.initialTranscriptIsFresh = false
        state.resync = null
        return Effect.merge(
            Effect.cancel(CancelID.freshness),
            Effect.cancel(CancelID.resyncDelay),
            Effect.cancel(CancelID.resyncMinimum),
        )
    }

    /// Drops answers and per-request presentation state for every request that
    /// is no longer pending.
    private fun pruneRequestState(state: State) {
        val live = state.reviewRequestIDs.toSet()
        fun <Value> prune(values: Map<String, Value>): Map<String, Value> =
            if (values.keys.any { !live.contains(it) }) values.filter { live.contains(it.key) } else values
        state.questionPageIndices = prune(state.questionPageIndices)
        state.formAnswers = prune(state.formAnswers)
        state.requestErrors = prune(state.requestErrors)
    }

    private fun beginResync(state: State, refreshID: Int): Effect<Action> {
        if (state.session == null) return Effect.none
        state.resync = State.ResyncState(refreshID = refreshID)
        return Effect.run { send ->
            clock.sleep(150.milliseconds)
            send(Action.resyncDelayElapsed(refreshID = refreshID))
        }
            .cancellable(CancelID.resyncDelay, cancelInFlight = true)
    }

    private fun settleResync(state: State, refreshID: Int): Effect<Action> {
        val resync = state.resync
        if (resync == null || resync.refreshID != refreshID) return Effect.none
        if (!resync.isShown || resync.minimumElapsed) {
            state.resync = null
            return Effect.merge(Effect.cancel(CancelID.resyncDelay), Effect.cancel(CancelID.resyncMinimum))
        } else {
            state.resync = resync.copy(responseReceived = true)
            return Effect.none
        }
    }

    /// Family switching retains only a small working set. Eight sessions covers
    /// the largest lab family with room for the root while bounding duplicated
    /// transcript rows; the selected session itself remains fully expanded.
    private fun cacheFamilyTranscript(
        items: List<OpenCodeTranscriptItem>,
        sessionID: String,
        state: State,
    ) {
        state.familyTranscriptCache += sessionID to items.takeLast(initialFetchLimit)
        while (state.familyTranscriptCache.size > 8) {
            val protected = listOfNotNull(state.session?.id, state.familyRootID).toSet()
            val evicted = state.familyTranscriptCache.keys.firstOrNull { !protected.contains(it) } ?: break
            state.familyTranscriptCache -= evicted
        }
    }

    /// Finishes dictation intentionally (mic tap): stops capture but keeps the
    /// transcript stream alive so the engine's finalized text — often the only
    /// accurate result — still lands in the draft before the stream ends.
    private fun finishDictation(
        state: State,
        sendWhenComplete: Boolean,
    ): Effect<Action> {
        if (!state.isDictating) return Effect.none
        state.dictationPhase = State.DictationPhase.finishing(sendWhenComplete = sendWhenComplete)
        return Effect.run { _ -> dictation.stop() }
    }

    /// Streams one composer file to the server. Progress and the result route
    /// back by ID, so a family switch that parks the draft keeps it moving.
    private fun upload(id: UUID, state: State): Effect<Action> {
        var target: ComposerFileAttachment? = null
        updateFile(id, state) { file ->
            file.copy(status = ComposerFileAttachment.Status.uploading(0)).also { target = it }
        }
        val file = target ?: return Effect.none
        if (!state.supportsFileUploads) {
            return Effect.send(Action.fileUploadFinished(id = id, result = Result.failure(OpenCodeClientError.uploadsUnsupported)))
        }
        val server = state.server
        val directory = state.session?.directory ?: state.draftProject?.directory
        val uploadFile = openCodeClient.uploadFile
        return Effect.run { send ->
            try {
                var finished: String? = null
                uploadFile(server, directory, file.localURL, file.name)
                    .transformWhile { event ->
                        emit(event)
                        event !is OpenCodeUploadEvent.finished
                    }
                    .collect { event ->
                        when (event) {
                            is OpenCodeUploadEvent.progress -> send(Action.fileUploadProgress(id = id, sent = event.sent))
                            is OpenCodeUploadEvent.finished -> finished = event.path
                        }
                    }
                val path = finished
                if (path != null) {
                    // The server has its copy; drop ours even if the session changed.
                    ComposerFileStaging.discard(file.localURL)
                    send(Action.fileUploadFinished(id = id, result = Result.success(path)))
                    return@run
                }
                if (!currentCoroutineContext().isActive) return@run
                send(Action.fileUploadFinished(id = id, result = Result.failure(OpenCodeClientError(message = "The upload ended early."))))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!currentCoroutineContext().isActive) return@run
                val failure = error as? OpenCodeClientError ?: OpenCodeClientError(message = error.message ?: error.toString())
                send(Action.fileUploadFinished(id = id, result = Result.failure(failure)))
            }
        }
            .cancellable(CancelID.upload(id), cancelInFlight = true)
    }

    /// Applies `change` to the file wherever its draft currently lives.
    private fun updateFile(id: UUID, state: State, change: (ComposerFileAttachment) -> ComposerFileAttachment) {
        val index = state.pendingFiles.indexOfFirst { it.id == id }
        if (index >= 0) {
            state.pendingFiles = state.pendingFiles.toMutableList().also { it[index] = change(it[index]) }
            return
        }
        for ((key, draft) in state.familyDrafts) {
            val fileIndex = draft.files.indexOfFirst { it.id == id }
            if (fileIndex < 0) continue
            state.familyDrafts += key to draft.copy(files = draft.files.toMutableList().also { it[fileIndex] = change(it[fileIndex]) })
            return
        }
    }

    /// Completes a send that was waiting on uploads once none remain in flight.
    private fun sendIfUploadsFinished(state: State): Effect<Action> {
        val delivery = state.sendAfterUploads
        if (delivery == null || state.isUploadingFiles) return Effect.none
        state.sendAfterUploads = null
        if (!state.hasPromptContent) return Effect.none
        return Effect.send(if (delivery == OpenCodeDelivery.queue) Action.queueTapped else Action.sendTapped)
    }

    /// A send that failed after its screen went away comes back here: the
    /// draft and the error, as if it had just failed on screen.
    private fun restoreUndeliveredPrompt(state: State): Effect<Action> {
        val entry = undeliveredPrompts.take(serverKey = state.server.persistenceID, sessionID = state.session?.id) ?: return Effect.none
        return Effect.send(Action.promptFailed(sessionID = state.session?.id, error = entry.error, pending = entry.pending))
    }

    /// Sends one prompt, optionally creating the session (and applying a
    /// pre-picked model) first, then waits for the run and loads the reply.
    private fun deliver(
        pending: PendingPrompt,
        to: SendTarget,
        server: OpenCodeServer,
    ): Effect<Action> {
        val target = to
        val openCodeClient = openCodeClient
        val clock = clock
        val undeliveredPrompts = undeliveredPrompts
        return Effect.run { send ->
            // Once Send is tapped the message must reach the server or come back
            // as a visible failure. The create-and-send runs in its own task so a
            // screen change mid-create (New Session, the drawer, another session)
            // cancels only this effect's reporting, never the delivery itself.
            val delivery = CoroutineScope(currentCoroutineContext().minusKey(Job)).async {
                var created: OpenCodeSession? = null
                val sessionID = when (target) {
                    is SendTarget.existing -> target.sessionID
                    is SendTarget.newSession -> {
                        val session = try {
                            openCodeClient.createSession(server, target.directory)
                        } catch (error: OpenCodeClientError) {
                            return@async DeliveryOutcome.failed(sessionID = null, created = null, error = error)
                        }
                        created = session
                        val model = target.model
                        if (model != null) {
                            try {
                                openCodeClient.switchModel(server, session.id, model)
                            } catch (_: OpenCodeClientError) {
                            }
                        }
                        session.id
                    }
                }
                try {
                    openCodeClient.sendPrompt(server, sessionID, pending.text, pending.attachments, pending.files.mapNotNull { it.reference }, pending.messageID, pending.delivery)
                } catch (error: OpenCodeClientError) {
                    // The POST may have been accepted even when its response was lost.
                    // Reconcile the same client ID before restoring the draft and
                    // offering a retry that would necessarily mint a different ID. A
                    // lost connection (or a new folder's location still booting) gets a
                    // few chances to settle; a server's own error answers once.
                    var accepted = false
                    for (attempt in 0 until (if (error.statusCode == null) 3 else 1)) {
                        if (accepted) break
                        if (attempt > 0) clock.sleep(1.seconds)
                        val messageEcho = try {
                            openCodeClient.message(server, sessionID, pending.messageID)
                        } catch (_: OpenCodeClientError) {
                            null
                        }
                        val inboxEcho = if (messageEcho == null) {
                            try {
                                openCodeClient.pendingInputs(server, sessionID)
                            } catch (_: OpenCodeClientError) {
                                null
                            }
                        } else {
                            null
                        }
                        accepted = messageEcho != null || inboxEcho?.any { it.id == pending.messageID } == true
                    }
                    if (!accepted) return@async DeliveryOutcome.failed(sessionID = sessionID, created = created, error = error)
                }
                DeliveryOutcome.sent(sessionID = sessionID, created = created)
            }
            // Awaiting `value` does not cancel the delivery when this effect is.
            // Like `Task.value`, the wait outlives this effect, so a failure is still kept for the next screen.
            val outcome = withContext(NonCancellable) { delivery.await() }
            val sessionID: String
            when (outcome) {
                is DeliveryOutcome.failed -> {
                    // Keep the draft until a screen shows the failure: if this one is
                    // gone, reopening the session (or the next new draft) restores it.
                    undeliveredPrompts.keep(pending, error = outcome.error, serverKey = server.persistenceID, sessionID = outcome.sessionID)
                    outcome.created?.let { send(Action.sessionCreated(it)) }
                    send(Action.promptFailed(sessionID = outcome.sessionID, error = outcome.error, pending = pending))
                    return@run
                }
                is DeliveryOutcome.sent -> {
                    outcome.created?.let { send(Action.sessionCreated(it)) }
                    sessionID = outcome.sessionID
                }
            }
            send(Action.promptAdmitted(sessionID = sessionID, messageID = pending.messageID, requestEpoch = pending.requestEpoch))
            if (pending.wasRunning) {
                send(Action.pendingInputsRequested(sessionID = sessionID))
            }
            // The optimistic local bubble covers the interim; wait for the run to
            // finish, then load the reply. Token streaming arrives with /api/event.
            try {
                openCodeClient.waitForIdle(server, sessionID)
            } catch (_: OpenCodeClientError) {
                return@run
            }
            if (!currentCoroutineContext().isActive) return@run
            send(Action.replyCompleted(sessionID = sessionID))
        }
    }

    /// Loads the model catalog once per session view — needed up front so the
    /// composer can show the model's display name instead of its raw ID.
    private fun loadModelsIfNeeded(state: State): Effect<Action> {
        if (state.models.isNotEmpty() || state.isLoadingModels) return Effect.none
        state.isLoadingModels = true
        val server = state.server
        val directory = state.session?.directory ?: state.draftProject?.directory
        return Effect.run { send ->
            try {
                send(Action.modelsResponse(Result.success(openCodeClient.modelCatalog(server, directory))))
            } catch (error: OpenCodeClientError) {
                send(Action.modelsResponse(Result.failure(error)))
            }
        }
    }

    /// Reconciliation is only a watchdog when projection is healthy. Without a
    /// proven content event, active sessions retain the short fallback cadence.
    private fun watchdog(state: State): Effect<Action> {
        // Individual watchdog tests opt into the live dependency context and
        // inject a TestClock. Other reducer tests should not inherit a perpetual
        // timer unrelated to the behavior they exercise.
        if (isTesting && !state.watchdogEnabledInTests) return Effect.none
        val session = state.session ?: return Effect.cancel(CancelID.watchdog)
        val seconds = when {
            state.streamIsHealthy && state.streamSupportsProjection -> 20
            state.isRunning -> 3
            else -> 30
        }
        // Stable per-session jitter avoids synchronized clients while remaining
        // deterministic within one process and friendly to TestClock.
        val jitter = abs(session.id.hashCode() % maxOf(1, seconds / 5 + 1))
        return Effect.run { send ->
            while (currentCoroutineContext().isActive) {
                clock.sleep((seconds + jitter).seconds)
                send(Action.refreshRequested(RefreshReason.watchdog))
            }
        }
            .cancellable(CancelID.watchdog, cancelInFlight = true)
    }

    /// Permission/question prompts and queued input are both ephemeral server
    /// state that a dropped event stream would desync, so one timer re-reads
    /// both while the session is open. (Re)starting it adopts the cadence the
    /// stream's current health warrants.
    private fun pollPendingWork(state: State): Effect<Action> {
        val session = state.session ?: return Effect.none
        val seconds = pendingWorkPollSeconds(streamIsHealthy = state.streamIsHealthy)
        state.pendingWorkPollSeconds = seconds
        val server = state.server
        val family = state.familySessions.map { it.id }
        val requests = Effect.run<Action> { send ->
            while (currentCoroutineContext().isActive) {
                readPendingRequests(server = server, session = session, family = family, send = send)
                wallClock.sleep(seconds.seconds)
            }
        }
            .cancellable(CancelID.requests, cancelInFlight = true)
        val inbox = Effect.run<Action> { send ->
            while (currentCoroutineContext().isActive) {
                send(Action.pendingInputsRequested(sessionID = session.id))
                wallClock.sleep(seconds.seconds)
            }
        }
            .cancellable(CancelID.inbox, cancelInFlight = true)
        return Effect.merge(requests, inbox)
    }

    private fun fetchQueuedInputs(server: OpenCodeServer, sessionID: String): Effect<Action> =
        Effect.run<Action> { send -> readQueuedInputs(server = server, sessionID = sessionID, send = send) }
            .cancellable(CancelID.inboxFetch, cancelInFlight = true)

    private suspend fun readQueuedInputs(server: OpenCodeServer, sessionID: String, send: Send<Action>) {
        val inputs = try {
            openCodeClient.pendingInputs(server, sessionID)
        } catch (_: OpenCodeClientError) {
            null
        }
        if (inputs != null) {
            if (!currentCoroutineContext().isActive) return
            send(Action.pendingInputsResponse(sessionID = sessionID, inputs = inputs))
        }
    }

    private fun startPendingWork(state: State, attempt: State.RequestLoadAttempt): Effect<Action> {
        if (state.session == null) return Effect.none
        state.requestLoadAttempt = attempt
        state.requestLoadError = null
        return pollPendingWork(state)
    }

    /// A poll started before the stream proved healthy keeps its fallback
    /// cadence until restarted; restart it once the stream is healthy.
    private fun retunePendingWork(state: State): Effect<Action> {
        val running = state.pendingWorkPollSeconds
        if (running == null || running == pendingWorkPollSeconds(streamIsHealthy = state.streamIsHealthy)) return Effect.none
        return pollPendingWork(state)
    }

    private fun refreshRequests(state: State): Effect<Action> {
        val session = state.session ?: return Effect.none
        val server = state.server
        val family = state.familySessions.map { it.id }
        return Effect.run { send -> readPendingRequests(server = server, session = session, family = family, send = send) }
    }

    /// Reads the requests pending anywhere in the selected session's family.
    private suspend fun readPendingRequests(
        server: OpenCodeServer,
        session: OpenCodeSession,
        family: List<String>,
        send: Send<Action>,
    ) {
        try {
            val requests = openCodeClient.familyPendingRequests(server, family, session.directory)
            send(Action.pendingRequestsResponse(sessionID = session.id, result = Result.success(requests)))
        } catch (error: OpenCodeClientError) {
            send(Action.pendingRequestsResponse(sessionID = session.id, result = Result.failure(error)))
        }
    }

    /// Sends the read receipt for an idle watermark the visible screen has just
    /// shown from an accepted authoritative read.
    private fun acknowledgeViewed(observed: OpenCodeSession, state: State): Effect<Action> {
        val idle = observed.idleMilliseconds
        if (!state.isVisible || !state.readsOnView || idle == null ||
            idle <= (observed.viewedMilliseconds ?: -1L) ||
            idle <= (state.viewedIdle[observed.id] ?: -1L)
        ) {
            return Effect.none
        }
        val server = state.server
        return Effect.run { send ->
            try {
                openCodeClient.viewSession(server, observed.id, idle)
                send(Action.viewAcknowledged(sessionID = observed.id, idle = idle))
            } catch (_: OpenCodeClientError) {
            }
        }
            .cancellable(CancelID.viewReceipt, cancelInFlight = true)
    }

    private fun authoritativeRefresh(
        server: OpenCodeServer,
        session: OpenCodeSession,
        requestID: Int,
        reason: RefreshReason,
        messageIsInWindow: Boolean,
        readsContext: Boolean,
    ): Effect<Action> {
        val client = openCodeClient
        suspend fun readContext(): OpenCodeContextReading =
            try {
                client.contextUsage(server, session.id)
            } catch (_: OpenCodeClientError) {
                OpenCodeContextReading.inconclusive
            }
        return Effect.run { send ->
            try {
                val messageID = reason.messageID
                val refresh = if (messageID != null && messageIsInWindow) {
                    // A short trailing coalescing window absorbs text/tool/step endings
                    // for the same durable assistant message into one authoritative GET.
                    clock.sleep(200.milliseconds)
                    coroutineScope {
                        // A settled message changes the context only when its step ended.
                        val reading = async { if (readsContext) readContext() else OpenCodeContextReading.inconclusive }
                        val items = openCodeClient.message(server, session.id, messageID)
                        AuthoritativeRefresh(observed = session, items = items, scope = AuthoritativeRefresh.Scope.message(messageID), context = reading.await())
                    }
                } else {
                    coroutineScope {
                        val reading = async { readContext() }
                        val observed = try {
                            openCodeClient.getSession(server, session.id)
                        } catch (_: OpenCodeClientError) {
                            session
                        }
                        val items = openCodeClient.transcript(server, session.id, initialFetchLimit)
                        AuthoritativeRefresh(observed = observed, items = items, scope = AuthoritativeRefresh.Scope.page, context = reading.await())
                    }
                }
                if (!currentCoroutineContext().isActive) return@run
                send(Action.refreshResponse(sessionID = session.id, requestID = requestID, result = Result.success(refresh)))
            } catch (error: OpenCodeClientError) {
                if (!currentCoroutineContext().isActive) return@run
                send(Action.refreshResponse(sessionID = session.id, requestID = requestID, result = Result.failure(error)))
            }
        }
            .cancellable(CancelID.freshness)
    }

    private fun applyAuthoritative(items: List<OpenCodeTranscriptItem>, state: State, preserveProjection: Boolean) {
        state.initialTranscriptIsFresh = true
        acceptWindow(items, state, preserveProjection = preserveProjection)
        // Events received before this request began may describe an unfinished
        // value absent from the REST projection. Reapply by semantic part ID.
        for ((id, text) in state.liveText) state.items = upsertLiveText(id = id, text = text, items = state.items)
    }

    /// Adopts a newest-messages window and settles the prompts it echoes.
    private fun acceptWindow(items: List<OpenCodeTranscriptItem>, state: State, preserveProjection: Boolean? = null) {
        // A newest window can pass a pending prompt using assistant messages
        // alone. Keep that prompt in paged history rather than appending its
        // targeted echo below the newer replies. Compare messages, not parts:
        // replacing a projected part or removing a notice is not a window jump.
        val windowIDs = windowMessageIDs(`in` = items)
        state.pendingPromptPositions = state.pendingPromptPositions.mapValues { (_, position) ->
            val preceding = position.precedingMessageIDs + position.precedingPromptIDs
            position.copy(isBeforeWindow = windowIDs.isNotEmpty() && position.precedingMessageIDs.isNotEmpty() && windowIDs.none(preceding::contains))
        }
        val preserveProjection = preserveProjection ?: (state.isRunning || state.refresh?.followup == RefreshReason.runState)
        if (!preserveProjection) state.resetLiveProjection()
        LabMarks.mark("session_data", sessionID = state.session?.id)
        state.initialTranscriptRequestIsExternal = false
        state.isLoading = false
        state.errorMessage = null
        val knownIDs = state.items.map { it.id }.toSet()
        if (state.items.isNotEmpty() && items.isNotEmpty() && !items.any { knownIDs.contains(it.id) }) {
            // Reaching the oldest message once doesn't mean future refreshes can't
            // skip an interval. Reopen the cursor chain so browsing fills that gap.
            state.earlierHistory = State.EarlierHistory.unknownEdge
            state.earlierHistoryInsertionID = null
            state.earlierHistoryError = null
            state.earlierHistoryGeneration += 1
        }
        val merged = mergeTranscript(
            fetched = items,
            into = state.items,
            optimisticPromptIDs = state.optimisticPromptIDs,
            projectedMessageIDs = if (preserveProjection) state.projectedMessageIDs else emptySet(),
            echoedPromptIDs = state.echoedPromptIDs,
        )
        reconcilePromptEchoes(items, state)
        if (merged != state.items) state.items = merged
        settleUnconfirmedSends(promptIDs(`in` = items), state)
    }

    /// A send reported as failed reached the server after all: drop its
    /// notice, and take back the draft restored for a retry unless the reader
    /// has since changed it.
    private fun settleUnconfirmedSends(messageIDs: Set<String>, state: State) {
        for (id in messageIDs) {
            val failure = state.unconfirmedSendFailures[id] ?: continue
            state.unconfirmedSendFailures -= id
            state.items = state.items.filterNot { it.id == failure.infoID }
            if (failure.text.isNotEmpty()) {
                if (state.draft == failure.text) {
                    state.draft = ""
                } else if (state.draft.startsWith(failure.text + "\n\n")) {
                    state.draft = state.draft.drop(failure.text.length + 2)
                }
            }
            state.pendingAttachments = state.pendingAttachments.filterNot { failure.attachmentIDs.contains(it.id) }
            state.pendingFiles = state.pendingFiles.filterNot { failure.fileIDs.contains(it.id) }
        }
    }

    private fun applyAuthoritativeMessage(
        fetched: List<OpenCodeTranscriptItem>,
        messageID: String,
        state: State,
        preserveProjection: Boolean? = null,
    ) {
        LabMarks.mark("session_data", sessionID = state.session?.id)
        val current = state.items
        val indexes = current.indices.filter { item(current[it], belongsTo = messageID) }
        val insertion = indexes.firstOrNull() ?: return
        // A durable boundary belongs to a part, not the whole message. A sibling
        // tool can have started before this read (so it is not in racedEvents)
        // and still have no REST payload. Use the same protection as page reads.
        val preserveProjection = preserveProjection ?: (state.isRunning || state.refresh?.followup == RefreshReason.runState)
        val replacement = if (preserveProjection && state.projectedMessageIDs.contains(messageID)) {
            mergeTranscript(
                fetched = fetched,
                into = indexes.map { current[it] },
                projectedMessageIDs = setOf(messageID),
            )
        } else {
            fetched
        }
        val items = current.filterNot { item(it, belongsTo = messageID) }.toMutableList()
        items.addAll(minOf(insertion, items.size), replacement)
        state.items = items
        reconcilePromptEchoes(fetched, state)
        for ((id, text) in state.liveText) {
            if (!id.startsWith("$messageID-")) continue
            state.items = upsertLiveText(id = id, text = text, items = state.items)
        }
    }

    private fun reconcilePromptEchoes(items: List<OpenCodeTranscriptItem>, state: State) {
        val promptIDs = promptIDs(`in` = items)
        state.optimisticPromptIDs -= promptIDs
        state.echoedPromptIDs -= promptIDs
        for (id in promptIDs) {
            if (state.promptStates[id] == null && !state.queuedInputs.any { it.id == id }) continue
            state.queuedInputs = state.queuedInputs.filterNot { it.id == id }
            state.pendingPromptPositions -= id
            state.queuedInputImages -= id
            state.promptStates -= id
            state.promotedPromptIDs = state.promotedPromptIDs.filterNot { it == id }
            state.promotedPromptIDs += id
            if (state.promotedPromptIDs.size > 32) {
                state.promotedPromptIDs = state.promotedPromptIDs.drop(state.promotedPromptIDs.size - 32)
            }
        }
    }

    private fun pendingPredecessors(state: State): Set<String> =
        promptIDs(`in` = state.items) + state.queuedInputs.mapNotNull {
            if (!it.isCompaction && it.notice == null) it.id else null
        }

    private fun recordSteeringPredecessor(id: String, state: State) {
        for (input in state.queuedInputs.filter { it.delivery == OpenCodeDelivery.queue }) {
            state.pendingPromptPositions = state.pendingPromptPositions.updating(input.id) { it.copy(precedingPromptIDs = it.precedingPromptIDs + id) }
        }
    }

    /// REST can already be ahead of a raced event. Tool input is writable only
    /// while streaming, and progress must never restart a settled tool.
    private fun applyLiveEvent(event: OpenCodeLiveContentEvent, state: State) {
        when (event) {
            is OpenCodeLiveContentEvent.textStarted -> {
                val id = "${event.messageID}-text-${event.ordinal}"
                state.liveText += id to (state.liveText[id] ?: "")
            }
            is OpenCodeLiveContentEvent.textDelta -> {
                val id = "${event.messageID}-text-${event.ordinal}"
                state.liveText += id to (state.liveText.getOrElse(id) { "" } + event.delta)
                state.items = upsertLiveText(id = id, text = state.liveText[id] ?: "", items = state.items)
            }
            is OpenCodeLiveContentEvent.textEnded -> {
                val id = "${event.messageID}-text-${event.ordinal}"
                state.items = upsertLiveText(id = id, text = event.text, items = state.items)
                state.liveText -= id
            }
            is OpenCodeLiveContentEvent.toolInputStarted ->
                state.items = upsertLiveTool(messageID = event.messageID, toolID = event.toolID, defaultInput = OpenCodeJSONValue.string(""), items = state.items) { call ->
                    if (call.status != OpenCodeToolCall.Status.streaming) return@upsertLiveTool call
                    call.copy(name = event.name)
                }
            is OpenCodeLiveContentEvent.toolInputDelta ->
                state.items = upsertLiveTool(messageID = event.messageID, toolID = event.toolID, defaultInput = OpenCodeJSONValue.string(""), items = state.items) { call ->
                    if (call.status != OpenCodeToolCall.Status.streaming || call.input !is OpenCodeJSONValue.string) return@upsertLiveTool call
                    call.copy(input = OpenCodeJSONValue.string((call.input.string ?: "") + event.delta))
                }
            is OpenCodeLiveContentEvent.toolInputEnded ->
                state.items = upsertLiveTool(messageID = event.messageID, toolID = event.toolID, defaultInput = OpenCodeJSONValue.string(""), items = state.items) { call ->
                    if (call.status != OpenCodeToolCall.Status.streaming || call.input !is OpenCodeJSONValue.string) return@upsertLiveTool call
                    call.copy(input = OpenCodeJSONValue.string(event.text))
                }
            is OpenCodeLiveContentEvent.toolCalled ->
                state.items = upsertLiveTool(messageID = event.messageID, toolID = event.toolID, items = state.items) { call ->
                    if (call.status != OpenCodeToolCall.Status.streaming) return@upsertLiveTool call
                    call.copy(status = OpenCodeToolCall.Status.running, input = event.input)
                }
            is OpenCodeLiveContentEvent.toolProgress ->
                state.items = upsertLiveTool(messageID = event.messageID, toolID = event.toolID, items = state.items) { call ->
                    if (call.status != OpenCodeToolCall.Status.streaming && call.status != OpenCodeToolCall.Status.running) return@upsertLiveTool call
                    call.copy(status = OpenCodeToolCall.Status.running, metadata = event.metadata)
                }
            is OpenCodeLiveContentEvent.toolSucceeded ->
                state.items = upsertLiveTool(messageID = event.messageID, toolID = event.toolID, items = state.items) { call ->
                    call.copy(status = OpenCodeToolCall.Status.completed, output = event.output, metadata = event.metadata)
                }
            is OpenCodeLiveContentEvent.toolFailed ->
                state.items = upsertLiveTool(messageID = event.messageID, toolID = event.toolID, items = state.items) { call ->
                    call.copy(status = OpenCodeToolCall.Status.error, error = event.error, output = event.output, metadata = event.metadata)
                }
            is OpenCodeLiveContentEvent.stepStarted, is OpenCodeLiveContentEvent.stepStreamed, is OpenCodeLiveContentEvent.stepEnded,
            is OpenCodeLiveContentEvent.reasoningStarted, is OpenCodeLiveContentEvent.reasoningDelta, is OpenCodeLiveContentEvent.reasoningEnded,
            -> Unit
        }
    }

    private fun upsertLiveText(id: String, text: String, items: List<OpenCodeTranscriptItem>): List<OpenCodeTranscriptItem> {
        if (text.isBlank()) return items
        val index = items.indexOfFirst { it.id == id }
        val previous = (items.getOrNull(index) as? OpenCodeTranscriptItem.assistantMarkdown)?.blocks ?: emptyList()
        val item = OpenCodeTranscriptItem.assistantMarkdown(
            id = id,
            blocks = OpenCodeMarkdownBlock.blocks(from = text, reusing = previous),
        )
        return if (index >= 0) items.toMutableList().also { it[index] = item } else items + item
    }

    private fun upsertLiveTool(
        messageID: String,
        toolID: String,
        defaultInput: OpenCodeJSONValue = OpenCodeJSONValue.`object`(emptyMap()),
        items: List<OpenCodeTranscriptItem>,
        update: (OpenCodeToolCall) -> OpenCodeToolCall,
    ): List<OpenCodeTranscriptItem> {
        val id = "$messageID-tool-$toolID"
        val index = items.indexOfFirst { it.id == id }
        val call = update(
            (items.getOrNull(index) as? OpenCodeTranscriptItem.tool)?.call ?: OpenCodeToolCall(
                callID = toolID,
                messageID = messageID,
                name = "tool",
                status = OpenCodeToolCall.Status.streaming,
                input = defaultInput,
                output = emptyList(),
                error = null,
                metadata = emptyMap(),
            ),
        )
        val item = OpenCodeTranscriptItem.tool(id = id, call = call)
        return if (index >= 0) items.toMutableList().also { it[index] = item } else items + item
    }

    private fun refreshFamily(state: State): Effect<Action> {
        val rootID = state.familyRootID ?: return Effect.none
        val root = state.familySessions.firstOrNull { it.id == rootID } ?: return Effect.none
        val server = state.server
        return Effect.run { send ->
            try {
                send(Action.familyResponse(Result.success(openCodeClient.sessionFamily(server, root))))
            } catch (error: OpenCodeClientError) {
                send(Action.familyResponse(Result.failure(error)))
            }
        }
            .cancellable(CancelID.familyRefresh, cancelInFlight = true)
    }

    private fun refreshShells(state: State): Effect<Action> {
        val session = state.session ?: return Effect.none
        val server = state.server
        return Effect.run { send ->
            try {
                val shells = openCodeClient.runningShells(server, session.directory)
                send(Action.shellsResponse(sessionID = session.id, result = Result.success(shells)))
            } catch (error: OpenCodeClientError) {
                send(Action.shellsResponse(sessionID = session.id, result = Result.failure(error)))
            }
        }
            .cancellable(CancelID.shells, cancelInFlight = true)
    }

    companion object {
        const val initialFetchLimit = 30

        /// The window fetched before the first frame. Small enough to paint in one
        /// round-trip on cellular; `firstContentFrameRendered` fills to
        /// `initialFetchLimit` right after (see perf/responsiveness.md, H2).
        const val firstFrameFetchLimit = 12

        /// How long a shell or subagent must block the run before it can be
        /// moved to the background (the TUI's `BACKGROUND_TOOL_HINT_DELAY`).
        val backgroundHintDelay: Duration = 3.seconds

        /// Splices a freshly fetched window of newest messages into what we show.
        ///
        /// Every fetch returns the *newest N messages*, so a poll's window is often
        /// shorter than a transcript already expanded by the full-history fetch —
        /// and its item count can even shrink as the window slides over tool-heavy
        /// messages. Comparing counts therefore froze the transcript and new
        /// messages stopped appearing, so instead: keep everything older than the
        /// window, adopt the window, and carry client-only rows forward.
        fun mergeTranscript(
            fetched: List<OpenCodeTranscriptItem>,
            into: List<OpenCodeTranscriptItem>,
            optimisticPromptIDs: Set<String> = emptySet(),
            projectedMessageIDs: Set<String> = emptySet(),
            echoedPromptIDs: Set<String> = emptySet(),
        ): List<OpenCodeTranscriptItem> {
            val existing = into
            if (fetched.isEmpty()) return existing
            val windowIDs = fetched.map { it.id }.toSet()
            // REST has no unfinished text/tool payload until its durable boundary.
            // A page fetched while an SSE projection is live must not erase a row
            // merely because that message's part is still absent from the snapshot.
            val windowStart = existing.indexOfFirst { it.id == fetched[0].id }.takeIf { it >= 0 } ?: 0
            val retainedIDs = existing.drop(windowStart).filter {
                !windowIDs.contains(it.id) &&
                    (projectedMessageIDs.contains(messageID(it)) || echoedPromptIDs.contains(messageID(it)))
            }.map { it.id }.toSet()
            val isClientOnly = { item: OpenCodeTranscriptItem ->
                !windowIDs.contains(item.id) && (
                    item.id.startsWith("info-") ||
                        TranscriptEntry.promptID(forItem = item)?.let(optimisticPromptIDs::contains) == true ||
                        retainedIDs.contains(item.id)
                    )
            }
            val fromServer = existing.filter { !isClientOnly(it) }
            val clientOnly = existing.filter(isClientOnly)

            // The window is authoritative for every id it contains: a row we hold
            // before the window start must not survive if the window also has it
            // (stale cache for a session whose history changed underneath us).
            val serverWindowStart = fromServer.indexOfFirst { it.id == fetched[0].id }
            val older = if (serverWindowStart >= 0) {
                fromServer.subList(0, serverWindowStart).filter { !windowIDs.contains(it.id) }
            } else {
                // No overlap: the window either reaches further back than what we hold
                // or jumped ahead of it. Keep whatever the window doesn't cover.
                fromServer.filter { !windowIDs.contains(it.id) }
            }

            val merged = older + fetched
            val trailing = clientOnly.filter { !windowIDs.contains(it.id) && !retainedIDs.contains(it.id) }
            if (retainedIDs.isEmpty()) return merged + trailing
            // Keep an unpersisted part before the next surviving row, rather than
            // moving it behind later steering messages on every page refresh.
            val survivingIDs = merged.map { it.id }.toSet()
            val nextSurvivor = HashMap<String, String?>()
            var successor: String? = null
            for (item in existing.asReversed()) {
                if (retainedIDs.contains(item.id)) {
                    nextSurvivor[item.id] = successor
                } else if (survivingIDs.contains(item.id)) {
                    successor = item.id
                }
            }
            // A new steering echo may have no old successor. Anchor after the part's
            // own fetched siblings, then fall back to its predecessor's whole message.
            val messageEnds = HashMap<String, Int>()
            val positions = merged.withIndex().associate { it.value.id to it.index }
            for ((index, item) in merged.withIndex()) messageEnds[messageID(item)] = index + 1
            val buckets = HashMap<Int, MutableList<OpenCodeTranscriptItem>>()
            var predecessor: OpenCodeTranscriptItem? = null
            for (item in existing) {
                if (retainedIDs.contains(item.id)) {
                    val insertion = nextSurvivor[item.id]?.let { positions[it] }
                        ?: messageEnds[messageID(item)]
                        ?: predecessor?.let { messageEnds[messageID(it)] }
                        ?: (if (echoedPromptIDs.contains(messageID(item))) 0 else merged.size)
                    buckets.getOrPut(insertion, ::mutableListOf).add(item)
                } else if (survivingIDs.contains(item.id)) {
                    predecessor = item
                }
            }
            val result = ArrayList<OpenCodeTranscriptItem>(merged.size + retainedIDs.size + trailing.size)
            for (index in merged.indices) {
                result += buckets[index] ?: emptyList()
                result.add(merged[index])
            }
            return result + (buckets[merged.size] ?: emptyList()) + trailing
        }

        private fun messageID(`for`: OpenCodeTranscriptItem): String {
            val item = `for`
            return when (item) {
                is OpenCodeTranscriptItem.tool ->
                    item.call.messageID ?: item.id.indexOf("-tool-").takeIf { it >= 0 }?.let { item.id.substring(0, it) } ?: item.id
                is OpenCodeTranscriptItem.assistantMarkdown ->
                    item.id.lastIndexOf("-text-").takeIf { it >= 0 }?.let { item.id.substring(0, it) } ?: item.id
                is OpenCodeTranscriptItem.userImages -> item.id.dropLast("-images".length)
                is OpenCodeTranscriptItem.userFiles -> item.id.dropLast("-files".length)
                else -> item.id
            }
        }

        private fun promptIDs(`in`: List<OpenCodeTranscriptItem>): Set<String> =
            `in`.mapNotNull { TranscriptEntry.promptID(forItem = it) }.toSet()

        private suspend fun readStagedFile(url: File): ByteArray? =
            withContext(Dispatchers.IO) { runCatching { url.readBytes() }.getOrNull() }

        private fun pendingWorkPollSeconds(streamIsHealthy: Boolean): Int = if (streamIsHealthy) 12 else 2

        private fun windowMessageIDs(`in`: List<OpenCodeTranscriptItem>): Set<String> =
            `in`.mapNotNull {
                when (it) {
                    is OpenCodeTranscriptItem.info, is OpenCodeTranscriptItem.sendFailure, is OpenCodeTranscriptItem.notice -> null
                    else -> messageID(it)
                }
            }.toSet()

        private fun containsMessage(messageID: String, `in`: List<OpenCodeTranscriptItem>): Boolean =
            `in`.any { item(it, belongsTo = messageID) }

        private fun item(item: OpenCodeTranscriptItem, belongsTo: String): Boolean {
            val messageID = belongsTo
            return when (item) {
                is OpenCodeTranscriptItem.userText, is OpenCodeTranscriptItem.compaction -> item.id == messageID
                is OpenCodeTranscriptItem.userImages -> item.id == "$messageID-images"
                is OpenCodeTranscriptItem.userFiles -> item.id == "$messageID-files"
                is OpenCodeTranscriptItem.tool -> item.call.messageID == messageID || item.id == messageID || item.id.startsWith("$messageID-")
                is OpenCodeTranscriptItem.assistantMarkdown, is OpenCodeTranscriptItem.info, is OpenCodeTranscriptItem.notice,
                is OpenCodeTranscriptItem.sendFailure,
                -> item.id == messageID || item.id.startsWith("$messageID-")
            }
        }
    }
}

private val SessionFeature.Action.voice.voiceDelegate: VoiceFeature.Delegate?
    get() = ((action as? PresentationAction.presented)?.action as? VoiceFeature.Action.delegate)?.action

/// `map[key]?.field = value`: changes the value at `key` when there is one.
private fun <K, V> Map<K, V>.updating(key: K, change: (V) -> V): Map<K, V> {
    val value = this[key] ?: return this
    return this + (key to change(value))
}

/// A permission or form awaiting an answer from some session.
interface PendingRequest {
    val id: String
    val sessionID: String
}

/// Case-insensitive multi-field filter used by the picker sheets.
fun <Element> List<Element>.filtered(by: String, fields: (Element) -> List<String>): List<Element> {
    val query = by.trim { it == '\t' || it.category == CharCategory.SPACE_SEPARATOR }
    if (query.isEmpty()) return this
    return filter { element ->
        fields(element).any { it.contains(query, ignoreCase = true) }
    }
}
