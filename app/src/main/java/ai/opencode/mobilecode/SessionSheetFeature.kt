@file:PortedFrom("OpenCode/SessionSheetFeature.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.tca.BindableAction
import ai.opencode.mobilecode.tca.BindingAction
import ai.opencode.mobilecode.tca.BindingReducer
import ai.opencode.mobilecode.tca.Dependency
import ai.opencode.mobilecode.tca.DependencyValues
import ai.opencode.mobilecode.tca.Effect
import ai.opencode.mobilecode.tca.ObservableState
import ai.opencode.mobilecode.tca.Reduce
import ai.opencode.mobilecode.tca.Reducer
import ai.opencode.mobilecode.tca.StoreVar
import ai.opencode.mobilecode.tca.cancellable
import ai.opencode.mobilecode.tca.combine
import ai.opencode.mobilecode.tca.continuousClock
import ai.opencode.mobilecode.tca.uuid
import java.io.File
import kotlin.time.Duration.Companion.seconds

/// The Session sheet's own work: rename, compact, fork, export, and the
/// confirmations around them. What the sheet shows (title, model, context,
/// cost) is read from the session; list-level actions are delegated to Home.
class SessionSheetFeature : Reducer<SessionSheetFeature.State, SessionSheetFeature.Action> {
    class State(
        server: OpenCodeServer,
        sessionID: String,
    ) : ObservableState() {
        var server by StoreVar(server)
        var sessionID by StoreVar(sessionID)
        var renameDraft by StoreVar("")
        var isRenaming by StoreVar(false)
        var operation by StoreVar<Operation?>(null)
        var confirmsDeletion by StoreVar(false)
        var exportedFile by StoreVar<ExportedFile?>(null)
        var failure by StoreVar<Failure?>(null)

        /// Auto Rename failed: the title stays and says so briefly, no alert.
        var autoRenameFailed by StoreVar(false)
    }

    sealed interface Operation {
        data object renaming : Operation

        data object compacting : Operation

        data object forking : Operation

        /// The server is writing a title; the title shimmers until it arrives.
        data object autoRenaming : Operation

        data class exporting(val format: OpenCodeExportFormat) : Operation
    }

    data class ExportedFile(val url: File) {
        val id: File get() = url
    }

    data class Failure(val title: String, val message: String)

    sealed interface Action : BindableAction<State> {
        data class binding(override val binding: BindingAction<State>) : Action

        data class renameTapped(val currentTitle: String) : Action

        data object renameSubmitted : Action

        /// The server writes a title from the conversation, as for a new session.
        data object autoRenameTapped : Action

        data class renameFinished(val title: String, val error: OpenCodeClientError?, val sessionID: String) : Action

        data object autoRenameFailureCleared : Action

        data object compactTapped : Action

        data class compactFinished(val error: OpenCodeClientError?) : Action

        data object forkTapped : Action

        data class forkFinished(val result: Result<OpenCodeSession>) : Action

        data class exportTapped(val format: OpenCodeExportFormat, val title: String) : Action

        data class exportFinished(val result: Result<File>) : Action

        data object exportDismissed : Action

        data object pinTapped : Action

        data object readTapped : Action

        data object deleteTapped : Action

        data object deleteConfirmed : Action

        data object failureDismissed : Action

        data class delegate(val action: SessionSheetFeature.Delegate) : Action
    }

    /// Swift nests this in `Action`. It lives here because `delegate` and `Delegate` are one file on a case-insensitive disk.
    sealed interface Delegate {
        data class renamed(val title: String) : Delegate

        data object compacted : Delegate

        data class forked(val session: OpenCodeSession) : Delegate

        data object togglePin : Delegate

        data object toggleRead : Delegate

        data object deleteSession : Delegate
    }

    private val openCodeClient by Dependency(DependencyValues::openCodeClient)
    private val uuid by Dependency(DependencyValues::uuid)
    private val haptics by Dependency(DependencyValues::haptics)
    private val clock by Dependency(DependencyValues::continuousClock)

    override val body: Reducer<State, Action> =
        combine(
            BindingReducer(),
            Reduce { action ->
                when (action) {
                    is Action.binding, is Action.delegate -> Effect.none
                    is Action.renameTapped -> {
                        if (state.operation != null) return@Reduce Effect.none
                        state.renameDraft = action.currentTitle
                        state.isRenaming = true
                        Effect.none
                    }
                    Action.renameSubmitted -> {
                        state.isRenaming = false
                        val title = state.renameDraft.trim()
                        if (state.operation != null || title.isEmpty()) return@Reduce Effect.none
                        state.operation = Operation.renaming
                        val server = state.server
                        val sessionID = state.sessionID
                        Effect.run { send ->
                            try {
                                openCodeClient.renameSession(server, sessionID, title)
                                send(Action.renameFinished(title, null, sessionID))
                            } catch (error: OpenCodeClientError) {
                                send(Action.renameFinished(title, error, sessionID))
                            }
                        }
                    }
                    Action.autoRenameTapped -> {
                        state.isRenaming = false
                        if (state.operation != null) return@Reduce Effect.none
                        state.operation = Operation.autoRenaming
                        state.autoRenameFailed = false
                        val server = state.server
                        val sessionID = state.sessionID
                        Effect.run { send ->
                            try {
                                // An empty title asks the server to generate one; it answers once
                                // the new title is saved.
                                openCodeClient.renameSession(server, sessionID, "")
                                val session = openCodeClient.getSession(server, sessionID)
                                send(Action.renameFinished(session.title, null, sessionID))
                            } catch (error: OpenCodeClientError) {
                                send(Action.renameFinished("", error, sessionID))
                            }
                        }
                    }
                    is Action.renameFinished -> {
                        // A rename for a session this sheet no longer shows isn't its business.
                        if (action.sessionID != state.sessionID) return@Reduce Effect.none
                        val wasAuto = state.operation == Operation.autoRenaming
                        state.operation = null
                        val error = action.error
                        if (error != null && wasAuto) {
                            // The old title stays; the line under it says so for a moment.
                            state.autoRenameFailed = true
                            haptics.error()
                            return@Reduce Effect.run { send ->
                                clock.sleep(2.2.seconds)
                                send(Action.autoRenameFailureCleared)
                            }.cancellable(CancelID.autoRenameFailure, cancelInFlight = true)
                        }
                        if (error != null) return@Reduce fail(state, "Couldn’t Rename Session", error)
                        Effect.send(Action.delegate(Delegate.renamed(action.title)))
                    }
                    Action.autoRenameFailureCleared -> {
                        state.autoRenameFailed = false
                        Effect.none
                    }
                    Action.compactTapped -> {
                        if (state.operation != null) return@Reduce Effect.none
                        state.operation = Operation.compacting
                        val server = state.server
                        val sessionID = state.sessionID
                        Effect.run { send ->
                            try {
                                openCodeClient.compactSession(server, sessionID)
                                send(Action.compactFinished(null))
                            } catch (error: OpenCodeClientError) {
                                send(Action.compactFinished(error))
                            }
                        }
                    }
                    is Action.compactFinished -> {
                        state.operation = null
                        val error = action.error
                        if (error != null) return@Reduce fail(state, "Couldn’t Compact Session", error)
                        haptics.success()
                        Effect.send(Action.delegate(Delegate.compacted))
                    }
                    Action.forkTapped -> {
                        if (state.operation != null) return@Reduce Effect.none
                        state.operation = Operation.forking
                        val server = state.server
                        val sessionID = state.sessionID
                        Effect.run { send ->
                            try {
                                send(Action.forkFinished(Result.success(openCodeClient.forkSession(server, sessionID))))
                            } catch (error: OpenCodeClientError) {
                                send(Action.forkFinished(Result.failure(error)))
                            }
                        }
                    }
                    is Action.forkFinished -> {
                        val session = action.result.getOrNull()
                        if (session != null) {
                            state.operation = null
                            return@Reduce Effect.send(Action.delegate(Delegate.forked(session)))
                        }
                        fail(state, "Couldn’t Fork Session", action.result.exceptionOrNull() as OpenCodeClientError)
                    }
                    is Action.exportTapped -> {
                        if (state.operation != null) return@Reduce Effect.none
                        state.operation = Operation.exporting(action.format)
                        val server = state.server
                        val sessionID = state.sessionID
                        val format = action.format
                        val directory = File(sessionExportRoot(), "Exports/${uuid()}")
                        val url = File(directory, SessionExport.fileName(action.title, format))
                        Effect.run { send ->
                            try {
                                val body = openCodeClient.exportSession(server, sessionID)
                                try {
                                    val content = SessionExport.content(body, format)
                                    directory.mkdirs()
                                    writeAtomic(url, content)
                                } catch (_: Exception) {
                                    throw OpenCodeClientError(message = "The export couldn’t be saved.")
                                }
                                send(Action.exportFinished(Result.success(url)))
                            } catch (error: OpenCodeClientError) {
                                send(Action.exportFinished(Result.failure(error)))
                            }
                        }
                    }
                    is Action.exportFinished -> {
                        val url = action.result.getOrNull()
                        if (url != null) {
                            state.operation = null
                            state.exportedFile = ExportedFile(url)
                            return@Reduce Effect.none
                        }
                        fail(state, "Couldn’t Export Session", action.result.exceptionOrNull() as OpenCodeClientError)
                    }
                    Action.exportDismissed -> {
                        val file = state.exportedFile ?: return@Reduce Effect.none
                        state.exportedFile = null
                        Effect.run<Action> { _ -> file.url.parentFile?.deleteRecursively() }
                    }
                    Action.pinTapped -> Effect.send(Action.delegate(Delegate.togglePin))
                    Action.readTapped -> Effect.send(Action.delegate(Delegate.toggleRead))
                    Action.deleteTapped -> {
                        state.confirmsDeletion = true
                        Effect.none
                    }
                    Action.deleteConfirmed -> {
                        state.confirmsDeletion = false
                        Effect.send(Action.delegate(Delegate.deleteSession))
                    }
                    Action.failureDismissed -> {
                        state.failure = null
                        Effect.none
                    }
                }
            },
        )

    enum class CancelID { autoRenameFailure }

    private fun fail(state: State, title: String, error: OpenCodeClientError): Effect<Action> {
        state.operation = null
        state.failure = Failure(title, error.message)
        haptics.error()
        return Effect.none
    }
}

internal fun sessionExportRoot(): File = OpenCodeApp.instance?.cacheDir ?: File(System.getProperty("java.io.tmpdir") ?: "/tmp")

private fun writeAtomic(url: File, content: ByteArray) {
    val temporary = File(url.parentFile, "${url.name}.tmp")
    temporary.writeBytes(content)
    if (!temporary.renameTo(url)) {
        url.writeBytes(content)
        temporary.delete()
    }
}
