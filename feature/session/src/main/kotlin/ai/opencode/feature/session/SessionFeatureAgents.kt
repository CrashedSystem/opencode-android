package ai.opencode.feature.session

import ai.opencode.core.haptics.Haptic
import ai.opencode.core.log.AppLog
import ai.opencode.core.sharing.Shared
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.cancellable
import ai.opencode.core.strings.localized
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.composer.AgentPickerSharedKeys
import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.AgentDisplayName
import ai.opencode.server.model.OpenCodeAutocompleteAgent
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.time.Duration.Companion.seconds

// Port of iOS `SessionFeature+Agents.swift`. Agent Selection (experimental `agentSelection`,
// OC-511): the composer's agent menu. A pick is remembered per session (`sessionAgents`) and,
// like a model pick, applied (`POST …/agent`) just before the next send; a draft starts with its
// pick, else the Settings default (`defaultAgentID`).

/** iOS `SessionFeature.AgentSelectionState`: Agent Selection's part of the session's state. */
data class AgentSelectionState(
    /** The server's primary agents, as last listed. */
    val primaryAgents: List<OpenCodeAutocompleteAgent> = emptyList(),
    /** The agent a draft starts with, when picked before its first send. */
    val draftAgent: String? = null,
    /** The agent each queued send was sent with, applied when its message starts or Send Now makes it a steer (as `queuedPicks`). */
    val queuedPicks: Map<String, String> = emptyMap(),
    val queuedPickDue: String? = null,
    /**
     * The folder `primaryAgents` were listed from: the chip mounting again (each time the
     * composer expands) asks only for another folder's.
     */
    val listedDirectory: ListedDirectory? = null,
    val sessionAgents: Shared<Map<String, String>> = shared(AgentPickerSharedKeys.sessionAgents),
    val defaultAgentID: Shared<String> = shared(AgentPickerSharedKeys.defaultAgentID),
    val knownPrimaryAgents: Shared<List<OpenCodeAutocompleteAgent>> = shared(AgentPickerSharedKeys.knownPrimaryAgents),
)

/** iOS `SessionFeature.ListedDirectory`: the folder an agents list came from (null: the server's own). */
data class ListedDirectory(val directory: String?)

/** iOS `SessionFeature.agentsAttempts`: tries for the agents list, the first, then retries after 1 s and 2 s. */
internal const val agentsAttempts = 3

/** iOS `reduceAgents(_:_:)`: the agent menu's list, picks and applications. */
internal fun SessionFeature.reduceAgents(state: State, action: Action): Reduced<State, Action> = when (action) {
    Action.AgentsRequested -> {
        if (!experimentalFeatures(ExperimentalFeature.agentSelection)) {
            Reduced(state)
        } else if (state.primaryAgents.isNotEmpty() && state.agentSelection.listedDirectory == ListedDirectory(state.effectiveDirectory)) {
            // Already listed for this folder: the chip shows that list.
            Reduced(state)
        } else {
            val directory = state.effectiveDirectory
            val server = state.server
            val clock = clock
            val client = openCodeClient
            Reduced(
                state,
                Effect.run<Action> { send ->
                    // The chip asks once, when it mounts: a dropped request would leave the menu on
                    // "Loading Agents…" until the app restarts, so a failure retries a few times
                    // before giving up.
                    var attempt = 0
                    while (true) {
                        try {
                            send(Action.AgentsResponse(Result.success(client.primaryAgents(server, directory))))
                            return@run
                        } catch (error: OpenCodeClientError) {
                            attempt += 1
                            if (attempt >= agentsAttempts || !currentCoroutineContext().isActive) {
                                send(Action.AgentsResponse(Result.failure(error)))
                                return@run
                            }
                            clock.sleep((1 shl (attempt - 1)).seconds)
                        }
                    }
                }.cancellable(SessionFeature.CancelID.Agents, cancelInFlight = true),
            )
        }
    }

    is Action.AgentsResponse -> {
        val agents = action.result.getOrNull()
        if (agents != null) {
            // A momentarily empty answer never replaces a list already shown.
            if (agents.isEmpty() && state.primaryAgents.isNotEmpty()) {
                Reduced(state)
            } else {
                var next = state
                if (next.primaryAgents != agents) next = next.withPrimaryAgents(agents)
                val listed = ListedDirectory(next.effectiveDirectory)
                if (agents.isNotEmpty() && next.agentSelection.listedDirectory != listed) {
                    next = next.copy(agentSelection = next.agentSelection.copy(listedDirectory = listed))
                }
                if (agents.isNotEmpty() && next.knownPrimaryAgents != agents) {
                    next.agentSelection.knownPrimaryAgents.set(agents)
                }
                Reduced(next)
            }
        } else {
            val error = action.result.exceptionOrNull()
            val summary = (error as? OpenCodeClientError)?.logSummary ?: error?.let { it::class.java.simpleName } ?: "client error"
            AppLog.network.notice("Couldn’t load agents: $summary")
            Reduced(state)
        }
    }

    is Action.AgentPicked -> {
        val id = action.agent
        if (id == state.currentAgentID) {
            Reduced(state)
        } else {
            haptics.play(Haptic.selection)
            val session = state.session
            if (session != null) {
                state.agentSelection.sessionAgents.update { it + (session.id to id) }
                Reduced(state)
            } else {
                Reduced(state.withDraftAgent(id))
            }
        }
    }

    is Action.AgentApplied -> Reduced(state.agentApplied(action.agent, action.sessionID))

    else -> Reduced(state)
}

