@file:PortedFrom("OpenCode/AgentActivity/AgentActivitySource.swift", "339260d")

package ai.opencode.mobilecode

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.floor
import kotlin.math.max

/// Tool calls and output chunks seen so far; a glyph reacts to the difference.
data class AgentActivityCounts(val tool: Int = 0, val output: Int = 0) {
    fun since(old: AgentActivityCounts): AgentActivityCounts =
        AgentActivityCounts(tool = max(0, tool - old.tool), output = max(0, output - old.output))
}

/// What the app knows about one subagent from its parent: the subagent
/// tool call's phase, its session, and whether it waits on the user.
data class SubagentActivity(
    val sessionID: String?,
    val phase: Phase,
    val needsInput: Boolean = false,
) {
    enum class Phase {
        /// The call's input is still streaming, or its session isn't known yet.
        spawning,
        running,
        done,
        failed,
    }

    /// The state at `now`. Done, Failed, Waiting for Input and Handing Off
    /// come straight from the app's state; the rest are derived from the
    /// subagent's own live events (`AgentActivityLedger`), which the app
    /// otherwise doesn't keep for sessions it isn't showing:
    /// - Starting Up: its run started in the last 1.4 s.
    /// - Handing Off: the call is still being written, or the subagent began
    ///   spawning a subagent of its own in the last 2.6 s.
    /// - Streaming Output: text arrived in the last 0.8 s.
    /// - Tool Burst: a tool was called in the last 2 s.
    /// - Long Work: running, with no live event for 8 s (one long step).
    /// - Thinking: otherwise, reasoning included.
    fun activity(at: Double): AgentActivity {
        val now = at
        when (phase) {
            Phase.failed -> return AgentActivity.failed
            Phase.done -> return AgentActivity.done
            Phase.spawning -> return if (needsInput) AgentActivity.waiting else AgentActivity.handoff
            Phase.running -> Unit
        }
        if (needsInput) return AgentActivity.waiting
        val sessionID = sessionID ?: return AgentActivity.handoff
        val record = AgentActivityLedger.shared.record(sessionID) ?: return AgentActivity.thinking
        record.spawnedAt?.let { if (now - it < 2.6) return AgentActivity.handoff }
        record.startedAt?.let { if (now - it < 1.4) return AgentActivity.startup }
        record.textAt?.let { if (now - it < 0.8) return AgentActivity.streaming }
        record.toolAt?.let { if (now - it < 2) return AgentActivity.tool }
        if (record.isActive && now - record.activityAt > 8) return AgentActivity.longWork
        return AgentActivity.thinking
    }

    val counts: AgentActivityCounts
        get() = sessionID?.let { AgentActivityLedger.shared.record(it)?.counts } ?: AgentActivityCounts()
}

/// Where a glyph's state comes from.
sealed interface AgentActivitySource {
    /// Work with no finer signal: Thinking.
    data object running : AgentActivitySource

    /// A shell: shells don't think, they stream.
    data object shell : AgentActivitySource

    data class subagent(val activity: SubagentActivity) : AgentActivitySource

    /// The background-work capsule: its most telling subagent, else its shells.
    data class summary(val subagents: List<SubagentActivity>, val shells: Int) : AgentActivitySource

    /// The lab's large preview.
    data object lab : AgentActivitySource

    fun activity(at: Double, lab: AgentActivityOverride?): AgentActivity {
        if (lab != null && followsLab) return lab.activity(at = at)
        return when (this) {
            running, AgentActivitySource.lab -> AgentActivity.thinking
            shell -> AgentActivity.streaming
            is subagent -> activity.activity(at = at)
            is summary -> leading(subagents, at)?.second ?: if (shells > 0) AgentActivity.streaming else AgentActivity.idle
        }
    }

    fun counts(at: Double, lab: AgentActivityOverride?): AgentActivityCounts {
        if (lab != null && followsLab) return lab.counts(at = at)
        return when (this) {
            running, shell, AgentActivitySource.lab -> AgentActivityCounts()
            is subagent -> activity.counts
            is summary -> leading(subagents, at)?.first?.counts ?: AgentActivityCounts()
        }
    }

    private val followsLab: Boolean
        get() = when (this) {
            is subagent, is summary, AgentActivitySource.lab -> true
            running, shell -> false
        }

