package ai.opencode.feature.media

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.strings.localized
import ai.opencode.core.touch.TouchRecovery
import ai.opencode.core.touch.rememberTouchRecovery
import ai.opencode.core.ui.AppearanceColorScheme
import ai.opencode.core.ui.OpenCodeAppTheme
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.rememberAppAppearance
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Build
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// MARK: - Sources

/**
 * Which corners of a source are rounded (iOS `CACornerMask`): a collage tile rounds only the
 * collage's own. Absolute (not start/end), like the mask and the flight's window frame.
 */
data class MediaCorners(
    val topLeft: Boolean = true,
    val topRight: Boolean = true,
    val bottomLeft: Boolean = true,
    val bottomRight: Boolean = true,
) {
    fun radii(radius: Float): List<Float> = listOf(
        if (topLeft) radius else 0f,
        if (topRight) radius else 0f,
        if (bottomRight) radius else 0f,
        if (bottomLeft) radius else 0f,
    )

    fun roundRect(rect: Rect, radius: Float): RoundRect {
        val r = radii(max(radius, 0f))
        return RoundRect(
            rect = rect,
            topLeft = CornerRadius(r[0]),
            topRight = CornerRadius(r[1]),
            bottomRight = CornerRadius(r[2]),
            bottomLeft = CornerRadius(r[3]),
        )
    }

    fun shape(radius: Dp): RoundedCornerShape = RoundedCornerShape(
        topStart = if (topLeft) radius else 0.dp,
        topEnd = if (topRight) radius else 0.dp,
        bottomEnd = if (bottomRight) radius else 0.dp,
        bottomStart = if (bottomLeft) radius else 0.dp,
    )

    companion object {
        val All = MediaCorners()
    }
}

/**
 * The views full-screen media zooms out of and back into (iOS `MediaZoomSources`). Thumbnails
 * register their layout coordinates by media ID; lists reuse items, so each source carries the
 * media it shows now and lookups check it. A UI registry like iOS's `MediaZoomSources.shared`
 * (not a business service), only touched on the main thread.
 */
@Stable
class MediaZoomSources internal constructor() {
    /** A registered view (iOS `MediaZoomSourceView`). */
    class Source internal constructor() {
        /** The first is the media this view shows; the rest are pages it stands in for, like the collage's “+2” tile. */
        var mediaIDs: List<String> = emptyList()
            internal set
        internal var coordinates: LayoutCoordinates? = null
        internal var cornerRadius: Dp = 0.dp
        internal var corners: MediaCorners = MediaCorners.All
        internal var density: Float = 1f

        /** Its exact frame in the window, unclipped. */
        val windowRect: Rect?
            get() {
                val coordinates = coordinates?.takeIf { it.isAttached } ?: return null
                val origin = coordinates.positionInWindow()
                return Rect(origin, Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
            }

        /** On screen: a view scrolled out of sight is no place to land. */
        internal val isOnScreen: Boolean
            get() {
                val coordinates = coordinates?.takeIf { it.isAttached } ?: return false
                val visible = coordinates.boundsInWindow()
                return visible.width > 0f && visible.height > 0f
            }

        val cornerRadiusPx: Float get() = cornerRadius.value * density
    }

    private val sources = LinkedHashSet<Source>()

    /** The media up full screen. */
    var hiddenID: String? by mutableStateOf(null)
        private set

    internal fun add(source: Source) {
        sources.add(source)
    }

    internal fun remove(source: Source) {
        sources.remove(source)
    }

    fun isHidden(ids: List<String>): Boolean = hiddenID?.let(ids::contains) ?: false

    /** Out of the transcript while its media is up full screen, so a drag never shows it twice; an item recomposed meanwhile comes up hidden too. */
    fun hide(id: String?) {
        if (id == hiddenID) return
        hiddenID = id
    }

    /** The on-screen view showing [id], preferring one that shows it over one standing in for it. */
    fun view(id: String?): Source? {
        if (id == null) return null
        val candidates = sources.filter { it.mediaIDs.contains(id) && it.isOnScreen }
        return candidates.firstOrNull { it.mediaIDs.firstOrNull() == id } ?: candidates.firstOrNull()
    }

    companion object {
        /** The process's one registry (iOS `MediaZoomSources.shared`). */
        val shared: MediaZoomSources = MediaZoomSources()
    }
}

private class MediaZoomSourceElement(
    val ids: List<String>,
    val cornerRadius: Dp,
    val corners: MediaCorners,
) : ModifierNodeElement<MediaZoomSourceNode>() {
    override fun create() = MediaZoomSourceNode(ids, cornerRadius, corners)

    override fun update(node: MediaZoomSourceNode) {
        node.source.mediaIDs = ids
        node.source.cornerRadius = cornerRadius
        node.source.corners = corners
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "mediaZoomSource"
        properties["ids"] = ids
    }

    override fun equals(other: Any?) =
        other is MediaZoomSourceElement && other.ids == ids && other.cornerRadius == cornerRadius && other.corners == corners

    override fun hashCode() = (ids.hashCode() * 31 + cornerRadius.hashCode()) * 31 + corners.hashCode()
}

private class MediaZoomSourceNode(
    ids: List<String>,
    cornerRadius: Dp,
    corners: MediaCorners,
) : Modifier.Node(), GlobalPositionAwareModifierNode, CompositionLocalConsumerModifierNode {
    val source = MediaZoomSources.Source().also {
        it.mediaIDs = ids
        it.cornerRadius = cornerRadius
        it.corners = corners
    }

    override fun onAttach() {
        MediaZoomSources.shared.add(source)
    }

    override fun onDetach() {
        MediaZoomSources.shared.remove(source)
        source.coordinates = null
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        source.coordinates = coordinates
        source.density = currentValueOf(LocalDensity).density
    }
}

/**
 * Marks a card or row that opens full-screen media, so the viewer zooms out of it and back
 * into it (iOS `.mediaZoomSource(id:cornerRadius:)`). Only its frame and shape are used; the
 * card itself stays visible.
 */
fun Modifier.mediaZoomSource(id: String, cornerRadius: Dp = 0.dp): Modifier =
    this then MediaZoomSourceElement(listOf(id), cornerRadius, MediaCorners.All)

/**
 * An image the full-screen viewer zooms out of and back into (iOS `MediaThumbnail`), so the
 * flight can start and land on exactly its frame and shape. It leaves the transcript (alpha 0)
 * while its media is up full screen. Decorative: the button around it carries the label and
 * takes the taps.
 *
 * @param ids the media this shows, then any pages it stands in for.
 * @param fits fill crops to the frame; fit sizes itself to the image, at most [maxHeight] tall.
 * @param corners the rounded corners: a collage tile rounds only the collage's own.
 */
@Composable
fun MediaThumbnail(
    image: Bitmap,
    ids: List<String>,
    modifier: Modifier = Modifier,
    fits: Boolean = false,
    maxHeight: Dp = Dp.Infinity,
    cornerRadius: Dp = 0.dp,
    corners: MediaCorners = MediaCorners.All,
) {
    val bitmap = remember(image) { image.asImageBitmap() }
    val shape = remember(cornerRadius, corners) { corners.shape(cornerRadius) }
    val sources = MediaZoomSources.shared
    Image(
        bitmap = bitmap,
        contentDescription = null,
        contentScale = if (fits) ContentScale.Fit else ContentScale.Crop,
        filterQuality = FilterQuality.High,
        modifier = modifier
            .thumbnailSize(image, fits, maxHeight)
            .then(MediaZoomSourceElement(ids, cornerRadius, corners))
            .graphicsLayer {
                alpha = if (sources.isHidden(ids)) 0f else 1f
                this.shape = shape
                clip = true
            },
    )
}

/** iOS `sizeThatFits`: fill takes the proposal (the image's size where unspecified); fit fits the image, at most [maxHeight] tall. */
private fun Modifier.thumbnailSize(image: Bitmap, fits: Boolean, maxHeight: Dp): Modifier = layout { measurable, constraints ->
    // iOS sizes decoded originals at scale 1: pixels as points.
    val imageSize = Size(image.width.dp.toPx(), image.height.dp.toPx())
    val width = if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat() else imageSize.width
    val proposedHeight = if (constraints.hasBoundedHeight) constraints.maxHeight.toFloat() else Float.POSITIVE_INFINITY
    val size = if (fits) {
        val cap = if (maxHeight == Dp.Infinity) Float.POSITIVE_INFINITY else maxHeight.toPx()
        MediaZoomGeometry.fittedSize(imageSize, Size(width, min(proposedHeight, cap)))
    } else {
        Size(width, if (proposedHeight.isInfinite()) imageSize.height else proposedHeight)
    }
    // The image keeps its own frame (its clip, and where flights start and land) even when the
    // caller insists on a larger one (`fillMaxWidth`): centred in it, as a SwiftUI frame does.
    val w = size.width.roundToInt().coerceIn(0, constraints.maxWidth)
    val h = size.height.roundToInt().coerceIn(0, constraints.maxHeight)
    val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(w, h))
    val outerWidth = max(w, constraints.minWidth)
    val outerHeight = max(h, constraints.minHeight)
    layout(outerWidth, outerHeight) { placeable.place((outerWidth - w) / 2, (outerHeight - h) / 2) }
}

