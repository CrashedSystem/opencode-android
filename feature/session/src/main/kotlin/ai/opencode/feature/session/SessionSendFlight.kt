package ai.opencode.feature.session

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionSpring
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.tuning.AnimationTimeScale
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.feature.composer.ComposerSendTransition
import ai.opencode.feature.media.TranscriptMetrics
import ai.opencode.feature.transcript.TranscriptTunables
import ai.opencode.feature.transcript.user.SendLanding
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

// Port of the flight half of iOS `ComposerSendTransition` (`beginLanding`, `stepLanding`,
// `flyIntoPill`, `dissolvePrepared`, `finishLanding`) and of the list's `SendFlight`
// (`TranscriptListViewController+SendFlights.swift`): the composer's words fly from the field
// into the new user bubble while the follower glides the list up to it, one motion, because the
// flight lands on the bubble wherever the list has carried it this frame. iOS draws snapshots in
// the window; here one overlay spans the transcript and the composer and draws the composer's
// captured words ([ComposerSendTransition.Prepared]) and the bubble's recorded layers
// (`:feature:transcript`'s [SendLanding] markers).

/**
 * A send's flight from the composer into its bubble (iOS `SendFlight` + `ComposerSendTransition.Flight`).
 * The flight starts once the bubble (and its photos' slots) have been laid out (iOS starts it as
 * the cell is found), and its clock runs from the first frame; a bubble that starts below the
 * words holds them level for a moment ([lift]) rather than the flight waiting for it.
 * [source] is null for a send of photos alone (iOS `carryRemainder`: no words fly, the row's
 * other content fades in around the photos).
 */
internal class SendFlight(
    val itemID: String,
    val source: ComposerSendTransition.Prepared?,
    var awaitsImages: Boolean,
    /** Off under Reduce Motion. */
    val lift: SendLift = SendLift(),
) {
    /** The flight's ω, captured at its start (iOS `capturedOmega`): about 0.45 s, or the photos' clock. */
    var omega: Double = TranscriptTunables.sendFlightOmega
    var elapsed: Double = 0.0
    /** The bubble was found and the flight is under way (iOS: `sendFlights[item]` exists). */
    var started = false
    /** How long (s) it has waited for its bubble's first layout. */
    var waited = 0.0
    var progress: Float = 0f
    /** The bubble's last known place (window px); a bubble scrolled away keeps it. */
    var destination: Rect? = null
    /** The send's photos fly with it ([SentImageFlight] holds them under [itemID]). */
    var fliesImages = false
    /** iOS `landedFrame`: one fully landed frame has been drawn. */
    var landedFrame = false
    /** The photos are handing over to the row, which shows again under them. */
    var handingOff = false
    /** This frame's lift (px): how far everything in the flight aims above the bubble. */
    var raised = 0f

    /** iOS `timeout`: `max(0.8, 8 / ω, hold × 1.5 + 0.1)`. */
    val timeout: Double get() = max(max(0.8, 8.0 / max(1.0, omega)), MAXIMUM_HOLD * 1.5 + 0.1)

    /**
     * iOS `step(timestamp:targetTimestamp:gap:interrupted:)`: the spring's progress, critically
     * damped, and the words' lift for this frame, given the bubble's [gap] below the words (px)
     * and whether something else is moving the list; null once landed.
     */
    fun advance(dt: Double, gap: Float? = null, interrupted: Boolean = false, softness: Float = 0f): Float? {
        elapsed += dt
        val p = 1 - (1 + omega * elapsed) * exp(-omega * elapsed)
        raised = lift.step(gap, elapsed, interrupted, hold = MAXIMUM_HOLD, softness = softness)
        if ((p >= 0.995 && !lift.isLifting) || elapsed >= timeout) return null
        return p.toFloat()
    }

    companion object {
        /**
         * iOS `maximumHold`: a bubble that starts below the words would pull them down toward the
         * composer before the list carries it up. For up to this long they aim no lower than they
         * left ([SendLift]), then let go over half as long again. The spring runs from the first
         * frame all the same, so they rise with the bubble as soon as it passes them.
         */
        val MAXIMUM_HOLD: Double get() = TranscriptTunables.sendBubbleWaitMax
    }
}

