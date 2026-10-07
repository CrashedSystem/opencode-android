package ai.opencode.mobilecode

import ai.opencode.mobilecode.tca.TestClock
import ai.opencode.mobilecode.tca.continuousClock
import ai.opencode.mobilecode.tca.TestDependencies
import ai.opencode.mobilecode.tca.TestStore
import ai.opencode.mobilecode.tca.expect
import kotlin.time.Duration.Companion.seconds
import org.junit.Rule
import org.junit.Test

class ShellOutputTests {
    @get:Rule
    val dependencies = TestDependencies()

    private val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")

    private fun shell(status: OpenCodeShell.Status = OpenCodeShell.Status.running) = OpenCodeShell(
        id = "sh_1",
        command = "bun dev --port 3000",
        status = status,
        startedAt = 1_000,
    )

    private fun page(output: String, cursor: Int, size: Int = cursor) = OpenCodeShellOutputPage(output, cursor, size)

    private fun store(
        shell: OpenCodeShell = shell(),
        prepare: ShellOutputFeature.State.() -> Unit = {},
        shellStatus: suspend (OpenCodeServer, String, String) -> OpenCodeShell = { _, _, _ -> shell },
        shellOutput: suspend (OpenCodeServer, String, String, Long, Long) -> OpenCodeShellOutputPage = { _, _, _, _, _ ->
            throw OpenCodeClientError(message = "unimplemented")
        },
        stopShell: suspend (OpenCodeServer, String, String) -> Unit = { _, _, _ -> },
        clock: TestClock? = null,
        played: MutableList<String>? = null,
    ) = TestStore(
        initialState = { ShellOutputFeature.State(server, "/work", shell).apply(prepare) },
        withDependencies = {
            it.openCodeClient = testOpenCodeClient(shell = shellStatus, shellOutput = shellOutput, stopShell = stopShell)
            if (clock != null) it.continuousClock = clock
            if (played != null) it.haptics = recordingHaptics(played)
        },
    ) { ShellOutputFeature() }

    @Test
    fun firstReadOpensOnTheLast256KBAndDrainsToSize() {
        val size = 300_000
        val start = size - ShellOutputFeature.initialTailBytes
        val exited = shell(OpenCodeShell.Status.exited).copy(exitCode = 0)
        val store = store(
            shellStatus = { _, _, _ -> exited },
            shellOutput = { _, _, _, cursor, _ ->
                when (cursor) {
                    ShellOutputFeature.endOfCapture -> page("", size, size)
                    start.toLong() -> page("first", start + ShellOutputFeature.pageBytes, size)
                    else -> page("second", size, size)
                }
            },
        )
        store.send(ShellOutputFeature.Action.task)
        store.receive(ShellOutputFeature.Action.tailLocated(start.toLong())) {
            it.startCursor = start.toLong()
            it.endCursor = start.toLong()
        }
        store.receive(ShellOutputFeature.Action.statusReceived(exited)) {
            it.shell = it.shell.copy(status = OpenCodeShell.Status.exited, exitCode = 0)
        }
        store.receive(ShellOutputFeature.Action.outputReceived(page("first", start + ShellOutputFeature.pageBytes, size))) {
            it.output = "first"
            it.endCursor = (start + ShellOutputFeature.pageBytes).toLong()
        }
        store.receive(ShellOutputFeature.Action.outputReceived(page("second", size, size))) {
            it.output = "firstsecond"
            it.endCursor = size.toLong()
        }
        store.finish()
    }

    @Test
    fun aShortCaptureStartsAtZero() {
        val store = store(
            shell = shell(OpenCodeShell.Status.exited),
            shellOutput = { _, _, _, cursor, _ ->
                if (cursor == ShellOutputFeature.endOfCapture) page("", 40, 40) else page("all", 40, 40)
            },
        )
        store.send(ShellOutputFeature.Action.task)
        store.receive(ShellOutputFeature.Action.tailLocated(0)) {
            it.startCursor = 0
            it.endCursor = 0
        }
        store.receive(ShellOutputFeature.Action.outputReceived(page("all", 40, 40))) {
            it.output = "all"
            it.endCursor = 40
        }
        store.finish()
    }

    @Test
    fun tailingTrimsWholeLinesOffTheFront() {
        val store = store(prepare = {
            endCursor = 0
            retainLimit = 10
        })
        store.send(ShellOutputFeature.Action.outputReceived(page("hello\nworld\nextra\n", 18))) {
            it.errorMessage = null
            it.output = "extra\n"
            it.endCursor = 18
            it.startCursor = 12
        }
    }

