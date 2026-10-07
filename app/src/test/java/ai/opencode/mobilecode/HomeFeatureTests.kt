@file:PortedFrom("OpenCodeTests/HomeFeatureTests.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.HomeFeature.Action
import ai.opencode.mobilecode.tca.BindingAction
import ai.opencode.mobilecode.tca.Exhaustivity
import ai.opencode.mobilecode.tca.ImmediateClock
import ai.opencode.mobilecode.tca.PresentationAction
import ai.opencode.mobilecode.tca.Shared
import ai.opencode.mobilecode.tca.SharedKey
import ai.opencode.mobilecode.tca.TestClock
import ai.opencode.mobilecode.tca.TestDependencies
import ai.opencode.mobilecode.tca.TestStore
import ai.opencode.mobilecode.tca.UUIDGenerator
import ai.opencode.mobilecode.tca.DateGenerator
import ai.opencode.mobilecode.tca.continuousClock
import ai.opencode.mobilecode.tca.date
import ai.opencode.mobilecode.tca.expect
import ai.opencode.mobilecode.tca.uuid
import ai.opencode.mobilecode.tca.withLock
import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric gives the reducers' LabMarks a working android.util.Log.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HomeFeatureTests {
    @get:Rule
    val dependencies = TestDependencies()

    private val testServer = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")

    private fun testSession(
        id: String = "ses_1",
        title: String = "Fix login bug",
        projectID: String = "proj_1",
        directory: String = "/tmp/demo",
        updatedAt: Long = 1_000_000,
    ): OpenCodeSession = OpenCodeSession(id = id, title = title, projectID = projectID, directory = directory, updatedAt = updatedAt)

    private fun snapshot(
        projects: List<OpenCodeProject> = emptyList(),
        sessions: List<OpenCodeSession>,
        activeSessionIDs: Set<String> = emptySet(),
        activeSessionParents: Map<String, String>? = null,
    ) = OpenCodeHomeSnapshot(projects = projects, sessions = sessions, activeSessionIDs = activeSessionIDs, activeSessionParents = activeSessionParents)

    /// Opt-in Release benchmark; each sample rebuilds projections from the same
    /// deterministic snapshot. No network, disk writes, or timing assertions.
    @Test
    fun homeCPUPerformance() {
        assumeTrue(System.getenv("HOME_CPU_BENCH") == "1")
        for (count in listOf(50, 500, 2_000)) {
            val sessions = (0 until count).map { index ->
                testSession(id = "root-$index", updatedAt = ((index * 37) % count) * 1_000L)
            }
            val parents = (0 until 16).flatMap { listOf("child-$it" to "root-$it", "nested-$it" to "child-$it") }.toMap()
            val state = HomeFeature.State(
                server = testServer,
                snapshot = snapshot(sessions = sessions, activeSessionIDs = (0 until 16).map { "nested-$it" }.toSet(), activeSessionParents = parents),
            )
            state.sessionDetail = null
            state.attentionSessionIDs = setOf("root-20")
            // Keep the old calculation as a same-binary control for timing noise.
            fun repeatedAncestrySort(): List<OpenCodeSession> =
                state.sessions.sortedWith { a, b ->
                    val aHot = state.attentionSessionIDs.contains(a.id) || state.busySessionIDs.contains(a.id)
                    val bHot = state.attentionSessionIDs.contains(b.id) || state.busySessionIDs.contains(b.id)
                    if (aHot != bHot) (if (aHot) -1 else 1) else b.updatedAt.compareTo(a.updatedAt)
                }
            expect(state.sessionsByPriority() == repeatedAncestrySort())
            for (operation in listOf("priority_repeated_ancestry", "priority", "indicators", "drawer")) {
                val samples = mutableListOf<Double>()
                var checksum = 0
                for (run in 0 until 8) {
                    val start = System.nanoTime()
                    checksum += when (operation) {
                        "priority_repeated_ancestry" -> repeatedAncestrySort().sumOf { it.id.length }
                        "priority" -> state.sessionsByPriority().sumOf { it.id.length }
                        "indicators" -> state.sessionIndicators.values.count { it == HomeFeature.State.Indicator.running }
                        else -> state.pinnedSessions.size + state.recentSessions.size
                    }
                    val elapsed = (System.nanoTime() - start) / 1e6
                    if (run > 0) samples += elapsed
                }
                val median = samples.sorted()[samples.size / 2]
                val mad = samples.map { kotlin.math.abs(it - median) }.sorted()[samples.size / 2]
                println("METRIC home_${operation}_${count}_ms=$median mad=$mad checksum=$checksum")
                expect(checksum > 0)
            }
        }
    }

    @Test
    fun eachSettledDrawerOpenAndCloseFeelsOneDrawerHaptic() {
        var drawerHaptics = 0
        val store = TestStore(
            initialState = { HomeFeature.State(server = testServer, snapshot = snapshot(sessions = emptyList())) },
            withDependencies = { it.haptics.drawer = { drawerHaptics += 1 } },
        ) {
            HomeFeature()
        }
        store.send(Action.sessionDrawerSettled(isOpen = true))
        expect(drawerHaptics == 1)
        store.send(Action.sessionDrawerSettled(isOpen = false))
        expect(drawerHaptics == 2)
    }

    @Test
    fun unopenedParentsStayBusyUntilTheirLastDescendantFinishes() {
        val root = OpenCodeSession(id = "root", title = "Main", projectID = "p", directory = "/p", updatedAt = distantPast)
        val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
        val parents = mapOf("child" to "root", "nested" to "child", "sibling" to "root")
        val state = HomeFeature.State(
            server = server,
            snapshot = snapshot(sessions = listOf(root), activeSessionIDs = setOf("nested", "sibling"), activeSessionParents = parents),
        )
        state.sessionDetail = null
        expect(state.indicator(root) == HomeFeature.State.Indicator.running)
        expect(state.catchUpSessions.isEmpty())
        expect(HomeFeature.attentionDirectories(state) == listOf("/p"))
        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.continuousClock = ImmediateClock()
                it.openCodeClient.homeAttention = { _, _, _ -> emptySet() }
                it.openCodeClient.transcript = { _, _, _ -> emptyList() }
            },
        ) {
            HomeFeature()
        }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        store.send(Action.snapshotRefreshed(snapshot(sessions = listOf(root), activeSessionIDs = setOf("sibling"), activeSessionParents = parents)))
        expect(store.state.indicator(root) == HomeFeature.State.Indicator.running)
        store.send(Action.snapshotRefreshed(snapshot(sessions = listOf(root))))
        expect(store.state.indicator(root) != HomeFeature.State.Indicator.running)
        store.finish()
        expect(SessionActivity.ancestors(of = setOf("a"), parents = mapOf("a" to "b", "b" to "a")) == setOf("a", "b"))
    }

    @Test
    fun prioritySortUsesCurrentActivityAndAttentionBeforeRecency() {
        val sessions = (0 until 5).map {
            testSession(id = "root-$it", updatedAt = it * 1_000L)
        }
        val state = HomeFeature.State(
            server = testServer,
            snapshot = snapshot(
                sessions = sessions,
                activeSessionIDs = setOf("nested"),
                activeSessionParents = mapOf("nested" to "child", "child" to "root-0"),
            ),
        )
        state.attentionSessionIDs = setOf("root-1")
        expect(state.sessionsByPriority().map { it.id } == listOf("root-1", "root-0", "root-4", "root-3", "root-2"))
        state.activeSessionIDs = emptySet()
        expect(state.sessionsByPriority().map { it.id } == listOf("root-1", "root-4", "root-3", "root-2", "root-0"))
        state.attentionSessionIDs = emptySet()
        expect(state.sessionsByPriority().map { it.id } == listOf("root-4", "root-3", "root-2", "root-1", "root-0"))
    }

    @Test
    fun restoresRememberedSessionFromPersistedTranscriptOrStartsDraft() {
        val server = OpenCodeServer(url = "https://saved.example", username = "", password = "")
        val session = OpenCodeSession(id = "remembered", title = "Remembered", projectID = "p", directory = "/p", updatedAt = Instant.now().toEpochMilli())
        val items = listOf<OpenCodeTranscriptItem>(OpenCodeTranscriptItem.userText(id = "m1", text = "ready offline"))
        val state = HomeFeature.State(server = server, snapshot = snapshot(sessions = listOf(session)))
        state::lastViewedSessionIDs.withLock { value += (server.persistenceID to session.id) }
        state.transcriptCache += (session.id to items)
        state.restoreInitialSession()
        expect(state.sessionDetail?.session == session)
        expect(state.sessionDetail?.items == items)
        expect(state.sessionDetail?.isLoading == false)

        state.sessions = emptyList()
        state.restoreInitialSession()
        expect(state.sessionDetail?.isDraft == true)
        expect(state.lastViewedSessionIDs[server.persistenceID] == null)
    }

    @Test
    fun transcriptSnapshotRoundTripsAndInvalidatesWhenSessionChanges() {
        val server = OpenCodeServer(url = "https://saved.example", username = "", password = "")
        val updatedAt = 1_000_000L
        val session = OpenCodeSession(id = "ses_cached", title = "Cached", projectID = "p", directory = "/p", updatedAt = updatedAt)
        val items = listOf(
            OpenCodeTranscriptItem.userText(id = "m1", text = "hello"),
            OpenCodeTranscriptItem.assistantMarkdown(id = "m2", blocks = OpenCodeMarkdownBlock.blocks(from = "**Hi**\n\n```swift\nlet n = 1\n```")),
            OpenCodeTranscriptItem.compaction(id = "m3"),
        )
        var cache = CachedTranscriptSnapshots()
        cache = cache.replaceEntries(
            server.persistenceID,
            listOf(CachedTranscriptSnapshots.Entry(serverID = server.persistenceID, sessionID = session.id, updatedAt = updatedAt, items = items)),
        )

        val restored = transcriptCacheJson.decodeFromString(
            CachedTranscriptSnapshots.serializer(),
            transcriptCacheJson.encodeToString(CachedTranscriptSnapshots.serializer(), cache),
        )
        val restoredItems = restored.validItems(serverID = server.persistenceID, sessions = listOf(session))[session.id] ?: error("no restored items")
        expect(restoredItems.map { it.id } == items.map { it.id })
        expect(restoredItems.mapNotNull { (it as? OpenCodeTranscriptItem.userText)?.text } == listOf("hello"))

        val changed = session.copy(updatedAt = updatedAt + 1_000)
        expect(restored.validItems(serverID = server.persistenceID, sessions = listOf(changed)).isEmpty())
        expect(restored.validItems(serverID = "another-server", sessions = listOf(session)).isEmpty())
    }

    @Test
    fun viewOnlyRefreshPublishesBoundedTranscriptForNextRestore() {
        val server = OpenCodeServer(url = "https://saved.example", username = "", password = "")
        val session = OpenCodeSession(id = "ses_cached", title = "Cached", projectID = "p", directory = "/p", updatedAt = 1_000_000)
        val stale = listOf<OpenCodeTranscriptItem>(OpenCodeTranscriptItem.userText(id = "old", text = "Old"))
        val fresh = (0 until 45).map { OpenCodeTranscriptItem.userText(id = "m$it", text = "Fresh $it") }
        val state = HomeFeature.State(server = server, snapshot = snapshot(sessions = listOf(session)))
        state.sessionDetail = SessionFeature.State(server = server, session = session, cachedItems = stale)
        state.sessionDetail?.isVisible = true
        state.sessionDetail?.refresh = SessionFeature.State.Refresh(id = 1, reason = SessionFeature.RefreshReason.open)
        val store = TestStore(initialState = { state }) { HomeFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(
            sessionAction(
                SessionFeature.Action.refreshResponse(
                    sessionID = session.id,
                    requestID = 1,
                    result = Result.success(SessionFeature.AuthoritativeRefresh(observed = session, items = fresh, scope = SessionFeature.AuthoritativeRefresh.Scope.page)),
                ),
            ),
        )
        store.receive({ it.presented is SessionFeature.Action.acceptedTranscriptUpdated })

        val cached = store.state.transcriptCache[session.id] ?: error("nothing cached")
        expect(cached == fresh.takeLast(SessionFeature.initialFetchLimit))
        val persisted = store.state.cachedTranscriptSnapshots.validItems(serverID = server.persistenceID, sessions = listOf(session))[session.id]
        expect(persisted == cached)

        val restored = HomeFeature.State(server = server, snapshot = snapshot(sessions = listOf(session)))
        restored::cachedTranscriptSnapshots.withLock { value = store.state.cachedTranscriptSnapshots }
        restored.restoreTranscriptCache()
        expect(restored.transcriptCache[session.id] == cached)
    }

    // transcriptPersistenceCoalescesAndKeepsProtectedFormat and
    // transcriptPersistenceBoundsLargeSnapshotsBeforeItsSingleFinalEncode live in TranscriptCachePersistenceTests.kt.

    @Test
    fun inMemoryTranscriptCacheRetainsTheOpenSessionAndPrioritySessions() {
        val server = OpenCodeServer(url = "https://saved.example", username = "", password = "")
        val sessions = (0 until 24).map { index ->
            OpenCodeSession(id = "ses_$index", title = "Session $index", projectID = "p", directory = "/p", updatedAt = index * 1_000L)
        }
        val state = HomeFeature.State(server = server, snapshot = snapshot(sessions = sessions, activeSessionIDs = setOf("ses_0")))
        state.sessionDetail = SessionFeature.State(server = server, session = sessions[1])
        for (session in sessions) {
            state.transcriptCache += (session.id to listOf(OpenCodeTranscriptItem.userText(id = session.id, text = session.title)))
        }
        state.trimTranscriptCache()

        expect(state.transcriptCache.size == HomeFeature.transcriptMemoryLimit)
        expect(state.transcriptCache["ses_1"] != null)
        expect(state.transcriptCache["ses_0"] != null)
        expect(state.transcriptCache["ses_23"] != null)
    }

    @Test
    fun runStartedWhileSnapshotInFlightSurvivesTheSnapshot() {
        val server = OpenCodeServer(url = "https://saved.example", username = "", password = "")
        val session = OpenCodeSession(id = "ses_1", title = "Work", projectID = "p", directory = "/p", updatedAt = distantPast)
        val stale = snapshot(sessions = listOf(session))
        val gate = CompletableDeferred<Unit>()
        val store = TestStore(
            initialState = { HomeFeature.State(server = server, snapshot = stale) },
            withDependencies = {
                it.continuousClock = ImmediateClock()
                it.openCodeClient.homeSnapshot = {
                    gate.await()
                    stale
                }
                it.openCodeClient.homeAttention = { _, _, _ -> emptySet() }
            },
        ) {
            HomeFeature()
        }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.serverEvent(OpenCodeServerEvent.sessionCreated))
        // The server started a run after the snapshot's active read left.
        store.send(Action.serverEvent(OpenCodeServerEvent.executionStarted(sessionID = "ses_1"))) {
            it.activeSessionIDs = setOf("ses_1")
            it.runsStartedDuringRefresh = setOf("ses_1")
        }
        gate.complete(Unit)
        store.receive<Action.snapshotRefreshed> {
            it.runsStartedDuringRefresh = emptySet()
            it.activeSessionIDs = setOf("ses_1")
        }
        expect(store.state.indicator(session) == HomeFeature.State.Indicator.running)
        store.finish()
    }

    @Test
    fun snapshotPrefetchesHotAndRecentTranscriptsWithoutRegressingCache() {
        val server = OpenCodeServer(url = "https://saved.example", username = "", password = "")
        fun session(id: String, seconds: Long) = OpenCodeSession(id = id, title = id, projectID = "p", directory = "/p", updatedAt = seconds * 1_000)
        val sessions = listOf(session("old", 1), session("newest", 5), session("running", 2), session("cached", 4), session("mid", 3))
        val snapshot = snapshot(sessions = sessions, activeSessionIDs = setOf("running"))
        val state = HomeFeature.State(server = server, snapshot = snapshot)
        val longer = listOf<OpenCodeTranscriptItem>(OpenCodeTranscriptItem.userText(id = "a", text = "a"), OpenCodeTranscriptItem.userText(id = "b", text = "b"))
        state.transcriptCache += ("cached" to longer)
        val requested = mutableListOf<String>()
        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.continuousClock = ImmediateClock()
                it.openCodeClient.homeSnapshot = { snapshot }
                it.openCodeClient.homeAttention = { _, _, _ -> emptySet() }
                it.openCodeClient.transcript = { _, id, _ ->
                    requested += id
                    listOf(OpenCodeTranscriptItem.userText(id = "$id-1", text = id))
                }
            },
        ) {
            HomeFeature()
        }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.snapshotRefreshed(snapshot))
        store.receive<Action.transcriptPrefetched>()
        store.receive<Action.transcriptPrefetched>()
        store.finish()
        expect(requested == listOf("running", "newest"), "running first, then recency; only the top three rows are candidates")
        expect(store.state.transcriptCache["running"]?.size == 1)
        expect(store.state.transcriptCache["cached"] == longer)
        expect(store.state.transcriptCache["mid"] == null)

        store.send(Action.transcriptPrefetched(sessionID = "cached", items = listOf(OpenCodeTranscriptItem.userText(id = "x", text = "x"))))
        expect(store.state.transcriptCache["cached"] == longer, "A prefetch never overwrites an existing entry")
    }

    /// Every location-scoped read boots that directory's service graph on the
    /// server. Home must only ask about directories that can hold a pending
    /// request, never the whole history.
    @Test
    fun attentionRefreshQueriesOnlyHotDirectoriesWithinAStrictBound() {
        val server = OpenCodeServer(url = "https://saved.example", username = "", password = "")
        val roots = (0 until 150).map { index ->
            OpenCodeSession(id = "ses_$index", title = "Work $index", projectID = "p$index", directory = "/projects/dir$index", updatedAt = index * 1_000L)
        }
        val state = HomeFeature.State(server = server, snapshot = snapshot(sessions = roots, activeSessionIDs = setOf("ses_3", "ses_40")))
        state.attentionSessionIDs = setOf("ses_7")
        state.sessionDetail = SessionFeature.State(server = server, session = roots[120], cachedItems = emptyList())
        val queried = mutableListOf<List<String>>()
        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.continuousClock = ImmediateClock()
                it.openCodeClient.homeAttention = { _, _, directories ->
                    queried += directories
                    setOf("ses_7")
                }
            },
        ) {
            HomeFeature()
        }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.serverEvent(OpenCodeServerEvent.attentionChanged(askedSessionID = null)))
        store.receive(Action.attentionRefreshed(setOf("ses_7")))
        expect(
            queried == listOf(listOf("/projects/dir120", "/projects/dir40", "/projects/dir7", "/projects/dir3")),
            "open session, then running and flagged roots by recency; 146 idle directories are never touched",
        )

        // Even a swarm of running roots stays under the cap.
        val swarm = HomeFeature.State(server = server, snapshot = snapshot(sessions = roots, activeSessionIDs = roots.map { it.id }.toSet()))
        expect(HomeFeature.attentionDirectories(swarm).size == HomeFeature.attentionDirectoryLimit)
        expect(HomeFeature.attentionDirectories(HomeFeature.State(server = server, snapshot = snapshot(sessions = roots))).isEmpty())
    }

    @Test
    fun pinsArePersistentServerScopedAndReturnToRecentWhenUnpinned() {
        val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
        val otherServer = OpenCodeServer(url = "http://localhost:4097", username = "", password = "")
        val recent = OpenCodeSession(id = "recent", title = "Recent", projectID = "p", directory = "/project", updatedAt = Instant.now().toEpochMilli())
        val older = OpenCodeSession(id = "older", title = "Older", projectID = "q", directory = "/other", updatedAt = distantPast)
        val snapshot = snapshot(sessions = listOf(recent, older))
        val state = HomeFeature.State(server = server, snapshot = snapshot)
        val store = TestStore(initialState = { state }) { HomeFeature() }
        store.send(Action.togglePin(older.id)) {
            it::pinnedSessionKeys.withLock { value = listOf(state.sessionKey(older.id)) }
        }
        expect(store.state.pinnedSessions == listOf(older))
        expect(store.state.recentSessions == listOf(recent))
        val restored = HomeFeature.State(server = server, snapshot = snapshot)
        expect(restored.pinnedSessions == listOf(older))
        val other = HomeFeature.State(server = otherServer, snapshot = snapshot)
        expect(other.pinnedSessions.isEmpty())
        store.send(Action.togglePin(older.id)) {
            it::pinnedSessionKeys.withLock { value = emptyList() }
        }
        expect(store.state.recentSessions == listOf(recent, older))
    }

    @Test
    fun pinsRespectSearchAndAppendInOrder() {
        val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
        val a = OpenCodeSession(id = "a", title = "Alpha", projectID = "p", directory = "/project", updatedAt = Instant.now().toEpochMilli())
        val b = OpenCodeSession(id = "b", title = "Beta", projectID = "q", directory = "/other", updatedAt = distantPast)
        val state = HomeFeature.State(server = server, snapshot = snapshot(sessions = listOf(a, b)))
        val store = TestStore(initialState = { state }) { HomeFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        store.send(Action.togglePin(a.id)) { it::pinnedSessionKeys.withLock { value = listOf(state.sessionKey(a.id)) } }
        store.send(Action.togglePin(b.id)) { it::pinnedSessionKeys.withLock { value = listOf(state.sessionKey(a.id), state.sessionKey(b.id)) } }
        expect(store.state.pinnedSessions == listOf(a, b))
        store.send(Action.binding(BindingAction.set(HomeFeature.State::search, "Beta"))) { it.search = "Beta" }
        expect(store.state.pinnedSessions == listOf(b))
        store.send(Action.togglePin("missing"))
    }

    @Test
    fun pinnedSessionsCanMoveWithAccessibleAndDragActions() {
        val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
        val now = Instant.now().toEpochMilli()
        val sessions = listOf("a", "b", "c").map {
            OpenCodeSession(id = it, title = it, projectID = "p", directory = "/p", updatedAt = now)
        }
        val state = HomeFeature.State(server = server, snapshot = snapshot(sessions = sessions))
        state::pinnedSessionKeys.withLock { value = sessions.map { state.sessionKey(it.id) } }
        val store = TestStore(initialState = { state }) { HomeFeature() }

        store.send(Action.movePinDown("a")) {
            it::pinnedSessionKeys.withLock { value = listOf(state.sessionKey("b"), state.sessionKey("a"), state.sessionKey("c")) }
        }
        store.send(Action.movePinUp("c")) {
            it::pinnedSessionKeys.withLock { value = listOf(state.sessionKey("b"), state.sessionKey("c"), state.sessionKey("a")) }
        }
        store.send(Action.pinsMoved(fromOffsets = setOf(2), toOffset = 0)) {
            it::pinnedSessionKeys.withLock { value = listOf(state.sessionKey("a"), state.sessionKey("b"), state.sessionKey("c")) }
        }
        store.send(Action.movePinUp("a"))
        store.send(Action.movePinDown("c"))
    }

    @Test
    fun draggingFilteredPinsMovesTheDisplayedRows() {
        val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
        val now = Instant.now().toEpochMilli()
        val sessions = listOf("a" to "Alpha", "b" to "Beta", "c" to "Bravo").map {
            OpenCodeSession(id = it.first, title = it.second, projectID = "p", directory = "/p", updatedAt = now)
        }
        val state = HomeFeature.State(server = server, snapshot = snapshot(sessions = sessions))
        state::pinnedSessionKeys.withLock { value = sessions.map { state.sessionKey(it.id) } }
        state.search = "b"
        val store = TestStore(initialState = { state }) { HomeFeature() }
        expect(store.state.pinnedSessions.map { it.id } == listOf("b", "c"))

        // Dragging the second visible row above the first reorders just those two.
        store.send(Action.pinsMoved(fromOffsets = setOf(1), toOffset = 0)) {
            it::pinnedSessionKeys.withLock { value = listOf(state.sessionKey("a"), state.sessionKey("c"), state.sessionKey("b")) }
        }
        store.send(Action.pinsMoved(fromOffsets = setOf(0), toOffset = 1))
    }

    @Test
    fun deletingSessionHidesTheRowThenPurgesLocalStateOnceConfirmed() {
        val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
        val session = OpenCodeSession(id = "chat", title = "Chat", projectID = "p", directory = "/project", updatedAt = Instant.now().toEpochMilli())
        val state = HomeFeature.State(server = server, snapshot = snapshot(sessions = listOf(session), activeSessionIDs = setOf(session.id)))
        val key = state.sessionKey(session.id)
        val items = listOf<OpenCodeTranscriptItem>(OpenCodeTranscriptItem.userText(id = "message", text = "Cached"))
        state::pinnedSessionKeys.withLock { value = listOf(key) }
        state::lastViewedSessionIDs.withLock { value += (server.persistenceID to session.id) }
        state::sessionReadDates.withLock { value += (key to session.updatedAt) }
        state::manuallyUnreadSessionKeys.withLock { value += key }
        state.transcriptCache += (session.id to items)
        state::cachedTranscriptSnapshots.withLock {
            value = value.copy(entries = listOf(CachedTranscriptSnapshots.Entry(serverID = server.persistenceID, sessionID = session.id, updatedAt = session.updatedAt, items = items)))
        }
        val gate = CompletableDeferred<Unit>()
        val store = TestStore(
            initialState = { state },
            withDependencies = { it.openCodeClient.deleteSession = { _, _ -> gate.await() } },
        ) {
            HomeFeature()
        }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.deleteSessionTapped(session.id)) { it.pendingDeletion = session }
        // Only the row goes; phone-local records wait for the server.
        store.send(Action.deleteSessionConfirmed) {
            it.pendingDeletion = null
            it.sessions = emptyList()
            it.activeSessionIDs = emptySet()
            it.recentlyDeletedIDs = setOf(session.id)
        }
        expect(store.state.pinnedSessionKeys == listOf(key))
        expect(store.state.pinnedSessions.isEmpty())
        expect(store.state.lastViewedSessionIDs[server.persistenceID] == session.id)
        expect(store.state.transcriptCache[session.id] == items)
        expect(store.state.cachedTranscriptSnapshots.entries.size == 1)

        gate.complete(Unit)
        store.receive<Action.deleteSessionResponse> {
            it.transcriptCache = emptyMap()
            it::pinnedSessionKeys.withLock { value = emptyList() }
            it::lastViewedSessionIDs.withLock { value -= server.persistenceID }
            it::sessionReadDates.withLock { value -= key }
            it::manuallyUnreadSessionKeys.withLock { value -= key }
            it::cachedTranscriptSnapshots.withLock { value = value.copy(entries = emptyList()) }
        }
        store.finish()
    }

    @Test
    fun failedSessionDeletionRestoresTheRowAndBookkeeping() {
        val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
        val session = OpenCodeSession(id = "ses_restore", title = "Restore me", projectID = "p", directory = "/project", updatedAt = 20_000)
        val other = OpenCodeSession(id = "ses_other", title = "Other", projectID = "p", directory = "/project", updatedAt = 10_000)
        val state = HomeFeature.State(server = server, snapshot = snapshot(sessions = listOf(session, other), activeSessionIDs = setOf(session.id)))
        val key = state.sessionKey(session.id)
        val openItems = listOf<OpenCodeTranscriptItem>(OpenCodeTranscriptItem.userText(id = "m1", text = "Open"))
        state.sessionDetail = SessionFeature.State(server = server, session = session, cachedItems = openItems)
        state::pinnedSessionKeys.withLock { value = listOf(key) }
        state::lastViewedSessionIDs.withLock { value += (server.persistenceID to session.id) }
        state::sessionReadDates.withLock { value += (key to session.updatedAt) }
        state.attentionSessionIDs = setOf(session.id)
        val clock = TestClock()
        val gate = CompletableDeferred<Unit>()
        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.continuousClock = clock
                it.openCodeClient.deleteSession = { _, _ ->
                    gate.await()
                    throw OpenCodeClientError(message = "Offline")
                }
            },
        ) {
            HomeFeature()
        }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.deleteSessionTapped(session.id))
        store.send(Action.deleteSessionConfirmed)
        expect(store.state.sessions == listOf(other))
        expect(store.state.sessionDetail?.isDraft == true)
        expect(store.state.pinnedSessionKeys == listOf(key))
        expect(store.state.lastViewedSessionIDs[server.persistenceID] == session.id)

        // Disk writes made while the request is in flight must survive a failure.
        val otherItems = listOf<OpenCodeTranscriptItem>(OpenCodeTranscriptItem.userText(id = "m2", text = "Other"))
        store.send(Action.transcriptPrefetched(sessionID = other.id, items = otherItems))
        expect(store.state.cachedTranscriptSnapshots.entries.map { it.sessionID } == listOf(other.id))

        gate.complete(Unit)
        store.receive<Action.deleteSessionResponse> {
            it.sessions = listOf(session, other)
            it.activeSessionIDs = setOf(session.id)
            it.attentionSessionIDs = setOf(session.id)
            it.recentlyDeletedIDs = emptySet()
            it.deletionError = "Couldn’t delete “Restore me”. Offline"
        }
        expect(store.state.sessionDetail?.session == session)
        expect(store.state.sessionDetail?.items == openItems)
        expect(store.state.cachedTranscriptSnapshots.entries.map { it.sessionID } == listOf(other.id))
        expect(store.state.pinnedSessionKeys == listOf(key))
        expect(store.state.lastViewedSessionIDs[server.persistenceID] == session.id)
        expect(store.state.sessionReadDates[key] == session.updatedAt)
        clock.advance(by = 3.seconds)
        store.receive(Action.clearDeletionError) { it.deletionError = null }
        store.finish()
    }

    @Test
    fun aSessionThatFinishesWhileOpenAfterTheDrawerSwappedItInStaysRead() {
        val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
        val first = OpenCodeSession(id = "ses_first", title = "First", projectID = "p", directory = "/project", updatedAt = 20_000, idleMilliseconds = 1_000, viewedMilliseconds = 1_000)
        var second = OpenCodeSession(id = "ses_second", title = "Second", projectID = "p", directory = "/project", updatedAt = 10_000, idleMilliseconds = 1_000, viewedMilliseconds = 1_000)
        val state = HomeFeature.State(server = server, snapshot = snapshot(sessions = listOf(first, second), activeSessionIDs = setOf(second.id)))
        state.sessionDetail = state.makeDetail(first, cachedItems = emptyList())
        // The session screen appeared once, for the first session.
        state.sessionDetail?.isVisible = true
        state.transcriptCache += (second.id to listOf(OpenCodeTranscriptItem.userText(id = "m2", text = "Second")))
        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.continuousClock = ImmediateClock()
                it.openCodeClient.viewSession = { _, _, _ -> }
            },
        ) {
            HomeFeature()
        }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.sessionTapped(second.id))
        expect(store.state.sessionDetail?.session?.id == second.id)
        expect(store.state.sessionDetail?.isVisible == true, "the same screen is still showing")

        // Its run finishes while the reader watches.
        second = second.copy(updatedAt = 30_000, idleMilliseconds = 3_000)
        store.send(Action.snapshotRefreshed(snapshot(sessions = listOf(first, second))))
        expect(!store.state.isUnread(store.state.sessions.first { it.id == second.id }))
        store.skipInFlightEffects()
    }

    @Test
    fun failedDeletionLeavesAReaderWhoMovedOnWhereTheyAre() {
        val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
        val session = OpenCodeSession(id = "ses_restore", title = "Restore me", projectID = "p", directory = "/project", updatedAt = 20_000)
        val other = OpenCodeSession(id = "ses_other", title = "Other", projectID = "p", directory = "/project", updatedAt = 10_000)
        val state = HomeFeature.State(server = server, snapshot = snapshot(sessions = listOf(session, other)))
        state.sessionDetail = SessionFeature.State(server = server, session = session, cachedItems = emptyList())
        state::lastViewedSessionIDs.withLock { value += (server.persistenceID to session.id) }
        state.transcriptCache += (other.id to listOf(OpenCodeTranscriptItem.userText(id = "m2", text = "Other")))
        val gate = CompletableDeferred<Unit>()
        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.continuousClock = ImmediateClock()
                it.openCodeClient.deleteSession = { _, _ ->
                    gate.await()
                    throw OpenCodeClientError(message = "Offline")
                }
            },
        ) {
            HomeFeature()
        }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.deleteSessionTapped(session.id))
        store.send(Action.deleteSessionConfirmed)
        store.send(Action.sessionTapped(other.id))
        gate.complete(Unit)
        store.receive<Action.deleteSessionResponse>()
        expect(store.state.sessions == listOf(session, other))
        expect(store.state.sessionDetail?.session == other)
        expect(store.state.lastViewedSessionIDs[server.persistenceID] == other.id)
        store.receive(Action.clearDeletionError)
        store.finish()
    }

    @Test
    fun openAndPinnedSessionsSurviveFallingOffTheSnapshotPage() {
        val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
        val open = OpenCodeSession(id = "ses_open", title = "Open", projectID = "p", directory = "/project", updatedAt = 10_000)
        val pinned = OpenCodeSession(id = "ses_pinned", title = "Pinned", projectID = "p", directory = "/project", updatedAt = 20_000)
        val forgotten = OpenCodeSession(id = "ses_old", title = "Old", projectID = "p", directory = "/project", updatedAt = 30_000)
        val newest = OpenCodeSession(id = "ses_new", title = "New", projectID = "p", directory = "/project", updatedAt = 40_000)
        val state = HomeFeature.State(server = server, snapshot = snapshot(sessions = listOf(open, pinned, forgotten)))
        state.sessionDetail = SessionFeature.State(server = server, session = open, cachedItems = emptyList())
        state::pinnedSessionKeys.withLock { value = listOf(state.sessionKey(pinned.id)) }
        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.continuousClock = ImmediateClock()
                it.openCodeClient.homeAttention = { _, _, _ -> emptySet() }
            },
        ) {
            HomeFeature()
        }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        // The newest-50 page now starts with sessions created since; the three
        // we knew have all fallen off it.
        store.send(Action.snapshotRefreshed(snapshot(sessions = listOf(newest))))
        expect(store.state.sessionDetail?.session == open)
        expect(store.state.sessions.map { it.id } == listOf(newest.id, open.id, pinned.id))
        expect(store.state.pinnedSessions == listOf(pinned))
        expect(store.state.recentSessions.map { it.id } == listOf(newest.id, open.id))

        // A real deletion still lands through its event.
        store.send(Action.serverEvent(OpenCodeServerEvent.sessionDeleted(sessionID = pinned.id)))
        expect(store.state.pinnedSessions.isEmpty())
        store.send(Action.serverEvent(OpenCodeServerEvent.sessionDeleted(sessionID = open.id)))
        expect(store.state.sessionDetail?.isDraft == true)
        store.skipInFlightEffects()
    }

    @Test
    fun sendingMovesTheSessionToTheTopOfRecentsBeforeTheServerCatchesUp() {
        val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
        val open = OpenCodeSession(id = "ses_open", title = "Open", projectID = "p", directory = "/project", updatedAt = 10_000)
        val newer = OpenCodeSession(id = "ses_newer", title = "Newer", projectID = "p", directory = "/project", updatedAt = 50_000)
        val state = HomeFeature.State(server = server, snapshot = snapshot(sessions = listOf(newer, open)))
        state.sessionDetail = SessionFeature.State(server = server, session = open, cachedItems = emptyList())
        state.sessionDetail?.draft = "Ship it"
        val sentAt = Instant.ofEpochSecond(100)
        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.continuousClock = ImmediateClock()
                it.date = DateGenerator.constant(sentAt)
                it.uuid = UUIDGenerator.incrementing()
                it.openCodeClient.homeAttention = { _, _, _ -> emptySet() }
                it.openCodeClient.sendPrompt = { _, _, _, _, _, _, _ -> }
            },
        ) {
            HomeFeature()
        }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        expect(store.state.recentSessions.map { it.id } == listOf(newer.id, open.id))

        store.send(sessionAction(SessionFeature.Action.sendTapped))
        expect(store.state.recentSessions.map { it.id } == listOf(open.id, newer.id))
        expect(!store.state.isUnread(store.state.sessions.first { it.id == open.id }), "The reader's own send is not unread")

        // A list fetched before the server recorded the send must not demote it.
        store.send(Action.snapshotRefreshed(snapshot(sessions = listOf(newer, open))))
        expect(store.state.recentSessions.map { it.id } == listOf(open.id, newer.id))

        // Once the server reports the send, the server wins again.
        val caughtUp = open.copy(updatedAt = 120_000)
        store.send(Action.snapshotRefreshed(snapshot(sessions = listOf(newer, caughtUp))))
        expect(store.state.sessions.first { it.id == open.id }.updatedAt == caughtUp.updatedAt)
        expect(store.state.localActivity.isEmpty())
        store.skipInFlightEffects()
    }

    @Test
    fun serverTimeBehindAFastPhoneClockStillSettlesTheLocalSend() {
        val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
        val open = OpenCodeSession(id = "ses_open", title = "Open", projectID = "p", directory = "/project", updatedAt = 10_000)
        val newer = OpenCodeSession(id = "ses_newer", title = "Newer", projectID = "p", directory = "/project", updatedAt = 50_000)
        val state = HomeFeature.State(server = server, snapshot = snapshot(sessions = listOf(newer, open)))
        state.sessionDetail = SessionFeature.State(server = server, session = open, cachedItems = emptyList())
        state.sessionDetail?.draft = "Ship it"
        // The phone's clock runs an hour ahead of the server's.
        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.continuousClock = ImmediateClock()
                it.date = DateGenerator.constant(Instant.ofEpochSecond(3_600))
                it.uuid = UUIDGenerator.incrementing()
                it.openCodeClient.homeAttention = { _, _, _ -> emptySet() }
                it.openCodeClient.sendPrompt = { _, _, _, _, _, _, _ -> }
            },
        ) {
            HomeFeature()
        }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(sessionAction(SessionFeature.Action.sendTapped))
        expect(store.state.recentSessions.map { it.id } == listOf(open.id, newer.id))

        val caughtUp = open.copy(updatedAt = 60_000)
        store.send(Action.snapshotRefreshed(snapshot(sessions = listOf(newer, caughtUp))))
        expect(store.state.sessions.first { it.id == open.id }.updatedAt == caughtUp.updatedAt)
        expect(store.state.localActivity.isEmpty())
        expect(store.state.recentSessions.map { it.id } == listOf(open.id, newer.id))
        store.skipInFlightEffects()
    }

    @Test
    fun deletingOpenSessionReturnsToDraftAndStaleSnapshotCannotResurrectIt() {
        val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
        val session = OpenCodeSession(id = "ses_open", title = "Open", projectID = "p", directory = "/project", updatedAt = Instant.now().toEpochMilli())
        val snapshot = snapshot(sessions = listOf(session))
        val state = HomeFeature.State(server = server, snapshot = snapshot)
        state.sessionDetail = SessionFeature.State(server = server, session = session, cachedItems = emptyList())
        state::lastViewedSessionIDs.withLock { value += (server.persistenceID to session.id) }
        val gate = CompletableDeferred<Unit>()
        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.continuousClock = ImmediateClock()
                it.openCodeClient.deleteSession = { _, _ -> gate.await() }
                it.openCodeClient.homeAttention = { _, _, _ -> emptySet() }
            },
        ) {
            HomeFeature()
        }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.deleteSessionTapped(session.id))
        store.send(Action.deleteSessionConfirmed)
        expect(store.state.sessionDetail?.isDraft == true)
        store.send(Action.snapshotRefreshed(snapshot))
        expect(store.state.sessions.isEmpty())
        expect(store.state.recentlyDeletedIDs == setOf(session.id))
        gate.complete(Unit)
        store.receive<Action.deleteSessionResponse>()
        expect(store.state.lastViewedSessionIDs[server.persistenceID] == null)
        store.finish()
    }

    @Test
    fun idleSnapshotRevalidatesOpenTranscriptEvenWhenAlreadyViewed() {
        val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
        val session = OpenCodeSession(id = "ses_resume", title = "Resume", projectID = "p", directory = "/project", updatedAt = 10_000, idleMilliseconds = 2000, viewedMilliseconds = 2000)
        val snapshot = snapshot(sessions = listOf(session))
        val old = listOf<OpenCodeTranscriptItem>(OpenCodeTranscriptItem.userText(id = "m1", text = "Hello"))
        val latest = old + OpenCodeTranscriptItem.assistantMarkdown(id = "m2", blocks = OpenCodeMarkdownBlock.blocks(from = "Finished while away"))
        val state = HomeFeature.State(server = server, snapshot = snapshot)
        state.sessionDetail = SessionFeature.State(server = server, session = session)
        state.sessionDetail?.items = old
        state.sessionDetail?.isLoading = false
        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.continuousClock = ImmediateClock()
                it.openCodeClient.transcript = { _, _, _ -> latest }
                it.openCodeClient.homeAttention = { _, _, _ -> emptySet() }
            },
        ) {
            HomeFeature()
        }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        store.send(Action.snapshotRefreshed(snapshot))
        store.receive({ it.presented is SessionFeature.Action.refreshResponse })
        expect(store.state.sessionDetail?.items == latest)
        store.finish()
    }

    @Test
    fun serverViewedWatermarkWinsOverLegacyReadDatesAndDoesNotCoverNewerIdle() {
        val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
        val session = OpenCodeSession(id = "ses_view", title = "Viewed", projectID = "p", directory = "/project", updatedAt = 10_000, idleMilliseconds = 2000, viewedMilliseconds = 1000)
        val state = HomeFeature.State(server = server, snapshot = snapshot(sessions = listOf(session)))
        val store = TestStore(initialState = { state }) { HomeFeature() }
        expect(store.state.isUnread(session))
        store.send(Action.viewAcknowledged(sessionID = session.id, idle = 1500)) {
            it.sessions = listOf(it.sessions[0].copy(viewedMilliseconds = 1500))
        }
        expect(store.state.isUnread(store.state.sessions[0]))
        store.send(Action.viewAcknowledged(sessionID = session.id, idle = 2000)) {
            it.sessions = listOf(it.sessions[0].copy(viewedMilliseconds = 2000))
        }
        expect(!store.state.isUnread(store.state.sessions[0]))
        store.send(Action.markUnread(session.id)) {
            it::sessionReadDates.withLock { value += (state.sessionKey(session.id) to session.updatedAt - 1_000) }
            it::manuallyUnreadSessionKeys.withLock { value += state.sessionKey(session.id) }
        }
        expect(store.state.isUnread(store.state.sessions[0]))
    }

    @Test
    fun markReadClearsServerUnreadIndicatorBeforeReceiptCompletes() {
        val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
        val session = OpenCodeSession(
            id = "ses_unread",
            title = "Unread",
            projectID = "p",
            directory = "/project",
            updatedAt = Instant.now().toEpochMilli(),
            idleMilliseconds = 2000,
            viewedMilliseconds = 1000,
        )
        val state = HomeFeature.State(server = server, snapshot = snapshot(sessions = listOf(session)))
        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.openCodeClient.viewSession = { _, _, _ -> throw OpenCodeClientError(message = "Offline") }
            },
        ) {
            HomeFeature()
        }
        expect(store.state.indicator(session) == HomeFeature.State.Indicator.unread)
        store.send(Action.markRead(session.id)) {
            it.sessions = listOf(it.sessions[0].copy(viewedMilliseconds = 2000))
            it.locallyViewedMilliseconds += (session.id to 2000L)
            it::sessionReadDates.withLock { value += (state.sessionKey(session.id) to session.updatedAt) }
        }
        expect(store.state.indicator(store.state.sessions[0]) == HomeFeature.State.Indicator.none)
        store.finish()
    }

    @Test
    fun openingSessionClearsUnreadInTheSelectionStep() {
        val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
        val session = OpenCodeSession(
            id = "ses_open",
            title = "Open me",
            projectID = "p",
            directory = "/project",
            updatedAt = 10_000,
            idleMilliseconds = 2_000,
            viewedMilliseconds = 1_000,
        )
        val state = HomeFeature.State(server = server, snapshot = snapshot(sessions = listOf(session)))
        state.transcriptCache += (session.id to emptyList())
        state::manuallyUnreadSessionKeys.withLock { value += state.sessionKey(session.id) }
        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.openCodeClient.viewSession = { _, _, _ -> throw OpenCodeClientError(message = "Offline") }
            },
        ) {
            HomeFeature()
        }

        store.send(Action.sessionTapped(session.id)) {
            it.sessions = listOf(it.sessions[0].copy(viewedMilliseconds = 2_000))
            it.locallyViewedMilliseconds += (session.id to 2_000L)
            it::lastViewedSessionIDs.withLock { value += (server.persistenceID to session.id) }
            it::sessionReadDates.withLock { value += (state.sessionKey(session.id) to session.updatedAt) }
            it::manuallyUnreadSessionKeys.withLock { value -= state.sessionKey(session.id) }
            it.detailGeneration = 2
            it.sessionDetail = SessionFeature.State(server = server, session = session, cachedItems = emptyList(), navigationID = 2)
        }
        expect(store.state.indicator(store.state.sessions[0]) == HomeFeature.State.Indicator.none)
        store.finish()
    }

    @Test
    fun staleSnapshotAndFailedReceiptDoNotResurrectOpenedUnreadSession() {
        val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
        val session = OpenCodeSession(
            id = "ses_stale",
            title = "Stale",
            projectID = "p",
            directory = "/project",
            updatedAt = 10_000,
            idleMilliseconds = 2_000,
            viewedMilliseconds = 1_000,
        )
        val snapshot = snapshot(sessions = listOf(session))
        val state = HomeFeature.State(server = server, snapshot = snapshot)
        state.transcriptCache += (session.id to emptyList())
        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.continuousClock = ImmediateClock()
                it.openCodeClient.viewSession = { _, _, _ -> throw OpenCodeClientError(message = "Offline") }
                it.openCodeClient.homeAttention = { _, _, _ -> emptySet() }
            },
        ) {
            HomeFeature()
        }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.sessionTapped(session.id))
        expect(store.state.sessions[0].viewedMilliseconds == 2_000L)
        store.send(Action.snapshotRefreshed(snapshot))
        expect(store.state.sessions[0].viewedMilliseconds == 2_000L)
        expect(store.state.indicator(store.state.sessions[0]) == HomeFeature.State.Indicator.none)
        store.skipInFlightEffects()
    }

    @Test
    fun visibleRunningSessionStaysReadWhenItsIdleWatermarkArrives() {
        val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
        val running = OpenCodeSession(id = "ses_running", title = "Running", projectID = "p", directory = "/project", updatedAt = 10_000)
        val idle = running.copy(updatedAt = 20_000, idleMilliseconds = 3_000)
        val state = HomeFeature.State(server = server, snapshot = snapshot(sessions = listOf(running), activeSessionIDs = setOf(running.id)))
        state.transcriptCache += (running.id to emptyList())
        state.sessionDetail = SessionFeature.State(server = server, session = running, cachedItems = emptyList(), isRunning = true)
        state.sessionDetail?.isVisible = true
        state.sessionDetail?.hasRenderedFirstContentFrame = true
        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.continuousClock = ImmediateClock()
                it.openCodeClient.getSession = { _, _ -> idle }
                it.openCodeClient.transcript = { _, _, _ -> emptyList() }
                it.openCodeClient.viewSession = { _, _, watermark -> expect(watermark == 3_000L) }
                it.openCodeClient.homeAttention = { _, _, _ -> emptySet() }
            },
        ) {
            HomeFeature()
        }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.snapshotRefreshed(snapshot(sessions = listOf(idle))))
        expect(store.state.sessions[0].viewedMilliseconds == 3_000L)
        expect(store.state.indicator(store.state.sessions[0]) == HomeFeature.State.Indicator.none)
        store.receive({ it.presented is SessionFeature.Action.viewAcknowledged })
        expect(store.state.sessions[0].viewedMilliseconds == 3_000L)
        store.skipInFlightEffects()
    }

    @Test
    fun tappingSessionOpensDetailAndLoadsTranscript() {
        val server = testServer
        val session = testSession()
        val items = listOf(
            OpenCodeTranscriptItem.userText(id = "m1", text = "hello"),
            OpenCodeTranscriptItem.assistantMarkdown(id = "m2-0", blocks = OpenCodeMarkdownBlock.blocks(from = "hi")),
        )
        val gate = CompletableDeferred<Unit>()
        var requests = 0
        val store = TestStore(
            initialState = { HomeFeature.State(server = server, snapshot = snapshot(sessions = listOf(session))) },
            withDependencies = {
                it.continuousClock = ImmediateClock()
                it.openCodeClient.transcript = { _, _, _ ->
                    requests += 1
                    gate.await()
                    items
                }
                it.openCodeClient.familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList()) }
                it.openCodeClient.pendingInputs = { _, _ -> emptyList() }
                it.openCodeClient.sessionFamily = { _, root -> OpenCodeSessionFamily(sessions = listOf(root), activeSessionIDs = emptySet()) }
                it.openCodeClient.homeAttention = { _, _, _ -> emptySet() }
            },
        ) {
            HomeFeature()
        }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.sessionTapped("ses_1"))
        store.receive(Action.sessionNavigationReady("ses_1")) {
            it.sessionDetail = SessionFeature.State(server = server, session = session, initialTranscriptRequestIsExternal = true, navigationID = 2)
            it.detailGeneration = 2
        }
        store.send(sessionAction(SessionFeature.Action.task))
        expect(requests == 1)
        gate.complete(Unit)
        store.receive(Action.selectedTranscriptResponse(sessionID = "ses_1", result = Result.success(items))) {
            it.transcriptCache += ("ses_1" to items)
        }
        store.receive(sessionAction(SessionFeature.Action.transcriptResponse(sessionID = "ses_1", result = Result.success(items)))) {
            it.sessionDetail?.initialTranscriptRequestIsExternal = false
            it.sessionDetail?.initialTranscriptIsFresh = true
            it.sessionDetail?.isLoading = false
            it.sessionDetail?.items = items
        }
        expect(requests == 1, "Session task must join the tap-time load instead of issuing a duplicate")
    }

    @Test
    fun draftOpenedBeforeTheFirstSnapshotLearnsItsProjects() {
        val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
        val recent = OpenCodeProject(id = "proj_recent", name = "recent", directory = "/tmp/recent")
        val other = OpenCodeProject(id = "proj_other", name = "other", directory = "/tmp/other")
        val session = OpenCodeSession(id = "ses_1", title = "Latest work", projectID = "proj_recent", directory = "/tmp/recent", updatedAt = 1_000_000)
        // Cold launch with nothing cached: Home starts empty and opens a draft.
        val state = HomeFeature.State(server = server, snapshot = snapshot(sessions = emptyList()))
        state.connectionStatus = HomeFeature.State.ConnectionStatus.reconnecting
        expect(state.sessionDetail?.isDraft == true)
        expect(state.sessionDetail?.projects?.isEmpty() == true)
        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.continuousClock = ImmediateClock()
                it.openCodeClient.homeAttention = { _, _, _ -> emptySet() }
                it.openCodeClient.transcript = { _, _, _ -> emptyList() }
            },
        ) {
            HomeFeature()
        }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.snapshotRefreshed(snapshot(projects = listOf(other, recent), sessions = listOf(session))))
        expect(store.state.sessionDetail?.isDraft == true)
        expect(store.state.sessionDetail?.projects == listOf(recent, other))
        expect(store.state.sessionDetail?.draftProject == recent)
        store.skipInFlightEffects()
    }

    @Test
    fun switchingSessionsCancelsThePreviousSessionsEffects() {
        val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
        val first = OpenCodeSession(id = "ses_first", title = "First", projectID = "p", directory = "/work", updatedAt = 2_000_000)
        val second = OpenCodeSession(id = "ses_second", title = "Second", projectID = "p", directory = "/work", updatedAt = 1_000_000)
        val state = HomeFeature.State(server = server, snapshot = snapshot(sessions = listOf(first, second)))
        state.transcriptCache += (first.id to emptyList())
        state.transcriptCache += (second.id to emptyList())
        // Microphone capture that never ends on its own: only cancellation
        // (or an explicit stop, which nothing sends on a swap) can end it.
        var captureEnded = false
        val transcripts = flow<String> {
            try {
                CompletableDeferred<Unit>().await()
            } finally {
                captureEnded = true
            }
        }
        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.continuousClock = ImmediateClock()
                it.openCodeClient.homeAttention = { _, _, _ -> emptySet() }
                it.dictation.start = { transcripts }
                it.haptics.impact = {}
            },
        ) {
            HomeFeature()
        }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.sessionTapped(first.id))
        store.send(sessionAction(SessionFeature.Action.micTapped))
        store.receive(sessionAction(SessionFeature.Action.dictationReady))
        expect(store.state.sessionDetail?.isDictating == true)

        // Swapping the presented session in place must end the first session's
        // work with it: cancelling the effect stops reading the capture stream.
        store.send(Action.sessionTapped(second.id))
        expect(store.state.sessionDetail?.session == second)
        expect(store.state.sessionDetail?.isDictating == false)
        expect(captureEnded, "the previous session's dictation kept running")
        store.skipInFlightEffects()
    }

    @Test
    fun newSessionOpensDraftWithMostRecentProject() {
        val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
        val recent = OpenCodeProject(id = "proj_recent", name = "recent", directory = "/tmp/recent")
        val other = OpenCodeProject(id = "proj_other", name = "other", directory = "/tmp/other")
        val session = OpenCodeSession(id = "ses_1", title = "Latest work", projectID = "proj_recent", directory = "/tmp/recent", updatedAt = 1_000_000)
        val created = OpenCodeSession(id = "ses_new", title = "Untitled session", projectID = "proj_recent", directory = "/tmp/recent", updatedAt = 2_000_000)
        val store = TestStore(
            initialState = { HomeFeature.State(server = server, snapshot = snapshot(projects = listOf(other, recent), sessions = listOf(session))) },
            withDependencies = {
                it.openCodeClient.familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList()) }
            },
        ) {
            HomeFeature()
        }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        expect(store.state.projectsByRecency == listOf(recent, other))

        store.send(Action.newSessionTapped) {
            it.sessionDetail = SessionFeature.State(server = server, draftProject = recent, projects = listOf(recent, other), navigationID = 2)
            it.detailGeneration = 2
        }
        store.send(sessionAction(SessionFeature.Action.sessionCreated(created))) {
            it.sessions = listOf(created, session)
            it.sessionDetail?.session = created
            it::lastViewedSessionIDs.withLock { value += (server.persistenceID to created.id) }
        }
        expect(
            store.state.cachedHomeSnapshot == CachedHomeSnapshot(
                serverID = server.persistenceID,
                snapshot = snapshot(projects = listOf(other, recent), sessions = listOf(created, session)),
            ),
        )
    }

    @Test
    fun reopeningSessionStartsFromCachedTranscript() {
        val server = testServer
        val session = testSession()
        val items = listOf<OpenCodeTranscriptItem>(OpenCodeTranscriptItem.userText(id = "m1", text = "hello"))
        val initialState = HomeFeature.State(server = server, snapshot = snapshot(sessions = listOf(session)))
        initialState.transcriptCache += ("ses_1" to items)

        val store = TestStore(initialState = { initialState }) {
            HomeFeature()
        }

        store.send(Action.sessionTapped("ses_1")) {
            val key = it.sessionKey("ses_1")
            it::sessionReadDates.withLock { value += (key to session.updatedAt) }
            it::lastViewedSessionIDs.withLock { value += (server.persistenceID to session.id) }
            it.detailGeneration = 2
            it.sessionDetail = SessionFeature.State(server = server, session = session, cachedItems = items, navigationID = 2)
        }
        expect(store.state.sessionDetail?.isLoading == false)
        expect(store.state.sessionDetail?.items == items)
    }

    @Test
    fun serverEventsDriveActivityTitlesAndDeletes() {
        val server = testServer
        val session = testSession()
        val refreshed = snapshot(sessions = listOf(session), activeSessionIDs = setOf("ses_1"))
        val state = HomeFeature.State(server = server, snapshot = snapshot(sessions = listOf(session)))
        // Already cached, so the snapshot schedules no prefetch (covered elsewhere).
        state.transcriptCache += (session.id to listOf(OpenCodeTranscriptItem.userText(id = "m1", text = "cached")))
        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.continuousClock = ImmediateClock()
                it.openCodeClient.homeSnapshot = { refreshed }
                it.openCodeClient.homeAttention = { _, _, _ -> emptySet() }
            },
        ) {
            HomeFeature()
        }

        store.send(Action.serverEvent(OpenCodeServerEvent.executionStarted(sessionID = "ses_1"))) {
            it.activeSessionIDs = setOf("ses_1")
            it.runsStartedDuringRefresh = setOf("ses_1")
        }
        val renamed = session.copy(title = "Login fixed")
        val renamedCache = CachedHomeSnapshot(
            serverID = server.persistenceID,
            snapshot = snapshot(sessions = listOf(renamed), activeSessionIDs = setOf(session.id)),
        )
        store.send(Action.serverEvent(OpenCodeServerEvent.sessionRenamed(sessionID = "ses_1", title = "Login fixed"))) {
            it.sessions = listOf(it.sessions[0].copy(title = "Login fixed"))
            it::cachedHomeSnapshot.withLock { value = renamedCache }
        }
        expect(store.state.cachedHomeSnapshot == renamedCache)
        store.send(Action.serverEvent(OpenCodeServerEvent.executionEnded(sessionID = "ses_1"))) {
            it.activeSessionIDs = emptySet()
            // The ended event triggers a fresh request, so recording restarts.
            it.runsStartedDuringRefresh = emptySet()
        }
        store.receive<Action.snapshotRefreshed> {
            val key = it.sessionKey(session.id)
            it.sessions = refreshed.sessions
            it.activeSessionIDs = refreshed.activeSessionIDs
            it::sessionReadDates.withLock { value += (key to session.updatedAt) }
            it::initializedInboxServerIDs.withLock { value = setOf(server.persistenceID) }
            it::cachedHomeSnapshot.withLock { value = CachedHomeSnapshot(serverID = server.persistenceID, snapshot = refreshed) }
        }
        val savedServer by Shared(SharedKey.savedServer)
        val savedServers by Shared(SharedKey.savedServers)
        expect(savedServer == server)
        expect(savedServers == listOf(server))
        store.receive(Action.attentionRefreshed(emptySet()))
        val deletedCache = CachedHomeSnapshot(serverID = server.persistenceID, snapshot = snapshot(sessions = emptyList()))
        store.send(Action.serverEvent(OpenCodeServerEvent.sessionDeleted(sessionID = "ses_1"))) {
            val key = it.sessionKey(session.id)
            it.sessions = emptyList()
            it.activeSessionIDs = emptySet()
            it.transcriptCache = emptyMap()
            it::sessionReadDates.withLock { value -= key }
            it::cachedHomeSnapshot.withLock { value = deletedCache }
        }
        expect(store.state.cachedHomeSnapshot == deletedCache)
    }

    @Test
    fun alternateAddressPropagatesIntoSelectedSession() {
        val primary = OpenCodeServer(url = "http://primary.test", username = "opencode", password = "pw", alternateURLs = listOf("http://alternate.test"))
        val alternate = primary.at(url = "http://alternate.test")
        val session = OpenCodeSession(id = "ses_1", title = "Selected", projectID = "p", directory = "/project", updatedAt = Instant.now().toEpochMilli())
        val state = HomeFeature.State(server = primary, snapshot = snapshot(sessions = listOf(session)))
        state.sessionDetail = SessionFeature.State(server = primary, session = session, cachedItems = emptyList())
        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.continuousClock = TestClock()
                it.openCodeClient.getSession = { _, _ -> session }
                it.openCodeClient.transcript = { _, _, _ -> emptyList() }
                it.openCodeClient.sessionFamily = { _, root -> OpenCodeSessionFamily(sessions = listOf(root), activeSessionIDs = emptySet()) }
                it.openCodeClient.familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) }
                it.openCodeClient.pendingInputs = { _, _ -> emptyList() }
            },
        ) {
            HomeFeature()
        }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.serverConnected(alternate))
        store.receive(sessionAction(SessionFeature.Action.eventStreamConnected(alternate)))
        expect(store.state.server == alternate)
        expect(store.state.sessionDetail?.server == alternate)
        store.skipInFlightEffects()
    }

    @Test
    fun unauthorizedEventStreamPausesWithCredentialError() {
        val server = OpenCodeServer(url = "http://server.test", username = "opencode", password = "wrong")
        val state = HomeFeature.State(server = server, snapshot = snapshot(sessions = emptyList()))
        state.connectionStatus = HomeFeature.State.ConnectionStatus.reconnecting
        val store = TestStore(initialState = { state }) { HomeFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        val error = OpenCodeClientError(message = "The server rejected these credentials.", statusCode = 401)

        store.send(Action.eventStreamFailed(error)) {
            it.connectionStatus = HomeFeature.State.ConnectionStatus.disconnected
            it.connectionErrorMessage = error.message
        }
        store.send(Action.retryConnectionTapped)
        store.skipInFlightEffects()
    }

    @Test
    fun retryReplacesCachedHomeWithAuthoritativeSnapshotAndReconnects() {
        val server = OpenCodeServer(url = "https://saved.example", username = "kit", password = "secret")
        val oldSession = OpenCodeSession(id = "ses_old", title = "Old work", projectID = "proj_old", directory = "/old", updatedAt = 1_000_000)
        val latest = OpenCodeSession(id = "ses_latest", title = "Latest work", projectID = "proj_latest", directory = "/latest", updatedAt = 2_000_000)
        val refreshed = snapshot(
            projects = listOf(OpenCodeProject(id = "proj_latest", name = "Latest", directory = "/latest")),
            sessions = listOf(latest),
            activeSessionIDs = setOf(latest.id),
        )
        val state = HomeFeature.State(
            server = server,
            snapshot = snapshot(projects = listOf(OpenCodeProject(id = "proj_old", name = "Old", directory = "/old")), sessions = listOf(oldSession)),
        )
        state.connectionStatus = HomeFeature.State.ConnectionStatus.disconnected
        state.persistSnapshot()
        state.transcriptCache += (latest.id to listOf(OpenCodeTranscriptItem.userText(id = "m1", text = "cached")))
        // The kit runs an effect as soon as it is sent; the gate holds the
        // snapshot so the cached rows can be checked before it lands.
        val gate = CompletableDeferred<Unit>()
        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.continuousClock = ImmediateClock()
                it.openCodeClient.homeSnapshot = { requestedServer ->
                    expect(requestedServer == server)
                    gate.await()
                    refreshed
                }
                it.openCodeClient.homeAttention = { _, _, _ -> emptySet() }
            },
        ) {
            HomeFeature()
        }

        store.send(Action.retryConnectionTapped) {
            it.connectionStatus = HomeFeature.State.ConnectionStatus.reconnecting
        }
        expect(store.state.sessions == listOf(oldSession))
        gate.complete(Unit)
        store.receive(Action.snapshotRefreshed(refreshed)) {
            val key = it.sessionKey(latest.id)
            it.projects = refreshed.projects
            it.sessions = listOf(latest)
            it.activeSessionIDs = setOf(latest.id)
            it.sessionDetail?.projects = refreshed.projects
            it.connectionStatus = HomeFeature.State.ConnectionStatus.connected
            it::sessionReadDates.withLock { value += (key to latest.updatedAt) }
            it::initializedInboxServerIDs.withLock { value = setOf(server.persistenceID) }
            it::cachedHomeSnapshot.withLock { value = CachedHomeSnapshot(serverID = server.persistenceID, snapshot = refreshed) }
        }
        store.receive(Action.attentionRefreshed(emptySet()))
        expect(store.state.isConnected)
        expect(unreadIDs(store.state).isEmpty())
        expect(store.state.cachedHomeSnapshot == CachedHomeSnapshot(serverID = server.persistenceID, snapshot = refreshed))
        store.finish()
    }

    @Test
    fun lostConnectionAndFailedRetryRetainCachedSessions() {
        val server = OpenCodeServer(url = "https://saved.example", username = "kit", password = "secret")
        val session = OpenCodeSession(id = "ses_cached", title = "Cached work", projectID = "proj_cached", directory = "/work", updatedAt = 1_000_000)
        val snapshot = snapshot(
            projects = listOf(OpenCodeProject(id = "proj_cached", name = "Work", directory = "/work")),
            sessions = listOf(session),
            activeSessionIDs = setOf(session.id),
        )
        val state = HomeFeature.State(server = server, snapshot = snapshot)
        state.persistSnapshot()
        val cache = state.cachedHomeSnapshot
        val items = listOf<OpenCodeTranscriptItem>(OpenCodeTranscriptItem.userText(id = "m_cached", text = "Cached message"))
        state.sessionDetail = SessionFeature.State(server = server, session = session, cachedItems = items)
        state.sessionDetail?.draft = "Unsent draft"
        state.hasConnected = true
        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.continuousClock = ImmediateClock()
                it.openCodeClient.homeSnapshot = { throw OpenCodeClientError(message = "Offline") }
            },
        ) {
            HomeFeature()
        }

        store.send(Action.connectionLost) {
            it.connectionStatus = HomeFeature.State.ConnectionStatus.reconnecting
        }
        store.receive(sessionAction(SessionFeature.Action.requestsConnectionChanged(false))) {
            it.sessionDetail?.requestsConnectionIsAvailable = false
            it.sessionDetail?.requestFailureAwaitingReconnect = true
        }
        expect(store.state.sessions == listOf(session))
        expect(store.state.indicator(session) == HomeFeature.State.Indicator.running, "Reconnecting keeps the last-known state on screen")
        store.send(Action.retryConnectionTapped)
        store.receive(Action.snapshotFailed) {
            it.connectionStatus = HomeFeature.State.ConnectionStatus.disconnected
        }
        store.receive(sessionAction(SessionFeature.Action.requestsConnectionChanged(false)))
        expect(!store.state.isConnected)
        expect(store.state.projects == snapshot.projects)
        expect(store.state.sessions == snapshot.sessions)
        expect(store.state.cachedHomeSnapshot == cache)
        expect(store.state.sessionDetail?.draft == "Unsent draft")
        expect(store.state.initializedInboxServerIDs.isEmpty())
        store.finish()
    }

    @Test
    fun offlineHomeOpensCachedSessionsWhileReconnecting() = offlineHomeOpensCachedSessionsAndOnlyAnOutageBlocksNewSessions(isReconnecting = true)

    @Test
    fun offlineHomeOpensCachedSessionsDuringAnOutage() = offlineHomeOpensCachedSessionsAndOnlyAnOutageBlocksNewSessions(isReconnecting = false)

    private fun offlineHomeOpensCachedSessionsAndOnlyAnOutageBlocksNewSessions(isReconnecting: Boolean) {
        val server = OpenCodeServer(url = "https://saved.example", username = "kit", password = "secret")
        val session = OpenCodeSession(id = "ses_cached", title = "Cached work", projectID = "proj_cached", directory = "/work", updatedAt = 1_000_000)
        val state = HomeFeature.State(server = server, snapshot = snapshot(sessions = listOf(session), activeSessionIDs = setOf(session.id)))
        state.connectionStatus = if (isReconnecting) HomeFeature.State.ConnectionStatus.reconnecting else HomeFeature.State.ConnectionStatus.disconnected
        val items = listOf<OpenCodeTranscriptItem>(OpenCodeTranscriptItem.userText(id = "m_cached", text = "Cached message"))
        state.transcriptCache += (session.id to items)
        val store = TestStore(initialState = { state }) { HomeFeature() }

        store.send(Action.sessionTapped(session.id)) {
            val key = it.sessionKey(session.id)
            it.detailGeneration = 2
            it.sessionDetail = SessionFeature.State(server = server, session = session, cachedItems = items, isRunning = true, navigationID = 2)
            it::sessionReadDates.withLock { value += (key to session.updatedAt) }
            it::lastViewedSessionIDs.withLock { value += (server.persistenceID to session.id) }
        }
        expect(store.state.sessionDetail?.items == items)
        expect(store.state.sessionDetail?.isLoading == false)
        // A retrying connection still allows a draft (first send reports any
        // failure); a confirmed outage keeps the cached detail in place.
        if (isReconnecting) {
            store.send(Action.newSessionTapped) {
                it.detailGeneration = 3
                it.sessionDetail = SessionFeature.State(server = server, draftProject = null, projects = emptyList(), inheritedModel = null, navigationID = 3)
            }
        } else {
            store.send(Action.newSessionTapped)
        }
        expect(store.state.sessionDetail?.isDraft == isReconnecting)
        expect(store.state.sessions == listOf(session))
        expect(store.state.indicator(session) == HomeFeature.State.Indicator.none, "A cached launch has no live stream to trust")
        store.finish()
    }

    @Test
    fun pinningAndReadStateAreLocalReversibleChoices() {
        val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
        val now = Instant.now().toEpochMilli()
        val first = OpenCodeSession(id = "ses_1", title = "First", projectID = "proj_1", directory = "/tmp/demo", updatedAt = now)
        val second = OpenCodeSession(id = "ses_2", title = "Second", projectID = "proj_1", directory = "/tmp/demo", updatedAt = now)
        val state = HomeFeature.State(server = server, snapshot = snapshot(sessions = listOf(first, second)))
        state::sessionReadDates.withLock {
            value += (state.sessionKey(first.id) to first.updatedAt)
            value += (state.sessionKey(second.id) to second.updatedAt)
        }
        val store = TestStore(initialState = { state }) { HomeFeature() }

        store.send(Action.togglePin(second.id)) {
            val key = it.sessionKey(second.id)
            it::pinnedSessionKeys.withLock { value = listOf(key) }
        }
        expect(store.state.pinnedSessions == listOf(second))
        store.send(Action.togglePin(second.id)) { it::pinnedSessionKeys.withLock { value = emptyList() } }
        expect(store.state.visibleSessions.map { it.id }.toSet() == setOf(first.id, second.id))
        store.send(Action.markUnread(first.id)) {
            val key = it.sessionKey(first.id)
            it::sessionReadDates.withLock { value += (key to first.updatedAt - 1_000) }
        }
        expect(unreadIDs(store.state) == setOf(first.id))
        store.send(Action.markRead(first.id)) {
            val key = it.sessionKey(first.id)
            it::sessionReadDates.withLock { value += (key to first.updatedAt) }
        }
    }

    @Test
    fun indicatorsPrioritizeAttentionThenRunningThenUnread() {
        val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
        val session = OpenCodeSession(id = "ses_1", title = "Work", projectID = "proj_1", directory = "/tmp/demo", updatedAt = 2_000_000)
        val state = HomeFeature.State(server = server, snapshot = snapshot(sessions = listOf(session)))
        state::sessionReadDates.withLock { value += (state.sessionKey(session.id) to 1_000_000L) }
        expect(state.indicator(session) == HomeFeature.State.Indicator.unread)
        state.activeSessionIDs = setOf(session.id)
        expect(state.indicator(session) == HomeFeature.State.Indicator.running)
        state.attentionSessionIDs = setOf(session.id)
        expect(state.indicator(session) == HomeFeature.State.Indicator.attention)
    }

    @Test
    fun runningAndUnreadSessionsRemainInRecents() {
        val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
        val now = Instant.now().toEpochMilli()
        val running = OpenCodeSession(id = "ses_running", title = "Running", projectID = "proj", directory = "/tmp/demo", updatedAt = now)
        val recent = OpenCodeSession(id = "ses_recent", title = "Recent", projectID = "proj", directory = "/tmp/demo", updatedAt = now)
        val state = HomeFeature.State(server = server, snapshot = snapshot(sessions = listOf(running, recent), activeSessionIDs = setOf(running.id)))
        state::sessionReadDates.withLock {
            value += (state.sessionKey(running.id) to running.updatedAt)
            value += (state.sessionKey(recent.id) to recent.updatedAt)
        }

        expect(state.recentSessions.map { it.id }.toSet() == setOf(running.id, recent.id))
        state::manuallyUnreadSessionKeys.withLock { value += state.sessionKey(recent.id) }
        expect(state.recentSessions.map { it.id }.toSet() == setOf(running.id, recent.id))
    }

    @Test
    fun pinnedChatStaysPinnedWhileItNeedsInput() {
        val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
        val session = OpenCodeSession(id = "chat", title = "Chat", projectID = "p", directory = "/project", updatedAt = Instant.now().toEpochMilli())
        val state = HomeFeature.State(server = server, snapshot = snapshot(sessions = listOf(session)))
        val store = TestStore(initialState = { state }) { HomeFeature() }
        store.send(Action.togglePin(session.id)) {
            it::pinnedSessionKeys.withLock { value = listOf(state.sessionKey(session.id)) }
        }
        store.send(Action.attentionRefreshed(setOf(session.id))) { it.attentionSessionIDs = setOf(session.id) }
        expect(store.state.pinnedSessions == listOf(session))
        expect(store.state.recentSessions.isEmpty())
        expect(store.state.isPinned(session.id))
        store.send(Action.attentionRefreshed(emptySet())) { it.attentionSessionIDs = emptySet() }
        expect(store.state.pinnedSessions == listOf(session))
        expect(store.state.recentSessions.isEmpty())
    }

    @Test
    fun attentionEventsReconcileFromAuthoritativePendingRequests() {
        val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
        val session = OpenCodeSession(id = "ses_1", title = "Work", projectID = "proj_1", directory = "/tmp/demo", updatedAt = Instant.now().toEpochMilli())
        val store = TestStore(
            initialState = {
                HomeFeature.State(
                    server = server,
                    snapshot = snapshot(projects = listOf(OpenCodeProject(id = "proj_1", name = "demo", directory = "/tmp/demo")), sessions = listOf(session)),
                )
            },
            withDependencies = {
                it.continuousClock = ImmediateClock()
                it.openCodeClient.homeAttention = { _, _, _ -> setOf(session.id) }
            },
        ) {
            HomeFeature()
        }

        store.send(Action.serverEvent(OpenCodeServerEvent.attentionChanged(askedSessionID = session.id))) {
            it.attentionSessionIDs = setOf(session.id)
        }
        store.receive(Action.attentionRefreshed(setOf(session.id)))
        // Unknown (child) owners and settlements wait for the authoritative refetch.
        store.send(Action.serverEvent(OpenCodeServerEvent.attentionChanged(askedSessionID = "ses_child_unknown")))
        store.receive(Action.attentionRefreshed(setOf(session.id)))
        store.send(Action.serverEvent(OpenCodeServerEvent.attentionChanged(askedSessionID = null)))
        store.receive(Action.attentionRefreshed(setOf(session.id)))
    }

    @Test
    fun requestSettledElsewhereRefreshesTheOpenSessionWithoutWaitingForItsPoll() {
        val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
        val session = OpenCodeSession(id = "ses_1", title = "Work", projectID = "proj_1", directory = "/tmp/demo", updatedAt = Instant.now().toEpochMilli())
        val state = HomeFeature.State(server = server, snapshot = snapshot(sessions = listOf(session)))
        state.sessionDetail = SessionFeature.State(server = server, session = session, cachedItems = emptyList())
        var fetches = 0
        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.continuousClock = ImmediateClock()
                it.openCodeClient.homeAttention = { _, _, _ -> emptySet() }
                it.openCodeClient.familyPendingRequests = { _, _, _ ->
                    fetches += 1
                    OpenCodePendingRequests(permissions = emptyList())
                }
            },
        ) {
            HomeFeature()
        }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.serverEvent(OpenCodeServerEvent.attentionChanged(askedSessionID = null)))
        store.receive(sessionAction(SessionFeature.Action.requestsChanged))
        store.receive({ it.presented is SessionFeature.Action.pendingRequestsResponse })
        expect(fetches == 1)
    }
}

