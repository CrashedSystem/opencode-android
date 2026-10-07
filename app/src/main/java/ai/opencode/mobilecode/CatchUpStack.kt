@file:PortedFrom("OpenCode/CatchUpStack.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.SystemImage
import ai.opencode.mobilecode.system.TextStyles
import ai.opencode.mobilecode.system.bold
import ai.opencode.mobilecode.system.symbolSize
import ai.opencode.mobilecode.tca.Effect
import ai.opencode.mobilecode.tca.Reduce
import ai.opencode.mobilecode.tca.Store
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/// Layout shared by the catch-up chrome (SwiftUI) and the inset cards (UIKit).
object CatchUpMetrics {
    val readColor = Color(red = 0.08f, green = 0.57f, blue = 0.42f)
    val readControlColor = Color(red = 0.06f, green = 0.46f, blue = 0.34f)
    val keepColor = Color(red = 0.34f, green = 0.38f, blue = 0.52f)
    val headerHeight = 52.dp
    val footerHeight = 74.dp
    val sideInset = 10.dp
    val cornerRadius = 32.dp

    /// Keeps the composer off the card's rounded bottom edge, which has no
    /// home-indicator safe area of its own.
    val composerClearance = PaddingValues(bottom = 12.dp)
}

/// The session card as the top of a catch-up stack.
class CatchUpStage(
    /// Sessions still to show; the first is the open card.
    val queue: List<String>,
    /// How the most recently handled card left, for Undo to reverse.
    val undoDirection: HomeFeature.CatchUpDisposition?,
    val onDismiss: (HomeFeature.CatchUpDisposition) -> Unit,
    val onUndo: () -> Unit,
    val onExit: () -> Unit,
) {
    val cardID: String? get() = queue.firstOrNull()
    val nextCardID: String? get() = queue.drop(1).firstOrNull()
}

/// Lets SwiftUI buttons play the same choreography as a swipe.
class CatchUpStageProxy {
    var dismiss: (HomeFeature.CatchUpDisposition) -> Unit = {}
    var undo: () -> Unit = {}

    fun dismissCard(disposition: HomeFeature.CatchUpDisposition) = dismiss(disposition)

    fun undoCard() = undo()
}

/// Inert stores that render the next card from its cached transcript. The
/// preview is the real session screen, so when it becomes the open card the
/// live screen, seeded from the same cache, replaces it without a visible seam.
class CatchUpPreviewStores {
    private class Entry(
        val session: OpenCodeSession,
        val items: List<OpenCodeTranscriptItem>?,
        val isRunning: Boolean,
        val store: Store<SessionFeature.State, SessionFeature.Action>,
    )

    private var cached: Entry? = null

    /// [locked] while the card behind is in flight or under the finger: a
    /// revealed card is one stable surface, so a cache refresh must not
    /// replace its store and lay it out again.
    fun store(`for`: OpenCodeSession, `in`: HomeFeature.State, locked: Boolean = false): Store<SessionFeature.State, SessionFeature.Action> {
        val session = `for`
        val home = `in`
        val items = home.transcriptCache[session.id]
        val isRunning = home.activeSessionIDs.contains(session.id)
        cached?.let { entry ->
            if (locked && entry.session.id == session.id) return entry.store
            if (entry.session == session && entry.items == items && entry.isRunning == isRunning) return entry.store
        }
        val state = SessionFeature.State(server = home.server, session = session, cachedItems = items, isRunning = isRunning)
        state.readsOnView = false
        val store = Store(initialState = { state }) {
            Reduce<SessionFeature.State, SessionFeature.Action> { action ->
                // Honor the renderer's first-frame event just like the live screen:
                // the provisional history spacer must disappear in both. Preview
                // stores perform no loading, request handling, or read receipts.
                if (action == SessionFeature.Action.firstContentFrameRendered) {
                    state.hasRenderedFirstContentFrame = true
                }
                Effect.none
            }
        }
        // There is one preview host; its store survives until its content changes.
        cached = Entry(session, items, isRunning, store)
        return store
    }
}

/// A single tinted surface follows the whole card, rather than floating over
/// its text. The icon and words keep the direction legible without color.
@Composable
internal fun CatchUpSwipeOverlay(
    title: String,
    symbol: String,
    tint: Color,
    leading: Boolean,
    alpha: () -> Float,
    modifier: Modifier = Modifier,
) {
    val opaque = LocalIncreasedContrast.current
    val depth = Color(red = tint.red * 0.70f, green = tint.green * 0.70f, blue = tint.blue * 0.78f)
    val washAlpha = if (opaque) 1f else 0.97f
    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha() }
            .clearAndSetSemantics {}
            .background(Brush.linearGradient(listOf(tint.copy(alpha = washAlpha), depth.copy(alpha = washAlpha)))),
    ) {
        Column(
            Modifier
                .align(if (leading) Alignment.TopStart else Alignment.TopEnd)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = if (leading) Alignment.Start else Alignment.End,
        ) {
            SystemImage(symbol, contentDescription = null, modifier = Modifier.symbolSize(50), tint = Color.White)
            Text(
                title,
                style = TextStyles.title.bold,
                color = Color.White,
                textAlign = if (leading) TextAlign.Start else TextAlign.End,
            )
        }
    }
}

