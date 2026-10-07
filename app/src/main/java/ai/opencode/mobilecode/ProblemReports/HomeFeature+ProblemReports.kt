@file:PortedFrom("OpenCode/ProblemReports/HomeFeature+ProblemReports.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.tca.Dependency
import ai.opencode.mobilecode.tca.DependencyKey
import ai.opencode.mobilecode.tca.DependencyValues
import ai.opencode.mobilecode.tca.Effect
import ai.opencode.mobilecode.tca.PresentationAction
import ai.opencode.mobilecode.tca.Reducer

/// Where the app is, for a report. The server is its origin only.
val HomeFeature.State.problemReportContext: ProblemReportContext
    get() {
        val detail = sessionDetail
        val session = detail?.session
        val screen = if (catchUp != null) {
            "Catch Up"
        } else if (detail != null) {
            if (detail.isDraft) "New session" else if (detail.familyRootID?.let { it != session?.id } == true) "Subagent session" else "Session"
        } else {
            "Home"
        }
        val connection = when (connectionStatus) {
            HomeFeature.State.ConnectionStatus.connected -> "Connected"
            HomeFeature.State.ConnectionStatus.reconnecting -> "Reconnecting"
            HomeFeature.State.ConnectionStatus.disconnected -> connectionErrorMessage?.let { "Offline: $it" } ?: "Offline"
        }
        val call = detail?.voice?.let { voice ->
            val parts = mutableListOf(voice.statusText)
            if (voice.isReconnecting && voice.statusText != "Reconnecting…") parts += "reconnecting"
            if (voice.pendingWork.isNotEmpty()) parts += if (voice.pendingWork.size == 1) "1 request working" else "${voice.pendingWork.size} requests working"
            if (voice.isMuted) parts += "muted"
            parts.joinToString(" · ")
        }
        return ProblemReportContext(
            server = ProblemReportBuilder.redactedServer(server.url),
            screen = screen,
            sessionID = session?.id,
            sessionTitle = session?.title,
            project = session?.let { session -> projects.firstOrNull { it.id == session.projectID }?.name },
            connection = connection,
            call = call,
            model = problemReportModel,
        )
    }

/// The session the report is about, as its composer has it (a draft's
/// pick included), else the model last used.
private val HomeFeature.State.problemReportModel: OpenCodeModelRef?
    get() = sessionDetail?.currentModel ?: sessionDetail?.session?.model ?: lastUsedModel

/// The newest session's model, never Phone Reports': reports are the app
/// talking, not you, and it may sit on the server's default.
val HomeFeature.State.lastUsedModel: OpenCodeModelRef?
    get() {
        val reports = problemReports.destinations[server.persistenceID]?.sessionID
        return sessions.firstOrNull { it.model != null && it.id != reports }?.model
    }

/// Keeps reports pointed at this server and its projects, fills in the
/// context a report captures, turns trouble Home sees into offers, and
/// opens Phone Reports.
/// Runs ahead of the reports reducer.
/// Swift nests this in an extension of `HomeFeature`; Kotlin cannot add a nested type from another file.
class ProblemReportTriggers : Reducer<HomeFeature.State, HomeFeature.Action> {
    private val isEnabled by Dependency(DependencyValues::problemReportsEnabled)
    private val openCodeClient by Dependency(DependencyValues::openCodeClient)

    override fun reduce(state: HomeFeature.State, action: HomeFeature.Action): Effect<HomeFeature.Action> {
        if (!isEnabled) return Effect.none
        if (state.problemReports.server != state.server) state.problemReports.server = state.server
        if (state.problemReports.projects != state.projects) state.problemReports.projects = state.projects
        val reports = (action as? HomeFeature.Action.problemReports)?.action
        return when {
            reports is ProblemReportsFeature.Action.reportRequested || reports is ProblemReportsFeature.Action.troubleDetected -> {
                state.problemReports.context = state.problemReportContext
                Effect.none
            }
            action is HomeFeature.Action.sessionDetail && action.isCallDropped -> {
                if (state.sessionDetail?.voice?.phase?.isTerminal != false) return Effect.none
                Effect.send(HomeFeature.Action.problemReports(ProblemReportsFeature.Action.troubleDetected(ProblemReportTrigger.callDropped)))
            }
            action is HomeFeature.Action.serverConnected ->
                Effect.send(HomeFeature.Action.problemReports(ProblemReportsFeature.Action.serverReachable))
            reports is ProblemReportsFeature.Action.delegate && reports.action is ProblemReportsFeature.Delegate.openSession -> {
                val sessionID = (reports.action as ProblemReportsFeature.Delegate.openSession).sessionID
                if (state.sessions.any { it.id == sessionID }) return Effect.send(HomeFeature.Action.sessionTapped(sessionID))
                // The drawer lists only recent sessions.
                val server = state.server
                val openCodeClient = openCodeClient
                Effect.run { send ->
                    val session = try {
                        openCodeClient.getSession(server, sessionID)
                    } catch (_: OpenCodeClientError) {
                        return@run
                    }
                    send(HomeFeature.Action.problemReports(ProblemReportsFeature.Action.delegate(ProblemReportsFeature.Delegate.openFetchedSession(session))))
                }
            }
            reports is ProblemReportsFeature.Action.delegate && reports.action is ProblemReportsFeature.Delegate.openFetchedSession -> {
                val session = (reports.action as ProblemReportsFeature.Delegate.openFetchedSession).session
                if (!state.sessions.any { it.id == session.id }) state.sessions = listOf(session) + state.sessions
                Effect.send(HomeFeature.Action.sessionTapped(session.id))
            }
            else -> Effect.none
        }
    }

    private val HomeFeature.Action.sessionDetail.isCallDropped: Boolean
        get() {
            val voice = ((action as? PresentationAction.presented)?.action as? SessionFeature.Action.voice)?.action
            return (voice as? PresentationAction.presented)?.action == VoiceFeature.Action.callDropped
        }
}

/// Reports are wired into Home in debug and TestFlight builds; Home's own
/// tests leave them out so their exhaustive stores see only Home.
object ProblemReportsEnabledKey : DependencyKey<Boolean> {
    override val liveValue: Boolean get() = InternalBuild.isEnabled
    override val testValue: Boolean get() = false
}

var DependencyValues.problemReportsEnabled: Boolean
    get() = this[ProblemReportsEnabledKey]
    set(value) {
        this[ProblemReportsEnabledKey] = value
    }
