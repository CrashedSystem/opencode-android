package ai.opencode.core.diagnostics

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Port of iOS `MotionAudit.swift` (`OpenCode/Transcript/`): the transcript as the app drew it,
 * frame by frame (`lab-motion-trace`, [MotionWatch]), and the jumps a
 * reader would see in it. Lives in `:core` so the transcript, the drawer and Diagnostics share it.
 * JSON keys match iOS's Codable keys; `MotionFrame(raw)` reads the compact lab wire format.
 */
@Serializable
data class MotionFrame(
    val launch: String,
    var index: Int,
    /** The recorded frame's number in its launch: a gap is a lost post. */
    var sequence: Int,
    /** Display frames since the previous callback (more than one through a hitch). */
    var elapsed: Int,
    /** Scheduled display-link period for this frame (ms), e.g. 16.67 at 60 Hz or 8.33 at 120 Hz. */
    var periodMs: Double = DEFAULT_PERIOD_MS,
    /** Wall clock, ms. */
    var wall: Int,
    val offset: Double,
    val insetTop: Double,
    val insetBottom: Double,
    val contentHeight: Double,
    val height: Double,
    /** The card's leading edge on screen: far right while the drawer is open. */
    val cardX: Double,
    val width: Double,
    val gesture: Boolean,
    val motions: List<String>,
    val phase: String,
    val reduceMotion: Boolean,
    val browsing: Boolean = false,
    val trailingSpace: Double = 0.0,
    /** Decoding a non-positive scale reads 1 (iOS `init(from:)`); the memberwise init keeps it. */
    @Serializable(with = PositiveScaleSerializer::class) val timeScale: Double = 1.0,
    val session: String,
    val rows: List<Row>,
    /** Each growing row's soft bottom edge (pt), keyed by row ID. */
    val growthFades: Map<String, Double> = emptyMap(),
    /**
     * The composer's drawn top on the list's screen, the bottom chrome's one source of truth: rows
     * riding it in the same frame aren't a jump.
     */
    val composerTop: Double? = null,
    val boxes: List<Box> = emptyList(),
    var events: List<Event> = emptyList(),
) {
    init {
        // iOS clamps these in its memberwise init and its decoder alike.
        elapsed = max(1, elapsed)
        if (periodMs <= 0) periodMs = DEFAULT_PERIOD_MS
    }

    @Serializable
    data class Row(
        val id: String,
        /** Top and height on the list's screen, and drawn opacity. */
        val y: Double,
        val h: Double,
        val alpha: Double,
        val kind: String,
        /** The row's own layers were animating (an entrance, the list's own update). */
        val animated: Boolean,
        /** The message ID a user or pending row shows (never message text). */
        val key: String? = null,
    ) {
        val bottom: Double get() = y + h
    }

    /**
     * Presentation bounding box of non-row chrome (`header`, `composer`, `pills`, `drawer-card`)
     * in the list window's coordinate space.
     */
    @Serializable
    data class Box(
        val id: String,
        val kind: String,
        val x: Double,
        val y: Double,
        val w: Double,
        val h: Double,
        val alpha: Double,
    )

    @Serializable
    data class Event(
        val name: String,
        val inserted: List<String> = emptyList(),
        val removed: List<String> = emptyList(),
        val order: List<String> = emptyList(),
        val isInitial: Boolean = false,
        val targetID: String? = null,
    ) {
        fun ids(key: String): List<String> = when (key) {
            "i" -> inserted
            "x" -> removed
            "order" -> order
            else -> emptyList()
        }

        val dictionary: Map<String, Any>
            get() {
                val result = linkedMapOf<String, Any>("n" to name)
                if (inserted.isNotEmpty()) result["i"] = inserted
                if (removed.isNotEmpty()) result["x"] = removed
                if (order.isNotEmpty()) result["order"] = order
                if (isInitial) result["init"] = 1
                targetID?.let { result["id"] = it }
                return result
            }

        companion object {
            /** iOS `Event.init?(raw:)`. */
            operator fun invoke(raw: Map<String, Any?>): Event? {
                val name = raw["n"] as? String ?: return null
                return Event(
                    name = name,
                    inserted = rawStrings(raw["i"]) ?: emptyList(),
                    removed = rawStrings(raw["x"]) ?: emptyList(),
                    order = rawStrings(raw["order"]) ?: emptyList(),
                    isInitial = rawInt(raw["init"]) == 1,
                    targetID = raw["id"] as? String,
                )
            }
        }
    }

    /** The part of the list the reader sees between the header and the composer. */
    val visibleTop: Double get() = insetTop
    val visibleBottom: Double get() = height - insetBottom

    fun row(id: String): Row? = rows.firstOrNull { it.id == id }

    /** Compact wire representation matching `lab-motion-trace`. */
    val dictionary: Map<String, Any>
        get() {
            val raw = linkedMapOf<String, Any>(
                "L" to launch,
                "f" to index,
                "q" to sequence,
                "dn" to elapsed,
                "w" to wall,
                "o" to offset,
                "it" to insetTop,
                "ib" to insetBottom,
                "ch" to contentHeight,
                "vh" to height,
                "vx" to cardX,
                "vw" to width,
                "g" to if (gesture) 1 else 0,
                "m" to motions.joinToString(","),
                "p" to phase,
                "rm" to if (reduceMotion) 1 else 0,
                "b" to if (browsing) 1 else 0,
                "ts" to trailingSpace,
                "s" to session,
                "r" to rows.map { row ->
                    val values = mutableListOf<Any>(row.id, row.y, row.h, row.alpha, row.kind, if (row.animated) 1 else 0)
                    row.key?.let { values += it }
                    values
                },
            )
            if (abs(timeScale - 1.0) > 1e-4) raw["sc"] = timeScale
            if (abs(periodMs - DEFAULT_PERIOD_MS) > 0.05) raw["dp"] = swiftRounded(periodMs * 100) / 100
            if (growthFades.isNotEmpty()) raw["gm"] = growthFades
            composerTop?.let { raw["ct"] = it }
            if (boxes.isNotEmpty()) raw["bx"] = boxes.map { listOf<Any>(it.id, it.kind, it.x, it.y, it.w, it.h, it.alpha) }
            if (events.isNotEmpty()) raw["e"] = events.map { it.dictionary }
            return raw
        }

    companion object {
        const val DEFAULT_PERIOD_MS = 1_000.0 / 60.0

        /** iOS `MotionFrame.init?(_ raw:)`: the compact `lab-motion-trace` dictionary. */
        operator fun invoke(raw: Map<String, Any?>): MotionFrame? {
            val index = rawInt(raw["f"]) ?: return null
            fun number(key: String): Double = rawNumber(raw[key]) ?: 0.0
            val rawPeriodMs = rawNumber(raw["dp"]) ?: DEFAULT_PERIOD_MS
            val rawScale = rawNumber(raw["sc"]) ?: 1.0
            val rows = (raw["r"] as? List<*>)?.takeIf { list -> list.all { it is List<*> } }.orEmpty().mapNotNull { item ->
                val values = item as List<*>
                val id = values.getOrNull(0) as? String
                if (values.size < 6 || id == null) return@mapNotNull null
                fun value(index: Int): Double = rawNumber(values[index]) ?: 0.0
                Row(
                    id = id, y = value(1), h = value(2), alpha = value(3), kind = values[4] as? String ?: "",
                    animated = value(5) == 1.0, key = if (values.size > 6) values[6] as? String else null,
                )
            }
            val growthFades = (raw["gm"] as? Map<*, *>)?.takeIf { map -> map.keys.all { it is String } }.orEmpty()
                .mapNotNull { (key, value) -> rawNumber(value)?.let { key as String to it } }.toMap()
            val boxes = (raw["bx"] as? List<*>)?.takeIf { list -> list.all { it is List<*> } }.orEmpty().mapNotNull { item ->
                val values = item as List<*>
                val id = values.getOrNull(0) as? String
                val kind = values.getOrNull(1) as? String
                if (values.size < 7 || id == null || kind == null) return@mapNotNull null
                fun value(index: Int): Double = rawNumber(values[index]) ?: 0.0
                Box(id = id, kind = kind, x = value(2), y = value(3), w = value(4), h = value(5), alpha = value(6))
            }
            @Suppress("UNCHECKED_CAST")
            val events = (raw["e"] as? List<*>)?.takeIf { list -> list.all { it is Map<*, *> } }.orEmpty()
                .mapNotNull { Event(it as Map<String, Any?>) }
            return MotionFrame(
                launch = raw["L"] as? String ?: "",
                index = index,
                sequence = rawInt(raw["q"]) ?: 0,
                elapsed = max(1, rawInt(raw["dn"]) ?: 1),
                periodMs = if (rawPeriodMs > 0) rawPeriodMs else DEFAULT_PERIOD_MS,
                wall = rawInt(raw["w"]) ?: 0,
                offset = number("o"),
                insetTop = number("it"),
                insetBottom = number("ib"),
                contentHeight = number("ch"),
                height = number("vh"),
                cardX = number("vx"),
                width = number("vw"),
                gesture = (rawInt(raw["g"]) ?: 0) == 1,
                motions = (raw["m"] as? String ?: "").split(",").filter { it.isNotEmpty() },
                phase = raw["p"] as? String ?: "live",
                reduceMotion = (rawInt(raw["rm"]) ?: 0) == 1,
                browsing = (rawInt(raw["b"]) ?: 0) == 1,
                trailingSpace = number("ts"),
                timeScale = if (rawScale > 0) rawScale else 1.0,
                session = raw["s"] as? String ?: "",
                rows = rows,
                growthFades = growthFades,
                composerTop = rawNumber(raw["ct"]),
                boxes = boxes,
                events = events,
            )
        }
    }
}