/// Owns the stack's choreography: the drag, the fling that hands the next
/// card forward, Undo's return flight, and the preview card beneath.
///
/// The live session card is the only interactive card. The card behind it is
/// a preview of the next session; a dismissal flies a snapshot of the live
/// card away while the preview rises into place, and the live card (already
/// showing that same session) replaces the preview once it has landed.
@Stable
class CatchUpStackController {
    var reducedMotion = false

    /// The stage's width and the inset card's height, in pixels.
    var containerWidth = 1f
    var cardHeight = 1f

    /// Pixels per point: the lifts and offsets below are in points.
    var density = 1f
    var isDark = false

    /// Captures what the live card shows now, for a flight. Null when it
    /// can't (a test, or a card that never drew).
    var captureCard: suspend () -> ImageBitmap? = { null }

    /// A light impact as a swipe crosses the threshold or a fling commits.
    var impact: () -> Unit = {}
    var endEditing: () -> Unit = {}
    internal lateinit var scope: CoroutineScope

    // The live card. The container's full-screen morph writes these too, as
    // UIKit's card view has one transform and one alpha.
    var cardTranslationX by mutableFloatStateOf(0f)
        internal set
    var cardTranslationY by mutableFloatStateOf(0f)
        internal set

    /// Degrees.
    var cardRotation by mutableFloatStateOf(0f)
        internal set
    var cardScale by mutableFloatStateOf(1f)
        internal set
    var cardAlpha by mutableFloatStateOf(1f)
        internal set
    var isContentInteractive by mutableStateOf(true)
        internal set

    // The card behind.
    var previewHidden by mutableStateOf(true)
        private set
    var previewAlpha by mutableFloatStateOf(1f)
        private set
    var previewScale by mutableFloatStateOf(1f)
        private set
    var previewLift by mutableFloatStateOf(0f)
        private set
    var previewDimAlpha by mutableFloatStateOf(0f)
        private set

    /// The session the card behind shows.
    var previewID by mutableStateOf<String?>(null)
        private set
    var readBadgeAlpha by mutableFloatStateOf(0f)
        private set
    var keepBadgeAlpha by mutableFloatStateOf(0f)
        private set

    /// A snapshot of the card in flight: above the live card as it flies
    /// away, or behind it as Undo shrinks it into the preview's place.
    @Stable
    class Flight(val image: ImageBitmap?, val aboveCard: Boolean, val hasShadow: Boolean = true) {
        var translationX by mutableFloatStateOf(0f)
        var translationY by mutableFloatStateOf(0f)
        var rotation by mutableFloatStateOf(0f)
        var scale by mutableFloatStateOf(1f)
        var alpha by mutableFloatStateOf(1f)
        var dimAlpha by mutableFloatStateOf(0f)
    }

    var flight by mutableStateOf<Flight?>(null)
        private set

