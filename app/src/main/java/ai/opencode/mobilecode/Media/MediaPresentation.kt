@file:PortedFrom("OpenCode/Media/MediaPresentation.swift", "8adf650")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.ForcedAppearance
import ai.opencode.mobilecode.system.RoundedRectangle
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

// MARK: - Sources

/// A view full-screen media zooms out of and back into. Cells are reused,
/// so each view carries the media it shows now and lookups check it.
@Stable
class MediaZoomSourceView {
    /// The first is the media this view shows; the rest are pages it stands
    /// in for, like the collage's “+2” tile.
    var mediaIDs: List<String> = emptyList()

    /// On screen, in pixels; null until laid out.
    var frame: Rect? = null
    var cornerRadius = 0f
    var alpha by mutableStateOf(1f)
}

class MediaZoomSources {
    private val views = mutableSetOf<MediaZoomSourceView>()

    fun add(view: MediaZoomSourceView) {
        views += view
    }

    fun remove(view: MediaZoomSourceView) {
        views -= view
    }

    /// The on-screen view showing `id`, preferring one that shows it over one
    /// standing in for it.
    fun view(forID: String?): MediaZoomSourceView? {
        val id = forID ?: return null
        val candidates = views.filter { it.mediaIDs.contains(id) && it.frame != null }
        return candidates.firstOrNull { it.mediaIDs.firstOrNull() == id } ?: candidates.firstOrNull()
    }

    companion object {
        val shared = MediaZoomSources()
    }
}

/// An image the full-screen viewer zooms out of and back into. It's a real
/// view so the zoom transition can lift it out of the transcript.
@Composable
fun MediaThumbnail(
    image: ImageBitmap,
    /// The media this shows, then any pages it stands in for.
    ids: List<String>,
    modifier: Modifier = Modifier,
    /// Fill crops to the frame; fit sizes itself to the image, at most
    /// `maxHeight` tall.
    fits: Boolean = false,
    maxHeight: Dp = Dp.Infinity,
    cornerRadius: Dp = 0.dp,
) {
    val view = mediaZoomSourceView(ids, cornerRadius)
    Image(
        bitmap = image,
        contentDescription = null,
        modifier = modifier
            .then(if (fits) Modifier.fitting(image, maxHeight) else Modifier)
            .onGloballyPositioned { view.frame = it.boundsOnScreen() }
            .graphicsLayer { alpha = view.alpha }
            .clip(RoundedRectangle(cornerRadius)),
        contentScale = if (fits) ContentScale.Fit else ContentScale.Crop,
    )
}

/// Marks a card or row that opens full-screen media, so the viewer zooms
/// out of it and back into it.
@Composable
fun Modifier.mediaZoomSource(id: String, cornerRadius: Dp = 0.dp): Modifier {
    val view = mediaZoomSourceView(listOf(id), cornerRadius)
    return onGloballyPositioned { view.frame = it.boundsOnScreen() }
}

@Composable
private fun mediaZoomSourceView(ids: List<String>, cornerRadius: Dp): MediaZoomSourceView {
    val view = remember { MediaZoomSourceView() }
    val radius = with(LocalDensity.current) { cornerRadius.toPx() }
    SideEffect {
        view.mediaIDs = ids
        view.cornerRadius = radius
    }
    DisposableEffect(view) {
        MediaZoomSources.shared.add(view)
        onDispose { MediaZoomSources.shared.remove(view) }
    }
    return view
}