// MARK: - Viewer

/**
 * What the presentation needs from the full-screen content, and what the content needs from
 * the presentation (iOS `MediaViewerState`). Content reads it from [LocalMediaViewer].
 */
@Stable
class MediaViewerState(sourceID: String?) {
    /** What's on screen, for the flight back into the transcript. */
    var sourceID: String? by mutableStateOf(sourceID)
        private set

    /** The image on screen, flown in and out in place of the content (not observed). */
    var image: Bitmap? = null
        private set

    /** A zoomed-in image pans instead of dismissing. */
    var isZoomed by mutableStateOf(false)

    /** Close and the page position step aside while a drag dismisses. */
    var chromeHidden by mutableStateOf(false)
        internal set

    /** How long the last chrome change fades (0.15 s hiding, 0.25 s showing), for content that animates its own chrome. */
    var chromeFadeSeconds: Double = 0.25
        internal set

    /** Whether the status bar is hidden while the dark room is up (such as while photo viewer controls are hidden). */
    var statusBarHidden by mutableStateOf(false)
        internal set

    /** The image on screen is drawn by the flight above the content: while it flies in, while it's dragged, and while it flies home. */
    var mediaHidden by mutableStateOf(false)
        internal set

    /** Where each page lays its image out, by media ID, so flights land exactly on it. */
    val mediaFrames: MutableMap<String, Rect> = HashMap()

    val mediaFrame: Rect? get() = sourceID?.let { mediaFrames[it] }

    /** The badges drawn on each source's corners, by media ID, carried by the flight. */
    var flightAccessories: (String) -> List<MediaFlightAccessory> = { emptyList() }
        internal set

    var dismiss: () -> Unit = {}
        internal set

    /** Puts the media away without flying it home, for media that's gone (a composer photo removed from the viewer). */
    var discard: () -> Unit = {}
        internal set
    internal var onShow: () -> Unit = {}

    /** The content moved to another page or finished loading its image. */
    fun show(sourceID: String?, image: Bitmap?) {
        val moved = sourceID != this.sourceID
        this.sourceID = sourceID
        this.image = image
        isZoomed = false
        if (moved) onShow()
    }

    fun setStatusBarHidden(hidden: Boolean) {
        if (statusBarHidden == hidden) return
        statusBarHidden = hidden
    }
}

/** iOS `EnvironmentValues.mediaViewer`. */
val LocalMediaViewer = staticCompositionLocalOf<MediaViewerState?> { null }

/**
 * A badge on a thumbnail's corner (a composer photo's ×, a video's duration). It rides the
 * flight pinned to that corner at its own size (the bitmap's pixels), fading out as the image
 * flies in and back in as it flies home (iOS `MediaFlightAccessory`).
 */
class MediaFlightAccessory(
    val image: Bitmap,
    val corner: Corner,
    /** From the thumbnail's corner to the image's matching corner, in px. */
    val offset: Offset = Offset.Zero,
) {
    enum class Corner { TopTrailing, BottomLeading }
}

/**
 * Full-screen media and files (iOS `MediaPresenter`). Media opens the Photos way: it flies out
 * of what was tapped, follows a drag down while shrinking, rounding toward the thumbnail's
 * corners and letting the session show through, and flies back on release (or springs back
 * from a short drag). Documents zoom out of their source, and a swipe down (from anywhere a
 * scroll view doesn't take it) shrinks them toward it and zooms them home, as the system zoom's
 * does. Under Reduce Motion both cross-fade, and a drag down fades them out. One viewer at a
 * time.
 *
 * [MediaView] owns the presenter and provides it as [LocalMediaPresenter] to the content it
 * hosts; the transcript image viewer goes through the store ([presentImage]).
 */
@Stable
class MediaPresenter internal constructor() {
    internal class Presentation(
        val sourceID: String?,
        val isMedia: Boolean,
        val reducesMotion: Boolean,
        val screenName: String,
        val flightAccessories: ((String) -> List<MediaFlightAccessory>)?,
        val onDismiss: (() -> Unit)?,
        val content: @Composable (MediaViewerState) -> Unit,
    )

    internal var presentation: Presentation? by mutableStateOf(null)
    internal var imageViewerIsUp: () -> Boolean = { false }
    internal var sendImage: (TranscriptImagePreview) -> Unit = {}

    val isPresenting: Boolean get() = presentation != null || imageViewerIsUp()

    /**
     * Presents [content] full screen; ignored while a viewer is up. [flightAccessories] are the
     * source's corner badges the flight carries; [onDismiss] runs once the viewer is gone and
     * its source shows again.
     */
    fun present(
        sourceID: String?,
        isMedia: Boolean,
        reducesMotion: Boolean,
        screenName: String? = null,
        flightAccessories: ((String) -> List<MediaFlightAccessory>)? = null,
        onDismiss: (() -> Unit)? = null,
        content: @Composable (MediaViewerState) -> Unit,
    ): Boolean {
        // One viewer at a time.
        if (isPresenting) return false
        presentation = Presentation(
            sourceID,
            isMedia,
            reducesMotion,
            screenName ?: if (isMedia) localized(R.string.media_viewer_screen_title) else localized(R.string.media_file_viewer_screen_title),
            flightAccessories,
            onDismiss,
            content,
        )
        return true
    }

    /** iOS `SessionView.presentImage`: the transcript image viewer, through [MediaFeature]. */
    fun presentImage(preview: TranscriptImagePreview) {
        if (presentation != null) return
        sendImage(preview)
    }

    companion object {
        /**
         * Media plays in a dark room; documents and text viewers follow the app's appearance
         * (iOS `interfaceStyle(isMedia:)`; the DEBUG lab override is iOS-only).
         */
        fun isDarkRoom(isMedia: Boolean): Boolean = isMedia
    }
}

/** The presenter of the [MediaView] above this content, or `null` outside one. */
val LocalMediaPresenter = staticCompositionLocalOf<MediaPresenter?> { null }

/**
 * The Photos drag (iOS `MediaDismissDrag`): how media shrinks, rounds, and lets the session
 * show through as it's pulled down, and when letting go puts it away. Distances in one unit;
 * [dismisses] takes dp and dp/s (iOS points).
 */