    private var stage: CatchUpStage? = null
    private var animator: Job? = null
    private var finishAnimator: (() -> Unit)? = null
    private var pendingCapture: Job? = null
    private var continueCapture: ((ImageBitmap?) -> Unit)? = null
    private var isTransitioning = false
    private var isDragging = false
    private var isPastThreshold = false
    private var lastMovement = 0L

    val isPreviewLocked: Boolean get() = isDragging || isTransitioning

    // MARK: State

    /// The card behind waits for the open card to settle, then rises into view.
    fun begin(stage: CatchUpStage) {
        this.stage = stage
        finishTransition()
        syncPreview()
        previewAlpha = 0f
        isContentInteractive = stage.cardID != null
    }

    fun revealPreview(animated: Boolean) {
        if (!animated || reducedMotion) {
            previewAlpha = 1f
            return
        }
        // Start tucked behind the front surface, then ease down into the stack.
        previewScale = 1f
        previewLift = 0f
        val startAlpha = previewAlpha
        val endScale = peekScale(0f)
        val endLift = peekLift(0f)
        run(duration = 0.36, onEnd = {
            previewAlpha = 1f
            previewScale = endScale
            previewLift = endLift
        }) { progress ->
            previewAlpha = lerp(startAlpha, 1f, progress)
            previewScale = lerp(1f, endScale, progress)
            previewLift = lerp(0f, endLift, progress)
        }
    }

    fun end() {
        finishTransition()
        isDragging = false
        stage = null
        previewHidden = true
        previewAlpha = 1f
        setBadges(0f)
        previewID = null
    }

    fun update(stage: CatchUpStage) {
        val previous = this.stage
        this.stage = stage
        // A running flight settles against the latest stage when it lands.
        if (isPreviewLocked) return
        syncPreview()
        if (stage.cardID == previous?.cardID) return
        if (stage.cardID == null) {
            fade(to = 0f)
            isContentInteractive = false
        } else if (previous?.cardID == null) {
            rise()
        } else {
            // Moved by another device (deleted or reordered): a quiet crossfade.
            crossfadeCard()
        }
    }

    private fun syncPreview() {
        val next = stage?.nextCardID
        previewHidden = next == null
        if (next == previewID) return
        previewID = next
        previewScale = peekScale(0f)
        previewLift = peekLift(0f)
        previewDimAlpha = dimAlpha
    }

    // MARK: Geometry

    private val dimAlpha: Float get() = if (isDark) 0.35f else 0.06f

    val threshold: Float get() = max(containerWidth * 0.3f, 1f)

    /// Smaller and lower, so a band of it shows beneath the open card.
    private fun peekScale(progress: Float): Float = if (reducedMotion) 1f else 0.92f + 0.08f * progress

    private fun peekLift(progress: Float): Float {
        if (reducedMotion) return 0f
        val height = max(cardHeight, 1f)
        return (height * 0.04f + 9f * density) * (1 - progress)
    }

    private fun dragTransform(x: Float, y: Float) {
        cardScale = 1f
        if (reducedMotion) {
            cardTranslationX = x
            cardTranslationY = 0f
            cardRotation = 0f
            return
        }
        cardTranslationX = x
        cardTranslationY = y * 0.3f
        cardRotation = degrees(-x / max(containerWidth, 1f) * 0.16f)
    }

    private fun direction(disposition: HomeFeature.CatchUpDisposition): Float =
        if (disposition == HomeFeature.CatchUpDisposition.markRead) 1f else -1f

    private fun offscreenX(disposition: HomeFeature.CatchUpDisposition): Float = direction(disposition) * containerWidth * 1.5f

    private fun offscreenRadians(disposition: HomeFeature.CatchUpDisposition): Float = -direction(disposition) * 0.32f

    private fun setBadges(offset: Float) {
        // Establish the decision surface early in the drag. Tying opacity to the
        // full commit distance left even a deliberate held swipe washed out.
        val progress = min(abs(offset) / (threshold * 0.45f), 1f)
        readBadgeAlpha = if (offset > 0) progress else 0f
        keepBadgeAlpha = if (offset < 0) progress else 0f
    }