/** iOS decodes a missing or non-positive `timeScale` as 1. */
internal object PositiveScaleSerializer : KSerializer<Double> {
    override val descriptor = PrimitiveSerialDescriptor("MotionFrame.timeScale", PrimitiveKind.DOUBLE)
    override fun serialize(encoder: Encoder, value: Double) = encoder.encodeDouble(value)
    override fun deserialize(decoder: Decoder): Double = decoder.decodeDouble().let { if (it > 0) it else 1.0 }
}

/** Swift's `as? Int` on a bridged number: whole numbers (and booleans) only. */
private fun rawInt(value: Any?): Int? = when (value) {
    is Int -> value
    is Long -> value.toInt()
    is Short -> value.toInt()
    is Byte -> value.toInt()
    is Boolean -> if (value) 1 else 0
    is Number -> value.toDouble().takeIf { it % 1.0 == 0.0 }?.toInt()
    else -> null
}

/** Swift's `(x as? NSNumber)?.doubleValue`. */
private fun rawNumber(value: Any?): Double? = when (value) {
    is Boolean -> if (value) 1.0 else 0.0
    is Number -> value.toDouble()
    else -> null
}

/** Swift's `as? [String]`: the whole array, or nothing. */
private fun rawStrings(value: Any?): List<String>? =
    (value as? List<*>)?.takeIf { list -> list.all { it is String } }?.map { it as String }

