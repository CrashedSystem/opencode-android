@file:PortedFrom("OpenCodeTests/SessionFeatureTests.swift", "083cd94")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.SessionFeature.Action
import ai.opencode.mobilecode.tca.BindingAction
import ai.opencode.mobilecode.tca.DateGenerator
import ai.opencode.mobilecode.tca.Exhaustivity
import ai.opencode.mobilecode.tca.ImmediateClock
import ai.opencode.mobilecode.tca.TestClock
import ai.opencode.mobilecode.tca.TestDependencies
import ai.opencode.mobilecode.tca.TestStore
import ai.opencode.mobilecode.tca.UUIDGenerator
import ai.opencode.mobilecode.tca.continuousClock
import ai.opencode.mobilecode.tca.date
import ai.opencode.mobilecode.tca.expect
import ai.opencode.mobilecode.tca.uuid
import ai.opencode.mobilecode.tca.withLock
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.time.Instant
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric gives the reducer's LabMarks a working android.util.Log.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SessionFeatureTests {
    @get:Rule
    val dependencies = TestDependencies()

    private val testServer: OpenCodeServer
        get() = OpenCodeServer(url = "http://localhost:9", username = "", password = "")

    private fun testSession(
        id: String = "ses_1",
        title: String = "Chat",
        directory: String = "/project",
        updatedAt: Long = System.currentTimeMillis(),
        model: OpenCodeModelRef? = null,
        parentID: String? = null,
        idleMilliseconds: Long? = null,
        viewedMilliseconds: Long? = null,
    ) = OpenCodeSession(
        id = id,
        title = title,
        projectID = "p",
        directory = directory,
        updatedAt = updatedAt,
        idleMilliseconds = idleMilliseconds,
        viewedMilliseconds = viewedMilliseconds,
        model = model,
        parentID = parentID,
    )

    private fun makeState(
        session: OpenCodeSession? = null,
        cachedItems: List<OpenCodeTranscriptItem> = emptyList(),
        isRunning: Boolean = false,
        draft: String = "",
    ): SessionFeature.State {
        val state = SessionFeature.State(
            server = testServer,
            session = session ?: testSession(),
            cachedItems = cachedItems,
            isRunning = isRunning,
        )
        state.draft = draft
        return state
    }

    @Test
    fun workingDescendantsRemainVisibleAfterParentSettles() {
        val root = testSession(id = "root", title = "Main")
        val child = testSession(id = "child", title = "Research", parentID = root.id)
        val nested = testSession(id = "nested", title = "Check sources", parentID = child.id)
        val unrelated = testSession(id = "other", title = "Other session")
        val state = SessionFeature.State(server = testServer, session = root)
        state.familySessions = listOf(root, child, nested, unrelated)
        val store = TestStore(initialState = { state }) { SessionFeature() }
        store.send(Action.familyRunStateChanged(sessionID = nested.id, isRunning = true)) {
            it.activeFamilySessionIDs = setOf(nested.id)
        }
        expect(store.state.activeSubagents == listOf(nested))
        expect(store.state.isBusy)
        expect(!store.state.isRunning, "Do not offer to interrupt an idle parent's run")
        store.send(Action.familyRunStateChanged(sessionID = unrelated.id, isRunning = true)) {
            it.activeFamilySessionIDs += unrelated.id
        }
        expect(store.state.activeSubagents == listOf(nested))
        store.send(Action.familyRunStateChanged(sessionID = nested.id, isRunning = false)) {
            it.activeFamilySessionIDs -= nested.id
        }
        expect(store.state.activeSubagents.isEmpty())
        expect(!store.state.isBusy)
    }

    @Test
    fun backgroundShellsAreThoseThatOutliveTheirToolCall() {
        val root = testSession(id = "root", title = "Main")
        val state = SessionFeature.State(server = testServer, session = root)
        fun tool(shellID: String, status: OpenCodeToolCall.Status) = OpenCodeTranscriptItem.tool(
            id = "m-tool-$shellID",
            call = OpenCodeToolCall(
                callID = shellID,
                name = "shell",
                status = status,
                input = OpenCodeJSONValue.`object`(emptyMap()),
                output = emptyList(),
                error = null,
                metadata = mapOf("shellID" to OpenCodeJSONValue.string(shellID)),
            ),
        )
        val justStarted = OpenCodeShell(id = "sh_new", command = "ls", sessionID = root.id)
        val foreground = OpenCodeShell(id = "sh_fg", command = "bun test", sessionID = root.id, isListed = true)
        val backgrounded = OpenCodeShell(id = "sh_bg", command = "bun dev", sessionID = root.id)
        val predating = OpenCodeShell(id = "sh_old", command = "tail -f log", sessionID = root.id, isListed = true)
        val userCommand = OpenCodeShell(id = "sh_user", command = "make", sessionID = root.id, isUserCommand = true)
        val otherSession = OpenCodeShell(id = "sh_other", command = "sleep 9", sessionID = "child", isListed = true)
        state.items = listOf(tool("sh_fg", OpenCodeToolCall.Status.running), tool("sh_bg", OpenCodeToolCall.Status.completed))
        state.shells = listOf(justStarted, foreground, backgrounded, predating, userCommand, otherSession)

        // A live shell waits for its tool call's verdict, so a quick command
        // never flashes the indicator before its progress event arrives.
        expect(state.backgroundShells.map { it.id } == listOf("sh_bg", "sh_old", "sh_user"))
    }

    @Test
    fun shellEventsAndListingsTrackOnlyTheSelectedSession() {
        val root = testSession(id = "root", title = "Main")
        val store = TestStore(initialState = { SessionFeature.State(server = testServer, session = root) }) { SessionFeature() }
        val dev = OpenCodeShell(id = "sh_dev", command = "bun dev", sessionID = root.id)
        val exited = OpenCodeShell(id = "sh_done", command = "ls", sessionID = root.id, isListed = true)
        val listed = OpenCodeShell(id = "sh_log", command = "tail -f log", sessionID = root.id, isListed = true)
        val foreign = OpenCodeShell(id = "sh_x", command = "sleep 9", sessionID = "elsewhere", isListed = true)

        store.send(Action.shellStarted(dev)) { it.shells = listOf(dev) }
        store.send(Action.shellStarted(foreign))
        store.send(Action.shellEnded("sh_done")) { it.endedShellIDs = setOf("sh_done") }
        // The list was requested before sh_done exited and before sh_dev started.
        store.send(Action.shellsResponse(sessionID = root.id, result = Result.success(listOf(exited, listed, foreign)))) {
            it.shells = listOf(listed, dev)
        }
        store.send(Action.shellsResponse(sessionID = "stale", result = Result.success(emptyList())))
        store.send(Action.shellEnded("sh_dev")) {
            it.shells = listOf(listed)
            it.endedShellIDs += "sh_dev"
        }
    }

    @Test
    fun textDeltaBatchProjectsWithoutREST() {
        val session = testSession(id = "ses_stream", title = "Stream")
        var reads = 0
        val store = TestStore(initialState = { makeState(session = session, cachedItems = emptyList()) }, withDependencies = {
            it.openCodeClient.transcript = { _, _, _ ->
                reads += 1
                emptyList()
            }
        }) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.contentBatch(sessionID = session.id, events = listOf(
            OpenCodeLiveContentEvent.textDelta(messageID = "msg_reply", ordinal = 0, delta = "Hel"),
            OpenCodeLiveContentEvent.textDelta(messageID = "msg_reply", ordinal = 0, delta = "lo"),
        )))
        expect(store.state.items == listOf(
            OpenCodeTranscriptItem.assistantMarkdown(id = "msg_reply-text-0", blocks = OpenCodeMarkdownBlock.blocks(from = "Hello")),
        ))
        expect(store.state.liveText["msg_reply-text-0"] == "Hello")
        expect(reads == 0)
    }

    @Test
    fun endedBoundaryReplacesDeltaTextAndSettlesOnce() {
        val session = testSession(id = "ses_stream", title = "Stream")
        var pageReads = 0
        var messageReads = 0
        val settled = listOf(
            OpenCodeTranscriptItem.assistantMarkdown(id = "msg_reply-text-0", blocks = OpenCodeMarkdownBlock.blocks(from = "Server value")),
        )
        val store = TestStore(initialState = { makeState(session = session, cachedItems = emptyList()) }, withDependencies = {
            it.continuousClock = ImmediateClock()
            it.openCodeClient.transcript = { _, _, _ ->
                pageReads += 1
                settled
            }
            it.openCodeClient.message = { _, _, messageID ->
                expect(messageID == "msg_reply")
                messageReads += 1
                settled
            }
        }) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.contentBatch(sessionID = session.id, events = listOf(
            OpenCodeLiveContentEvent.textDelta(messageID = "msg_reply", ordinal = 0, delta = "Client"),
            OpenCodeLiveContentEvent.textEnded(messageID = "msg_reply", ordinal = 0, text = "Ended value"),
        )))
        store.receive(Action.refreshRequested(SessionFeature.RefreshReason.settlement(messageID = "msg_reply")))
        store.receive<Action.refreshResponse>()
        expect(pageReads == 0)
        expect(messageReads == 1)
        expect(store.state.items == settled)
    }

    @Test
    fun completedReadToolImageSettlesIntoTheOpenTranscript() {
        val session = testSession(id = "ses_image", title = "Image")
        val image = OpenCodeImageAttachment(id = "msg_reply-tool-call_read-image-1", data = byteArrayOf(1, 2, 3))
        val settled = listOf(OpenCodeTranscriptItem.tool(
            id = "msg_reply-tool-call_read",
            call = OpenCodeToolCall(
                callID = "call_read",
                messageID = "msg_reply",
                name = "read",
                status = OpenCodeToolCall.Status.completed,
                input = OpenCodeJSONValue.`object`(mapOf("path" to OpenCodeJSONValue.string("preview.png"))),
                output = listOf("Image read successfully"),
                error = null,
                metadata = emptyMap(),
                images = listOf(image),
            ),
        ))
        var messageReads = 0
        val store = TestStore(initialState = { makeState(session = session, cachedItems = emptyList()) }, withDependencies = {
            it.continuousClock = ImmediateClock()
            it.openCodeClient.getSession = { _, _ -> session }
            it.openCodeClient.message = { _, _, messageID ->
                expect(messageID == "msg_reply")
                messageReads += 1
                settled
            }
            it.openCodeClient.transcript = { _, _, _ ->
                error("Image settlement must read only the message")
            }
        }) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.contentBatch(sessionID = session.id, events = listOf(
            OpenCodeLiveContentEvent.toolInputStarted(messageID = "msg_reply", toolID = "call_read", name = "read"),
            OpenCodeLiveContentEvent.toolSucceeded(
                messageID = "msg_reply",
                toolID = "call_read",
                output = listOf("Image read successfully"),
                metadata = emptyMap(),
            ),
        )))
        store.receive(Action.refreshRequested(SessionFeature.RefreshReason.settlement(messageID = "msg_reply")))
        store.receive<Action.refreshResponse>()
        expect(messageReads == 1)
        expect(store.state.items == settled)
        expect(store.state.items.mapNotNull { item ->
            (item as? OpenCodeTranscriptItem.tool)?.call?.imageAttachments?.size
        } == listOf(1))
    }

    @Test
    fun nearbySettlementsForOneMessageCoalesceIntoOneRead() {
        val clock = TestClock()
        val session = testSession(id = "ses_stream", title = "Stream")
        var reads = 0
        val settled = listOf(
            OpenCodeTranscriptItem.assistantMarkdown(id = "msg_reply-text-0", blocks = OpenCodeMarkdownBlock.blocks(from = "Done")),
        )
        val store = TestStore(initialState = { makeState(session = session, cachedItems = settled) }, withDependencies = {
            it.continuousClock = clock
            it.openCodeClient.message = { _, _, _ ->
                reads += 1
                settled
            }
            it.openCodeClient.transcript = { _, _, _ ->
                error("Settlement must not read a page")
            }
        }) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.contentBatch(sessionID = session.id, events = listOf(
            OpenCodeLiveContentEvent.textEnded(messageID = "msg_reply", ordinal = 0, text = "Done"),
        )))
        store.receive(Action.refreshRequested(SessionFeature.RefreshReason.settlement(messageID = "msg_reply")))
        clock.advance(by = 100.milliseconds)
        store.send(Action.contentBatch(sessionID = session.id, events = listOf(
            OpenCodeLiveContentEvent.stepEnded(messageID = "msg_reply"),
        )))
        store.receive(Action.refreshRequested(SessionFeature.RefreshReason.settlement(messageID = "msg_reply")))
        clock.advance(by = 100.milliseconds)
        store.receive<Action.refreshResponse>()
        expect(reads == 1)
    }

    @Test
    fun settlementOutsideCurrentWindowFallsBackToPage() {
        val session = testSession(id = "ses_stream", title = "Stream")
        var pageReads = 0
        val store = TestStore(
            initialState = { makeState(session = session, cachedItems = listOf(OpenCodeTranscriptItem.userText(id = "msg_user", text = "Hello"))) },
            withDependencies = {
                it.continuousClock = ImmediateClock()
                it.openCodeClient.getSession = { _, _ -> session }
                it.openCodeClient.message = { _, _, _ ->
                    error("Unknown message must use overlap-safe page reconciliation")
                }
                it.openCodeClient.transcript = { _, _, _ ->
                    pageReads += 1
                    emptyList()
                }
            },
        ) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.contentBatch(sessionID = session.id, events = listOf(OpenCodeLiveContentEvent.stepEnded(messageID = "msg_missing"))))
        store.receive(Action.refreshRequested(SessionFeature.RefreshReason.settlement(messageID = "msg_missing")))
        store.receive<Action.refreshResponse>()
        expect(pageReads == 1)
    }

    @Test
    fun pendingWorkPollSlowsOnceAnAlreadyConnectedStreamProvesHealthy() {
        // Home reports `eventStreamConnected` only when the server connects, so a
        // session opened on an established stream starts its poll unhealthy.
        val session = testSession(id = "ses_poll", title = "Poll")
        var reads = 0
        val store = TestStore(initialState = { makeState(session = session) }, withDependencies = {
            it.openCodeClient.familyPendingRequests = { _, _, _ ->
                reads += 1
                OpenCodePendingRequests(permissions = emptyList(), forms = emptyList())
            }
            it.openCodeClient.pendingInputs = { _, _ -> emptyList() }
        }) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.familyResponse(Result.failure(OpenCodeClientError(message = "offline")))) {
            it.pendingWorkPollSeconds = 2
        }
        store.receive<Action.pendingRequestsResponse>()
        store.send(Action.contentBatch(sessionID = session.id, events = listOf(OpenCodeLiveContentEvent.stepStarted(messageID = "msg_1")))) {
            it.streamIsHealthy = true
            it.streamSupportsProjection = true
            it.pendingWorkPollSeconds = 12
        }
        store.receive<Action.pendingRequestsResponse>()
        expect(reads == 2)
        store.skipInFlightEffects()
    }

    @Test
    fun staleMessageResponseCannotOverwriteNewerPageGeneration() {
        val session = testSession(id = "ses_stream", title = "Stream")
        val old = listOf(OpenCodeTranscriptItem.assistantMarkdown(id = "msg_reply-text-0", blocks = OpenCodeMarkdownBlock.blocks(from = "Old")))
        val fresh = listOf(OpenCodeTranscriptItem.assistantMarkdown(id = "msg_reply-text-0", blocks = OpenCodeMarkdownBlock.blocks(from = "Fresh")))
        val state = makeState(session = session, cachedItems = fresh)
        state.refresh = SessionFeature.State.Refresh(id = 2, reason = SessionFeature.RefreshReason.open)
        val store = TestStore(initialState = { state }) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.refreshResponse(
            sessionID = session.id,
            requestID = 1,
            result = Result.success(SessionFeature.AuthoritativeRefresh(
                observed = session,
                items = old,
                scope = SessionFeature.AuthoritativeRefresh.Scope.message("msg_reply"),
            )),
        ))
        expect(store.state.items == fresh)
        store.send(Action.refreshResponse(
            sessionID = session.id,
            requestID = 2,
            result = Result.success(SessionFeature.AuthoritativeRefresh(
                observed = session,
                items = fresh,
                scope = SessionFeature.AuthoritativeRefresh.Scope.page,
            )),
        ))
        expect(store.state.items == fresh)
    }

    @Test
    fun messageSettlementKeepsLaterRowsAndRacedLiveContent() {
        val session = testSession(id = "ses_settle", title = "Settle", idleMilliseconds = 2000, viewedMilliseconds = 1000)
        val first = OpenCodeTranscriptItem.userText(id = "msg_a", text = "First")
        val draft = OpenCodeTranscriptItem.assistantMarkdown(id = "msg_b-text-0", blocks = OpenCodeMarkdownBlock.blocks(from = "Draft"))
        val later = OpenCodeTranscriptItem.userText(id = "msg_c", text = "Later")
        val settled = OpenCodeTranscriptItem.assistantMarkdown(id = "msg_b-text-0", blocks = OpenCodeMarkdownBlock.blocks(from = "Final"))
        val streaming = OpenCodeTranscriptItem.assistantMarkdown(id = "msg_d-text-0", blocks = OpenCodeMarkdownBlock.blocks(from = "Streaming"))
        val tool = OpenCodeTranscriptItem.tool(
            id = "msg_d-tool-t1",
            call = OpenCodeToolCall(
                callID = "t1",
                messageID = "msg_d",
                name = "tool",
                status = OpenCodeToolCall.Status.running,
                input = OpenCodeJSONValue.`object`(emptyMap()),
                output = emptyList(),
                error = null,
                metadata = emptyMap(),
            ),
        )
        val state = makeState(session = session, cachedItems = listOf(first, draft, later))
        state.isVisible = true
        val gate = CompletableDeferred<Unit>()
        val receipts = mutableListOf<Long>()
        val store = TestStore(initialState = { state }, withDependencies = {
            it.continuousClock = ImmediateClock()
            it.openCodeClient.message = { _, _, messageID ->
                expect(messageID == "msg_b")
                gate.await()
                listOf(settled)
            }
            it.openCodeClient.transcript = { _, _, _ ->
                error("A message settlement must not read a page")
            }
            it.openCodeClient.viewSession = { _, _, idle -> receipts += idle }
        }) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.contentBatch(sessionID = session.id, events = listOf(
            OpenCodeLiveContentEvent.textEnded(messageID = "msg_b", ordinal = 0, text = "Final"),
        )))
        store.receive(Action.refreshRequested(SessionFeature.RefreshReason.settlement(messageID = "msg_b")))
        store.send(Action.contentBatch(sessionID = session.id, events = listOf(
            OpenCodeLiveContentEvent.textDelta(messageID = "msg_d", ordinal = 0, delta = "Streaming"),
            OpenCodeLiveContentEvent.toolProgress(messageID = "msg_d", toolID = "t1", metadata = emptyMap()),
        )))
        gate.complete(Unit)

        val expected = listOf(first, settled, later, streaming, tool)
        store.receive<Action.refreshResponse>()
        store.receive(Action.acceptedTranscriptUpdated(sessionID = session.id, items = expected))
        store.receive(Action.viewAcknowledged(sessionID = session.id, idle = 2000))
        store.finish()
        expect(store.state.items == expected)
        expect(store.state.liveText["msg_d-text-0"] == "Streaming")
        expect(receipts == listOf(2000L))
    }

    @Test
    fun siblingSettlementDoesNotEraseAnUnfinishedTool() {
        for (whileRunning in listOf(true, false)) {
            val session = testSession(id = "ses_parts", title = "Parts")
            val text = OpenCodeTranscriptItem.assistantMarkdown(id = "msg_reply-text-0", blocks = OpenCodeMarkdownBlock.blocks(from = "Checking now"))
            val tool = OpenCodeTranscriptItem.tool(
                id = "msg_reply-tool-call",
                call = OpenCodeToolCall(
                    callID = "call",
                    messageID = "msg_reply",
                    name = "shell",
                    status = OpenCodeToolCall.Status.running,
                    input = OpenCodeJSONValue.`object`(emptyMap()),
                    output = emptyList(),
                    error = null,
                    metadata = emptyMap(),
                ),
            )
            val state = makeState(session = session, cachedItems = listOf(text, tool))
            state.isExternallyActive = whileRunning
            state.projectedMessageIDs = setOf("msg_reply")
            state.refresh = SessionFeature.State.Refresh(id = 1, reason = SessionFeature.RefreshReason.settlement(messageID = "msg_reply"))
            val store = TestStore(initialState = { state }) { SessionFeature() }
            store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

            // text.ended is durable for the text, not every sibling part. The tool
            // started before the read, so it is not in racedEvents either.
            store.send(Action.refreshResponse(
                sessionID = session.id,
                requestID = 1,
                result = Result.success(SessionFeature.AuthoritativeRefresh(
                    observed = session,
                    items = listOf(text),
                    scope = SessionFeature.AuthoritativeRefresh.Scope.message("msg_reply"),
                )),
            ))
            expect(store.state.items == if (whileRunning) listOf(text, tool) else listOf(text))
        }
    }

    @Test
    fun reconnectKeepsLiveRowsUntilRESTCanEchoThem() {
        val session = testSession(id = "ses_reconnect", title = "Reconnect")
        val user = OpenCodeTranscriptItem.userText(id = "msg_user", text = "Check")
        val text = OpenCodeTranscriptItem.assistantMarkdown(id = "msg_reply-text-0", blocks = OpenCodeMarkdownBlock.blocks(from = "Checking"))
        val tool = OpenCodeTranscriptItem.tool(id = "msg_reply-tool-call", call = OpenCodeToolCall.test(name = "shell"))
        val state = makeState(session = session, cachedItems = listOf(user, text, tool))
        state.isExternallyActive = true
        state.projectedMessageIDs = setOf("msg_reply")
        state.refresh = SessionFeature.State.Refresh(id = 1, reason = SessionFeature.RefreshReason.resume)
        val store = TestStore(initialState = { state }) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        store.send(Action.eventStreamConnected(testServer))
        store.send(Action.refreshResponse(
            sessionID = session.id,
            requestID = 1,
            result = Result.success(SessionFeature.AuthoritativeRefresh(
                observed = session,
                items = listOf(user),
                scope = SessionFeature.AuthoritativeRefresh.Scope.page,
            )),
        ))
        expect(store.state.items == listOf(user, text, tool))
        store.finish()
    }

    @Test
    fun retainedToolDoesNotReplayItsInputDeltaTwice() {
        for (toolIsInSnapshot in listOf(false, true)) {
            val session = testSession(id = "ses_input", title = "Input")
            val text = OpenCodeTranscriptItem.assistantMarkdown(id = "msg_reply-text-0", blocks = OpenCodeMarkdownBlock.blocks(from = "Checking"))
            val tool = OpenCodeTranscriptItem.tool(
                id = "msg_reply-tool-call",
                call = OpenCodeToolCall(
                    callID = "call",
                    messageID = "msg_reply",
                    name = "shell",
                    status = OpenCodeToolCall.Status.streaming,
                    input = OpenCodeJSONValue.string("{"),
                    output = emptyList(),
                    error = null,
                    metadata = emptyMap(),
                ),
            )
            val state = makeState(session = session, cachedItems = listOf(text, tool))
            state.isExternallyActive = true
            state.projectedMessageIDs = setOf("msg_reply")
            state.refresh = SessionFeature.State.Refresh(id = 1, reason = SessionFeature.RefreshReason.settlement(messageID = "msg_reply"))
            val store = TestStore(initialState = { state }) { SessionFeature() }
            store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
            store.send(Action.contentBatch(sessionID = session.id, events = listOf(
                OpenCodeLiveContentEvent.toolInputDelta(messageID = "msg_reply", toolID = "call", delta = "\"command\":"),
            )))
            val projected = store.state.items
            store.send(Action.refreshResponse(
                sessionID = session.id,
                requestID = 1,
                result = Result.success(SessionFeature.AuthoritativeRefresh(
                    observed = session,
                    items = if (toolIsInSnapshot) listOf(text, tool) else listOf(text),
                    scope = SessionFeature.AuthoritativeRefresh.Scope.message("msg_reply"),
                )),
            ))
            expect(store.state.items == projected)
        }
    }

    @Test
    fun refreshKeepsProjectedPartsBeforeLaterSteeringEchoes() {
        val session = testSession(id = "ses_order", title = "Order")
        val user = OpenCodeTranscriptItem.userText(id = "msg_user", text = "First")
        val tool = OpenCodeTranscriptItem.tool(id = "msg_reply-tool-call", call = OpenCodeToolCall.test(name = "subagent"))
        val steer = OpenCodeTranscriptItem.userText(id = "msg_steer", text = "Also check this")
        val state = makeState(session = session, cachedItems = listOf(user, tool))
        state.queuedInputs = listOf(OpenCodePendingInput(id = "msg_steer", text = "Also check this"))
        state.promptStates += ("msg_steer" to SessionFeature.State.PromptState(
            requestEpoch = 1,
            admission = SessionFeature.State.PromptState.Admission.admitted,
        ))
        state.isExternallyActive = true
        state.projectedMessageIDs = setOf("msg_reply")
        state.refresh = SessionFeature.State.Refresh(id = 1, reason = SessionFeature.RefreshReason.runState)
        val store = TestStore(initialState = { state }) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        store.send(Action.refreshResponse(
            sessionID = session.id,
            requestID = 1,
            result = Result.success(SessionFeature.AuthoritativeRefresh(
                observed = session,
                items = listOf(user, steer),
                scope = SessionFeature.AuthoritativeRefresh.Scope.page,
            )),
        ))
        expect(store.state.items == listOf(user, tool, steer))
    }

    @Test
    fun queuedSendRefreshKeepsCurrentLiveToolUntilRestEchoesIt() {
        val session = testSession(id = "ses_steer", title = "Steer")
        val user = OpenCodeTranscriptItem.userText(id = "msg_user", text = "First prompt")
        val tool = OpenCodeTranscriptItem.tool(id = "msg_reply-tool-call", call = OpenCodeToolCall.test(name = "shell"))
        val state = makeState(session = session, cachedItems = listOf(user, tool))
        state.isExternallyActive = true
        state.projectedMessageIDs = setOf("msg_reply")
        state.refresh = SessionFeature.State.Refresh(id = 1, reason = SessionFeature.RefreshReason.runState)
        val store = TestStore(initialState = { state }) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.refreshResponse(
            sessionID = session.id,
            requestID = 1,
            result = Result.success(SessionFeature.AuthoritativeRefresh(
                observed = session,
                items = listOf(user),
                scope = SessionFeature.AuthoritativeRefresh.Scope.page,
            )),
        ))
        expect(store.state.items == listOf(user, tool))
    }

    @Test
    fun invalidationDuringRefreshQueuesExactlyOneFollowup() {
        val session = testSession(id = "ses_refresh", title = "Refresh")
        var reads = 0
        val first = CompletableDeferred<Unit>()
        val store = TestStore(initialState = { makeState(session = session, cachedItems = emptyList()) }, withDependencies = {
            it.openCodeClient.getSession = { _, _ -> session }
            it.openCodeClient.transcript = { _, _, _ ->
                reads += 1
                val number = reads
                if (number == 1) first.await()
                emptyList()
            }
        }) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.refreshRequested(SessionFeature.RefreshReason.open))
        store.send(Action.refreshRequested(SessionFeature.RefreshReason.snapshot))
        store.send(Action.refreshRequested(SessionFeature.RefreshReason.invalidation))
        expect(reads == 1)
        first.complete(Unit)
        store.receive<Action.refreshResponse>()
        store.receive(Action.refreshRequested(SessionFeature.RefreshReason.invalidation))
        store.receive<Action.refreshResponse>()
        expect(reads == 2)
    }

    @Test
    fun streamReconnectCoalescesWithResumeRead() {
        val session = testSession(id = "ses_resume", title = "Resume")
        var reads = 0
        val gate = CompletableDeferred<Unit>()
        val server = testServer.at("http://alternate.test")
        val store = TestStore(initialState = { makeState(session = session, cachedItems = emptyList()) }, withDependencies = {
            it.openCodeClient.getSession = { _, _ -> session }
            it.openCodeClient.transcript = { _, _, _ ->
                reads += 1
                gate.await()
                emptyList()
            }
        }) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.refreshRequested(SessionFeature.RefreshReason.resume))
        store.send(Action.eventStreamConnected(server))
        expect(reads == 1)
        expect(store.state.server == server)
        gate.complete(Unit)
        store.receive<Action.refreshResponse>()
        expect(reads == 1)
        store.skipReceivedActions()
    }

    /// Without a healthy stream, reconciliation is the only content path, so a
    /// running session falls back to the short cadence; a healthy projecting
    /// stream only needs a slow watchdog.
    @Test
    fun watchdogCadenceFollowsStreamHealth() {
        val session = testSession(id = "ses_watchdog", title = "Watchdog")
        val clock = TestClock()
        var reads = 0
        val state = SessionFeature.State(
            server = testServer,
            session = session,
            cachedItems = listOf(OpenCodeTranscriptItem.userText(id = "m1", text = "Hi")),
            isRunning = true,
        )
        state.watchdogEnabledInTests = true
        state.requestsConnectionIsAvailable = true
        state.streamIsHealthy = true
        state.streamSupportsProjection = true
        val store = TestStore(initialState = { state }, withDependencies = {
            it.continuousClock = clock
            it.openCodeClient.getSession = { _, _ -> session }
            it.openCodeClient.transcript = { _, _, _ ->
                reads += 1
                listOf(OpenCodeTranscriptItem.userText(id = "m1", text = "Hi"))
            }
        }) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        // Stream drops: the watchdog restarts at the running cadence (3 s + jitter ≤ 1 s).
        store.send(Action.requestsConnectionChanged(false))
        clock.advance(by = 4.seconds)
        store.receive<Action.refreshRequested>()
        store.receive<Action.refreshResponse>()
        expect(reads == 1)
        clock.advance(by = 4.seconds)
        store.receive<Action.refreshRequested>()
        store.receive<Action.refreshResponse>()
        expect(reads == 2)
        store.skipInFlightEffects()
    }

    @Test
    fun pastedAttachmentCanBeAddedAndRemoved() {
        val attachment = OpenCodeOutgoingAttachment(
            id = "00000000-0000-0000-0000-000000000001",
            data = byteArrayOf(1, 2, 3),
            mime = "image/jpeg",
        )
        val store = TestStore(initialState = {
            SessionFeature.State(
                server = testServer,
                session = OpenCodeSession(
                    id = "ses_paste",
                    title = "Paste test",
                    projectID = "project",
                    directory = "/project",
                    updatedAt = System.currentTimeMillis(),
                ),
                cachedItems = emptyList(),
            )
        }) { SessionFeature() }

        store.send(Action.attachmentAdded(attachment)) {
            it.pendingAttachments = listOf(attachment)
        }
        store.send(Action.attachmentRemoved(0)) {
            it.pendingAttachments = emptyList()
        }
    }

    @Test
    fun imageImportDecodesImagesAndRejectsOversizedSources() {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val source = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        bitmap.recycle()
        val attachment = runBlocking { ImageAttachmentImporter.process(source) } ?: error("ImageAttachmentImporter.process returned nil")
        expect(attachment.mime == "image/jpeg")
        expect(attachment.data.isNotEmpty())

        val oversized = ByteArray(ImageAttachmentImporter.maxSourceBytes + 1)
        expect(runBlocking { ImageAttachmentImporter.process(oversized) } == null)
    }

    @Test
    fun requestFailureWaitsForReconnectRetryBeforeBecomingVisible() {
        val session = testSession(id = "ses_requests", title = "Requests")
        val error = OpenCodeClientError(message = "Could not reach the OpenCode server")
        val state = makeState(session = session)
        state.requestLoadAttempt = SessionFeature.State.RequestLoadAttempt.initial
        state.requestRetryEnabledInTests = true
        val clock = TestClock()
        val store = TestStore(initialState = { state }, withDependencies = {
            it.continuousClock = clock
            it.openCodeClient.familyPendingRequests = { _, _, _ -> throw error }
            it.openCodeClient.pendingInputs = { _, _ -> throw error }
        }) { SessionFeature() }

        store.send(Action.pendingRequestsResponse(sessionID = session.id, result = Result.failure(error))) {
            it.requestLoadAttempt = null
            it.requestFailureAwaitingReconnect = true
        }
        expect(store.state.settledRequestLoadError == null)

        // One automatic retry while still connected; only its failure is settled.
        // Let the reducer effect register its injected TestClock sleep before the
        // clock advances; this keeps the assertion independent of suite load.
        clock.advance(by = 2.seconds)
        store.receive<Action.requestsAutoRetry> {
            it.requestLoadAttempt = SessionFeature.State.RequestLoadAttempt.reconnect
            it.pendingWorkPollSeconds = 2
        }
        assertReconnectFailureSettlesAndClearsOnDisconnect(store, sessionID = session.id, error = error)
    }

    @Test
    fun disconnectCancelsThePendingAutomaticRetry() {
        val session = testSession(id = "ses_requests", title = "Requests")
        val error = OpenCodeClientError(message = "Could not reach the OpenCode server")
        val state = makeState(session = session)
        state.requestLoadAttempt = SessionFeature.State.RequestLoadAttempt.initial
        state.requestRetryEnabledInTests = true
        val clock = TestClock()
        val store = TestStore(initialState = { state }, withDependencies = {
            it.continuousClock = clock
        }) { SessionFeature() }

        store.send(Action.pendingRequestsResponse(sessionID = session.id, result = Result.failure(error))) {
            it.requestLoadAttempt = null
            it.requestFailureAwaitingReconnect = true
        }
        store.send(Action.requestsConnectionChanged(false)) {
            it.requestsConnectionIsAvailable = false
        }
        expect(store.state.settledRequestLoadError == null)
    }

    @Test
    fun failedReconnectRetryBecomesVisibleOnlyAfterItSettles() {
        val session = testSession(id = "ses_requests", title = "Requests")
        val error = OpenCodeClientError(message = "Could not reach the OpenCode server")
        val state = makeState(session = session)
        state.requestsConnectionIsAvailable = false
        state.requestFailureAwaitingReconnect = true
        val clock = TestClock()
        val store = TestStore(initialState = { state }, withDependencies = {
            it.continuousClock = clock
            it.openCodeClient.familyPendingRequests = { _, _, _ -> throw error }
            it.openCodeClient.pendingInputs = { _, _ -> throw error }
        }) { SessionFeature() }

        store.send(Action.requestsConnectionChanged(true)) {
            it.requestsConnectionIsAvailable = true
            it.requestLoadAttempt = SessionFeature.State.RequestLoadAttempt.reconnect
            it.pendingWorkPollSeconds = 2
        }
        assertReconnectFailureSettlesAndClearsOnDisconnect(store, sessionID = session.id, error = error)
    }

    private fun assertReconnectFailureSettlesAndClearsOnDisconnect(
        store: TestStore<SessionFeature.State, SessionFeature.Action>,
        sessionID: String,
        error: OpenCodeClientError,
    ) {
        expect(store.state.settledRequestLoadError == null)
        store.receive(Action.pendingRequestsResponse(sessionID = sessionID, result = Result.failure(error))) {
            it.requestLoadAttempt = null
            it.requestLoadError = error.message
        }
        expect(store.state.settledRequestLoadError == error.message)
        store.receive(Action.pendingInputsRequested(sessionID))
        store.send(Action.requestsConnectionChanged(false)) {
            it.requestsConnectionIsAvailable = false
            it.requestLoadError = null
            it.pendingWorkPollSeconds = null
        }
        expect(store.state.settledRequestLoadError == null)
        // The poll loop is cancelled by the disconnect; give the sleeping task a beat to unwind.
        store.finish(timeout = 2.seconds)
    }

    @Test
    fun laterRequestSnapshotClearsSettledFailure() {
        val session = testSession(id = "ses_requests", title = "Requests")
        val state = makeState(session = session)
        state.requestFailureAwaitingReconnect = true
        state.requestLoadError = "Could not reach the OpenCode server"
        val store = TestStore(initialState = { state }) { SessionFeature() }

        store.send(Action.pendingRequestsResponse(
            sessionID = session.id,
            result = Result.success(OpenCodePendingRequests(permissions = emptyList())),
        )) {
            it.requestFailureAwaitingReconnect = false
            it.requestLoadError = null
        }
        expect(store.state.settledRequestLoadError == null)
    }

    @Test
    fun initialContentOwnsTheCriticalLaneUntilItsFirstFrame() {
        val session = testSession(id = "ses_critical", title = "Critical")
        val calls = mutableListOf<String>()
        val store = TestStore(
            initialState = {
                SessionFeature.State(
                    server = testServer,
                    session = session,
                    cachedItems = listOf(OpenCodeTranscriptItem.userText(id = "m1", text = "Ready")),
                    initialTranscriptRequestIsExternal = true,
                )
            },
            withDependencies = {
                it.continuousClock = ImmediateClock()
                it.openCodeClient.modelCatalog = { _, _ ->
                    calls += "models"
                    OpenCodeModelCatalog(models = emptyList(), defaultModel = null)
                }
                it.openCodeClient.sessionFamily = { _, root ->
                    calls += "family"
                    OpenCodeSessionFamily(sessions = listOf(root), activeSessionIDs = emptySet())
                }
                it.openCodeClient.familyPendingRequests = { _, _, _ ->
                    calls += "requests"
                    OpenCodePendingRequests(permissions = emptyList())
                }
                it.openCodeClient.pendingInputs = { _, _ ->
                    calls += "inbox"
                    emptyList()
                }
                it.openCodeClient.getSession = { _, _ ->
                    calls += "metadata"
                    session
                }
            },
        ) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.task)
        store.send(Action.visibilityChanged(true))
        expect(calls.isEmpty())

        store.send(Action.firstContentFrameRendered)
        expect(calls.toSet().containsAll(listOf("models", "family", "requests", "inbox", "metadata")))
        store.skipInFlightEffects()
    }

    /// A running session opened from its cached window must read the live
    /// transcript at once, not after the poll's first sleep.
    @Test
    fun openingRunningSessionFromCacheReadsTranscriptImmediately() {
        val session = testSession(id = "running", title = "Running")
        var transcriptReads = 0
        val state = SessionFeature.State(
            server = testServer,
            session = session,
            cachedItems = listOf(OpenCodeTranscriptItem.userText(id = "m1", text = "Cached")),
            isRunning = true,
        )
        state.isVisible = false
        val store = TestStore(initialState = { state }, withDependencies = {
            it.openCodeClient.transcript = { _, _, _ ->
                transcriptReads += 1
                listOf(
                    OpenCodeTranscriptItem.userText(id = "m1", text = "Cached"),
                    OpenCodeTranscriptItem.assistantMarkdown(id = "m2", blocks = OpenCodeMarkdownBlock.blocks(from = "Live")),
                )
            }
            it.openCodeClient.sessionFamily = { _, root -> OpenCodeSessionFamily(sessions = listOf(root), activeSessionIDs = setOf(root.id)) }
            it.openCodeClient.familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) }
            it.openCodeClient.pendingInputs = { _, _ -> emptyList() }
            it.openCodeClient.modelCatalog = { _, _ -> OpenCodeModelCatalog(models = emptyList(), defaultModel = null) }
            it.openCodeClient.getSession = { _, _ -> session }
        }) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.firstContentFrameRendered)
        store.receive<Action.refreshResponse> {
            it.items = listOf(
                OpenCodeTranscriptItem.userText(id = "m1", text = "Cached"),
                OpenCodeTranscriptItem.assistantMarkdown(id = "m2", blocks = OpenCodeMarkdownBlock.blocks(from = "Live")),
            )
        }
        expect(transcriptReads == 1)
        store.skipInFlightEffects()
    }

    @Test
    fun firstPendingRequestReadWaitsForFamilyDiscovery() {
        val root = testSession(id = "root", title = "Root")
        val child = testSession(id = "child", title = "Child", parentID = root.id)
        val family = Channel<OpenCodeSessionFamily>(Channel.UNLIMITED)
        val requestedSessionIDs = mutableListOf<List<String>>()
        val store = TestStore(
            initialState = { makeState(session = root, cachedItems = listOf(OpenCodeTranscriptItem.userText(id = "m1", text = "Ready"))) },
            withDependencies = {
                it.openCodeClient.sessionFamily = { _, _ ->
                    family.receiveCatching().getOrNull()
                        ?: OpenCodeSessionFamily(sessions = listOf(root), activeSessionIDs = emptySet())
                }
                it.openCodeClient.familyPendingRequests = { _, ids, _ ->
                    requestedSessionIDs += ids
                    OpenCodePendingRequests(permissions = emptyList(), forms = emptyList())
                }
                it.openCodeClient.pendingInputs = { _, _ -> emptyList() }
                it.openCodeClient.modelCatalog = { _, _ -> OpenCodeModelCatalog(models = emptyList(), defaultModel = null) }
            },
        ) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.firstContentFrameRendered)
        expect(requestedSessionIDs.isEmpty())
        family.trySend(OpenCodeSessionFamily(sessions = listOf(root, child), activeSessionIDs = emptySet()))
        family.close()
        store.receive<Action.familyResponse>()
        store.receive<Action.pendingRequestsResponse>()
        expect(requestedSessionIDs == listOf(listOf(root.id, child.id)))
        store.skipInFlightEffects()
    }

    @Test
    fun inboxReadDoesNotWaitForLocationScopedPendingRequests() {
        val session = testSession(id = "root", title = "Root")
        val location = CompletableDeferred<Unit>()
        var inboxReads = 0
        val store = TestStore(initialState = { makeState(session = session, cachedItems = emptyList()) }, withDependencies = {
            it.openCodeClient.familyPendingRequests = { _, _, _ ->
                location.await()
                OpenCodePendingRequests(permissions = emptyList(), forms = emptyList())
            }
            it.openCodeClient.pendingInputs = { _, _ ->
                inboxReads += 1
                emptyList()
            }
        }) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.familyResponse(Result.success(OpenCodeSessionFamily(sessions = listOf(session), activeSessionIDs = emptySet()))))
        expect(inboxReads == 1)
        location.complete(Unit)
        store.receive<Action.pendingRequestsResponse>()
        store.skipInFlightEffects()
    }

    @Test
    fun refreshedVisibleSessionSnapshotReobservesItsIdleWatermark() {
        val old = testSession(id = "root", title = "Root", updatedAt = 0L, idleMilliseconds = 1, viewedMilliseconds = 1)
        val refreshed = testSession(id = old.id, title = old.title, idleMilliseconds = 2, viewedMilliseconds = 1)
        val items = listOf(OpenCodeTranscriptItem.userText(id = "m1", text = "Ready"))
        val state = makeState(session = old, cachedItems = items)
        state.isVisible = true
        state.hasRenderedFirstContentFrame = true
        val store = TestStore(initialState = { state }, withDependencies = {
            it.openCodeClient.getSession = { _, _ -> refreshed }
            it.openCodeClient.transcript = { _, _, _ -> items }
            it.openCodeClient.viewSession = { _, id, idle ->
                expect(id == refreshed.id)
                expect(idle == 2L)
            }
        }) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.sessionSnapshotUpdated(refreshed)) {
            it.session = refreshed
            it.familySessions = listOf(refreshed)
        }
        store.receive<Action.refreshResponse>()
        store.receive<Action.acceptedTranscriptUpdated>()
        store.receive(Action.viewAcknowledged(sessionID = refreshed.id, idle = 2))
        expect(store.state.viewedIdle[refreshed.id] == 2L)
    }

    @Test
    fun failedEarlierHistoryFetchRemainsRetryable() {
        val session = testSession(id = "ses_history", title = "History", updatedAt = 0L)
        val items = (0 until 30).map { OpenCodeTranscriptItem.userText(id = "message-$it", text = "Message $it") }
        var requests = 0
        val error = OpenCodeClientError(message = "The server returned HTTP 504.", statusCode = 504)
        val store = TestStore(initialState = { makeState(session = session, cachedItems = items) }, withDependencies = {
            it.openCodeClient.historyPage = { _, _, _ ->
                requests += 1
                throw error
            }
        }) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        store.send(Action.showEarlierTapped)
        store.receive(Action.earlierHistoryResponse(sessionID = session.id, generation = 0, cursor = null, result = Result.failure(error)))
        expect(store.state.earlierHistory == SessionFeature.State.EarlierHistory.seekingOverlap(cursor = null))
        expect(store.state.hasEarlierItems)
        expect(store.state.earlierHistoryError == error.message)
        expect(!store.state.isLoadingEarlier)
        store.send(Action.showEarlierTapped)
        store.receive(Action.earlierHistoryResponse(sessionID = session.id, generation = 0, cursor = null, result = Result.failure(error)))
        store.finish()
        expect(requests == 2)
    }

    @Test
    fun restoredCacheWaitsForFreshOverlapBeforeStartingCursorHistory() {
        val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
        val session = OpenCodeSession(id = "ses_history", title = "History", projectID = "p", directory = "/project", updatedAt = 0L)
        val stale = (0 until 30).map { OpenCodeTranscriptItem.userText(id = "stale-$it", text = "Stale $it") }
        val fresh = (0 until 30).map { OpenCodeTranscriptItem.userText(id = "fresh-$it", text = "Fresh $it") }
        val state = SessionFeature.State(server = server, session = session, cachedItems = stale)
        state.hasRenderedFirstContentFrame = true
        state.visibleLimit = stale.size
        state.isVisible = true
        state.refresh = SessionFeature.State.Refresh(id = 1, reason = SessionFeature.RefreshReason.open)
        val store = TestStore(initialState = { state }) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        expect(!store.state.hasEarlierItems)
        store.send(Action.showEarlierTapped)
        expect(!store.state.isLoadingEarlier)

        store.send(Action.refreshResponse(
            sessionID = session.id,
            requestID = 1,
            result = Result.success(SessionFeature.AuthoritativeRefresh(
                observed = session,
                items = fresh,
                scope = SessionFeature.AuthoritativeRefresh.Scope.page,
            )),
        ))
        expect(store.state.initialTranscriptIsFresh)
        expect(store.state.hasEarlierItems)
    }

    @Test
    fun unreachableEarlierHistoryStaysQuietAndPagesAgainOnReconnect() {
        val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
        val session = OpenCodeSession(id = "ses_history", title = "History", projectID = "p", directory = "/project", updatedAt = 0L)
        val items = (0 until 30).map { OpenCodeTranscriptItem.userText(id = "message-$it", text = "Message $it") }
        val older = OpenCodeTranscriptItem.userText(id = "message-older", text = "Older")
        val unreachable = OpenCodeClientError(message = "Could not reach the OpenCode server.")
        var reachable = false
        val store = TestStore(
            initialState = { SessionFeature.State(server = server, session = session, cachedItems = items) },
            withDependencies = {
                it.openCodeClient.historyPage = { _, _, _ ->
                    if (!reachable) throw unreachable
                    OpenCodeTranscriptPage(items = listOf(older) + items, next = null)
                }
                it.openCodeClient.familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList()) }
            },
        ) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        // The automatic page fails because the server is down: no error banner,
        // just a note to try again when it is back.
        store.send(Action.showEarlierTapped)
        store.receive(Action.earlierHistoryResponse(sessionID = session.id, generation = 0, cursor = null, result = Result.failure(unreachable)))
        expect(store.state.earlierHistoryError == null)
        expect(store.state.earlierHistoryRetriesOnReconnect)
        expect(store.state.hasEarlierItems)

        reachable = true
        store.send(Action.requestsConnectionChanged(false))
        store.send(Action.requestsConnectionChanged(true))
        store.receive(Action.showEarlierTapped)
        store.receive<Action.earlierHistoryResponse>()
        expect(!store.state.earlierHistoryRetriesOnReconnect)
        expect(store.state.earlierHistory == SessionFeature.State.EarlierHistory.exhausted)
        expect(store.state.items.firstOrNull() == older)
        store.skipInFlightEffects()
    }

    @Test
    fun failedInitialLoadHealsWhenTheConnectionReturns() {
        val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
        val session = OpenCodeSession(id = "ses_cold", title = "Cold", projectID = "p", directory = "/project", updatedAt = 0L)
        val unreachable = OpenCodeClientError(message = "Could not reach the OpenCode server.")
        val item = OpenCodeTranscriptItem.userText(id = "message-1", text = "Hello")
        var reachable = false
        val state = SessionFeature.State(server = server, session = session, initialTranscriptRequestIsExternal = true)
        val store = TestStore(initialState = { state }, withDependencies = {
            it.openCodeClient.transcript = { _, _, _ ->
                if (!reachable) throw unreachable
                listOf(item)
            }
            it.openCodeClient.familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList()) }
        }) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.transcriptResponse(sessionID = session.id, result = Result.failure(unreachable)))
        expect(store.state.errorMessage == unreachable.message)

        reachable = true
        store.send(Action.requestsConnectionChanged(false))
        store.send(Action.requestsConnectionChanged(true))
        expect(store.state.errorMessage == null)
        expect(store.state.isLoading)
        store.receive<Action.refreshResponse>()
        expect(store.state.items == listOf(item))
        expect(!store.state.isLoading)
        expect(store.state.errorMessage == null)
        store.skipInFlightEffects()
    }

    @Test
    fun earlierHistoryAllowsOnlyOneInFlightPage() {
        val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
        val session = OpenCodeSession(id = "ses_history", title = "History", projectID = "p", directory = "/project", updatedAt = 0L)
        val items = (0 until 30).map { OpenCodeTranscriptItem.userText(id = "message-$it", text = "Message $it") }
        var requests = 0
        val gate = CompletableDeferred<Unit>()
        val store = TestStore(
            initialState = { SessionFeature.State(server = server, session = session, cachedItems = items) },
            withDependencies = {
                it.openCodeClient.historyPage = { _, _, _ ->
                    requests += 1
                    gate.await()
                    OpenCodeTranscriptPage(items = items, next = null)
                }
            },
        ) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.showEarlierTapped)
        store.send(Action.showEarlierTapped)
        expect(requests == 1)
        gate.complete(Unit)
        store.receive<Action.earlierHistoryResponse>()
        store.finish()
    }

    @Test
    fun draftSessionHasNoEarlierHistory() {
        val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
        val project = OpenCodeProject(id = "p", name = "Project", directory = "/project")
        val state = SessionFeature.State(server = server, draftProject = project, projects = listOf(project))
        expect(state.isDraft)
        expect(state.earlierHistory == SessionFeature.State.EarlierHistory.exhausted)
        expect(!state.hasEarlierItems)
    }

    @Test
    fun earlierHistoryPagesBeyondTwoHundredMessages() {
        val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
        val session = OpenCodeSession(id = "ses_history", title = "History", projectID = "p", directory = "/project", updatedAt = 0L)
        val items = (0 until 240).map { OpenCodeTranscriptItem.userText(id = "message-$it", text = "Message $it") }
        val requests = mutableListOf<String?>()
        val store = TestStore(
            initialState = { SessionFeature.State(server = server, session = session, cachedItems = items.takeLast(30)) },
            withDependencies = {
                it.openCodeClient.historyPage = { _, _, cursor ->
                    requests += cursor
                    val end = cursor?.toIntOrNull() ?: items.size
                    val start = maxOf(0, end - 40)
                    OpenCodeTranscriptPage(items = items.subList(start, end).toList(), next = if (start > 0) start.toString() else null)
                }
            },
        ) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        for ((index, end) in (240 downTo 40 step 40).withIndex()) {
            val start = end - 40
            val cursor = if (end == 240) null else end.toString()
            val page = OpenCodeTranscriptPage(items = items.subList(start, end).toList(), next = if (start > 0) start.toString() else null)
            if (index == 0) {
                store.send(Action.showEarlierTapped)
            } else if (index == 1) {
                store.receive(Action.fillEarlierHistory)
            } else {
                store.send(Action.showEarlierTapped)
            }
            store.receive(Action.earlierHistoryResponse(sessionID = session.id, generation = 0, cursor = cursor, result = Result.success(page)))
        }
        store.send(Action.showEarlierTapped)
        expect(store.state.items == items)
        expect(store.state.earlierHistory == SessionFeature.State.EarlierHistory.exhausted)
        expect(!store.state.hasEarlierItems)
        expect(requests == listOf(null, "200", "160", "120", "80", "40"))
        store.finish()
    }

    @Test
    fun latestSnapshotDoesNotFinishEarlierHistoryRequest() {
        val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
        val session = OpenCodeSession(id = "ses_history", title = "History", projectID = "p", directory = "/project", updatedAt = 0L)
        val item = OpenCodeTranscriptItem.userText(id = "message", text = "Latest")
        val state = SessionFeature.State(server = server, session = session, cachedItems = listOf(item))
        state.earlierHistory = SessionFeature.State.EarlierHistory.loading(cursor = null, reachedLoadedItems = false)
        val store = TestStore(initialState = { state }) { SessionFeature() }
        store.send(Action.transcriptResponse(sessionID = session.id, result = Result.success(listOf(item))))
        store.receive<Action.acceptedTranscriptUpdated>()
        expect(store.state.isLoadingEarlier)
        store.send(Action.earlierHistoryResponse(
            sessionID = session.id,
            generation = 0,
            cursor = null,
            result = Result.success(OpenCodeTranscriptPage(items = listOf(item), next = null)),
        )) {
            it.earlierHistory = SessionFeature.State.EarlierHistory.exhausted
            it.earlierHistoryInsertionID = item.id
        }
        store.receive<Action.acceptedTranscriptUpdated>()
        store.finish()
    }

    @Test
    fun mergeDropsHeldRowsThatTheWindowAlsoContains() {
        // A restored snapshot can hold a row *before* the window start that the
        // fresh window also contains (history rewritten under the same session).
        // The window wins; the transcript must never carry the id twice.
        val stale = OpenCodeTranscriptItem.assistantMarkdown(id = "reply", blocks = OpenCodeMarkdownBlock.blocks(from = "old"))
        val existing = listOf(
            OpenCodeTranscriptItem.userText(id = "u0", text = "old"),
            stale,
            OpenCodeTranscriptItem.userText(id = "w1", text = "w1"),
            OpenCodeTranscriptItem.userText(id = "w2", text = "w2"),
        )
        val fetched = listOf(
            OpenCodeTranscriptItem.userText(id = "w1", text = "w1"),
            OpenCodeTranscriptItem.userText(id = "w2", text = "w2"),
            OpenCodeTranscriptItem.userText(id = "u9", text = "new"),
            OpenCodeTranscriptItem.assistantMarkdown(id = "reply", blocks = OpenCodeMarkdownBlock.blocks(from = "new")),
        )
        val merged = SessionFeature.mergeTranscript(fetched = fetched, into = existing)
        expect(merged.map { it.id } == listOf("u0", "w1", "w2", "u9", "reply"))
        expect(merged.last() == OpenCodeTranscriptItem.assistantMarkdown(id = "reply", blocks = OpenCodeMarkdownBlock.blocks(from = "new")))
    }

    @Test
    fun replayedOlderPageNeverDuplicatesTranscriptIDs() {
        val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
        val session = OpenCodeSession(id = "ses_history", title = "History", projectID = "p", directory = "/project", updatedAt = 0L)
        val older = OpenCodeTranscriptItem.userText(id = "m1", text = "Older")
        val latest = OpenCodeTranscriptItem.userText(id = "m2", text = "Latest")
        val state = SessionFeature.State(server = server, session = session, cachedItems = listOf(older, latest))
        state.earlierHistory = SessionFeature.State.EarlierHistory.loading(cursor = "c1", reachedLoadedItems = true)
        val store = TestStore(initialState = { state }) { SessionFeature() }
        // A page whose rows already arrived (overlapping paging, replayed cursor)
        // must not prepend them a second time: the list keys its snapshot on ids.
        store.send(Action.earlierHistoryResponse(
            sessionID = session.id,
            generation = 0,
            cursor = "c1",
            result = Result.success(OpenCodeTranscriptPage(items = listOf(older), next = null)),
        )) {
            it.earlierHistory = SessionFeature.State.EarlierHistory.exhausted
            it.earlierHistoryInsertionID = older.id
        }
        store.receive<Action.acceptedTranscriptUpdated>()
        expect(store.state.items.map { it.id }.toSet().size == store.state.items.size)
        store.finish()
    }

    @Test
    fun switchingSessionsCancelsHistoryAndRejectsOldVisitResponses() {
        val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
        val root = OpenCodeSession(id = "ses_root", title = "Root", projectID = "p", directory = "/project", updatedAt = 0L)
        val child = OpenCodeSession(id = "ses_child", title = "Child", projectID = "p", directory = "/project", updatedAt = 0L, parentID = root.id)
        val item = OpenCodeTranscriptItem.userText(id = "latest", text = "Latest")
        val state = SessionFeature.State(server = server, session = root, cachedItems = listOf(item))
        state.familySessions = listOf(root, child)
        state.earlierHistory = SessionFeature.State.EarlierHistory.paging(cursor = "older")
        val pages = Channel<OpenCodeTranscriptPage>(Channel.UNLIMITED)
        var cancelled = false
        val store = TestStore(initialState = { state }, withDependencies = {
            it.openCodeClient.historyPage = { _, _, _ ->
                try {
                    pages.receive()
                } catch (cancellation: CancellationException) {
                    cancelled = true
                    throw cancellation
                }
            }
            it.openCodeClient.transcript = { _, _, _ -> emptyList() }
            it.openCodeClient.familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) }
            it.openCodeClient.pendingInputs = { _, _ -> emptyList() }
        }) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        val history = store.send(Action.showEarlierTapped)
        store.send(Action.familyPicked(child.id))
        history.finish()
        expect(cancelled)
        expect(!store.state.isLoadingEarlier)
        expect(store.state.earlierHistory == SessionFeature.State.EarlierHistory.unknownEdge)
        store.send(Action.familyPicked(root.id))
        store.send(Action.earlierHistoryResponse(
            sessionID = root.id,
            generation = 0,
            cursor = "older",
            result = Result.success(OpenCodeTranscriptPage(
                items = listOf(OpenCodeTranscriptItem.userText(id = "stale", text = "Old visit")),
                next = null,
            )),
        ))
        expect(store.state.items == listOf(item))
        expect(store.state.earlierHistory == SessionFeature.State.EarlierHistory.unknownEdge)
        store.skipInFlightEffects()
        pages.close()
    }

    @Test
    fun newestWindowJumpReopensExhaustedHistoryAndCursorFillsInteriorGap() {
        for (splitAcrossPages in listOf(false, true)) {
            val session = testSession(id = "ses_root", title = "Root")
            val first = OpenCodeTranscriptItem.userText(id = "msg_first", text = "First")
            val missing = OpenCodeTranscriptItem.userText(id = "msg_middle", text = "Originally queued")
            val later = OpenCodeTranscriptItem.userText(id = "msg_later", text = "After the prompt")
            val last = OpenCodeTranscriptItem.userText(id = "msg_last", text = "Newest authoritative text")
            val state = makeState(session = session, cachedItems = listOf(first))
            state.earlierHistory = SessionFeature.State.EarlierHistory.exhausted
            state.initialTranscriptIsFresh = true
            val store = TestStore(initialState = { state }, withDependencies = {
                it.openCodeClient.historyPage = { _, _, cursor ->
                    when (cursor) {
                        null -> OpenCodeTranscriptPage(
                            items = listOf(later, OpenCodeTranscriptItem.userText(id = "msg_last", text = "Stale page text")),
                            next = if (splitAcrossPages) "middle" else "oldest",
                        )
                        "middle" -> OpenCodeTranscriptPage(items = listOf(missing), next = "oldest")
                        else -> OpenCodeTranscriptPage(
                            items = if (splitAcrossPages) listOf(first) else listOf(first, missing),
                            next = null,
                        )
                    }
                }
            }) { SessionFeature() }
            store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
            store.send(Action.transcriptResponse(sessionID = session.id, result = Result.success(listOf(last))))
            expect(store.state.earlierHistory == SessionFeature.State.EarlierHistory.unknownEdge)
            expect(store.state.items == listOf(first, last))
            expect(store.state.historyGeneration == 0, "Repairing a gap must not reset the reader's viewport")
            store.send(Action.showEarlierTapped)
            store.receive<Action.earlierHistoryResponse>()
            store.receive<Action.earlierHistoryResponse>()
            if (splitAcrossPages) store.receive<Action.earlierHistoryResponse>()
            store.finish()
            expect(store.state.items == listOf(first, missing, later, last))
            expect(store.state.earlierHistory == SessionFeature.State.EarlierHistory.exhausted)
        }
    }

    @Test
    fun historyGapRepairRejectsCursorResponsesFromBeforeTheGap() {
        val session = testSession(id = "ses_root", title = "Root")
        val state = makeState(session = session)
        state.earlierHistoryGeneration = 1
        state.earlierHistory = SessionFeature.State.EarlierHistory.loading(cursor = null, reachedLoadedItems = false)
        val store = TestStore(initialState = { state }) { SessionFeature() }
        store.send(Action.earlierHistoryResponse(
            sessionID = session.id,
            generation = 0,
            cursor = null,
            result = Result.success(OpenCodeTranscriptPage(items = emptyList(), next = null)),
        ))
        expect(store.state.isLoadingEarlier)
    }

    @Test
    fun returningToAlreadyViewedSessionStillLoadsLatestTranscript() {
        val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
        val session = OpenCodeSession(
            id = "ses_resume",
            title = "Resume",
            projectID = "p",
            directory = "/project",
            updatedAt = 0L,
            idleMilliseconds = 1234,
            viewedMilliseconds = 1234,
        )
        val items = listOf(OpenCodeTranscriptItem.assistantMarkdown(id = "final", blocks = OpenCodeMarkdownBlock.blocks(from = "Finished while away")))
        val store = TestStore(
            initialState = { SessionFeature.State(server = server, session = session, cachedItems = items) },
            withDependencies = {
                it.continuousClock = ImmediateClock()
                it.openCodeClient.getSession = { _, _ -> session }
                it.openCodeClient.transcript = { _, _, _ -> items }
                it.openCodeClient.viewSession = { _, _, _ -> error("Already viewed completion needs no new receipt") }
            },
        ) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        store.send(Action.visibilityChanged(true))
        store.send(Action.firstContentFrameRendered)
        store.receive<Action.refreshResponse>()
        store.receive<Action.acceptedTranscriptUpdated>()
        expect(store.state.items == items)
        store.finish()
    }

    private fun makeResumeStore(
        clock: TestClock,
        gate: Channel<Unit>,
        cachedItems: List<OpenCodeTranscriptItem> = listOf(OpenCodeTranscriptItem.userText(id = "m1", text = "Ready")),
        transcriptResult: Result<List<OpenCodeTranscriptItem>>? = null,
    ): TestStore<SessionFeature.State, SessionFeature.Action> {
        val session = testSession(id = "ses_resume", title = "Resume")
        val state = makeState(session = session, cachedItems = cachedItems)
        state.hasRenderedFirstContentFrame = true
        val result = transcriptResult ?: Result.success(cachedItems)
        val store = TestStore(initialState = { state }, withDependencies = {
            it.continuousClock = clock
            it.openCodeClient.getSession = { _, _ -> session }
            it.openCodeClient.transcript = { _, _, _ ->
                gate.receive()
                result.getOrThrow()
            }
        }) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        return store
    }

    @Test
    fun fastResumeRefreshNeverShowsSyncCue() {
        val clock = TestClock()
        val gate = Channel<Unit>(Channel.UNLIMITED)
        val store = makeResumeStore(clock = clock, gate = gate)

        store.send(Action.visibilityChanged(true))
        expect(!store.state.isResyncing)
        clock.advance(by = 149.milliseconds)
        gate.trySend(Unit)
        gate.close()
        store.receive<Action.refreshResponse>()
        expect(!store.state.isResyncing)
        store.receive<Action.acceptedTranscriptUpdated>()
        store.finish()
    }

    @Test
    fun slowResumeRefreshShowsCueForThreeHundredMilliseconds() {
        val clock = TestClock()
        val gate = Channel<Unit>(Channel.UNLIMITED)
        val store = makeResumeStore(clock = clock, gate = gate)

        store.send(Action.visibilityChanged(true))
        clock.advance(by = 150.milliseconds)
        store.receive(Action.resyncDelayElapsed(refreshID = 1))
        expect(store.state.isResyncing)
        gate.trySend(Unit)
        gate.close()
        store.receive<Action.refreshResponse>()
        store.receive<Action.acceptedTranscriptUpdated>()
        clock.advance(by = 299.milliseconds)
        expect(store.state.isResyncing)
        clock.advance(by = 1.milliseconds)
        store.receive(Action.resyncMinimumElapsed(refreshID = 1))
        expect(!store.state.isResyncing)
        store.finish()
    }

    @Test
    fun failedResumeKeepsCachedRowsAndSettlesIntoLastKnownState() {
        val clock = TestClock()
        val cached = listOf(OpenCodeTranscriptItem.userText(id = "m1", text = "Cached"))
        val gate = Channel<Unit>(Channel.UNLIMITED)
        val store = makeResumeStore(
            clock = clock,
            gate = gate,
            cachedItems = cached,
            transcriptResult = Result.failure(OpenCodeClientError(message = "Offline")),
        )

        store.send(Action.visibilityChanged(true))
        clock.advance(by = 150.milliseconds)
        store.receive(Action.resyncDelayElapsed(refreshID = 1))
        gate.trySend(Unit)
        gate.close()
        store.receive<Action.refreshResponse>()
        expect(store.state.items == cached)
        expect(store.state.refreshFailed)
        expect(store.state.isResyncing)
        clock.advance(by = 300.milliseconds)
        store.receive(Action.resyncMinimumElapsed(refreshID = 1))
        expect(!store.state.isResyncing)
        store.finish()
    }

    @Test
    fun duplicateVisibleNotificationDoesNotRestartFreshness() {
        val session = testSession(id = "ses_resume", title = "Resume")
        val state = makeState(session = session, cachedItems = listOf(OpenCodeTranscriptItem.userText(id = "m1", text = "Cached")))
        state.hasRenderedFirstContentFrame = true
        val gate = CompletableDeferred<Unit>()
        var reads = 0
        val store = TestStore(initialState = { state }, withDependencies = {
            it.continuousClock = ImmediateClock()
            it.openCodeClient.getSession = { _, _ -> session }
            it.openCodeClient.transcript = { _, _, _ ->
                reads += 1
                gate.await()
                emptyList()
            }
        }) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.visibilityChanged(true))
        store.send(Action.visibilityChanged(true))
        expect(reads == 1)
        gate.complete(Unit)
        store.skipReceivedActions()
    }

    @Test
    fun visibleSessionAcknowledgesOnlyObservedIdleAfterLoadingItsTranscript() {
        val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
        val observed = OpenCodeSession(
            id = "ses_view",
            title = "Viewed",
            projectID = "p",
            directory = "/project",
            updatedAt = 0L,
            idleMilliseconds = 1234,
            viewedMilliseconds = 1000,
        )
        val items = listOf(OpenCodeTranscriptItem.userText(id = "message", text = "Loaded result"))
        val store = TestStore(
            initialState = { SessionFeature.State(server = server, session = observed, cachedItems = items) },
            withDependencies = {
                it.continuousClock = ImmediateClock()
                it.openCodeClient.getSession = { _, id ->
                    expect(id == observed.id)
                    observed
                }
                it.openCodeClient.transcript = { _, id, _ ->
                    expect(id == observed.id)
                    items
                }
                it.openCodeClient.viewSession = { _, id, idle ->
                    expect(id == observed.id)
                    expect(idle == 1234L)
                }
            },
        ) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        store.send(Action.visibilityChanged(true)) { it.isVisible = true }
        store.send(Action.firstContentFrameRendered)
        store.receive<Action.refreshResponse>()
        store.receive<Action.acceptedTranscriptUpdated>()
        store.receive(Action.viewAcknowledged(sessionID = observed.id, idle = 1234)) {
            it.viewedIdle += (observed.id to 1234L)
            it.session = it.session?.copy(viewedMilliseconds = 1234)
        }
        store.send(Action.visibilityChanged(false)) { it.isVisible = false }
        // A response from before the screen was hidden cannot acknowledge a newer idle.
        val newer = observed.copy(idleMilliseconds = 2000)
        store.send(Action.refreshResponse(
            sessionID = observed.id,
            requestID = 1,
            result = Result.success(SessionFeature.AuthoritativeRefresh(
                observed = newer,
                items = items,
                scope = SessionFeature.AuthoritativeRefresh.Scope.page,
            )),
        ))
        store.finish()
    }

    @Test
    fun catchUpCardShowsTheSessionWithoutSendingAReadReceipt() {
        val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
        val observed = OpenCodeSession(
            id = "ses_card",
            title = "Card",
            projectID = "p",
            directory = "/project",
            updatedAt = 0L,
            idleMilliseconds = 1234,
            viewedMilliseconds = 1000,
        )
        val items = listOf(OpenCodeTranscriptItem.userText(id = "message", text = "Loaded result"))
        val state = SessionFeature.State(server = server, session = observed, cachedItems = items)
        state.readsOnView = false
        val store = TestStore(initialState = { state }, withDependencies = {
            it.continuousClock = ImmediateClock()
            it.openCodeClient.getSession = { _, _ -> observed }
            it.openCodeClient.transcript = { _, _, _ -> items }
            it.openCodeClient.viewSession = { _, _, _ -> error("Only the reader's swipe marks a catch-up card read") }
        }) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        store.send(Action.visibilityChanged(true)) { it.isVisible = true }
        store.send(Action.firstContentFrameRendered)
        store.receive<Action.refreshResponse>()
        store.receive<Action.acceptedTranscriptUpdated>()
        store.send(Action.visibilityChanged(false))
        store.finish()
        expect(store.state.viewedIdle.isEmpty())
    }

    @Test
    fun failedTranscriptDoesNotAcknowledgeViewedState() {
        val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
        val session = OpenCodeSession(
            id = "ses_view",
            title = "Viewed",
            projectID = "p",
            directory = "/project",
            updatedAt = 0L,
            idleMilliseconds = 1234,
        )
        val store = TestStore(
            initialState = {
                SessionFeature.State(
                    server = server,
                    session = session,
                    cachedItems = listOf(OpenCodeTranscriptItem.userText(id = "stale", text = "Stale")),
                )
            },
            withDependencies = {
                it.continuousClock = ImmediateClock()
                it.openCodeClient.getSession = { _, _ -> session }
                it.openCodeClient.transcript = { _, _, _ -> throw OpenCodeClientError(message = "Offline") }
                it.openCodeClient.viewSession = { _, _, _ -> error("Failed transcript must not be marked viewed") }
            },
        ) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        store.send(Action.visibilityChanged(true)) { it.isVisible = true }
        store.send(Action.firstContentFrameRendered)
        store.finish()
        expect(store.state.viewedIdle.isEmpty())
    }

    @Test
    fun deliveredPromptOutsideNewestWindowLeavesQueueWithoutMovingToTail() {
        val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
        val session = OpenCodeSession(id = "ses_1", title = "Chat", projectID = "proj_1", directory = "/tmp/demo", updatedAt = System.currentTimeMillis())
        val latest = listOf(OpenCodeTranscriptItem.userText(id = "msg_newer", text = "Sent later"))
        val state = SessionFeature.State(server = server, session = session, cachedItems = latest)
        state.queuedInputs = listOf(OpenCodePendingInput(id = "msg_old", text = "Sent first"))
        state.promptStates += ("msg_old" to SessionFeature.State.PromptState(
            requestEpoch = 1,
            admission = SessionFeature.State.PromptState.Admission.admitted,
        ))
        state.optimisticPromptIDs = setOf("msg_old")
        val store = TestStore(initialState = { state }, withDependencies = {
            it.openCodeClient.message = { _, sessionID, messageID ->
                expect(sessionID == "ses_1")
                expect(messageID == "msg_old")
                listOf(OpenCodeTranscriptItem.userText(id = "msg_old", text = "Sent first"))
            }
        }) { SessionFeature() }
        store.send(Action.pendingInputsResponse(sessionID = session.id, inputs = emptyList()))
        store.receive(Action.promptEchoResponse(
            sessionID = session.id,
            messageID = "msg_old",
            requestEpoch = 1,
            items = listOf(OpenCodeTranscriptItem.userText(id = "msg_old", text = "Sent first")),
        )) {
            it.queuedInputs = emptyList()
            it.promptStates = emptyMap()
            it.optimisticPromptIDs = emptySet()
            it.promotedPromptIDs = listOf("msg_old")
        }
        expect(store.state.items == latest)
        // A late inbox response must not resurrect the already-delivered row.
        store.send(Action.pendingInputsResponse(sessionID = session.id, inputs = listOf(OpenCodePendingInput(id = "msg_old", text = "Sent first"))))
        store.finish()
    }

    @Test
    fun pendingPromotionSurvivesInboxBeforeHistory() {
        for (source in listOf("typed", "sendNow", "voice")) {
            val session = testSession(id = "ses_promotion", title = "Promotion")
            val first = OpenCodeTranscriptItem.userText(id = "msg_first", text = "First")
            val id = "msg_11111111111111111111111111111111"
            val text = if (source == "voice") {
                "Voice delegation. Instruction is the cleaned interpretation of the user's request.\n{\"instruction\":\"Check the build\",\"transcript_delta\":[\"user: check it\"]}"
            } else {
                "Check the build"
            }
            val input = OpenCodePendingInput(
                id = id,
                text = text,
                delivery = if (source == "sendNow") OpenCodeDelivery.queue else OpenCodeDelivery.steer,
            )
            val state = makeState(session = session, cachedItems = listOf(first))
            state.isExternallyActive = true
            if (source != "voice") {
                state.promptStates += (id to SessionFeature.State.PromptState(
                    requestEpoch = 1,
                    admission = SessionFeature.State.PromptState.Admission.admitted,
                ))
            }
            val gate = CompletableDeferred<Unit>()
            val store = TestStore(initialState = { state }, withDependencies = {
                it.openCodeClient.message = { _, _, messageID ->
                    expect(messageID == id)
                    gate.await()
                    listOf(OpenCodeTranscriptItem.userText(id = id, text = text))
                }
                it.openCodeClient.setPendingInputDelivery = { _, _, _, _ -> }
                it.openCodeClient.pendingInputs = { _, _ -> listOf(OpenCodePendingInput(id = id, text = text, delivery = OpenCodeDelivery.steer)) }
            }) { SessionFeature() }
            store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
            store.send(Action.pendingInputsResponse(sessionID = session.id, inputs = listOf(input)))
            if (source == "sendNow") {
                store.send(Action.pendingInputSendNowTapped(id))
                store.receive<Action.pendingInputsResponse>()
            }
            val pending = store.state.queuedInputRows
            expect(pending.map { it.id } == listOf(id))
            store.send(Action.pendingInputsResponse(sessionID = session.id, inputs = emptyList()))
            expect(store.state.queuedInputRows == pending, "An inbox omission is not a rendered promotion")
            gate.complete(Unit)
            store.finish()
            store.skipReceivedActions()
            expect(store.state.items == listOf(first, OpenCodeTranscriptItem.userText(id = id, text = text)))
            expect(store.state.queuedInputs.isEmpty())
            val entries = TranscriptEntry.build(from = store.state.items, isRunning = true)
            val plan = TranscriptRowIdentityPlan.make(
                previousEntries = TranscriptEntry.build(from = listOf(first)),
                previousQueued = pending,
                previousEntryStableIDs = mapOf("user-msg_first" to "user-msg_first"),
                previousQueuedStableIDs = mapOf(id to id),
                entries = entries,
                queued = store.state.queuedInputRows,
            )
            expect(plan.entryStableIDs["user-$id"] == id)
            expect(plan.promotedEntryIDs == setOf("user-$id"))
            // A page read captured before delivery must not erase the targeted echo.
            store.send(Action.transcriptResponse(sessionID = session.id, result = Result.success(listOf(first))))
            expect(store.state.items == listOf(first, OpenCodeTranscriptItem.userText(id = id, text = text)))
            // A newer page can omit it too, but it must stay before that page, never
            // stick below later replies as if it were still an undelivered prompt.
            val later = OpenCodeTranscriptItem.assistantMarkdown(id = "msg_later-text-0", blocks = emptyList())
            store.send(Action.transcriptResponse(sessionID = session.id, result = Result.success(listOf(later))))
            val promptIndex = store.state.items.indexOfFirst { it.id == id }
            val laterIndex = store.state.items.indexOfFirst { it.id == later.id }
            expect(promptIndex >= 0 && laterIndex >= 0 && promptIndex < laterIndex)
            store.send(Action.transcriptResponse(
                sessionID = session.id,
                result = Result.success(listOf(first, OpenCodeTranscriptItem.userText(id = id, text = text))),
            ))
            expect(store.state.optimisticPromptIDs.isEmpty())
            expect(store.state.echoedPromptIDs.isEmpty())
            store.finish()
        }
    }

    @Test
    fun cancelledPromotionRejectsItsLateEcho() {
        for (remote in listOf(false, true)) {
            val session = testSession(id = "ses_cancel", title = "Cancel")
            val first = OpenCodeTranscriptItem.userText(id = "first", text = "First")
            val input = OpenCodePendingInput(id = "msg_cancel", text = "Cancel me")
            val state = makeState(session = session, cachedItems = listOf(first))
            if (!remote) {
                state.promptStates += (input.id to SessionFeature.State.PromptState(
                    requestEpoch = 1,
                    admission = SessionFeature.State.PromptState.Admission.admitted,
                ))
            }
            val gate = CompletableDeferred<Unit>()
            val store = TestStore(initialState = { state }, withDependencies = {
                it.openCodeClient.message = { _, _, _ ->
                    gate.await()
                    listOf(OpenCodeTranscriptItem.userText(id = input.id, text = input.text))
                }
                it.openCodeClient.cancelPendingInput = { _, _, _ -> }
                it.openCodeClient.pendingInputs = { _, _ -> emptyList() }
            }) { SessionFeature() }
            store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
            store.send(Action.pendingInputsResponse(sessionID = session.id, inputs = listOf(input)))
            store.send(Action.pendingInputsResponse(sessionID = session.id, inputs = emptyList()))
            expect(store.state.queuedInputs == listOf(input))
            store.send(if (remote) Action.inputCancelled(input.id) else Action.pendingInputCancelTapped(input.id))
            gate.complete(Unit)
            store.finish()
            store.skipReceivedActions()
            expect(store.state.queuedInputs.isEmpty())
            expect(store.state.pendingPromptPositions.isEmpty())
            expect(store.state.items == listOf(first))
            store.send(Action.pendingInputsResponse(sessionID = session.id, inputs = listOf(input)))
            expect(store.state.queuedInputs.isEmpty())
        }
    }

    @Test
    fun remotePromptLookupOnlyRetiresAuthoritativeAbsence() {
        for (status in listOf(404, 500)) {
            val session = testSession(id = "ses_lookup", title = "Lookup")
            val input = OpenCodePendingInput(id = "msg_remote", text = "From voice")
            val store = TestStore(initialState = { makeState(session = session, cachedItems = emptyList()) }, withDependencies = {
                it.openCodeClient.message = { _, _, _ -> throw OpenCodeClientError(message = "Read failed", statusCode = status) }
            }) { SessionFeature() }
            store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
            store.send(Action.pendingInputsResponse(sessionID = session.id, inputs = listOf(input)))
            store.send(Action.pendingInputsResponse(sessionID = session.id, inputs = emptyList()))
            store.finish()
            store.skipReceivedActions()
            expect(store.state.queuedInputs == if (status == 404) emptyList() else listOf(input))
            expect(!store.state.cancelledInputIDs.contains(input.id), "A 404 is not an explicit cancellation")
            store.send(Action.pendingInputsResponse(sessionID = session.id, inputs = listOf(input)))
            expect(store.state.queuedInputs == listOf(input), "An authoritative inbox read can restore a remote prompt")
        }
    }

    @Test
    fun promotionDoesNotDependOnAnUndeliveredPredecessor() {
        for (path in listOf("steer", "sendNow", "cancelFirst", "voice")) {
            val session = testSession(id = "ses_order", title = "Order")
            val first = OpenCodeTranscriptItem.userText(id = "msg_first", text = "First")
            val queued = OpenCodePendingInput(id = "msg_queued", text = "Later", delivery = OpenCodeDelivery.queue)
            val second = OpenCodePendingInput(
                id = "msg_second",
                text = if (path == "voice") "Voice delegation" else "Do this now",
                delivery = if (path == "sendNow") OpenCodeDelivery.queue else OpenCodeDelivery.steer,
            )
            val state = makeState(session = session, cachedItems = listOf(first))
            state.isExternallyActive = true
            val store = TestStore(initialState = { state }, withDependencies = {
                it.openCodeClient.setPendingInputDelivery = { _, _, _, _ -> }
                it.openCodeClient.pendingInputs = { _, _ ->
                    listOf(queued, OpenCodePendingInput(id = second.id, text = second.text, delivery = OpenCodeDelivery.steer))
                }
            }) { SessionFeature() }
            store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
            store.send(Action.pendingInputsResponse(sessionID = session.id, inputs = listOf(queued)))
            store.send(Action.pendingInputsResponse(sessionID = session.id, inputs = listOf(queued, second)))
            if (path == "sendNow") {
                store.send(Action.pendingInputSendNowTapped(second.id))
                store.receive<Action.pendingInputsResponse>()
            }
            if (path == "cancelFirst") store.send(Action.inputCancelled(queued.id))
            val epoch = store.state.pendingPromptPositions.getValue(second.id).requestEpoch
            store.send(Action.promptEchoResponse(
                sessionID = session.id,
                messageID = second.id,
                requestEpoch = epoch,
                items = listOf(OpenCodeTranscriptItem.userText(id = second.id, text = second.text)),
            ))
            expect(store.state.items.map { it.id } == listOf(first.id, second.id))
            expect(!store.state.queuedInputs.any { it.id == second.id })
            if (path != "cancelFirst") {
                val epoch = store.state.pendingPromptPositions.getValue(queued.id).requestEpoch
                store.send(Action.promptEchoResponse(
                    sessionID = session.id,
                    messageID = queued.id,
                    requestEpoch = epoch,
                    items = listOf(OpenCodeTranscriptItem.userText(id = queued.id, text = queued.text)),
                ))
                expect(
                    store.state.items.map { it.id } == listOf(first.id, second.id, queued.id),
                    "The queued prompt delivers after the steering prompt",
                )
            }
            store.finish()
        }
    }

    @Test
    fun promotionSurvivesATransientLastRow() {
        for (kind in listOf("info", "sendFailure", "notice", "projection")) {
            val session = testSession(id = "ses_transient", title = "Transient")
            val first = OpenCodeTranscriptItem.userText(id = "msg_first", text = "First")
            val transient = when (kind) {
                "info" -> OpenCodeTranscriptItem.info(id = "transient", text = "Temporary")
                "sendFailure" -> OpenCodeTranscriptItem.sendFailure(id = "transient", message = "Temporary")
                "notice" -> OpenCodeTranscriptItem.notice(id = "transient", text = "Temporary")
                else -> OpenCodeTranscriptItem.assistantMarkdown(id = "msg_reply-text-0", blocks = emptyList())
            }
            val initial = if (kind == "projection") listOf(transient) else listOf(first, transient)
            val refreshed = if (kind == "projection") {
                listOf(OpenCodeTranscriptItem.assistantMarkdown(id = "msg_reply-text-1", blocks = emptyList()))
            } else {
                listOf(first)
            }
            val store = TestStore(initialState = { makeState(session = session, cachedItems = initial) }, withDependencies = {
                it.continuousClock = ImmediateClock()
                it.openCodeClient.getSession = { _, _ -> session }
                it.openCodeClient.message = { _, _, _ -> refreshed }
            }) { SessionFeature() }
            store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
            store.send(Action.pendingInputsResponse(sessionID = session.id, inputs = listOf(OpenCodePendingInput(id = "msg_next", text = "Next"))))
            if (kind == "projection") {
                store.send(Action.refreshRequested(SessionFeature.RefreshReason.settlement(messageID = "msg_reply")))
                store.receive<Action.refreshResponse>()
            } else {
                store.send(Action.transcriptResponse(sessionID = session.id, result = Result.success(refreshed)))
            }
            store.send(Action.promptEchoResponse(
                sessionID = session.id,
                messageID = "msg_next",
                requestEpoch = 1,
                items = listOf(OpenCodeTranscriptItem.userText(id = "msg_next", text = "Next")),
            ))
            expect(store.state.items.map { it.id } == refreshed.map { it.id } + "msg_next")
            store.finish()
        }
    }

    @Test
    fun newerInboxFetchWinsOverALateEmptyRead() {
        val session = testSession(id = "ses_inbox", title = "Inbox")
        val input = OpenCodePendingInput(id = "msg_voice", text = "From voice")
        val old = Channel<List<OpenCodePendingInput>>(Channel.UNLIMITED)
        val `new` = Channel<List<OpenCodePendingInput>>(Channel.UNLIMITED)
        val started = Channel<Int>(Channel.UNLIMITED)
        var count = 0
        var lookups = 0
        val store = TestStore(initialState = { makeState(session = session, cachedItems = emptyList()) }, withDependencies = {
            it.openCodeClient.pendingInputs = { _, _ ->
                count += 1
                val index = count
                started.trySend(index)
                val inputs = (if (index == 1) old else `new`).receiveCatching().getOrNull()
                inputs ?: emptyList()
            }
            it.openCodeClient.message = { _, _, _ ->
                lookups += 1
                throw OpenCodeClientError(message = "Not delivered", statusCode = 404)
            }
        }) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        store.send(Action.inputEnqueued)
        expect(started.tryReceive().getOrNull() == 1)
        store.send(Action.inputEnqueued)
        expect(started.tryReceive().getOrNull() == 2)
        `new`.trySend(listOf(input))
        `new`.close()
        store.receive<Action.pendingInputsResponse>()
        old.trySend(emptyList())
        old.close()
        store.finish()
        store.skipReceivedActions()
        expect(lookups == 0, "The cancelled older inbox read must not trigger a message lookup")
        expect(store.state.queuedInputs == listOf(input))
        expect(store.state.cancelledInputIDs.isEmpty())
        started.close()
    }

    @Test
    fun typedSteeringOvertakesAQueuedPrompt() {
        val session = testSession(id = "ses_typed", title = "Typed")
        val queued = OpenCodePendingInput(id = "msg_queued", text = "Later", delivery = OpenCodeDelivery.queue)
        val id = "msg_00000000000000000000000000000000"
        val state = makeState(session = session, cachedItems = listOf(OpenCodeTranscriptItem.userText(id = "msg_first", text = "First")))
        state.isExternallyActive = true
        state.draft = "Now"
        val wait = CompletableDeferred<Unit>()
        val store = TestStore(initialState = { state }, withDependencies = {
            it.uuid = UUIDGenerator.incrementing()
            it.openCodeClient.sendPrompt = { _, _, _, _, _, messageID, delivery ->
                expect(messageID == id)
                expect(delivery == OpenCodeDelivery.steer)
            }
            it.openCodeClient.pendingInputs = { _, _ -> listOf(queued, OpenCodePendingInput(id = id, text = "Now")) }
            it.openCodeClient.waitForIdle = { _, _ ->
                wait.await()
                throw OpenCodeClientError(message = "Disconnected")
            }
        }) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        store.send(Action.pendingInputsResponse(sessionID = session.id, inputs = listOf(queued)))
        store.send(Action.sendTapped)
        store.receive<Action.promptAdmitted>()
        store.receive<Action.pendingInputsResponse>()
        val epoch = store.state.promptStates.getValue(id).requestEpoch
        store.send(Action.promptEchoResponse(
            sessionID = session.id,
            messageID = id,
            requestEpoch = epoch,
            items = listOf(OpenCodeTranscriptItem.userText(id = id, text = "Now")),
        ))
        expect(store.state.items.map { it.id } == listOf("msg_first", id))
        store.send(Action.promptEchoResponse(
            sessionID = session.id,
            messageID = queued.id,
            requestEpoch = 1,
            items = listOf(OpenCodeTranscriptItem.userText(id = queued.id, text = queued.text)),
        ))
        expect(store.state.items.map { it.id } == listOf("msg_first", id, queued.id))
        wait.complete(Unit)
        store.finish()
    }

    @Test
    fun deliveredPromptBehindANewerAssistantWindowDoesNotBecomeTheTail() {
        val session = testSession(id = "ses_window", title = "Window")
        val first = OpenCodeTranscriptItem.userText(id = "msg_first", text = "First")
        val later = OpenCodeTranscriptItem.assistantMarkdown(id = "msg_later-text-0", blocks = emptyList())
        val store = TestStore(initialState = { makeState(session = session, cachedItems = listOf(first)) }) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        store.send(Action.pendingInputsResponse(sessionID = session.id, inputs = listOf(OpenCodePendingInput(id = "msg_pending", text = "Earlier"))))
        // The newest page has moved beyond the prompt, even though it contains
        // only assistant messages and therefore adds no new prompt IDs.
        store.send(Action.transcriptResponse(sessionID = session.id, result = Result.success(listOf(later))))
        store.send(Action.promptEchoResponse(sessionID = session.id, messageID = "msg_pending", requestEpoch = 1, items = listOf(OpenCodeTranscriptItem.userText(id = "msg_pending", text = "Earlier"))))
        expect(store.state.items == listOf(first, later))
        expect(store.state.queuedInputs.isEmpty())
        store.finish()
    }

    @Test
    fun lateFirstPromptEchoRespectsInboxOrder() {
        for (firstIsQueued in listOf(false, true)) {
            val session = testSession(id = "ses_order", title = "Order")
            val first = OpenCodeTranscriptItem.userText(id = "msg_a", text = "First")
            val second = OpenCodeTranscriptItem.userText(id = "msg_b", text = "Second")
            val inputs = listOf(
                OpenCodePendingInput(id = first.id, text = "First", delivery = if (firstIsQueued) OpenCodeDelivery.queue else OpenCodeDelivery.steer),
                OpenCodePendingInput(id = second.id, text = "Second", delivery = OpenCodeDelivery.steer),
            )
            val state = makeState(session = session, cachedItems = emptyList())
            state.earlierHistory = SessionFeature.State.EarlierHistory.loading(cursor = null, reachedLoadedItems = false)
            val store = TestStore(initialState = { state }) { SessionFeature() }
            store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
            store.send(Action.pendingInputsResponse(sessionID = session.id, inputs = inputs))
            // Repeated reads must preserve the same predecessor ordering too.
            store.send(Action.pendingInputsResponse(sessionID = session.id, inputs = inputs))
            store.send(Action.transcriptResponse(sessionID = session.id, result = Result.success(listOf(second))))
            store.send(Action.promptEchoResponse(sessionID = session.id, messageID = first.id, requestEpoch = 1, items = listOf(first)))
            expect(store.state.queuedInputs.isEmpty())
            if (firstIsQueued) {
                expect(store.state.items == listOf(second, first), "Steering can overtake a queued prompt")
            } else {
                expect(store.state.items == listOf(second), "The earlier steer must not append after the later one")
                store.send(Action.earlierHistoryResponse(sessionID = session.id, generation = 0, cursor = null, result = Result.success(OpenCodeTranscriptPage(items = listOf(first, second), next = null))))
                expect(store.state.items == listOf(first, second), "Paged history restores the earlier steer before its successor")
            }
            store.finish()
        }
    }

    @Test
    fun oneMissingPendingPromptDoesNotReorderItsSiblings() {
        val session = testSession(id = "ses_order", title = "Order")
        val inputs = listOf(OpenCodePendingInput(id = "msg_a", text = "First"), OpenCodePendingInput(id = "msg_b", text = "Second"))
        val store = TestStore(
            initialState = { makeState(session = session, cachedItems = emptyList()) },
            withDependencies = {
                it.openCodeClient.message = { _, _, _ -> throw OpenCodeClientError(message = "Offline") }
            },
        ) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        store.send(Action.pendingInputsResponse(sessionID = session.id, inputs = inputs))
        store.send(Action.pendingInputsResponse(sessionID = session.id, inputs = listOf(inputs[1])))
        expect(store.state.queuedInputs == inputs)
        store.finish()
        store.send(Action.promptEchoResponse(sessionID = session.id, messageID = "msg_a", requestEpoch = 1, items = listOf(OpenCodeTranscriptItem.userText(id = "msg_a", text = "First"))))
        store.send(Action.promptEchoResponse(sessionID = session.id, messageID = "msg_b", requestEpoch = 2, items = listOf(OpenCodeTranscriptItem.userText(id = "msg_b", text = "Second"))))
        expect(store.state.items.map { it.id } == listOf("msg_a", "msg_b"))
        expect(store.state.queuedInputs.isEmpty())
    }

    @Test
    fun failedPromptLookupKeepsQueueAndLateInboxCannotDuplicateRemotePrompt() {
        val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
        val session = OpenCodeSession(id = "ses_1", title = "Chat", projectID = "proj_1", directory = "/tmp/demo", updatedAt = System.currentTimeMillis())
        val state = SessionFeature.State(server = server, session = session, cachedItems = listOf(OpenCodeTranscriptItem.userText(id = "msg_remote", text = "Other client")))
        state.queuedInputs = listOf(OpenCodePendingInput(id = "msg_pending", text = "Still pending"))
        state.promptStates += ("msg_pending" to SessionFeature.State.PromptState(requestEpoch = 1, admission = SessionFeature.State.PromptState.Admission.admitted))
        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.openCodeClient.message = { _, _, _ -> throw OpenCodeClientError(message = "Offline") }
            },
        ) { SessionFeature() }
        store.send(Action.pendingInputsResponse(sessionID = session.id, inputs = listOf(OpenCodePendingInput(id = "msg_remote", text = "Other client"))))
        store.finish()
        expect(store.state.queuedInputs.map { it.id } == listOf("msg_pending"))
    }

    @Test
    fun switchingSessionsCancelsPromptLookupAndRejectsItsLateEcho() {
        val root = testSession(id = "ses_root", title = "Root")
        val child = testSession(id = "ses_child", title = "Child", parentID = root.id)
        val state = makeState(session = root, cachedItems = emptyList())
        state.familySessions = listOf(root, child)
        state.queuedInputs = listOf(OpenCodePendingInput(id = "msg_pending", text = "Root prompt"))
        state.promptStates += ("msg_pending" to SessionFeature.State.PromptState(requestEpoch = 1, admission = SessionFeature.State.PromptState.Admission.admitted))
        var cancelled = false
        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.openCodeClient.message = { _, _, _ ->
                    try {
                        suspendCancellableCoroutine<Nothing> { continuation ->
                            continuation.invokeOnCancellation { cancelled = true }
                        }
                    } catch (error: CancellationException) {
                        cancelled = true
                        throw error
                    }
                }
                it.openCodeClient.transcript = { _, _, _ -> emptyList() }
                it.openCodeClient.familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) }
                it.openCodeClient.pendingInputs = { _, _ -> emptyList() }
            },
        ) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        val lookup = store.send(Action.pendingInputsResponse(sessionID = root.id, inputs = emptyList()))
        store.send(Action.familyPicked(child.id))
        lookup.finish()
        expect(cancelled)
        store.send(Action.promptEchoResponse(sessionID = root.id, messageID = "msg_pending", requestEpoch = 1, items = listOf(OpenCodeTranscriptItem.userText(id = "msg_pending", text = "Root prompt"))))
        expect(store.state.items.isEmpty())
        expect(store.state.promotedPromptIDs.isEmpty())
        store.skipInFlightEffects()
    }

    @Test
    fun sendingWhileRunningQueuesOptimisticallyAndSurvivesStalePolls() {
        val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
        val session = OpenCodeSession(id = "ses_1", title = "Chat", projectID = "proj_1", directory = "/tmp/demo", updatedAt = System.currentTimeMillis())
        val state = SessionFeature.State(server = server, session = session, cachedItems = listOf(OpenCodeTranscriptItem.userText(id = "m1", text = "first")))
        state.isExternallyActive = true
        state.draft = "second"
        val localID = "msg_00000000000000000000000000000000"

        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.uuid = UUIDGenerator.incrementing()
                // Hold the send open so the poll below is genuinely stale.
                it.openCodeClient.sendPrompt = { _, _, _, _, _, messageID, delivery ->
                    expect(messageID == localID)
                    expect(delivery == OpenCodeDelivery.steer)
                    try {
                        delay(400.milliseconds)
                    } catch (_: CancellationException) {
                    }
                }
                it.openCodeClient.waitForIdle = { _, _ -> }
                it.openCodeClient.transcript = { _, _, _ -> listOf(OpenCodeTranscriptItem.userText(id = "m1", text = "first")) }
                it.openCodeClient.message = { _, _, _ -> throw OpenCodeClientError(message = "Not promoted") }
                it.openCodeClient.familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList()) }
            },
        ) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.sendTapped) {
            it.optimisticPromptIDs = setOf(localID)
            // Mid-run sends render as queued from the start, above Working,
            // and do not trigger the sent-message scroll choreography.
            it.items = listOf(OpenCodeTranscriptItem.userText(id = "m1", text = "first"))
            it.queuedInputs = listOf(OpenCodePendingInput(id = localID, text = "second"))
            it.lastSentItemID = null
            it.sendGeneration = 0
        }
        // A poll that predates the server's admission must not blink the row out.
        store.send(Action.pendingInputsResponse(sessionID = "ses_1", inputs = emptyList()))
        expect(store.state.queuedInputs.map { it.text } == listOf("second"))
        store.timeout = 2.seconds
        store.receive<Action.promptAdmitted> { state ->
            state.promptStates[localID]?.let { prompt ->
                state.promptStates += (localID to prompt.copy(admission = SessionFeature.State.PromptState.Admission.admitted))
            }
        }
        expect(!store.state.isSubmittingPrompt)
        // A read that started before admission can complete afterwards. Admission
        // alone does not retire the client-ID row.
        store.send(Action.pendingInputsResponse(sessionID = "ses_1", inputs = emptyList()))
        expect(store.state.queuedInputs.map { it.id } == listOf(localID))
        // The server's echo retires the optimistic row.
        store.send(Action.pendingInputsResponse(sessionID = "ses_1", inputs = listOf(OpenCodePendingInput(id = localID, text = "second")))) {
            it.queuedInputs = listOf(OpenCodePendingInput(id = localID, text = "second"))
        }
        store.finish(timeout = 2.seconds)
    }

    @Test
    fun queuedSendWithImagesKeepsImagesThroughEchoAndPromotion() {
        val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
        val session = OpenCodeSession(id = "ses_1", title = "Chat", projectID = "proj_1", directory = "/tmp/demo", updatedAt = System.currentTimeMillis())
        val data = byteArrayOf(1, 2, 3)
        val state = SessionFeature.State(server = server, session = session, cachedItems = emptyList(), isRunning = true)
        state.draft = "Look"
        state.pendingAttachments = listOf(OpenCodeOutgoingAttachment(data = data, mime = "image/png"))
        val messageID = "msg_00000000000000000000000000000000"
        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.uuid = UUIDGenerator.incrementing()
                it.openCodeClient.sendPrompt = { _, _, _, _, _, _, _ ->
                    try {
                        delay(10.seconds)
                    } catch (_: CancellationException) {
                    }
                }
            },
        ) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.sendTapped)
        expect(store.state.queuedInputs.map { it.id } == listOf(messageID))
        expect(store.state.queuedInputRows.firstOrNull()?.images?.map { it.data }?.singleOrNull()?.contentEquals(data) == true)

        store.send(Action.pendingInputsResponse(
            sessionID = session.id,
            inputs = listOf(OpenCodePendingInput(id = messageID, text = "Look")),
        ))
        expect(store.state.queuedInputRows.firstOrNull()?.images?.map { it.data }?.singleOrNull()?.contentEquals(data) == true)

        val promoted = listOf(
            OpenCodeTranscriptItem.userImages(id = "$messageID-images", images = listOf(OpenCodeImageAttachment(id = "server-image", data = data))),
            OpenCodeTranscriptItem.userText(id = messageID, text = "Look"),
        )
        store.send(Action.transcriptResponse(sessionID = session.id, result = Result.success(promoted)))
        expect(store.state.items == promoted)
        expect(store.state.queuedInputs.isEmpty())
        expect(store.state.queuedInputImages.isEmpty())

        // A pre-promotion inbox response landing late cannot resurrect the row.
        store.send(Action.pendingInputsResponse(
            sessionID = session.id,
            inputs = listOf(OpenCodePendingInput(id = messageID, text = "Look")),
        ))
        expect(store.state.queuedInputs.isEmpty())

        store.send(Action.promptAdmitted(sessionID = session.id, messageID = messageID, requestEpoch = 1))
        store.send(Action.pendingInputsResponse(sessionID = session.id, inputs = emptyList()))
        expect(store.state.queuedInputs.isEmpty())
        expect(store.state.queuedInputImages.isEmpty())
        store.skipInFlightEffects()
    }

    @Test
    fun sendingMessageDeliversPromptAndLoadsReply() {
        val replied = listOf(
            OpenCodeTranscriptItem.userText(id = "msg_00000000000000000000000000000000", text = "hello"),
            OpenCodeTranscriptItem.assistantMarkdown(id = "m2-0", blocks = OpenCodeMarkdownBlock.blocks(from = "hi!")),
        )

        val store = TestStore(
            initialState = { makeState(draft = "hello") },
            withDependencies = {
                it.uuid = UUIDGenerator.incrementing()
                it.openCodeClient.sendPrompt = { _, sessionID, text, _, _, messageID, _ ->
                    expect(sessionID == "ses_1")
                    expect(text == "hello")
                    expect(messageID == "msg_00000000000000000000000000000000")
                }
                it.openCodeClient.waitForIdle = { _, _ -> }
                it.openCodeClient.transcript = { _, _, _ -> replied }
                it.openCodeClient.familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList()) }
            },
        ) { SessionFeature() }

        store.send(Action.sendTapped) { expectOptimisticSend(it) }
        expect(store.state.isSubmittingPrompt)
        store.receive(Action.promptAdmitted(sessionID = "ses_1", messageID = "msg_00000000000000000000000000000000", requestEpoch = 1)) { state ->
            state.promptStates["msg_00000000000000000000000000000000"]?.let { prompt ->
                state.promptStates += ("msg_00000000000000000000000000000000" to prompt.copy(admission = SessionFeature.State.PromptState.Admission.admitted))
            }
        }
        expect(!store.state.isSubmittingPrompt)
        store.receive(Action.replyCompleted(sessionID = "ses_1"))
        store.receive(Action.externalRunStateChanged(isRunning = false)) {
            it.isAwaitingReply = false
        }
        store.receive(Action.refreshRequested(SessionFeature.RefreshReason.runState)) {
            it.nextRefreshID = 1
            it.refresh = SessionFeature.State.Refresh(id = 1, reason = SessionFeature.RefreshReason.runState)
        }
        store.receive<Action.refreshResponse> {
            it.refresh = null
            it.initialTranscriptIsFresh = true
            it.optimisticPromptIDs = emptySet()
            it.promptStates = emptyMap()
            it.promotedPromptIDs = listOf("msg_00000000000000000000000000000000")
            it.items = replied
        }
        store.receive(Action.acceptedTranscriptUpdated(sessionID = "ses_1", items = replied))
    }

    @Test
    fun queueIsOneShotAndTheNextSendSteers() {
        val state = makeState(session = testSession(id = "ses_root", title = "Root"))
        state.isExternallyActive = true
        state.draft = "Do this afterwards"
        val deliveries = mutableListOf<OpenCodeDelivery>()
        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.uuid = UUIDGenerator.incrementing()
                it.openCodeClient.sendPrompt = { _, _, _, _, _, _, delivery ->
                    deliveries += delivery
                }
                it.openCodeClient.transcript = { _, _, _ -> emptyList() }
                it.openCodeClient.familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList()) }
                it.openCodeClient.pendingInputs = { _, _ -> emptyList() }
            },
        ) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        store.send(Action.queueTapped)
        expect(store.state.queuedInputs.lastOrNull()?.delivery == OpenCodeDelivery.queue)
        store.receive<Action.promptAdmitted>()
        store.send(Action.binding(BindingAction.set(SessionFeature.State::draft, "Actually, use this approach\n")))
        store.send(Action.returnKeyTapped)
        store.receive<Action.sendTapped>()
        expect(store.state.queuedInputs.lastOrNull()?.delivery == OpenCodeDelivery.steer)
        store.receive<Action.promptAdmitted>()
        expect(deliveries == listOf(OpenCodeDelivery.queue, OpenCodeDelivery.steer))
        store.skipInFlightEffects()
    }

    @Test
    fun acceptedPromptWithLostResponseReconcilesSameClientID() {
        val session = testSession()
        val messageID = "msg_00000000000000000000000000000000"
        val echoed = listOf(OpenCodeTranscriptItem.userText(id = messageID, text = "hello"))
        val store = TestStore(
            initialState = { makeState(session = session, draft = "hello") },
            withDependencies = {
                it.uuid = UUIDGenerator.incrementing()
                it.openCodeClient.sendPrompt = { _, _, _, _, _, _, _ ->
                    throw OpenCodeClientError(message = "The response was lost.")
                }
                it.openCodeClient.message = { _, _, id ->
                    expect(id == messageID)
                    echoed
                }
                it.openCodeClient.pendingInputs = { _, _ -> emptyList() }
                it.openCodeClient.waitForIdle = { _, _ -> }
                it.openCodeClient.transcript = { _, _, _ -> echoed }
            },
        ) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.sendTapped)
        store.receive(Action.promptAdmitted(sessionID = session.id, messageID = messageID, requestEpoch = 1))
        store.receive(Action.replyCompleted(sessionID = session.id))
        store.receive<Action.refreshResponse>()
        expect(store.state.draft.isEmpty())
        expect(!store.state.items.any { it is OpenCodeTranscriptItem.sendFailure })
    }

    @Test
    fun firstSendInDraftCreatesSessionThenDelivers() {
        val project = OpenCodeProject(id = "proj_1", name = "demo", directory = "/tmp/demo")
        val created = testSession(id = "ses_new", title = "Untitled session", directory = "/tmp/demo", updatedAt = 2_000_000L)
        val replied = listOf(
            OpenCodeTranscriptItem.userText(id = "msg_00000000000000000000000000000000", text = "hello"),
            OpenCodeTranscriptItem.assistantMarkdown(id = "m2-0", blocks = OpenCodeMarkdownBlock.blocks(from = "hi!")),
        )

        val state = SessionFeature.State(server = testServer, draftProject = project, projects = listOf(project))
        state.draft = "hello"

        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.uuid = UUIDGenerator.incrementing()
                it.openCodeClient.createSession = { _, directory ->
                    expect(directory == "/tmp/demo")
                    created
                }
                it.openCodeClient.getSession = { _, _ -> created }
                it.openCodeClient.pendingInputs = { _, _ -> emptyList() }
                it.openCodeClient.sendPrompt = { _, sessionID, text, _, _, _, _ ->
                    expect(sessionID == "ses_new")
                    expect(text == "hello")
                }
                it.openCodeClient.waitForIdle = { _, _ -> }
                it.openCodeClient.transcript = { _, _, _ -> replied }
                it.openCodeClient.familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList()) }
            },
        ) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.sendTapped) {
            it.optimisticPromptIDs = setOf("msg_00000000000000000000000000000000")
            it.draft = ""
            it.isAwaitingReply = true
            it.items = listOf(OpenCodeTranscriptItem.userText(id = "msg_00000000000000000000000000000000", text = "hello"))
            it.lastSentItemID = "msg_00000000000000000000000000000000"
            it.sendGeneration = 1
        }
        store.receive<Action.sessionCreated> {
            it.session = created
        }
        store.receive(Action.replyCompleted(sessionID = "ses_new"))
        store.receive(Action.externalRunStateChanged(isRunning = false)) {
            it.isAwaitingReply = false
        }
        store.receive<Action.refreshResponse> {
            it.items = replied
        }
        store.skipInFlightEffects()
    }

    @Test
    fun pickingModelSwitchesSessionModel() {
        val session = testSession(
            directory = "/tmp/demo",
            model = OpenCodeModelRef(id = "ling-3.0-tiny-free", providerID = "opencode"),
        )
        val fast = OpenCodeModel(modelID = "fast-frank", providerID = "opencode", name = "Fast Frank")

        val store = TestStore(
            initialState = { makeState(session = session) },
            withDependencies = {
                it.uuid = UUIDGenerator.incrementing()
                it.openCodeClient.modelCatalog = { _, directory ->
                    expect(directory == "/tmp/demo")
                    OpenCodeModelCatalog(models = listOf(fast), defaultModel = null)
                }
                it.openCodeClient.switchModel = { _, sessionID, ref ->
                    expect(sessionID == "ses_1")
                    expect(ref == fast.ref)
                }
            },
        ) { SessionFeature() }

        // The persisted model remains identifiable while its friendly name loads.
        expect(store.state.currentModelName == "ling-3.0-tiny-free")

        store.send(Action.modelButtonTapped) {
            it.activePicker = SessionFeature.State.ActivePicker.model
            it.isLoadingModels = true
        }
        store.receive({ it is Action.modelsResponse && it.result.isSuccess }) {
            it.isLoadingModels = false
            it.models = listOf(fast)
        }
        store.send(Action.modelPicked(fast)) {
            it.currentModel = fast.ref
            it.activePicker = null
        }
        expect(store.state.currentModelName == "Fast Frank")
    }

    @Test
    fun loadingCatalogShowsDefaultModelForImplicitSessionSelection() {
        val defaultModel = OpenCodeModel(modelID = "example-model", providerID = "example", name = "Example Model")
        val store = TestStore(
            initialState = { makeState(session = testSession(directory = "/tmp/demo")) },
            withDependencies = {
                it.openCodeClient.modelCatalog = { _, directory ->
                    expect(directory == "/tmp/demo")
                    OpenCodeModelCatalog(models = listOf(defaultModel), defaultModel = defaultModel)
                }
            },
        ) { SessionFeature() }

        expect(store.state.currentModelName == "Model")
        store.send(Action.modelButtonTapped) {
            it.activePicker = SessionFeature.State.ActivePicker.model
            it.isLoadingModels = true
        }
        store.receive({ it is Action.modelsResponse && it.result.isSuccess }) {
            it.isLoadingModels = false
            it.models = listOf(defaultModel)
            it.currentModel = defaultModel.ref
        }
        expect(store.state.currentModelName == "Example Model")
    }

    @Test
    fun togglingFavoritePinsModel() {
        val fast = OpenCodeModel(modelID = "example-model-fast", providerID = "opencode", name = "Example Model Fast")
        val tiny = OpenCodeModel(modelID = "example-model-tiny", providerID = "opencode", name = "Example Model Tiny")

        val state = makeState()
        state.models = listOf(tiny, fast)

        val store = TestStore(initialState = { state }) { SessionFeature() }

        store.send(Action.toggleFavoriteModel(fast)) {
            it::favoriteModelIDs.withLock { value = setOf(fast.id) }
        }
        expect(store.state.favoriteModels == listOf(fast))
        expect(store.state.unfavoriteModels == listOf(tiny))

        store.send(Action.toggleFavoriteModel(fast)) {
            it::favoriteModelIDs.withLock { value = emptySet() }
        }
    }

    @Test
    fun failedSendRestoresFeedback() {
        val session = testSession()

        val failSend: suspend (OpenCodeServer, String, String, List<OpenCodeOutgoingAttachment>, List<OpenCodeFileReference>, String, OpenCodeDelivery) -> Unit =
            { _, _, _, _, _, _, _ -> throw OpenCodeClientError(message = "Offline") }

        val store = TestStore(
            initialState = { makeState(session = session, draft = "hello") },
            withDependencies = {
                it.uuid = UUIDGenerator.incrementing()
                it.continuousClock = ImmediateClock()
                it.openCodeClient.sendPrompt = failSend
                it.openCodeClient.message = { _, _, _ -> throw OpenCodeClientError(message = "Missing", statusCode = 404) }
                it.openCodeClient.pendingInputs = { _, _ -> throw OpenCodeClientError(message = "Offline") }
            },
        ) { SessionFeature() }

        store.send(Action.sendTapped) { expectOptimisticSend(it) }
        store.receive<Action.promptFailed> {
            it.optimisticPromptIDs = emptySet()
            it.promptStates = emptyMap()
            it.isAwaitingReply = false
            it.draft = "hello"
            it.items = listOf(OpenCodeTranscriptItem.sendFailure(id = "info-send-msg_00000000000000000000000000000000", message = "Offline"))
            it.lastSentItemID = null
            it.unconfirmedSendFailures += (
                "msg_00000000000000000000000000000000" to SessionFeature.UnconfirmedSendFailure(
                    infoID = "info-send-msg_00000000000000000000000000000000",
                    text = "hello",
                    attachmentIDs = emptySet(),
                    fileIDs = emptySet(),
                )
            )
        }

        // The connection dropped after the server took the message: when it shows
        // up, the failure notice and the draft restored for a retry go away.
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        store.send(Action.transcriptResponse(sessionID = session.id, result = Result.success(listOf(
            OpenCodeTranscriptItem.userText(id = "msg_00000000000000000000000000000000", text = "hello"),
        ))))
        expect(store.state.draft.isEmpty())
        expect(store.state.unconfirmedSendFailures.isEmpty())
        expect(!store.state.items.any { it is OpenCodeTranscriptItem.info })
        expect(store.state.items.map { it.id } == listOf("msg_00000000000000000000000000000000"))
    }

    @Test
    fun interruptingAnActiveSessionWaitsForServerState() {
        val session = testSession()
        val store = TestStore(
            initialState = { makeState(session = session, isRunning = true) },
            withDependencies = {
                it.openCodeClient.interrupt = { _, sessionID ->
                    expect(sessionID == "ses_1")
                }
                it.openCodeClient.getSession = { _, _ -> session }
                it.openCodeClient.transcript = { _, _, _ -> emptyList() }
            },
        ) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.interruptTapped) { it.isInterrupting = true }
        store.receive(Action.interruptFinished(sessionID = "ses_1", error = null))
        store.send(Action.interruptTapped)
        expect(store.state.isExternallyActive)
        expect(store.state.isInterrupting)
        store.send(Action.externalRunStateChanged(isRunning = false)) {
            it.isExternallyActive = false
            it.isInterrupting = false
        }
        store.receive(Action.refreshRequested(SessionFeature.RefreshReason.runState))
        store.receive<Action.refreshResponse>()
        store.skipReceivedActions()
    }

    @Test
    fun interruptFailureKeepsSessionActiveAndShowsFeedback() {
        val failInterrupt: suspend (OpenCodeServer, String) -> Unit = { _, _ -> throw OpenCodeClientError(message = "Offline") }
        val store = TestStore(
            initialState = { makeState(isRunning = true) },
            withDependencies = {
                it.uuid = UUIDGenerator.incrementing()
                it.openCodeClient.interrupt = failInterrupt
            },
        ) { SessionFeature() }

        store.send(Action.interruptTapped) { it.isInterrupting = true }
        store.receive(Action.interruptFinished(sessionID = "ses_1", error = OpenCodeClientError(message = "Offline"))) {
            it.isInterrupting = false
            it.items = listOf(OpenCodeTranscriptItem.info(id = "info-00000000-0000-0000-0000-000000000000", text = "Couldn’t interrupt: Offline"))
        }
        expect(store.state.isExternallyActive)
    }

    @Test
    fun subagentPermissionRepliesToOwner() {
        val permission = protocolJson.decodeFromString<OpenCodePermissionRequest>("""
            {"id":"per_1","sessionID":"ses_child","action":"shell","resources":["git status"],
             "source":{"type":"tool","messageID":"msg_1","id":"call_1"}}
        """.trimIndent())
        val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
        val root = OpenCodeSession(id = "ses_root", title = "Root", projectID = "proj_1", directory = "/tmp/demo", updatedAt = System.currentTimeMillis())
        val child = OpenCodeSession(id = "ses_child", title = "Child", projectID = "proj_1", directory = "/tmp/demo", updatedAt = System.currentTimeMillis(), parentID = root.id)
        val state = SessionFeature.State(server = server, session = root, cachedItems = emptyList())
        state.familySessions = listOf(root, child)
        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.openCodeClient.replyPermission = { _, sessionID, requestID, reply ->
                    expect(sessionID == "ses_child")
                    expect(requestID == "per_1")
                    expect(reply == OpenCodePermissionReply.once)
                }
                it.openCodeClient.familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) }
            },
        ) { SessionFeature() }

        store.send(Action.pendingRequestsResponse(sessionID = root.id, result = Result.success(OpenCodePendingRequests(
            permissions = listOf(permission),
            forms = emptyList(),
        )))) {
            it.permissionRequests = listOf(permission)
            it.selectedRequestID = permission.id
            it.activePicker = SessionFeature.State.ActivePicker.requests
        }
        store.send(Action.permissionReplyTapped(id = permission.id, reply = OpenCodePermissionReply.once)) {
            it.respondingRequestIDs = setOf(permission.id)
        }
        store.receive(Action.requestResponseFinished(sessionID = "ses_child", requestID = permission.id, error = null)) {
            it.respondingRequestIDs = emptySet()
            it.completedRequestIDs = setOf(permission.id)
            it.selectedRequestID = null
            it.activePicker = null
            it.permissionRequests = emptyList()
        }
        store.receive(Action.pendingRequestsResponse(sessionID = root.id, result = Result.success(OpenCodePendingRequests(
            permissions = emptyList(),
            forms = emptyList(),
        ))))
    }

    @Test
    fun formsInitializeDefaultsAndSubmitActiveAnswers() {
        val form = protocolJson.decodeFromString<OpenCodeFormRequest>("""
            {"id":"frm_1","sessionID":"ses_1","title":"Deploy","fields":[
              {"key":"confirm","type":"boolean","title":"Confirm","required":true,"default":true},
              {"key":"note","type":"string","title":"Note","required":true,"when":[{"key":"confirm","op":"eq","value":true}]}
            ]}
        """.trimIndent())
        val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
        val session = OpenCodeSession(id = "ses_1", title = "Chat", projectID = "proj_1", directory = "/tmp/demo", updatedAt = System.currentTimeMillis())
        val store = TestStore(
            initialState = { SessionFeature.State(server = server, session = session, cachedItems = emptyList()) },
            withDependencies = {
                it.openCodeClient.replyForm = { _, sessionID, formID, answer ->
                    expect(sessionID == "ses_1")
                    expect(formID == "frm_1")
                    expect(answer == mapOf("confirm" to OpenCodeFormValue.boolean(true), "note" to OpenCodeFormValue.string("Ship it")))
                }
                it.openCodeClient.familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) }
            },
        ) { SessionFeature() }

        store.send(Action.pendingRequestsResponse(sessionID = "ses_1", result = Result.success(OpenCodePendingRequests(permissions = emptyList(), forms = listOf(form))))) {
            it.formRequests = listOf(form)
            it.formAnswers += (form.id to mapOf("confirm" to OpenCodeFormValue.boolean(true)))
            it.selectedRequestID = form.id
            it.activePicker = SessionFeature.State.ActivePicker.requests
        }
        store.send(Action.formSubmitTapped(form.id)) {
            it.requestErrors += (form.id to "Required")
            it.questionPageIndices += (form.id to 1)
        }
        store.send(Action.formValueChanged(formID = form.id, key = "note", value = OpenCodeFormValue.string("Ship it"))) { state ->
            state.formAnswers[form.id]?.let { answers ->
                state.formAnswers += (form.id to (answers + ("note" to OpenCodeFormValue.string("Ship it"))))
            }
            state.requestErrors -= form.id
        }
        store.send(Action.formSubmitTapped(form.id)) {
            it.respondingRequestIDs = setOf(form.id)
        }
        store.receive(Action.requestResponseFinished(sessionID = "ses_1", requestID = "frm_1", error = null)) {
            it.respondingRequestIDs = emptySet()
            it.formRequests = emptyList()
            it.formAnswers = emptyMap()
            it.completedRequestIDs = setOf(form.id)
            it.selectedRequestID = null
            it.activePicker = null
            it.questionPageIndices = emptyMap()
        }
        store.receive(Action.pendingRequestsResponse(sessionID = "ses_1", result = Result.success(OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()))))
    }

    @Test
    fun childOwnedRequestResponseIsAppliedBeforeFamilyDiscoveryFinishes() {
        val form = protocolJson.decodeFromString<OpenCodeFormRequest>("""
            {"id":"frm_child","sessionID":"child","title":"Question","fields":[
              {"key":"answer","type":"string","required":true}
            ]}
        """.trimIndent())
        val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
        val root = OpenCodeSession(id = "root", title = "Root", projectID = "p", directory = "/project", updatedAt = System.currentTimeMillis())
        val state = SessionFeature.State(server = server, session = root, cachedItems = emptyList())
        state.formRequests = listOf(form)
        state.formAnswers += (form.id to mapOf("answer" to OpenCodeFormValue.string("Ready")))
        state.selectedRequestID = form.id
        state.activePicker = SessionFeature.State.ActivePicker.requests
        val reply = CompletableDeferred<Unit>()
        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.openCodeClient.replyForm = { _, _, _, _ ->
                    reply.await()
                    throw OpenCodeClientError(message = "HTTP 503")
                }
                it.openCodeClient.familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) }
            },
        ) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.formSubmitTapped(form.id)) {
            it.respondingRequestIDs = setOf(form.id)
        }
        store.send(Action.pendingRequestsResponse(sessionID = root.id, result = Result.success(OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()))))
        expect(store.state.formRequests == listOf(form))
        expect(store.state.formAnswers[form.id] == mapOf("answer" to OpenCodeFormValue.string("Ready")))
        reply.complete(Unit)
        store.receive(Action.requestResponseFinished(sessionID = "child", requestID = form.id, error = OpenCodeClientError(message = "HTTP 503"))) {
            it.respondingRequestIDs = emptySet()
            it.requestErrors += (form.id to "HTTP 503")
        }
        store.send(Action.pendingRequestsResponse(sessionID = root.id, result = Result.success(OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()))))
        expect(store.state.formRequests == listOf(form))
        expect(store.state.formAnswers[form.id] == mapOf("answer" to OpenCodeFormValue.string("Ready")))
        expect(store.state.activePicker == SessionFeature.State.ActivePicker.requests)
    }

    @Test
    fun sessionFamilyTracksSubagentsAndSwitchesCachedTranscripts() {
        val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
        val root = OpenCodeSession(id = "ses_root", title = "Root", projectID = "proj_1", directory = "/tmp/demo", updatedAt = System.currentTimeMillis())
        val child = OpenCodeSession(
            id = "ses_child",
            title = "Inspect API",
            projectID = "proj_1",
            directory = "/tmp/demo",
            updatedAt = System.currentTimeMillis(),
            parentID = root.id,
            agent = "explore",
        )
        val state = SessionFeature.State(server = server, session = root, cachedItems = listOf(OpenCodeTranscriptItem.userText(id = "root", text = "Root")))
        state.familyTranscriptCache += (child.id to listOf(OpenCodeTranscriptItem.userText(id = "child", text = "Child")))
        state.draft = "Unsent root work"
        state.queuedInputs = listOf(OpenCodePendingInput(id = "msg_queued", text = "Look"))
        state.queuedInputImages += ("msg_queued" to listOf(OpenCodeImageAttachment(id = "img", data = byteArrayOf(1))))
        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.openCodeClient.transcript = { _, _, _ -> emptyList() }
                it.openCodeClient.familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) }
                // Inbox reads never land, so only the switch itself can clear queued rows.
                it.openCodeClient.pendingInputs = { _, _ -> throw OpenCodeClientError(message = "offline") }
            },
        ) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.familyResponse(Result.success(OpenCodeSessionFamily(sessions = listOf(root, child), activeSessionIDs = setOf(child.id))))) {
            it.familySessions = listOf(root, child)
            it.activeFamilySessionIDs = setOf(child.id)
        }
        store.send(Action.familyResponse(Result.success(OpenCodeSessionFamily(sessions = listOf(root), activeSessionIDs = setOf(child.id)))))
        expect(store.state.familySessions == listOf(root, child))
        store.send(Action.familyPicked(child.id))
        expect(store.state.session == child)
        expect(store.state.items == listOf(OpenCodeTranscriptItem.userText(id = "child", text = "Child")))
        expect(store.state.isExternallyActive)
        expect(store.state.draft.isEmpty())
        expect(store.state.queuedInputs.isEmpty())
        expect(store.state.queuedInputImages.isEmpty())
        store.send(Action.transcriptResponse(sessionID = root.id, result = Result.success(listOf(OpenCodeTranscriptItem.userText(id = "late", text = "Late root")))))
        expect(store.state.items == listOf(OpenCodeTranscriptItem.userText(id = "child", text = "Child")))
        store.send(Action.promptFailed(
            sessionID = root.id,
            error = OpenCodeClientError(message = "Late failure"),
            pending = SessionFeature.PendingPrompt(messageID = "msg_root", text = "Root prompt", attachments = emptyList(), optimisticIDs = emptySet(), requestEpoch = 1),
        ))
        expect(store.state.draft.isEmpty())
        store.send(Action.familyPicked(root.id))
        expect(store.state.items == listOf(OpenCodeTranscriptItem.userText(id = "root", text = "Root")))
        expect(store.state.draft == "Unsent root work")
        store.skipInFlightEffects()
    }

    @Test
    fun familySwitchNeverEvictsTheIncomingTranscript() {
        val root = testSession(id = "ses_root", title = "Root")
        val children = (0 until 8).map { testSession(id = "ses_child$it", title = "Child $it", parentID = root.id) }
        // Caching the outgoing root overfills the cache, so every switch evicts
        // one child. Whichever child that is, it must not be the one opened.
        for (target in children) {
            val state = makeState(session = root, cachedItems = listOf(OpenCodeTranscriptItem.userText(id = "root", text = "Root")))
            state.familySessions = listOf(root) + children
            for (child in children) {
                state.familyTranscriptCache += (child.id to listOf(OpenCodeTranscriptItem.userText(id = child.id, text = child.title)))
            }
            val store = TestStore(
                initialState = { state },
                withDependencies = {
                    it.openCodeClient.transcript = { _, _, _ -> emptyList() }
                    it.openCodeClient.familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) }
                    it.openCodeClient.pendingInputs = { _, _ -> emptyList() }
                },
            ) { SessionFeature() }
            store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

            store.send(Action.familyPicked(target.id))
            expect(store.state.items == listOf(OpenCodeTranscriptItem.userText(id = target.id, text = target.title)))
            store.skipInFlightEffects()
        }
    }

    @Test
    fun draftWithoutProjectOpensPickerWithoutLosingText() {
        val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
        val state = SessionFeature.State(server = server, draftProject = null, projects = emptyList())
        state.draft = "Keep this"
        val store = TestStore(initialState = { state }) { SessionFeature() }

        store.send(Action.sendTapped) {
            it.activePicker = SessionFeature.State.ActivePicker.project
        }
        expect(store.state.draft == "Keep this")
        expect(store.state.items.isEmpty())
    }

    @Test
    fun lateInboxCannotDuplicateAnAuthoritativeUserBubble() {
        val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
        val session = OpenCodeSession(
            id = "ses_1",
            title = "Chat",
            projectID = "proj_1",
            directory = "/tmp/demo",
            updatedAt = 1_000_000L,
        )
        val state = SessionFeature.State(server = server, session = session, cachedItems = emptyList(), isRunning = true)
        state.items = listOf(OpenCodeTranscriptItem.userText(id = "msg_9", text = "steer me"))

        val store = TestStore(initialState = { state }) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off

        // The transcript already contains the authoritative prompt. A late inbox
        // snapshot cannot turn it back into queued work below newer replies.
        store.send(Action.pendingInputsResponse(sessionID = "ses_1", inputs = listOf(OpenCodePendingInput(id = "msg_9", text = "steer me"))))
        expect(store.state.visibleItems == listOf(OpenCodeTranscriptItem.userText(id = "msg_9", text = "steer me")))
        expect(store.state.queuedInputs.isEmpty())

        // A later empty poll leaves the same single delivered bubble.
        store.send(Action.pendingInputsResponse(sessionID = "ses_1", inputs = emptyList()))
        expect(store.state.visibleItems == listOf(OpenCodeTranscriptItem.userText(id = "msg_9", text = "steer me")))
        expect(store.state.queuedInputs.isEmpty())
    }

    @Test
    fun steeringShowsQueuedStateAsSoonAsServerAdmitsIt() {
        val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
        val session = OpenCodeSession(id = "ses_1", title = "Chat", projectID = "proj_1", directory = "/tmp/demo", updatedAt = System.currentTimeMillis())
        val state = SessionFeature.State(server = server, session = session, cachedItems = emptyList(), isRunning = true)
        state.draft = "Steer"
        val messageID = "msg_00000000000000000000000000000000"
        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.uuid = UUIDGenerator.incrementing()
                it.openCodeClient.sendPrompt = { _, _, _, _, _, _, _ -> }
                it.openCodeClient.waitForIdle = { _, _ -> }
                it.openCodeClient.transcript = { _, _, _ -> emptyList() }
                it.openCodeClient.pendingInputs = { _, _ -> listOf(OpenCodePendingInput(id = messageID, text = "Steer")) }
            },
        ) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.sendTapped)
        store.receive(Action.pendingInputsResponse(sessionID = "ses_1", inputs = listOf(OpenCodePendingInput(id = messageID, text = "Steer")))) {
            it.queuedInputs = listOf(OpenCodePendingInput(id = messageID, text = "Steer"))
        }
        expect(store.state.visibleItems.isEmpty())
    }

    @Test
    fun returnKeySubmitsInsteadOfInsertingNewline() {
        val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
        val session = OpenCodeSession(id = "ses_1", title = "Chat", projectID = "proj_1", directory = "/tmp/demo", updatedAt = System.currentTimeMillis())
        val state = SessionFeature.State(server = server, session = session, cachedItems = emptyList())
        state.draft = "Ship it\n"
        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.uuid = UUIDGenerator.incrementing()
                it.openCodeClient.sendPrompt = { _, _, _, _, _, _, _ -> }
                it.openCodeClient.waitForIdle = { _, _ -> }
                it.openCodeClient.transcript = { _, _, _ -> emptyList() }
            },
        ) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.returnKeyTapped) {
            it.draft = "Ship it"
        }
        store.receive(Action.sendTapped) {
            it.optimisticPromptIDs = setOf("msg_00000000000000000000000000000000")
            it.draft = ""
            it.items = listOf(OpenCodeTranscriptItem.userText(id = "msg_00000000000000000000000000000000", text = "Ship it"))
        }
    }

    @Test
    fun dictationStreamsIntoDraftAfterTypedText() {
        val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
        val session = OpenCodeSession(id = "ses_1", title = "Chat", projectID = "proj_1", directory = "/tmp/demo", updatedAt = System.currentTimeMillis())
        val state = SessionFeature.State(server = server, session = session, cachedItems = emptyList())
        state.draft = "Fix"
        val feed = Channel<String>(Channel.UNLIMITED)
        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.dictation.start = { feed.consumeAsFlow() }
                // Stopping finalizes: the engine delivers its last transcript, then
                // the stream ends — mirroring finalizeAndFinishThroughEndOfInput.
                it.dictation.stop = {
                    feed.trySend("the spinner ring!")
                    feed.close()
                }
            },
        ) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.micTapped) {
            it.dictationPhase = SessionFeature.State.DictationPhase.preparing
        }
        store.receive(Action.dictationReady) {
            it.dictationPhase = SessionFeature.State.DictationPhase.recording
        }
        feed.trySend("the spinner")
        store.receive(Action.dictationUpdated("Fix the spinner")) {
            it.draft = "Fix the spinner"
        }
        feed.trySend("the spinner ring")
        store.receive(Action.dictationUpdated("Fix the spinner ring")) {
            it.draft = "Fix the spinner ring"
        }
        // An intentional stop keeps the stream alive: the finalized transcript
        // (often the only accurate one) still lands in the draft.
        store.send(Action.micTapped) {
            it.dictationPhase = SessionFeature.State.DictationPhase.finishing(send = null)
        }
        store.receive(Action.dictationUpdated("Fix the spinner ring!")) {
            it.draft = "Fix the spinner ring!"
        }
        store.receive(Action.dictationStopped(errorMessage = null)) {
            it.dictationPhase = SessionFeature.State.DictationPhase.idle
        }
        // Late results after teardown must not clobber the draft.
        store.send(Action.dictationUpdated("Fix ghost"))
        expect(store.state.draft == "Fix the spinner ring!")
    }

    @Test
    fun micRetapWhileFinishingIsIgnored() {
        val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
        val session = OpenCodeSession(id = "ses_1", title = "Chat", projectID = "proj_1", directory = "/tmp/demo", updatedAt = System.currentTimeMillis())
        val feed = Channel<String>(Channel.UNLIMITED)
        val store = TestStore(
            initialState = { SessionFeature.State(server = server, session = session, cachedItems = emptyList()) },
            withDependencies = {
                it.dictation.start = { feed.consumeAsFlow() }
                // Engine finalizes slowly; the final transcript arrives later.
                it.dictation.stop = {}
            },
        ) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.micTapped) {
            it.dictationPhase = SessionFeature.State.DictationPhase.preparing
        }
        store.receive(Action.dictationReady) {
            it.dictationPhase = SessionFeature.State.DictationPhase.recording
        }
        store.send(Action.micTapped) {
            it.dictationPhase = SessionFeature.State.DictationPhase.finishing(send = null)
        }
        // A double-tap on stop must not restart dictation mid-teardown.
        store.send(Action.micTapped)
        expect(store.state.isDictating == false)

        feed.trySend("final words")
        store.receive(Action.dictationUpdated("final words")) {
            it.draft = "final words"
        }
        feed.close()
        store.receive(Action.dictationStopped(errorMessage = null)) {
            it.dictationPhase = SessionFeature.State.DictationPhase.idle
        }
    }

    @Test
    fun sendingWhileDictatingStopsTheMic() {
        val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
        val session = OpenCodeSession(id = "ses_1", title = "Chat", projectID = "proj_1", directory = "/tmp/demo", updatedAt = System.currentTimeMillis())
        val state = SessionFeature.State(server = server, session = session, cachedItems = emptyList())
        state.draft = "Deploy"
        val feed = Channel<String>(Channel.UNLIMITED)
        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.uuid = UUIDGenerator.incrementing()
                it.dictation.start = { feed.consumeAsFlow() }
                it.dictation.stop = { feed.close() }
                it.openCodeClient.sendPrompt = { _, _, _, _, _, _, _ -> }
                it.openCodeClient.waitForIdle = { _, _ -> }
                it.openCodeClient.transcript = { _, _, _ -> emptyList() }
            },
        ) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.micTapped) {
            it.dictationPhase = SessionFeature.State.DictationPhase.preparing
        }
        store.send(Action.sendTapped) {
            it.optimisticPromptIDs = setOf("msg_00000000000000000000000000000000")
            it.dictationPhase = SessionFeature.State.DictationPhase.idle
            it.draft = ""
        }
        expect(store.state.items == listOf(OpenCodeTranscriptItem.userText(id = "msg_00000000000000000000000000000000", text = "Deploy")))
    }

    @Test
    fun dictationSendWaitsForFinalTranscript() {
        val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
        val session = OpenCodeSession(id = "ses_1", title = "Chat", projectID = "proj_1", directory = "/tmp/demo", updatedAt = System.currentTimeMillis())
        val feed = Channel<String>(Channel.UNLIMITED)
        val store = TestStore(
            initialState = { SessionFeature.State(server = server, session = session, cachedItems = emptyList()) },
            withDependencies = {
                it.uuid = UUIDGenerator.incrementing()
                it.dictation.start = { feed.consumeAsFlow() }
                it.dictation.stop = {
                    feed.trySend("Send all final words")
                    feed.close()
                }
                it.openCodeClient.sendPrompt = { _, _, text, _, _, _, _ ->
                    expect(text == "Send all final words")
                }
                it.openCodeClient.waitForIdle = { _, _ -> }
                it.openCodeClient.transcript = { _, _, _ -> emptyList() }
            },
        ) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.micTapped) { it.dictationPhase = SessionFeature.State.DictationPhase.preparing }
        store.send(Action.dictationSendTapped) {
            it.dictationPhase = SessionFeature.State.DictationPhase.finishing(send = OpenCodeDelivery.steer)
        }
        store.receive(Action.dictationUpdated("Send all final words")) {
            it.draft = "Send all final words"
        }
        store.receive(Action.dictationStopped(errorMessage = null)) {
            it.dictationPhase = SessionFeature.State.DictationPhase.idle
        }
        store.receive(Action.sendTapped) {
            it.optimisticPromptIDs = setOf("msg_00000000000000000000000000000000")
            it.draft = ""
            it.items = listOf(OpenCodeTranscriptItem.userText(id = "msg_00000000000000000000000000000000", text = "Send all final words"))
        }
    }

    @Test
    fun heldMicKeepsWordsOffTheDraftAndSendsOnRelease() {
        val feed = Channel<String>(Channel.UNLIMITED)
        val store = TestStore(
            initialState = { makeState() },
            withDependencies = {
                it.uuid = UUIDGenerator.incrementing()
                it.dictation.start = { feed.consumeAsFlow() }
                it.dictation.stop = {
                    feed.trySend("Rename the tab bar")
                    feed.close()
                }
                it.openCodeClient.sendPrompt = { _, _, text, _, _, _, _ ->
                    expect(text == "Rename the tab bar")
                }
                it.openCodeClient.waitForIdle = { _, _ -> }
                it.openCodeClient.transcript = { _, _, _ -> emptyList() }
            },
        ) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.micPressBegan) {
            it.dictationPhase = SessionFeature.State.DictationPhase.preparing
            it.dictationHold = SessionFeature.State.DictationHold(text = "")
        }
        store.receive(Action.dictationReady) { it.dictationPhase = SessionFeature.State.DictationPhase.recording }
        feed.trySend("Rename the")
        // Recording: words wait off the draft, as Telegram shows only the timer.
        store.receive(Action.dictationUpdated("Rename the")) { state ->
            state.dictationHold?.let { hold -> state.dictationHold = hold.copy(text = "Rename the") }
        }
        expect(store.state.draft == "")
        store.send(Action.micPressReleased) {
            it.dictationPhase = SessionFeature.State.DictationPhase.finishing(send = OpenCodeDelivery.steer)
        }
        store.receive(Action.dictationUpdated("Rename the tab bar")) { state ->
            state.dictationHold?.let { hold -> state.dictationHold = hold.copy(text = "Rename the tab bar") }
        }
        store.receive(Action.dictationStopped(errorMessage = null)) {
            it.dictationPhase = SessionFeature.State.DictationPhase.idle
            it.dictationHold = null
            it.draft = "Rename the tab bar"
        }
        store.receive(Action.sendTapped) {
            it.optimisticPromptIDs = setOf("msg_00000000000000000000000000000000")
            it.draft = ""
            it.items = listOf(OpenCodeTranscriptItem.userText(id = "msg_00000000000000000000000000000000", text = "Rename the tab bar"))
        }
    }

    /// The microphone permission prompt takes the touch, so the finger lifts
    /// while setup is still waiting on Allow. Recording must carry on locked.
    @Test
    fun releasingWhilePreparingLocksTheRecordingInsteadOfStopping() {
        val feed = Channel<String>(Channel.UNLIMITED)
        val allow = CompletableDeferred<Unit>()
        var stops = 0
        val store = TestStore(
            initialState = { makeState() },
            withDependencies = {
                it.dictation.start = {
                    allow.await()
                    feed.consumeAsFlow()
                }
                it.dictation.stop = { stops += 1 }
                it.openCodeClient.sendPrompt = { _, _, _, _, _, _, _ ->
                    expect(false, "Letting go before the mic was ready must not send")
                }
            },
        ) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.micPressBegan) {
            it.dictationPhase = SessionFeature.State.DictationPhase.preparing
            it.dictationHold = SessionFeature.State.DictationHold(text = "")
        }
        store.send(Action.micPressReleased) { state ->
            state.dictationHold?.let { hold -> state.dictationHold = hold.copy(isLocked = true) }
        }
        expect(store.state.dictationPhase == SessionFeature.State.DictationPhase.preparing)
        expect(stops == 0, "Setup must not be stopped")

        allow.complete(Unit)
        store.receive(Action.dictationReady) { it.dictationPhase = SessionFeature.State.DictationPhase.recording }
        expect(store.state.dictationHold?.isLocked == true)
        feed.trySend("Rename the tab bar")
        store.receive(Action.dictationUpdated("Rename the tab bar")) { state ->
            state.dictationHold?.let { hold -> state.dictationHold = hold.copy(text = "Rename the tab bar") }
        }
        expect(store.state.draft == "")
        expect(store.state.items.isEmpty())
        expect(stops == 0)
        feed.close()
        store.receive(Action.dictationStopped(errorMessage = null))
        expect(store.state.draft == "Rename the tab bar")
        expect(store.state.items.isEmpty())
    }

    /// Background audio keeps the mic on when the app leaves: leaving is Stop,
    /// whether recording from a tap or mid-hold, and nothing is sent.
    @Test
    fun leavingTheAppStopsDictationAndKeepsTheWordsInTheDraft() {
        for (holding in listOf(false, true)) {
            val feed = Channel<String>(Channel.UNLIMITED)
            val store = TestStore(
                initialState = { makeState(draft = "Check") },
                withDependencies = {
                    it.dictation.start = { feed.consumeAsFlow() }
                    it.dictation.stop = {
                        feed.trySend("the failing test suite")
                        feed.close()
                    }
                    it.openCodeClient.sendPrompt = { _, _, _, _, _, _, _ ->
                        expect(false, "Leaving the app must not send a dictation")
                    }
                },
            ) { SessionFeature() }
            store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

            store.send(if (holding) Action.micPressBegan else Action.micTapped)
            store.receive(Action.dictationReady) { it.dictationPhase = SessionFeature.State.DictationPhase.recording }
            feed.trySend("the failing")
            store.receive(Action.dictationUpdated("Check the failing"))
            store.send(Action.sceneEnteredBackground) {
                it.dictationPhase = SessionFeature.State.DictationPhase.finishing(send = null)
            }
            // A finger still down when the app left lifts later; it must not send.
            store.send(Action.micPressReleased)
            store.finish()
            expect(store.state.dictationPhase == SessionFeature.State.DictationPhase.idle)
            expect(store.state.dictationHold == null)
            expect(store.state.draft == "Check the failing test suite", "holding: $holding")
            expect(store.state.items.isEmpty(), "holding: $holding")
        }
    }

    @Test
    fun slidingLeftCancelsAHeldMicWithoutSending() {
        val feed = Channel<String>(Channel.UNLIMITED)
        val store = TestStore(
            initialState = { makeState(draft = "Typed") },
            withDependencies = {
                it.dictation.start = { feed.consumeAsFlow() }
                it.dictation.stop = { feed.close() }
                it.openCodeClient.sendPrompt = { _, _, _, _, _, _, _ ->
                    expect(false, "A cancelled recording must not send")
                }
            },
        ) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.micPressBegan) {
            it.dictationPhase = SessionFeature.State.DictationPhase.preparing
            it.dictationHold = SessionFeature.State.DictationHold(text = "Typed")
        }
        store.receive(Action.dictationReady) { it.dictationPhase = SessionFeature.State.DictationPhase.recording }
        feed.trySend("never mind")
        store.receive(Action.dictationUpdated("Typed never mind")) { state ->
            state.dictationHold?.let { hold -> state.dictationHold = hold.copy(text = "Typed never mind") }
        }
        store.send(Action.micPressCancelled) {
            it.dictationPhase = SessionFeature.State.DictationPhase.idle
            it.dictationHold = null
        }
        expect(store.state.draft == "Typed")
        store.finish()
        expect(store.state.items.isEmpty())
    }

    @Test
    fun lockedRecordingIgnoresReleaseAndInsertsIntoDraftOnStop() {
        val feed = Channel<String>(Channel.UNLIMITED)
        val store = TestStore(
            initialState = { makeState(draft = "Check") },
            withDependencies = {
                it.dictation.start = { feed.consumeAsFlow() }
                it.dictation.stop = {
                    feed.trySend("the failing test suite")
                    feed.close()
                }
                it.openCodeClient.sendPrompt = { _, _, _, _, _, _, _ ->
                    expect(false, "Stopping a locked recording must leave the transcription in the draft, not send it")
                }
            },
        ) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.micPressBegan) {
            it.dictationPhase = SessionFeature.State.DictationPhase.preparing
            it.dictationHold = SessionFeature.State.DictationHold(text = "Check")
        }
        store.receive(Action.dictationReady) { it.dictationPhase = SessionFeature.State.DictationPhase.recording }
        store.send(Action.micPressLocked) { state ->
            state.dictationHold?.let { hold -> state.dictationHold = hold.copy(isLocked = true) }
        }
        // The finger lifting after the tap/lock must not end or send the recording.
        store.send(Action.micPressReleased)
        expect(store.state.isDictating)
        feed.trySend("the failing test")
        store.receive(Action.dictationUpdated("Check the failing test")) { state ->
            state.dictationHold?.let { hold -> state.dictationHold = hold.copy(text = "Check the failing test") }
        }
        expect(store.state.draft == "Check")
        store.send(Action.dictationInsertTapped) {
            it.dictationPhase = SessionFeature.State.DictationPhase.finishing(send = null)
        }
        store.receive(Action.dictationUpdated("Check the failing test suite")) { state ->
            state.dictationHold?.let { hold -> state.dictationHold = hold.copy(text = "Check the failing test suite") }
        }
        store.receive(Action.dictationStopped(errorMessage = null)) {
            it.dictationPhase = SessionFeature.State.DictationPhase.idle
            it.dictationHold = null
            it.draft = "Check the failing test suite"
        }
        expect(store.state.items.isEmpty())
        store.finish()
    }

    private data class SentPrompt(
        val text: String,
        val attachments: Int,
        val delivery: OpenCodeDelivery,
    )

    private class LockedRecording(
        val store: TestStore<SessionFeature.State, Action>,
        val sent: MutableList<SentPrompt>,
        val feed: Channel<String>,
    )

    /// A locked recording (tapped, or slid up and released) whose engine says
    /// `words` so far and `final` when stopped. Every send is recorded.
    private fun lockedRecording(
        state: SessionFeature.State,
        words: String?,
        final: String?,
        finishesOnStop: Boolean = true,
    ): LockedRecording {
        val feed = Channel<String>(Channel.UNLIMITED)
        val sent = mutableListOf<SentPrompt>()
        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.uuid = UUIDGenerator.incrementing()
                it.dictation.start = { feed.consumeAsFlow() }
                it.dictation.stop = {
                    if (finishesOnStop) {
                        if (final != null) feed.trySend(final)
                        feed.close()
                    }
                }
                it.openCodeClient.sendPrompt = { _, _, text, attachments, _, _, delivery ->
                    sent += SentPrompt(text = text, attachments = attachments.size, delivery = delivery)
                }
                it.openCodeClient.waitForIdle = { _, _ -> }
                it.openCodeClient.transcript = { _, _, _ -> emptyList() }
            },
        ) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        store.send(Action.micPressBegan)
        store.receive(Action.dictationReady) { it.dictationPhase = SessionFeature.State.DictationPhase.recording }
        store.send(Action.micPressLocked) { state -> state.dictationHold?.let { hold -> state.dictationHold = hold.copy(isLocked = true) } }
        if (words != null) {
            feed.trySend(words)
            store.receive<Action.dictationUpdated>()
        }
        return LockedRecording(store, sent, feed)
    }

    @Test
    fun lockedBarSendSendsTheWordsWithTheDraftAndAttachments() {
        val state = makeState(draft = "Check")
        state.pendingAttachments = listOf(OpenCodeOutgoingAttachment(data = byteArrayOf(1, 2, 3), mime = "image/png"))
        val recording = lockedRecording(state, words = "the failing", final = "the failing test suite")
        val store = recording.store
        expect(store.state.draft == "Check", "Recording keeps its words off the draft")
        store.send(Action.dictationSendTapped) {
            it.dictationPhase = SessionFeature.State.DictationPhase.finishing(send = OpenCodeDelivery.steer)
        }
        store.receive<Action.dictationStopped>()
        store.receive(Action.sendTapped)
        store.finish()
        expect(recording.sent == listOf(SentPrompt(text = "Check the failing test suite", attachments = 1, delivery = OpenCodeDelivery.steer)))
        expect(store.state.draft == "")
        expect(store.state.pendingAttachments.isEmpty())
        expect(store.state.dictationHold == null)
    }

    @Test
    fun lockedBarSendDuringARunSteersAndItsMenuQueues() {
        for ((action, delivery) in listOf(Action.dictationSendTapped to OpenCodeDelivery.steer, Action.dictationQueueTapped to OpenCodeDelivery.queue)) {
            val recording = lockedRecording(makeState(isRunning = true), words = "Also check", final = "Also check the docs")
            val store = recording.store
            store.send(action) {
                it.dictationPhase = SessionFeature.State.DictationPhase.finishing(send = delivery)
            }
            store.receive<Action.dictationStopped>()
            store.finish()
            expect(recording.sent == listOf(SentPrompt(text = "Also check the docs", attachments = 0, delivery = delivery)))
        }
    }

    @Test
    fun lockedBarSendBeforeAnyWordsSendsOnlyAnExistingDraft() {
        val empty = lockedRecording(makeState(), words = null, final = null)
        empty.store.send(Action.dictationSendTapped)
        empty.store.receive<Action.dictationStopped>()
        empty.store.finish()
        expect(empty.sent.isEmpty(), "Nothing to send: Send does nothing")
        expect(empty.store.state.dictationPhase == SessionFeature.State.DictationPhase.idle)
        expect(empty.store.state.items.isEmpty())

        val typed = lockedRecording(makeState(draft = "Typed first"), words = null, final = null)
        typed.store.send(Action.dictationSendTapped)
        typed.store.receive<Action.dictationStopped>()
        typed.store.finish()
        expect(typed.sent == listOf(SentPrompt(text = "Typed first", attachments = 0, delivery = OpenCodeDelivery.steer)))
    }

    /// Stop, then Send before the engine has finished: the words go with the message.
    @Test
    fun sendWhileFinishingAfterStopSendsTheWords() {
        val recording = lockedRecording(makeState(), words = "Rename", final = null, finishesOnStop = false)
        val store = recording.store
        store.send(Action.dictationInsertTapped) {
            it.dictationPhase = SessionFeature.State.DictationPhase.finishing(send = null)
        }
        store.send(Action.dictationSendTapped) {
            it.dictationPhase = SessionFeature.State.DictationPhase.finishing(send = OpenCodeDelivery.steer)
        }
        recording.feed.trySend("Rename the tab")
        recording.feed.close()
        store.receive<Action.dictationStopped>()
        store.finish()
        expect(recording.sent == listOf(SentPrompt(text = "Rename the tab", attachments = 0, delivery = OpenCodeDelivery.steer)))
    }

    /// Sliding only arms; letting go commits what is armed.
    @Test
    fun slidingArmsAndReleasingCommits() {
        for (arm in listOf(SessionFeature.State.DictationHold.Arm.lock, SessionFeature.State.DictationHold.Arm.cancel, null)) {
            val feed = Channel<String>(Channel.UNLIMITED)
            val sent = mutableListOf<String>()
            val store = TestStore(
                initialState = { makeState(draft = "Typed") },
                withDependencies = {
                    it.uuid = UUIDGenerator.incrementing()
                    it.dictation.start = { feed.consumeAsFlow() }
                    it.dictation.stop = { feed.close() }
                    it.openCodeClient.sendPrompt = { _, _, text, _, _, _, _ -> sent += text }
                    it.openCodeClient.waitForIdle = { _, _ -> }
                    it.openCodeClient.transcript = { _, _, _ -> emptyList() }
                },
            ) { SessionFeature() }
            store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
            store.send(Action.micPressBegan)
            store.receive(Action.dictationReady) { it.dictationPhase = SessionFeature.State.DictationPhase.recording }
            feed.trySend("and more")
            store.receive<Action.dictationUpdated>()
            // Slide past both thresholds and back: arming alone commits nothing.
            store.send(Action.micPressArmed(SessionFeature.State.DictationHold.Arm.lock)) { state ->
                state.dictationHold?.let { state.dictationHold = it.copy(armed = SessionFeature.State.DictationHold.Arm.lock) }
            }
            store.send(Action.micPressArmed(SessionFeature.State.DictationHold.Arm.cancel)) { state ->
                state.dictationHold?.let { state.dictationHold = it.copy(armed = SessionFeature.State.DictationHold.Arm.cancel) }
            }
            store.send(Action.micPressArmed(null)) { state ->
                state.dictationHold?.let { state.dictationHold = it.copy(armed = null) }
            }
            expect(store.state.isDictating)
            expect(store.state.dictationHold?.isLocked == false)
            if (arm != null) {
                store.send(Action.micPressArmed(arm)) { state -> state.dictationHold?.let { state.dictationHold = it.copy(armed = arm) } }
            }

            store.send(Action.micPressReleased)
            when (arm) {
                SessionFeature.State.DictationHold.Arm.lock -> {
                    expect(store.state.dictationHold == SessionFeature.State.DictationHold(text = "Typed and more", isLocked = true, armed = SessionFeature.State.DictationHold.Arm.lock))
                    expect(store.state.isDictating)
                    feed.close()
                    store.finish()
                    expect(sent.isEmpty())
                }
                SessionFeature.State.DictationHold.Arm.cancel -> {
                    store.receive(Action.micPressCancelled)
                    store.finish()
                    expect(store.state.dictationHold == null)
                    expect(store.state.draft == "Typed")
                    expect(sent.isEmpty())
                }
                null -> {
                    store.finish()
                    expect(sent == listOf("Typed and more"))
                }
            }
        }
    }

    /// A held mic whose touch the system takes (Control Center, a call
    /// banner, another finger, the app leaving) keeps the words in the draft
    /// and never sends, whether nothing, lock, or cancel was armed.
    @Test
    fun aStolenTouchInsertsTheWordsInsteadOfSending() {
        for (arm in listOf(null, SessionFeature.State.DictationHold.Arm.lock, SessionFeature.State.DictationHold.Arm.cancel)) {
            val feed = Channel<String>(Channel.UNLIMITED)
            val store = TestStore(
                initialState = { makeState(draft = "Check") },
                withDependencies = {
                    it.dictation.start = { feed.consumeAsFlow() }
                    it.dictation.stop = {
                        feed.trySend("the failing test suite")
                        feed.close()
                    }
                    it.openCodeClient.sendPrompt = { _, _, _, _, _, _, _ ->
                        expect(false, "A stolen touch must not send")
                    }
                },
            ) { SessionFeature() }
            store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
            store.send(Action.micPressBegan)
            store.receive(Action.dictationReady) { it.dictationPhase = SessionFeature.State.DictationPhase.recording }
            feed.trySend("the failing")
            store.receive<Action.dictationUpdated>()
            if (arm != null) store.send(Action.micPressArmed(arm))
            store.send(Action.micPressInterrupted) {
                it.dictationPhase = SessionFeature.State.DictationPhase.finishing(send = null)
            }
            store.receive<Action.dictationStopped>()
            store.finish()
            expect(store.state.dictationHold == null)
            expect(store.state.draft == "Check the failing test suite", "armed: $arm")
            expect(store.state.items.isEmpty())
        }
    }

    /// The permission prompt takes the touch while setup waits on Allow:
    /// recording carries on locked, as when the finger lifts then.
    @Test
    fun aStolenTouchWhilePreparingLocks() {
        val allow = CompletableDeferred<Unit>()
        val feed = Channel<String>(Channel.UNLIMITED)
        var stops = 0
        val store = TestStore(
            initialState = { makeState() },
            withDependencies = {
                it.dictation.start = {
                    allow.await()
                    feed.consumeAsFlow()
                }
                it.dictation.stop = { stops += 1 }
                it.openCodeClient.sendPrompt = { _, _, _, _, _, _, _ ->
                    expect(false, "A stolen touch must not send")
                }
            },
        ) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        store.send(Action.micPressBegan)
        store.send(Action.micPressInterrupted) { state ->
            state.dictationHold?.let { hold -> state.dictationHold = hold.copy(isLocked = true) }
        }
        expect(store.state.dictationPhase == SessionFeature.State.DictationPhase.preparing)
        expect(stops == 0)
        allow.complete(Unit)
        store.receive(Action.dictationReady) { it.dictationPhase = SessionFeature.State.DictationPhase.recording }
        feed.close()
        store.finish()
        expect(store.state.items.isEmpty())
    }

    /// UIKit cancels the touch while the scene is merely inactive, before it
    /// reaches the background. If that ever ends as a release, the send still
    /// waits on the engine, and leaving downgrades it to Stop.
    @Test
    fun backgroundingMidHoldNeverSends() {
        val feed = Channel<String>(Channel.UNLIMITED)
        val store = TestStore(
            initialState = { makeState(draft = "Check") },
            withDependencies = {
                it.dictation.start = { feed.consumeAsFlow() }
                it.dictation.stop = {}
                it.openCodeClient.sendPrompt = { _, _, _, _, _, _, _ ->
                    expect(false, "Leaving the app must not send a dictation")
                }
            },
        ) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        store.send(Action.micPressBegan)
        store.receive(Action.dictationReady) { it.dictationPhase = SessionFeature.State.DictationPhase.recording }
        feed.trySend("the failing")
        store.receive<Action.dictationUpdated>()
        store.send(Action.micPressReleased) {
            it.dictationPhase = SessionFeature.State.DictationPhase.finishing(send = OpenCodeDelivery.steer)
        }
        store.send(Action.sceneEnteredBackground) {
            it.dictationPhase = SessionFeature.State.DictationPhase.finishing(send = null)
        }
        feed.trySend("the failing test suite")
        feed.close()
        store.receive<Action.dictationStopped>()
        store.finish()
        expect(store.state.draft == "Check the failing test suite")
        expect(store.state.items.isEmpty())
    }

    @Test
    fun tappedRecordingCancelDiscardsWordsAndKeepsTypedDraft() {
        val feed = Channel<String>(Channel.UNLIMITED)
        val store = TestStore(
            initialState = { makeState(draft = "Keep this") },
            withDependencies = {
                it.dictation.start = { feed.consumeAsFlow() }
                it.dictation.stop = { feed.close() }
                it.openCodeClient.sendPrompt = { _, _, _, _, _, _, _ ->
                    expect(false, "Cancelling a locked recording must not send")
                }
            },
        ) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.micPressBegan) {
            it.dictationPhase = SessionFeature.State.DictationPhase.preparing
            it.dictationHold = SessionFeature.State.DictationHold(text = "Keep this")
        }
        store.receive(Action.dictationReady) { it.dictationPhase = SessionFeature.State.DictationPhase.recording }
        store.send(Action.micPressLocked) { state ->
            state.dictationHold?.let { hold -> state.dictationHold = hold.copy(isLocked = true) }
        }
        feed.trySend("discard me")
        store.receive(Action.dictationUpdated("Keep this discard me")) { state ->
            state.dictationHold?.let { hold -> state.dictationHold = hold.copy(text = "Keep this discard me") }
        }
        store.send(Action.micPressCancelled) {
            it.dictationPhase = SessionFeature.State.DictationPhase.idle
            it.dictationHold = null
        }
        expect(store.state.draft == "Keep this")
        expect(store.state.items.isEmpty())
        store.finish()
    }

    @Test
    fun dictationFailureSurfacesTheReason() {
        val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
        val session = OpenCodeSession(id = "ses_1", title = "Chat", projectID = "proj_1", directory = "/tmp/demo", updatedAt = System.currentTimeMillis())
        val store = TestStore(
            initialState = { SessionFeature.State(server = server, session = session, cachedItems = emptyList()) },
            withDependencies = {
                it.dictation.start = { throw DictationError.microphoneDenied }
                it.dictation.stop = {}
            },
        ) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.micTapped) {
            it.dictationPhase = SessionFeature.State.DictationPhase.preparing
        }
        store.receive(Action.dictationStopped(errorMessage = DictationError.microphoneDenied.message)) {
            it.dictationPhase = SessionFeature.State.DictationPhase.idle
            it.dictationError = DictationError.microphoneDenied.message
        }
    }

    @Test
    fun failedDictationFinalizationDoesNotSendAnEmptyTranscript() {
        val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
        val session = OpenCodeSession(id = "ses_1", title = "Chat", projectID = "proj_1", directory = "/tmp/demo", updatedAt = System.currentTimeMillis())
        val feed = Channel<String>(Channel.UNLIMITED)
        val store = TestStore(
            initialState = { SessionFeature.State(server = server, session = session, cachedItems = emptyList()) },
            withDependencies = {
                it.dictation.start = { feed.consumeAsFlow() }
                it.dictation.stop = {
                    feed.close(DictationError.microphoneDenied)
                }
            },
        ) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.micTapped) { it.dictationPhase = SessionFeature.State.DictationPhase.preparing }
        store.receive(Action.dictationReady) { it.dictationPhase = SessionFeature.State.DictationPhase.recording }
        store.send(Action.dictationSendTapped) {
            it.dictationPhase = SessionFeature.State.DictationPhase.finishing(send = OpenCodeDelivery.steer)
        }
        store.receive(Action.dictationStopped(errorMessage = DictationError.microphoneDenied.message)) {
            it.dictationPhase = SessionFeature.State.DictationPhase.idle
            it.dictationError = DictationError.microphoneDenied.message
        }
        expect(store.state.items.isEmpty())
        store.finish()
    }

    @Test
    fun returnKeyOnEmptyDraftJustClearsTheNewline() {
        val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
        val session = OpenCodeSession(id = "ses_1", title = "Chat", projectID = "proj_1", directory = "/tmp/demo", updatedAt = System.currentTimeMillis())
        val state = SessionFeature.State(server = server, session = session, cachedItems = emptyList())
        state.draft = "\n"
        val store = TestStore(initialState = { state }) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(Action.returnKeyTapped) {
            it.draft = ""
        }
    }

    @Test
    fun shorterWindowSplicesInsteadOfFreezingTheTranscript() {
        val expanded = listOf(
            OpenCodeTranscriptItem.userText(id = "m1", text = "one"),
            OpenCodeTranscriptItem.tool(id = "m2", call = OpenCodeToolCall.test(name = "read")),
            OpenCodeTranscriptItem.tool(id = "m3", call = OpenCodeToolCall.test(name = "edit")),
            OpenCodeTranscriptItem.userText(id = "m4", text = "two"),
        )
        // A poll returns only the newest messages — fewer items than we hold.
        val window = listOf(
            OpenCodeTranscriptItem.userText(id = "m4", text = "two"),
            OpenCodeTranscriptItem.userText(id = "m5", text = "three"),
        )

        expect(
            SessionFeature.mergeTranscript(fetched = window, into = expanded) == listOf(
                OpenCodeTranscriptItem.userText(id = "m1", text = "one"),
                OpenCodeTranscriptItem.tool(id = "m2", call = OpenCodeToolCall.test(name = "read")),
                OpenCodeTranscriptItem.tool(id = "m3", call = OpenCodeToolCall.test(name = "edit")),
                OpenCodeTranscriptItem.userText(id = "m4", text = "two"),
                OpenCodeTranscriptItem.userText(id = "m5", text = "three"),
            ),
        )
    }

    @Test
    fun restPageDuringStreamPreservesUnsettledAssistantRowsWithoutDuplicatingEchoes() {
        val user = OpenCodeTranscriptItem.userText(id = "msg_user", text = "First prompt")
        val liveText = OpenCodeTranscriptItem.assistantMarkdown(
            id = "msg_reply-text-0",
            blocks = OpenCodeMarkdownBlock.blocks(from = "Still typing"),
        )
        val liveTool = OpenCodeTranscriptItem.tool(id = "msg_reply-tool-call", call = OpenCodeToolCall.test(name = "shell"))
        val existing = listOf(user, liveText, liveTool)

        // A queued second prompt can trigger a page read before these ephemeral
        // parts have durable REST values. Neither the answer nor its tool vanishes.
        expect(SessionFeature.mergeTranscript(
            fetched = listOf(user),
            into = existing,
            projectedMessageIDs = setOf("msg_reply"),
        ) == existing)

        // Once REST echoes text, it replaces the SSE part exactly once, while an
        // unpersisted tool stays projected until the message settles.
        val echoed = OpenCodeTranscriptItem.assistantMarkdown(
            id = "msg_reply-text-0",
            blocks = OpenCodeMarkdownBlock.blocks(from = "Server text"),
        )
        expect(SessionFeature.mergeTranscript(
            fetched = listOf(user, echoed),
            into = existing,
            projectedMessageIDs = setOf("msg_reply"),
        ) == listOf(user, echoed, liveTool))

        // With no active projection, the authoritative page can remove rows.
        expect(SessionFeature.mergeTranscript(fetched = listOf(user), into = existing) == listOf(user))
    }

    @Test
    fun mergeRetiresEchoedOptimisticRowsAndKeepsInfoRows() {
        val existing = listOf(
            OpenCodeTranscriptItem.userText(id = "m1", text = "one"),
            OpenCodeTranscriptItem.info(id = "info-a", text = "Run failed: aborted"),
            OpenCodeTranscriptItem.userText(id = "msg_2", text = "two"),
        )
        val window = listOf(
            OpenCodeTranscriptItem.userText(id = "m1", text = "one"),
            OpenCodeTranscriptItem.userText(id = "msg_2", text = "two"),
        )

        expect(
            SessionFeature.mergeTranscript(fetched = window, into = existing, optimisticPromptIDs = setOf("msg_2")) == listOf(
                OpenCodeTranscriptItem.userText(id = "m1", text = "one"),
                OpenCodeTranscriptItem.userText(id = "msg_2", text = "two"),
                OpenCodeTranscriptItem.info(id = "info-a", text = "Run failed: aborted"),
            ),
        )
        // Until the server echoes it, the optimistic bubble survives refreshes.
        expect(
            SessionFeature.mergeTranscript(
                fetched = listOf(OpenCodeTranscriptItem.userText(id = "m1", text = "one")),
                into = existing,
                optimisticPromptIDs = setOf("msg_2"),
            ) == existing,
        )
    }

    @Test
    fun mergeAppendsAWindowThatJumpedPastWhatWeHold() {
        val existing = listOf(OpenCodeTranscriptItem.userText(id = "m1", text = "one"))
        val window = listOf(
            OpenCodeTranscriptItem.userText(id = "m9", text = "nine"),
            OpenCodeTranscriptItem.userText(id = "m10", text = "ten"),
        )

        expect(SessionFeature.mergeTranscript(fetched = window, into = existing) == existing + window)
    }

    private fun expectOptimisticSend(
        state: SessionFeature.State,
        text: String = "hello",
    ) {
        val messageID = "msg_00000000000000000000000000000000"
        state.optimisticPromptIDs = setOf(messageID)
        state.nextPromptRequestEpoch = 1
        state.promptStates += (messageID to SessionFeature.State.PromptState(requestEpoch = 1))
        state.draft = ""
        state.isAwaitingReply = true
        state.items = listOf(OpenCodeTranscriptItem.userText(id = messageID, text = text))
        state.lastSentItemID = messageID
        state.sendGeneration = 1
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SessionRefreshReviewTests {
    @get:Rule
    val dependencies = TestDependencies()

    private val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
    private val session = OpenCodeSession(id = "ses_review", title = "Review", projectID = "p", directory = "/project", updatedAt = 0L)
    private val user = OpenCodeTranscriptItem.userText(id = "msg_user", text = "Check")
    private val text = OpenCodeTranscriptItem.assistantMarkdown(id = "msg_reply-text-0", blocks = OpenCodeMarkdownBlock.blocks(from = "Unpersisted"))

    @Test
    fun idleSnapshotDoesNotResurrectRacedParts() {
        for (targeted in listOf(false, true)) {
            val state = SessionFeature.State(server = server, session = session, cachedItems = listOf(user, text))
            state.projectedMessageIDs = setOf("msg_reply")
            state.liveText = mapOf("msg_reply-text-0" to "Unpersisted")
            state.refresh = SessionFeature.State.Refresh(
                id = 1,
                reason = SessionFeature.RefreshReason.settlement("msg_reply"),
                racedEvents = listOf(
                    OpenCodeLiveContentEvent.textEnded(messageID = "msg_reply", ordinal = 0, text = "Unpersisted"),
                    OpenCodeLiveContentEvent.toolInputStarted(messageID = "msg_reply", toolID = "call", name = "shell"),
                ),
            )
            val store = TestStore(initialState = { state }) { SessionFeature() }
            store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
            store.send(Action.refreshResponse(
                sessionID = session.id,
                requestID = 1,
                result = Result.success(SessionFeature.AuthoritativeRefresh(
                    observed = session,
                    items = if (targeted) emptyList() else listOf(user),
                    scope = if (targeted) SessionFeature.AuthoritativeRefresh.Scope.message("msg_reply") else SessionFeature.AuthoritativeRefresh.Scope.page,
                )),
            ))
            expect(store.state.items == listOf(user))
            expect(store.state.liveText.isEmpty())
            expect(store.state.projectedMessageIDs.isEmpty())
        }
    }

    @Test
    fun terminalExecutionQueuesAFullReadBehindAPartSettlement() {
        for (targeted in listOf(false, true)) {
            val tool = OpenCodeTranscriptItem.tool(
                id = "msg_reply-tool-call",
                call = OpenCodeToolCall(
                    callID = "call",
                    messageID = "msg_reply",
                    name = "tool",
                    status = OpenCodeToolCall.Status.completed,
                    input = OpenCodeJSONValue.`object`(emptyMap()),
                    output = listOf("Done"),
                    error = null,
                    metadata = emptyMap(),
                ),
            )
            val ghost = OpenCodeTranscriptItem.assistantMarkdown(id = "msg_reply-text-1", blocks = emptyList())
            val state = SessionFeature.State(server = server, session = session, cachedItems = listOf(user, text))
            state.isExternallyActive = true
            state.projectedMessageIDs = setOf("msg_reply")
            state.refresh = SessionFeature.State.Refresh(id = 1, reason = SessionFeature.RefreshReason.settlement("msg_reply"))
            state.nextRefreshID = 1
            val finalRead = CompletableDeferred<Unit>()
            val store = TestStore(
                initialState = { state },
                withDependencies = {
                    it.openCodeClient.getSession = { _, _ -> session }
                    it.openCodeClient.transcript = { _, _, _ ->
                        finalRead.await()
                        listOf(user, text, tool)
                    }
                },
            ) { SessionFeature() }
            store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
            store.send(Action.contentBatch(sessionID = session.id, events = listOf(
                OpenCodeLiveContentEvent.textEnded(messageID = "msg_reply", ordinal = 0, text = "Unpersisted"),
                OpenCodeLiveContentEvent.toolSucceeded(messageID = "msg_reply", toolID = "call", output = listOf("Done"), metadata = emptyMap()),
            )))
            store.receive(Action.refreshRequested(SessionFeature.RefreshReason.settlement("msg_reply")))
            store.send(Action.externalRunStateChanged(isRunning = false))
            expect(store.state.refresh?.followup == SessionFeature.RefreshReason.runState)
            store.receive(Action.refreshRequested(SessionFeature.RefreshReason.runState))
            store.send(Action.refreshRequested(SessionFeature.RefreshReason.invalidation))
            expect(store.state.refresh?.followup == SessionFeature.RefreshReason.runState)
            expect(store.state.projectedMessageIDs == setOf("msg_reply"))
            val stale = OpenCodeTranscriptItem.assistantMarkdown(id = "msg_reply-text-0", blocks = emptyList())
            store.send(Action.refreshResponse(
                sessionID = session.id,
                requestID = 1,
                result = Result.success(SessionFeature.AuthoritativeRefresh(
                    observed = session,
                    items = if (targeted) listOf(stale, ghost) else listOf(user, stale, ghost),
                    scope = if (targeted) SessionFeature.AuthoritativeRefresh.Scope.message("msg_reply") else SessionFeature.AuthoritativeRefresh.Scope.page,
                )),
            ))
            expect(store.state.items.contains(text), "R1 must replay the raced final text")
            expect(store.state.items.contains(tool), "R1 must retain the completed tool absent from its snapshot")
            store.receive(Action.refreshRequested(SessionFeature.RefreshReason.runState))
            finalRead.complete(Unit)
            store.receive<Action.refreshResponse>()
            expect(store.state.items == listOf(user, text, tool), "The terminal read retires only the unpersisted part")
            expect(store.state.projectedMessageIDs.isEmpty())
            store.finish()
        }
    }

    @Test
    fun serverIdleSettlesAnAdmittedSendUnlessALocalPromptStillWaits() {
        for (pending in listOf("none", "submitting", "queued", "echoed")) {
            val next = OpenCodeTranscriptItem.userText(id = "msg_next", text = "Next")
            val state = SessionFeature.State(server = server, session = session, cachedItems = listOf(user, text))
            state.isAwaitingReply = true
            state.isExternallyActive = true
            state.projectedMessageIDs = setOf("msg_reply")
            if (pending != "none") {
                state.promptStates += (
                    "msg_next" to SessionFeature.State.PromptState(
                        requestEpoch = 1,
                        admission = if (pending == "submitting") SessionFeature.State.PromptState.Admission.sent else SessionFeature.State.PromptState.Admission.admitted,
                    )
                )
                if (pending != "submitting") state.queuedInputs = listOf(OpenCodePendingInput(id = "msg_next", text = "Next", delivery = OpenCodeDelivery.queue))
            }
            val store = TestStore(
                initialState = { state },
                withDependencies = {
                    it.openCodeClient.getSession = { _, _ -> session }
                    it.openCodeClient.transcript = { _, _, _ -> if (pending == "echoed") listOf(user, next) else listOf(user) }
                },
            ) { SessionFeature() }
            store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
            store.send(Action.externalRunStateChanged(isRunning = false))
            expect(store.state.isAwaitingReply == (pending != "none"))
            expect(store.state.isRunning == (pending != "none"))
            store.receive<Action.refreshResponse>()
            expect(store.state.isAwaitingReply == (pending == "submitting" || pending == "queued"))
            expect(store.state.items == when (pending) {
                "echoed" -> listOf(user, next)
                "none" -> listOf(user)
                else -> listOf(user, text)
            })
            store.finish()
        }
    }

    @Test
    fun failedIdleWaitDoesNotCompleteTheSendOrEraseItsProjection() {
        val idle = CompletableDeferred<Unit>()
        var reads = 0
        val state = SessionFeature.State(server = server, session = session, cachedItems = listOf(user))
        state.draft = "Check"
        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.uuid = UUIDGenerator.incrementing()
                it.openCodeClient.sendPrompt = { _, _, _, _, _, _, _ -> }
                it.openCodeClient.waitForIdle = { _, _ ->
                    idle.await()
                    throw OpenCodeClientError(message = "Network interrupted")
                }
                it.openCodeClient.getSession = { _, _ -> session }
                it.openCodeClient.transcript = { _, _, _ ->
                    reads += 1
                    listOf(user)
                }
            },
        ) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        store.send(Action.sendTapped)
        store.receive<Action.promptAdmitted>()
        store.send(Action.contentBatch(sessionID = session.id, events = listOf(OpenCodeLiveContentEvent.textDelta(messageID = "msg_reply", ordinal = 0, delta = "Unpersisted"))))
        idle.complete(Unit)
        store.finish()
        store.skipReceivedActions()
        expect(store.state.isAwaitingReply)
        expect(reads == 0, "A failed wait is not a terminal read")
        store.send(Action.refreshRequested(SessionFeature.RefreshReason.reconnect))
        store.receive<Action.refreshResponse>()
        expect(store.state.items.contains(text))
        store.send(Action.externalRunStateChanged(isRunning = false))
        store.receive<Action.refreshResponse>()
        expect(!store.state.isRunning)
        expect(!store.state.items.contains(text))
        store.finish()
    }

    @Test
    fun laterPromptEchoSettlesIdleAwaitingReply() {
        for (reason in listOf(
            SessionFeature.RefreshReason.invalidation,
            SessionFeature.RefreshReason.settlement("msg_next"),
            SessionFeature.RefreshReason.reconnect,
            SessionFeature.RefreshReason.watchdog,
        )) {
            val next = OpenCodeTranscriptItem.userText(id = "msg_next", text = "Next")
            var snapshot = listOf(user, text)
            val state = SessionFeature.State(server = server, session = session, cachedItems = listOf(user, text))
            state.isExternallyActive = true
            state.isAwaitingReply = true
            state.projectedMessageIDs = setOf("msg_reply")
            state.queuedInputs = listOf(OpenCodePendingInput(id = "msg_next", text = "Next", delivery = OpenCodeDelivery.queue))
            state.promptStates += ("msg_next" to SessionFeature.State.PromptState(requestEpoch = 1, admission = SessionFeature.State.PromptState.Admission.admitted))
            val store = TestStore(
                initialState = { state },
                withDependencies = {
                    it.openCodeClient.getSession = { _, _ -> session }
                    it.openCodeClient.transcript = { _, _, _ -> snapshot }
                },
            ) { SessionFeature() }
            store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
            store.send(Action.externalRunStateChanged(isRunning = false))
            store.receive<Action.refreshResponse>()
            expect(store.state.isAwaitingReply)
            snapshot = listOf(user, next)
            store.send(Action.refreshRequested(reason))
            store.receive<Action.refreshResponse>()
            expect(store.state.queuedInputs.isEmpty())
            expect(!store.state.isAwaitingReply)
            expect(!store.state.isRunning)
            expect(store.state.projectedMessageIDs.isEmpty())
            expect(store.state.items == listOf(user, next))
            store.finish()
        }
    }

    @Test
    fun explicitEchoOrCancellationReleasesTheLastIdlePrompt() {
        for (cancel in listOf(false, true)) {
            val next = OpenCodeTranscriptItem.userText(id = "msg_next", text = "Next")
            val state = SessionFeature.State(server = server, session = session, cachedItems = listOf(user, text))
            state.isExternallyActive = true
            state.isAwaitingReply = true
            state.projectedMessageIDs = setOf("msg_reply")
            state.queuedInputs = listOf(OpenCodePendingInput(id = "msg_next", text = "Next", delivery = OpenCodeDelivery.queue))
            state.promptStates += ("msg_next" to SessionFeature.State.PromptState(requestEpoch = 1, admission = SessionFeature.State.PromptState.Admission.admitted))
            val store = TestStore(
                initialState = { state },
                withDependencies = {
                    it.openCodeClient.getSession = { _, _ -> session }
                    it.openCodeClient.transcript = { _, _, _ -> listOf(user, text) }
                    it.openCodeClient.cancelPendingInput = { _, _, id -> expect(id == "msg_next") }
                    it.openCodeClient.pendingInputs = { _, _ -> emptyList() }
                },
            ) { SessionFeature() }
            store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
            store.send(Action.externalRunStateChanged(isRunning = false))
            store.receive<Action.refreshResponse>()
            expect(store.state.isAwaitingReply)
            if (cancel) {
                store.send(Action.pendingInputCancelTapped("msg_next"))
                store.receive<Action.pendingInputsResponse>()
            } else {
                store.send(Action.promptEchoResponse(sessionID = session.id, messageID = "msg_next", requestEpoch = 1, items = listOf(next)))
            }
            expect(store.state.queuedInputs.isEmpty())
            expect(!store.state.isAwaitingReply)
            expect(!store.state.isRunning)
            expect(store.state.projectedMessageIDs.isEmpty())
            store.finish()
        }
    }

    @Test
    fun duplicateIdleSignalsShareTheInFlightTerminalRead() {
        for (replyCompleted in listOf(false, true)) {
            val gate = CompletableDeferred<Unit>()
            var reads = 0
            val state = SessionFeature.State(server = server, session = session, cachedItems = listOf(user, text))
            state.isExternallyActive = true
            state.isAwaitingReply = true
            state.projectedMessageIDs = setOf("msg_reply")
            val store = TestStore(
                initialState = { state },
                withDependencies = {
                    it.openCodeClient.getSession = { _, _ -> session }
                    it.openCodeClient.transcript = { _, _, _ ->
                        val count = ++reads
                        if (count == 1) gate.await()
                        listOf(user)
                    }
                },
            ) { SessionFeature() }
            store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
            store.send(Action.externalRunStateChanged(isRunning = false))
            store.receive(Action.refreshRequested(SessionFeature.RefreshReason.runState))
            expect(!store.state.isRunning)
            store.send(if (replyCompleted) Action.replyCompleted(sessionID = session.id) else Action.externalRunStateChanged(isRunning = false))
            store.skipReceivedActions()
            expect(store.state.refresh?.followup == null)
            gate.complete(Unit)
            store.receive<Action.refreshResponse>()
            expect(store.state.items == listOf(user))
            expect(store.state.projectedMessageIDs.isEmpty())
            store.finish()
            store.skipReceivedActions()
            expect(reads == 1)
            store.send(Action.replyCompleted(sessionID = session.id))
            store.finish()
            store.skipReceivedActions()
            expect(reads == 1, "A wait resolving after terminal reconciliation must also be a no-op")
        }
    }

    @Test
    fun contentBeforeTheRunningEventSurvivesTheFirstRead() {
        for (reason in listOf(SessionFeature.RefreshReason.open, SessionFeature.RefreshReason.reconnect)) {
            val state = SessionFeature.State(server = server, session = session, cachedItems = listOf(user))
            state.refresh = SessionFeature.State.Refresh(id = 1, reason = reason)
            state.nextRefreshID = 1
            val store = TestStore(
                initialState = { state },
                withDependencies = {
                    it.openCodeClient.getSession = { _, _ -> session }
                    it.openCodeClient.transcript = { _, _, _ -> listOf(user, text) }
                },
            ) { SessionFeature() }
            store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
            store.send(Action.contentBatch(sessionID = session.id, events = listOf(OpenCodeLiveContentEvent.textDelta(messageID = "msg_reply", ordinal = 0, delta = "Unpersisted"))))
            expect(store.state.isExternallyActive)
            store.send(Action.refreshResponse(
                sessionID = session.id,
                requestID = 1,
                result = Result.success(SessionFeature.AuthoritativeRefresh(observed = session, items = listOf(user), scope = SessionFeature.AuthoritativeRefresh.Scope.page)),
            ))
            expect(store.state.items == listOf(user, text))
            store.send(Action.externalRunStateChanged(isRunning = false))
            store.receive<Action.refreshResponse>()
            expect(store.state.items == listOf(user, text))
            expect(store.state.projectedMessageIDs.isEmpty())
            store.finish()
        }
    }

    @Test
    fun racedEventsCannotRegressRESTTool() {
        val statuses = listOf(OpenCodeToolCall.Status.running, OpenCodeToolCall.Status.completed, OpenCodeToolCall.Status.error)
        val events = listOf(
            OpenCodeLiveContentEvent.toolInputStarted(messageID = "msg_reply", toolID = "call", name = "shell"),
            OpenCodeLiveContentEvent.toolInputDelta(messageID = "msg_reply", toolID = "call", delta = "{\"command\":"),
            OpenCodeLiveContentEvent.toolInputEnded(messageID = "msg_reply", toolID = "call", text = "{}"),
            OpenCodeLiveContentEvent.toolCalled(messageID = "msg_reply", toolID = "call", input = OpenCodeJSONValue.`object`(mapOf("stale" to OpenCodeJSONValue.bool(true)))),
            OpenCodeLiveContentEvent.toolProgress(messageID = "msg_reply", toolID = "call", metadata = mapOf("known" to OpenCodeJSONValue.bool(true))),
        )
        for (status in statuses) {
            for (event in events) {
                val call = OpenCodeToolCall(
                    callID = "call",
                    messageID = "msg_reply",
                    name = "read",
                    status = status,
                    input = OpenCodeJSONValue.`object`(mapOf("path" to OpenCodeJSONValue.string("preview.png"))),
                    output = listOf("Image read successfully"),
                    error = if (status == OpenCodeToolCall.Status.error) "Failed" else null,
                    metadata = mapOf("known" to OpenCodeJSONValue.bool(true)),
                    images = listOf(OpenCodeImageAttachment(id = "image", data = byteArrayOf(1, 2, 3))),
                    startedAt = 0L,
                )
                val tool = OpenCodeTranscriptItem.tool(id = "msg_reply-tool-call", call = call)
                val state = SessionFeature.State(server = server, session = session, cachedItems = listOf(tool))
                state.isExternallyActive = true
                state.refresh = SessionFeature.State.Refresh(id = 1, reason = SessionFeature.RefreshReason.settlement("msg_reply"), racedEvents = listOf(event))
                val store = TestStore(initialState = { state }) { SessionFeature() }
                store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
                store.send(Action.refreshResponse(
                    sessionID = session.id,
                    requestID = 1,
                    result = Result.success(SessionFeature.AuthoritativeRefresh(
                        observed = session,
                        items = listOf(tool),
                        scope = SessionFeature.AuthoritativeRefresh.Scope.message("msg_reply"),
                    )),
                ))
                expect(store.state.items == listOf(tool))
            }
        }
    }

    @Test
    fun userSendIsIdleBeforeItsFinalTranscriptIsAccepted() {
        val idle = CompletableDeferred<Unit>()
        var sentID = ""
        val state = SessionFeature.State(server = server, session = session, cachedItems = emptyList())
        state.draft = "Check"
        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.uuid = UUIDGenerator.incrementing()
                it.openCodeClient.sendPrompt = { _, _, _, _, _, id, _ -> sentID = id }
                it.openCodeClient.waitForIdle = { _, _ -> idle.await() }
                it.openCodeClient.transcript = { _, _, _ -> listOf(OpenCodeTranscriptItem.userText(id = sentID, text = "Check")) }
            },
        ) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        store.send(Action.sendTapped)
        store.receive<Action.promptAdmitted>()
        store.send(Action.contentBatch(sessionID = session.id, events = listOf(OpenCodeLiveContentEvent.textDelta(messageID = "msg_reply", ordinal = 0, delta = "Unpersisted"))))
        idle.complete(Unit)
        store.receive<Action.refreshResponse>()
        expect(!store.state.isRunning)
        expect(store.state.items.map { it.id } == listOf(sentID))
        expect(store.state.projectedMessageIDs.isEmpty())
        store.finish()
    }

    @Test
    fun revertedMessageFromAnEarlierRunIsNotProtectedInTheNextRun() {
        val state = SessionFeature.State(server = server, session = session, cachedItems = listOf(user, text))
        state.isExternallyActive = true
        state.projectedMessageIDs = setOf("msg_reply")
        var transcriptItems = listOf(user, text)
        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.openCodeClient.getSession = { _, _ -> session }
                it.openCodeClient.transcript = { _, _, _ -> transcriptItems }
            },
        ) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        store.send(Action.externalRunStateChanged(isRunning = false))
        store.receive<Action.refreshResponse>()
        expect(store.state.projectedMessageIDs.isEmpty())
        // The old reply was reverted on the server just before another run starts.
        transcriptItems = listOf(user)
        store.send(Action.externalRunStateChanged(isRunning = true))
        store.receive<Action.refreshResponse>()
        expect(store.state.items == listOf(user))
    }

    @Test
    fun settlementWithNoProjectedRowReadsThePage() {
        for (event in listOf(
            OpenCodeLiveContentEvent.textStarted(messageID = "msg_reply", ordinal = 0),
            OpenCodeLiveContentEvent.textDelta(messageID = "msg_reply", ordinal = 0, delta = " \n"),
        )) {
            val state = SessionFeature.State(server = server, session = session, cachedItems = listOf(user))
            state.isExternallyActive = true
            var pageReads = 0
            var messageReads = 0
            val store = TestStore(
                initialState = { state },
                withDependencies = {
                    it.continuousClock = ImmediateClock()
                    it.openCodeClient.getSession = { _, _ -> session }
                    it.openCodeClient.transcript = { _, _, _ ->
                        pageReads += 1
                        listOf(user, text)
                    }
                    it.openCodeClient.message = { _, _, _ ->
                        messageReads += 1
                        listOf(text)
                    }
                },
            ) { SessionFeature() }
            store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
            store.send(Action.contentBatch(sessionID = session.id, events = listOf(event)))
            store.send(Action.refreshRequested(SessionFeature.RefreshReason.settlement("msg_reply")))
            store.receive<Action.refreshResponse>()
            expect(pageReads == 1)
            expect(messageReads == 0)
            expect(store.state.items == listOf(user, text))
        }
    }

    @Test
    fun oldProjectedHistoryStaysBeforeTheWindow() {
        val items = (0 until 200).map { OpenCodeTranscriptItem.assistantMarkdown(id = "msg_$it-text-0", blocks = emptyList()) }
        val next = OpenCodeTranscriptItem.userText(id = "msg_next", text = "Next")
        val state = SessionFeature.State(server = server, session = session, cachedItems = items)
        state.isExternallyActive = true
        state.projectedMessageIDs = (0 until 200).map { "msg_$it" }.toSet()
        state.refresh = SessionFeature.State.Refresh(id = 1, reason = SessionFeature.RefreshReason.invalidation)
        val store = TestStore(initialState = { state }) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        store.send(Action.refreshResponse(
            sessionID = session.id,
            requestID = 1,
            result = Result.success(SessionFeature.AuthoritativeRefresh(
                observed = session,
                items = listOf(items.last(), next),
                scope = SessionFeature.AuthoritativeRefresh.Scope.page,
            )),
        ))
        expect(store.state.items.firstOrNull()?.id == items.firstOrNull()?.id)
        expect(store.state.items.lastOrNull()?.id == next.id)
        expect(store.state.items.size == 201)
    }

    @Test
    fun mergeCostScalesWithHistoryRatherThanPastRunCount() {
        fun duration(count: Int): Double {
            val items = (0 until count).map { OpenCodeTranscriptItem.assistantMarkdown(id = "msg_$it-text-0", blocks = emptyList()) }
            val projected = (0 until count).map { "msg_$it" }.toSet()
            val fetched = listOf(items.last(), OpenCodeTranscriptItem.userText(id = "msg_next", text = "Next"))
            return (0 until 3).map {
                val start = System.nanoTime()
                val merged = SessionFeature.mergeTranscript(fetched = fetched, into = items, projectedMessageIDs = projected)
                expect(merged.size == count + 1)
                (System.nanoTime() - start) / 1_000_000_000.0
            }.sorted()[1]
        }
        val small = duration(300)
        val large = duration(1200)
        println("merge scaling: 300=${small}s 1200=${large}s ratio=${large / maxOf(small, 0.000001)}")
        expect(large < maxOf(0.02, small * 9), "Four times the history should stay comfortably below quadratic growth")
    }

    @Test
    fun retainedTailFollowsItsPredecessorsNewSiblingsBeforeANewSteer() {
        for (hadText in listOf(false, true)) {
            val tool = OpenCodeTranscriptItem.tool(
                id = "msg_reply-tool-call",
                call = OpenCodeToolCall(
                    callID = "call",
                    messageID = "msg_reply",
                    name = "shell",
                    status = OpenCodeToolCall.Status.running,
                    input = OpenCodeJSONValue.`object`(emptyMap()),
                    output = emptyList(),
                    error = null,
                    metadata = emptyMap(),
                ),
            )
            val sibling = OpenCodeTranscriptItem.assistantMarkdown(id = "msg_reply-text-1", blocks = emptyList())
            val steer = OpenCodeTranscriptItem.userText(id = "msg_steer", text = "Also check")
            val state = SessionFeature.State(server = server, session = session, cachedItems = if (hadText) listOf(user, text, tool) else listOf(user, tool))
            state.isExternallyActive = true
            state.projectedMessageIDs = setOf("msg_reply")
            state.queuedInputs = listOf(OpenCodePendingInput(id = "msg_steer", text = "Also check"))
            state.refresh = SessionFeature.State.Refresh(id = 1, reason = SessionFeature.RefreshReason.invalidation)
            val store = TestStore(initialState = { state }) { SessionFeature() }
            store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
            store.send(Action.refreshResponse(
                sessionID = session.id,
                requestID = 1,
                result = Result.success(SessionFeature.AuthoritativeRefresh(
                    observed = session,
                    items = if (hadText) listOf(user, text, sibling, steer) else listOf(user, text, steer),
                    scope = SessionFeature.AuthoritativeRefresh.Scope.page,
                )),
            ))
            expect(store.state.items == if (hadText) listOf(user, text, sibling, tool, steer) else listOf(user, text, tool, steer))
        }
    }
}