    private fun applyDrag(x: Float, y: Float) {
        dragTransform(x, y)
        val progress = min(abs(x) / threshold, 1f)
        previewScale = peekScale(progress)
        previewLift = peekLift(progress)
        previewDimAlpha = dimAlpha * (1 - progress)
        setBadges(x)
        val past = progress >= 1
        if (past != isPastThreshold) {
            isPastThreshold = past
            if (past) impact()
        }
    }

    // MARK: Drag

    fun panBegan() {
        finishTransition()
        isDragging = true
        isPastThreshold = false
        endEditing()
        isContentInteractive = false
    }

    /// The finger's travel since it touched down, in pixels.
    fun panChanged(translationX: Float, translationY: Float) {
        lastMovement = System.nanoTime()
        applyDrag(translationX, translationY)
    }

    /// [velocityX] is in pixels per second.
    fun panEnded(translationX: Float, velocityX: Float) {
        isDragging = false
        // UIKit keeps reporting the last movement's speed after the finger
        // comes to rest; a held finger is not a flick.
        val rested = (System.nanoTime() - lastMovement) / 1e9 > 0.08
        val velocity = if (rested) 0f else velocityX
        val flick = abs(velocity / density) > 650 && (velocity > 0) == (translationX > 0) && abs(translationX / density) > 24
        if ((abs(translationX) > threshold || flick) && stage?.cardID != null) {
            fling(if (translationX > 0) HomeFeature.CatchUpDisposition.markRead else HomeFeature.CatchUpDisposition.keepUnread, velocity)
        } else {
            settleBack(velocity)
        }
    }

    fun panCancelled() {
        isDragging = false
        settleBack(0f)
    }

    private fun settleBack(velocity: Float) {
        val startX = cardTranslationX
        val startY = if (reducedMotion) 0f else cardTranslationY / 0.3f
        val offset = max(abs(startX), 1f)
        val relative = min(abs(velocity) / offset, 20f)
        run(duration = 0.45, dampingRatio = 0.78f, initialVelocity = relative, onEnd = {
            applyDrag(0f, 0f)
            isContentInteractive = true
            syncPreview()
        }) { progress ->
            applyDrag(lerp(startX, 0f, progress), lerp(startY, 0f, progress))
        }
    }

    // MARK: Dismiss

    /// Flings the open card away along the finger's path and brings the next
    /// one forward. Buttons and the header checkmark use the same flight.
    /// [velocity] is in pixels per second.
    fun fling(disposition: HomeFeature.CatchUpDisposition, velocity: Float = 0f) {
        finishTransition()
        val stage = stage
        if (stage == null || stage.cardID == null) return
        // A swipe already ticked as it crossed the threshold; a flick or a button commits with one now.
        if (!isPastThreshold) impact()
        isPastThreshold = false
        isTransitioning = true
        capture(skip = cardAlpha <= 0.01f) { image ->
            val next = stage.nextCardID
            if (previewID != next) previewID = next
            val snapshot = image?.let {
                Flight(it, aboveCard = true).apply {
                    translationX = cardTranslationX
                    translationY = cardTranslationY
                    rotation = cardRotation
                }
            }
            flight = snapshot
            cardAlpha = 0f
            resetTransform()
            setBadges(0f)
            endEditing()
            isContentInteractive = false
            previewHidden = next == null
            stage.onDismiss(disposition)

            // CGAffineTransform's translatedBy(x: 0, y: start.ty) on the rotated
            // exit transform: the start's vertical offset follows the rotation.
            val radians = offscreenRadians(disposition)
            val startX = snapshot?.translationX ?: 0f
            val startY = snapshot?.translationY ?: 0f
            val startRotation = snapshot?.rotation ?: 0f
            val targetX = offscreenX(disposition) - sin(radians) * startY
            val targetY = 40f * density + cos(radians) * startY
            val distance = max(abs(targetX - startX), 1f)
            val towardExit = (velocity > 0) == (targetX > 0)
            val relative = if (towardExit) min(abs(velocity) / distance, 12f) else 0f
            val startScale = previewScale
            val startLift = previewLift
            val startDim = previewDimAlpha
            run(duration = if (reducedMotion) 0.25 else 0.5, initialVelocity = relative, onEnd = {
                flight = null
                previewScale = 1f
                previewLift = 0f
                previewDimAlpha = 0f
                land()
            }) { progress ->
                snapshot?.let {
                    if (reducedMotion) {
                        it.alpha = 1 - progress
                    } else {
                        it.translationX = lerp(startX, targetX, progress)
                        it.translationY = lerp(startY, targetY, progress)
                        it.rotation = lerp(startRotation, degrees(radians), progress)
                    }
                }
                previewScale = lerp(startScale, 1f, progress)
                previewLift = lerp(startLift, 0f, progress)
                previewDimAlpha = lerp(startDim, 0f, progress)
            }
        }
    }