/** Swift's `.rounded()`: to nearest, halves away from zero. */
internal fun swiftRounded(value: Double): Double =
    if (value < 0) -Math.round(-value).toDouble() else Math.round(value).toDouble()

@Serializable
data class MotionFinding(
    val kind: Kind,
    val frame: Int,
    /** ms since the scenario's first frame. */
    val time: Int,
    val rows: List<String>,
    val detail: String,
    val magnitudePt: Double? = null,
    val runningMotions: List<String> = emptyList(),
) {
    @Serializable
    enum class Kind(val rawValue: String) {
        /** A new row drawn at once, at full opacity and height, mid-screen. */
        @SerialName("pop-in") PopIn("pop-in"),
        /** A row gone in one frame with nothing in its place. */
        @SerialName("pop-out") PopOut("pop-out"),
        /** Rows moving more than a point in one frame with nothing animating. */
        @SerialName("snap") Snap("snap"),
        /** The view's offset moved the rows with nothing animating. */
        @SerialName("offset-jump") OffsetJump("offset-jump"),
        /** A row's height changing at once with nothing animating it. */
        @SerialName("height-snap") HeightSnap("height-snap"),
        /**
         * A growing row's soft edge widening faster than the row grows (what was drawn dims) or
         * vanishing while still wide (it brightens at once).
         */
        @SerialName("mask-jump") MaskJump("mask-jump"),
        /** A row's speed changing by much in one frame during a motion. */
        @SerialName("jerk") Jerk("jerk"),
        /** Three or more display frames dropped while something moved. */
        @SerialName("hitch") Hitch("hitch"),
        /** One row, or one message, drawn twice. */
        @SerialName("duplicate") Duplicate("duplicate"),
        /** Rows drawn out of the transcript's order, or over one another. */
        @SerialName("order") Order("order"),
        @SerialName("overlap") Overlap("overlap"),
        /** A row on screen that drew nothing (`inkFraction`). */
        @SerialName("blank") Blank("blank"),
        /** Frames the trace recorded never reached the lab. */
        @SerialName("incomplete") Incomplete("incomplete"),
        /** A row's height or an image tile moving one way and then back during a motion: an overshoot or a wobble. */
        @SerialName("reversal") Reversal("reversal"),
        /** A flight landing off the thumbnail it hands over to (`PhotoFlightAudit`). */
        @SerialName("landing-miss") LandingMiss("landing-miss"),
        /** A dragged row more than a point from the finger after a frame (`DrawerDragAudit`). */
        @SerialName("follow-lag") FollowLag("follow-lag"),
        /** A dragged row cut off by a clipping ancestor. */
        @SerialName("lifted-clipped") LiftedClipped("lifted-clipped"),
        /** Chrome (a header or footer) drawn over a dragged row. */
        @SerialName("underlap") Underlap("underlap"),
        ;

        /** Jerks and hitches are reported; the rest fail the audit. */
        val isError: Boolean get() = this != Jerk && this != Hitch

        companion object {
            fun of(rawValue: String): Kind? = entries.firstOrNull { it.rawValue == rawValue }
        }
    }

    val description: String
        get() = String.format(Locale.ROOT, "f%-6d %+6d ms  %-11s %s  %s", frame, time, kind.rawValue, rows.joinToString(", "), detail)

    override fun toString(): String = description
}

object MotionAuditDetector {
    /** How far a row may move in one frame with nothing animating it. */
    const val SNAP_THRESHOLD = 1.0
    /** How much a moving row's per-frame step may change in one 60 Hz frame at 1×. */
    const val JERK_THRESHOLD = 10.0
    /** More than any of the list's follower or growth motions moves content in one 60 Hz frame at 1×. */
    const val LEAP_THRESHOLD = 24.0
    /** Upper bound on a single 60 Hz frame step during the keyboard's spring (iOS: UIKit's `stiffness: 555`). */
    const val KEYBOARD_LEAP_THRESHOLD = 60.0
    /**
     * How far past [KEYBOARD_LEAP_THRESHOLD] a row riding the composer's drawn top may step under the
     * keyboard: its first frame measures 64–73 pt.
     */
    const val KEYBOARD_RIDE_ALLOWANCE = 1.5
    /** Minimum single-frame step under `"keyboard"` that counts as a pop if the row stops dead next frame. */
    const val KEYBOARD_STOP_DEAD_THRESHOLD = 12.0
    /** How far a soft edge may widen beyond the row's growth in one frame. */
    const val MASK_JUMP_THRESHOLD = 1.0