/** iOS `waitingAgent(_:)`: the pick a send would apply now, while Agent Selection is on. */
internal fun SessionFeature.waitingAgent(state: State): String? =
    if (experimentalFeatures(ExperimentalFeature.agentSelection)) state.sendAgent else null

/** iOS `applyDueAgentPick(_:)`: a queued send's message started with its agent still waiting. */
internal fun SessionFeature.applyDueAgentPick(state: State): Reduced<State, Action> {
    val agent = state.queuedAgentPickDue ?: return Reduced(state)
    val next = state.withQueuedAgentPickDue(null)
    val session = next.session ?: return Reduced(next)
    return Reduced(next, applyQueuedAgentPick(agent, session.id, next.server))
}

/**
 * iOS `applyQueuedAgentPick(_:sessionID:server:)`: applies a queued send's agent once its message
 * starts or becomes a steer. A refusal leaves the pick waiting, so the next send applies it (and
 * fails, saying why, if it's still refused).
 */
internal fun SessionFeature.applyQueuedAgentPick(agent: String, sessionID: String, server: OpenCodeServer): Effect<Action> {
    val client = openCodeClient
    return Effect.run { send ->
        try {
            client.switchAgent(server, sessionID, agent)
            send(Action.AgentApplied(sessionID, agent))
        } catch (error: OpenCodeClientError) {
            AppLog.send.notice("Queued agent pick not applied: ${error.logSummary}")
        }
    }
}

/** iOS `adoptDraftAgent(_:in:)`: a draft's pick (or the default it started with) becomes its session's. */
internal fun SessionFeature.adoptDraftAgent(sessionID: String, state: State): State {
    val picked = state.draftAgent
    val next = state.withDraftAgent(null)
    if (!experimentalFeatures(ExperimentalFeature.agentSelection)) return next
    val agent = picked ?: next.defaultAgentID.ifEmpty { null } ?: return next
    if (next.sessionAgents[sessionID] != agent) {
        next.agentSelection.sessionAgents.update { it + (sessionID to agent) }
    }
    return next
}

// region `SessionFeature.State` members (iOS `extension SessionFeature.State`)

val State.primaryAgents: List<OpenCodeAutocompleteAgent> get() = agentSelection.primaryAgents
fun State.withPrimaryAgents(value: List<OpenCodeAutocompleteAgent>): State = copy(agentSelection = agentSelection.copy(primaryAgents = value))

val State.draftAgent: String? get() = agentSelection.draftAgent
fun State.withDraftAgent(value: String?): State = copy(agentSelection = agentSelection.copy(draftAgent = value))

val State.queuedAgentPicks: Map<String, String> get() = agentSelection.queuedPicks
fun State.withQueuedAgentPicks(value: Map<String, String>): State = copy(agentSelection = agentSelection.copy(queuedPicks = value))

/** iOS `State.queuedAgentPickDue` (get). */
val State.queuedAgentPickDue: String? get() = agentSelection.queuedPickDue

/** iOS `State.queuedAgentPickDue` (set). */
fun State.withQueuedAgentPickDue(value: String?): State = copy(agentSelection = agentSelection.copy(queuedPickDue = value))

val State.sessionAgents: Map<String, String> get() = agentSelection.sessionAgents.value
val State.defaultAgentID: String get() = agentSelection.defaultAgentID.value
val State.knownPrimaryAgents: List<OpenCodeAutocompleteAgent> get() = agentSelection.knownPrimaryAgents.value

/** The server has [agent] for [sessionID]. The pick stays remembered; it no longer waits once it matches. */
fun State.agentApplied(agent: String, sessionID: String): State {
    var next = this
    val index = familySessions.indexOfFirst { it.id == sessionID }
    if (index >= 0 && familySessions[index].agent != agent) {
        next = next.copy(familySessions = familySessions.toMutableList().also { it[index] = it[index].copy(agent = agent) })
    }
    val session = next.session
    if (session != null && session.id == sessionID && session.agent != agent) next = next.copy(session = session.copy(agent = agent))
    return next
}

/**
 * The agent the composer shows: this session's pick, else the agent the server reports for it; a
 * draft's pick, else the Settings default.
 */
val State.currentAgentID: String?
    get() {
        val session = session
        if (session != null) return sessionAgents[session.id] ?: session.agent ?: primaryAgents.firstOrNull()?.id
        return draftAgent ?: defaultAgentID.ifEmpty { null } ?: primaryAgents.firstOrNull()?.id
    }

val State.currentAgentName: String
    get() {
        val id = currentAgentID
        return primaryAgents.firstOrNull { it.id == id }?.displayName ?: AgentDisplayName.of(id) ?: localized(R.string.session_agent_fallback_name)
    }

/** The agent a send applies first, or null when the session already has it. */
val State.sendAgent: String?
    get() {
        val session = session ?: return draftAgent ?: defaultAgentID.ifEmpty { null }
        val picked = sessionAgents[session.id] ?: return null
        return if (picked != session.agent) picked else null
    }

// endregion
