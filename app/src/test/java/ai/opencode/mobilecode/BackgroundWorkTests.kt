@file:PortedFrom("OpenCodeTests/BackgroundWorkTests.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.tca.Exhaustivity
import ai.opencode.mobilecode.tca.ImmediateClock
import ai.opencode.mobilecode.tca.TestClock
import ai.opencode.mobilecode.tca.TestDependencies
import ai.opencode.mobilecode.tca.TestStore
import ai.opencode.mobilecode.tca.UUIDGenerator
import ai.opencode.mobilecode.tca.continuousClock
import ai.opencode.mobilecode.tca.date
import ai.opencode.mobilecode.tca.expect
import ai.opencode.mobilecode.tca.DateGenerator
import ai.opencode.mobilecode.tca.uuid
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
private val session = OpenCodeSession(id = "ses_bg", title = "Background", projectID = "p", directory = "/project", updatedAt = System.currentTimeMillis())

private fun runningTool(
    key: String = "msg_a-tool-call_1",
    name: String = "shell",
    status: OpenCodeToolCall.Status = OpenCodeToolCall.Status.running,
    startedAt: Long? = null,
) = OpenCodeTranscriptItem.tool(
    id = key,
    call = OpenCodeToolCall(
        callID = "call_1",
        messageID = "msg_a",
        name = name,
        status = status,
        input = OpenCodeJSONValue.`object`(mapOf("command" to OpenCodeJSONValue.string("bun run build --watch"))),
        output = emptyList(),
        error = null,
        metadata = mapOf("shellID" to OpenCodeJSONValue.string("sh_1")),
        startedAt = startedAt,
    ),
)

/// A shell's capture file: ASCII bytes the mock pages like the server does.
private class Capture {
    private val bytes = mutableListOf<Byte>()
    var status = OpenCodeShell.Status.running
    val reads = mutableListOf<Pair<Long, Long>>()
    var stops = 0

    fun append(text: String) {
        bytes += text.toByteArray(Charsets.UTF_8).toList()
    }

    fun page(cursor: Long, limit: Long): OpenCodeShellOutputPage {
        reads += cursor to limit
        if (cursor >= bytes.size) return OpenCodeShellOutputPage(output = "", cursor = bytes.size, size = bytes.size)
        val end = minOf(bytes.size.toLong(), cursor + limit).toInt()
        val slice = bytes.subList(cursor.toInt(), end).toByteArray()
        return OpenCodeShellOutputPage(output = String(slice, Charsets.UTF_8), cursor = end, size = bytes.size)
    }

    fun shell() = OpenCodeShell(
        id = "sh_1",
        command = "bun dev",
        sessionID = "ses_bg",
        status = status,
        exitCode = if (status == OpenCodeShell.Status.exited) 0 else null,
    )

    fun install(client: OpenCodeClient) {
        client.shellOutput = { _, directory, id, cursor, limit ->
            expect(directory == "/project" && id == "sh_1")
            page(cursor, limit)
        }
        client.shell = { _, _, _ -> shell() }
        client.stopShell = { _, _, _ -> stops += 1 }
    }
}

private fun outputState(status: OpenCodeShell.Status = OpenCodeShell.Status.running) = ShellOutputFeature.State(
    server = server,
    directory = "/project",
    shell = OpenCodeShell(id = "sh_1", command = "bun dev", sessionID = session.id, status = status),
)

/// Delivers a REST window, the way a refresh changes the transcript.
private fun show(
    items: List<OpenCodeTranscriptItem>,
    store: TestStore<SessionFeature.State, SessionFeature.Action>,
    assert: ((SessionFeature.State) -> Unit)? = null,
) {
    store.send(SessionFeature.Action.transcriptResponse(sessionID = session.id, result = Result.success(items))) {
        it.items = items
        assert?.invoke(it)
    }
    store.receive(SessionFeature.Action.acceptedTranscriptUpdated(sessionID = session.id, items = items))
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackgroundWorkTests {
    @get:Rule
    val dependencies = TestDependencies()

    // MARK: Move to Background

    @Test
    fun blockingShellOffersMoveToBackgroundAfterThreeSecondsAndMovesIt() {
        val clock = TestClock()
        val state = SessionFeature.State(server = server, session = session, cachedItems = emptyList())
        state.isExternallyActive = true
        val moved = mutableListOf<String>()
        val listed = OpenCodeShell(id = "sh_1", command = "bun run build --watch", sessionID = session.id, isListed = true)
        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.continuousClock = clock
                it.openCodeClient.moveToBackground = { _, id -> moved += id }
                it.openCodeClient.runningShells = { _, directory ->
                    expect(directory == "/project")
                    listOf(listed)
                }
            },
        ) { SessionFeature() }
        val tool = runningTool()
        show(listOf(tool), store)
        expect(store.state.foregroundWork?.key == "msg_a-tool-call_1")
        clock.advance(by = 2_900.milliseconds)
        expect(!store.state.showsMoveToBackground, "The TUI waits three seconds before offering")
        clock.advance(by = 100.milliseconds)
        store.receive(SessionFeature.Action.backgroundHintElapsed("msg_a-tool-call_1")) { it.movableWorkKey = "msg_a-tool-call_1" }
        expect(store.state.showsMoveToBackground)

        store.send(SessionFeature.Action.moveToBackgroundTapped) { it.movedWorkKeys = setOf("msg_a-tool-call_1") }
        expect(!store.state.showsMoveToBackground, "The offer leaves as soon as it is taken")
        store.receive(SessionFeature.Action.moveToBackgroundFinished(sessionID = session.id, key = "msg_a-tool-call_1", error = null))
        store.receive<SessionFeature.Action.shellsResponse> { it.shells = listOf(listed) }
        expect(moved == listOf(session.id))

        // The server settles the tool; the shell now outlives it in the capsule.
        val settled = runningTool(status = OpenCodeToolCall.Status.completed)
        show(listOf(settled), store) { it.movableWorkKey = null }
        expect(store.state.backgroundShells == listOf(listed))
    }

    @Test
    fun serverStartTimeShortensTheWaitAndSubagentsQualify() {
        val clock = TestClock()
        val now = Instant.ofEpochSecond(1_800_000_000)
        val state = SessionFeature.State(server = server, session = session, cachedItems = emptyList())
        state.isExternallyActive = true
        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.continuousClock = clock
                it.date = DateGenerator.constant(now)
            },
        ) { SessionFeature() }
        val subagent = runningTool(name = "subagent", startedAt = now.minusMillis(2_500).toEpochMilli())
        show(listOf(subagent), store)
        clock.advance(by = 500.milliseconds)
        store.receive(SessionFeature.Action.backgroundHintElapsed("msg_a-tool-call_1")) { it.movableWorkKey = "msg_a-tool-call_1" }

        // A refresh that only learns the start time keeps the offer up.
        val refreshed = runningTool(name = "subagent", startedAt = now.minusMillis(10_000).toEpochMilli())
        show(listOf(refreshed), store)
        expect(store.state.showsMoveToBackground)
    }

    @Test
    fun quickToolsAndIdleSessionsNeverOfferIt() {
        val clock = TestClock()
        val state = SessionFeature.State(server = server, session = session, cachedItems = emptyList())
        state.isExternallyActive = true
        val store = TestStore(
            initialState = { state },
            withDependencies = { it.continuousClock = clock },
        ) { SessionFeature() }
        val tool = runningTool()
        show(listOf(tool), store)
        clock.advance(by = 1.seconds)
        val done = runningTool(status = OpenCodeToolCall.Status.completed)
        show(listOf(done), store)
        clock.advance(by = 5.seconds)

        val read = runningTool(name = "read")
        show(listOf(read), store)
        expect(store.state.foregroundWork == null, "Only shells and subagents can move")

        val idle = SessionFeature.State(server = server, session = session, cachedItems = listOf(runningTool()))
        idle.isExternallyActive = false
        expect(idle.foregroundWork == null, "An idle session has nothing blocking it")
    }

    @Test
    fun failedMoveRestoresTheOffer() {
        val clock = TestClock()
        val state = SessionFeature.State(server = server, session = session, cachedItems = listOf(runningTool()))
        state.isExternallyActive = true
        state.movableWorkKey = "msg_a-tool-call_1"
        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.continuousClock = clock
                it.uuid = UUIDGenerator.incrementing()
                it.openCodeClient.moveToBackground = { _, _ ->
                    throw OpenCodeClientError(message = "Not found", statusCode = 404)
                }
            },
        ) { SessionFeature() }
        store.send(SessionFeature.Action.moveToBackgroundTapped) { it.movedWorkKeys = setOf("msg_a-tool-call_1") }
        val error = OpenCodeClientError(message = "Not found", statusCode = 404)
        store.receive(SessionFeature.Action.moveToBackgroundFinished(sessionID = session.id, key = "msg_a-tool-call_1", error = error)) {
            it.movedWorkKeys = emptySet()
            it.items += OpenCodeTranscriptItem.info(id = "info-00000000-0000-0000-0000-000000000000", text = "Couldn’t move to background: Not found")
        }
        expect(store.state.showsMoveToBackground)
    }

    @Test
    fun tappingAShellOpensItsOutput() {
        val state = SessionFeature.State(server = server, session = session, cachedItems = emptyList())
        val shell = OpenCodeShell(id = "sh_1", command = "bun dev", sessionID = session.id, isListed = true)
        state.shells = listOf(shell)
        val store = TestStore(initialState = { state }) { SessionFeature() }
        store.send(SessionFeature.Action.shellOutputTapped("sh_missing"))
        store.send(SessionFeature.Action.shellOutputTapped("sh_1")) {
            it.shellOutput = ShellOutputFeature.State(server = server, directory = "/project", shell = shell)
        }
    }

    // MARK: Shell output

    @Test
    fun tailsGrowingOutputUntilTheCommandExits() {
        val clock = TestClock()
        val capture = Capture()
        capture.append("$ bun dev\n")
        val store = TestStore(
            initialState = { outputState() },
            withDependencies = {
                it.continuousClock = clock
                capture.install(it.openCodeClient)
            },
        ) { ShellOutputFeature() }
        val task = store.send(ShellOutputFeature.Action.task)
        store.receive(ShellOutputFeature.Action.tailLocated(start = 0)) { it.endCursor = 0 }
        store.receive<ShellOutputFeature.Action.statusReceived>()
        store.receive(ShellOutputFeature.Action.outputReceived(OpenCodeShellOutputPage(output = "$ bun dev\n", cursor = 10, size = 10))) {
            it.output = "$ bun dev\n"
            it.endCursor = 10
        }

        capture.append("ready on :3000\n")
        clock.advance(by = ShellOutputFeature.pollInterval)
        store.receive<ShellOutputFeature.Action.statusReceived>()
        store.receive<ShellOutputFeature.Action.outputReceived> {
            it.output = "$ bun dev\nready on :3000\n"
            it.endCursor = 25
        }

        capture.append("bye\n")
        capture.status = OpenCodeShell.Status.exited
        clock.advance(by = ShellOutputFeature.pollInterval)
        store.receive<ShellOutputFeature.Action.statusReceived> {
            it.shell = it.shell.copy(status = OpenCodeShell.Status.exited, exitCode = 0)
        }
        store.receive<ShellOutputFeature.Action.outputReceived> {
            it.output = "$ bun dev\nready on :3000\nbye\n"
            it.endCursor = 29
        }
        // Exited and drained: the poll ends by itself.
        task.finish()
        expect(!store.state.isRunning)
    }

    @Test
    fun firstReadShowsOnlyTheTailInPages() {
        val capture = Capture()
        capture.append("0123456789abcde\n".repeat(20_000)) // 320,000 bytes
        capture.status = OpenCodeShell.Status.exited
        val store = TestStore(
            initialState = { outputState() },
            withDependencies = {
                it.continuousClock = ImmediateClock()
                capture.install(it.openCodeClient)
            },
        ) { ShellOutputFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        store.send(ShellOutputFeature.Action.task).finish()
        store.skipReceivedActions()
        val start = 320_000 - ShellOutputFeature.initialTailBytes
        expect(store.state.startCursor == start.toLong())
        expect(store.state.endCursor == 320_000L)
        expect(store.state.output.toByteArray(Charsets.UTF_8).size == ShellOutputFeature.initialTailBytes)
        expect(store.state.hasEarlierOutput)
        expect(capture.reads.firstOrNull()?.first == ShellOutputFeature.endOfCapture, "The first read only learns the size")
        expect(capture.reads.drop(1).all { it.second == ShellOutputFeature.pageBytes.toLong() })
    }

    @Test
    fun pollingStopsWhenTheViewGoesAway() {
        val clock = TestClock()
        val capture = Capture()
        capture.append("x\n")
        val store = TestStore(
            initialState = { outputState() },
            withDependencies = {
                it.continuousClock = clock
                capture.install(it.openCodeClient)
            },
        ) { ShellOutputFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        val task = store.send(ShellOutputFeature.Action.task)
        store.receive<ShellOutputFeature.Action.outputReceived>()
        val reads = capture.reads.size
        task.cancel()
        capture.append("more\n")
        clock.advance(by = 10.seconds)
        expect(capture.reads.size == reads)
        expect(store.state.output == "x\n")
    }

    @Test
    fun retainedOutputDropsWholeLinesOffTheFront() {
        val state = outputState()
        val line = "a".repeat(99) + "\n"
        state.output = line.repeat(ShellOutputFeature.retainedBytes / 100)
        state.startCursor = 5_000
        state.endCursor = 5_000 + state.output.toByteArray(Charsets.UTF_8).size.toLong()
        val end = state.endCursor!!
        val store = TestStore(initialState = { state }) { ShellOutputFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        store.send(ShellOutputFeature.Action.outputReceived(OpenCodeShellOutputPage(output = "b".repeat(149) + "\n", cursor = (end + 150).toInt(), size = (end + 150).toInt())))
        expect(store.state.output.toByteArray(Charsets.UTF_8).size <= ShellOutputFeature.retainedBytes)
        expect(store.state.output.startsWith(line), "The cut lands on a line boundary")
        expect(store.state.startCursor + store.state.output.toByteArray(Charsets.UTF_8).size == end + 150)
        // A stale page never re-appends bytes already shown.
        val output = store.state.output
        store.send(ShellOutputFeature.Action.outputReceived(OpenCodeShellOutputPage(output = "dup", cursor = (end + 150).toInt(), size = (end + 150).toInt())))
        expect(store.state.output == output)
    }

    @Test
    fun loadEarlierPrependsTheBytesBeforeTheWindow() {
        val capture = Capture()
        capture.append("e".repeat(300_000))
        val state = outputState(status = OpenCodeShell.Status.exited)
        state.startCursor = 300_000
        state.endCursor = 300_010
        state.output = "tail line\n"
        val store = TestStore(
            initialState = { state },
            withDependencies = { capture.install(it.openCodeClient) },
        ) { ShellOutputFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        store.send(ShellOutputFeature.Action.loadEarlierTapped) { it.isLoadingEarlier = true }
        store.receive<ShellOutputFeature.Action.earlierOutputReceived>()
        val start = 300_000 - ShellOutputFeature.initialTailBytes
        expect(capture.reads.lastOrNull()?.first == start.toLong())
        expect(capture.reads.lastOrNull()?.second == ShellOutputFeature.initialTailBytes.toLong())
        expect(store.state.startCursor == start.toLong())
        expect(store.state.output.endsWith("eeee" + "tail line\n"))
        expect(!store.state.isLoadingEarlier)

        // A page that no longer joins the window is dropped.
        store.send(ShellOutputFeature.Action.earlierOutputReceived(start = 0, result = Result.success(OpenCodeShellOutputPage(output = "zz", cursor = 2, size = 300_000))))
        expect(!store.state.output.startsWith("zz"))
    }

    @Test
    fun stopConfirmsReadsTheLastBytesThenEndsTheShell() {
        val capture = Capture()
        capture.append("serving\n")
        val state = outputState()
        state.output = "serving\n"
        state.endCursor = 8
        val store = TestStore(
            initialState = { state },
            withDependencies = { capture.install(it.openCodeClient) },
        ) { ShellOutputFeature() }
        capture.append("^C\n")
        store.send(ShellOutputFeature.Action.stopTapped) { it.isConfirmingStop = true }
        store.send(ShellOutputFeature.Action.stopConfirmed) {
            it.isConfirmingStop = false
            it.isStopping = true
        }
        store.receive(ShellOutputFeature.Action.outputReceived(OpenCodeShellOutputPage(output = "^C\n", cursor = 11, size = 11))) {
            it.output = "serving\n^C\n"
            it.endCursor = 11
        }
        store.receive(ShellOutputFeature.Action.stopFinished(null)) {
            it.isStopping = false
            it.shell = it.shell.copy(status = OpenCodeShell.Status.killed)
            it.isUnavailable = true
        }
        expect(capture.stops == 1)
        expect(!store.state.isRunning)
        store.send(ShellOutputFeature.Action.stopTapped)
    }

    @Test
    fun failedStopKeepsTheShellRunning() {
        val capture = Capture()
        val state = outputState()
        state.endCursor = 0
        val failure = OpenCodeClientError(message = "Offline", statusCode = 500)
        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.continuousClock = TestClock()
                capture.install(it.openCodeClient)
                it.openCodeClient.stopShell = { _, _, _ -> throw failure }
            },
        ) { ShellOutputFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        store.send(ShellOutputFeature.Action.stopConfirmed) { it.isStopping = true }
        store.receive(ShellOutputFeature.Action.stopFinished(failure)) {
            it.isStopping = false
            it.errorMessage = "Couldn’t stop shell"
        }
        expect(store.state.shell.status == OpenCodeShell.Status.running)
        store.skipInFlightEffects()
    }

    @Test
    fun removedShellIsUnavailable() {
        val store = TestStore(
            initialState = { outputState() },
            withDependencies = {
                it.openCodeClient.shellOutput = { _, _, _, _, _ ->
                    throw OpenCodeClientError(message = "Shell command not found", statusCode = 404)
                }
            },
        ) { ShellOutputFeature() }
        store.send(ShellOutputFeature.Action.task).finish()
        store.receive<ShellOutputFeature.Action.pollFailed> {
            it.isUnavailable = true
            it.endCursor = 0
        }
        expect(!store.state.isRunning)
    }
}