/** A send that doesn't fly: its words fade where they are (iOS `dissolvePrepared`), or shrink into the queued pill (`flyIntoPill`). */
private class FadingSend(val source: ComposerSendTransition.Prepared, val pill: Rect?) {
    val progress = Animatable(0f)
}

/** A pending row's copy shrinking into the queued pill (iOS `flyIntoQueuedPill`). */
private class PillFlight(val departure: SendLanding.PillDeparture, val pill: Rect) {
    val progress = Animatable(0f)
}

/**
 * The session's send flights (iOS `scrollEdgeBridge.sendTransition`'s window snapshots). A plain
 * object: it owns per-frame motion, and the reducer never hears of it.
 */
@Stable
internal class SessionSendFlights(
    private val transition: ComposerSendTransition,
    private val landing: SendLanding,
    private val scope: CoroutineScope,
) {
    private val flights = mutableStateListOf<SendFlight>()
    private val fading = mutableStateListOf<FadingSend>()
    private val pillFlights = mutableStateListOf<PillFlight>()

    /** Bumped every flight frame so the overlay redraws. */
    var frame by mutableIntStateOf(0)
        private set

    val isFlying: Boolean get() = flights.isNotEmpty()

    /** px per dp, from the last [land]. */
    private var flightDensity = 0f

    /** The flights' clock (seconds, slowed with the app's animation time scale). */
    private var clock = 0.0

    /** Whether motion was reduced at the last [land] (iOS reads `reduceMotion` for the photos' clock). */
    private var reducesMotion = false

    private val images get() = transition.images

    /** The overlay's place in the window (px), to draw window rects in its own space. */
    var coordinates: LayoutCoordinates? = null

    /**
     * iOS `landSentMessage`. [composerFrame] is the composer's place (window px) for a send the
     * composer didn't capture (dictation's ↑, a send that waited for uploads), [layOut] lays its
     * words out as the composer does. Returns true when it flies into its bubble.
     */
    fun land(
        send: SendLanding.Send,
        reduceMotion: Boolean,
        composerFrame: Rect?,
        queuedPillFrame: Rect?,
        density: Float,
        layOut: (String, Float) -> androidx.compose.ui.text.TextLayoutResult,
    ): Boolean {
        flightDensity = density
        reducesMotion = reduceMotion
        val text = send.text
        if ((send.flies || send.isQueued) && !transition.isPrepared && !reduceMotion && composerFrame != null &&
            composerFrame != Rect.Zero && text != null
        ) {
            transition.prepare(
                text = text,
                composer = composerFrame,
                expandedTopInsetPx = ComposerTunables.expandedTopInset * density,
                contentGutterPx = TranscriptMetrics.contentGutter.value * density,
                measure = layOut,
            )
        }
        // A queued send goes into the pill above the composer; its photos fade where they are.
        if (send.isQueued) {
            if (reduceMotion) {
                transition.cancelPrepared()
            } else {
                transition.dissolvePreparedImages()
                transition.takePrepared()?.let { fade(it, pill = queuedPillFrame ?: fallbackPill(it, density)) }
            }
            return false
        }
        val fliesImages = images.preparedToken != null
        if (!send.flies && !fliesImages) {
            transition.takePrepared()?.let { fade(it, pill = null) }
            return false
        }
        val itemID = send.itemID
        // Photos land even under Reduce Motion (they fade in at their slots); words only fly in full motion.
        if (itemID == null || !send.hasRow || !transition.isPrepared || (reduceMotion && !fliesImages) || !send.isFollowing) {
            transition.cancelPrepared()
            return false
        }
        val source = transition.takePrepared()
        // A later send to the same bubble replaces its flight.
        flights.filter { it.itemID == itemID }.forEach { flights.remove(it); images.finish(itemID) }
        landing.hide(itemID)
        flights += SendFlight(itemID, source, awaitsImages = fliesImages, lift = SendLift(enabled = !reduceMotion))
        startMotionLink()
        return true
    }

    /** The display's frame duration (s), for the motion link's first step. */
    var frameInterval: Double = 1.0 / 60

    private var motionLink: Job? = null

    /**
     * iOS `startMotionLink`: steps every flight from the next frame on, while any is in the air.
     * Started here as the send lands, the frame the field empties under the words, not after the
     * overlay's next composition. Like iOS's display link, each step runs to the frame's target
     * time, so the first frame already carries the words one frame along.
     */
    private fun startMotionLink() {
        if (motionLink?.isActive == true) return
        motionLink = scope.launch {
            var previous = 0L
            while (flights.isNotEmpty()) {
                withFrameNanos { now ->
                    val raw = if (previous == 0L) frameInterval else (now - previous) / 1e9
                    previous = now
                    step(AnimationTimeScale.shared.scaledDelta(raw))
                }
            }
        }
    }

    /** The overlay's root's place in the window (px): [SentImageFlight] works in root px, the markers in window px. */
    private fun rootShift(): Offset =
        coordinates?.takeIf { it.isAttached }?.findRootCoordinates()?.positionInWindow() ?: Offset.Zero

    /** A photo slot's landing marker (iOS `SendLandingProbe.imageID`), in root px. */
    private fun imageMarkers(): (String) -> Rect? {
        val shift = rootShift()
        return { id -> landing.marker(id)?.bounds?.takeIf { it.width > 0f && it.height > 0f }?.translate(-shift) }
    }

    /**
     * iOS `bubbleGap`: how far the top of the words in the bubble sits below the words' top as
     * they left the composer (px), where last seen; null without words.
     */
    private fun bubbleGap(flight: SendFlight): Float? {
        val source = flight.source ?: return null
        val destination = flight.destination ?: return null
        val vertical = TranscriptMetrics.bubbleVertical.value * flightDensity
        return destination.top + vertical - source.bounds.top
    }

    /** Set when the keyboard moves mid-flight (iOS `sendFlightsInterrupted`); cleared every frame. */
    private var interrupted = false

    /** The keyboard moved: every flight's words let go of their hold. */
    fun interrupt() {
        if (flights.isNotEmpty()) interrupted = true
    }

    /** iOS `flyIntoPill`'s target before the pill has appeared: trailing the row above the composer. */
    private fun fallbackPill(source: ComposerSendTransition.Prepared, density: Float): Rect? {
        val width = coordinates?.size?.width?.toFloat() ?: return null
        val origin = coordinates?.positionInWindow() ?: Offset.Zero
        val right = origin.x + width - 20 * density
        return Rect(right - 110 * density, source.bounds.top - 56 * density, right, source.bounds.top - 22 * density)
    }

    private fun fade(source: ComposerSendTransition.Prepared, pill: Rect?) {
        val send = FadingSend(source, pill)
        fading += send
        scope.launch {
            try {
                // iOS: into the pill on the flight's duration (critically damped); a micro fade otherwise.
                val spec = if (pill != null) MotionSpring(Motion.flight.curve().duration).spec<Float>() else Motion.microOut.animation(reduceMotion = false)
                send.progress.animateTo(1f, spec)
            } finally {
                fading -= send
            }
        }
    }

    /**
     * iOS `flyIntoQueuedPill`: a pending row that went into the queued pill shrinks into it from
     * where it sat, aiming just inside the list's bottom padding until the pill has appeared.
     */
    fun flyIntoQueuedPill(departure: SendLanding.PillDeparture, queuedPillFrame: Rect?, density: Float) {
        // Mid-flight from the composer, it lands first.
        departure.itemID?.let { id -> flights.filter { it.itemID == id }.forEach(::finish) }
        val pill = queuedPillFrame ?: landing.listEnd?.let { end ->
            Rect(end.right - (20 + 110) * density, end.bottom + 5 * density, end.right - 20 * density, end.bottom + (5 + 34) * density)
        }
        if (pill == null) return departure.release()
        val flight = PillFlight(departure, pill)
        pillFlights += flight
        scope.launch {
            try {
                flight.progress.animateTo(1f, Motion.flight.animation())
            } finally {
                pillFlights -= flight
                departure.release()
            }
        }
    }

    /** One frame of every flight (iOS `stepSendFlights`). */
    fun step(dt: Double) {
        clock += dt
        val landed = mutableListOf<SendFlight>()
        val markers = imageMarkers()
        val listMoving = interrupted || landing.isUserScrolling()
        val softness = (TranscriptTunables.sendLiftSoftness * flightDensity).toFloat()
        for (flight in flights) {
            val marker = landing.marker(flight.itemID)
            if (marker != null && marker.bounds.width > 0f) flight.destination = marker.bounds
            if (!flight.started) {
                if (!begin(flight, markers, dt)) {
                    landed += flight
                    continue
                }
                // Found its bubble: it moves on this frame (iOS starts the flight as it finds the cell).
                if (!flight.started) continue
            }
            val progress = flight.advance(dt, bubbleGap(flight), listMoving, softness)
            if (progress == null) {
                flight.progress = 1f
                flight.raised = 0f
                if (flight.fliesImages) images.step(flight.itemID, markers, progress = 1f)
                // Commit one fully landed frame before removing the copies (iOS `landedFrame`).
                val isLanded = images.isLanded(flight.itemID)
                if (flight.landedFrame && isLanded && images.handOff(flight.itemID, clock)) {
                    landed += flight
                } else {
                    flight.landedFrame = isLanded
                    // The photos' hand-off reveals the live row under them.
                    if (flight.fliesImages && !images.hidesContent(flight.itemID) && !flight.handingOff) {
                        flight.handingOff = true
                        landing.show(flight.itemID)
                    }
                }
                continue
            }
            flight.progress = progress
            if (flight.fliesImages) images.step(flight.itemID, markers, progress, lift = flight.raised)
        }
        interrupted = false
        landed.forEach(::finish)
        frame += 1
    }

    /**
     * Finds [flight]'s bubble and its photos' slots (iOS `beginLanding`, which lays the cell out
     * first). Returns false when it gives up: it then fades where it is.
     */
    private fun begin(flight: SendFlight, markers: (String) -> Rect?, dt: Double): Boolean {
        if (flight.source != null && flight.destination == null) predictedDestination(flight)?.let { flight.destination = it }
        val wordsReady = flight.source == null || flight.destination != null
        val imagesReady = !flight.awaitsImages || images.hasDestination(flight.itemID, markers)
        if (!wordsReady || !imagesReady) {
            // The bubble hasn't been laid out yet: a moment for the follower to bring it (iOS
            // `sendBubbleWaitMax`), however fast the display's frames come.
            flight.waited += dt
            if (flight.waited <= SendFlight.MAXIMUM_HOLD) return true
            // The photos' slots never came: the words fly alone, and the copies fade where they are.
            if (!wordsReady || flight.source == null) return false
            transition.dissolvePreparedImages()
            flight.awaitsImages = false
        }
        if (flight.awaitsImages) {
            flight.fliesImages = images.begin(flight.itemID, markers)
            flight.awaitsImages = false
            if (!flight.fliesImages && flight.source == null) return false
        }
        // Text and photos share one clock; 7.43 is the critical spring's 99.5% point.
        flight.omega = if (flight.fliesImages) {
            7.43 / (if (reducesMotion) Motion.reducedFade else TranscriptTunables.sendImageDuration)
        } else {
            TranscriptTunables.sendFlightOmega
        }
        flight.started = true
        return true
    }

    /**
     * Where a bubble still below the screen will land (iOS lays it out at the end and starts the
     * flight there): the words' size in a bubble at the list's end, trailing its gutter. The
     * flight retargets onto the real bubble as soon as it is laid out.
     */
    private fun predictedDestination(flight: SendFlight): Rect? {
        val end = landing.listEnd ?: return null
        val density = flightDensity.takeIf { it > 0f } ?: return null
        val gutter = TranscriptMetrics.contentGutter.value * density
        val vertical = TranscriptMetrics.bubbleVertical.value * density
        val words = flight.source?.bounds ?: return null
        val right = end.right - TranscriptTunables.contentGutter * density
        val bottom = end.bottom - TranscriptMetrics.turnHalf.value * density
        return Rect(right - words.width - 2 * gutter, bottom - words.height - 2 * vertical, right, bottom)
    }

    /** Lands [flight] now: its row shows again where it is (iOS `finishLanding`). */
    private fun finish(flight: SendFlight) {
        flights.remove(flight)
        images.finish(flight.itemID)
        if (flights.none { it.itemID == flight.itemID }) landing.show(flight.itemID)
        // A flight that never found its bubble fades where it is, its photos with it.
        if (!flight.started) {
            if (flight.awaitsImages) transition.dissolvePreparedImages()
            flight.source?.let { fade(it, pill = null) }
        }
    }

    /**
     * A capture no send took (the list never heard of it): it fades where it is rather than
     * linger over the composer.
     */
    fun dissolveIfStill(prepared: ComposerSendTransition.Prepared?, imagesToken: Any?) {
        if (imagesToken != null && images.preparedToken === imagesToken) transition.dissolvePreparedImages()
        if (prepared != null && transition.prepared === prepared) transition.takePrepared()?.let { fade(it, pill = null) }
    }

    /** Everything, flights included (the screen is going away; iOS `cancel()`). */
    fun cancel() {
        transition.cancel()
        motionLink?.cancel()
        flights.clear()
        fading.clear()
        pillFlights.forEach { it.departure.release() }
        pillFlights.clear()
        landing.showAll()
    }

    fun draw(scope: DrawScope, density: Float) {
        val origin = coordinates?.takeIf { it.isAttached }?.positionInWindow() ?: return
        // The next send's words, captured as they left the field: they stay where they were until they fly.
        transition.prepared?.let { scope.drawWords(it, it.bounds, 1f, origin) }
        for (send in fading) {
            val p = send.progress.value
            val pill = send.pill
            if (pill == null) {
                scope.drawWords(send.source, send.source.bounds, 1 - p, origin)
            } else {
                val from = send.source.bounds
                val scale = max(0.2f, min(1f, pill.height / max(from.height, 1f) * 0.6f))
                val center = lerp(from.center, pill.center, p)
                val s = 1 + (scale - 1) * p
                val size = Size(from.width * s, from.height * s)
                scope.drawWords(send.source, Rect(center - Offset(size.width / 2, size.height / 2), size), 1 - p, origin)
            }
        }
        for (flight in pillFlights) {
            val p = flight.progress.value
            val from = flight.departure.bounds
            val scale = max(0.2f, min(1f, flight.pill.height / max(from.height, 1f)))
            val s = 1 + (scale - 1) * p
            val center = lerp(from.center, flight.pill.center, p)
            val layer = flight.departure.layer
            layer.alpha = 1 - p
            scope.translate(center.x - origin.x - from.width * s / 2, center.y - origin.y - from.height * s / 2) {
                scale(s, s, pivot = Offset.Zero) { drawLayer(layer) }
            }
        }
        val gutter = TranscriptMetrics.contentGutter.value * density
        val vertical = TranscriptMetrics.bubbleVertical.value * density
        for (flight in flights) {
            // Handing over to the photos' slots, the row draws itself again.
            if (flight.handingOff) continue
            val source = flight.source
            val p = if (flight.started) flight.progress else 0f
            if (source == null) {
                // Photos alone (iOS `carryRemainder`): the rest of the row fades in where it is, around their slots.
                if (flight.started) drawRemainder(scope, flight, p, origin)
                continue
            }
            val destination = flight.destination
            if (destination == null) {
                // Waiting for the bubble's first layout: the words stay where they left the field.
                scope.drawWords(source, source.bounds, 1f, origin)
                continue
            }
            val from = source.bounds
            // Everything in the flight (words, bubble, attachments, label) aims [SendLift] higher together.
            val lift = if (flight.started) flight.raised else 0f
            val aim = destination.translate(0f, -lift)
            // The words shrink into the bubble's text as the bubble grows out of them.
            val text = Rect(aim.left + gutter, aim.top + vertical, aim.right - gutter, aim.bottom - vertical)
            // The words are gone by a third of the way and the bubble only fades in after a fifth,
            // so they and the bubble's own lines (wrapped at another width) are never both readable.
            scope.drawWords(source, ComposerSendTransition.interpolate(from, text, p), max(0f, 1 - 3 * p), origin)
            val bubble = landing.marker(flight.itemID)
            if (bubble != null) {
                val landingRect = ComposerSendTransition.interpolate(
                    Rect(from.left - gutter, from.top - vertical, from.right + gutter, from.bottom + vertical), aim, p,
                )
                bubble.layer.alpha = max(0f, (p - 0.2f) / 0.8f)
                scope.withTransform({
                    translate(landingRect.left - origin.x, landingRect.top - origin.y)
                    scale(landingRect.width / max(destination.width, 1f), landingRect.height / max(destination.height, 1f), Offset.Zero)
                }) { drawLayer(bubble.layer) }
            }
            // What sits above the bubble in the message itself (a sent image) fades in on top of its landing place.
            val surfaces = landing.marker(flight.itemID + SendLanding.SURFACES_SUFFIX)
            if (surfaces != null && surfaces.bounds.top < destination.top - 1) {
                val band = destination.top - surfaces.bounds.top
                surfaces.layer.alpha = p
                // The photos' copies own their slots (iOS `maskImages`): one visible owner per image.
                scope.withoutImageSlots(flight, origin, lift) {
                    withTransform({ translate(surfaces.bounds.left - origin.x, surfaces.bounds.top - origin.y - lift) }) {
                        clipRect(0f, 0f, surfaces.bounds.width, band) { drawLayer(surfaces.layer) }
                    }
                }
            }
            // A steering row's label settles down into place over the flight's last half.
            val label = landing.marker(flight.itemID + SendLanding.LABEL_SUFFIX)
            if (label != null && label.bounds.height > 1) {
                val chrome = ComposerSendTransition.chromeProgress(p)
                val rise = TranscriptTunables.promotionLabelRise * density * (1 - chrome)
                label.layer.alpha = chrome
                scope.withTransform({ translate(label.bounds.left - origin.x, label.bounds.top - origin.y - rise - lift) }) { drawLayer(label.layer) }
            }
        }
    }

    /**
     * iOS `carryRemainder`: a send of photos alone hides its row while the copies fly; the row's
     * other content (files, a location, a steering label) fades in where it is as they land,
     * with the photos' slots punched out.
     */
    private fun drawRemainder(scope: DrawScope, flight: SendFlight, p: Float, origin: Offset) {
        scope.withoutImageSlots(flight, origin) {
            for (suffix in listOf("", SendLanding.SURFACES_SUFFIX, SendLanding.LABEL_SUFFIX)) {
                val marker = landing.marker(flight.itemID + suffix) ?: continue
                if (marker.bounds.width <= 0f || marker.bounds.height <= 0f) continue
                marker.layer.alpha = p
                withTransform({ translate(marker.bounds.left - origin.x, marker.bounds.top - origin.y) }) { drawLayer(marker.layer) }
            }
        }
    }

    /** Draws [block] everywhere but [flight]'s photo slots (raised by [lift] px) while its copies fly (iOS `maskImages`' even-odd mask). */
    private fun DrawScope.withoutImageSlots(flight: SendFlight, origin: Offset, lift: Float = 0f, block: DrawScope.() -> Unit) {
        if (!flight.fliesImages) return block()
        val slots = images.tiles.filter { tile -> landing.marker(SendLanding.imageID(flight.itemID, tile.index)) != null }
        if (slots.isEmpty()) return block()
        val path = Path()
        for (tile in slots) {
            val slot = landing.marker(SendLanding.imageID(flight.itemID, tile.index))?.bounds ?: continue
            path.addRect(slot.translate(-origin).translate(0f, -lift))
        }
        clipPath(path, ClipOp.Difference) { block() }
    }
}