data class MediaDismissDrag(val screenHeight: Float) {
    /** How far down the media is at its smallest and the session fully shows. */
    val range: Float = max(screenHeight * 0.5f, 1f)

    /** 0 at rest, 1 at the bottom of the range. */
    fun progress(distance: Float): Float = min(max(distance / range, 0f), 1f)

    fun scale(distance: Float): Float = 1 - (1 - minimumScale) * progress(distance)

    /** The drag distance a scale corresponds to, to pick up a spring-back. */
    fun distance(atScale: Float): Float = min(max((1 - atScale) / (1 - minimumScale), 0f), 1f) * range

    fun backdropAlpha(distance: Float): Float = 1 - progress(distance)

    /** Square at full screen, rounding toward the thumbnail's corners with the scale, so a release flies home already the thumbnail's shape. */
    fun cornerRadius(distance: Float, toward: Float): Float = toward * progress(distance)

    companion object {
        const val minimumScale: Float = 0.6f
        const val dismissDistance: Float = 120f
        const val dismissVelocity: Float = 900f

        /** Far enough, or flicked down; a flick back up keeps it. */
        fun dismisses(distance: Float, velocity: Float): Boolean =
            (distance > dismissDistance && velocity > -300f) || velocity > dismissVelocity
    }
}

/**
 * Android only: how far a predictive back gesture pulls the media at full progress, as
 * fractions of the pull-down's range ([MediaDismissDrag.range]) and of the screen's width.
 * At full progress the media is at about 88% of its size, as Android's own back preview.
 */
internal object MediaBackPreview {
    const val DOWN = 0.3f
    const val SIDEWAYS = 0.04f
}

/**
 * The viewer's motion roles (iOS `MediaViewerController` on `Motion`): fly in, content open and
 * the zoom dismissal on surface; a short drag back to the photo on panel open (its slight bounce
 * keeps Photos' cushion); home to the thumbnail on flight; nowhere to land, carried on and faded
 * on panel close; the backdrop's fade in on dim.
 */
internal object MediaMotion {
    val open = MotionRole.Surface
    val springBack = MotionRole.PanelOpen
    val home = MotionRole.Flight
    val away = MotionRole.PanelClose
    val backdropIn = MotionRole.Dim
    /** Fade style's open (0.2 s). */
    const val fade = 0.2
}

/** The content's transform about the host's centre (iOS `host.view.transform`). */
internal data class HostTransform(val scale: Float = 1f, val tx: Float = 0f, val ty: Float = 0f) {
    companion object {
        val Identity = HostTransform()
    }
}

/** The image in flight, under the content's chrome; the content hides its own copy meanwhile. */
internal class Flight(image: Bitmap, frame: Rect) {
    var image: Bitmap by mutableStateOf(image)
    var frame: Rect by mutableStateOf(frame)
    var radius by mutableFloatStateOf(0f)
    var corners: MediaCorners by mutableStateOf(MediaCorners.All)
    var alpha by mutableFloatStateOf(1f)
    var scale by mutableFloatStateOf(1f)
}

/** The source's corner badges riding the flight ([MediaFlightAccessory]), pinned to [frame]. */
internal class FlightAccessories(val badges: List<MediaFlightAccessory>, frame: Rect, alpha: Float) {
    var frame: Rect by mutableStateOf(frame)
    var alpha by mutableFloatStateOf(alpha)
}

/**
 * Drives one presentation (iOS `MediaViewerController`): the flight, the backdrop, the
 * content's transform, the dismiss drag and the status bar. A plain object owning per-frame
 * motion; the host composable draws its state.
 */