    /// The preview now sits exactly where the open card belongs, showing the
    /// session the live card has been loading underneath it; swap them.
    private fun land() {
        val stage = stage
        if (stage == null || stage.cardID == null) {
            isTransitioning = false
            previewHidden = true
            isContentInteractive = false
            return
        }
        isContentInteractive = true
        val start = cardAlpha
        run(duration = 0.16, curve = tween(160, easing = EaseInOut), onEnd = {
            cardAlpha = 1f
            isTransitioning = false
            // Behind the open card again, now showing the card after it.
            previewID = null
            syncPreview()
            revealPreview(animated = true)
        }) { progress -> cardAlpha = lerp(start, 1f, progress) }
    }

    // MARK: Undo

    /// Returns the last handled card from the side it left, while the open
    /// card shrinks back into the place behind it.
    fun undo() {
        finishTransition()
        val stage = stage ?: return
        val direction = stage.undoDirection
        if (direction == null || reducedMotion) {
            if (stage.undoDirection != null) crossfadeCard()
            stage.onUndo()
            return
        }
        isTransitioning = true
        capture(skip = stage.cardID == null || cardAlpha <= 0.01f) { image ->
            val snapshot = image?.let { Flight(it, aboveCard = false) }
            flight = snapshot
            if (stage.cardID != null) {
                // The card now behind the returning one is the current card.
                previewID = stage.cardID
                previewScale = peekScale(0f)
                previewLift = peekLift(0f)
                previewDimAlpha = dimAlpha
                previewHidden = false
            }
            stage.onUndo()
            cardAlpha = 1f
            cardScale = 1f
            cardTranslationX = offscreenX(direction)
            cardTranslationY = 40f * density
            cardRotation = degrees(offscreenRadians(direction))
            isContentInteractive = true
            val startX = cardTranslationX
            val startY = cardTranslationY
            val startRotation = cardRotation
            val endScale = peekScale(0f)
            val endLift = peekLift(0f)
            val endDim = dimAlpha
            run(duration = 0.55, dampingRatio = 0.86f, onEnd = {
                resetTransform()
                flight = null
                isTransitioning = false
                syncPreview()
            }) { progress ->
                cardTranslationX = lerp(startX, 0f, progress)
                cardTranslationY = lerp(startY, 0f, progress)
                cardRotation = lerp(startRotation, 0f, progress)
                snapshot?.let {
                    it.scale = lerp(1f, endScale, progress)
                    it.translationY = lerp(0f, endLift, progress)
                    it.dimAlpha = lerp(0f, endDim, progress)
                }
            }
        }
    }

    // MARK: Arrivals

    /// A card appears after the stack was finished: it rises from the back.
    private fun rise() {
        val startScale = peekScale(0f)
        val startLift = peekLift(0f)
        cardScale = startScale
        cardTranslationX = 0f
        cardTranslationY = startLift
        cardRotation = 0f
        cardAlpha = 0f
        isContentInteractive = true
        run(duration = 0.4, onEnd = {
            resetTransform()
            cardAlpha = 1f
        }) { progress ->
            cardScale = lerp(startScale, 1f, progress)
            cardTranslationY = lerp(startLift, 0f, progress)
            cardAlpha = progress
        }
    }