    private data class Mark(val frame: Int, val wall: Int)

    private fun format(pattern: String, vararg args: Any?): String = String.format(Locale.ROOT, pattern, *args)

    /** Incremental frame-by-frame detector used by both [findings] and the live in-app [MotionWatch]. */
    class Scanner {
        var firstWall: Int? = null
            private set
        var previous: MotionFrame? = null
            private set
        /** Rows present when the screen first drew this session: they appear in place. */
        private var shownAtOpen = HashSet<String>()
        private var openSettled = false
        private var insertedAt = HashMap<String, Mark>()
        private var removedAt = HashMap<String, Mark>()
        private data class RowMove(val index: Int, val wall: Int, val step: Double)

        private data class ComposerMove(val index: Int, val wall: Int, val step: Double, val rate: Double)

        private var lastRowMoves = HashMap<String, RowMove>()
        /** Steps moved from rest under `"keyboard"`, to catch 1-frame pops that stop dead. */
        private var lastKeyboardMoves = HashMap<String, RowMove>()
        /** The composer's drawn top's last step as drawn, and per display frame. */
        private var lastComposerMove: ComposerMove? = null
        /** Rows that rode the composer's last step. */
        private var composerRiders: List<String> = emptyList()

        private fun resetMoves() {
            lastRowMoves = HashMap()
            lastKeyboardMoves = HashMap()
            lastComposerMove = null
            composerRiders = emptyList()
        }
        private var lastGestureWall: Int? = null
        private var order: List<String> = emptyList()

        fun reset() {
            firstWall = null
            previous = null
            shownAtOpen = HashSet()
            openSettled = false
            insertedAt = HashMap()
            removedAt = HashMap()
            resetMoves()
            lastGestureWall = null
            order = emptyList()
        }

        fun step(frame: MotionFrame, finalHeights: Map<String, Double>? = null): List<MotionFinding> {
            val origin = firstWall ?: frame.wall
            if (firstWall == null) firstWall = frame.wall
            val findings = mutableListOf<MotionFinding>()
            val earlier = previous
            if (earlier != null && earlier.launch == frame.launch && frame.sequence > earlier.sequence + 1) {
                findings += finding(frame, origin, MotionFinding.Kind.Incomplete, emptyList(), "${frame.sequence - earlier.sequence - 1} recorded frames missing before this one")
            }
            try {
                scan(frame, origin, finalHeights, findings)
            } finally {
                // iOS `defer { previous = frame }`.
                previous = frame
            }
            return findings
        }

        private fun finding(frame: MotionFrame, origin: Int, kind: MotionFinding.Kind, rows: List<String>, detail: String, magnitudePt: Double? = null) =
            MotionFinding(
                kind = kind,
                frame = frame.index,
                time = frame.wall - origin,
                rows = rows,
                detail = detail,
                magnitudePt = magnitudePt?.let { swiftRounded(it * 10) / 10 },
                runningMotions = frame.motions,
            )