    private fun leading(subagents: List<SubagentActivity>, at: Double): Pair<SubagentActivity, AgentActivity>? =
        subagents.map { it to it.activity(at = at) }.minByOrNull { priority.indexOf(it.second).takeIf { index -> index >= 0 } ?: 99 }

    companion object {
        /// The background-work capsule's: its subagents, running, and its shells.
        fun summary(agents: List<OpenCodeSession>, shells: List<OpenCodeShell>, waiting: Set<String>): AgentActivitySource = summary(
            subagents = agents.map { SubagentActivity(sessionID = it.id, phase = SubagentActivity.Phase.running, needsInput = waiting.contains(it.id)) },
            shells = shells.size,
        )

        /// Most telling first, for a summary of several.
        private val priority = listOf(
            AgentActivity.waiting, AgentActivity.failed, AgentActivity.handoff, AgentActivity.startup, AgentActivity.tool,
            AgentActivity.streaming, AgentActivity.longWork, AgentActivity.thinking, AgentActivity.done, AgentActivity.idle,
        )
    }
}

/// A small projection of every session's live events, outside the reducers:
/// when each run started, and when it last streamed text, called a tool, or
/// began spawning a subagent. The event stream records into it as events
/// arrive; glyphs read it while they draw. Times are monotonic seconds
/// (`AgentActivityLedger.now()`), the frame clock's base.
class AgentActivityLedger {
    data class Record(
        val isActive: Boolean = false,
        val startedAt: Double? = null,
        val activityAt: Double,
        val textAt: Double? = null,
        val toolAt: Double? = null,
        val spawnedAt: Double? = null,
        val counts: AgentActivityCounts = AgentActivityCounts(),
    )

    private val records = mutableMapOf<String, Record>()

    fun record(sessionID: String): Record? = synchronized(records) { records[sessionID] }

    fun record(event: OpenCodeServerEvent, at: Double = now()) {
        val now = at
        when (event) {
            is OpenCodeServerEvent.executionStarted -> update(event.sessionID, now) { it.copy(isActive = true, startedAt = now) }
            is OpenCodeServerEvent.executionEnded -> update(event.sessionID, now) { it.copy(isActive = false) }
            is OpenCodeServerEvent.contentBatch -> update(event.sessionID, now) { start ->
                var record = start
                var output = false
                for (content in event.events) {
                    when (content) {
                        is OpenCodeLiveContentEvent.textDelta -> output = true
                        is OpenCodeLiveContentEvent.toolCalled ->
                            record = record.copy(toolAt = now, counts = record.counts.copy(tool = record.counts.tool + 1))
                        is OpenCodeLiveContentEvent.toolInputStarted ->
                            if (content.name == "subagent" || content.name == "task") record = record.copy(spawnedAt = now)
                        else -> Unit
                    }
                }
                if (output) record = record.copy(textAt = now, counts = record.counts.copy(output = record.counts.output + 1))
                record
            }
            else -> Unit
        }
    }

    private fun update(sessionID: String, now: Double, change: (Record) -> Record) {
        synchronized(records) {
            val record = (records[sessionID] ?: Record(activityAt = now)).copy(activityAt = now)
            records[sessionID] = change(record)
            // Sessions come and go; keep the projection small.
            if (records.size > 256) {
                records.minByOrNull { it.value.activityAt }?.key?.let { records.remove(it) }
            }
        }
    }

    companion object {
        val shared = AgentActivityLedger()

        fun now(): Double = System.nanoTime() / 1e9
    }
}

/// The lab's hold on subagent glyphs: one state, or the prototype's showcase
/// of three turns.
data class AgentActivityOverride(val mode: Mode, val start: Double) {
    sealed interface Mode {
        data class state(val activity: AgentActivity) : Mode

        data object showcase : Mode
    }

    fun activity(at: Double): AgentActivity = when (mode) {
        is Mode.state -> mode.activity
        Mode.showcase -> AgentActivityShowcase.segment(at = max(0.0, at - start)).activity
    }