@Stable
internal class MediaViewerController(
    val state: MediaViewerState,
    private val isMedia: Boolean,
    /** Only the Fade style runs under Reduce Motion, so the flights see false; the roles still take it. */
    private val reducesMotion: Boolean,
    private val scope: CoroutineScope,
    private val density: Density,
    private val onDismissed: () -> Unit,
) {
    enum class Style {
        /** Flies out of its source and is dragged down to dismiss. */
        Photos,
        /** The system zoom, for documents. */
        Zoom,
        /** Reduce Motion: cross-fades, and a drag down fades it out. */
        Fade,
    }

    val style: Style = if (reducesMotion) Style.Fade else if (isMedia) Style.Photos else Style.Zoom

    var backdropAlpha by mutableFloatStateOf(1f)
    var hostAlpha by mutableFloatStateOf(1f)
    var hostTransform by mutableStateOf(HostTransform.Identity)
    /**
     * Zoom style: the page's corner radius in its own (unscaled) pixels, so on screen it rounds
     * toward its source's corners as it shrinks (the system zoom's rounded page).
     */
    var hostCornerRadius by mutableFloatStateOf(0f)
    /** Zoom style: the corners [hostCornerRadius] rounds (the source's). */
    var hostCorners: MediaCorners by mutableStateOf(MediaCorners.All)
    /** Where the finger is, for a dismissal a scroll view hands over (nested scroll has no position). */
    internal var lastPointer = Offset.Zero
    /** Fade style: the whole viewer's opacity. */
    var viewAlpha by mutableFloatStateOf(1f)
    var flight: Flight? by mutableStateOf(null)
    /** The source's badges, riding the flight. */
    var accessories: FlightAccessories? by mutableStateOf(null)
    /** Light over the dark room; the session's own once it shows through. */
    var statusBarLight by mutableStateOf(isMedia && reducesMotion)
    var isLeaving by mutableStateOf(false)
        private set

    /** The host's size and where it sits in the window (sources are in window coordinates). */
    var bounds by mutableStateOf(Rect.Zero)
    var originInWindow = Offset.Zero

    private var hasOpened = false
    private var finished = false
    /** Bumped by each flight animation, so an interrupted one's end doesn't hand the image back to the content. */
    private var flightGeneration = 0
    private var flightJob: Job? = null
    private var backdropJob: Job? = null

    // A drag, relative to where the image was when it began.
    private var dragAnchor = Offset.Zero
    private var dragStart = Rect.Zero
    private var dragStartDistance = 0f
    private var dragCarry = Offset.Zero
    private var lastFadeDistance = 0f

    private val trace = MediaTransitionTrace()

    init {
        state.dismiss = { close() }
        state.discard = { close(home = false) }
        when (style) {
            Style.Photos -> {
                // The image flies in first; the content shows it once it lands.
                state.mediaHidden = true
                state.chromeHidden = true
                backdropAlpha = 0f
                hostAlpha = 0f
                state.onShow = { hideSource() }
            }
            Style.Fade -> viewAlpha = 0f
            Style.Zoom -> hostAlpha = 0f
        }
    }

    /** Whether the status bar is hidden (iOS `prefersStatusBarHidden`). */
    val statusBarHidden: Boolean
        get() {
            if (isLeaving || !state.statusBarHidden) return false
            return if (style == Style.Photos) statusBarLight else true
        }

    private fun updateStatusBarLight(light: Boolean) {
        if (style != Style.Photos) return
        if (statusBarLight != light) statusBarLight = light
    }

    /** iOS `viewDidAppear`: the presentation runs once the content has laid out. */
    fun open() {
        if (hasOpened) return
        hasOpened = true
        when (style) {
            Style.Photos -> flyIn()
            Style.Fade -> scope.launch { animate(viewAlpha, 1f, animationSpec = tween(millis(MediaMotion.fade))) { v, _ -> viewAlpha = v } }
            Style.Zoom -> zoomIn()
        }
    }

    // MARK: Photos

    private val source: MediaZoomSources.Source? get() = MediaZoomSources.shared.view(state.sourceID)

    /** Like Photos, the thumbnail leaves the transcript while its media is up. */
    private fun hideSource() {
        MediaZoomSources.shared.hide(state.sourceID)
    }

    /** Where the image rests: where the page lays it out, or else fitted to the screen. */
    private val restingFrame: Rect
        get() {
            val image = state.image ?: return bounds
            if (image.width <= 0 || image.height <= 0) return bounds
            state.mediaFrame?.let { return it }
            val fitted = MediaZoomGeometry.fittedSize(Size(image.width.toFloat(), image.height.toFloat()), bounds.size)
            return Rect(Offset(bounds.center.x - fitted.width / 2, bounds.center.y - fitted.height / 2), fitted)
        }

    private fun sourceFrame(source: MediaZoomSources.Source): Rect? =
        source.windowRect?.translate(-originInWindow.x, -originInWindow.y)

    private fun shape(source: MediaZoomSources.Source?): Pair<Float, MediaCorners> =
        if (source == null) 0f to MediaCorners.All else source.cornerRadiusPx to source.corners

    /** The image in flight, created at [frame] the first time it's needed; caught where it is otherwise. */
    private fun flightView(image: Bitmap, frame: Rect): Flight {
        flightJob?.cancel()
        flight?.let {
            if (it.image !== image) it.image = image
            return it
        }
        return Flight(image, frame).also { flight = it }
    }

    /** The content draws the image again; the flight goes once it has. */
    private fun land(generation: Int) {
        if (generation != flightGeneration || isLeaving) return
        state.mediaHidden = false
        flight = null
        dropAccessories()
    }

    /** The source's badges over the flight at [frame], each pinned to its corner at its own size, so they follow the flight's frame. */
    private fun pinAccessories(frame: Rect, alpha: Float): FlightAccessories? {
        dropAccessories()
        if (flight == null) return null
        val id = state.sourceID ?: return null
        val badges = state.flightAccessories(id)
        if (badges.isEmpty()) return null
        return FlightAccessories(badges, frame, alpha).also { accessories = it }
    }

    private fun dropAccessories() {
        accessories = null
    }

    private fun catchBackdrop() {
        backdropJob?.cancel()
        backdropJob = null
    }

    private fun flyIn() {
        val source = source
        hideSource()
        hostAlpha = 1f
        updateStatusBarLight(true)
        setChromeHidden(false, flightDuration = MediaMotion.open.curve().duration)
        backdropJob = scope.launch {
            animate(backdropAlpha, 1f, animationSpec = MediaMotion.backdropIn.animation(reduceMotion = reducesMotion)) { v, _ -> backdropAlpha = v }
        }
        val image = state.image
        val from = source?.let(::sourceFrame)
        if (source != null && from != null && image != null) {
            val (radius, corners) = shape(source)
            val rest = restingFrame
            val flight = flightView(image, from)
            flight.radius = radius
            flight.corners = corners
            val badges = pinAccessories(flight.frame, alpha = 1f)
            flightGeneration += 1
            val generation = flightGeneration
            trace.begin("open", scope, density, source = from to radius, rest = rest, state = state, sample = ::traceSample)
            flightJob = scope.launch {
                spring(MediaMotion.open) { p ->
                    flight.frame = lerp(from, rest, p)
                    flight.radius = radius + (0f - radius) * p
                    badges?.frame = flight.frame
                    badges?.alpha = 1 - p
                }
                trace.end("open")
                land(generation)
            }
        } else {
            state.mediaHidden = false
            hostAlpha = 0f
            val start = from?.let { transform(restingFrame, it) } ?: HostTransform(scale = 0.94f)
            hostTransform = start
            flightJob = scope.launch {
                spring(MediaMotion.open) { p ->
                    hostTransform = lerp(start, HostTransform.Identity, p)
                    hostAlpha = p.coerceIn(0f, 1f)
                }
            }
        }
    }

    /** Documents: the system zoom out of their source (or a small scale-up without one). */
    private fun zoomIn() {
        val source = source
        val from = source?.let(::sourceFrame)
        val start = from?.let { transform(bounds, it) } ?: HostTransform(scale = 0.94f)
        val (radius, corners) = shape(source.takeIf { from != null })
        val startRadius = radius / max(start.scale, 0.01f)
        hostTransform = start
        hostCorners = corners
        hostCornerRadius = startRadius
        hostAlpha = 0f
        flightJob = scope.launch {
            spring(MediaMotion.open) { p ->
                hostTransform = lerp(start, HostTransform.Identity, p)
                hostCornerRadius = startRadius * (1 - p)
                hostAlpha = p.coerceIn(0f, 1f)
            }
        }
    }

    /** The transform about the view's centre that maps [rect] onto [target]. */
    private fun transform(rect: Rect, target: Rect): HostTransform {
        val scale = min(target.width / max(rect.width, 1f), target.height / max(rect.height, 1f))
        val center = bounds.center
        return HostTransform(
            scale = scale,
            tx = target.center.x - center.x - scale * (rect.center.x - center.x),
            ty = target.center.y - center.y - scale * (rect.center.y - center.y),
        )
    }

    private val drag: MediaDismissDrag get() = MediaDismissDrag(screenHeight = bounds.height)

    // MARK: Drag

    /**
     * iOS `gestureRecognizerShouldBegin`: down and steeper than sideways; never on a zoomed image.
     * Media claims the drag before its content ([fromContent] false: pages turn only once a drag
     * has shown it isn't a dismissal). Documents (the system zoom's swipe down, or the fade) take
     * only a drag their content left alone ([fromContent] true): a scroll view scrolled away from
     * its top keeps it, and one at its top hands it over through [documentDismissConnection].
     */
    fun shouldBegin(translation: Offset, fromContent: Boolean): Boolean {
        if (isLeaving) return false
        if (translation.y <= abs(translation.x)) return false
        if (!isMedia) return fromContent
        if (fromContent) return false
        return !state.isZoomed
    }

    fun dragBegan(anchor: Offset) {
        when (style) {
            Style.Photos -> if (state.image != null) dragImageBegan(anchor) else dragContentBegan(anchor)
            Style.Fade -> lastFadeDistance = 0f
            Style.Zoom -> zoomDragBegan(anchor)
        }
    }

    fun dragChanged(translation: Offset) {
        when (style) {
            Style.Photos -> if (state.image != null) dragImageChanged(translation) else dragContentChanged(translation)
            Style.Fade -> fadeChanged(max(0f, translation.y))
            Style.Zoom -> zoomDragChanged(translation)
        }
    }

    /** [velocity] in px/s. */
    fun dragEnded(translation: Offset, velocity: Velocity) {
        when (style) {
            Style.Photos -> {
                if (state.image != null) trace.end("drag")
                val distance = translation.y + dragStartDistance
                if (MediaDismissDrag.dismisses(toDp(distance), toDp(velocity.y))) {
                    flyOut(velocity)
                } else if (state.image != null) {
                    springBack(velocity)
                } else {
                    contentSpringBack()
                }
            }
            Style.Fade -> fadeEnded(max(0f, translation.y), velocity.y)
            Style.Zoom -> {
                val distance = translation.y + dragStartDistance
                if (MediaDismissDrag.dismisses(toDp(distance), toDp(velocity.y))) zoomOut(velocity) else zoomSpringBack(velocity)
            }
        }
    }

    // region Predictive back (Android only)

    /** Whether a predictive back gesture is previewing the dismissal (see [backBegan]). */
    private var backPreviewing = false

    /**
     * A predictive back gesture began: it drives the same drag as a pull down, a little way
     * ([backProgressed]). Returns false when nothing previews (a zoomed image, already leaving);
     * the commit still closes.
     */
    fun backBegan(): Boolean {
        if (isLeaving) return false
        if (isMedia && state.isZoomed) return false
        dragBegan(bounds.center)
        backPreviewing = true
        return true
    }

    /** [progress] 0..1 from the platform; [fromRightEdge] shifts the media toward the left. */
    fun backProgressed(progress: Float, fromRightEdge: Boolean) {
        if (!backPreviewing) return
        val side = if (fromRightEdge) -1f else 1f
        dragChanged(
            Offset(
                x = side * progress * bounds.width * MediaBackPreview.SIDEWAYS,
                y = progress * drag.range * MediaBackPreview.DOWN,
            ),
        )
    }

    /** The gesture (or a button) committed: the media goes away from wherever it is. */
    fun backCommitted() {
        if (backPreviewing && style == Style.Photos && state.image != null) trace.end("drag")
        backPreviewing = false
        close()
    }

    /** The gesture was abandoned: the media springs back to rest. */
    fun backCancelled() {
        if (!backPreviewing) return
        backPreviewing = false
        when (style) {
            Style.Photos -> {
                if (state.image != null) {
                    trace.end("drag")
                    springBack(Velocity.Zero)
                } else {
                    contentSpringBack()
                }
            }
            Style.Fade -> fadeEnded(distance = 0f, velocity = 0f)
            Style.Zoom -> zoomSpringBack(Velocity.Zero)
        }
    }

    // endregion

    private fun toDp(px: Float): Float = px / density.density

    /** An image is dragged as the flight: it shrinks about the finger, so the spot it holds stays under it, and rounds toward the thumbnail. */
    private fun dragImageBegan(anchor: Offset) {
        val image = state.image ?: return
        val rest = restingFrame
        flightGeneration += 1
        val flight = flightView(image, rest)
        dropAccessories()
        catchBackdrop()
        state.mediaHidden = true
        flight.corners = shape(source).second
        dragAnchor = anchor
        dragStart = flight.frame
        dragStartDistance = drag.distance(atScale = flight.frame.width / max(rest.width, 1f))
        setChromeHidden(true)
        trace.begin("drag", scope, density, source = source?.let { s -> sourceFrame(s)?.let { it to s.cornerRadiusPx } }, rest = rest, state = state, sample = ::traceSample)
    }

    private fun dragImageChanged(translation: Offset) {
        val flight = flight ?: return
        val rest = restingFrame
        val distance = translation.y + dragStartDistance
        val scale = drag.scale(distance)
        val relative = scale * rest.width / max(dragStart.width, 1f)
        val center = Offset(
            dragAnchor.x + translation.x + (dragStart.center.x - dragAnchor.x) * relative,
            dragAnchor.y + translation.y + (dragStart.center.y - dragAnchor.y) * relative,
        )
        val size = Size(rest.width * scale, rest.height * scale)
        flight.frame = Rect(Offset(center.x - size.width / 2, center.y - size.height / 2), size)
        flight.radius = drag.cornerRadius(distance, toward = shape(source).first)
        backdropAlpha = drag.backdropAlpha(distance)
        updateStatusBarLight(backdropAlpha > 0.5f)
    }

    private fun springBack(velocity: Velocity) {
        val flight = flight ?: return
        val rest = restingFrame
        flightGeneration += 1
        val generation = flightGeneration
        setChromeHidden(false)
        updateStatusBarLight(true)
        trace.begin("spring", scope, density, source = null, rest = rest, state = state, sample = ::traceSample)
        val speed = springVelocity(velocity, flight.frame, rest)
        val from = flight.frame
        val radius = flight.radius
        val backdrop = backdropAlpha
        flightJob = scope.launch {
            spring(MediaMotion.springBack, speed) { p ->
                flight.frame = lerp(from, rest, p)
                flight.radius = radius * (1 - p)
                backdropAlpha = backdrop + (1 - backdrop) * p
            }
            if (generation == flightGeneration) trace.end("spring")
            land(generation)
        }
    }

    /** Other content (video, a file still loading) moves as a whole. */
    private fun dragContentBegan(anchor: Offset) {
        // Catch a spring-back where it is.
        flightJob?.cancel()
        val current = hostTransform
        catchBackdrop()
        dragAnchor = anchor
        val center = bounds.center
        dragStartDistance = drag.distance(atScale = current.scale)
        dragCarry = Offset(
            current.tx - (dragAnchor.x - center.x) * (1 - current.scale),
            current.ty - (dragAnchor.y - center.y) * (1 - current.scale),
        )
        setChromeHidden(true)
    }

    private fun dragContentChanged(translation: Offset) {
        val distance = translation.y + dragStartDistance
        val scale = drag.scale(distance)
        val center = bounds.center
        hostTransform = HostTransform(
            scale = scale,
            tx = (dragAnchor.x - center.x) * (1 - scale) + translation.x + dragCarry.x,
            ty = (dragAnchor.y - center.y) * (1 - scale) + translation.y + dragCarry.y,
        )
        backdropAlpha = drag.backdropAlpha(distance)
        updateStatusBarLight(backdropAlpha > 0.5f)
    }

    private fun contentSpringBack() {
        val start = hostTransform
        val backdrop = backdropAlpha
        flightJob = scope.launch {
            spring(MediaMotion.springBack) { p ->
                hostTransform = lerp(start, HostTransform.Identity, p)
                backdropAlpha = backdrop + (1 - backdrop) * p
            }
        }
        setChromeHidden(false)
        updateStatusBarLight(true)
    }

    // MARK: Zoom

    /**
     * Documents: the system zoom's interactive swipe down. The page follows the finger, shrinking
     * about it (the spot it holds stays under it, as [dragContentBegan]) and rounding toward its
     * source's corners; a release past [MediaDismissDrag.dismisses] zooms it home, else it springs
     * back. Chrome stays put, as the system zoom leaves the presented page alone.
     */
    private fun zoomDragBegan(anchor: Offset) {
        // Catch an opening zoom or a spring-back where it is.
        flightJob?.cancel()
        val current = hostTransform
        dragAnchor = anchor
        val center = bounds.center
        dragStartDistance = drag.distance(atScale = current.scale)
        dragCarry = Offset(
            current.tx - (dragAnchor.x - center.x) * (1 - current.scale),
            current.ty - (dragAnchor.y - center.y) * (1 - current.scale),
        )
        hostCorners = shape(source).second
        hostAlpha = 1f
    }

    private fun zoomDragChanged(translation: Offset) {
        val distance = translation.y + dragStartDistance
        val scale = drag.scale(distance)
        val center = bounds.center
        hostTransform = HostTransform(
            scale = scale,
            tx = (dragAnchor.x - center.x) * (1 - scale) + translation.x + dragCarry.x,
            ty = (dragAnchor.y - center.y) * (1 - scale) + translation.y + dragCarry.y,
        )
        hostCornerRadius = drag.cornerRadius(distance, toward = shape(source).first) / max(scale, 0.01f)
    }

    private fun zoomSpringBack(velocity: Velocity) {
        val start = hostTransform
        val radius = hostCornerRadius
        val speed = springVelocity(velocity, transformed(bounds, start), bounds)
        flightJob?.cancel()
        flightJob = scope.launch {
            spring(MediaMotion.springBack, speed) { p ->
                hostTransform = lerp(start, HostTransform.Identity, p)
                hostCornerRadius = radius * (1 - p)
            }
        }
    }

    /** Where [rect] lands under [transform] (about the host's centre). */
    private fun transformed(rect: Rect, transform: HostTransform): Rect {
        val center = bounds.center
        fun map(point: Offset) = Offset(
            center.x + (point.x - center.x) * transform.scale + transform.tx,
            center.y + (point.y - center.y) * transform.scale + transform.ty,
        )
        return Rect(map(rect.topLeft), map(rect.bottomRight))
    }

    /** [flightDuration]: the chrome changes on the flight's own spring, so nothing arrives or lingers after the image. */
    private fun setChromeHidden(hidden: Boolean, flightDuration: Double? = null) {
        if (state.chromeHidden == hidden) return
        state.chromeFadeSeconds = flightDuration ?: if (hidden) 0.15 else 0.25
        state.chromeHidden = hidden
    }

    private fun close(home: Boolean = true) {
        when (style) {
            Style.Photos -> flyOut(Velocity.Zero, home)
            Style.Zoom -> zoomOut()
            Style.Fade -> fadeOut()
        }
    }

    /** A spring's initial velocity, in units of the distance to travel. */
    private fun springVelocity(velocity: Velocity, frame: Rect, target: Rect): Float {
        val dx = target.center.x - frame.center.x
        val dy = target.center.y - frame.center.y
        val length = dx * dx + dy * dy
        if (length <= 1f) return 0f
        return min(max((velocity.x * dx + velocity.y * dy) / length, 0f), 8f)
    }

    private fun flyOut(velocity: Velocity, home: Boolean = true) {
        if (isLeaving) return
        isLeaving = true
        flightGeneration += 1
        val source = if (home) source else null
        val target = source?.let(::sourceFrame)
        // Home to its thumbnail on flight; with nowhere to land it carries on down and fades on
        // panel close. Neither bounces on the way out.
        val role = if (target == null) MediaMotion.away else MediaMotion.home
        setChromeHidden(true, flightDuration = role.curve().duration)
        updateStatusBarLight(false)
        catchBackdrop()
        val backdrop = backdropAlpha
        val image = state.image
        if (image != null) {
            val flight = flightView(image, restingFrame)
            dropAccessories()
            state.mediaHidden = true
            val (radius, corners) = shape(source)
            flight.corners = corners
            trace.begin("close", scope, density, source = target?.let { it to radius }, rest = restingFrame, state = state, sample = ::traceSample)
            val from = flight.frame
            val fromRadius = flight.radius
            if (target != null) {
                val speed = springVelocity(velocity, from, target)
                val badges = pinAccessories(flight.frame, alpha = 0f)
                flightJob = scope.launch {
                    spring(MediaMotion.home, speed) { p ->
                        flight.frame = lerp(from, target, p)
                        flight.radius = fromRadius + (radius - fromRadius) * p
                        badges?.frame = flight.frame
                        badges?.alpha = p
                        backdropAlpha = backdrop * (1 - p)
                    }
                    finishHome()
                }
            } else {
                // Nowhere to land: carry on the way it was thrown and fade.
                val drop = bounds.height * 0.25f
                flightJob = scope.launch {
                    spring(MediaMotion.away, phase = MotionPhase.Exit) { p ->
                        flight.frame = from.translate(0f, drop * p)
                        flight.scale = 1 - 0.2f * p
                        flight.alpha = 1 - p
                        backdropAlpha = backdrop * (1 - p)
                    }
                    finishHome()
                }
            }
        } else {
            flightJob?.cancel()
            val current = hostTransform
            val end = if (target != null) {
                transform(restingFrame, target)
            } else {
                current.copy(ty = current.ty + current.scale * (bounds.height * 0.25f / max(current.scale, 0.1f)))
            }
            val alpha = hostAlpha
            flightJob = scope.launch {
                spring(role, phase = if (target == null) MotionPhase.Exit else MotionPhase.Enter) { p ->
                    hostTransform = lerp(current, end, p)
                    hostAlpha = alpha * (1 - p)
                    backdropAlpha = backdrop * (1 - p)
                }
                finishHome()
            }
        }
    }

    /** Documents: back into their source (the system zoom's dismissal), carrying a release's velocity. */
    private fun zoomOut(velocity: Velocity = Velocity.Zero) {
        if (isLeaving) return
        isLeaving = true
        val source = source
        val target = source?.let(::sourceFrame)
        val start = hostTransform
        val end = target?.let { transform(bounds, it) } ?: HostTransform(scale = 0.94f)
        val (radius, corners) = shape(source.takeIf { target != null })
        if (target != null) hostCorners = corners
        val fromRadius = hostCornerRadius
        val toRadius = radius / max(end.scale, 0.01f)
        val speed = target?.let { springVelocity(velocity, transformed(bounds, start), it) } ?: 0f
        val alpha = hostAlpha
        flightJob?.cancel()
        flightJob = scope.launch {
            spring(MediaMotion.open, speed) { p ->
                hostTransform = lerp(start, end, p)
                hostCornerRadius = fromRadius + (toRadius - fromRadius) * p
                hostAlpha = alpha * (1 - p)
            }
            finish()
        }
    }

    // MARK: Fade

    private fun fadeChanged(distance: Float) {
        lastFadeDistance = distance
        viewAlpha = 1 - (1 - drag.backdropAlpha(distance)) * 0.7f
        setChromeHidden(toDp(distance) > 12f)
    }

    private fun fadeEnded(distance: Float, velocity: Float) {
        if (MediaDismissDrag.dismisses(toDp(distance), toDp(velocity))) {
            fadeOut()
        } else {
            val alpha = viewAlpha
            flightJob?.cancel()
            // Back on the release role (the reduced fade: Fade only runs under Reduce Motion).
            flightJob = scope.launch {
                animate(alpha, 1f, animationSpec = MotionRole.Release.animation(reduceMotion = reducesMotion)) { v, _ -> viewAlpha = v }
            }
            setChromeHidden(false)
        }
    }

    private fun fadeOut() {
        if (isLeaving) return
        isLeaving = true
        val alpha = viewAlpha
        flightJob?.cancel()
        flightJob = scope.launch {
            animate(alpha, 0f, animationSpec = tween(millis(Motion.reducedFade), easing = Motion.EaseInOut)) { v, _ -> viewAlpha = v }
            finish()
        }
    }

    /**
     * Documents (zoom or fade): a drag down their scroll view can't take. iOS begins the
     * dismissal only when the scroll view under the finger is at its top as the drag starts
     * (`gestureRecognizerShouldBegin`); here a scroll view at its top hands over the downward
     * delta it can't use. A drag that already scrolled keeps scrolling. Once dismissing, the
     * scroll view gets nothing (the finger drives the page) and the fling is the release.
     */
    val documentDismissConnection: NestedScrollConnection = object : NestedScrollConnection {
        private var dragging = false
        private var scrolled = false
        private var anchor = Offset.Zero

        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (!dragging || source != NestedScrollSource.UserInput) return Offset.Zero
            dragChanged(lastPointer - anchor)
            return available
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (isMedia || isLeaving || dragging || source != NestedScrollSource.UserInput) return Offset.Zero
            if (consumed.y != 0f) scrolled = true
            if (scrolled || available.y <= 0f || available.y <= abs(available.x + consumed.x)) return Offset.Zero
            dragging = true
            // Where the finger was before this delta, so the page moves with all of it.
            anchor = lastPointer - Offset(0f, available.y)
            dragBegan(anchor)
            dragChanged(lastPointer - anchor)
            return available
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            scrolled = false
            if (!dragging) return Velocity.Zero
            dragging = false
            dragEnded(lastPointer - anchor, Velocity(0f, available.y))
            return available
        }
    }

    // MARK: Lifecycle

    /**
     * The thumbnail comes back under the landed flight. A thumbnail redraws on its next frame,
     * so the flight stays two frames longer: never a frame with neither on screen.
     */
    private suspend fun finishHome() {
        trace.end("close")
        MediaZoomSources.shared.hide(null)
        repeat(2) { withFrameNanos { } }
        finish()
    }

    private fun finish() {
        if (finished) return
        finished = true
        trace.end("close")
        // The thumbnail comes back in the same frame the viewer leaves.
        MediaZoomSources.shared.hide(null)
        onDismissed()
    }

    /**
     * iOS `MediaViewerController.touchRecovery` inspect: a dismissal whose surface has already
     * faded out (the backdrop, or the page for documents and Reduce Motion) but never finished
     * still covers the session and takes its touches.
     */
    fun strandedExitFault(): TouchRecovery.Fault? {
        if (!isLeaving || finished) return null
        val shown = when (style) {
            Style.Photos -> backdropAlpha
            Style.Zoom -> hostAlpha
            Style.Fade -> viewAlpha
        }
        if (shown > 0.01f) return null
        return TouchRecovery.Fault(TouchRecovery.Reason.Closed, this)
    }

    /** iOS `touchRecovery` release: drops the flight and leaves at once. */
    fun recoverStrandedExit() {
        flightGeneration += 1
        flightJob?.cancel()
        finish()
    }

    /** iOS `viewDidDisappear`. */
    fun disposed() {
        flightJob?.cancel()
        backdropJob?.cancel()
        trace.cancel()
        if (style == Style.Photos || MediaZoomSources.shared.hiddenID == state.sourceID) MediaZoomSources.shared.hide(null)
    }

    // MARK: Trace

    private fun traceSample(): MediaTransitionTrace.Sample? {
        val flight = flight ?: return null
        val frame = flight.frame.translate(originInWindow.x, originInWindow.y)
        val sourceAlpha = MediaZoomSources.shared.view(state.sourceID)?.let { if (MediaZoomSources.shared.isHidden(it.mediaIDs)) 0f else 1f } ?: -1f
        return MediaTransitionTrace.Sample(frame, flight.radius, backdropAlpha, sourceAlpha)
    }

    // MARK: Animation

    /** iOS `role.animate(phase, reduceMotion:velocity:)`: one curve of progress 0 → 1 for every property. */
    private suspend fun spring(role: MotionRole, velocity: Float = 0f, phase: MotionPhase = MotionPhase.Enter, update: (Float) -> Unit) {
        val spec = role.animation(phase, reducesMotion, visibilityThreshold = 0.0005f)
        animate(0f, 1f, initialVelocity = role.initialVelocity(velocity, reducesMotion), animationSpec = spec) { value, _ -> update(value) }
        update(1f)
    }

    private fun millis(seconds: Double): Int = (seconds * 1000).roundToInt()

    private fun lerp(a: Rect, b: Rect, p: Float): Rect = Rect(
        a.left + (b.left - a.left) * p,
        a.top + (b.top - a.top) * p,
        a.right + (b.right - a.right) * p,
        a.bottom + (b.bottom - a.bottom) * p,
    )

    private fun lerp(a: HostTransform, b: HostTransform, p: Float) = HostTransform(
        a.scale + (b.scale - a.scale) * p,
        a.tx + (b.tx - a.tx) * p,
        a.ty + (b.ty - a.ty) * p,
    )
}

