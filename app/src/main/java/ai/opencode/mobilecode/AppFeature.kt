@file:PortedFrom("OpenCode/AppFeature.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.ProcessInfo
import ai.opencode.mobilecode.tca.BindableAction
import ai.opencode.mobilecode.tca.BindingAction
import ai.opencode.mobilecode.tca.BindingReducer
import ai.opencode.mobilecode.tca.Case
import ai.opencode.mobilecode.tca.Dependency
import ai.opencode.mobilecode.tca.DependencyValues
import ai.opencode.mobilecode.tca.Effect
import ai.opencode.mobilecode.tca.ObservableState
import ai.opencode.mobilecode.tca.Reduce
import ai.opencode.mobilecode.tca.Reducer
import ai.opencode.mobilecode.tca.Shared
import ai.opencode.mobilecode.tca.SharedKey
import ai.opencode.mobilecode.tca.StoreVar
import ai.opencode.mobilecode.tca.cancellable
import ai.opencode.mobilecode.tca.combine
import ai.opencode.mobilecode.tca.ifLet
import ai.opencode.mobilecode.tca.onChange
import ai.opencode.mobilecode.tca.withLock
import java.net.URI
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive

class AppFeature : Reducer<AppFeature.State, AppFeature.Action> {
    class State : ObservableState() {
        var serverURL by StoreVar("")
        var username by StoreVar("opencode")
        var password by StoreVar(developmentServicePassword())

        /// The last manual entry that failed, restored when the form reopens so a
        /// retry starts from what the user typed rather than a blank form.
        var manualEntryDraft by StoreVar<OpenCodeServer?>(null)

        /// The server that was open when the user left it to pick another, so the
        /// server list can offer a way back without choosing anything.
        var returnServer by StoreVar<OpenCodeServer?>(null)
        var connection by StoreVar<Connection>(Connection.idle)
        var connectionGeneration by StoreVar(0)
        var connectionAttempt by StoreVar<ConnectionAttempt?>(null)
        var home by StoreVar<HomeFeature.State?>(null)

        /// Share-extension items whose server is still connecting.
        var pendingShares by StoreVar<List<ReceivedShare>>(emptyList())
        var shareError by StoreVar<String?>(null)

        /// Manual entry is opt-in; first launch presents the pairing action.
        var isEditingServer by StoreVar(false)

        /// The saved entry being edited, replaced only once the edited target loads.
        var editingSavedServer by StoreVar<OpenCodeServer?>(null)
        var savedServer by Shared(SharedKey.savedServer)
        var savedServers by Shared(SharedKey.savedServers)
        var cachedHomeSnapshot by Shared(SharedKey.cachedHomeSnapshot)
        var lastViewedSessionIDs by Shared(SharedKey.lastViewedSessionIDs)

        /// Addresses discovered from a pairing code, tried alongside `serverURL`.
        var alternateURLs by StoreVar<List<String>>(emptyList())

        var isScanningPairingCode by StoreVar(false)

        init {
            if (BuildConfig.DEBUG) {
                applyLabLaunchState()
            }
            val saved = savedServer
            if (saved != null) {
                val cache = cachedHomeSnapshot?.takeIf { it.serverID == saved.persistenceID }
                load(saved)
                home = HomeFeature.State(
                    server = saved,
                    snapshot = cache?.snapshot ?: OpenCodeHomeSnapshot(projects = emptyList(), sessions = emptyList(), activeSessionIDs = emptySet()),
                )
                home?.connectionStatus = HomeFeature.State.ConnectionStatus.reconnecting
                // The restored session must not report request-load failures before
                // Home has heard from the server once; its first snapshot re-enables them.
                home?.sessionDetail?.requestsConnectionIsAvailable = false
            }
        }

        sealed interface Connection {
            data object idle : Connection

            /// Redeeming a one-time `opencode pair` link for a session token.
            data object pairing : Connection

            data object connecting : Connection

            data class loadingHome(val server: OpenCodeServer) : Connection

            data object connected : Connection

            data class failed(
                val message: String,
                val phase: FailureContext.Phase = FailureContext.Phase.connect,
                val context: FailureContext? = null,
            ) : Connection

            /// Left the session list on purpose to pick a server; unlike `idle` this
            /// must not auto-reconnect when the view's task re-runs.
            data object picking : Connection
        }

        val knownServers: List<OpenCodeServer>
            get() = savedServers + listOfNotNull(savedServer)

        /// A blank username field means the server's default user, like the web app.
        val server: OpenCodeServer
            get() = OpenCodeServer(
                url = serverURL,
                username = username.trim().ifEmpty { "opencode" },
                password = password,
                alternateURLs = alternateURLs,
            )

        /// Fills the connection form with a complete target.
        fun load(server: OpenCodeServer) {
            serverURL = server.url
            username = server.username
            password = server.password
            alternateURLs = server.alternateURLs
        }

        val connectingServerID: String?
            get() = when (val phase = connection) {
                Connection.pairing, Connection.connecting -> connectionAttempt?.server?.id
                is Connection.loadingHome -> phase.server.id
                else -> null
            }

        val connectionProgressLabel: String?
            get() = when (connection) {
                Connection.pairing -> "Pairing"
                Connection.connecting -> "Connecting"
                is Connection.loadingHome -> "Loading sessions"
                else -> null
            }

        val errorMessage: String?
            get() = (connection as? Connection.failed)?.message

        val errorContext: FailureContext?
            get() = (connection as? Connection.failed)?.context

        val errorPhase: FailureContext.Phase?
            get() {
                val failed = connection as? Connection.failed ?: return null
                return failed.context?.phase ?: failed.phase
            }

        /// Generated only when copying, using captured safe context, never form
        /// credentials or a timestamp that would make reducer state nondeterministic.
        fun errorReport(appVersion: String, build: String, generatedAt: Instant): String? {
            val message = errorMessage ?: return null
            val lines = mutableListOf(
                "OpenCode connection error",
                "App: OpenCode $appVersion ($build)",
                "Report generated at: ${DateTimeFormatter.ISO_INSTANT.format(generatedAt)}",
            )
            errorContext?.let { context ->
                lines += listOf(
                    "Phase: ${context.phase.raw}",
                    "Server: ${context.target}",
                    "Attempt: ${context.attempt}",
                )
            }
            return (lines + listOf("", "Message:", message)).joinToString("\n")
        }

        private fun applyLabLaunchState() {
            // XCUITest makes swift-sharing storage process-local. The gauntlet passes
            // an encoded production snapshot so cold-process measurements still use
            // the same synchronous restore path as a saved phone launch.
            val environment = ProcessInfo.processInfo.environment
            val address = environment["OPENCODE_LAB_SERVER_URL"] ?: return
            val server = OpenCodeServer(url = address, username = "opencode", password = developmentServicePassword())
            ::savedServer.withLock { value = server }
            ::savedServers.withLock { value = listOf(server) }
            val raw = environment["OPENCODE_LAB_CACHED_HOME_SNAPSHOT"]
            if (raw != null) {
                val cache = runCatching { protocolJson.decodeFromString(CachedHomeSnapshot.serializer(), raw) }.getOrNull()
                if (cache != null) {
                    cache.serverID = server.persistenceID
                    ::cachedHomeSnapshot.withLock { value = cache }
                }
            }
            val sessionID = environment["OPENCODE_LAB_LAST_SESSION_ID"]
            if (sessionID != null) {
                ::lastViewedSessionIDs.withLock {
                    if (sessionID == "-") value -= server.persistenceID else value += (server.persistenceID to sessionID)
                }
            }
        }
    }

    sealed interface Action : BindableAction<State> {
        data class binding(override val binding: BindingAction<State>) : Action

        data object task : Action

        /// Launch or return to the foreground: collect share-extension items.
        data object pendingSharesRequested : Action

        data class sharesReceived(val shares: List<ReceivedShare>) : Action

        data object connectButtonTapped : Action

        data object addServerTapped : Action

        data class editServerTapped(val server: OpenCodeServer) : Action

        data object cancelEditingTapped : Action

        data class serverTapped(val server: OpenCodeServer) : Action

        data object returnToServerTapped : Action

        data class serverDeleted(val server: OpenCodeServer) : Action

        data object scanPairingCodeTapped : Action

        data object scannerDismissed : Action

        data class pairingCodeScanned(val text: String) : Action

        data class pairingRedeemed(val attemptID: Int, val result: Result<OpenCodeServer>) : Action

        data class healthResponse(val attemptID: Int, val result: Result<OpenCodeServer>) : Action

        data class home(val action: HomeFeature.Action) : Action {
            companion object : Case<Action, HomeFeature.Action>({ home(it) }, { (it as? home)?.action })
        }

        data class homeResponse(val attemptID: Int, val result: Result<OpenCodeHomeSnapshot>) : Action
    }

    /// Form edits cannot change the target of work already in flight. A new ID
    /// also distinguishes retries of the same address with different credentials.
    data class ConnectionAttempt(val id: Int, val server: OpenCodeServer)

    /// Safe to display or copy: no full URL, credentials, or raw network payload.
    data class FailureContext(
        val phase: Phase,
        val target: String,
        val attempt: Int,
    ) {
        constructor(server: OpenCodeServer, phase: Phase, attempt: Int) : this(phase, safeTarget(server.url), attempt)

        enum class Phase(val raw: String) {
            pair("Pair"),
            connect("Connect"),
            loadSessions("Load sessions"),
        }

        companion object {
            private val allowed = Regex("""[A-Za-z0-9.\-:\[\]]""")

            fun safeTarget(address: String): String {
                val uri = runCatching { URI(address) }.getOrNull() ?: return invalid
                val scheme = uri.scheme?.lowercase() ?: return invalid
                if (scheme != "http" && scheme != "https") return invalid
                val host = uri.host?.takeIf { it.isNotEmpty() } ?: return invalid
                if (host.any { !allowed.matches(it.toString()) }) return invalid
                if (uri.port != -1 && uri.port !in 1..65535) return invalid
                val hostname = if (host.contains(":") && !host.startsWith("[")) "[$host]" else host
                return if (uri.port == -1) hostname else "$hostname:${uri.port}"
            }

            private const val invalid = "Unavailable (invalid address)"
        }
    }

    private sealed interface CancelID {
        data object health : CancelID

        data object home : CancelID
    }

    private val openCodeClient by Dependency(DependencyValues::openCodeClient)
    private val shareInbox by Dependency(DependencyValues::shareInbox)
    private val voiceLiveActivity by Dependency(DependencyValues::voiceLiveActivity)

    override val body: Reducer<State, Action> =
        combine(
            BindingReducer<State, Action>()
                .onChange(of = { it.server }) { _, _ ->
                    Reduce<State, Action> { _ ->
                        // Dismissing the credential sheet can send unchanged field bindings.
                        // Only an actual edit invalidates the in-flight connection attempt.
                        if (state.connectionAttempt == null) return@Reduce Effect.none
                        // With the form already closed, the edit is a text field flushing its
                        // final value after Connect (the keyboard was still up). Retry with the
                        // completed entry instead of silently abandoning the attempt.
                        if (!state.isEditingServer && state.manualEntryDraft != null) {
                            state.manualEntryDraft = state.server
                            return@Reduce connect(state)
                        }
                        cancelConnection(state)
                    }
                },
            Reduce<State, Action> { action ->
                when (action) {
                    is Action.binding -> Effect.none
                    Action.task -> {
                        // Restored home owns revalidation; never replace its cached list with setup.
                        if (state.connection != State.Connection.idle || state.home != null) return@Reduce Effect.none
                        val saved = state.savedServer ?: return@Reduce Effect.none
                        // With no last-used server, wait for a scan or an explicit selection.
                        state.load(saved)
                        connect(state)
                    }
                    Action.pendingSharesRequested -> {
                        val inbox = shareInbox
                        Effect.run { send ->
                            val shares = inbox.consume()
                            if (shares.isEmpty()) return@run
                            send(Action.sharesReceived(shares))
                        }
                    }
                    is Action.sharesReceived -> {
                        val saved = state.knownServers
                        val known = action.shares.filter { share -> saved.any { it.persistenceID == share.handoff.serverID } }
                        if (known.size < action.shares.size) {
                            state.shareError = "The server you shared to is no longer saved on this device. Connect to it again, then share again."
                            discardStagedFiles(action.shares.filter { share -> known.none { it.handoff.id == share.handoff.id } })
                        }
                        state.pendingShares += known
                        state.pendingShares = state.pendingShares.sortedBy { it.handoff.createdAt }
                        val serverID = state.pendingShares.lastOrNull()?.handoff?.serverID ?: return@Reduce Effect.none
                        if (state.home?.server?.persistenceID == serverID) return@Reduce deliverPendingShares(state)
                        // Shares name the server whose sessions the sheet offered; switch to it.
                        val server = saved.firstOrNull { it.persistenceID == serverID } ?: return@Reduce Effect.none
                        if (state.connectingServerID == server.id) return@Reduce Effect.none
                        selectServer(server, state)
                    }
                    Action.connectButtonTapped -> {
                        state.isEditingServer = false
                        state.serverURL = state.serverURL.trim()
                        val original = state.editingSavedServer
                        if (original != null && state.serverURL != original.url) {
                            // A changed address replaces the old one; stale pairing alternates
                            // must not silently reconnect to the address the user just edited.
                            state.alternateURLs = emptyList()
                        }
                        state.manualEntryDraft = state.server
                        connect(state)
                    }
                    Action.addServerTapped -> {
                        state.isEditingServer = true
                        state.editingSavedServer = null
                        val draft = state.manualEntryDraft
                        if (draft != null) {
                            state.load(draft)
                        } else {
                            state.serverURL = ""
                            state.username = "opencode"
                            state.password = ""
                            state.alternateURLs = emptyList()
                        }
                        cancelConnection(state)
                    }
                    is Action.editServerTapped -> {
                        val cancellation = cancelConnection(state)
                        state.editingSavedServer = action.server
                        state.manualEntryDraft = null
                        state.load(action.server)
                        state.isEditingServer = true
                        cancellation
                    }
                    Action.scanPairingCodeTapped -> {
                        state.isScanningPairingCode = true
                        state.editingSavedServer = null
                        cancelConnection(state)
                    }
                    Action.scannerDismissed -> {
                        state.isScanningPairingCode = false
                        cancelConnection(state)
                    }
                    is Action.pairingCodeScanned -> {
                        state.isScanningPairingCode = false
                        state.isEditingServer = false
                        state.editingSavedServer = null
                        state.manualEntryDraft = null
                        state.home = null
                        val link = OpenCodePairingLink.decode(action.text)
                        if (link == null) {
                            return@Reduce cancelConnection(
                                state,
                                State.Connection.failed(
                                    message = "That isn’t an OpenCode pairing code. Run `opencode pair` on your computer, then scan its QR code or paste the link it prints.",
                                    phase = FailureContext.Phase.pair,
                                ),
                            )
                        }
                        // `opencode pair` reports whatever the service is bound to. If that's
                        // only loopback, no amount of retrying will help — say so precisely.
                        if (link.isLoopback) {
                            return@Reduce cancelConnection(
                                state,
                                State.Connection.failed(message = loopbackPairingMessage, phase = FailureContext.Phase.pair),
                            )
                        }
                        redeem(link, state)
                    }
                    Action.cancelEditingTapped -> {
                        state.isEditingServer = false
                        state.editingSavedServer = null
                        cancelConnection(state)
                    }
                    Action.returnToServerTapped -> {
                        val server = state.returnServer ?: return@Reduce Effect.none
                        selectServer(server, state)
                    }
                    is Action.serverTapped -> selectServer(action.server, state)
                    is Action.serverDeleted -> {
                        if (state.returnServer?.persistenceID == action.server.persistenceID) state.returnServer = null
                        val wasEditingServer = state.editingSavedServer?.persistenceID == action.server.persistenceID
                        if (wasEditingServer) {
                            state.editingSavedServer = null
                            state.isEditingServer = false
                        }
                        if (state.cachedHomeSnapshot?.serverID == action.server.persistenceID) {
                            state::cachedHomeSnapshot.withLock { value = null }
                        }
                        state::savedServers.withLock { value = value.filterNot { it.persistenceID == action.server.persistenceID } }
                        if (state.savedServer?.persistenceID == action.server.persistenceID) {
                            state::savedServer.withLock { value = null }
                        }
                        discardStagedFiles(state.pendingShares.filter { it.handoff.serverID == action.server.persistenceID })
                        state.pendingShares = state.pendingShares.filterNot { it.handoff.serverID == action.server.persistenceID }
                        shareInbox.removeTargets(action.server.persistenceID)
                        if (wasEditingServer || state.connectingServerID == action.server.id) {
                            return@Reduce cancelConnection(state)
                        }
                        Effect.none
                    }
                    is Action.home if action.action == HomeFeature.Action.serverBadgeTapped -> {
                        // Back to the picker; Home is rebuilt from scratch on reconnect since
                        // sessions, projects and the event stream are all server-scoped.
                        state.returnServer = state.home?.server
                        state.home = null
                        cancelConnection(state)
                    }
                    is Action.pairingRedeemed -> {
                        val server = action.result.getOrNull()
                        if (server != null) {
                            if (state.connectionAttempt?.id != action.attemptID || state.connection != State.Connection.pairing) {
                                return@Reduce Effect.none
                            }
                            state.load(server)
                            return@Reduce connect(state)
                        }
                        val attempt = state.connectionAttempt
                        if (attempt == null || attempt.id != action.attemptID || state.connection != State.Connection.pairing) {
                            return@Reduce Effect.none
                        }
                        val error = action.result.exceptionOrNull() as? OpenCodeClientError ?: return@Reduce Effect.none
                        state.connectionAttempt = null
                        state.connection = State.Connection.failed(
                            message = error.message,
                            context = FailureContext(server = attempt.server, phase = FailureContext.Phase.pair, attempt = action.attemptID),
                        )
                        Effect.none
                    }
                    is Action.healthResponse -> {
                        val server = action.result.getOrNull()
                        if (server != null) {
                            if (state.connectionAttempt?.id != action.attemptID || state.connection != State.Connection.connecting) {
                                return@Reduce Effect.none
                            }
                            state.connection = State.Connection.loadingHome(server)
                            // Adopt the complete winning target, not a URL combined with form auth.
                            state.load(server)
                            // An edited entry is replaced only after its sessions load.
                            if (state.editingSavedServer == null) server.remember()
                            val attemptID = action.attemptID
                            val client = openCodeClient
                            return@Reduce Effect.run { send ->
                                try {
                                    LabMarks.mark("home_snapshot_request")
                                    val snapshot = client.homeSnapshot(server)
                                    LabMarks.mark("home_snapshot_response")
                                    send(Action.homeResponse(attemptID, Result.success(snapshot)))
                                } catch (error: OpenCodeClientError) {
                                    send(Action.homeResponse(attemptID, Result.failure(error)))
                                }
                            }.cancellable(CancelID.home, cancelInFlight = true)
                        }
                        val attempt = state.connectionAttempt
                        if (attempt == null || attempt.id != action.attemptID || state.connection != State.Connection.connecting) {
                            return@Reduce Effect.none
                        }
                        val error = action.result.exceptionOrNull() as? OpenCodeClientError ?: return@Reduce Effect.none
                        state.connectionAttempt = null
                        state.connection = State.Connection.failed(
                            message = error.message,
                            context = FailureContext(server = attempt.server, phase = FailureContext.Phase.connect, attempt = action.attemptID),
                        )
                        Effect.none
                    }
                    is Action.homeResponse -> {
                        val snapshot = action.result.getOrNull()
                        val loading = state.connection as? State.Connection.loadingHome
                        if (snapshot != null) {
                            if (state.connectionAttempt?.id != action.attemptID || loading == null) return@Reduce Effect.none
                            val server = loading.server
                            val original = state.editingSavedServer
                            if (original != null) {
                                server.remember(replacing = original)
                                state.editingSavedServer = null
                            }
                            state.home = HomeFeature.State(server = server, snapshot = snapshot)
                            state.home?.persistSnapshot()
                            state.connection = State.Connection.connected
                            state.connectionAttempt = null
                            state.manualEntryDraft = null
                            state.returnServer = null
                            return@Reduce deliverPendingShares(state)
                        }
                        if (state.connectionAttempt?.id != action.attemptID || loading == null) return@Reduce Effect.none
                        val error = action.result.exceptionOrNull() as? OpenCodeClientError ?: return@Reduce Effect.none
                        state.connectionAttempt = null
                        state.editingSavedServer = null
                        state.connection = State.Connection.failed(
                            message = error.message,
                            context = FailureContext(server = loading.server, phase = FailureContext.Phase.loadSessions, attempt = action.attemptID),
                        )
                        Effect.none
                    }
                    is Action.home -> Effect.none
                }
            }
                .ifLet(State::home, Action.home) { HomeFeature() }
                // Leaving Home cancels a call without Home seeing it end, so its Live
                // Activity is ended here.
                .onChange(of = { feature -> feature.home?.let { it.voiceCallActivity != null } }) { old, _ ->
                    Reduce<State, Action> { _ ->
                        if (old != true || state.home != null) return@Reduce Effect.none
                        val activity = voiceLiveActivity
                        Effect.run<Action> { _ -> activity.update(null, null) }
                    }
                },
        )

    /// Hands the open server's shares to Home, which opens the newest target.
    /// Shares that will never reach a composer would otherwise leave their
    /// staged files behind.
    private fun discardStagedFiles(shares: List<ReceivedShare>) {
        shares.flatMap { it.files }.forEach { ComposerFileStaging.discard(it.url) }
    }

    private fun deliverPendingShares(state: State): Effect<Action> {
        val serverID = state.home?.server?.persistenceID ?: return Effect.none
        val ready = state.pendingShares.filter { it.handoff.serverID == serverID }
        if (ready.isEmpty()) return Effect.none
        state.pendingShares = state.pendingShares.filterNot { it.handoff.serverID == serverID }
        return Effect.send(Action.home(HomeFeature.Action.sharesReceived(ready)))
    }

    private fun cancelConnection(
        state: State,
        connection: State.Connection = State.Connection.picking,
    ): Effect<Action> {
        state.connection = connection
        state.connectionAttempt = null
        return Effect.merge(Effect.cancel(CancelID.health), Effect.cancel(CancelID.home))
    }

    private fun selectServer(server: OpenCodeServer, state: State): Effect<Action> {
        state.isScanningPairingCode = false
        state.isEditingServer = false
        state.editingSavedServer = null
        state.load(server)
        return connect(state)
    }

    /// Exchange a one-time pairing link for a token, then connect normally.
    private fun redeem(link: OpenCodePairingLink, state: State): Effect<Action> {
        state.connection = State.Connection.pairing
        state.connectionGeneration += 1
        val attempt = ConnectionAttempt(
            id = state.connectionGeneration,
            server = OpenCodeServer(url = link.origin, username = "opencode", password = "", alternateURLs = link.alternateOrigins),
        )
        state.connectionAttempt = attempt
        val client = openCodeClient
        return Effect.run { send ->
            try {
                val server = client.redeemPairing(link)
                send(Action.pairingRedeemed(attempt.id, Result.success(server)))
            } catch (error: OpenCodeClientError) {
                send(Action.pairingRedeemed(attempt.id, Result.failure(error)))
            }
        }.cancellable(CancelID.health, cancelInFlight = true)
    }

    /// Probe every address concurrently, but save the healthiest external scope
    /// rather than whichever LAN address happened to answer first.
    private fun connect(state: State): Effect<Action> {
        state.connection = State.Connection.connecting
        state.home = null
        state.connectionGeneration += 1
        val attempt = ConnectionAttempt(id = state.connectionGeneration, server = state.server)
        state.connectionAttempt = attempt
        val client = openCodeClient
        val health = Effect.run<Action> { send ->
            var lastError = OpenCodeClientError(message = "The server could not be reached.")
            var didConnect = false
            val pending = HashMap<Int, Int>()
            val healthy = HashMap<Int, OpenCodeServer>()
            coroutineScope {
                val results = Channel<Pair<Int, Result<OpenCodeServer>>>(Channel.UNLIMITED)
                val jobs = attempt.server.candidateURLs.map { url ->
                    val candidate = attempt.server.at(url)
                    val rank = OpenCodeServer.reachabilityRank(url)
                    pending[rank] = (pending[rank] ?: 0) + 1
                    async {
                        try {
                            client.health(candidate)
                            if (isActive) results.send(rank to Result.success(candidate))
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: OpenCodeClientError) {
                            if (isActive) results.send(rank to Result.failure(error))
                        }
                    }
                }
                var received = 0
                while (received < jobs.size && isActive) {
                    val (rank, result) = results.receive()
                    received += 1
                    pending[rank] = (pending[rank] ?: 1) - 1
                    val winner = result.getOrNull()
                    if (winner != null) {
                        if (rank !in healthy) healthy[rank] = winner
                    } else {
                        val error = result.exceptionOrNull() as? OpenCodeClientError
                        if (error != null) lastError = error
                    }
                    val preferredRank = healthy.keys.minOrNull() ?: continue
                    val betterStillRunning = pending.any { (key, count) -> key < preferredRank && count > 0 }
                    val server = healthy[preferredRank]
                    if (betterStillRunning || server == null) continue
                    jobs.forEach { it.cancel() }
                    send(Action.healthResponse(attempt.id, Result.success(server)))
                    didConnect = true
                    break
                }
            }
            if (!didConnect) send(Action.healthResponse(attempt.id, Result.failure(lastError)))
        }.cancellable(CancelID.health, cancelInFlight = true)
        return Effect.merge(Effect.cancel(CancelID.home), health)
    }
}

private const val loopbackPairingMessage = "That server only listens on localhost, so this phone can’t reach it. Run `opencode service set hostname 0.0.0.0` on your computer, then run `opencode pair` again."

private fun developmentServicePassword(): String {
    if (!BuildConfig.DEBUG) return ""
    return ProcessInfo.processInfo.environment["OPENCODE_PASSWORD"] ?: ""
}
