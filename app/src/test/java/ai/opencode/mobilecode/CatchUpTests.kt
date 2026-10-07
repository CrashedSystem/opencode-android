@file:PortedFrom("OpenCodeTests/CatchUpTests.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.HomeFeature.Action
import ai.opencode.mobilecode.tca.DependencyValues
import ai.opencode.mobilecode.tca.Exhaustivity
import ai.opencode.mobilecode.tca.ImmediateClock
import ai.opencode.mobilecode.tca.TestDependencies
import ai.opencode.mobilecode.tca.TestStore
import ai.opencode.mobilecode.tca.continuousClock
import ai.opencode.mobilecode.tca.expect
import ai.opencode.mobilecode.tca.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.yield
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric gives the reducers' LabMarks a working android.util.Log.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CatchUpTests {
    @get:Rule
    val dependencies = TestDependencies()

    val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")

    fun session(id: String, updated: Long, idle: Long = 2_000, viewed: Long = 1_000): OpenCodeSession =
        OpenCodeSession(
            id = id,
            title = id,
            projectID = "p",
            directory = "/project",
            updatedAt = updated * 1_000,
            idleMilliseconds = idle,
            viewedMilliseconds = viewed,
        )

    /// unread (older), waiting, running, read, unread (newer).
    fun home(): HomeFeature.State {
        val sessions = listOf(
            session("older", updated = 10),
            session("waiting", updated = 5, idle = 1_000, viewed = 1_000),
            session("running", updated = 30),
            session("read", updated = 40, idle = 1_000, viewed = 1_000),
            session("newer", updated = 20),
        )
        val state = HomeFeature.State(
            server = server,
            snapshot = OpenCodeHomeSnapshot(projects = emptyList(), sessions = sessions, activeSessionIDs = setOf("running", "waiting")),
        )
        state.attentionSessionIDs = setOf("waiting")
        state.sessionDetail = state.makeDetail(sessions[3])
        state.sessionDetail?.draft = "half-written thought"
        return state
    }

    fun store(
        state: HomeFeature.State,
        receipts: MutableList<String> = mutableListOf(),
        configure: (DependencyValues) -> Unit = {},
    ): TestStore<HomeFeature.State, Action> {
        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.continuousClock = ImmediateClock()
                it.openCodeClient.viewSession = { _, id, _ -> receipts += id }
                it.openCodeClient.transcript = { _, _, _ -> emptyList() }
                it.openCodeClient.homeAttention = { _, _, _ -> setOf("waiting") }
                configure(it)
            },
        ) {
            HomeFeature()
        }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        return store
    }

    private val noAttention: (DependencyValues) -> Unit = { it.openCodeClient.homeAttention = { _, _, _ -> emptySet() } }

    @Test
    fun stacksWaitingThenUnreadSessionsAndOpensTheFirstWithoutReadingIt() {
        val receipts = mutableListOf<String>()
        val store = store(home(), receipts = receipts)
        expect(store.state.catchUpSessions.map { it.id } == listOf("waiting", "newer", "older"))

        store.send(Action.catchUpTapped)
        expect(store.state.catchUp?.queue == listOf("waiting", "newer", "older"))
        expect(store.state.catchUp?.returnSessionID == "read")
        expect(store.state.sessionDetail?.session?.id == "waiting")
        expect(store.state.sessionDetail?.readsOnView == false)
        store.finish()
        expect(receipts.isEmpty())
        expect(store.state.indicator(store.state.sessions[0]) == HomeFeature.State.Indicator.unread)
    }

    @Test
    fun catchUpCardsLeaveQuietlyAndUndoTicks() {
        val log = mutableListOf<String>()
        val state = home()
        state.attentionSessionIDs = emptySet()
        val store = store(state) {
            noAttention(it)
            it.haptics = recordingHaptics(log)
        }
        store.send(Action.catchUpTapped)
        // The card's own flight ticks as it crosses the threshold (CatchUpStackController).
        store.send(Action.catchUpCardDismissed(sessionID = "newer", disposition = HomeFeature.CatchUpDisposition.markRead))
        expect(log.isEmpty())
        store.send(Action.catchUpUndoTapped)
        expect(log == listOf("selection"))
        store.finish()
    }

    @Test
    fun markReadMovesAlongAndUndoBringsTheCardBackUnread() {
        val receipts = mutableListOf<String>()
        val state = home()
        state.attentionSessionIDs = emptySet()
        val store = store(state, receipts = receipts, configure = noAttention)

        store.send(Action.catchUpTapped)
        expect(store.state.catchUp?.queue == listOf("newer", "older"))
        store.send(Action.catchUpCardDismissed(sessionID = "newer", disposition = HomeFeature.CatchUpDisposition.markRead))
        store.finish()
        expect(receipts == listOf("newer"))
        expect(store.state.catchUp?.queue == listOf("older"))
        expect(store.state.sessionDetail?.session?.id == "older")
        val newer = store.state.sessions.first { it.id == "newer" }
        expect(!store.state.isUnread(newer))

        store.send(Action.catchUpUndoTapped)
        expect(store.state.catchUp?.queue == listOf("newer", "older"))
        expect(store.state.sessionDetail?.session?.id == "newer")
        expect(store.state.sessionDetail?.readsOnView == false)
        expect(store.state.isUnread(store.state.sessions.first { it.id == "newer" }))
        store.finish()
    }

    @Test
    fun keepUnreadLeavesTheSessionUnreadAndFinishesTheStack() {
        val state = home()
        state.attentionSessionIDs = emptySet()
        val store = store(state, configure = noAttention)

        store.send(Action.catchUpTapped)
        store.send(Action.catchUpCardDismissed(sessionID = "newer", disposition = HomeFeature.CatchUpDisposition.keepUnread))
        store.send(Action.catchUpCardDismissed(sessionID = "older", disposition = HomeFeature.CatchUpDisposition.keepUnread))
        expect(store.state.catchUp?.queue == emptyList<String>())
        expect(store.state.catchUpSessions.map { it.id } == listOf("newer", "older"), "still unread in the drawer")
        // A stale dismissal for a card no longer on top is ignored.
        store.send(Action.catchUpCardDismissed(sessionID = "newer", disposition = HomeFeature.CatchUpDisposition.markRead))
        expect(store.state.catchUp?.handled?.size == 2)
        store.finish()
    }

    @Test
    fun cardsBehindTheOpenOneFollowReadsAndAnswersFromOtherDevices() {
        val store = store(home())
        store.send(Action.catchUpTapped)
        expect(store.state.catchUp?.queue == listOf("waiting", "newer", "older"))

        // Another device reads "older" and answers the open card's request.
        val snapshot = OpenCodeHomeSnapshot(projects = emptyList(), sessions = store.state.sessions, activeSessionIDs = setOf("running"))
        snapshot.sessions = snapshot.sessions.toMutableList().also { it[0] = it[0].copy(viewedMilliseconds = 2_000) }
        store.send(Action.snapshotRefreshed(snapshot))
        store.send(Action.attentionRefreshed(emptySet()))
        expect(store.state.catchUp?.queue == listOf("waiting", "newer"), "the open card stays until the reader moves it along")
        expect(store.state.sessionDetail?.session?.id == "waiting")
        store.finish()
    }

    @Test
    fun aHandledCardReturnsWhenItsSessionHasNewActivity() {
        val state = home()
        state.attentionSessionIDs = emptySet()
        val store = store(state, configure = noAttention)
        store.send(Action.catchUpTapped)
        store.send(Action.catchUpCardDismissed(sessionID = "newer", disposition = HomeFeature.CatchUpDisposition.markRead))

        // An unrelated refresh does not bring it back…
        store.send(Action.snapshotRefreshed(OpenCodeHomeSnapshot(projects = emptyList(), sessions = store.state.sessions, activeSessionIDs = setOf("running"))))
        expect(store.state.catchUp?.queue == listOf("older"))

        // …but a reply that finished after the swipe does, at the back.
        val sessions = store.state.sessions.toMutableList()
        val index = sessions.indexOfFirst { it.id == "newer" }
        sessions[index] = sessions[index].copy(idleMilliseconds = 5_000)
        store.send(Action.snapshotRefreshed(OpenCodeHomeSnapshot(projects = emptyList(), sessions = sessions, activeSessionIDs = setOf("running"))))
        expect(store.state.catchUp?.queue == listOf("older", "newer"))
        store.finish()
    }

    @Test
    fun leavingReturnsToThePreviousSessionWithItsComposer() {
        val store = store(home())
        store.send(Action.catchUpTapped)
        store.send(Action.catchUpExitTapped)
        expect(store.state.catchUp == null)
        expect(store.state.sessionDetail?.session?.id == "read")
        expect(store.state.sessionDetail?.readsOnView == true)
        expect(store.state.sessionDetail?.draft == "half-written thought")
        store.finish()
    }

    @Test
    fun openingASessionFromANotificationLeavesTheStack() {
        val store = store(home())
        store.send(Action.catchUpTapped)
        store.send(Action.sessionTapped("running"))
        expect(store.state.catchUp == null)
        store.receive<Action.sessionNavigationReady>()
        store.finish()
        expect(store.state.sessionDetail?.session?.id == "running")
        expect(store.state.sessionDetail?.readsOnView == true)
    }

    @Test
    fun notificationForTheOpenCardRestoresAutomaticReadsWithoutLosingItsDraft() {
        val receipts = mutableListOf<String>()
        val state = home()
        state.attentionSessionIDs = emptySet()
        state.catchUp = HomeFeature.CatchUp(queue = listOf("newer"))
        state.openCatchUpCard()
        state.sessionDetail?.draft = "unfinished reply"
        val store = store(state, receipts = receipts)

        store.send(Action.sessionTapped("newer"))
        expect(store.state.catchUp == null)
        expect(store.state.sessionDetail?.readsOnView == true)
        expect(store.state.sessionDetail?.draft == "unfinished reply")
        store.finish()
        expect(receipts == listOf("newer"))
    }

    @Test
    fun browsingAChildAndReturningDoesNotReadTheCatchUpCard() {
        val state = home()
        state.attentionSessionIDs = emptySet()
        state.catchUp = HomeFeature.CatchUp(queue = listOf("newer", "older"))
        state.openCatchUpCard()
        val root = state.sessionDetail!!.session!!
        val child = session("child", updated = 20).copy(parentID = root.id)
        state.sessionDetail?.familySessions = listOf(root, child)
        val receipts = mutableListOf<String>()
        val store = store(state, receipts = receipts) {
            it.openCodeClient.familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) }
            it.openCodeClient.pendingInputs = { _, _ -> emptyList() }
        }

        for (target in listOf(child, root)) {
            store.send(sessionAction(SessionFeature.Action.familyPicked(target.id)))
            expect(store.state.sessionDetail?.session?.viewedMilliseconds == target.viewedMilliseconds)
            expect(store.state.isUnread(root))
        }
        store.send(Action.catchUpCardDismissed(sessionID = root.id, disposition = HomeFeature.CatchUpDisposition.keepUnread))
        expect(store.state.isUnread(root))
        store.finish()
        expect(receipts.isEmpty())
    }

    @Test
    fun undoCancelsTheInFlightReadReceipt() {
        val state = home()
        state.attentionSessionIDs = emptySet()
        state.catchUp = HomeFeature.CatchUp(queue = listOf("newer", "older"))
        state.openCatchUpCard()
        val pending = CompletableDeferred<Unit>()
        var cancelled = false
        val store = store(state) {
            it.openCodeClient.viewSession = { _, _, _ ->
                try {
                    pending.await()
                } catch (error: CancellationException) {
                    cancelled = true
                    throw error
                }
                throw OpenCodeClientError(message = "Cancelled")
            }
        }

        val receipt = store.send(Action.catchUpCardDismissed(sessionID = "newer", disposition = HomeFeature.CatchUpDisposition.markRead))
        store.send(Action.catchUpUndoTapped)
        receipt.finish()
        expect(cancelled)
        expect(store.state.isUnread(store.state.sessions.first { it.id == "newer" }))
        expect(store.state.catchUp?.queue?.firstOrNull() == "newer")
        store.finish()
    }

    @Test
    fun markAllAsReadReadsUnreadSessionsAndLeavesWaitingAndRunningOnes() {
        val receipts = mutableListOf<String>()
        val state = home()
        // Waiting and unread: what led up to the question is still unseen.
        state.sessions = state.sessions.toMutableList().also { it[1] = it[1].copy(idleMilliseconds = 2_000) }
        val store = store(state, receipts = receipts)
        expect(store.state.catchUpSessions.map { it.id } == listOf("waiting", "newer", "older"))

        store.send(Action.catchUpMarkAllReadTapped)
        store.finish()
        expect(receipts.toSet() == setOf("newer", "older"))
        expect(store.state.catchUpSessions.map { it.id } == listOf("waiting"))
        expect(store.state.attentionSessionIDs == setOf("waiting"), "its pending request still needs you")
        val byID = store.state.sessions.associateBy { it.id }
        expect(store.state.isUnread(byID.getValue("waiting")))
        expect(store.state.isUnread(byID.getValue("running")), "running sessions join once they finish")
        expect(!store.state.isUnread(byID.getValue("newer")))
        expect(!store.state.isUnread(byID.getValue("older")))
    }

    @Test
    fun markAllAsReadEmptiesCatchUpWhenNothingIsWaiting() {
        val state = home()
        state.attentionSessionIDs = emptySet()
        state::manuallyUnreadSessionKeys.withLock { value = setOf(state.sessionKey("read")) }
        val store = store(state, configure = noAttention)
        expect(store.state.catchUpSessions.map { it.id } == listOf("read", "newer", "older"))

        store.send(Action.catchUpMarkAllReadTapped)
        store.finish()
        expect(store.state.catchUpSessions.isEmpty(), "the drawer row hides")
        // Nothing left to read is a no-op.
        store.send(Action.catchUpMarkAllReadTapped)
    }

    @Test
    fun markAllAsReadSendsAFewReceiptsAtATime() {
        val state = home()
        state.attentionSessionIDs = emptySet()
        state.sessions += (0 until 10).map { session("extra$it", updated = 50L + it) }
        var current = 0
        var peak = 0
        val delivered = mutableListOf<String>()
        val store = store(state) {
            noAttention(it)
            it.openCodeClient.viewSession = { _, id, _ ->
                current += 1
                peak = maxOf(peak, current)
                repeat(20) { yield() }
                current -= 1
                delivered += id
            }
        }

        store.send(Action.catchUpMarkAllReadTapped)
        store.finish()
        store.skipReceivedActions()
        expect(delivered.size == 12)
        expect(peak <= HomeFeature.receiptConcurrency)
        expect(store.state.markAllReceipts.isEmpty())
        expect(store.state.catchUpSessions.isEmpty())
    }

    @Test
    fun aFailedMarkAllReceiptPutsThatSessionBackUnread() {
        val state = home()
        state.attentionSessionIDs = emptySet()
        val store = store(state) {
            noAttention(it)
            it.openCodeClient.viewSession = { _, id, _ ->
                if (id == "older") throw OpenCodeClientError(message = "Offline")
            }
        }

        store.send(Action.catchUpMarkAllReadTapped)
        store.finish()
        store.skipReceivedActions()
        expect(store.state.catchUpSessions.map { it.id } == listOf("older"), "only the refused receipt comes back")
        expect(store.state.locallyViewedMilliseconds["older"] == null)
        expect(store.state.locallyViewedMilliseconds["newer"] == 2_000L)
        expect(store.state.markAllReceipts.isEmpty())

        // A stale list response can't hide it again either.
        store.send(Action.snapshotRefreshed(OpenCodeHomeSnapshot(projects = emptyList(), sessions = home().sessions, activeSessionIDs = setOf("running"))))
        expect(store.state.catchUpSessions.map { it.id } == listOf("older"))
    }

    @Test
    fun markAsUnreadWinsOverALateMarkAllReceipt() {
        val state = home()
        state.attentionSessionIDs = emptySet()
        // Each receipt waits for its own release.
        val gates = mutableListOf<CompletableDeferred<Unit>>()
        val store = store(state) {
            noAttention(it)
            it.openCodeClient.viewSession = { _, _, _ ->
                val release = CompletableDeferred<Unit>()
                gates += release
                release.await()
            }
        }

        store.send(Action.catchUpMarkAllReadTapped)
        store.send(Action.markUnread("newer"))
        expect(gates.size == 2)
        gates.forEach { it.complete(Unit) }
        store.finish()
        store.skipReceivedActions()
        val newer = store.state.sessions.first { it.id == "newer" }
        expect(store.state.isUnread(newer))
        expect(store.state.locallyViewedMilliseconds["newer"] == null)
        expect(!store.state.isUnread(store.state.sessions.first { it.id == "older" }))
    }

    @Test
    fun markAsUnreadCancelsARowsReceiptInFlight() {
        val state = home()
        state.attentionSessionIDs = emptySet()
        val gate = CompletableDeferred<Unit>()
        var cancelled = false
        val store = store(state) {
            noAttention(it)
            it.openCodeClient.viewSession = { _, _, _ ->
                try {
                    gate.await()
                } catch (error: CancellationException) {
                    cancelled = true
                    throw error
                }
                throw OpenCodeClientError(message = "Cancelled")
            }
        }

        store.send(Action.markRead("newer"))
        store.send(Action.markUnread("newer"))
        store.finish()
        expect(cancelled)
        expect(store.state.isUnread(store.state.sessions.first { it.id == "newer" }))
    }

    @Test
    fun markAllAsReadDuringCatchUpKeepsTheOpenCard() {
        val state = home()
        state.attentionSessionIDs = emptySet()
        val store = store(state, configure = noAttention)
        store.send(Action.catchUpTapped)
        store.send(Action.catchUpMarkAllReadTapped)
        store.finish()
        expect(store.state.catchUp?.queue == listOf("newer"), "the open card stays until the reader moves it along")
        expect(store.state.sessionDetail?.session?.id == "newer")
    }

    @Test
    fun tappingTheCardTitleOpensTheSessionAndLeavesCatchUp() {
        val receipts = mutableListOf<String>()
        val state = home()
        state.attentionSessionIDs = emptySet()
        state.catchUp = HomeFeature.CatchUp(queue = listOf("newer", "older"), returnSessionID = "read")
        state.openCatchUpCard()
        state.sessionDetail?.draft = "reply from the card"
        val store = store(state, receipts = receipts, configure = noAttention)

        store.send(Action.catchUpOpenTapped)
        expect(store.state.catchUp == null)
        expect(store.state.sessionDetail?.session?.id == "newer")
        expect(store.state.sessionDetail?.readsOnView == true)
        expect(store.state.sessionDetail?.draft == "reply from the card")
        expect(store.state.lastViewedSessionIDs[store.state.server.persistenceID] == "newer")
        expect(!store.state.isUnread(store.state.sessions.first { it.id == "newer" }))
        expect(store.state.isUnread(store.state.sessions.first { it.id == "older" }), "the rest stay for next time")
        store.finish()
        expect(receipts == listOf("newer"))

        // Outside Catch Up the title has nothing to leave.
        store.send(Action.catchUpOpenTapped)
    }

    @Test
    fun openingFromTheTitleReadsTheNewestIdleTheCardSaw() {
        val state = home()
        state.attentionSessionIDs = emptySet()
        state.catchUp = HomeFeature.CatchUp(queue = listOf("newer", "older"))
        state.openCatchUpCard()
        // A reply finished on the card after the list was read.
        state.sessionDetail?.session = state.sessionDetail?.session?.copy(idleMilliseconds = 3_000)
        val receipts = mutableMapOf<String, Long>()
        val store = store(state) {
            noAttention(it)
            it.openCodeClient.viewSession = { _, id, idle -> receipts[id] = idle }
        }

        store.send(Action.catchUpOpenTapped)
        store.finish()
        store.skipReceivedActions()
        expect(receipts == mapOf("newer" to 3_000L))
        expect(store.state.sessionDetail?.session?.viewedMilliseconds == 3_000L)
        expect(store.state.locallyViewedMilliseconds["newer"] == 3_000L)
        // The list catching up to that reply doesn't bring it back unread.
        val sessions = store.state.sessions.toMutableList()
        val index = sessions.indexOfFirst { it.id == "newer" }
        sessions[index] = sessions[index].copy(idleMilliseconds = 3_000, viewedMilliseconds = 1_000)
        store.send(Action.snapshotRefreshed(OpenCodeHomeSnapshot(projects = emptyList(), sessions = sessions, activeSessionIDs = setOf("running"))))
        expect(!store.state.isUnread(store.state.sessions.first { it.id == "newer" }))
    }

    @Test
    fun openingFromTheTitleOnASubagentReadsItAndItsSession() {
        val state = home()
        state.attentionSessionIDs = emptySet()
        state.catchUp = HomeFeature.CatchUp(queue = listOf("newer", "older"))
        state.openCatchUpCard()
        val root = state.sessionDetail!!.session!!
        val child = session("child", updated = 20, idle = 2_500, viewed = 1_000).copy(parentID = root.id)
        state.sessionDetail?.familySessions = listOf(root, child)
        state.sessionDetail?.session = child
        val receipts = mutableMapOf<String, Long>()
        val store = store(state) {
            noAttention(it)
            it.openCodeClient.viewSession = { _, id, idle -> receipts[id] = idle }
        }

        store.send(Action.catchUpOpenTapped)
        store.finish()
        store.skipReceivedActions()
        expect(store.state.catchUp == null)
        expect(store.state.sessionDetail?.session?.id == "child", "the reader stays on the subagent")
        expect(receipts == mapOf("newer" to 2_000L, "child" to 2_500L))
        expect(store.state.sessionDetail?.session?.viewedMilliseconds == 2_500L)
        expect(store.state.sessionDetail?.viewedIdle?.get("child") == 2_500L)
        expect(store.state.sessionDetail?.familySessions?.firstOrNull { it.id == "newer" }?.viewedMilliseconds == 2_000L)
        expect(!store.state.isUnread(store.state.sessions.first { it.id == "newer" }))
    }
}
