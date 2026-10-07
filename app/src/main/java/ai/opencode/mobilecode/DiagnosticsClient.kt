@file:PortedFrom("OpenCode/DiagnosticsClient.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.tca.DependencyKey
import ai.opencode.mobilecode.tca.DependencyValues
import java.util.concurrent.atomic.AtomicBoolean

/// Reducers' window onto `DiagnosticsRecorder.shared`. Tests get a no-op.
class DiagnosticsClient(
    var voice: (String, String?, String?, String?) -> Unit,
    /// The current run's retained window, oldest first.
    var timeline: () -> List<DiagnosticsEvent>,
    /// True once per launch when the previous run ended unexpectedly in the foreground.
    var takeUnexpectedEnd: () -> Boolean,
    var previousRunEvents: () -> List<DiagnosticsEvent>,
    var foregroundSeconds: () -> Double?,
    /// Whether Settings allows Live Activities and what the call's last did.
    var liveActivities: () -> String?,
) {
    companion object : DependencyKey<DiagnosticsClient> {
        override val liveValue
            get() = DiagnosticsClient(
                voice = { name, sessionID, detail, excerpt ->
                    DiagnosticsRecorder.shared.recordVoice(name, sessionID = sessionID, detail = detail, excerpt = excerpt)
                },
                timeline = { DiagnosticsRecorder.shared.snapshot() },
                takeUnexpectedEnd = {
                    val ended = DiagnosticsRecorder.shared.previousRun?.endedUnexpectedly ?: false
                    ended && unexpectedEndTaken.getAndSet(true).not()
                },
                previousRunEvents = { DiagnosticsRecorder.shared.previousRunEvents() },
                foregroundSeconds = { DiagnosticsRecorder.shared.foregroundSeconds },
                liveActivities = { LiveActivityStatus.current.withLock { it.summary } },
            )

        override val testValue
            get() = DiagnosticsClient(
                voice = { _, _, _, _ -> },
                timeline = { emptyList() },
                takeUnexpectedEnd = { false },
                previousRunEvents = { emptyList() },
                foregroundSeconds = { null },
                liveActivities = { null },
            )
    }
}

private val unexpectedEndTaken = AtomicBoolean(false)

var DependencyValues.diagnostics: DiagnosticsClient
    get() = this[DiagnosticsClient]
    set(value) {
        this[DiagnosticsClient] = value
    }