/**
 * The presented viewer (iOS `MediaViewerController`'s view): a black backdrop (hidden for
 * documents), the flight, and the content with its chrome above it, over the whole window. It takes
 * every touch while up, hides the content below from TalkBack (the host does), dismisses on
 * back, and runs the dark room's status bar.
 */
@Composable
internal fun MediaViewerHost(
    sourceID: String?,
    isMedia: Boolean,
    reducesMotion: Boolean,
    screenName: String,
    onDismissed: () -> Unit,
    flightAccessories: ((String) -> List<MediaFlightAccessory>)? = null,
    content: @Composable (MediaViewerState) -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val currentDismissed by rememberUpdatedState(onDismissed)
    val state = remember {
        MediaViewerState(sourceID).also { state -> flightAccessories?.let { state.flightAccessories = it } }
    }
    val controller = remember {
        MediaViewerController(state, isMedia, reducesMotion, scope, density) { currentDismissed() }
    }
    rememberTouchRecovery(
        owner = TouchRecovery.Owner.Zoom,
        inspect = { controller.strandedExitFault() },
        release = { controller.recoverStrandedExit() },
    )
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val slop = LocalViewConfiguration.current.touchSlop

    LaunchedEffect(controller) {
        // Opening an image dismisses the keyboard.
        keyboard?.hide()
        focus.clearFocus(force = true)
        snapshotFlow { controller.bounds }.first { it.width > 0f && it.height > 0f }
        // One frame for the pages to lay out and report where their images rest.
        withFrameNanos { }
        controller.open()
    }
    DisposableEffect(controller) { onDispose { controller.disposed() } }
    // Back previews the dismissal as a short pull down; the commit puts the media away.
    MediaPredictiveBackHandler(
        enabled = !controller.isLeaving,
        onStarted = { controller.backBegan() },
        onProgressed = { progress, fromRightEdge -> controller.backProgressed(progress, fromRightEdge) },
        onCancelled = { controller.backCancelled() },
        onInvoked = { controller.backCommitted() },
    )
    StatusBarEffect(controller)

    val dark = MediaPresenter.isDarkRoom(isMedia)
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                controller.originInWindow = coordinates.positionInWindow()
                val size = coordinates.size
                val rect = Rect(Offset.Zero, Size(size.width.toFloat(), size.height.toFloat()))
                if (controller.bounds != rect) controller.bounds = rect
            }
            .graphicsLayer { alpha = controller.viewAlpha }
            .semantics { paneTitle = screenName }
            .pointerInput(controller) { trackPointer(controller) }
            .pointerInput(controller) { dismissDrag(controller, slop, fromContent = false) }
            .pointerInput(controller) { dismissDrag(controller, slop, fromContent = true) }
            .nestedScroll(controller.documentDismissConnection),
    ) {
        if (controller.style != MediaViewerController.Style.Zoom) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = controller.backdropAlpha }
                    .background(Color.Black),
            )
        }
        val themed: @Composable () -> Unit = {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val transform = controller.hostTransform
                        alpha = controller.hostAlpha
                        scaleX = transform.scale
                        scaleY = transform.scale
                        translationX = transform.tx
                        translationY = transform.ty
                        transformOrigin = TransformOrigin.Center
                        val radius = controller.hostCornerRadius
                        if (radius > 0.5f) {
                            val c = controller.hostCorners
                            shape = RoundedCornerShape(
                                topStart = if (c.topLeft) radius else 0f,
                                topEnd = if (c.topRight) radius else 0f,
                                bottomEnd = if (c.bottomRight) radius else 0f,
                                bottomStart = if (c.bottomLeft) radius else 0f,
                            )
                            clip = true
                        } else {
                            shape = RectangleShape
                            clip = false
                        }
                    }
                    .then(
                        if (controller.style == MediaViewerController.Style.Zoom) {
                            Modifier.background(MaterialTheme.colorScheme.background)
                        } else {
                            Modifier
                        },
                    ),
            ) {
                CompositionLocalProvider(LocalMediaViewer provides state) { content(state) }
            }
        }
        // The flight flies under the content's chrome, which hides its own copy meanwhile.
        FlightLayer(controller)
        if (dark) {
            val appearance = rememberAppAppearance()
            OpenCodeTheme(theme = appearance.theme, appearance = AppearanceColorScheme.Dark, content = themed)
        } else {
            OpenCodeAppTheme(content = themed)
        }
    }
}

