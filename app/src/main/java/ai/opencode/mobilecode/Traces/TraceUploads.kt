@file:PortedFrom("OpenCode/Traces/TraceUploads.swift", "494e433")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.ProcessInfo
import ai.opencode.mobilecode.tca.Dependency
import ai.opencode.mobilecode.tca.DependencyKey
import ai.opencode.mobilecode.tca.DependencyValues
import ai.opencode.mobilecode.tca.Effect
import ai.opencode.mobilecode.tca.ObservableState
import ai.opencode.mobilecode.tca.Reduce
import ai.opencode.mobilecode.tca.Reducer
import ai.opencode.mobilecode.tca.StoreVar
import ai.opencode.mobilecode.tca.date
import ai.opencode.mobilecode.tca.uuid
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/// Developer Mode traces. With Developer Mode on, a shake, a dropped call, a
/// stalled event stream, a crash last run, and leaving the app each upload a
/// trace (the diagnostics timeline, the previous run when it's recent, where
/// the app was, and a screenshot when one is safe) to the log server
/// (`packages/voice-logs`), next to the voice calls. Off, nothing leaves the
/// phone. Replaces Report Problem and Phone Reports.
class TraceUploadsFeature : Reducer<TraceUploadsFeature.State, TraceUploadsFeature.Action> {
    class State(
        /// Where the app is; Home keeps it current.
        context: ProblemReportContext = ProblemReportContext(),
        lastAutomaticAt: Instant? = null,
        /// The newest upload's outcome, for Diagnostics.
        lastResult: LastResult? = null,
    ) : ObservableState() {
        var context by StoreVar(context)
        var lastAutomaticAt by StoreVar(lastAutomaticAt)
        var lastResult by StoreVar(lastResult)

        data class LastResult(
            val trigger: ProblemReportTrigger,
            val at: Instant,
            val uploaded: Boolean,
        )
    }

    sealed interface Action {
        data object task : Action

        data class traceRequested(val trigger: ProblemReportTrigger) : Action

        data class uploaded(val trigger: ProblemReportTrigger, val ok: Boolean) : Action
    }

    private val uuid by Dependency(DependencyValues::uuid)
    private val now by Dependency { it.date.now }
    private val diagnostics by Dependency(DependencyValues::diagnostics)
    private val capture by Dependency(DependencyValues::problemReportCapture)
    private val uploader by Dependency(DependencyValues::traceUploader)
    private val haptics by Dependency(DependencyValues::haptics)

    override val body: Reducer<State, Action> =
        Reduce { action ->
            when (action) {
                Action.task -> {
                    if (!uploader.isEnabled() || !reportsCrashes || !diagnostics.takeUnexpectedEnd()) return@Reduce Effect.none
                    Effect.send(Action.traceRequested(ProblemReportTrigger.crash))
                }

                is Action.traceRequested -> {
                    val trigger = action.trigger
                    if (!uploader.isEnabled()) return@Reduce Effect.none
                    if (trigger != ProblemReportTrigger.shake && trigger != ProblemReportTrigger.crash) {
                        val last = state.lastAutomaticAt
                        if (last != null && Duration.between(last, now).seconds < automaticInterval) return@Reduce Effect.none
                        if (trigger == ProblemReportTrigger.streamStalled && (diagnostics.foregroundSeconds() ?: 0.0) < stallForegroundSeconds) {
                            return@Reduce Effect.none
                        }
                        state.lastAutomaticAt = now
                    }
                    // A shake is someone saying "something went wrong here": confirm it.
                    if (trigger == ProblemReportTrigger.shake) haptics.selection()
                    val id = uuid()
                    val context = state.context
                    val at = now
                    val diagnostics = diagnostics
                    val capture = capture
                    val uploader = uploader
                    Effect.run { send ->
                        // Never drawn in the background or after a crash.
                        val screenshot = if (trigger == ProblemReportTrigger.crash || trigger == ProblemReportTrigger.snapshot) null else capture.screenshot()
                        val bundle = ProblemReportsBuild.make(
                            id = id,
                            trigger = trigger,
                            context = context,
                            now = at,
                            screenshot = screenshot,
                            diagnostics = diagnostics,
                        )
                        send(Action.uploaded(trigger, uploader.upload(bundle)))
                    }
                }

                is Action.uploaded -> {
                    state.lastResult = State.LastResult(trigger = action.trigger, at = now, uploaded = action.ok)
                    Effect.none
                }
            }
        }

    companion object {
        /// A debugger stop or a UI test's terminate looks like a crash, so debug
        /// builds treat one as a crash only when a lab asks.
        val reportsCrashes: Boolean
            get() = if (BuildConfig.DEBUG) {
                ProcessInfo.processInfo.arguments.contains("-lab-crash-report")
            } else {
                true
            }

        /// Automatic traces at most this often, so a flapping connection doesn't
        /// upload a trace a minute. A shake or a crash always uploads.
        const val automaticInterval: Long = 300

        /// A stalled stream counts only after this long in the foreground: a
        /// resumed phone's timed-out socket isn't a stall.
        const val stallForegroundSeconds: Double = 120.0
    }
}

object ProblemReportsBuild {
    /// Off the main actor: the timeline and the previous run can be a few hundred KB.
    suspend fun make(
        id: UUID,
        trigger: ProblemReportTrigger,
        context: ProblemReportContext,
        now: Instant,
        screenshot: ByteArray?,
        diagnostics: DiagnosticsClient,
    ): ProblemReportBundle = withContext(Dispatchers.Default) {
        val device = ProblemReportDevice.current.copy(liveActivities = diagnostics.liveActivities())
        ProblemReportBuilder.make(
            id = id,
            trigger = trigger,
            status = ProblemReport.Status.pending,
            context = context,
            device = device,
            now = now,
            events = diagnostics.timeline(),
            previousRun = diagnostics.previousRunEvents(),
            screenshot = screenshot,
        )
    }
}

/// Uploads a trace under this phone's Developer Mode owner key.
class TraceUploader(
    var isEnabled: () -> Boolean,
    var upload: suspend (ProblemReportBundle) -> Boolean,
) {
    companion object : DependencyKey<TraceUploader> {
        override val liveValue
            get() = TraceUploader(
                isEnabled = { DeveloperMode.isEnabled },
                upload = { bundle -> DeveloperMode.upload(bundle) },
            )

        override val testValue get() = TraceUploader(isEnabled = { false }, upload = { false })
    }
}

var DependencyValues.traceUploader: TraceUploader
    get() = this[TraceUploader]
    set(value) {
        this[TraceUploader] = value
    }

/// The app window at the moment of a trace, as a JPEG.
class ProblemReportCapture(
    var screenshot: suspend () -> ByteArray?,
) {
    companion object : DependencyKey<ProblemReportCapture> {
        override val liveValue get() = ProblemReportCapture(screenshot = { WindowSnapshot.jpeg() })
        override val testValue get() = ProblemReportCapture(screenshot = { null })
    }
}

var DependencyValues.problemReportCapture: ProblemReportCapture
    get() = this[ProblemReportCapture]
    set(value) {
        this[ProblemReportCapture] = value
    }