    @Test
    fun trimDoesNotSplitACharacterWhenNoNewlineIsNear() {
        val store = store(prepare = {
            endCursor = 0
            retainLimit = 1
        })
        store.send(ShellOutputFeature.Action.outputReceived(page("é", 2))) {
            it.errorMessage = null
            it.output = ""
            it.endCursor = 2
            it.startCursor = 2
        }
    }

    @Test
    fun loadEarlierJoinsOnlyWhenThePageMeetsTheFront() {
        val store = store(
            prepare = {
                output = "later\n"
                startCursor = 8
                endCursor = 14
                retainLimit = 10
            },
            shellOutput = { _, _, _, _, _ -> page("earlier", 8) },
        )
        store.send(ShellOutputFeature.Action.loadEarlierTapped) { it.isLoadingEarlier = true }
        store.receive(ShellOutputFeature.Action.earlierOutputReceived(0, Result.success(page("earlier", 8)))) {
            it.isLoadingEarlier = false
            it.output = "earlierlater\n"
            it.startCursor = 0
            it.retainLimit = 13
        }
    }

    @Test
    fun anEarlierPageThatMissesTheFrontIsDropped() {
        val store = store(prepare = {
            output = "later\n"
            startCursor = 8
            endCursor = 14
        })
        store.send(ShellOutputFeature.Action.earlierOutputReceived(0, Result.success(page("stale", 7)))) {
            it.isLoadingEarlier = false
        }
        expect(store.state.output == "later\n")
        expect(store.state.startCursor == 8L)
    }

    @Test
    fun earlierFailureIsUnavailableOrABanner() {
        val missing = store(prepare = { startCursor = 8; endCursor = 14; output = "later" })
        missing.send(
            ShellOutputFeature.Action.earlierOutputReceived(0, Result.failure(OpenCodeClientError(message = "gone", statusCode = 404))),
        ) {
            it.isLoadingEarlier = false
            it.isUnavailable = true
        }
        expect(missing.state.errorMessage == null)

        val failed = store(prepare = { startCursor = 8; endCursor = 14; output = "later" })
        failed.send(
            ShellOutputFeature.Action.earlierOutputReceived(0, Result.failure(OpenCodeClientError(message = "offline", statusCode = 500))),
        ) {
            it.isLoadingEarlier = false
            it.errorMessage = "Couldn’t load earlier output"
        }
    }

    @Test
    fun loadEarlierStopsAt4MB() {
        val store = store(prepare = {
            output = "x".repeat(ShellOutputFeature.maximumRetainedBytes)
            startCursor = 10
            endCursor = 10L + ShellOutputFeature.maximumRetainedBytes
        })
        store.send(ShellOutputFeature.Action.loadEarlierTapped)
        expect(store.state.isLoadingEarlier == false)
        store.finish()
    }

    @Test
    fun aMissingShellIsUnavailableNotAnErrorBanner() {
        val store = store(shellOutput = { _, _, _, _, _ -> throw OpenCodeClientError(message = "gone", statusCode = 404) })
        store.send(ShellOutputFeature.Action.task)
        store.receive(ShellOutputFeature.Action.pollFailed(OpenCodeClientError(message = "gone", statusCode = 404))) {
            it.isUnavailable = true
            it.errorMessage = null
            it.endCursor = 0
        }
        store.finish()
    }

    @Test
    fun aFailedPollRetriesAfter3Seconds() {
        val clock = TestClock()
        var calls = 0
        val store = store(
            shell = shell(OpenCodeShell.Status.exited),
            clock = clock,
            shellOutput = { _, _, _, cursor, _ ->
                calls += 1
                if (calls == 1) throw OpenCodeClientError(message = "offline", statusCode = 500)
                if (cursor == ShellOutputFeature.endOfCapture) page("", 4, 4) else page("ok", 4, 4)
            },
        )
        store.send(ShellOutputFeature.Action.task)
        store.receive(ShellOutputFeature.Action.pollFailed(OpenCodeClientError(message = "offline", statusCode = 500))) {
            it.errorMessage = "Couldn’t refresh output"
        }
        clock.advance(by = 3.seconds)
        store.receive(ShellOutputFeature.Action.tailLocated(0)) {
            it.startCursor = 0
            it.endCursor = 0
        }
        store.receive(ShellOutputFeature.Action.outputReceived(page("ok", 4, 4))) {
            it.errorMessage = null
            it.output = "ok"
            it.endCursor = 4
        }
        store.finish()
    }