fun OpenCodeToolCall.Companion.test(
    callID: String? = null,
    name: String,
    status: OpenCodeToolCall.Status = OpenCodeToolCall.Status.completed,
    input: OpenCodeJSONValue = OpenCodeJSONValue.`null`,
) = OpenCodeToolCall(
    callID = callID ?: name,
    name = name,
    status = status,
    input = input,
    output = emptyList(),
    error = null,
    metadata = emptyMap(),
)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SharedLocationTests {
    @get:Rule
    val dependencies = TestDependencies()

    private val spot = SharedLocation(
        latitude = 37.3349, longitude = -122.00902, accuracy = 12.4,
        place = "Apple Park", address = "1 Apple Park Way, Cupertino, CA 95014",
        capturedAt = Instant.ofEpochSecond(1_800_000_000),
    )

    @Test
    fun theBlockReadsPlainlyAndParsesBack() {
        val prompt = spot.prompt(with = "Find me coffee nearby")
        expect(prompt.startsWith("<shared-location>\nPlace: Apple Park\nAddress: 1 Apple Park Way, Cupertino, CA 95014\nCoordinates: 37.334900, -122.009020\nAccuracy: ±12 m"))
        expect(prompt.contains("Map: https://maps.apple.com/?ll=37.334900,-122.009020"))
        val parsed = requireNotNull(SharedLocation.parse(prompt))
        expect(parsed.remainder == "Find me coffee nearby")
        expect(parsed.location.place == "Apple Park")
        expect(parsed.location.address == spot.address)
        expect(abs(parsed.location.latitude - 37.3349) < 1e-6)
        expect(parsed.location.capturedAt == spot.capturedAt)
        expect(SharedLocation.parse("Coordinates: 1, 2") == null)
    }

    @Test
    fun theTranscriptShowsTheCardAndOnlyTheTypedText() {
        val message = TranscriptUserMessage(id = "msg_1", text = spot.prompt(with = "Here"), images = emptyList(), files = emptyList(), pending = null)
        expect(message.location?.place == "Apple Park")
        expect(message.displayText == "Here")
    }

    @Test
    fun locationAttachesThenSendsAtTheTopOfThePrompt() {
        val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
        val session = OpenCodeSession(id = "ses_loc", title = "Loc", projectID = "p", directory = "/tmp", updatedAt = distantPast)
        val state = SessionFeature.State(server = server, session = session, cachedItems = emptyList())
        state.draft = "Where am I?"
        val sent = mutableListOf<String>()
        val spot = spot
        val store = TestStore(initialState = { state }, withDependencies = {
            it.uuid = UUIDGenerator.incrementing()
            it.location.current = { spot }
            it.openCodeClient.sendPrompt = { _, _, text, _, _, _, _ -> sent += text }
        }) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        store.send(Action.locationTapped) { it.isLocating = true }
        store.receive<Action.locationResponse> {
            it.isLocating = false
            it.pendingLocation = spot
        }
        expect(store.state.canSendPrompt)
        store.send(Action.sendTapped)
        store.skipInFlightEffects()
        expect(store.state.pendingLocation == null)
        expect(sent == listOf(spot.prompt(with = "Where am I?")))
    }

    @Test
    fun deniedLocationExplainsItself() {
        val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
        val session = OpenCodeSession(id = "ses_denied", title = "Denied", projectID = "p", directory = "/tmp", updatedAt = distantPast)
        val store = TestStore(initialState = { SessionFeature.State(server = server, session = session, cachedItems = emptyList()) }, withDependencies = {
            it.location.current = { throw LocationClientError.denied }
        }) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        store.send(Action.locationTapped)
        store.receive<Action.locationResponse>()
        expect(store.state.pendingLocation == null)
        expect(store.state.locationError == LocationClientError.denied.message)
    }
}