/**
 * The image in flight, and the source's badges riding it, drawn under the content's chrome in
 * the host's coordinates (only the draw phase reads their frames).
 */
@Composable
private fun FlightLayer(controller: MediaViewerController) {
    val flight = controller.flight ?: return
    val bitmap: ImageBitmap = remember(flight.image) { flight.image.asImageBitmap() }
    val accessories = controller.accessories
    val badges = remember(accessories) { accessories?.badges?.map { it to it.image.asImageBitmap() }.orEmpty() }
    Canvas(Modifier.fillMaxSize()) {
        drawFlight(flight, bitmap)
        if (accessories != null && accessories.alpha > 0f) {
            val frame = accessories.frame
            for ((badge, image) in badges) {
                val x = when (badge.corner) {
                    MediaFlightAccessory.Corner.TopTrailing -> frame.right - image.width + badge.offset.x
                    MediaFlightAccessory.Corner.BottomLeading -> frame.left + badge.offset.x
                }
                val y = when (badge.corner) {
                    MediaFlightAccessory.Corner.TopTrailing -> frame.top + badge.offset.y
                    MediaFlightAccessory.Corner.BottomLeading -> frame.bottom - image.height + badge.offset.y
                }
                drawImage(image, topLeft = Offset(x, y), alpha = accessories.alpha.coerceIn(0f, 1f))
            }
        }
    }
}