        private fun scan(frame: MotionFrame, origin: Int, finalHeights: Map<String, Double>?, findings: MutableList<MotionFinding>) {
            val effectiveScale = max(0.1, min(1.0, frame.timeScale))
            val windowMs = swiftRounded(1_500.0 / effectiveScale).toInt()
            val gestureGraceMs = swiftRounded(100.0 / effectiveScale).toInt()
            fun add(kind: MotionFinding.Kind, rows: List<String>, detail: String, magnitudePt: Double? = null) {
                findings += finding(frame, origin, kind, rows, detail, magnitudePt)
            }

            for (event in frame.events) {
                if (event.name != "apply") continue
                for (id in event.ids("i")) insertedAt[id] = Mark(frame.index, frame.wall)
                for (id in event.ids("x")) removedAt[id] = Mark(frame.index, frame.wall)
                if (event.ids("order").isNotEmpty()) order = event.ids("order")
                if (event.isInitial) shownAtOpen.addAll(event.ids("i"))
            }
            // A row the list fades out where it was leaves with a transition.
            for (event in frame.events) {
                if (event.name == "exit") event.targetID?.let { removedAt.remove(it) }
            }
            if (insertedAt.isNotEmpty()) insertedAt.entries.removeAll { frame.wall - it.value.wall >= windowMs }
            if (removedAt.isNotEmpty()) removedAt.entries.removeAll { frame.wall - it.value.wall >= windowMs }
            // A new launch or another session starts over: what it first draws appears in place.
            // A draft becoming its session is the same transcript.
            val last = previous
            if (last != null && (last.launch != frame.launch || (last.session != frame.session && last.session.isNotEmpty()))) {
                previous = null
                shownAtOpen = HashSet()
                openSettled = false
                insertedAt = HashMap()
                removedAt = HashMap()
                resetMoves()
                lastGestureWall = null
                order = emptyList()
            }
            if (frame.phase == "opening" || frame.events.any { it.name == "first-frame" || it.name == "attach" }) {
                openSettled = false
            }
            // The first frame that draws rows on screen is what the reader first sees.
            if (!openSettled && frame.phase != "opening" && frame.rows.any { it.bottom > frame.visibleTop && it.y < frame.visibleBottom }) {
                shownAtOpen.addAll(frame.rows.map { it.id })
                openSettled = true
                resetMoves()
                return
            }
            if (frame.phase == "opening" || !openSettled) {
                shownAtOpen.addAll(frame.rows.map { it.id })
                return
            }
            val previous = previous ?: return
            if (frame.gesture || previous.gesture) lastGestureWall = frame.wall
            // Off screen behind the drawer, nothing is seen.
            val cardShown = frame.cardX < frame.width * 0.6 && previous.cardX < frame.width * 0.6
            if (!cardShown) {
                resetMoves()
                return
            }
            val userMoving = lastGestureWall?.let { frame.wall - it < gestureGraceMs } ?: false
            if (frame.elapsed >= 3 && previous.motions.isNotEmpty()) {
                add(MotionFinding.Kind.Hitch, emptyList(), "${frame.elapsed - 1} frames dropped during ${previous.motions.joinToString("+")}")
            }
            val moving = frame.motions.isNotEmpty() || previous.motions.isNotEmpty()
            val growing = "grow" in frame.motions || "grow" in previous.motions ||
                "disclosure" in frame.motions || "disclosure" in previous.motions ||
                // A promotion's row changes in place while the list moves its content.
                "promotion" in frame.motions || "promotion" in previous.motions ||
                // The New divider's row closes or opens on its own spring.
                "divider" in frame.motions || "divider" in previous.motions
            val top = frame.visibleTop
            val bottom = frame.visibleBottom
            fun seen(row: MotionFrame.Row): Boolean = row.bottom > top + 2 && row.y < bottom - 2 && row.alpha > 0.05

            // Duplicates and order.
            val ids = frame.rows.map { it.id }.filter { it != "leaving" }
            val repeated = ids.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
            if (repeated.isNotEmpty()) add(MotionFinding.Kind.Duplicate, repeated.sorted(), "row drawn twice")
            val keyed = frame.rows.filter { it.key != null && it.alpha > 0.5 && seen(it) }.groupBy { it.key!! }.filter { it.value.size > 1 }
            for ((key, rows) in keyed) add(MotionFinding.Kind.Duplicate, rows.map { it.id }, "message $key shown by ${rows.size} rows")
            val solid = frame.rows.filter { it.alpha > 0.5 && seen(it) && !it.animated }.sortedBy { it.y }
            val ranks = HashMap<String, Int>()
            order.forEachIndexed { rank, id -> ranks.putIfAbsent(id, rank) }
            for ((upper, lower) in solid.zipWithNext()) {
                val a = ranks[upper.id]
                val b = ranks[lower.id]
                if (a != null && b != null && a > b && !moving) {
                    add(MotionFinding.Kind.Order, listOf(upper.id, lower.id), format("%s drawn above %s", upper.id, lower.id))
                }
                val overlap = upper.bottom - lower.y
                if (overlap > 2 && !moving && lower.h > 4 && upper.h > 4) {
                    add(MotionFinding.Kind.Overlap, listOf(upper.id, lower.id), format("overlap %.1f pt", overlap), magnitudePt = overlap)
                }
            }

            // Rows arriving and leaving.
            val before = previous.rows.mapTo(HashSet()) { it.id }
            val after = ids.toHashSet()
            for (row in frame.rows) {
                if (row.id in before) continue
                val inserted = insertedAt[row.id] ?: continue
                if (frame.wall - inserted.wall >= windowMs || row.id in shownAtOpen) continue
                insertedAt.remove(row.id)
                // Entering from under the composer, or already fading in, is a transition.
                if (!(row.y < bottom - 20 && row.bottom > top + 20 && row.alpha > 0.95 && !row.animated && !userMoving)) continue
                val final = finalHeights?.get(row.id) ?: row.h
                if (row.h < final * 0.9) continue
                // In place of a row that left this frame (Working handing off to the reply's end).
                if (previous.rows.any { it.id !in after && abs(it.y - row.y) <= 1.5 && abs(it.h - row.h) <= 1.5 }) continue
                add(
                    MotionFinding.Kind.PopIn, listOf(row.id),
                    format("%s drawn at y %.0f, h %.0f, alpha %.2f in one frame (inserted f%d, phase %s)", row.kind, row.y, row.h, row.alpha, inserted.frame, frame.phase),
                    magnitudePt = row.h,
                )
            }
            for (row in previous.rows) {
                if (row.id in after) continue
                val removed = removedAt[row.id] ?: continue
                if (frame.wall - removed.wall >= windowMs) continue
                removedAt.remove(row.id)
                if (!(seen(row) && row.alpha > 0.95 && !row.animated && !userMoving && row.h > 4)) continue
                if (frame.rows.any { it.id !in before && abs(it.y - row.y) <= 1.5 && abs(it.h - row.h) <= 1.5 }) continue
                add(
                    MotionFinding.Kind.PopOut, listOf(row.id),
                    format("%s at y %.0f, h %.0f gone in one frame (phase %s)", row.kind, row.y, row.h, frame.phase),
                    magnitudePt = row.h,
                )
            }

            // Rows moving and resizing.
            val nextRowMoves = HashMap<String, RowMove>()
            val nextKeyboardMoves = HashMap<String, RowMove>()
            val keyboardStoppedDead = mutableListOf<Pair<MotionFrame.Row, Double>>()
            val snapped = mutableListOf<Pair<MotionFrame.Row, Double>>()
            val resized = mutableListOf<Pair<MotionFrame.Row, Double>>()
            val offsetStep = frame.offset - previous.offset
            // Normalize 60 Hz 1× thresholds (24 pt leap, 10 pt jerk) by each producer's actual
            // movement cadence and `AnimationTimeScale` (0.25× slow-mo). The trace may tick at 120 Hz
            // (8.33 ms) while the list's motion loop runs at 60 Hz (16.67 ms), so only tighten below
            // 60 Hz when a row or the composer actually moves on back-to-back sub-60 Hz ticks (< 14.5 ms).
            val basePeriodMs = 1_000.0 / 60.0
            val sub60CutoffMs = 14.5
            fun gapMs(sinceIndex: Int, sinceWall: Int): Double {
                val ticks = max(frame.elapsed, max(1, frame.index - sinceIndex))
                val tickMs = ticks * frame.periodMs
                val wallMs = max(0, frame.wall - sinceWall).toDouble()
                return max(tickMs, min(wallMs, basePeriodMs))
            }
            fun cadenceScale(lastIndex: Int?, lastWall: Int?): Double {
                val nominalMs = max(frame.periodMs, basePeriodMs)
                val producerMs = if (lastIndex != null && lastWall != null) {
                    val gap = gapMs(lastIndex, lastWall)
                    if (gap < sub60CutoffMs) gap else nominalMs
                } else {
                    nominalMs
                }
                return min(max(producerMs / basePeriodMs, 0.25), 2.0) * effectiveScale
            }
            val renderServerMotion = listOf("disclosure", "promotion", "divider").any { it in frame.motions || it in previous.motions }
            val hitchSpan = if (renderServerMotion) min(frame.elapsed, 4).toDouble() else 1.0
            // Jump to latest, the keyboard, and the slide up to what arrived while the reader was
            // away ride their own curves, which start or accelerate hard.
            val keyboardMoving = "keyboard" in frame.motions || "keyboard" in previous.motions
            val ownCurve = listOf("jump", "keyboard", "reveal").any { it in frame.motions || it in previous.motions }
            // Bottom chrome moves as one: a row moving with the composer's drawn top in the same
            // frame rides it (a new line, attachments easing in). Riding it is no proof the composer
            // moved smoothly: its own track, per display frame (a stall merges frames), takes the
            // moving rows' leap and jerk limits, and rows riding a snap are a jump. A step from rest
            // takes only the leap limit, like a row's. A drawn step the composer stops dead after was
            // a snap however many frames it merged (only a spring glides on), and the rows that rode
            // it jumped. At 120 Hz, one skipped 8.33 ms tick is just a 60 Hz producer's off-tick; two
            // or more (> 20 ms) means the composer sat still since `previous`.
            val skippedTicks = max(0, (frame.index - previous.index) - frame.elapsed)
            val ticksSincePrevious = max(frame.elapsed, max(1, frame.index - previous.index))
            val restedBetween = (skippedTicks + 1) * frame.periodMs > 20.0 ||
                (frame.wall - previous.wall).toDouble() > ticksSincePrevious * frame.periodMs + 20.0
            var composerStep = frame.composerTop?.let { now -> previous.composerTop?.let { now - it } }
                ?.takeIf { abs(it) > SNAP_THRESHOLD }
            // The composer's step as drawn, before its own limits judge it: the keyboard carries it
            // farther in its first frame than the list's own motions ever move.
            val keyboardComposerStep = if (keyboardMoving) composerStep else null
            var composerHoldTick = false
            lastComposerMove?.let { prevComp ->
                val compGap = gapMs(prevComp.index, prevComp.wall)
                val compStopJerkLimit = max(2.0, JERK_THRESHOLD * cadenceScale(null, null))
                if (composerStep == null && !restedBetween && compGap < sub60CutoffMs) {
                    composerHoldTick = true
                } else if (frame.composerTop != null && (composerStep == null || restedBetween) &&
                    abs(prevComp.step) > compStopJerkLimit && composerRiders.isNotEmpty()
                ) {
                    add(
                        MotionFinding.Kind.OffsetJump, composerRiders,
                        format("%d rows rode the composer's %.1f pt step, then it stopped dead", composerRiders.size, abs(prevComp.step)),
                        magnitudePt = abs(prevComp.step),
                    )
                }
            }
            if (restedBetween) lastComposerMove = null
            val drawnComposerStep = composerStep
            if (drawnComposerStep != null) {
                val last = lastComposerMove
                val compScale = cadenceScale(last?.index, last?.wall)
                val composerLeapLimit = max(SNAP_THRESHOLD * 1.5, LEAP_THRESHOLD * compScale)
                val composerJerkLimit = max(2.0, JERK_THRESHOLD * compScale)
                val rate = drawnComposerStep / frame.elapsed
                if (abs(rate) > composerLeapLimit || (last != null && abs(rate - last.rate) > composerJerkLimit)) composerStep = null
                lastComposerMove = ComposerMove(frame.index, frame.wall, drawnComposerStep, rate)
            } else if (!composerHoldTick) {
                lastComposerMove = null
            }
            val riders = mutableListOf<String>()
            for (row in frame.rows) {
                // Moving on screen: a row drawn off screen last frame wasn't seen there.
                val old = previous.row(row.id) ?: continue
                if (!seen(row) || !seen(old)) continue
                val step = row.y - old.y
                val grew = row.h - old.h
                if (userMoving || row.animated || old.animated) continue
                if (!growing && abs(grew) > 1 && row.alpha > 0.5) resized += row to grew
                val prevMove = if (restedBetween) null else lastRowMoves[row.id]
                val rowMoved = abs(step) > 0.5
                val isSub60Hold = !rowMoved && !restedBetween &&
                    prevMove != null && gapMs(prevMove.index, prevMove.wall) < sub60CutoffMs
                val rowScale = if (rowMoved) cadenceScale(prevMove?.index, prevMove?.wall) else cadenceScale(null, null)
                val rowLeapLimit = max(SNAP_THRESHOLD * 1.5, LEAP_THRESHOLD * rowScale * hitchSpan)
                val rowJerkLimit = max(2.0, JERK_THRESHOLD * rowScale * hitchSpan)
                val keyboardLeapLimit = max(SNAP_THRESHOLD * 4.0, KEYBOARD_LEAP_THRESHOLD * rowScale * min(frame.elapsed, 4))
                val keyboardStopDeadMin = max(SNAP_THRESHOLD * 2.0, KEYBOARD_STOP_DEAD_THRESHOLD * rowScale)
                if (rowMoved) {
                    nextRowMoves[row.id] = RowMove(frame.index, frame.wall, step)
                } else if (isSub60Hold && prevMove != null) {
                    nextRowMoves[row.id] = prevMove
                }
                val ridden = composerStep
                if (ridden != null && abs(step - ridden) <= SNAP_THRESHOLD) {
                    riders += row.id
                    continue
                }
                if (!moving && abs(step) > SNAP_THRESHOLD) snapped += row to step
                // The follower and growth never move content more than 18 pt per 60 Hz frame; a
                // larger step under them is a snap, not their motion.
                if (moving && !ownCurve && abs(step) > rowLeapLimit) snapped += row to step
                // Under a keyboard transition, catch 1-frame keyboard pops: either a leap exceeding
                // the keyboard spring's peak, or a sharp step that immediately stops dead instead of
                // gliding on the keyboard curve.
                lastKeyboardMoves[row.id]?.let { prevKb ->
                    val kbGap = gapMs(prevKb.index, prevKb.wall)
                    if (!restedBetween && abs(step) <= SNAP_THRESHOLD && kbGap < sub60CutoffMs) {
                        nextKeyboardMoves[row.id] = prevKb
                    } else if ((restedBetween || abs(step) <= SNAP_THRESHOLD) && abs(prevKb.step) > keyboardStopDeadMin) {
                        keyboardStoppedDead += row to prevKb.step
                    }
                }
                if (keyboardMoving) {
                    // A row moving with the composer's drawn top in the same frame follows the
                    // keyboard's curve, whose first full frame can pass the leap limit (after a
                    // few-point first tick). Up to a ceiling it isn't a jump, but it must glide on:
                    // wherever it falls, if both stop dead next frame, it was a pop all the same.
                    val ridesKeyboard = keyboardComposerStep?.let {
                        abs(step - it) <= SNAP_THRESHOLD && abs(step) <= keyboardLeapLimit * KEYBOARD_RIDE_ALLOWANCE
                    } ?: false
                    val rodePastLimit = abs(step) > keyboardLeapLimit && ridesKeyboard
                    if (abs(step) > keyboardLeapLimit && !ridesKeyboard) {
                        snapped += row to step
                    } else if (abs(step) > SNAP_THRESHOLD && (prevMove == null || rodePastLimit)) {
                        // Only a step starting from rest can be a 1-frame pop: the last step of a
                        // multi-frame glide is never one.
                        nextKeyboardMoves[row.id] = RowMove(frame.index, frame.wall, step)
                    }
                }
                // Jump to latest's far cut starts fast by design. Skip intermediate 120 Hz
                // off-ticks of a 60 Hz producer (`isSub60Hold`) so hold frames don't false-flag jerk.
                val last = prevMove?.step
                if (moving && !ownCurve && !isSub60Hold && last != null && abs(step - last) > rowJerkLimit) {
                    add(
                        MotionFinding.Kind.Jerk, listOf(row.id),
                        format("step %.1f → %.1f pt/frame (%s)", last, step, frame.motions.joinToString("+")),
                        magnitudePt = abs(step - last),
                    )
                }
            }
            lastRowMoves = if (userMoving) HashMap() else nextRowMoves
            lastKeyboardMoves = if (userMoving) HashMap() else nextKeyboardMoves
            composerRiders = if (composerHoldTick) composerRiders else if (composerStep == null) emptyList() else riders
            if (keyboardStoppedDead.isNotEmpty() && snapped.isEmpty()) {
                val worst = keyboardStoppedDead.maxOf { abs(it.second) }
                add(
                    MotionFinding.Kind.OffsetJump, keyboardStoppedDead.map { it.first.id },
                    format("%d rows popped %.1f pt in one keyboard frame, then stopped dead", keyboardStoppedDead.size, worst),
                    magnitudePt = worst,
                )
            }
            // Growing rows' soft edges: every pixel only ever brightens.
            for (row in frame.rows) {
                val old = previous.row(row.id) ?: continue
                if (!seen(row) || !seen(old)) continue
                val fadeBefore = previous.growthFades[row.id] ?: 0.0
                val fadeAfter = frame.growthFades[row.id] ?: 0.0
                if (!(fadeBefore > 0 || fadeAfter > 0)) continue
                val grew = row.h - old.h
                if (fadeAfter - fadeBefore > max(0.0, grew) + MASK_JUMP_THRESHOLD) {
                    add(
                        MotionFinding.Kind.MaskJump, listOf(row.id),
                        format("%s soft edge %.1f → %.1f pt while it grew %+.1f pt", row.kind, fadeBefore, fadeAfter, grew),
                        magnitudePt = fadeAfter - fadeBefore - max(0.0, grew),
                    )
                } else if (fadeBefore > MASK_JUMP_THRESHOLD * 2 && frame.growthFades[row.id] == null) {
                    add(MotionFinding.Kind.MaskJump, listOf(row.id), format("%s soft edge %.1f pt gone in one frame", row.kind, fadeBefore), magnitudePt = fadeBefore)
                }
            }
            if (snapped.isNotEmpty()) {
                val uniform = abs(offsetStep) > SNAP_THRESHOLD && snapped.all { abs(it.second + offsetStep) < 1 }
                val worst = snapped.maxOf { abs(it.second) }
                add(
                    if (uniform) MotionFinding.Kind.OffsetJump else MotionFinding.Kind.Snap, snapped.map { it.first.id },
                    format(
                        "%d rows moved up to %.1f pt in one frame, offset %+.1f, content %+.1f, phase %s%s",
                        snapped.size, worst, frame.offset - previous.offset, frame.contentHeight - previous.contentHeight, frame.phase,
                        if (moving) " during " + frame.motions.joinToString("+") else "",
                    ),
                    magnitudePt = worst,
                )
            }
            // A row shrinking with nothing below it, or growing only below the visible viewport
            // with nothing moving on screen, moves nothing the reader sees.
            for ((row, grew) in resized) {
                val oldBottom = previous.row(row.id)?.bottom ?: row.bottom
                val visibleEdgeMoved = oldBottom < bottom - 2 || row.bottom < bottom - 2
                if ((grew > 0 && visibleEdgeMoved) || snapped.any { it.first.y > row.y }) {
                    add(
                        MotionFinding.Kind.HeightSnap, listOf(row.id),
                        format("%s %+.1f pt in one frame to %.0f (phase %s)", row.kind, grew, row.h, frame.phase),
                        magnitudePt = abs(grew),
                    )
                }
            }
        }
    }