/// `sizeThatFits` for a fitted image: as wide as offered, no taller than
/// `maxHeight`, in the image's aspect ratio.
private fun Modifier.fitting(image: ImageBitmap, maxHeight: Dp): Modifier = layout { measurable, constraints ->
    val width = if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat() else image.width.toFloat()
    val bound = if (constraints.hasBoundedHeight) constraints.maxHeight.toFloat() else Float.POSITIVE_INFINITY
    val height = min(bound, if (maxHeight == Dp.Infinity) Float.POSITIVE_INFINITY else maxHeight.toPx())
    val scale = if (image.width > 0 && image.height > 0) min(width / image.width, height / image.height) else 0f
    val fitted = Constraints.fixed((image.width * scale).roundToInt(), (image.height * scale).roundToInt())
    val placeable = measurable.measure(fitted)
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

private fun LayoutCoordinates.boundsOnScreen(): Rect {
    val origin = localToScreen(Offset.Zero)
    return Rect(origin, Size(size.width.toFloat(), size.height.toFloat()))
}

// MARK: - Viewer

/// What the presentation needs from the full-screen content, and what the
/// content needs from the presentation.
@Stable
class MediaViewerState(sourceID: String?) {
    /// What's on screen, for the flight back into the transcript.
    var sourceID by mutableStateOf(sourceID)
        private set

    /// The image on screen, flown in and out in place of the content.
    var image: ImageBitmap? = null
        private set

    /// A zoomed-in image pans instead of dismissing.
    var isZoomed by mutableStateOf(false)

    /// Close and the page position step aside while a drag dismisses.
    var chromeHidden by mutableStateOf(false)
    var dismiss: () -> Unit = {}
    internal var onShow: () -> Unit = {}

    /// The content moved to another page or finished loading its image.
    fun show(sourceID: String?, image: ImageBitmap?) {
        val moved = sourceID != this.sourceID
        this.sourceID = sourceID
        this.image = image
        isZoomed = false
        if (moved) onShow()
    }
}

/// `@Environment(\.mediaViewer)`.
val LocalMediaViewer = staticCompositionLocalOf<MediaViewerState?> { null }

/// Full-screen media and files. Media opens the Photos way: it flies out of
/// what was tapped, follows a drag down while shrinking and letting the
/// session show through, and flies back on release (or springs back from a
/// short drag). Documents use the system zoom, whose swipe down dismisses.
/// Under Reduce Motion both cross-fade, and a drag down fades them out.
object MediaPresenter {
    internal var presented by mutableStateOf<MediaViewerController?>(null)

    fun present(
        sourceID: String?,
        isMedia: Boolean,
        reducesMotion: Boolean,
        darkColorScheme: Boolean,
        content: @Composable (MediaViewerState) -> Unit,
    ) {
        // One viewer at a time.
        if (presented != null) return
        presented = MediaViewerController(content, MediaViewerState(sourceID), isMedia, reducesMotion, darkColorScheme)
    }
}

/// Where the presented viewer draws: its own window above the whole app, as
/// iOS presents over the root view controller.
@Composable
fun MediaPresenterHost() {
    val controller = MediaPresenter.presented ?: return
    key(controller) {
        controller.Content(onDismissed = { if (MediaPresenter.presented === controller) MediaPresenter.presented = null })
    }
}

/// The Photos drag: how media shrinks and lets the session show through as
/// it's pulled down, and when letting go puts it away.
data class MediaDismissDrag(
    /// How far down the media is at its smallest and the session fully shows.
    val range: Double,
) {
    private fun progress(distance: Double): Double = min(max(distance / range, 0.0), 1.0)

    fun scale(at: Double): Double = 1 - (1 - minimumScale) * progress(at)

    /// The drag distance a scale corresponds to, to pick up a spring-back.
    fun distance(atScale: Double): Double = min(max((1 - atScale) / (1 - minimumScale), 0.0), 1.0) * range

    fun backdropAlpha(at: Double): Double = 1 - progress(at)

    companion object {
        const val minimumScale = 0.6
        const val dismissDistance = 120.0
        const val dismissVelocity = 900.0

        operator fun invoke(screenHeight: Double) = MediaDismissDrag(range = max(screenHeight * 0.5, 1.0))

        /// Far enough, or flicked down; a flick back up keeps it.
        fun dismisses(distance: Double, velocity: Double): Boolean =
            (distance > dismissDistance && velocity > -300) || velocity > dismissVelocity
    }
}

/// The presented viewer. Positions are in pixels in the viewer's window;
/// `MediaDismissDrag` works in points, which are dp here.
class MediaViewerController(
    private val content: @Composable (MediaViewerState) -> Unit,
    val state: MediaViewerState,
    private val isMedia: Boolean,
    private val reducesMotion: Boolean,
    private val darkColorScheme: Boolean,
) {
    enum class Style {
        /// Flies out of its source and is dragged down to dismiss.
        photos,

        /// The system zoom, for documents. Android has none, so the document
        /// grows out of its card and shrinks back into it.
        zoom,

        /// Reduce Motion: cross-fades, and a drag down fades it out.
        fade,
    }

    val style = if (reducesMotion) Style.fade else if (isMedia) Style.photos else Style.zoom

    /// The image in flight, above the content: from one frame to another.
    private class Flight(val image: ImageBitmap, val from: Rect, val to: Rect, val fromRadius: Float, val toRadius: Float) {
        val progress = Animatable(0f)
        val alpha = Animatable(1f)
        val frame: Rect get() = lerp(from, to, progress.value)
        val radius: Float get() = lerp(fromRadius, toRadius, progress.value)
    }

    private val backdrop = Animatable(if (style == Style.zoom) 1f else 0f)
    private val hostAlpha = Animatable(if (style == Style.zoom) 1f else 0f)
    private val hostScale = Animatable(1f)
    private val hostX = Animatable(0f)
    private val hostY = Animatable(0f)
    private val viewAlpha = Animatable(1f)
    private var flight by mutableStateOf<Flight?>(null)
    private var hiddenSource: MediaZoomSourceView? = null
    private var hasOpened = false
    private var isLeaving = false

    /// Continuity for a drag that catches a spring-back mid-flight.
    private var dragCarry = Offset.Zero
    private var dragCarryY = 0.0
    private var dragAnchor = Offset.Zero
    private lateinit var scope: CoroutineScope
    private var bounds = Rect.Zero
    private var screenOrigin = Offset.Zero
    private var density = 1f
    private var onDismissed: () -> Unit = {}

    init {
        state.dismiss = { close() }
    }

    @Composable
    fun Content(onDismissed: () -> Unit) {
        this.onDismissed = onDismissed
        Dialog(
            onDismissRequest = { close() },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            val view = LocalView.current
            val window = (view.parent as? DialogWindowProvider)?.window
            SideEffect {
                if (window != null) {
                    window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                    window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    window.setWindowAnimations(0)
                    WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !(isMedia || darkColorScheme)
                }
            }
            scope = rememberCoroutineScope()
            density = LocalDensity.current.density
            val reduceMotion = LocalReduceMotion.current || reducesMotion
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = viewAlpha.value }
                    .onGloballyPositioned {
                        bounds = Rect(Offset.Zero, Size(it.size.width.toFloat(), it.size.height.toFloat()))
                        screenOrigin = it.localToScreen(Offset.Zero)
                    }
                    .then(if (style == Style.zoom) Modifier else Modifier.pointerInput(Unit) { dismissPan() }),
            ) {
                if (style != Style.zoom) {
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = backdrop.value }.background(Color.Black))
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = hostAlpha.value
                            scaleX = hostScale.value
                            scaleY = hostScale.value
                            translationX = hostX.value
                            translationY = hostY.value
                        },
                ) {
                    ForcedAppearance(dark = darkColorScheme) {
                        CompositionLocalProvider(LocalMediaViewer provides state, LocalReduceMotion provides reduceMotion) {
                            content(state)
                        }
                    }
                }
                flight?.let { FlightImage(it) }
            }
            LaunchedEffect(Unit) { opened() }
            DisposableEffect(Unit) { onDispose { restoreSource() } }
        }
    }

    @Composable
    private fun FlightImage(flight: Flight) {
        val density = LocalDensity.current
        Image(
            bitmap = flight.image,
            contentDescription = null,
            modifier = Modifier
                .layout { measurable, _ ->
                    val frame = flight.frame
                    val placeable = measurable.measure(Constraints.fixed(frame.width.roundToInt().coerceAtLeast(0), frame.height.roundToInt().coerceAtLeast(0)))
                    layout(placeable.width, placeable.height) { placeable.place(IntOffset(frame.left.roundToInt(), frame.top.roundToInt())) }
                }
                .graphicsLayer {
                    alpha = flight.alpha.value
                    shape = RoundedRectangle(with(density) { flight.radius.toDp() })
                    clip = true
                },
            contentScale = ContentScale.Crop,
        )
    }

    private fun opened() {
        if (style == Style.photos) {
            state.onShow = { hideSource() }
            if (!hasOpened) {
                hasOpened = true
                flyIn()
            }
            return
        }
        scope.launch {
            if (style == Style.fade) {
                // `.crossDissolve`.
                launch { backdrop.animateTo(1f, tween(300, easing = EaseOut)) }
                hostAlpha.animateTo(1f, tween(300, easing = EaseOut))
                return@launch
            }
            zoomIn()
        }
    }

    // MARK: Photos

    private val source: MediaZoomSourceView? get() = MediaZoomSources.shared.view(forID = state.sourceID)

    /// Like Photos, the thumbnail leaves the transcript while it's up.
    private fun hideSource() {
        val source = source
        if (source === hiddenSource) return
        hiddenSource?.alpha = 1f
        source?.alpha = 0f
        hiddenSource = source
    }

    private fun restoreSource() {
        hiddenSource?.alpha = 1f
        hiddenSource = null
    }

    /// Where the media rests: the image fitted to the screen, or the screen.
    private val restingFrame: Rect
        get() {
            val image = state.image
            if (image == null || image.width <= 0 || image.height <= 0) return bounds
            return fitted(image.width.toFloat(), image.height.toFloat(), bounds)
        }

    private fun sourceFrame(source: MediaZoomSourceView): Rect = source.frame?.translate(-screenOrigin) ?: bounds

    private fun flyIn() {
        val source = source
        hideSource()
        val image = state.image
        if (source != null && image != null) {
            val flight = Flight(image, sourceFrame(source), restingFrame, source.cornerRadius, 0f)
            this.flight = flight
            scope.launch {
                listOf(
                    launch { flight.progress.animateTo(1f, swiftSpring(0.42, 0.1)) },
                    launch { backdrop.animateTo(1f, swiftSpring(0.42, 0.1)) },
                ).joinAll()
                hostAlpha.snapTo(1f)
                flight.alpha.animateTo(0f, tween(120))
                if (this@MediaViewerController.flight === flight) this@MediaViewerController.flight = null
            }
            return
        }
        scope.launch {
            snapTransform(source?.let { transform(from = restingFrame, to = sourceFrame(it)) } ?: Transform(0.94f, 0f, 0f))
            listOf(
                launch { animateTransform(Transform.identity, swiftSpring(0.42, 0.1)) },
                launch { hostAlpha.animateTo(1f, swiftSpring(0.42, 0.1)) },
                launch { backdrop.animateTo(1f, swiftSpring(0.42, 0.1)) },
            ).joinAll()
        }
    }

    /// The system zoom's open: the document grows out of its card.
    private suspend fun zoomIn() {
        val source = source ?: return
        snapTransform(transform(from = bounds, to = sourceFrame(source)))
        hostAlpha.snapTo(0f)
        scope.launch { hostAlpha.animateTo(1f, tween(200)) }
        animateTransform(Transform.identity, swiftSpring(0.42, 0.1))
    }

    private data class Transform(val scale: Float, val x: Float, val y: Float) {
        companion object {
            val identity = Transform(1f, 0f, 0f)
        }
    }

    /// The transform about the view's center that maps `from` onto `to`.
    private fun transform(from: Rect, to: Rect): Transform {
        val scale = min(to.width / max(from.width, 1f), to.height / max(from.height, 1f))
        val center = bounds.center
        return Transform(
            scale,
            to.center.x - center.x - scale * (from.center.x - center.x),
            to.center.y - center.y - scale * (from.center.y - center.y),
        )
    }

    private suspend fun snapTransform(transform: Transform) {
        hostScale.snapTo(transform.scale)
        hostX.snapTo(transform.x)
        hostY.snapTo(transform.y)
    }

    private suspend fun animateTransform(transform: Transform, spec: AnimationSpec<Float>) {
        scope.launch { hostX.animateTo(transform.x, spec) }
        scope.launch { hostY.animateTo(transform.y, spec) }
        hostScale.animateTo(transform.scale, spec)
    }

    private val drag: MediaDismissDrag get() = MediaDismissDrag(screenHeight = (bounds.height / density).toDouble())

    private fun points(pixels: Float): Double = (pixels / density).toDouble()

    private var springBack: Job? = null

    private fun dragBegan(location: Offset) {
        // Catch a spring-back where it is.
        springBack?.cancel()
        scope.launch {
            hostScale.stop()
            hostX.stop()
            hostY.stop()
            backdrop.stop()
        }
        dragAnchor = location
        val current = hostScale.value
        val center = bounds.center
        dragCarryY = drag.distance(atScale = current.toDouble())
        dragCarry = Offset(
            hostX.value - (dragAnchor.x - center.x) * (1 - current),
            hostY.value - (dragAnchor.y - center.y) * (1 - current),
        )
        setChromeHidden(true)
    }

    private fun dragChanged(translation: Offset) {
        val y = points(translation.y) + dragCarryY
        val scale = drag.scale(at = y).toFloat()
        val center = bounds.center
        // Scaled about the finger, so the spot it holds stays under it.
        scope.launch {
            hostScale.snapTo(scale)
            hostX.snapTo((dragAnchor.x - center.x) * (1 - scale) + translation.x + dragCarry.x)
            hostY.snapTo((dragAnchor.y - center.y) * (1 - scale) + translation.y + dragCarry.y)
            backdrop.snapTo(drag.backdropAlpha(at = y).toFloat())
        }
    }

    private fun dragEnded(translation: Offset, velocity: Offset) {
        val y = points(translation.y) + dragCarryY
        if (MediaDismissDrag.dismisses(distance = y, velocity = points(velocity.y))) {
            flyOut(velocity)
            return
        }
        springBack = scope.launch {
            val spec = swiftSpring(0.4, 0.15)
            listOf(
                launch { animateTransform(Transform.identity, spec) },
                launch { backdrop.animateTo(1f, spec) },
            ).joinAll()
        }
        setChromeHidden(false)
    }

    private fun setChromeHidden(hidden: Boolean) {
        if (state.chromeHidden == hidden) return
        state.chromeHidden = hidden
    }

    private fun close() {
        when (style) {
            Style.photos -> flyOut(Offset.Zero)
            Style.zoom -> zoomOut()
            Style.fade -> fadeOut()
        }
    }

    private fun finish() {
        restoreSource()
        onDismissed()
    }

    private fun flyOut(velocity: Offset) {
        if (isLeaving) return
        isLeaving = true
        setChromeHidden(true)
        springBack?.cancel()
        val current = Transform(hostScale.value, hostX.value, hostY.value)
        val center = bounds.center
        val resting = restingFrame
        val onScreen = Rect(
            center.x + (resting.left - center.x) * current.scale + current.x,
            center.y + (resting.top - center.y) * current.scale + current.y,
            center.x + (resting.right - center.x) * current.scale + current.x,
            center.y + (resting.bottom - center.y) * current.scale + current.y,
        )
        val source = source
        val target = source?.let { sourceFrame(it) }

        fun springVelocity(toward: Offset): Float {
            val distance = toward - onScreen.center
            val length = distance.x * distance.x + distance.y * distance.y
            if (length <= 1) return 0f
            return min(max((velocity.x * distance.x + velocity.y * distance.y) / length, 0f), 8f)
        }

        scope.launch {
            listOf(hostScale, hostX, hostY, backdrop).forEach { it.stop() }
            val image = state.image
            if (image != null && target != null) {
                val flight = Flight(image, onScreen, target, 0f, source?.cornerRadius ?: 0f)
                this@MediaViewerController.flight = flight
                hostAlpha.snapTo(0f)
                val spec = swiftSpring(0.4, 0.0)
                listOf(
                    launch { flight.progress.animateTo(1f, spec, initialVelocity = springVelocity(target.center)) },
                    launch { backdrop.animateTo(0f, spec) },
                ).joinAll()
            } else if (image != null) {
                // Nowhere to land: carry on the way it was thrown and fade.
                val flight = Flight(image, onScreen, scaled(onScreen.translate(0f, bounds.height * 0.25f), 0.8f), 0f, 0f)
                this@MediaViewerController.flight = flight
                hostAlpha.snapTo(0f)
                val spec = swiftSpring(0.35, 0.0)
                listOf(
                    launch { flight.progress.animateTo(1f, spec) },
                    launch { flight.alpha.animateTo(0f, spec) },
                    launch { backdrop.animateTo(0f, spec) },
                ).joinAll()
            } else {
                val spec = swiftSpring(0.4, 0.0)
                val destination = if (target != null) {
                    transform(from = resting, to = target)
                } else {
                    current.copy(y = current.y + bounds.height * 0.25f)
                }
                listOf(
                    launch { animateTransform(destination, spec) },
                    launch { hostAlpha.animateTo(0f, spec) },
                    launch { backdrop.animateTo(0f, spec) },
                ).joinAll()
            }
            finish()
        }
    }

    /// The system zoom's close: the document shrinks back into its card.
    private fun zoomOut() {
        if (isLeaving) return
        isLeaving = true
        val target = source?.let { sourceFrame(it) }
        scope.launch {
            val spec = swiftSpring(0.4, 0.0)
            listOf(
                launch { if (target != null) animateTransform(transform(from = bounds, to = target), spec) },
                launch { hostAlpha.animateTo(0f, tween(250)) },
            ).joinAll()
            finish()
        }
    }

    private fun fadeOut() {
        if (isLeaving) return
        isLeaving = true
        scope.launch {
            viewAlpha.animateTo(0f, tween(300, easing = LinearEasing))
            finish()
        }
    }

    // MARK: Gesture

    /// `UIPanGestureRecognizer` that begins only for a drag heading down, and
    /// never for a zoomed image. It claims the drag before the pager or the
    /// page sees it, so pages turn only once a drag has shown it isn't a
    /// dismissal.
    private suspend fun PointerInputScope.dismissPan() {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (isLeaving) return@awaitEachGesture
            val tracker = VelocityTracker()
            tracker.addPointerInputChange(down)
            var began = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (!began && event.changes.size > 1) return@awaitEachGesture
                val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                tracker.addPointerInputChange(change)
                val translation = change.position - down.position
                if (!change.pressed) {
                    if (began) {
                        change.consume()
                        val velocity = tracker.calculateVelocity()
                        ended(translation, Offset(velocity.x, velocity.y))
                    }
                    return@awaitEachGesture
                }
                if (!began) {
                    if (translation.getDistance() < viewConfiguration.touchSlop) continue
                    if (translation.y <= abs(translation.x) || (isMedia && state.isZoomed)) return@awaitEachGesture
                    began = true
                    if (style == Style.photos) dragBegan(down.position)
                }
                change.consume()
                changed(translation)
            }
        }
    }

    private fun changed(translation: Offset) {
        when (style) {
            Style.photos -> dragChanged(translation)
            Style.fade -> fadeChanged(translation)
            Style.zoom -> Unit
        }
    }

    private fun ended(translation: Offset, velocity: Offset) {
        when (style) {
            Style.photos -> dragEnded(translation, velocity)
            Style.fade -> fadeEnded(translation, velocity)
            Style.zoom -> Unit
        }
    }

    private fun fadeChanged(translation: Offset) {
        val distance = max(0.0, points(translation.y))
        scope.launch { viewAlpha.snapTo((1 - (1 - drag.backdropAlpha(at = distance)) * 0.7).toFloat()) }
        setChromeHidden(distance > 12)
    }

    private fun fadeEnded(translation: Offset, velocity: Offset) {
        val distance = max(0.0, points(translation.y))
        if (MediaDismissDrag.dismisses(distance = distance, velocity = points(velocity.y))) {
            fadeOut()
            return
        }
        scope.launch { viewAlpha.animateTo(1f, tween(200)) }
        setChromeHidden(false)
    }

    companion object {
        /// UIKit's `animate(springDuration:bounce:)`: stiffness (2π / duration)²
        /// and a damping ratio of 1 − bounce.
        fun swiftSpring(duration: Double, bounce: Double): AnimationSpec<Float> {
            val omega = 2 * Math.PI / duration
            return spring(dampingRatio = (1 - bounce).toFloat(), stiffness = (omega * omega).toFloat())
        }

        /// `AVMakeRect(aspectRatio:insideRect:)`.
        fun fitted(width: Float, height: Float, inside: Rect): Rect {
            val scale = min(inside.width / width, inside.height / height)
            val size = Size(width * scale, height * scale)
            return Rect(Offset(inside.center.x - size.width / 2, inside.center.y - size.height / 2), size)
        }

        private fun scaled(rect: Rect, by: Float): Rect {
            val size = Size(rect.width * by, rect.height * by)
            return Rect(Offset(rect.center.x - size.width / 2, rect.center.y - size.height / 2), size)
        }
    }
}