private fun unreadIDs(state: HomeFeature.State): Set<String> = state.sessions.filter(state::isUnread).map { it.id }.toSet()

internal fun sessionAction(action: SessionFeature.Action): Action = Action.sessionDetail(PresentationAction.presented(action))

internal val Action.presented: SessionFeature.Action?
    get() = ((this as? Action.sessionDetail)?.action as? PresentationAction.presented)?.action

/// Regression: a new session from the phone lost its first prompt when the
/// screen changed while the session was still being created (the create can
/// take seconds while a new folder's location boots). The effect was
/// cancelled between create and send, leaving an empty session and no error.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FirstSendSurvivesNavigationTests {
    @get:Rule
    val dependencies = TestDependencies()

    private fun makeStore(
        projectID: String,
        createSession: suspend (OpenCodeServer, String) -> OpenCodeSession,
        sendPrompt: suspend (OpenCodeServer, String, String, List<OpenCodeOutgoingAttachment>, List<OpenCodeFileReference>, String, OpenCodeDelivery) -> Unit = { _, _, _, _, _, _, _ -> },
    ): TestStore<HomeFeature.State, Action> {
        val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
        val project = OpenCodeProject(id = projectID, name = "experiments", directory = "/tmp/experiments")
        val store = TestStore(
            initialState = { HomeFeature.State(server = server, snapshot = OpenCodeHomeSnapshot(projects = listOf(project), sessions = emptyList(), activeSessionIDs = emptySet())) },
            withDependencies = {
                it.uuid = UUIDGenerator.incrementing()
                it.continuousClock = ImmediateClock()
                it.undeliveredPrompts = UndeliveredPrompts()
                it.openCodeClient.createSession = createSession
                it.openCodeClient.sendPrompt = sendPrompt
                it.openCodeClient.familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList()) }
            },
        ) {
            HomeFeature()
        }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        return store
    }

    @Test
    fun firstPromptIsDeliveredEvenWhenTheReaderLeavesMidCreate() {
        val created = OpenCodeSession(id = "ses_new", title = "", projectID = "proj_x", directory = "/tmp/experiments", updatedAt = distantPast)
        val createStarted = CompletableDeferred<Unit>()
        val createGate = CompletableDeferred<Unit>()
        var sent: Pair<String, String>? = null
        val store = makeStore(
            projectID = "proj_x",
            createSession = { _, _ ->
                createStarted.complete(Unit)
                createGate.await()
                created
            },
            sendPrompt = { _, sessionID, text, _, _, _, _ -> sent = sessionID to text },
        )

        store.send(Action.newSessionTapped)
        store.send(sessionAction(SessionFeature.Action.binding(BindingAction.set(SessionFeature.State::draft, "Why did the Slack bot fail?"))))
        store.send(sessionAction(SessionFeature.Action.sendTapped))
        expect(createStarted.isCompleted)
        // The reader opens something else before the create returns.
        store.send(Action.newSessionTapped)
        createGate.complete(Unit)
        store.skipReceivedActions()
        expect(sent?.first == "ses_new")
        expect(sent?.second == "Why did the Slack bot fail?")
        store.skipInFlightEffects()
    }

    @Test
    fun aSendThatFailsAfterItsScreenLeftComesBackWithTheDraft() {
        val createStarted = CompletableDeferred<Unit>()
        val createGate = CompletableDeferred<Unit>()
        val store = makeStore(
            projectID = "proj_y",
            createSession = { _, _ ->
                createStarted.complete(Unit)
                createGate.await()
                throw OpenCodeClientError(message = "The server returned HTTP 500.", statusCode = 500)
            },
        )

        store.send(Action.newSessionTapped)
        store.send(sessionAction(SessionFeature.Action.binding(BindingAction.set(SessionFeature.State::draft, "Keep this draft"))))
        store.send(sessionAction(SessionFeature.Action.sendTapped))
        expect(createStarted.isCompleted)
        // Leaving to another screen tears the first one down mid-create.
        store.send(Action.newSessionTapped)
        createGate.complete(Unit)
        // The next new-session screen gets the draft back, with the failure,
        // whenever the failed create lands.
        for (attempt in 0 until 40) {
            if (store.state.sessionDetail?.draft == "Keep this draft") break
            store.send(sessionAction(SessionFeature.Action.task))
        }
        expect(store.state.sessionDetail?.draft == "Keep this draft")
        expect(store.state.sessionDetail?.items?.any { it is OpenCodeTranscriptItem.sendFailure } == true)
        store.skipInFlightEffects()
    }
}