private fun DrawScope.drawFlight(flight: Flight, bitmap: ImageBitmap) {
    run {
        val frame = flight.frame
        if (frame.width <= 0f || frame.height <= 0f || flight.alpha <= 0f) return
        withTransform({ scale(flight.scale, flight.scale, pivot = frame.center) }) {
            val path = Path().apply { addRoundRect(flight.corners.roundRect(frame, flight.radius)) }
            clipPath(path) {
                // Aspect fill (iOS `.scaleAspectFill`).
                val imageW = bitmap.width.toFloat()
                val imageH = bitmap.height.toFloat()
                val scale = max(frame.width / imageW, frame.height / imageH)
                val srcW = frame.width / scale
                val srcH = frame.height / scale
                val srcOffset = IntOffset(((imageW - srcW) / 2).roundToInt(), ((imageH - srcH) / 2).roundToInt())
                drawImage(
                    image = bitmap,
                    srcOffset = srcOffset,
                    srcSize = IntSize(srcW.roundToInt().coerceAtMost(bitmap.width), srcH.roundToInt().coerceAtMost(bitmap.height)),
                    dstOffset = IntOffset(frame.left.roundToInt(), frame.top.roundToInt()),
                    dstSize = IntSize(frame.width.roundToInt(), frame.height.roundToInt()),
                    alpha = flight.alpha,
                    filterQuality = FilterQuality.High,
                )
            }
        }
    }
}