    @Test
    fun stopReadsTheLastPageThenDeletesAndKeepsWhatIsShown() {
        val played = mutableListOf<String>()
        val calls = mutableListOf<String>()
        val clock = TestClock()
        val store = store(
            clock = clock,
            played = played,
            shellOutput = { _, _, _, cursor, _ ->
                when (cursor) {
                    ShellOutputFeature.endOfCapture -> page("", 10, 10)
                    10L -> {
                        calls += "output"
                        page("left\n", 16, 16)
                    }
                    else -> page("shown", 10, 10)
                }
            },
            stopShell = { _, _, id ->
                expect(id == "sh_1")
                calls += "stop"
            },
        )
        store.send(ShellOutputFeature.Action.task)
        store.receive(ShellOutputFeature.Action.tailLocated(0)) {
            it.startCursor = 0
            it.endCursor = 0
        }
        store.receive(ShellOutputFeature.Action.statusReceived(shell())) {
            it.shell = it.shell.copy(completedAt = null)
        }
        store.receive(ShellOutputFeature.Action.outputReceived(page("shown", 10, 10))) {
            it.output = "shown"
            it.endCursor = 10
        }
        store.send(ShellOutputFeature.Action.stopTapped) { it.isConfirmingStop = true }
        store.send(ShellOutputFeature.Action.stopConfirmed) {
            it.isConfirmingStop = false
            it.isStopping = true
            it.errorMessage = null
        }
        store.receive(ShellOutputFeature.Action.outputReceived(page("left\n", 16, 16))) {
            it.output = "shownleft\n"
            it.endCursor = 16
        }
        store.receive(ShellOutputFeature.Action.stopFinished(null)) {
            it.isStopping = false
            it.shell = it.shell.copy(status = OpenCodeShell.Status.killed)
            it.isUnavailable = true
        }
        clock.advance(by = 5.seconds)
        store.finish()
        expect(calls == listOf("output", "stop"))
        expect(played == listOf("impact"))
        expect(store.state.output == "shownleft\n")
    }

    @Test
    fun stopNotFoundKeepsTheOutputWithoutABanner() {
        val store = store(
            prepare = { output = "kept"; endCursor = 4; startCursor = 0 },
            shellOutput = { _, _, _, _, _ -> page("", 4, 4) },
            stopShell = { _, _, _ -> throw OpenCodeClientError(message = "gone", statusCode = 404) },
        )
        store.send(ShellOutputFeature.Action.stopTapped) { it.isConfirmingStop = true }
        store.send(ShellOutputFeature.Action.stopConfirmed) {
            it.isConfirmingStop = false
            it.isStopping = true
            it.errorMessage = null
        }
        store.receive(ShellOutputFeature.Action.outputReceived(page("", 4, 4)))
        store.receive(ShellOutputFeature.Action.stopFinished(OpenCodeClientError(message = "gone", statusCode = 404))) {
            it.isStopping = false
            it.shell = it.shell.copy(status = OpenCodeShell.Status.killed)
            it.isUnavailable = true
        }
        expect(store.state.output == "kept")
        expect(store.state.errorMessage == null)
        store.finish()
    }

    @Test
    fun aFailedStopExplainsAndResumesPolling() {
        val played = mutableListOf<String>()
        val store = store(
            prepare = { output = "kept"; endCursor = 4 },
            played = played,
            shellStatus = { _, _, _ -> shell(OpenCodeShell.Status.exited) },
            shellOutput = { _, _, _, _, _ -> page("", 4, 4) },
            stopShell = { _, _, _ -> throw OpenCodeClientError(message = "busy", statusCode = 500) },
        )
        store.send(ShellOutputFeature.Action.stopConfirmed) {
            it.isStopping = true
            it.errorMessage = null
        }
        store.receive(ShellOutputFeature.Action.outputReceived(page("", 4, 4)))
        store.receive(ShellOutputFeature.Action.stopFinished(OpenCodeClientError(message = "busy", statusCode = 500))) {
            it.isStopping = false
            it.errorMessage = "Couldn’t stop shell"
        }
        store.receive(ShellOutputFeature.Action.statusReceived(shell(OpenCodeShell.Status.exited))) {
            it.errorMessage = null
            it.shell = it.shell.copy(status = OpenCodeShell.Status.exited, completedAt = null)
        }
        expect(played == listOf("impact", "error"))
        expect(store.state.isUnavailable == false)
        store.finish()
    }

    @Test
    fun anIdleLoadedShellDoesNotPoll() {
        val store = store(shell = shell(OpenCodeShell.Status.exited), prepare = { endCursor = 4; output = "done" })
        store.send(ShellOutputFeature.Action.task)
        store.finish()
    }
}