    private fun crossfadeCard() {
        if (cardAlpha <= 0.01f) return
        capture { image ->
            val snapshot = image?.let { Flight(it, aboveCard = true, hasShadow = false) } ?: return@capture
            snapshot.translationX = cardTranslationX
            snapshot.translationY = cardTranslationY
            snapshot.rotation = cardRotation
            flight = snapshot
            run(duration = 0.25, curve = tween(250, easing = EaseOut), onEnd = { flight = null }) { progress ->
                snapshot.alpha = 1 - progress
            }
        }
    }

    private fun fade(to: Float) {
        val start = cardAlpha
        run(duration = 0.25, curve = tween(250, easing = EaseOut), onEnd = { cardAlpha = to }) { progress ->
            cardAlpha = lerp(start, to, progress)
        }
    }

    // MARK: Helpers

    private fun resetTransform() {
        cardTranslationX = 0f
        cardTranslationY = 0f
        cardRotation = 0f
        cardScale = 1f
    }

    /// UIKit snapshots a view synchronously; a Compose layer renders to a
    /// bitmap asynchronously, so the step continues once it has. A capture
    /// that finishes at once (or is skipped) continues before this returns.
    private fun capture(skip: Boolean = false, then: (ImageBitmap?) -> Unit) {
        if (skip) {
            then(null)
            return
        }
        continueCapture = then
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val image = runCatching { captureCard() }.getOrNull()
            resumeCapture(image)
        }
        if (continueCapture != null) pendingCapture = job
    }

    private fun resumeCapture(image: ImageBitmap?) {
        val then = continueCapture ?: return
        continueCapture = null
        pendingCapture = null
        then(image)
    }

    /// Runs one step of the choreography as a 0-to-1 progress. Starting
    /// another, or a new gesture, jumps it to its end first.
    private fun run(
        duration: Double,
        dampingRatio: Float = 1f,
        initialVelocity: Float = 0f,
        curve: AnimationSpec<Float>? = null,
        onEnd: () -> Unit,
        frame: (Float) -> Unit,
    ) {
        finishTransition()
        finishAnimator = onEnd
        animator = scope.launch {
            animate(
                initialValue = 0f,
                targetValue = 1f,
                initialVelocity = initialVelocity,
                animationSpec = curve ?: uikitSpring(duration, dampingRatio),
            ) { value, _ -> frame(value) }
            animator = null
            finishAnimator = null
            onEnd()
        }
    }

    /// Jumps a running flight to its end so a new gesture starts from rest.
    fun finishTransition() {
        // A capture still rendering continues without its snapshot.
        pendingCapture?.let {
            it.cancel()
            resumeCapture(null)
        }
        // A landing chains a short reveal, so settle until nothing is running.
        while (true) {
            val running = animator ?: return
            val end = finishAnimator
            animator = null
            finishAnimator = null
            running.cancel()
            end?.invoke()
        }
    }

    companion object {
        /// UIKit's `UIViewPropertyAnimator(duration:dampingRatio:)` for a 0-to-1
        /// progress: a spring that comes to rest, within 0.1% of its travel,
        /// when the duration ends.
        fun uikitSpring(duration: Double, dampingRatio: Float = 1f): SpringSpec<Float> {
            val omega = if (dampingRatio >= 1f) CriticalSettle / duration else ln(1_000.0) / (dampingRatio * duration)
            return spring(dampingRatio = dampingRatio, stiffness = (omega * omega).toFloat(), visibilityThreshold = 0.001f)
        }

        /// ωt at which a critically damped spring is within 0.1% of its travel.
        private const val CriticalSettle = 9.233

        /// UIKit's `.easeInOut` and `.easeOut` curves.
        val EaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
        val EaseOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)

        private fun degrees(radians: Float): Float = (radians * 180 / PI).toFloat()

        private fun lerp(start: Float, end: Float, progress: Float): Float = start + (end - start) * progress
    }
}
