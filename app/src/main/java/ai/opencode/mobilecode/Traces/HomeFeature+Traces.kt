@file:PortedFrom("OpenCode/Traces/HomeFeature+Traces.swift", "494e433")

package ai.opencode.mobilecode

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

/// The newest session's model.
val HomeFeature.State.lastUsedModel: OpenCodeModelRef?
    get() = sessions.firstOrNull { it.model != null }?.model

private val HomeFeature.State.problemReportModel: OpenCodeModelRef?
    get() = sessionDetail?.currentModel ?: sessionDetail?.session?.model ?: lastUsedModel

/// Fills in where the app is for a trace and turns trouble Home sees (a
/// dropped call, leaving the app) into trace requests. Runs ahead of the
/// traces reducer, which drops them unless Developer Mode is on.
/// Swift nests this in an extension of `HomeFeature`; Kotlin cannot add a nested type from another file.
class TraceTriggers : Reducer<HomeFeature.State, HomeFeature.Action> {
    override fun reduce(state: HomeFeature.State, action: HomeFeature.Action): Effect<HomeFeature.Action> = when {
        action is HomeFeature.Action.traces && action.action is TraceUploadsFeature.Action.traceRequested -> {
            state.traces.context = state.problemReportContext
            Effect.none
        }
        action is HomeFeature.Action.sessionDetail && action.isCallDropped -> {
            if (state.sessionDetail?.voice?.phase?.isTerminal != false) {
                Effect.none
            } else {
                Effect.send(HomeFeature.Action.traces(TraceUploadsFeature.Action.traceRequested(ProblemReportTrigger.callDropped)))
            }
        }
        // Leaving the app: upload the flow so far.
        action == HomeFeature.Action.transcriptCacheFlushRequested ->
            Effect.send(HomeFeature.Action.traces(TraceUploadsFeature.Action.traceRequested(ProblemReportTrigger.snapshot)))
        else -> Effect.none
    }

    private val HomeFeature.Action.sessionDetail.isCallDropped: Boolean
        get() {
            val voice = ((action as? PresentationAction.presented)?.action as? SessionFeature.Action.voice)?.action
            return (voice as? PresentationAction.presented)?.action == VoiceFeature.Action.callDropped
        }
}
