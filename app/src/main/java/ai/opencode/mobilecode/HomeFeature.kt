@file:PortedFrom("OpenCode/HomeFeature.swift", "553a407", partial = "State, nested types, Action, CancelID, attentionDirectories; the reducer body and its helpers follow, and problemReports waits for ProblemReportsFeature")

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
import ai.opencode.mobilecode.tca.Scope
import ai.opencode.mobilecode.tca.Shared
import ai.opencode.mobilecode.tca.SharedKey
import ai.opencode.mobilecode.tca.StoreVar
import ai.opencode.mobilecode.tca.combine
import ai.opencode.mobilecode.tca.continuousClock
import ai.opencode.mobilecode.tca.date
import ai.opencode.mobilecode.tca.ifLet
import ai.opencode.mobilecode.tca.withLock
import java.time.Instant

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
            val directory = draft.draftProject?.directory ?: sessionDetail?.session?.directory ?: activeVoice?.target?.directory
            if (activeVoice != null && directory != null) {
                activeVoice.retargetToDraft(directory = directory, model = draft.currentModel)
                draft.voice = activeVoice
            } else if (activeVoice != null) {
                // No folder to move the call to: a new identity ends it cleanly.
                draft.navigationID = nextDetailID()
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
            if (System.getenv("OPENCODE_LAB_DISABLE_TRANSCRIPT_DISK_CACHE") == "1") return
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
            if (System.getenv("OPENCODE_LAB_DISABLE_TRANSCRIPT_DISK_CACHE") == "1") return
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
            Scope(state = State::whatsNew, action = Action.whatsNew) {
                WhatsNewFeature()
            },
            Reduce<State, Action> { _ -> TODO("HomeFeature's reducer is ported in phase 2") }
                .ifLet(State::sessionDetail, Action.sessionDetail) {
                    SessionFeature()
                },
        )

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
