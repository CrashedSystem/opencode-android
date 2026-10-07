@file:PortedFrom("OpenCode/ShellOutputFeature.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.tca.BindableAction
import ai.opencode.mobilecode.tca.BindingAction
import ai.opencode.mobilecode.tca.BindingReducer
import ai.opencode.mobilecode.tca.Dependency
import ai.opencode.mobilecode.tca.DependencyValues
import ai.opencode.mobilecode.tca.Effect
import ai.opencode.mobilecode.tca.Identifiable
import ai.opencode.mobilecode.tca.ObservableState
import ai.opencode.mobilecode.tca.Reduce
import ai.opencode.mobilecode.tca.Reducer
import ai.opencode.mobilecode.tca.StoreVar
import ai.opencode.mobilecode.tca.cancellable
import ai.opencode.mobilecode.tca.combine
import ai.opencode.mobilecode.tca.continuousClock
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.isActive

/// Live output of one server shell. Polls only while its view is on screen,
/// tails the newest bytes, and keeps a bounded window of the capture.
class ShellOutputFeature : Reducer<ShellOutputFeature.State, ShellOutputFeature.Action> {
    class State(
        server: OpenCodeServer,
        directory: String,
        shell: OpenCodeShell,
    ) : ObservableState(), Identifiable<String> {
        override val id: String get() = shell.id
        var server by StoreVar(server)
        var directory by StoreVar(directory)
        var shell by StoreVar(shell)

        /// The retained output; it ends at `endCursor`.
        var output by StoreVar("")

        /// Absolute byte offset of the first retained byte.
        var startCursor by StoreVar(0L)

        /// The next byte to read; nil until the tail has been located.
        var endCursor by StoreVar<Long?>(null)
        var retainLimit by StoreVar(retainedBytes)
        var isLoadingEarlier by StoreVar(false)
        var isConfirmingStop by StoreVar(false)
        var isStopping by StoreVar(false)

        /// The server no longer has this shell: stopped here or elsewhere, or expired.
        var isUnavailable by StoreVar(false)
        var errorMessage by StoreVar<String?>(null)

        val isLoaded: Boolean get() = endCursor != null
        val isRunning: Boolean get() = shell.status == OpenCodeShell.Status.running && !isUnavailable
        val hasEarlierOutput: Boolean get() = startCursor > 0
        val canLoadEarlier: Boolean get() = hasEarlierOutput && output.utf8Count < maximumRetainedBytes
    }

    sealed interface Action : BindableAction<State> {
        data class binding(override val binding: BindingAction<State>) : Action

        /// Sent by the view for its lifetime; polling stops when it ends.
        data object task : Action

        data class tailLocated(val start: Long) : Action

        data class outputReceived(val page: OpenCodeShellOutputPage) : Action

        data class statusReceived(val shell: OpenCodeShell) : Action

        data class pollFailed(val error: OpenCodeClientError) : Action

        data object loadEarlierTapped : Action

        data class earlierOutputReceived(val start: Long, val result: Result<OpenCodeShellOutputPage>) : Action

        data object stopTapped : Action

        data object stopConfirmed : Action

        data class stopFinished(val error: OpenCodeClientError?) : Action
    }

    private enum class CancelID { poll, earlier, stop }

    private val openCodeClient by Dependency(DependencyValues::openCodeClient)
    private val clock by Dependency(DependencyValues::continuousClock)
    private val haptics by Dependency(DependencyValues::haptics)

    override val body: Reducer<State, Action> =
        combine(
            BindingReducer(),
            Reduce { action ->
                when (action) {
                    is Action.binding -> Effect.none
                    Action.task -> {
                        if (state.isUnavailable || state.isStopping) return@Reduce Effect.none
                        if (state.shell.status != OpenCodeShell.Status.running && state.isLoaded) return@Reduce Effect.none
                        poll(state)
                    }
                    is Action.tailLocated -> {
                        if (state.endCursor != null) return@Reduce Effect.none
                        state.startCursor = action.start
                        state.endCursor = action.start
                        Effect.none
                    }
                    is Action.outputReceived -> {
                        state.errorMessage = null
                        val end = state.endCursor
                        if (end == null || action.page.cursor.toLong() <= end) return@Reduce Effect.none
                        state.output += action.page.output
                        state.endCursor = action.page.cursor.toLong()
                        trimFront(state)
                        Effect.none
                    }
                    is Action.statusReceived -> {
                        state.errorMessage = null
                        state.shell = state.shell.copy(
                            status = action.shell.status,
                            exitCode = action.shell.exitCode,
                            startedAt = action.shell.startedAt ?: state.shell.startedAt,
                            completedAt = action.shell.completedAt,
                        )
                        Effect.none
                    }
                    is Action.pollFailed -> {
                        if (action.error.statusCode == 404) {
                            state.isUnavailable = true
                            state.errorMessage = null
                            if (state.endCursor == null) state.endCursor = 0
                        } else {
                            state.errorMessage = "Couldn’t refresh output"
                        }
                        Effect.none
                    }
                    Action.loadEarlierTapped -> {
                        if (!state.canLoadEarlier || state.isLoadingEarlier || state.isUnavailable) return@Reduce Effect.none
                        val start = maxOf(0L, state.startCursor - initialTailBytes.toLong())
                        val limit = state.startCursor - start
                        state.isLoadingEarlier = true
                        val server = state.server
                        val directory = state.directory
                        val id = state.shell.id
                        Effect.run { send ->
                            try {
                                val page = openCodeClient.shellOutput(server, directory, id, start, limit)
                                send(Action.earlierOutputReceived(start, Result.success(page)))
                            } catch (error: OpenCodeClientError) {
                                send(Action.earlierOutputReceived(start, Result.failure(error)))
                            }
                        }.cancellable(CancelID.earlier, cancelInFlight = true)
                    }
                    is Action.earlierOutputReceived -> {
                        state.isLoadingEarlier = false
                        val page = action.result.getOrNull()
                        if (page != null) {
                            // The front moved while the page was in flight; it no longer joins up.
                            if (page.cursor.toLong() != state.startCursor || action.start >= state.startCursor) return@Reduce Effect.none
                            state.output = page.output + state.output
                            state.startCursor = action.start
                            state.retainLimit = minOf(maximumRetainedBytes, maxOf(state.retainLimit, state.output.utf8Count))
                            return@Reduce Effect.none
                        }
                        val error = action.result.exceptionOrNull() as OpenCodeClientError
                        if (error.statusCode == 404) state.isUnavailable = true
                        else state.errorMessage = "Couldn’t load earlier output"
                        Effect.none
                    }
                    Action.stopTapped -> {
                        if (!state.isRunning || state.isStopping) return@Reduce Effect.none
                        state.isConfirmingStop = true
                        Effect.none
                    }
                    Action.stopConfirmed -> {
                        state.isConfirmingStop = false
                        if (!state.isRunning || state.isStopping) return@Reduce Effect.none
                        state.isStopping = true
                        state.errorMessage = null
                        haptics.impact()
                        val server = state.server
                        val directory = state.directory
                        val id = state.shell.id
                        val cursor = state.endCursor
                        Effect.merge(
                            Effect.cancel(CancelID.poll),
                            Effect.run { send ->
                                // Stopping discards the server's capture: read what's left first.
                                if (cursor != null) {
                                    val page = runCatching {
                                        openCodeClient.shellOutput(server, directory, id, cursor, pageBytes.toLong())
                                    }.getOrNull()
                                    if (page != null) send(Action.outputReceived(page))
                                }
                                try {
                                    openCodeClient.stopShell(server, directory, id)
                                    send(Action.stopFinished(null))
                                } catch (error: OpenCodeClientError) {
                                    send(Action.stopFinished(error))
                                }
                            }.cancellable(CancelID.stop, cancelInFlight = true),
                        )
                    }
                    is Action.stopFinished -> {
                        state.isStopping = false
                        val error = action.error
                        if (error != null && error.statusCode != 404) {
                            haptics.error()
                            state.errorMessage = "Couldn’t stop shell"
                            return@Reduce poll(state)
                        }
                        state.shell = state.shell.copy(status = OpenCodeShell.Status.killed)
                        state.isUnavailable = true
                        Effect.none
                    }
                }
            },
        )

    /// Status first, then every byte up to the capture's current size: output
    /// read after an exit is complete. Repeats while the command runs.
    private fun poll(state: State): Effect<Action> {
        val server = state.server
        val directory = state.directory
        val id = state.shell.id
        val initialCursor = state.endCursor
        val wasRunning = state.shell.status == OpenCodeShell.Status.running
        return Effect.run { send ->
            var cursor = initialCursor
            var isRunning = wasRunning
            while (coroutineContext.isActive) {
                try {
                    if (cursor == null) {
                        val probe = openCodeClient.shellOutput(server, directory, id, endOfCapture, 1)
                        val start = maxOf(0L, probe.size.toLong() - initialTailBytes)
                        send(Action.tailLocated(start))
                        cursor = start
                    }
                    if (isRunning) {
                        val shell = openCodeClient.shell(server, directory, id)
                        send(Action.statusReceived(shell))
                        isRunning = shell.status == OpenCodeShell.Status.running
                    }
                    while (true) {
                        val current = cursor ?: break
                        val page = openCodeClient.shellOutput(server, directory, id, current, pageBytes.toLong())
                        if (page.cursor.toLong() <= current) break
                        send(Action.outputReceived(page))
                        cursor = page.cursor.toLong()
                        if (page.cursor >= page.size) break
                    }
                    if (!isRunning) return@run
                    clock.sleep(pollInterval)
                } catch (error: OpenCodeClientError) {
                    if (!coroutineContext.isActive) return@run
                    send(Action.pollFailed(error))
                    if (error.statusCode == 404) return@run
                    clock.sleep(retryInterval)
                }
            }
        }.cancellable(CancelID.poll, cancelInFlight = true)
    }

    /// Drops the oldest output past the retained window, at a line boundary
    /// when one is near so the first visible line is whole.
    private fun trimFront(state: State) {
        val utf8 = state.output.toByteArray(Charsets.UTF_8)
        val excess = utf8.size - state.retainLimit
        if (excess <= 0) return
        var cut = excess
        val searchEnd = minOf(utf8.size, cut + 4096)
        var newline = -1
        for (index in cut until searchEnd) {
            if (utf8[index] == '\n'.code.toByte()) {
                newline = index
                break
            }
        }
        if (newline >= 0) {
            cut = newline + 1
        } else {
            while (cut < utf8.size && utf8[cut].toInt() and 0xC0 == 0x80) cut++
        }
        state.startCursor += cut
        state.output = String(utf8, cut, utf8.size - cut, Charsets.UTF_8)
    }

    companion object {
        /// One read of the capture file; the server's default page size.
        const val pageBytes = 64 * 1024

        /// How much of an existing capture the first read shows.
        const val initialTailBytes = 256 * 1024

        /// The retained window while tailing; older bytes drop off the front.
        const val retainedBytes = 1024 * 1024

        /// Load Earlier can grow the window up to this much, never further.
        const val maximumRetainedBytes = 4 * 1024 * 1024

        val pollInterval: Duration = 1.seconds
        val retryInterval: Duration = 3.seconds

        /// Asks the server for the capture's size without reading any of it.
        const val endOfCapture = 9_007_199_254_740_991L
    }
}

private val String.utf8Count: Int get() = toByteArray(Charsets.UTF_8).size
