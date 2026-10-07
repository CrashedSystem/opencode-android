@file:PortedFrom("OpenCode/HomeFeature.swift", "9f4d56c")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.ProcessInfo
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
import ai.opencode.mobilecode.tca.Scope
import ai.opencode.mobilecode.tca.Shared
import ai.opencode.mobilecode.tca.SharedKey
import ai.opencode.mobilecode.tca.StoreVar
import ai.opencode.mobilecode.tca.combine
import ai.opencode.mobilecode.tca.continuousClock
import ai.opencode.mobilecode.tca.date
import ai.opencode.mobilecode.tca.ifLet
import ai.opencode.mobilecode.tca.cancellable
import ai.opencode.mobilecode.tca.onChange
import ai.opencode.mobilecode.tca.withLock
import java.time.Instant
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class HomeFeature : Reducer<HomeFeature.State, HomeFeature.Action> {
    class State(
        server: OpenCodeServer,
        snapshot: OpenCodeHomeSnapshot,
    ) : ObservableState() {
        var server by StoreVar(server)
        var projects by StoreVar(snapshot.projects)
        var sessions by StoreVar(snapshot.sessions)
        var activeSessionIDs by StoreVar(snapshot.activeSessionIDs)
        var activeSessionParents by StoreVar(snapshot.activeSessionParents ?: emptyMap())

        val busySessionIDs: Set<String>
            get() {
                val parents = activeSessionParents.toMutableMap()
                for (session in sessionDetail?.familySessions ?: emptyList()) {
                    session.parentID?.let { parents[session.id] = it }
                }
                return SessionActivity.ancestors(of = activeSessionIDs, parents = parents)
            }

        enum class ConnectionStatus { reconnecting, connected, disconnected }

        var connectionStatus by StoreVar(ConnectionStatus.connected)

        /// Set only when the event stream is refused with a 401.
        var connectionErrorMessage by StoreVar<String?>(null)

        /// Retrying the same credentials cannot help.
        val credentialsRejected: Boolean get() = connectionErrorMessage != null
        val isConnected: Boolean get() = connectionStatus == ConnectionStatus.connected

        /// This process has had a live event stream, so last-known rows are worth
        /// showing while it reconnects. A cached cold launch has not.
        var hasConnected by StoreVar(false)

        /// Set once `.task` owns the event stream, so a foreground retry can restart it.
        var isStreaming by StoreVar(false)
        var cachedHomeSnapshot by Shared(SharedKey.cachedHomeSnapshot)
        var search by StoreVar("")
        var transcriptCache by StoreVar<Map<String, List<OpenCodeTranscriptItem>>>(emptyMap())
        var cachedTranscriptSnapshots by Shared(SharedKey.cachedTranscriptSnapshots)
        var attentionSessionIDs by StoreVar<Set<String>>(emptySet())

        /// Runs that started since the current snapshot request was triggered.
        /// Its `/api/session/active` was read before these arrived, so they are
        /// re-applied over it; otherwise a run that started mid-request would
        /// vanish from Home until the next event. (A run that *ends* mid-request
        /// triggers a fresh request itself, so only starts need recording.)
        var runsStartedDuringRefresh by StoreVar<Set<String>>(emptySet())
        var pinnedSessionKeys by Shared(SharedKey.pinnedSessionKeys)
        var lastViewedSessionIDs by Shared(SharedKey.lastViewedSessionIDs)
        var sessionReadDates by Shared(SharedKey.sessionReadDates)
        var manuallyUnreadSessionKeys by Shared(SharedKey.manuallyUnreadSessionKeys)

        /// Device-local read receipts that have not appeared in an authoritative
        /// session snapshot yet. A stale list response must not resurrect unread.
        var locallyViewedMilliseconds by StoreVar<Map<String, Long>>(emptyMap())

        /// Mark All as Read receipts in flight, with what each session looked
        /// like before, so a failed receipt can put its unread state back.
        var markAllReceipts by StoreVar<Map<String, ReadBackup>>(emptyMap())

        /// Sends the server's session list has not reflected yet.
        var localActivity by StoreVar<Map<String, LocalActivity>>(emptyMap())

        data class LocalActivity(
            /// The server's `updatedAt` before the send. Any newer server time
            /// means it has seen the send, whatever the phone's clock says.
            val serverUpdatedAt: Long,
            val sentAt: Long,
        )

        var initializedInboxServerIDs by Shared(SharedKey.initializedInboxServerIDs)
        var pendingDeletion by StoreVar<OpenCodeSession?>(null)
        var deletionError by StoreVar<String?>(null)
        var recentlyDeletedIDs by StoreVar<Set<String>>(emptySet())

        enum class Sheet(val rawValue: String) : Identifiable<Sheet> {
            settings("settings"),
            ;

            override val id: Sheet get() = this
        }

        var activeSheet by StoreVar<Sheet?>(null)
        var sessionDetail by Presents<SessionFeature.State>()

        /// Slack-style catch-up: unread and waiting sessions as a card stack.
        var catchUp by StoreVar<CatchUp?>(null)
        var whatsNew by StoreVar(WhatsNewFeature.State())

        /// Shared items waiting for their session's composer to open.
        var sharedDrafts by StoreVar<Map<SharedDraftTarget, SharedComposerDraft>>(emptyMap())

        /// What the voice call's Live Activity last showed.
        var voiceCallActivity by StoreVar<VoiceCallActivityAttributes.ContentState?>(null)
        var problemReports by StoreVar(ProblemReportsFeature.State())

        /// Moves shared items into the open composer when it is their target.
        /// Catch Up cards are left alone: their composers are not kept.
        fun absorbSharedDrafts() {
            val detail = sessionDetail
            if (sharedDrafts.isEmpty() || catchUp != null || detail == null) return
            val key: SharedDraftTarget = detail.session?.let { SharedDraftTarget.session(it.id) } ?: SharedDraftTarget.newSession
            val shared = sharedDrafts[key] ?: return
            sharedDrafts -= key
            val projectID = shared.projectID
            if (detail.isDraft && projectID != null) {
                val project = detail.projects.firstOrNull { it.id == projectID } ?: projects.firstOrNull { it.id == projectID }
                if (project != null) sessionDetail?.draftProject = project
            }
            sessionDetail?.absorb(shared)
        }

        /// Projects ordered by the newest session they contain.
        val projectsByRecency: List<OpenCodeProject>
            get() {
                val lastUsed = HashMap<String, Long>()
                for (session in sessions) {
                    lastUsed[session.projectID] = maxOf(lastUsed[session.projectID] ?: Long.MIN_VALUE, session.updatedAt)
                }
                return projects.sortedByDescending { lastUsed[it.id] ?: Long.MIN_VALUE }
            }

        fun restoreInitialSession() {
            val rememberedID = lastViewedSessionIDs[server.persistenceID]
            val session = rememberedID?.let { id -> sessions.firstOrNull { it.id == id } }
            if (session != null) {
                markReadLocally(session)
                sessionDetail = makeDetail(session, cachedItems = transcriptCache[session.id])
            } else {
                ::lastViewedSessionIDs.withLock { value -= server.persistenceID }
                sessionDetail = makeDraft()
            }
        }

        /// Counts opened details so each presented `SessionFeature.State` has its
        /// own identity; the value is deterministic for tests.
        var detailGeneration by StoreVar(0)

        // Swift runs `init` once every stored property has its default. Kotlin
        // initializes in declaration order, so the init body follows the last one.
        init {
            restoreTranscriptCache()
            restoreInitialSession()
        }

        fun nextDetailID(): Int {
            detailGeneration += 1
            return detailGeneration
        }

        fun makeDetail(
            `for`: OpenCodeSession,
            cachedItems: List<OpenCodeTranscriptItem>? = null,
            externalTranscript: Boolean = false,
        ): SessionFeature.State {
            val session = `for`
            val activeVoice = sessionDetail?.voice?.takeIf { !it.phase.isTerminal }
            val navID = if (activeVoice != null) (sessionDetail?.navigationID ?: nextDetailID()) else nextDetailID()
            val detail = SessionFeature.State(
                server = server,
                session = session,
                cachedItems = cachedItems,
                initialTranscriptRequestIsExternal = externalTranscript,
                isRunning = activeSessionIDs.contains(session.id),
                navigationID = navID,
            )
            if (activeVoice != null) {
                activeVoice.retarget(to = session)
                detail.voice = activeVoice
            }
            // The screen swaps details in place without appearing again, so the
            // new one inherits whether it is on screen.
            detail.isVisible = sessionDetail?.isVisible ?: false
            return detail
        }

        fun makeDraft(): SessionFeature.State {
            val projects = projectsByRecency
            // A live call moves to the draft, where its first request creates the
            // session. Keeping the identity keeps the call's effects alive.
            val activeVoice = sessionDetail?.voice?.takeIf { !it.phase.isTerminal }
            val draft = SessionFeature.State(
                server = server,
                draftProject = projects.firstOrNull(),
                projects = projects,
                inheritedModel = lastUsedModel,
                navigationID = if (activeVoice != null) (sessionDetail?.navigationID ?: nextDetailID()) else nextDetailID(),
            )
            if (activeVoice != null) {
                val directory = draft.draftProject?.directory ?: sessionDetail?.session?.directory ?: activeVoice.target.directory
                activeVoice.retargetToDraft(directory = directory, model = draft.currentModel)
                draft.voice = activeVoice
            }
            draft.isVisible = sessionDetail?.isVisible ?: false
            return draft
        }

        fun persistSnapshot() {
            val cache = CachedHomeSnapshot(
                serverID = server.persistenceID,
                snapshot = OpenCodeHomeSnapshot(
                    projects = projects,
                    sessions = sessions,
                    activeSessionIDs = activeSessionIDs,
                    activeSessionParents = activeSessionParents.ifEmpty { null },
                ),
            )
            ::cachedHomeSnapshot.withLock { value = cache }
        }

        fun initializeInbox() {
            if (initializedInboxServerIDs.contains(server.persistenceID)) return
            ::sessionReadDates.withLock {
                for (session in sessions) value += (sessionKey(session.id) to session.updatedAt)
            }
            ::initializedInboxServerIDs.withLock { value += server.persistenceID }
        }

        fun restoreTranscriptCache() {
            if (ProcessInfo.processInfo.environment["OPENCODE_LAB_DISABLE_TRANSCRIPT_DISK_CACHE"] == "1") return
            val restored = cachedTranscriptSnapshots.validItems(serverID = server.persistenceID, sessions = sessions)
            transcriptCache = transcriptCache + restored.filterKeys { !transcriptCache.containsKey(it) }
            trimTranscriptCache()
        }

        fun publishTranscript(sessionID: String, items: List<OpenCodeTranscriptItem>): Boolean {
            val bounded = items.takeLast(SessionFeature.initialFetchLimit)
            if (transcriptCache[sessionID] == bounded) return false
            transcriptCache += (sessionID to bounded)
            trimTranscriptCache()
            return true
        }

        fun trimTranscriptCache() {
            if (transcriptCache.size <= HomeFeature.transcriptMemoryLimit) return
            val priority = listOfNotNull(sessionDetail?.session?.id) + (catchUp?.queue ?: emptyList()) + sessionsByPriority().map { it.id }
            val retained = priority.filter { transcriptCache[it] != null }.take(HomeFeature.transcriptMemoryLimit).toSet()
            transcriptCache = transcriptCache.filter { retained.contains(it.key) }
        }

        fun persistTranscriptCache() {
            if (ProcessInfo.processInfo.environment["OPENCODE_LAB_DISABLE_TRANSCRIPT_DISK_CACHE"] == "1") return
            val hot = sessionsByPriority().take(HomeFeature.prefetchCount).map { it.id }
            val desired = (hot + listOfNotNull(sessionDetail?.session?.id)).toSet()
            val entries = sessions.mapNotNull { session ->
                val items = transcriptCache[session.id]
                if (!desired.contains(session.id) || items == null) return@mapNotNull null
                CachedTranscriptSnapshots.Entry(
                    serverID = server.persistenceID,
                    sessionID = session.id,
                    updatedAt = session.updatedAt,
                    items = items,
                )
            }
            ::cachedTranscriptSnapshots.withLock { value = value.replaceEntries(server.persistenceID, entries) }
        }

        /// Sessions that need you or are running first, then newest.
        fun sessionsByPriority(): List<OpenCodeSession> {
            val busy = busySessionIDs
            fun isHot(session: OpenCodeSession): Boolean =
                attentionSessionIDs.contains(session.id) || busy.contains(session.id)
            return sessions.sortedWith { a, b ->
                val aHot = isHot(a)
                val bHot = isHot(b)
                if (aHot != bHot) return@sortedWith if (aHot) -1 else 1
                b.updatedAt.compareTo(a.updatedAt)
            }
        }

        val visibleSessions: List<OpenCodeSession>
            get() = sessions.filter { session ->
                (search.isEmpty() || session.title.contains(search, ignoreCase = true))
            }

        val pinnedSessions: List<OpenCodeSession>
            get() {
                val indexed = visibleSessions.associateBy { sessionKey(it.id) }
                return pinnedSessionKeys.mapNotNull { indexed[it] }
            }

        val recentSessions: List<OpenCodeSession>
            get() = visibleSessions.filter { !isPinned(it.id) }
                .sortedByDescending { it.updatedAt }

        /// Idle with something new, or waiting on the reader. Running sessions
        /// join once they finish.
        fun needsCatchUp(session: OpenCodeSession): Boolean =
            when (indicator(session)) {
                Indicator.attention, Indicator.unread -> true
                Indicator.running, Indicator.none -> false
            }

        /// Waiting sessions first, then newest.
        val catchUpSessions: List<OpenCodeSession>
            get() = sessions.filter(::needsCatchUp).sortedWith { a, b ->
                val aWaiting = attentionSessionIDs.contains(a.id)
                val bWaiting = attentionSessionIDs.contains(b.id)
                if (aWaiting != bWaiting) return@sortedWith if (aWaiting) -1 else 1
                b.updatedAt.compareTo(a.updatedAt)
            }

        /// Opens the stack's top card without marking it read: reading is the
        /// reader's swipe, not the card appearing.
        fun openCatchUpCard() {
            val id = catchUp?.queue?.firstOrNull() ?: return
            if (sessionDetail?.familyRootID == id && sessionDetail?.readsOnView == false) return
            val session = sessions.firstOrNull { it.id == id } ?: return
            val detail = makeDetail(session, cachedItems = transcriptCache[id])
            detail.readsOnView = false
            sessionDetail = detail
        }

        /// Follows reads, answers, and new activity from any device. The open
        /// card stays until the reader moves it along.
        fun reconcileCatchUp() {
            var catchUp = this.catchUp ?: return
            val byID = sessions.distinctBy { it.id }.associateBy { it.id }
            val front = catchUp.queue.firstOrNull()
            catchUp = catchUp.copy(
                queue = catchUp.queue.filter { id ->
                    val session = byID[id] ?: return@filter false
                    id == front || needsCatchUp(session)
                },
            )
            val queued = catchUp.queue.toSet()
            val handled = catchUp.handled.associateBy { it.sessionID }
            for (session in catchUpSessions) {
                if (queued.contains(session.id)) continue
                val card = handled[session.id]
                if (card != null && !card.isSuperseded(by = session, needsAttention = attentionSessionIDs.contains(session.id))) continue
                catchUp = catchUp.copy(queue = catchUp.queue + session.id)
            }
            if (catchUp == this.catchUp) return
            this.catchUp = catchUp
            openCatchUpCard()
        }

        fun markUnreadLocally(session: OpenCodeSession) {
            markAllReceipts -= session.id
            locallyViewedMilliseconds -= session.id
            ::sessionReadDates.withLock { value += (sessionKey(session.id) to session.updatedAt - 1_000) }
            if (session.idleMilliseconds != null) {
                ::manuallyUnreadSessionKeys.withLock { value += sessionKey(session.id) }
            }
        }

        fun readBackup(session: OpenCodeSession, idle: Long): ReadBackup {
            val key = sessionKey(session.id)
            return ReadBackup(
                idle = idle,
                viewedMilliseconds = sessions.firstOrNull { it.id == session.id }?.viewedMilliseconds,
                locallyViewedMilliseconds = locallyViewedMilliseconds[session.id],
                readDate = sessionReadDates[key],
                wasManuallyUnread = manuallyUnreadSessionKeys.contains(key),
            )
        }

        /// Undoes `markReadLocally` for a receipt the server refused.
        fun restoreRead(sessionID: String, from: ReadBackup) {
            val backup = from
            val key = sessionKey(sessionID)
            val index = sessions.indexOfFirst { it.id == sessionID }
            if (index >= 0 && sessions[index].viewedMilliseconds == maxOf(backup.viewedMilliseconds ?: 0L, backup.idle)) {
                sessions = sessions.toMutableList().also { it[index] = it[index].copy(viewedMilliseconds = backup.viewedMilliseconds) }
            }
            val local = backup.locallyViewedMilliseconds
            locallyViewedMilliseconds = if (local != null) locallyViewedMilliseconds + (sessionID to local) else locallyViewedMilliseconds - sessionID
            ::sessionReadDates.withLock {
                val readDate = backup.readDate
                value = if (readDate != null) value + (key to readDate) else value - key
            }
            if (backup.wasManuallyUnread) {
                ::manuallyUnreadSessionKeys.withLock { value += key }
            }
        }

        fun noteViewAcknowledged(sessionID: String, idle: Long) {
            val index = sessions.indexOfFirst { it.id == sessionID }
            if (index >= 0) {
                sessions = sessions.toMutableList().also {
                    it[index] = it[index].copy(viewedMilliseconds = maxOf(it[index].viewedMilliseconds ?: 0L, idle))
                }
            }
            ::manuallyUnreadSessionKeys.withLock { value -= sessionKey(sessionID) }
        }

        fun sessionKey(id: String): String = "${server.persistenceID}|$id"

        fun isPinned(id: String): Boolean = pinnedSessionKeys.contains(sessionKey(id))

        fun isUnread(session: OpenCodeSession): Boolean {
            if (manuallyUnreadSessionKeys.contains(sessionKey(session.id))) return true
            val idle = session.idleMilliseconds
            if (idle != null) {
                return idle > (session.viewedMilliseconds ?: -1L)
            }
            return sessionReadDates[sessionKey(session.id)]?.let { it < session.updatedAt } ?: true
        }

        /// Moves a session to the top of Recents the moment the reader sends,
        /// rather than when the next list refresh carries the server's time. The
        /// reader's own send must not make the chat look unread.
        fun noteLocalActivity(sessionID: String, at: Instant) {
            val date = at.toEpochMilli()
            val index = sessions.indexOfFirst { it.id == sessionID }
            if (index < 0 || sessions[index].updatedAt >= date) return
            val serverUpdatedAt = localActivity[sessionID]?.serverUpdatedAt ?: sessions[index].updatedAt
            localActivity += (sessionID to LocalActivity(serverUpdatedAt = serverUpdatedAt, sentAt = date))
            sessions = sessions.toMutableList().also { it[index] = it[index].copy(updatedAt = date) }
            ::sessionReadDates.withLock { value += (sessionKey(sessionID) to date) }
        }

        fun markReadLocally(session: OpenCodeSession) {
            val key = sessionKey(session.id)
            markAllReceipts -= session.id
            val index = sessions.indexOfFirst { it.id == session.id }
            val idle = session.idleMilliseconds
            if (index >= 0 && idle != null) {
                sessions = sessions.toMutableList().also {
                    it[index] = it[index].copy(viewedMilliseconds = maxOf(it[index].viewedMilliseconds ?: 0L, idle))
                }
                locallyViewedMilliseconds += (session.id to maxOf(locallyViewedMilliseconds[session.id] ?: 0L, idle))
            }
            ::sessionReadDates.withLock { value += (key to session.updatedAt) }
            ::manuallyUnreadSessionKeys.withLock { value -= key }
        }

        /// Forgets a session the server has deleted: its row and every
        /// phone-local record keyed by it.
        fun purgeSession(sessionID: String) {
            sessions = sessions.filterNot { it.id == sessionID }
            activeSessionIDs -= sessionID
            attentionSessionIDs -= sessionID
            runsStartedDuringRefresh -= sessionID
            locallyViewedMilliseconds -= sessionID
            localActivity -= sessionID
            transcriptCache -= sessionID
            val serverID = server.persistenceID
            ::cachedTranscriptSnapshots.withLock { value = value.removeEntry(serverID = serverID, sessionID = sessionID) }
            val key = sessionKey(sessionID)
            ::pinnedSessionKeys.withLock { value = value.filterNot { it == key } }
            ::sessionReadDates.withLock { value -= key }
            ::manuallyUnreadSessionKeys.withLock { value -= key }
            if (lastViewedSessionIDs[serverID] == sessionID) {
                ::lastViewedSessionIDs.withLock { value -= serverID }
            }
        }

        enum class Indicator {
            attention,
            running,
            unread,
            none,
            ;

            /// The gauntlet pairs fixture actions with these names; unread and none
            /// are both "idle" because a finished run reads either way.
            val labMarkName: String
                get() = when (this) {
                    attention -> "attention"
                    running -> "running"
                    unread, none -> "idle"
                }
        }

        fun indicator(`for`: OpenCodeSession): Indicator {
            val session = `for`
            // Reconnecting keeps the last-known state on screen when this process
            // has actually had a live stream; a routine background/foreground cycle
            // then doesn't blank every row. A cached cold launch or a failed refresh
            // has nothing trustworthy to show.
            if (!(isConnected || (connectionStatus == ConnectionStatus.reconnecting && hasConnected))) return Indicator.none
            if (attentionSessionIDs.contains(session.id)) return Indicator.attention
            if (busySessionIDs.contains(session.id)) return Indicator.running
            if (isUnread(session)) return Indicator.unread
            return Indicator.none
        }

        val sessionIndicators: Map<String, Indicator>
            get() = sessions.associate { it.id to indicator(it) }
    }

    /// The row a pending deletion hid. Phone-local data (pins, read state,
    /// cached transcripts) is untouched until the server confirms, so a failure
    /// only needs to put the row back.
    data class DeletionBackup(
        val session: OpenCodeSession,
        val sessionIndex: Int,
        val wasActive: Boolean,
        val neededAttention: Boolean,
        val runStartedDuringRefresh: Boolean,
        /// The draft that replaced the open session, if it was open.
        val replacementDraftID: Int? = null,
        val openItems: List<OpenCodeTranscriptItem>,
    )

    data class CatchUp(
        /// Sessions still to show; the first is the open card.
        val queue: List<String>,
        /// Cards already moved along, newest last, for Undo.
        val handled: List<Handled> = emptyList(),
        /// Where the reader was, restored on leaving.
        val returnSessionID: String? = null,
        val returnComposer: SessionFeature.State.DraftState = SessionFeature.State.DraftState(),
    ) {
        data class Handled(
            val sessionID: String,
            val disposition: CatchUpDisposition,
            val idleMilliseconds: Long? = null,
            val updatedAt: Long,
            val neededAttention: Boolean,
        ) {
            /// New activity since the card was handled brings it back this pass.
            fun isSuperseded(by: OpenCodeSession, needsAttention: Boolean): Boolean {
                val session = by
                if (needsAttention && !neededAttention) return true
                val idle = session.idleMilliseconds
                val handledIdle = idleMilliseconds
                if (idle != null && handledIdle != null) return idle > handledIdle
                return session.updatedAt > updatedAt
            }
        }
    }

    enum class CatchUpDisposition { markRead, keepUnread }

    data class ReadBackup(
        /// The idle watermark the receipt acknowledges.
        val idle: Long,
        val viewedMilliseconds: Long? = null,
        val locallyViewedMilliseconds: Long? = null,
        val readDate: Long? = null,
        val wasManuallyUnread: Boolean,
    )

    sealed interface Action : BindableAction<State> {
        data class binding(override val binding: BindingAction<State>) : Action

        data object catchUpTapped : Action

        data class catchUpCardDismissed(val sessionID: String, val disposition: CatchUpDisposition) : Action

        data object catchUpUndoTapped : Action

        data object catchUpExitTapped : Action

        /// The open card's title: leaves the stack for that session.
        data object catchUpOpenTapped : Action

        /// The drawer row's menu: reads every unread session, leaving waiting ones.
        data object catchUpMarkAllReadTapped : Action

        data class markAllReceiptResponse(val sessionID: String, val idle: Long, val succeeded: Boolean) : Action

        data class whatsNew(val action: WhatsNewFeature.Action) : Action {
            companion object : Case<Action, WhatsNewFeature.Action>({ whatsNew(it) }, { (it as? whatsNew)?.action })
        }

        data object task : Action

        data object transcriptCacheFlushRequested : Action

        data object connectionLost : Action

        data class eventStreamFailed(val error: OpenCodeClientError) : Action

        data class serverConnected(val server: OpenCodeServer) : Action

        data object snapshotFailed : Action

        data object retryConnectionTapped : Action

        data class serverEvent(val event: OpenCodeServerEvent) : Action

        /// Handled by AppFeature: returns to the server picker.
        data object serverBadgeTapped : Action

        data object settingsTapped : Action

        data class sessionDrawerSettled(val isOpen: Boolean) : Action

        data object newSessionTapped : Action

        data class sessionTapped(val sessionID: String) : Action

        data class togglePin(val sessionID: String) : Action

        /// Native list reordering, in offsets of the displayed pinned rows.
        data class pinsMoved(val fromOffsets: Set<Int>, val toOffset: Int) : Action

        data class movePinUp(val id: String) : Action

        data class movePinDown(val id: String) : Action

        data class deleteSessionTapped(val sessionID: String) : Action

        data object deleteSessionCancelled : Action

        data object deleteSessionConfirmed : Action

        data class deleteSessionResponse(val sessionID: String, val backup: DeletionBackup, val error: OpenCodeClientError?) : Action

        data object clearDeletionError : Action

        data class markRead(val sessionID: String) : Action

        data class viewAcknowledged(val sessionID: String, val idle: Long) : Action

        data class markUnread(val sessionID: String) : Action

        data class snapshotRefreshed(val snapshot: OpenCodeHomeSnapshot) : Action

        data class attentionRefreshed(val sessionIDs: Set<String>) : Action

        data class sessionDetail(val action: PresentationAction<SessionFeature.Action>) : Action {
            companion object : Case<Action, PresentationAction<SessionFeature.Action>>({ sessionDetail(it) }, { (it as? sessionDetail)?.action })
        }

        /// Items from the share extension, oldest first, all for this server.
        data class sharesReceived(val shares: List<ReceivedShare>) : Action

        data class transcriptPrefetched(val sessionID: String, val items: List<OpenCodeTranscriptItem>) : Action

        data class sessionNavigationReady(val sessionID: String) : Action

        data class selectedTranscriptResponse(val sessionID: String, val result: Result<List<OpenCodeTranscriptItem>>) : Action

        data class voiceCallCommand(val command: VoiceCallCommand) : Action

        data class problemReports(val action: ProblemReportsFeature.Action) : Action {
            companion object : Case<Action, ProblemReportsFeature.Action>({ problemReports(it) }, { (it as? problemReports)?.action })
        }
    }

    private sealed interface CancelID {
        data object events : CancelID

        data object refresh : CancelID

        data object attention : CancelID

        data object prefetch : CancelID

        data object selectedTranscript : CancelID

        data object deletionError : CancelID

        data object voiceCallCommands : CancelID

        data class deleteSession(val id: String) : CancelID

        data class viewReceipt(val id: String) : CancelID

        data object markAllReceipts : CancelID
    }

    /// A view receipt for one session: its idle watermark.
    private data class Receipt(val sessionID: String, val idle: Long)

    private val openCodeClient by Dependency(DependencyValues::openCodeClient)
    private val clock by Dependency(DependencyValues::continuousClock)
    private val now by Dependency { it.date.now }
    private val haptics by Dependency(DependencyValues::haptics)
    private val transcriptCachePersistence by Dependency(DependencyValues::transcriptCachePersistence)
    private val shareInbox by Dependency(DependencyValues::shareInbox)
    private val voiceLiveActivity by Dependency(DependencyValues::voiceLiveActivity)

    override val body: Reducer<State, Action> =
        combine(
            BindingReducer(),
            ProblemReportTriggers(),
            Scope(state = State::problemReports, action = Action.problemReports) { ProblemReportsFeature() },
            Scope(state = State::whatsNew, action = Action.whatsNew) {
                WhatsNewFeature()
            },
            Reduce<State, Action> { action ->
                try {
                    when (action) {
                        Action.catchUpTapped -> {
                            val queue = state.catchUpSessions.map { it.id }
                            if (queue.isEmpty()) return@Reduce Effect.none
                            val detail = state.sessionDetail
                            state.catchUp = CatchUp(
                                queue = queue,
                                returnSessionID = if (detail?.isDraft == false) detail.familyRootID else null,
                                returnComposer = detail?.draftState ?: SessionFeature.State.DraftState(),
                            )
                            state.openCatchUpCard()
                            Effect.merge(Effect.cancel(CancelID.selectedTranscript), prefetchTranscripts(state))
                        }

                        is Action.catchUpCardDismissed -> {
                            val sessionID = action.sessionID
                            val disposition = action.disposition
                            val catchUp = state.catchUp
                            var session = state.sessions.firstOrNull { it.id == sessionID }
                            if (catchUp == null || catchUp.queue.firstOrNull() != sessionID || session == null) return@Reduce Effect.none
                            // The card may have observed a newer idle watermark than the list.
                            val observed = state.sessionDetail?.session
                            if (observed != null && observed.id == sessionID &&
                                (observed.idleMilliseconds ?: -1) > (session.idleMilliseconds ?: -1)
                            ) {
                                session = session.copy(idleMilliseconds = observed.idleMilliseconds)
                            }
                            state.catchUp = catchUp.copy(
                                queue = catchUp.queue.drop(1),
                                handled = catchUp.handled + CatchUp.Handled(
                                    sessionID = sessionID,
                                    disposition = disposition,
                                    idleMilliseconds = session.idleMilliseconds,
                                    updatedAt = session.updatedAt,
                                    neededAttention = state.attentionSessionIDs.contains(sessionID),
                                ),
                            )
                            state.openCatchUpCard()
                            // The card's flight plays its own haptic (CatchUpStackController).
                            if (disposition != CatchUpDisposition.markRead) return@Reduce prefetchTranscripts(state)
                            state.markReadLocally(session)
                            Effect.merge(acknowledgeView(session, server = state.server), prefetchTranscripts(state))
                        }

                        Action.catchUpUndoTapped -> {
                            val catchUp = state.catchUp
                            val card = catchUp?.handled?.lastOrNull() ?: return@Reduce Effect.none
                            state.catchUp = catchUp.copy(
                                queue = listOf(card.sessionID) + catchUp.queue.filter { it != card.sessionID },
                                handled = catchUp.handled.dropLast(1),
                            )
                            haptics.selection()
                            if (card.disposition == CatchUpDisposition.markRead) {
                                state.sessions.firstOrNull { it.id == card.sessionID }?.let { state.markUnreadLocally(it) }
                            }
                            state.openCatchUpCard()
                            // A receipt still in flight would otherwise clear the restored unread.
                            Effect.cancel(CancelID.viewReceipt(card.sessionID))
                        }

                        Action.catchUpExitTapped -> {
                            val catchUp = state.catchUp ?: return@Reduce Effect.none
                            state.catchUp = null
                            val session = catchUp.returnSessionID?.let { id -> state.sessions.firstOrNull { it.id == id } }
                            if (session != null) {
                                state.sessionDetail = state.makeDetail(session, cachedItems = state.transcriptCache[session.id])
                            } else {
                                state.sessionDetail = state.makeDraft()
                            }
                            if (catchUp.returnComposer != SessionFeature.State.DraftState()) {
                                state.sessionDetail?.restoreDraft(catchUp.returnComposer)
                            }
                            Effect.none
                        }

                        Action.catchUpOpenTapped -> {
                            val catchUp = state.catchUp
                            val detail = state.sessionDetail
                            val rootID = detail?.familyRootID
                            if (catchUp == null || detail == null || rootID == null || catchUp.queue.firstOrNull() != rootID) {
                                return@Reduce Effect.none
                            }
                            var root = state.sessions.firstOrNull { it.id == rootID } ?: return@Reduce Effect.none
                            val shown = detail.session
                            // The card may have observed a newer idle watermark than the list.
                            val observed = if (shown?.id == rootID) shown else detail.familySessions.firstOrNull { it.id == rootID }
                            if (observed != null && (observed.idleMilliseconds ?: -1) > (root.idleMilliseconds ?: -1)) {
                                root = root.copy(idleMilliseconds = observed.idleMilliseconds)
                            }
                            // The card already is the session: keep it, its draft, and any
                            // subagent the reader opened, and let it read as any open session.
                            state.catchUp = null
                            state::lastViewedSessionIDs.withLock { value += (state.server.persistenceID to rootID) }
                            state.markReadLocally(root)
                            state.sessionDetail?.readsOnView = true
                            state.sessionDetail?.markViewed(root)
                            val receipts = mutableListOf(acknowledgeView(root, server = state.server))
                            // A subagent on screen reads too, as it would had it been picked.
                            val idle = shown?.idleMilliseconds
                            if (shown != null && shown.id != rootID && idle != null && idle > (shown.viewedMilliseconds ?: -1)) {
                                state.sessionDetail?.markViewed(shown)
                                receipts += acknowledgeShownSubagent(shown.id, idle = idle, server = state.server)
                            }
                            Effect.merge(receipts)
                        }

                        Action.catchUpMarkAllReadTapped -> {
                            // Waiting sessions keep their unread state as well as their request:
                            // what led up to the question is still unseen.
                            val unread = state.sessions.filter { state.indicator(it) == State.Indicator.unread }
                            if (unread.isEmpty()) return@Reduce Effect.none
                            haptics.selection()
                            val receipts = mutableListOf<Receipt>()
                            val backups = mutableMapOf<String, ReadBackup>()
                            for (session in unread) {
                                val idle = session.idleMilliseconds
                                if (idle != null) {
                                    backups[session.id] = state.readBackup(session, idle = idle)
                                    receipts += Receipt(session.id, idle)
                                }
                                state.markReadLocally(session)
                            }
                            state.markAllReceipts += backups
                            state.reconcileCatchUp()
                            acknowledgeViews(receipts, server = state.server)
                        }

                        is Action.markAllReceiptResponse -> {
                            // Reading or unreading the session since then supersedes the receipt.
                            val backup = state.markAllReceipts[action.sessionID]
                            if (backup == null || backup.idle != action.idle) return@Reduce Effect.none
                            state.markAllReceipts -= action.sessionID
                            if (action.succeeded) {
                                state.noteViewAcknowledged(sessionID = action.sessionID, idle = action.idle)
                            } else {
                                state.restoreRead(action.sessionID, from = backup)
                                state.reconcileCatchUp()
                            }
                            Effect.none
                        }

                        Action.settingsTapped -> {
                            state.activeSheet = State.Sheet.settings
                            Effect.none
                        }

                        is Action.sessionDrawerSettled -> {
                            haptics.drawer()
                            Effect.none
                        }

                        is Action.whatsNew -> Effect.none

                        is Action.sessionDetail if action.presented?.voiceDelegate == VoiceFeature.Delegate.checkAPIKey -> {
                            state.activeSheet = State.Sheet.settings
                            Effect.none
                        }

                        is Action.sessionDetail if action.presented == SessionFeature.Action.sendTapped -> {
                            // The session reducer has already run: an accepted send cleared the
                            // draft and is awaiting its reply. Recents lists roots.
                            val detail = state.sessionDetail
                            val sessionID = detail?.familyRootID ?: detail?.session?.id
                            if (detail == null || !detail.isAwaitingReply || detail.draft.isNotEmpty() || sessionID == null) {
                                return@Reduce Effect.none
                            }
                            state.noteLocalActivity(sessionID = sessionID, at = now)
                            Effect.none
                        }

                        Action.task -> {
                            LabMarks.mark("home_task_started")
                            if (state.isConnected) state.initializeInbox()
                            state.isStreaming = true
                            val server = state.server
                            val voiceLiveActivity = voiceLiveActivity
                            Effect.merge(
                                eventStream(server),
                                if (state.isConnected) refreshAttention(state) else refreshSnapshot(state),
                                Effect.run<Action> { send ->
                                    voiceLiveActivity.commands().collect { command ->
                                        send(Action.voiceCallCommand(command))
                                    }
                                }.cancellable(CancelID.voiceCallCommands, cancelInFlight = true),
                            )
                        }

                        is Action.voiceCallCommand -> {
                            val voice = state.sessionDetail?.voice
                            if (voice == null) {
                                // No call owns the activity the tap came from.
                                state.voiceCallActivity = null
                                val voiceLiveActivity = voiceLiveActivity
                                return@Reduce Effect.run { _ -> voiceLiveActivity.update(null, null) }
                            }
                            // An ended call can still be in state while its bar sweeps out.
                            if (voice.phase.isTerminal) return@Reduce Effect.none
                            when (action.command) {
                                VoiceCallCommand.end -> Effect.send(voiceAction(VoiceFeature.Action.endTapped))
                                VoiceCallCommand.toggleMute -> Effect.send(voiceAction(VoiceFeature.Action.muteTapped))
                            }
                        }

                        Action.transcriptCacheFlushRequested -> {
                            // Leaving the app is when the share sheet becomes reachable.
                            shareInbox.publishTargets(state.shareTargets)
                            val transcriptCachePersistence = transcriptCachePersistence
                            Effect.run { _ -> transcriptCachePersistence.flush() }
                        }

                        is Action.sharesReceived -> {
                            if (action.shares.isEmpty()) return@Reduce Effect.none
                            var newestTarget: SharedDraftTarget = SharedDraftTarget.newSession
                            for (share in action.shares) {
                                val (target, projectID) = state.resolvedShareTarget(share.handoff.target)
                                val draft = (state.sharedDrafts[target] ?: SharedComposerDraft()).copy()
                                draft.append(SharedComposerDraft(share, projectID = projectID))
                                state.sharedDrafts += (target to draft)
                                newestTarget = target
                            }
                            state.catchUp = null
                            haptics.success()
                            when (val target = newestTarget) {
                                is SharedDraftTarget.session -> Effect.send(Action.sessionTapped(target.id))
                                SharedDraftTarget.newSession -> {
                                    if (state.sessionDetail?.isDraft != true) state.sessionDetail = state.makeDraft()
                                    Effect.none
                                }
                            }
                        }

                        is Action.serverConnected -> {
                            state.server = action.server
                            state.hasConnected = true
                            state.connectionErrorMessage = null
                            Effect.merge(
                                Effect.send(Action.serverEvent(OpenCodeServerEvent.connected)),
                                if (state.sessionDetail == null) {
                                    Effect.none
                                } else {
                                    Effect.send(sessionAction(SessionFeature.Action.eventStreamConnected(action.server)))
                                },
                            )
                        }

                        is Action.eventStreamFailed -> {
                            state.connectionErrorMessage = action.error.message
                            state.connectionStatus = State.ConnectionStatus.disconnected
                            requestsDisconnected(state)
                        }

                        Action.connectionLost -> {
                            // A live stream dropping is a reconnect (the loop retries in a second;
                            // keep last-known rows meanwhile). Failing again is an outage.
                            state.connectionStatus = if (state.connectionStatus == State.ConnectionStatus.connected) {
                                State.ConnectionStatus.reconnecting
                            } else {
                                State.ConnectionStatus.disconnected
                            }
                            Effect.merge(
                                Effect.cancel(CancelID.refresh),
                                Effect.cancel(CancelID.attention),
                                requestsDisconnected(state),
                            )
                        }

                        Action.snapshotFailed -> {
                            state.connectionStatus = State.ConnectionStatus.disconnected
                            requestsDisconnected(state)
                        }

                        Action.retryConnectionTapped -> {
                            if (state.credentialsRejected) return@Reduce Effect.none
                            if (!state.isConnected) state.connectionStatus = State.ConnectionStatus.reconnecting
                            val server = state.server
                            // Foregrounding restarts the stream rather than trusting a socket that
                            // may have died while the phone slept; its `.connected` refresh
                            // coalesces with the one requested here.
                            Effect.merge(
                                if (state.isStreaming) eventStream(server) else Effect.none,
                                refreshSnapshot(state),
                            )
                        }

                        is Action.serverEvent -> when (val event = action.event) {
                            OpenCodeServerEvent.sessionViewChanged -> refreshSnapshot(state)

                            OpenCodeServerEvent.connected, OpenCodeServerEvent.sessionCreated -> {
                                val refresh = refreshSnapshot(state)
                                if (state.sessionDetail == null) return@Reduce refresh
                                Effect.merge(refresh, Effect.send(sessionAction(SessionFeature.Action.familyRefreshRequested)))
                            }

                            is OpenCodeServerEvent.contentBatch -> {
                                if (state.sessionDetail?.session?.id != event.sessionID) return@Reduce Effect.none
                                Effect.send(sessionAction(SessionFeature.Action.contentBatch(sessionID = event.sessionID, events = event.events)))
                            }

                            is OpenCodeServerEvent.messageInvalidated -> {
                                if (state.sessionDetail?.session?.id != event.sessionID) return@Reduce Effect.none
                                Effect.send(sessionAction(SessionFeature.Action.messageInvalidated(sessionID = event.sessionID)))
                            }

                            is OpenCodeServerEvent.inputQueueChanged -> {
                                // Queued work lives outside projected history, so only the open
                                // session cares; the list already reorders on execution events.
                                if (state.sessionDetail?.session?.id != event.sessionID) return@Reduce Effect.none
                                Effect.send(sessionAction(SessionFeature.Action.inputQueueChanged))
                            }

                            is OpenCodeServerEvent.inputEnqueued -> {
                                if (state.sessionDetail?.session?.id != event.sessionID) return@Reduce Effect.none
                                Effect.send(sessionAction(SessionFeature.Action.inputEnqueued))
                            }

                            is OpenCodeServerEvent.inputCancelled -> {
                                if (state.sessionDetail?.session?.id != event.sessionID) return@Reduce Effect.none
                                Effect.send(sessionAction(SessionFeature.Action.inputCancelled(event.inputID)))
                            }

                            is OpenCodeServerEvent.attentionChanged -> {
                                // A request on a root we already list can light up now; the
                                // authoritative refetch settles child-owned requests and removals.
                                val askedSessionID = event.askedSessionID
                                if (askedSessionID != null && state.sessions.any { it.id == askedSessionID }) {
                                    state.attentionSessionIDs += askedSessionID
                                }
                                Effect.merge(
                                    refreshAttention(state),
                                    if (state.sessionDetail?.session == null) Effect.none else Effect.send(sessionAction(SessionFeature.Action.requestsChanged)),
                                )
                            }

                            is OpenCodeServerEvent.shellStarted -> {
                                val sessionID = event.shell.sessionID
                                if (sessionID == null || state.sessionDetail?.session?.id != sessionID) return@Reduce Effect.none
                                Effect.send(sessionAction(SessionFeature.Action.shellStarted(event.shell)))
                            }

                            is OpenCodeServerEvent.shellEnded -> {
                                if (state.sessionDetail == null) return@Reduce Effect.none
                                Effect.send(sessionAction(SessionFeature.Action.shellEnded(event.shellID)))
                            }

                            is OpenCodeServerEvent.executionStarted -> {
                                val sessionID = event.sessionID
                                state.activeSessionIDs += sessionID
                                state.runsStartedDuringRefresh += sessionID
                                if (state.sessionDetail?.familySessions?.any { it.id == sessionID } == true) {
                                    return@Reduce Effect.send(sessionAction(SessionFeature.Action.familyRunStateChanged(sessionID = sessionID, isRunning = true)))
                                }
                                // A newly spawned child may not be discovered yet. Refresh metadata
                                // so its ancestors light up even when that parent isn't open.
                                if (state.sessions.any { it.id == sessionID }) Effect.none else refreshSnapshot(state)
                            }

                            is OpenCodeServerEvent.executionEnded -> {
                                val sessionID = event.sessionID
                                state.activeSessionIDs -= sessionID
                                // Refetch for updatedAt/ordering; if the finished run belongs to
                                // the open session, stop its working indicator and pull the reply.
                                if (state.sessionDetail?.familySessions?.any { it.id == sessionID } == true) {
                                    return@Reduce Effect.merge(
                                        refreshSnapshot(state),
                                        Effect.send(sessionAction(SessionFeature.Action.familyRunStateChanged(sessionID = sessionID, isRunning = false))),
                                    )
                                }
                                refreshSnapshot(state)
                            }

                            is OpenCodeServerEvent.sessionRenamed -> {
                                val sessionID = event.sessionID
                                val index = state.sessions.indexOfFirst { it.id == sessionID }
                                if (index >= 0) {
                                    state.sessions = state.sessions.toMutableList().also { it[index] = it[index].copy(title = event.title) }
                                }
                                val detail = state.sessionDetail
                                if (detail == null ||
                                    !(detail.familySessions.any { it.id == sessionID } || detail.voice?.target?.sessionID == sessionID)
                                ) {
                                    return@Reduce Effect.none
                                }
                                Effect.send(sessionAction(SessionFeature.Action.familySessionRenamed(sessionID = sessionID, title = event.title)))
                            }

                            is OpenCodeServerEvent.sessionDeleted -> {
                                val sessionID = event.sessionID
                                state.purgeSession(sessionID)
                                state.recentlyDeletedIDs -= sessionID
                                val detail = state.sessionDetail
                                if (detail == null || !detail.familySessions.any { it.id == sessionID }) return@Reduce Effect.none
                                if (detail.familyRootID == sessionID) {
                                    state.sessionDetail = state.makeDraft()
                                    return@Reduce Effect.none
                                }
                                Effect.send(sessionAction(SessionFeature.Action.familySessionDeleted(sessionID)))
                            }
                        }

                        is Action.sessionDetail if action.presented is SessionFeature.Action.viewAcknowledged -> {
                            val acknowledged = action.presented as SessionFeature.Action.viewAcknowledged
                            state.noteViewAcknowledged(sessionID = acknowledged.sessionID, idle = acknowledged.idle)
                            Effect.none
                        }

                        is Action.viewAcknowledged -> {
                            state.noteViewAcknowledged(sessionID = action.sessionID, idle = action.idle)
                            Effect.none
                        }

                        is Action.sessionDetail if action.presented is SessionFeature.Action.familyPicked -> {
                            val sessionID = (action.presented as SessionFeature.Action.familyPicked).id
                            val session = state.sessionDetail?.familySessions?.firstOrNull { it.id == sessionID }
                            if (state.sessionDetail?.readsOnView == true && session != null) {
                                state.markReadLocally(session)
                            }
                            Effect.none
                        }

                        is Action.sessionDetail if action.presented is SessionFeature.Action.acceptedTranscriptUpdated -> {
                            val accepted = action.presented as SessionFeature.Action.acceptedTranscriptUpdated
                            if (state.publishTranscript(sessionID = accepted.sessionID, items = accepted.items)) {
                                state.persistTranscriptCache()
                            }
                            prefetchTranscripts(state)
                        }

                        is Action.snapshotRefreshed -> {
                            val snapshot = action.snapshot.copy()
                            val wasConnected = state.isConnected
                            val incomingSessionIDs = snapshot.sessions.map { it.id }.toSet()
                            state.recentlyDeletedIDs = state.recentlyDeletedIDs.filter(incomingSessionIDs::contains).toSet()
                            snapshot.sessions = snapshot.sessions.filterNot { state.recentlyDeletedIDs.contains(it.id) }
                            snapshot.activeSessionIDs = snapshot.activeSessionIDs - state.recentlyDeletedIDs
                            snapshot.activeSessionIDs = snapshot.activeSessionIDs + state.runsStartedDuringRefresh
                            state.runsStartedDuringRefresh = emptySet()
                            val previousUpdatedAt = state.sessions.associate { it.id to it.updatedAt }
                            val sessions = snapshot.sessions.toMutableList()
                            for (index in sessions.indices) {
                                val sessionID = sessions[index].id
                                val local = state.localActivity[sessionID]
                                if (local != null) {
                                    if (sessions[index].updatedAt > local.serverUpdatedAt) {
                                        state.localActivity -= sessionID
                                    } else {
                                        sessions[index] = sessions[index].copy(updatedAt = local.sentAt)
                                    }
                                }
                                val viewed = state.locallyViewedMilliseconds[sessionID] ?: continue
                                if ((sessions[index].viewedMilliseconds ?: -1) >= viewed) {
                                    state.locallyViewedMilliseconds -= sessionID
                                } else {
                                    sessions[index] = sessions[index].copy(viewedMilliseconds = viewed)
                                }
                            }
                            snapshot.sessions = sessions.toList()
                            state.projects = snapshot.projects
                            // The list is one page of the newest roots, not the whole server.
                            // Whatever is open or pinned and fell off that page is unknown, not
                            // gone — deletion arrives as its own event — so keep the last copy
                            // instead of kicking the reader to a draft or silently dropping a pin.
                            val openRootID = state.sessionDetail?.familyRootID
                            val carried = state.sessions.filter { session ->
                                !incomingSessionIDs.contains(session.id) &&
                                    !state.recentlyDeletedIDs.contains(session.id) &&
                                    (session.id == openRootID || state.isPinned(session.id))
                            }
                            state.sessions = snapshot.sessions + carried
                            state.activeSessionIDs = snapshot.activeSessionIDs
                            state.activeSessionParents = snapshot.activeSessionParents ?: emptyMap()
                            if (state.sessionDetail?.session != null) {
                                state.sessionDetail?.activeFamilySessionIDs = snapshot.activeSessionIDs
                            }
                            val selectedID = state.sessionDetail?.session?.id
                            val selected = selectedID?.let { id -> state.sessions.firstOrNull { it.id == id } }
                            if (state.sessionDetail?.isVisible == true && state.sessionDetail?.readsOnView == true && selected != null) {
                                state.markReadLocally(selected)
                            }
                            val openID = state.sessionDetail?.familyRootID
                            if (openID != null && !state.sessions.any { it.id == openID }) {
                                state::lastViewedSessionIDs.withLock { value -= state.server.persistenceID }
                                state.sessionDetail = state.makeDraft()
                            } else if (state.sessionDetail?.isDraft == true) {
                                // A draft opened before the first snapshot (cold launch, empty
                                // cache) has no folders to offer; refresh its choices as the list
                                // arrives so the Project picker is never empty on a live server.
                                val projects = state.projectsByRecency
                                state.sessionDetail?.projects = projects
                                if (state.sessionDetail?.draftProject == null) {
                                    state.sessionDetail?.draftProject = projects.firstOrNull()
                                }
                            }
                            val currentUpdatedAt = state.sessions.associate { it.id to it.updatedAt }
                            state.transcriptCache = state.transcriptCache.filter { (sessionID, _) ->
                                val current = currentUpdatedAt[sessionID] ?: return@filter false
                                previousUpdatedAt[sessionID]?.let { it == current } ?: true
                            }
                            state.restoreTranscriptCache()
                            state.connectionStatus = State.ConnectionStatus.connected
                            state.connectionErrorMessage = null
                            state.server.remember()
                            state.initializeInbox()
                            val attention = refreshAttention(state)
                            val prefetch = prefetchTranscripts(state)
                            // A whole run can start and finish while disconnected. Even idle →
                            // idle must revalidate the transcript, not just the working indicator.
                            val detail = state.sessionDetail
                            val openSessionID = detail?.session?.id
                            if (detail != null && openSessionID != null) {
                                val update = snapshot.sessions.firstOrNull { it.id == openSessionID }?.let {
                                    Effect.send(sessionAction(SessionFeature.Action.sessionSnapshotUpdated(it)))
                                } ?: Effect.none
                                val runState = snapshot.activeSessionIDs.contains(openSessionID)
                                return@Reduce Effect.merge(
                                    attention,
                                    prefetch,
                                    update,
                                    if (detail.isExternallyActive == runState) {
                                        Effect.none
                                    } else {
                                        Effect.send(sessionAction(SessionFeature.Action.externalRunStateChanged(isRunning = runState)))
                                    },
                                    if (wasConnected) Effect.none else Effect.send(sessionAction(SessionFeature.Action.requestsConnectionChanged(true))),
                                )
                            }
                            Effect.merge(attention, prefetch)
                        }

                        is Action.transcriptPrefetched -> {
                            // Fill an empty slot only; an opened session owns its entry.
                            if (!state.sessions.any { it.id == action.sessionID } ||
                                state.recentlyDeletedIDs.contains(action.sessionID) ||
                                state.transcriptCache[action.sessionID] != null
                            ) {
                                return@Reduce Effect.none
                            }
                            state.publishTranscript(sessionID = action.sessionID, items = action.items)
                            state.persistTranscriptCache()
                            Effect.none
                        }

                        is Action.selectedTranscriptResponse -> {
                            if (state.sessionDetail?.session?.id != action.sessionID) return@Reduce Effect.none
                            action.result.onSuccess { state.publishTranscript(sessionID = action.sessionID, items = it) }
                            Effect.send(sessionAction(SessionFeature.Action.transcriptResponse(sessionID = action.sessionID, result = action.result)))
                        }

                        is Action.sessionNavigationReady -> {
                            val session = state.sessions.firstOrNull { it.id == action.sessionID } ?: return@Reduce Effect.none
                            state.sessionDetail = state.makeDetail(session, externalTranscript = true)
                            LabMarks.mark("session_navigation_started", sessionID = action.sessionID)
                            Effect.none
                        }

                        is Action.attentionRefreshed -> {
                            state.attentionSessionIDs = action.sessionIDs
                            Effect.none
                        }

                        Action.newSessionTapped -> {
                            // A draft is local; the server is only needed on first send, which
                            // reports failure in the transcript. The event stream drops often on
                            // a phone, so a retrying connection must not dead-end the button.
                            // Only a confirmed outage blocks it.
                            if (state.connectionStatus == State.ConnectionStatus.disconnected) return@Reduce Effect.none
                            state.catchUp = null
                            state.sessionDetail = state.makeDraft()
                            Effect.none
                        }

                        is Action.sessionDetail if action.presented is SessionFeature.Action.sessionCreated -> {
                            val session = (action.presented as SessionFeature.Action.sessionCreated).session
                            // The event stream may have listed it already.
                            state.sessions = listOf(session) + state.sessions.filterNot { it.id == session.id }
                            state::sessionReadDates.withLock { value += (state.sessionKey(session.id) to session.updatedAt) }
                            state::lastViewedSessionIDs.withLock { value += (state.server.persistenceID to session.id) }
                            Effect.none
                        }

                        is Action.sessionDetail if action.presented is SessionFeature.Action.delegate -> {
                            when (val delegate = (action.presented as SessionFeature.Action.delegate).action) {
                                is SessionFeature.Delegate.openSession -> {
                                    if (!state.sessions.any { it.id == delegate.session.id }) {
                                        state.sessions = listOf(delegate.session) + state.sessions
                                    }
                                    Effect.send(Action.sessionTapped(delegate.session.id))
                                }
                                is SessionFeature.Delegate.togglePin -> Effect.send(Action.togglePin(delegate.sessionID))
                                is SessionFeature.Delegate.toggleRead -> {
                                    val session = state.sessions.firstOrNull { it.id == delegate.sessionID } ?: return@Reduce Effect.none
                                    Effect.send(if (state.isUnread(session)) Action.markRead(delegate.sessionID) else Action.markUnread(delegate.sessionID))
                                }
                                is SessionFeature.Delegate.deleteSession -> {
                                    // The sheet already confirmed; the drawer's deletion path does the rest.
                                    val session = state.sessions.firstOrNull { it.id == delegate.sessionID } ?: return@Reduce Effect.none
                                    state.pendingDeletion = session
                                    state.deletionError = null
                                    Effect.send(Action.deleteSessionConfirmed)
                                }
                            }
                        }

                        is Action.binding, is Action.sessionDetail, Action.serverBadgeTapped -> Effect.none

                        is Action.sessionTapped -> {
                            val sessionID = action.sessionID
                            // Navigation and cached transcripts do not require a live event stream.
                            val session = state.sessions.firstOrNull { it.id == sessionID } ?: return@Reduce Effect.none
                            // Opening a session leaves the stack for the session it names.
                            state.catchUp = null
                            state::lastViewedSessionIDs.withLock { value += (state.server.persistenceID to sessionID) }
                            state.markReadLocally(session)
                            // Re-opening the session already on screen (a repeated row tap) must
                            // not rebuild it and lose the composer draft.
                            if (state.sessionDetail?.session?.id == sessionID) {
                                state.sessionDetail?.readsOnView = true
                                return@Reduce acknowledgeView(session, server = state.server)
                            }
                            LabMarks.mark("session_tap", sessionID = sessionID)
                            val cachedItems = state.transcriptCache[sessionID]
                            if (cachedItems == null) {
                                val server = state.server
                                return@Reduce Effect.merge(
                                    Effect.cancel(CancelID.prefetch),
                                    acknowledgeView(session, server = server),
                                    Effect.run<Action> { send ->
                                        LabMarks.mark("selected_transcript_effect_started", sessionID = sessionID)
                                        coroutineScope {
                                            val result = async { selectedTranscript(server = server, sessionID = sessionID) }
                                            send(Action.sessionNavigationReady(sessionID))
                                            send(Action.selectedTranscriptResponse(sessionID = sessionID, result = result.await()))
                                        }
                                    }.cancellable(CancelID.selectedTranscript, cancelInFlight = true),
                                )
                            }
                            state.sessionDetail = state.makeDetail(session, cachedItems = cachedItems)
                            LabMarks.mark("session_navigation_started", sessionID = sessionID)
                            Effect.merge(
                                Effect.cancel(CancelID.prefetch),
                                Effect.cancel(CancelID.selectedTranscript),
                                acknowledgeView(session, server = state.server),
                            )
                        }

                        is Action.togglePin -> {
                            if (!state.sessions.any { it.id == action.sessionID }) return@Reduce Effect.none
                            haptics.selection()
                            val key = state.sessionKey(action.sessionID)
                            if (state.isPinned(action.sessionID)) {
                                state::pinnedSessionKeys.withLock { value = value.filterNot { it == key } }
                            } else {
                                state::pinnedSessionKeys.withLock { value += key }
                            }
                            Effect.none
                        }

                        is Action.pinsMoved -> {
                            // Search can hide pins, so offsets resolve against the displayed rows.
                            // The destination is an offset into the pre-move array.
                            val ids = state.pinnedSessions.map { it.id }
                            val index = action.fromOffsets.minOrNull()
                            val destination = action.toOffset
                            if (index == null || index !in ids.indices || destination == index || destination == index + 1) {
                                return@Reduce Effect.none
                            }
                            movePin(state, id = ids[index], before = if (destination < ids.size) ids[destination] else null)
                        }

                        is Action.movePinUp -> {
                            val ids = state.pinnedSessions.map { it.id }
                            val index = ids.indexOf(action.id)
                            if (index <= 0) return@Reduce Effect.none
                            movePin(state, id = action.id, before = ids[index - 1])
                        }

                        is Action.movePinDown -> {
                            val ids = state.pinnedSessions.map { it.id }
                            val index = ids.indexOf(action.id)
                            if (index < 0 || index + 1 >= ids.size) return@Reduce Effect.none
                            val next = if (index + 2 < ids.size) ids[index + 2] else null
                            movePin(state, id = action.id, before = next)
                        }

                        is Action.deleteSessionTapped -> {
                            state.pendingDeletion = state.sessions.firstOrNull { it.id == action.sessionID }
                            state.deletionError = null
                            Effect.none
                        }

                        Action.deleteSessionCancelled -> {
                            state.pendingDeletion = null
                            Effect.none
                        }

                        Action.deleteSessionConfirmed -> {
                            val session = state.pendingDeletion
                            val backup = session?.let { hideSession(state, session = it) }
                            if (session == null || backup == null) {
                                state.pendingDeletion = null
                                return@Reduce Effect.none
                            }
                            state.pendingDeletion = null
                            state.deletionError = null
                            haptics.impact()
                            val server = state.server
                            val deletion = Effect.run<Action> { send ->
                                try {
                                    openCodeClient.deleteSession(server, session.id)
                                    send(Action.deleteSessionResponse(sessionID = session.id, backup = backup, error = null))
                                } catch (error: OpenCodeClientError) {
                                    send(Action.deleteSessionResponse(sessionID = session.id, backup = backup, error = error))
                                }
                            }.cancellable(CancelID.deleteSession(session.id), cancelInFlight = true)
                            Effect.merge(
                                Effect.cancel(CancelID.prefetch),
                                if (backup.replacementDraftID != null) Effect.cancel(CancelID.selectedTranscript) else Effect.none,
                                deletion,
                            )
                        }

                        is Action.deleteSessionResponse -> {
                            val error = action.error
                            if (error == null) {
                                state.purgeSession(action.sessionID)
                                return@Reduce Effect.none
                            }
                            state.recentlyDeletedIDs -= action.sessionID
                            restoreSession(state, from = action.backup)
                            state.deletionError = "Couldn’t delete “${action.backup.session.title}”. ${error.message}"
                            haptics.error()
                            Effect.run<Action> { send ->
                                clock.sleep(3.seconds)
                                send(Action.clearDeletionError)
                            }.cancellable(CancelID.deletionError, cancelInFlight = true)
                        }

                        Action.clearDeletionError -> {
                            state.deletionError = null
                            Effect.none
                        }

                        is Action.problemReports -> Effect.none

                        is Action.markRead -> {
                            val session = state.sessions.firstOrNull { it.id == action.sessionID } ?: return@Reduce Effect.none
                            haptics.selection()
                            state.markReadLocally(session)
                            acknowledgeView(session, server = state.server)
                        }

                        is Action.markUnread -> {
                            val session = state.sessions.firstOrNull { it.id == action.sessionID } ?: return@Reduce Effect.none
                            haptics.selection()
                            state.markUnreadLocally(session)
                            // A receipt still in flight would otherwise mark it read again.
                            Effect.cancel(CancelID.viewReceipt(action.sessionID))
                        }
                    }
                } finally {
                    state.absorbSharedDrafts()
                    if (persistsSnapshot(action)) state.persistSnapshot()
                }
            }
                .ifLet(State::sessionDetail, Action.sessionDetail) {
                    SessionFeature()
                }
                // Whoever moves a live call (the drawer, New Session, a draft's project)
                // only edits its target; the voice model hears about it here.
                .onChange(of = { it.sessionDetail?.voice?.placement }) { old, _ ->
                    Reduce { _ ->
                        val new = state.sessionDetail?.voice?.placement
                        if (old == null || new == null) return@Reduce Effect.none
                        // Releasing what was let go clears it; that alone isn't a move.
                        if (old.target == new.target && new.abandonedCallIDs.isEmpty()) return@Reduce Effect.none
                        Effect.send(voiceAction(VoiceFeature.Action.targetChanged))
                    }
                }
                // The Live Activity follows the call wherever it moves; it is pushed
                // only when what it shows changes, never per second.
                .onChange(of = { it.sessionDetail?.voice?.let(VoiceLiveActivity::Call) }) { _, _ ->
                    Reduce { _ ->
                        val call = state.sessionDetail?.voice?.let(VoiceLiveActivity::Call)
                        val content = VoiceLiveActivity.content(call, previous = state.voiceCallActivity, now = now)
                        if (content == state.voiceCallActivity) return@Reduce Effect.none
                        state.voiceCallActivity = content
                        val attributes = call?.let(VoiceLiveActivity::attributes)
                        val voiceLiveActivity = voiceLiveActivity
                        Effect.run { _ -> voiceLiveActivity.update(content, attributes) }
                    }
                }
                .onChange(of = { if (LabMarks.isEnabled) it.sessionIndicators else emptyMap() }) { old, _ ->
                    Reduce { _ ->
                        for ((sessionID, indicator) in state.sessionIndicators) {
                            if (old[sessionID] != indicator) LabMarks.mark("indicator:${indicator.labMarkName}", sessionID = sessionID)
                        }
                        Effect.none
                    }
                },
            // Absorbing shared files is a pure state change; start their uploads here.
            Reduce { _ ->
                if (state.sessionDetail?.unstartedUploadIDs?.isEmpty() != false) return@Reduce Effect.none
                Effect.send(sessionAction(SessionFeature.Action.queuedUploadsStarted))
            },
            Reduce { action ->
                // Only list and attention changes move the stack; skip the transcript's
                // own high-frequency traffic.
                if (action is Action.sessionDetail || action is Action.problemReports) return@Reduce Effect.none
                if (action is Action.serverEvent &&
                    (action.event is OpenCodeServerEvent.contentBatch || action.event is OpenCodeServerEvent.messageInvalidated)
                ) {
                    return@Reduce Effect.none
                }
                val front = state.catchUp?.queue?.firstOrNull()
                state.reconcileCatchUp()
                if (state.catchUp?.queue?.firstOrNull() != front) prefetchTranscripts(state) else Effect.none
            },
        )

    private fun persistsSnapshot(action: Action): Boolean =
        when (action) {
            is Action.snapshotRefreshed, Action.deleteSessionConfirmed, is Action.deleteSessionResponse -> true
            is Action.serverEvent -> action.event is OpenCodeServerEvent.sessionRenamed || action.event is OpenCodeServerEvent.sessionDeleted
            is Action.sessionDetail -> action.presented is SessionFeature.Action.sessionCreated
            else -> false
        }

    private fun requestsDisconnected(state: State): Effect<Action> =
        if (state.sessionDetail == null) Effect.none else Effect.send(sessionAction(SessionFeature.Action.requestsConnectionChanged(false)))

    /// Removes only the row; phone-local records wait for the server's answer.
    private fun hideSession(state: State, session: OpenCodeSession): DeletionBackup? {
        val sessionIndex = state.sessions.indexOfFirst { it.id == session.id }
        if (sessionIndex < 0) return null
        val wasOpen = state.sessionDetail?.familyRootID == session.id
        val openItems = if (wasOpen) state.sessionDetail?.items ?: emptyList() else emptyList()
        var backup = DeletionBackup(
            session = session,
            sessionIndex = sessionIndex,
            wasActive = state.activeSessionIDs.contains(session.id),
            neededAttention = state.attentionSessionIDs.contains(session.id),
            runStartedDuringRefresh = state.runsStartedDuringRefresh.contains(session.id),
            openItems = openItems,
        )
        state.recentlyDeletedIDs += session.id
        state.sessions = state.sessions.toMutableList().also { it.removeAt(sessionIndex) }
        state.activeSessionIDs -= session.id
        state.attentionSessionIDs -= session.id
        state.runsStartedDuringRefresh -= session.id
        if (wasOpen) {
            state.sessionDetail = state.makeDraft()
            backup = backup.copy(replacementDraftID = state.sessionDetail?.navigationID)
        }
        return backup
    }

    private fun restoreSession(state: State, from: DeletionBackup) {
        val backup = from
        if (state.sessions.any { it.id == backup.session.id }) return
        state.sessions = state.sessions.toMutableList().also { it.add(minOf(backup.sessionIndex, it.size), backup.session) }
        if (backup.wasActive) state.activeSessionIDs += backup.session.id
        if (backup.neededAttention) state.attentionSessionIDs += backup.session.id
        if (backup.runStartedDuringRefresh) state.runsStartedDuringRefresh += backup.session.id
        // Reopen only if the reader is still looking at the untouched draft the
        // deletion left behind; anywhere else they have moved on.
        val draftID = backup.replacementDraftID
        val detail = state.sessionDetail
        if (draftID != null && detail != null && detail.navigationID == draftID && detail.isDraft && !detail.hasPromptContent) {
            state.sessionDetail = state.makeDetail(backup.session, cachedItems = backup.openItems)
        }
    }

    private fun movePin(state: State, id: String, before: String?): Effect<Action> {
        val targetID = before
        val key = state.sessionKey(id)
        if (!state.pinnedSessionKeys.contains(key)) return Effect.none
        val targetKey = targetID?.let(state::sessionKey)
        if (targetKey == key) return Effect.none
        state::pinnedSessionKeys.withLock {
            val keys = value.filterNot { it == key }.toMutableList()
            val index = targetKey?.let(keys::indexOf) ?: -1
            if (index >= 0) {
                keys.add(index, key)
            } else {
                // Server-scoped keys for other servers retain their relative order;
                // this server's pin moves to the end of its visible sequence.
                val last = keys.indexOfLast { it.startsWith("${state.server.persistenceID}|") }
                keys.add(if (last >= 0) last + 1 else keys.size, key)
            }
            value = keys.toList()
        }
        haptics.selection()
        return Effect.none
    }

    /// Live updates, TUI-style: subscribe to the SSE firehose, reconnect forever
    /// with a short delay. Every (re)connection replays `.connected`, which
    /// rehydrates the snapshot so missed events (the stream is volatile by
    /// contract) can't leave us stale. Cancel-in-flight makes it restartable.
    private fun eventStream(server: OpenCodeServer): Effect<Action> {
        val events = openCodeClient.events
        return Effect.run<Action> { send ->
            var attempt = 0
            while (currentCoroutineContext().isActive) {
                var connected = false
                for (address in server.candidateURLs) {
                    val candidate = server.at(url = address)
                    try {
                        events(candidate).collect { event ->
                            if (event == OpenCodeServerEvent.connected) {
                                connected = true
                                attempt = 0
                                send(Action.serverConnected(candidate))
                            } else {
                                send(Action.serverEvent(event))
                            }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        // A live stream that went silent past its 90 s timeout.
                        if (connected && DiagnosticsErrorCategory(error) == DiagnosticsErrorCategory.timeout) {
                            send(Action.problemReports(ProblemReportsFeature.Action.troubleDetected(ProblemReportTrigger.streamStalled)))
                        }
                        val failure = error as? OpenCodeClientError ?: OpenCodeClientError(message = "Couldn’t reach the OpenCode server")
                        if (failure.statusCode == 401) {
                            send(Action.eventStreamFailed(failure))
                            return@run
                        }
                    }
                    if (!currentCoroutineContext().isActive) return@run
                    send(Action.connectionLost)
                }
                attempt = if (connected) 1 else minOf(attempt + 1, 5)
                val baseMilliseconds = minOf(15_000, 1_000 * (1 shl maxOf(0, attempt - 1)))
                val jitter = abs(server.persistenceID.hashCode() % 401)
                clock.sleep((baseMilliseconds + jitter).milliseconds)
            }
        }.cancellable(CancelID.events, cancelInFlight = true)
    }

    /// Warm the transcript cache for the rows most likely to be opened next, so
    /// the first tap renders instantly instead of waiting a round trip. Only the
    /// top few are ever fetched, so the cache stays bounded to the hot set plus
    /// whatever was actually opened. Staggered so it never competes with the
    /// snapshot or an open session's own fetch.
    private fun prefetchTranscripts(state: State): Effect<Action> {
        // A cached frame is not a fresh frame. Keep speculative history off the
        // connection until the selected session's authoritative owner settles.
        val detail = state.sessionDetail
        if (detail != null && detail.session != null && (detail.refresh != null || !detail.initialTranscriptIsFresh)) {
            return Effect.none
        }
        val byID = state.sessions.distinctBy { it.id }.associateBy { it.id }
        val ranked = state.catchUp?.let { catchUp -> catchUp.queue.mapNotNull { byID[it] } } ?: state.sessionsByPriority()
        val openID = state.sessionDetail?.session?.id
        val targets = ranked.take(prefetchCount)
            .filter { state.transcriptCache[it.id] == null && it.id != openID }
            .map { it.id }
        if (targets.isEmpty()) return Effect.none
        val server = state.server
        return Effect.run<Action> { send ->
            for ((index, sessionID) in targets.withIndex()) {
                if (index > 0) clock.sleep(50.milliseconds)
                val items = try {
                    openCodeClient.transcript(server, sessionID, SessionFeature.initialFetchLimit)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    continue
                }
                send(Action.transcriptPrefetched(sessionID = sessionID, items = items))
            }
        }.cancellable(CancelID.prefetch, cancelInFlight = true)
    }

    private suspend fun selectedTranscript(
        server: OpenCodeServer,
        sessionID: String,
    ): Result<List<OpenCodeTranscriptItem>> =
        try {
            Result.success(openCodeClient.transcript(server, sessionID, SessionFeature.firstFrameFetchLimit))
        } catch (error: OpenCodeClientError) {
            Result.failure(error)
        }

    /// One receipt per session, a few at a time: Mark All can cover a whole
    /// page of sessions.
    private fun acknowledgeViews(receipts: List<Receipt>, server: OpenCodeServer): Effect<Action> {
        if (receipts.isEmpty()) return Effect.none
        val client = openCodeClient
        return Effect.run<Action> { send ->
            coroutineScope {
                val permits = Semaphore(receiptConcurrency)
                for (receipt in receipts) {
                    launch {
                        permits.withPermit {
                            val succeeded = try {
                                client.viewSession(server, receipt.sessionID, receipt.idle)
                                true
                            } catch (_: OpenCodeClientError) {
                                false
                            }
                            if (!currentCoroutineContext().isActive) return@withPermit
                            send(Action.markAllReceiptResponse(sessionID = receipt.sessionID, idle = receipt.idle, succeeded = succeeded))
                        }
                    }
                }
            }
        }.cancellable(CancelID.markAllReceipts)
    }

    /// Routed through the session screen so it records the receipt as its own.
    private fun acknowledgeShownSubagent(sessionID: String, idle: Long, server: OpenCodeServer): Effect<Action> =
        Effect.run<Action> { send ->
            try {
                openCodeClient.viewSession(server, sessionID, idle)
                send(sessionAction(SessionFeature.Action.viewAcknowledged(sessionID = sessionID, idle = idle)))
            } catch (_: OpenCodeClientError) {
            }
        }.cancellable(CancelID.viewReceipt(sessionID), cancelInFlight = true)

    private fun acknowledgeView(session: OpenCodeSession, server: OpenCodeServer): Effect<Action> {
        val idle = session.idleMilliseconds ?: return Effect.none
        return Effect.run<Action> { send ->
            try {
                openCodeClient.viewSession(server, session.id, idle)
                send(Action.viewAcknowledged(sessionID = session.id, idle = idle))
            } catch (_: OpenCodeClientError) {
                /* Keep the server unread state until an acknowledgment succeeds. */
            }
        }.cancellable(CancelID.viewReceipt(session.id), cancelInFlight = true)
    }

    /// Debounced and serialized: event bursts (subagent swarms finishing)
    /// coalesce into one fetch, and cancel-in-flight guarantees an older
    /// response can never land after a newer one and resurrect stale state.
    /// Execution events from here until the response are re-applied over it.
    private fun refreshSnapshot(state: State): Effect<Action> {
        state.runsStartedDuringRefresh = emptySet()
        val server = state.server
        return Effect.run<Action> { send ->
            clock.sleep(250.milliseconds)
            try {
                LabMarks.mark("home_snapshot_request")
                val snapshot = openCodeClient.homeSnapshot(server)
                LabMarks.mark("home_snapshot_response")
                if (!currentCoroutineContext().isActive) return@run
                send(Action.snapshotRefreshed(snapshot))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (!currentCoroutineContext().isActive) return@run
                send(Action.snapshotFailed)
            }
        }.cancellable(CancelID.refresh, cancelInFlight = true)
    }

    private fun refreshAttention(state: State): Effect<Action> {
        val server = state.server
        val roots = state.sessions
        val directories = attentionDirectories(state)
        return Effect.run<Action> { send ->
            clock.sleep(150.milliseconds)
            val sessionIDs = try {
                openCodeClient.homeAttention(server, roots, directories)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            if (sessionIDs != null) send(Action.attentionRefreshed(sessionIDs))
        }.cancellable(CancelID.attention, cancelInFlight = true)
    }

    /// `.sessionDetail(.presented(action))`
    private fun sessionAction(action: SessionFeature.Action): Action =
        Action.sessionDetail(PresentationAction.presented(action))

    /// `.sessionDetail(.presented(.voice(.presented(action))))`
    private fun voiceAction(action: VoiceFeature.Action): Action =
        sessionAction(SessionFeature.Action.voice(PresentationAction.presented(action)))

    /// The open session's action in a `.sessionDetail(.presented(…))` case.
    private val Action.sessionDetail.presented: SessionFeature.Action?
        get() = (action as? PresentationAction.presented)?.action

    /// The call's delegate in a `.sessionDetail(.presented(.voice(.presented(.delegate(…)))))` case.
    private val SessionFeature.Action.voiceDelegate: VoiceFeature.Delegate?
        get() {
            val voice = ((this as? SessionFeature.Action.voice)?.action as? PresentationAction.presented)?.action
            return (voice as? VoiceFeature.Action.delegate)?.action
        }

    companion object {
        /// Mark All as Read receipts in flight at once.
        const val receiptConcurrency = 4

        /// How many uncached rows to warm after each snapshot. Rows that need you
        /// or are running come first; the rest by recency.
        const val prefetchCount = 3

        /// Bounds the in-memory transcripts so long drawer browsing cannot keep an
        /// unbounded second copy of history.
        const val transcriptMemoryLimit = 8

        /// Upper bound on directories one attention refresh may query.
        const val attentionDirectoryLimit = 8

        /// Directories that can actually hold a pending request. Every
        /// location-scoped read makes the server boot that directory's full
        /// service graph (MCP servers, watchers, plugins) and keep it for an hour,
        /// so asking about all historical directories on each Home refresh once
        /// booted 152 graphs and ~900 child processes. A request can only pend
        /// inside a booted graph, and a session blocked on one is still running,
        /// so running roots, roots already flagged, and the open session cover it.
        fun attentionDirectories(state: State): List<String> {
            val busy = state.busySessionIDs
            val openID = state.sessionDetail?.session?.id
            val hot = state.sessions
                .filter {
                    busy.contains(it.id) ||
                        state.attentionSessionIDs.contains(it.id) ||
                        it.id == openID
                }
                .sortedByDescending { it.updatedAt }
            val seen = mutableSetOf<String>()
            return hot.mapNotNull { if (seen.add(it.directory)) it.directory else null }
                .take(attentionDirectoryLimit)
        }
    }
}