/**
 * The dismiss pan (iOS `dismissPan`, one finger). For media ([fromContent] false) it looks at
 * every touch before the content (the initial pass), and claims a drag heading down more than
 * sideways once it passes the touch slop, unless the image is zoomed. Claimed drags are
 * consumed, so the pager and the zoom never see them: pages turn only once a drag has shown it
 * isn't a dismissal. For documents ([fromContent] true) it looks after the content (the main
 * pass) and claims only a drag nothing in it took: a scroll view (scrolled or not) consumes
 * its drags, and one at its top hands its overscroll over through
 * [MediaViewerController.documentDismissConnection].
 */
private suspend fun PointerInputScope.dismissDrag(controller: MediaViewerController, slop: Float, fromContent: Boolean) {
    val pass = if (fromContent) PointerEventPass.Main else PointerEventPass.Initial
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = pass)
        if (controller.isLeaving) return@awaitEachGesture
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var total = Offset.Zero
        var anchor = Offset.Zero
        while (true) {
            val event = awaitPointerEvent(pass)
            if (event.changes.count { it.pressed } > 1) return@awaitEachGesture
            val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (!change.pressed || change.isConsumed) return@awaitEachGesture
            tracker.addPosition(change.uptimeMillis, change.position)
            total += change.positionChange()
            if (total.getDistance() > slop) {
                if (!controller.shouldBegin(total, fromContent)) return@awaitEachGesture
                change.consume()
                anchor = change.position
                break
            }
        }
        controller.dragBegan(anchor)
        var translation = Offset.Zero
        var ended = false
        try {
            while (true) {
                // Claimed: consumed before the content sees it, so nothing under it scrolls along.
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null || !change.pressed) {
                    val v = tracker.calculateVelocity()
                    ended = true
                    controller.dragEnded(translation, v)
                    return@awaitEachGesture
                }
                tracker.addPosition(change.uptimeMillis, change.position)
                event.changes.forEach { it.consume() }
                translation = change.position - anchor
                controller.dragChanged(translation)
            }
        } finally {
            if (!ended) controller.dragEnded(translation, Velocity.Zero)
        }
    }
}

/** Records where the finger is (without consuming), for a dismissal a scroll view hands over. */
private suspend fun PointerInputScope.trackPointer(controller: MediaViewerController) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.firstOrNull { it.pressed }?.let { controller.lastPointer = it.position }
        }
    }
}

/**
 * The dark room's status bar (iOS `preferredStatusBarStyle` / `prefersStatusBarHidden`): light
 * icons over the black backdrop, the session's own once it shows through, hidden while the
 * photo viewer's controls are hidden. When the viewer goes the bar follows the app's current
 * appearance (as [ai.opencode.core.ui.OpenCodeAppTheme] sets it), not the one it opened over.
 */
@Composable
private fun StatusBarEffect(controller: MediaViewerController) {
    val activity = LocalContext.current.findActivity() ?: return
    val window = activity.window
    val insets = remember(window) { WindowCompat.getInsetsController(window, window.decorView) }
    val appLight = !rememberAppAppearance().colorScheme.resolveIsDark(isSystemInDarkTheme())
    val currentAppLight by rememberUpdatedState(appLight)
    val light = controller.statusBarLight
    val hidden = controller.statusBarHidden
    LaunchedEffect(light, hidden, appLight) {
        insets.isAppearanceLightStatusBars = if (light) false else appLight
        if (hidden) insets.hide(WindowInsetsCompat.Type.statusBars()) else insets.show(WindowInsetsCompat.Type.statusBars())
    }
    DisposableEffect(window) {
        onDispose {
            insets.isAppearanceLightStatusBars = currentAppLight
            insets.show(WindowInsetsCompat.Type.statusBars())
        }
    }
}

/**
 * Back dismisses the viewer (iOS's accessibility escape). The platform's back dispatcher at
 * overlay priority, so it wins over the app's own handlers while the viewer is up.
 */
@Composable
internal fun MediaBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val activity = LocalContext.current.findActivity() ?: return
    val currentOnBack by rememberUpdatedState(onBack)
    DisposableEffect(activity, enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val callback = OnBackInvokedCallback { currentOnBack() }
        activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
        onDispose { activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback) }
    }
}

/**
 * [MediaBackHandler] that also hears the back gesture's progress (Android only), so the viewer
 * previews its dismissal while the swipe is held. A button or TalkBack back calls only
 * [onInvoked].
 */
@Composable
internal fun MediaPredictiveBackHandler(
    enabled: Boolean,
    onStarted: () -> Unit,
    onProgressed: (progress: Float, fromRightEdge: Boolean) -> Unit,
    onCancelled: () -> Unit,
    onInvoked: () -> Unit,
) {
    val activity = LocalContext.current.findActivity() ?: return
    val currentOnStarted by rememberUpdatedState(onStarted)
    val currentOnProgressed by rememberUpdatedState(onProgressed)
    val currentOnCancelled by rememberUpdatedState(onCancelled)
    val currentOnInvoked by rememberUpdatedState(onInvoked)
    DisposableEffect(activity, enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        // Android 13 reports no gesture progress to apps, so back there only dismisses.
        val callback = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            OnBackInvokedCallback { currentOnInvoked() }
        } else object : OnBackAnimationCallback {
            override fun onBackStarted(backEvent: BackEvent) {
                currentOnStarted()
            }

            override fun onBackProgressed(backEvent: BackEvent) {
                currentOnProgressed(backEvent.progress, backEvent.swipeEdge == BackEvent.EDGE_RIGHT)
            }

            override fun onBackCancelled() {
                currentOnCancelled()
            }

            override fun onBackInvoked() {
                currentOnInvoked()
            }
        }
        activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
        onDispose { activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback) }
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