    /// Tool calls and output in the prototype's steady rhythms (three calls
    /// every 2.2 s, five chunks every 2.1 s), or the showcase's own events.
    fun counts(at: Double): AgentActivityCounts {
        val t = max(0.0, at - start)
        return when {
            mode == Mode.state(AgentActivity.tool) -> AgentActivityCounts(tool = rhythm(t, period = 2.2, at = listOf(0.15, 0.4, 0.65)))
            mode == Mode.state(AgentActivity.streaming) -> AgentActivityCounts(output = rhythm(t, period = 2.1, at = listOf(0.1, 0.4, 0.7, 1.0, 1.3)))
            mode is Mode.state -> AgentActivityCounts()
            else -> AgentActivityShowcase.counts(at = t)
        }
    }

    private fun rhythm(t: Double, period: Double, at: List<Double>): Int {
        val cycles = floor(t / period)
        val phase = t - cycles * period
        return cycles.toInt() * at.size + at.count { it <= phase }
    }
}

/// The prototype's showcase (`activity-glyph/index.html`, TURNS): a turn
/// that finishes, one that fails, one that hands off, with tool calls in
/// bursts and output in chunks fixed per segment, looping.
object AgentActivityShowcase {
    data class Segment(val activity: AgentActivity, val at: Double, val duration: Double)

    data class Timeline(val segments: List<Segment>, val tools: List<Double>, val outputs: List<Double>, val length: Double)

    val turns: List<List<Pair<AgentActivity, Double>>> = listOf(
        listOf(
            AgentActivity.idle to 1.2, AgentActivity.startup to 1.4, AgentActivity.thinking to 2.2, AgentActivity.tool to 2.0,
            AgentActivity.streaming to 2.6, AgentActivity.thinking to 1.2, AgentActivity.waiting to 3.0, AgentActivity.thinking to 1.4,
            AgentActivity.longWork to 4.2, AgentActivity.streaming to 2.2, AgentActivity.done to 2.4, AgentActivity.idle to 1.2,
        ),
        listOf(
            AgentActivity.startup to 1.2, AgentActivity.thinking to 1.6, AgentActivity.tool to 1.6, AgentActivity.streaming to 2.2,
            AgentActivity.failed to 2.6, AgentActivity.idle to 1.2,
        ),
        listOf(AgentActivity.startup to 1.2, AgentActivity.thinking to 1.8, AgentActivity.tool to 1.4, AgentActivity.handoff to 2.8, AgentActivity.idle to 1.4),
    )

    val timeline: Timeline by lazy {
        var seed = 3L
        fun rnd(): Double {
            seed = (seed * 16807) % 2147483647
            return seed.toDouble() / 2147483647
        }
        val segments = mutableListOf<Segment>()
        val tools = mutableListOf<Double>()
        val outputs = mutableListOf<Double>()
        var length = 0.0
        for (turn in turns) {
            for ((activity, duration) in turn) {
                val at = length
                if (activity == AgentActivity.tool) {
                    var t = 0.08
                    while (t < duration - 0.1) {
                        val n = 2 + floor(rnd() * 3).toInt()
                        var i = 0
                        while (i < n && t < duration - 0.1) {
                            tools += at + t
                            t += 0.14 + rnd() * 0.22
                            i += 1
                        }
                        t += 0.5 + rnd() * 0.5
                    }
                }
                if (activity == AgentActivity.streaming) {
                    var t = 0.06
                    while (t < duration - 0.05) {
                        outputs += at + t
                        t += if (rnd() < 0.8) 0.1 + rnd() * 0.2 else 0.4 + rnd() * 0.4
                    }
                }
                segments += Segment(activity = activity, at = at, duration = duration)
                length += duration
            }
        }
        Timeline(segments, tools, outputs, length)
    }

    fun segment(at: Double): Segment {
        val local = at % timeline.length
        return timeline.segments.lastOrNull { local >= it.at } ?: timeline.segments[0]
    }

    fun counts(at: Double): AgentActivityCounts {
        val loops = floor(at / timeline.length).toInt()
        val local = at - loops * timeline.length
        return AgentActivityCounts(
            tool = loops * timeline.tools.size + timeline.tools.count { it <= local },
            output = loops * timeline.outputs.size + timeline.outputs.count { it <= local },
        )
    }
}

/// The session lab's hold on subagent glyphs and its 120 pt preview. iOS
/// drives it through Darwin notifications, which Android has no counterpart
/// for, so nothing sets it yet.
object AgentActivityLab {
    var override by mutableStateOf<AgentActivityOverride?>(null)
    var showsPreview by mutableStateOf(false)
}
