package ai.opencode.core.diagnostics

import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.log.AppLog
import ai.opencode.core.sharing.SharedKey
import ai.opencode.core.sharing.shared
import ai.opencode.core.tuning.DeveloperMode
import ai.opencode.core.tuning.InternalBuild
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Timer
import java.util.UUID
import kotlin.concurrent.schedule
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Port of iOS `MotionWatchEvent`: one jump, pop-in, pop-out or snap caught live by [MotionWatch],
 * with metadata only (never message text) and the 2 s of [MotionFrame]s around it.
 * (iOS `replayContext` belongs to the Mac-driven playgrounds, which Android doesn't have.)
 */
@Serializable
data class MotionWatchEvent(
    @Serializable(with = UuidSerializer::class) val id: UUID,
    @Serializable(with = InstantSerializer::class) val wallDate: Instant,
    val monotonicSeconds: Double,
    val sessionID: String? = null,
    val kind: MotionFinding.Kind,
    val rowKinds: List<String>,
    /** Stable row IDs only (never message text). */
    val rows: List<String>,
    val detail: String,
    val frame: Int,
    val magnitudePt: Double? = null,
    val runningMotions: List<String> = emptyList(),
    /** The 2 s window (`[-1 s, +1 s]`) of frames around [frame]. */
    val frames: List<MotionFrame>,
) {
    val summaryLine: String
        get() {
            val kinds = if (rowKinds.isEmpty()) "transcript" else rowKinds.joinToString(",")
            val ids = if (rows.isEmpty()) "—" else rows.joinToString(",")
            val session = sessionID?.let { " [$it]" } ?: ""
            val mag = magnitudePt?.let { String.format(Locale.ROOT, " · %.1f pt", it) } ?: ""
            return "${kind.rawValue}$session ($kinds: $ids)$mag — $detail (${frames.size}f)"
        }
}

/**
 * Port of iOS `MotionWatch` (`Diagnostics/Traces/MotionWatch.swift`): the live in-app motion
 * detector (Developer Mode › Motion Watch, off by default; internal builds with Developer Mode
 * on). Runs [MotionAuditDetector] on the frames it is given ([ingest]), records jump/pop-in/snap events with 2 s of
 * surrounding frames (no message content), surfaces them in Troubleshooting, and attaches them to
 * problem reports. Zero cost when off: [isActive] is one volatile read.
 */
class MotionWatch(
    enabled: Boolean? = null,
    private val store: Boolean = enabled == null,
    private val recorder: () -> DiagnosticsRecorder = { DiagnosticsRecorder.shared },
    private val monotonicNow: () -> Double = { DiagnosticsRecorder.processElapsedSeconds() },
    private val wallNow: () -> Instant = Instant::now,
) {
    private class PendingCapture(var event: MotionWatchEvent, val eventWall: Int, val endWall: Int)

    private class State(var enabled: Boolean, val isAvailableOverride: Boolean?) {
        val scanner = MotionAuditDetector.Scanner()
        val rollingFrames = ArrayList<MotionFrame>()
        var pending = ArrayList<PendingCapture>()
        val events = ArrayList<MotionWatchEvent>()
    }

    @Volatile private var active = false
    private val lock = Any()
    private val state: State
    private val _changes = MutableStateFlow(0)
    @Volatile private var notifyPending = false

    init {
        val stored = if (store) runCatching { shared(sharedKey).value }.getOrNull() else null
        val initial = enabled ?: (labEnabled || (stored ?: false))
        val overrideAvailable = if (enabled != null) true else null
        state = State(initial, overrideAvailable)
        active = initial && (overrideAvailable ?: isAvailable)
    }

    /** Bumps (coalesced to every 0.25 s) when the jumps change; screens reload on it (iOS `didChange`). */
    val changes: StateFlow<Int> get() = _changes.asStateFlow()

    /** Fast volatile load: no lock or allocation when off. */
    val isActive: Boolean get() = active

    val isEnabled: Boolean get() = synchronized(lock) { state.enabled }

    fun setEnabled(enabled: Boolean) {
        if (store) runCatching { shared(sharedKey).set(enabled) }
        synchronized(lock) {
            state.enabled = enabled
            refreshActiveState(resetBuffer = true, clearEvents = !enabled)
        }
        activeListeners.forEach { it() }
        notifyChange()
    }

    /** Re-evaluates availability when Developer Mode toggles on or off. */
    fun accessChanged() {
        synchronized(lock) { refreshActiveState(resetBuffer = false, clearEvents = false) }
        activeListeners.forEach { it() }
        notifyChange()
    }

    private fun refreshActiveState(resetBuffer: Boolean, clearEvents: Boolean) {
        val nowActive = state.enabled && (state.isAvailableOverride ?: isAvailable)
        active = nowActive
        if (resetBuffer || !nowActive) {
            state.scanner.reset()
            state.rollingFrames.clear()
        }
        if (!nowActive) state.pending.clear()
        if (clearEvents) state.events.clear()
    }

    /** Feeds one display frame into the live detector. */
    fun ingest(frame: MotionFrame, forcedActive: Boolean = false) {
        if (!(isActive || forcedActive)) return
        record(frame, forcedActive)
    }

    private fun record(frame: MotionFrame, forcedActive: Boolean) {
        val now = monotonicNow()
        val wall = wallNow()
        var completed = false
        val created = ArrayList<MotionWatchEvent>()
        synchronized(lock) {
            if (!(active || forcedActive)) return@synchronized
            val previousFrame = state.scanner.previous
            val last = state.rollingFrames.lastOrNull()
            if (last != null && (last.launch != frame.launch || (last.session.isNotEmpty() && frame.session.isNotEmpty() && last.session != frame.session))) {
                // Finalize any pending captures from the previous session before switching.
                for (capture in state.pending) appendEvent(capture.event)
                state.pending.clear()
                state.rollingFrames.clear()
            }
            state.rollingFrames.add(frame)
            val cutoff = frame.wall - ROLLING_BUFFER_MILLISECONDS
            val firstKept = state.rollingFrames.indexOfFirst { it.wall >= cutoff }
            if (firstKept > 0) state.rollingFrames.subList(0, firstKept).clear()
            if (state.rollingFrames.size > MAXIMUM_FRAMES_PER_EVENT) {
                state.rollingFrames.subList(0, state.rollingFrames.size - MAXIMUM_FRAMES_PER_EVENT).clear()
            }

            // Append this frame to any capture still within its +1 s post-window, and finalize
            // any capture whose post-window has elapsed.
            val remaining = ArrayList<PendingCapture>()
            for (capture in state.pending) {
                val sameSession = frame.session == (capture.event.sessionID ?: "") || capture.event.sessionID == null
                if (sameSession && frame.wall <= capture.endWall && capture.event.frames.size < MAXIMUM_FRAMES_PER_EVENT) {
                    capture.event = capture.event.copy(frames = capture.event.frames + frame)
                }
                if (frame.wall >= capture.endWall) {
                    appendEvent(capture.event)
                    completed = true
                } else {
                    remaining += capture
                }
            }
            state.pending = remaining

            val findings = state.scanner.step(frame).filter { it.kind.isError && it.kind != MotionFinding.Kind.Incomplete }
            if (findings.isEmpty()) return@synchronized

            val preCutoff = frame.wall - PRE_WINDOW_MILLISECONDS
            val preFrames = state.rollingFrames
                .filter { it.wall >= preCutoff && it.wall <= frame.wall && (it.session == frame.session || frame.session.isEmpty()) }
                .takeLast(MAXIMUM_FRAMES_PER_EVENT)
            for (finding in findings) {
                val event = MotionWatchEvent(
                    id = UUID.randomUUID(),
                    wallDate = wall,
                    monotonicSeconds = now,
                    sessionID = frame.session.ifEmpty { null },
                    kind = finding.kind,
                    rowKinds = rowKinds(finding.rows, frame, previousFrame),
                    rows = finding.rows,
                    detail = finding.detail,
                    frame = finding.frame,
                    magnitudePt = finding.magnitudePt,
                    runningMotions = finding.runningMotions,
                    frames = preFrames,
                )
                if (state.pending.size >= MAXIMUM_EVENTS) {
                    appendEvent(state.pending.removeAt(0).event)
                    completed = true
                }
                state.pending += PendingCapture(event, frame.wall, frame.wall + POST_WINDOW_MILLISECONDS)
                created += event
            }
        }
        for (event in created) {
            recorder().recordMotionJump(event.kind.rawValue, event.sessionID, event.rowKinds, event.rows, event.detail)
            AppLog.app.info("motion watch jump kind=${event.kind.rawValue} session=${event.sessionID ?: "-"} rows=${event.rows.size}")
        }
        if (created.isNotEmpty() || completed) notifyChange()
    }

    /**
     * Findings from a motion with its own trace and audit (iOS's photo flight): recorded like the
     * transcript's jumps, with no transcript frames. [kind] names the motion; rows are its own IDs.
     */
    fun record(findings: List<MotionFinding>, kind: String) {
        if (!isActive) return
        val errors = findings.filter { it.kind.isError && it.kind != MotionFinding.Kind.Incomplete }
        if (errors.isEmpty()) return
        val now = monotonicNow()
        val wall = wallNow()
        val events = errors.map { finding ->
            MotionWatchEvent(
                id = UUID.randomUUID(), wallDate = wall, monotonicSeconds = now, sessionID = null, kind = finding.kind, rowKinds = listOf(kind),
                rows = finding.rows, detail = finding.detail, frame = finding.frame, magnitudePt = finding.magnitudePt,
                runningMotions = listOf(kind), frames = emptyList(),
            )
        }
        synchronized(lock) { events.forEach(::appendEvent) }
        for (event in events) {
            recorder().recordMotionJump(event.kind.rawValue, null, event.rowKinds, event.rows, event.detail)
            AppLog.app.info("motion watch jump kind=${event.kind.rawValue} motion=$kind rows=${event.rows.size}")
        }
        notifyChange()
    }

    /**
     * Finalizes any pending captures once motion has settled for 1 s+. If no frame changed after
     * the jump itself, appends a resting anchor frame at the end of the post-window so the 2 s
     * trace records the settled pose.
     */
    fun settle(wall: Int) {
        val completed = synchronized(lock) {
            if (state.pending.isEmpty()) return@synchronized false
            val remaining = ArrayList<PendingCapture>()
            var any = false
            for (capture in state.pending) {
                if (wall >= capture.endWall) {
                    val last = capture.event.frames.lastOrNull()
                    if (last != null && last.wall == capture.eventWall && capture.event.frames.size < MAXIMUM_FRAMES_PER_EVENT) {
                        val settled = last.copy(events = emptyList()).also {
                            it.index = last.index + 1
                            it.sequence = last.sequence + 1
                            it.elapsed = max(1, ((capture.endWall - last.wall).toDouble() / max(4.0, last.periodMs)).roundToInt())
                            it.wall = capture.endWall
                        }
                        capture.event = capture.event.copy(frames = capture.event.frames + settled)
                    }
                    appendEvent(capture.event)
                    any = true
                } else {
                    remaining += capture
                }
            }
            state.pending = remaining
            any
        }
        if (completed) notifyChange()
    }

    /** All recorded jumps (including any still gathering their post-frames), oldest first. */
    fun snapshot(): List<MotionWatchEvent> = synchronized(lock) {
        val all = state.events + state.pending.map { it.event }
        if (all.size > MAXIMUM_EVENTS) all.takeLast(MAXIMUM_EVENTS) else all
    }

    fun clear() {
        synchronized(lock) {
            state.scanner.reset()
            state.rollingFrames.clear()
            state.pending.clear()
            state.events.clear()
        }
        notifyChange()
    }

    private fun appendEvent(event: MotionWatchEvent) {
        state.events += event
        if (state.events.size > MAXIMUM_EVENTS) state.events.subList(0, state.events.size - MAXIMUM_EVENTS).clear()
    }

    private fun notifyChange() {
        if (notifyPending) return
        notifyPending = true
        timer.schedule((DiagnosticsRecorder.NOTIFY_INTERVAL * 1000).toLong()) {
            notifyPending = false
            _changes.value += 1
        }
    }

    companion object Key : DependencyKey<MotionWatch> {
        /** iOS `@Shared(.motionWatch)` / `developer-motion-watch`. */
        val sharedKey: SharedKey<Boolean> = SharedKey.appStorage("developer-motion-watch", false)
        /** 1 s before + 1 s after = 2 s around each jump. */
        const val PRE_WINDOW_MILLISECONDS = 1_000
        const val POST_WINDOW_MILLISECONDS = 1_000
        const val ROLLING_BUFFER_MILLISECONDS = 2_500
        const val MAXIMUM_EVENTS = 25
        const val MAXIMUM_FRAMES_PER_EVENT = 260

        private val timer by lazy { Timer("motion-watch-changes", true) }

        /**
         * On for lab runs (iOS `-lab-motion-watch` / `OPENCODE_MOTION_WATCH=1`): the debug
         * `lab-motion-watch` launch extra, set by `DevTools.handleLaunchIntent`.
         */
        @Volatile var labEnabled: Boolean = false
            private set

        val isAvailable: Boolean get() = labEnabled || (InternalBuild.isEnabled && DeveloperMode.isEnabled)

        /** The live watcher (iOS `MotionWatch.shared`). */
        val shared: MotionWatch by lazy { MotionWatch() }

        override val liveValue: MotionWatch get() = shared
        override val testValue: MotionWatch get() = MotionWatch(enabled = false)

        /** Called when [isActive] may have changed (the transcript's frame source starts or stops). */
        val activeListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

        /** The lab extra arrives with the launch intent, after the watcher may exist: turn it on now. */
        fun enableForLab() {
            labEnabled = true
            shared.setEnabledForLab()
        }

        private fun rowKinds(rows: List<String>, frame: MotionFrame, previous: MotionFrame?): List<String> {
            val kinds = ArrayList<String>()
            for (id in rows) {
                val kind = frame.row(id)?.kind ?: previous?.row(id)?.kind
                if (!kind.isNullOrEmpty() && kind !in kinds) kinds += kind
            }
            return kinds
        }

        private val prettyJson = Json { prettyPrint = true; encodeDefaults = false; explicitNulls = false }

        fun exportJSON(events: List<MotionWatchEvent>): String =
            runCatching { prettyJson.encodeToString(ListSerializer(MotionWatchEvent.serializer()), events) }.getOrDefault("[]")

        private val clock = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT).withZone(ZoneOffset.UTC)

        fun exportText(events: List<MotionWatchEvent>): String {
            if (events.isEmpty()) return "OpenCode Motion Watch\n(no jumps recorded)\n"
            val lines = mutableListOf("OpenCode Motion Watch", "Jumps: ${events.size}", "")
            for (event in events) {
                lines += "[${clock.format(event.wallDate)}] f${event.frame} ${event.summaryLine}"
                val first = event.frames.firstOrNull()
                val last = event.frames.lastOrNull()
                if (first != null && last != null) {
                    lines += "  window: ${event.frames.size} frames (${last.wall - first.wall} ms, f${first.index}..f${last.index})"
                }
            }
            return lines.joinToString("\n") + "\n"
        }

        /**
         * The newest jumps (with their 2 s frame traces) whose JSON fits [limit]. Trimming a single
         * jump's frames drops those farthest from its jump frame first.
         */
        fun boundedJSON(events: List<MotionWatchEvent>, limit: Int): ByteArray {
            var kept = events
            var data = exportJSON(kept).toByteArray()
            while (data.size > limit && kept.isNotEmpty()) {
                val only = kept.singleOrNull()
                if (only != null && only.frames.size > 1) {
                    val frames = only.frames.toMutableList()
                    val removeCount = max(1, frames.size / 4)
                    for (i in 0 until removeCount) {
                        if (frames.size <= 1) break
                        val firstDist = abs(frames.first().index - only.frame)
                        val lastDist = abs(frames.last().index - only.frame)
                        if (firstDist >= lastDist && frames.first().index != only.frame) {
                            frames.removeAt(0)
                        } else if (frames.last().index != only.frame) {
                            frames.removeAt(frames.size - 1)
                        } else {
                            break
                        }
                    }
                    kept = listOf(only.copy(frames = frames))
                } else {
                    kept = kept.drop(max(1, kept.size / 2))
                }
                data = exportJSON(kept).toByteArray()
            }
            return data
        }
    }

    /** iOS's initial `enabled = labEnabled || stored` for a lab launch, without saving the switch. */
    private fun setEnabledForLab() {
        synchronized(lock) {
            state.enabled = true
            refreshActiveState(resetBuffer = false, clearEvents = false)
        }
        activeListeners.forEach { it() }
        notifyChange()
    }
}