    fun findings(frames: List<MotionFrame>): List<MotionFinding> {
        if (frames.isEmpty()) return emptyList()
        val lastFrameBySession = HashMap<String, MotionFrame>()
        for (frame in frames) lastFrameBySession[frame.session] = frame
        val heightsBySession = lastFrameBySession.mapValues { (_, lastFrame) -> lastFrame.rows.associate { it.id to it.h } }
        val scanner = Scanner()
        val incomplete = mutableListOf<MotionFinding>()
        val rest = mutableListOf<MotionFinding>()
        for (frame in frames) {
            for (finding in scanner.step(frame, finalHeights = heightsBySession[frame.session])) {
                if (finding.kind == MotionFinding.Kind.Incomplete) incomplete += finding else rest += finding
            }
        }
        return incomplete + rest
    }

    fun report(scenario: String, frames: List<MotionFrame>, findings: List<MotionFinding>): String {
        val errors = findings.filter { it.kind.isError }
        val lines = mutableListOf("$scenario: ${frames.size} frames, ${errors.size} jumps, ${findings.size - errors.size} jerks or hitches")
        val counts = findings.groupBy { it.kind.rawValue }.map { "${it.key} ${it.value.size}" }.sorted()
        if (counts.isNotEmpty()) lines += "  " + counts.joinToString(" · ")
        lines += findings.take(60).map { "  " + it.description }
        if (findings.size > 60) lines += "  … ${findings.size - 60} more"
        return lines.joinToString("\n") + "\n"
    }
}
