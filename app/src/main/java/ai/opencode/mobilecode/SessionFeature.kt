@file:PortedFrom("OpenCode/SessionFeature.swift", "553a407", partial = "State, Action, Delegate, CancelID, nested types, child wiring, mergeTranscript, PendingRequest, filtered(by:)")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.tca.BindableAction
import ai.opencode.mobilecode.tca.BindingAction
import ai.opencode.mobilecode.tca.BindingReducer
import ai.opencode.mobilecode.tca.Case
import ai.opencode.mobilecode.tca.Dependency
import ai.opencode.mobilecode.tca.DependencyValues
import ai.opencode.mobilecode.tca.Identifiable
import ai.opencode.mobilecode.tca.ObservableState
import ai.opencode.mobilecode.tca.PresentationAction
import ai.opencode.mobilecode.tca.Presents
import ai.opencode.mobilecode.tca.Reduce
import ai.opencode.mobilecode.tca.Reducer
import ai.opencode.mobilecode.tca.Shared
import ai.opencode.mobilecode.tca.SharedKey
import ai.opencode.mobilecode.tca.StoreVar
import ai.opencode.mobilecode.tca.combine
import ai.opencode.mobilecode.tca.continuousClock
import ai.opencode.mobilecode.tca.date
import ai.opencode.mobilecode.tca.ifLet
import ai.opencode.mobilecode.tca.uuid
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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

    override val body: Reducer<State, Action> =
        combine(
            BindingReducer(),
            Reduce<State, Action> { _ -> TODO("SessionFeature's reducer is ported in phase 2") }
                .ifLet(State::shellOutput, Action.shellOutput) {
                    ShellOutputFeature()
                }
                .ifLet(State::sessionSheet, Action.sessionSheet) {
                    SessionSheetFeature()
                }
                .ifLet(State::voice, Action.voice) {
                    VoiceFeature()
                },
        )

    private data class VoiceSessionName(
        val sessionID: String? = null,
        val title: String? = null,
    )

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
    }
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