/** How long a capture waits for the list to take its send. */
private const val UNCLAIMED_CAPTURE_MILLIS = 1_000L

private fun lerp(from: Offset, to: Offset, p: Float) = Offset(from.x + (to.x - from.x) * p, from.y + (to.y - from.y) * p)

/** The captured words stretched into [target] (window px), like iOS's resizable snapshot. */
private fun DrawScope.drawWords(source: ComposerSendTransition.Prepared, target: Rect, alpha: Float, origin: Offset) {
    val layout = source.layout ?: return
    if (alpha <= 0f || source.bounds.width <= 0f || source.bounds.height <= 0f) return
    withTransform({
        translate(target.left - origin.x, target.top - origin.y)
        scale(target.width / source.bounds.width, target.height / source.bounds.height, Offset.Zero)
    }) {
        clipRect(0f, 0f, source.bounds.width, source.bounds.height) {
            drawText(layout, topLeft = source.origin - source.bounds.topLeft, alpha = alpha)
        }
    }
}

/**
 * Hosts the send flights over the transcript and the composer (iOS's window snapshots). Mounted
 * by `SessionView` above the composer; it registers itself as the list's [SendLanding] host.
 * Under Reduce Motion nothing flies: the composer captures nothing and sends arrive as rows.
 */
@Composable
internal fun SessionSendFlightOverlay(host: SessionComposerHost, bridge: SessionScrollEdgeBridge, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val flights = remember(host, bridge) { SessionSendFlights(host.sendTransition, bridge.sendLanding, scope) }
    val reduceMotion by rememberUpdatedState(rememberReduceMotion())
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = ComposerTunables.textStyle.textStyle.copy(color = OpenCodeTheme.colors.text)
    val currentStyle by rememberUpdatedState(style)

    val view = LocalView.current
    SideEffect {
        flights.frameInterval = 1.0 / (view.display?.refreshRate?.takeIf { it > 0f } ?: 60f)
        bridge.sendLanding.isAwaitingSend = { host.sendTransition.isPrepared }
        bridge.sendLanding.onQueuedPill = { departure -> flights.flyIntoQueuedPill(departure, host.queuedPillFrame, density.density) }
        bridge.sendLanding.onSend = { send ->
            val coordinates = flights.coordinates
            val composer = host.attachmentAnchors.composer.takeIf { it != Rect.Zero }?.let { root ->
                // Anchors are root px; the flight works in the window's.
                val shift = coordinates?.takeIf { it.isAttached }?.findRootCoordinates()?.positionInWindow() ?: Offset.Zero
                root.translate(shift)
            }
            flights.land(
                send = send,
                reduceMotion = reduceMotion,
                composerFrame = composer,
                queuedPillFrame = host.queuedPillFrame,
                density = density.density,
                layOut = { text, width ->
                    measurer.measure(
                        text,
                        currentStyle,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 4,
                        constraints = Constraints(maxWidth = width.roundToInt().coerceAtLeast(1)),
                    )
                },
            )
        }
    }
    DisposableEffect(flights) {
        onDispose {
            bridge.sendLanding.onSend = null
            bridge.sendLanding.onQueuedPill = null
            bridge.sendLanding.isAwaitingSend = { false }
            flights.cancel()
        }
    }
    val prepared = host.sendTransition.prepared
    // The send's photos as they left (their copies hold the composer's place until the list lands them).
    val imagesToken = if (host.sendTransition.images.isSending) host.sendTransition.images.preparedToken else null
    LaunchedEffect(flights, prepared, imagesToken) {
        if (prepared == null && imagesToken == null) return@LaunchedEffect
        delay(UNCLAIMED_CAPTURE_MILLIS)
        flights.dissolveIfStill(prepared, imagesToken)
    }
    // iOS `sendFlightsInterrupted`: the keyboard moving mid-flight lets the words go.
    val ime = WindowInsets.ime
    LaunchedEffect(flights, ime, density) {
        snapshotFlow { ime.getBottom(density) }.drop(1).collect { flights.interrupt() }
    }
    Spacer(
        modifier
            .onGloballyPositioned { flights.coordinates = it }
            .drawBehind {
                @Suppress("UNUSED_VARIABLE") val frame = flights.frame
                flights.draw(this, density.density)
            },
    )
}
