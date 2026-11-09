package ai.opencode.feature.drawer.list

import ai.opencode.core.diagnostics.MotionFinding
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Port of iOS `DrawerDragAudit.swift`: one display frame of a drawer row drag, in window points
 * (dp here): where the finger is, where the lifted row belongs (the finger plus where it was
 * grabbed), and where the lifted row was drawn. iOS records these with `-lab-pin-drag-trace`
 * (`DrawerPinDrag+FollowTrace`), which isn't ported ([DrawerPinDrag.IS_TRACED]); the checks are,
 * so a trace on Android reports the same [MotionFinding] kinds.
 */
data class DrawerDragFrame(
    val index: Int,
    /** ms since the drag's first frame. */
    val time: Int,
    val finger: Offset,
    /** The finger plus the grab offset taken at the lift. */
    val expected: Offset,
    /** The lifted row's drawn frame. */
    val lifted: Rect,
    /** How much of the lifted row's height a clipping ancestor cuts off (the screen's own edge excluded). */
    val clipped: Float = 0f,
    /** The drawer's header or footer chrome draws over the lifted row where they overlap. */
    val underChrome: Boolean = false,
)

/**
 * The checks `MotionAuditDetector` runs on list rows, for a lifted drawer row: it must track the
 * finger 1:1, never be clipped, and float above the drawer's chrome.
 */
object DrawerDragAudit {
    /** How far the lifted row may sit from the finger once a frame's latency is allowed: it may show where the finger was on the frame before. */
    const val followThreshold: Float = 1f
    const val clipThreshold: Float = 0.5f

    data class Summary(
        val frames: Int = 0,
        /** The largest distance from where the finger puts the row, this frame. */
        val maxGap: Float = 0f,
        /** The same, allowing one frame of latency. */
        val maxLag: Float = 0f,
        val lagFrames: Int = 0,
        val maxClipped: Float = 0f,
        val underChromeFrames: Int = 0,
        /** How far up the finger went and the lifted row's centre followed. */
        val fingerMinY: Float = Float.POSITIVE_INFINITY,
        val liftedMinY: Float = Float.POSITIVE_INFINITY,
        val fingerMaxY: Float = Float.NEGATIVE_INFINITY,
        val liftedMaxY: Float = Float.NEGATIVE_INFINITY,
    ) {
        /** The finding kinds these frames add up to, as [findings] reports them. */
        val kinds: List<MotionFinding.Kind>
            get() = buildList {
                if (lagFrames > 0) add(MotionFinding.Kind.FollowLag)
                if (maxClipped > clipThreshold) add(MotionFinding.Kind.LiftedClipped)
                if (underChromeFrames > 0) add(MotionFinding.Kind.Underlap)
            }
    }

    /** The row's distance from where the finger puts it (`gap`), and the same allowing a frame (`lag`). */
    fun lag(frame: DrawerDragFrame, previous: DrawerDragFrame?): Pair<Float, Float> {
        val center = frame.lifted.center
        val gap = hypot(center.x - frame.expected.x, center.y - frame.expected.y)
        if (previous == null) return gap to gap
        val behind = hypot(center.x - previous.expected.x, center.y - previous.expected.y)
        return gap to min(gap, behind)
    }

    fun summarize(frames: List<DrawerDragFrame>): Summary {
        var summary = Summary()
        var previous: DrawerDragFrame? = null
        for (frame in frames) {
            summary = accumulate(summary, frame, previous)
            previous = frame
        }
        return summary
    }

    fun accumulate(summary: Summary, frame: DrawerDragFrame, previous: DrawerDragFrame?): Summary {
        val (gap, lag) = lag(frame, previous)
        return summary.copy(
            frames = summary.frames + 1,
            maxGap = max(summary.maxGap, gap),
            maxLag = max(summary.maxLag, lag),
            lagFrames = summary.lagFrames + if (lag > followThreshold) 1 else 0,
            maxClipped = max(summary.maxClipped, frame.clipped),
            underChromeFrames = summary.underChromeFrames + if (frame.underChrome) 1 else 0,
            fingerMinY = min(summary.fingerMinY, frame.expected.y),
            fingerMaxY = max(summary.fingerMaxY, frame.expected.y),
            liftedMinY = min(summary.liftedMinY, frame.lifted.center.y),
            liftedMaxY = max(summary.liftedMaxY, frame.lifted.center.y),
        )
    }

    fun findings(frames: List<DrawerDragFrame>, row: String = "lifted"): List<MotionFinding> {
        val findings = mutableListOf<MotionFinding>()
        var previous: DrawerDragFrame? = null
        for (frame in frames) {
            fun add(kind: MotionFinding.Kind, detail: String, magnitude: Float) {
                findings += MotionFinding(
                    kind = kind, frame = frame.index, time = frame.time, rows = listOf(row), detail = detail,
                    magnitudePt = (magnitude.toDouble() * 10).roundToLong() / 10.0,
                )
            }
            val (_, lag) = lag(frame, previous)
            if (lag > followThreshold) add(MotionFinding.Kind.FollowLag, "%.1f pt from the finger after a frame".format(Locale.ROOT, lag), lag)
            if (frame.clipped > clipThreshold) add(MotionFinding.Kind.LiftedClipped, "%.1f pt cut off".format(Locale.ROOT, frame.clipped), frame.clipped)
            if (frame.underChrome) add(MotionFinding.Kind.Underlap, "the drawer's chrome draws over it", frame.lifted.height)
            previous = frame
        }
        return findings
    }
}
