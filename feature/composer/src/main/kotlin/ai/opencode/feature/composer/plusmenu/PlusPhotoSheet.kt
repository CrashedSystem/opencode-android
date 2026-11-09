package ai.opencode.feature.composer.plusmenu

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.ui.appFormattingLocale
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.motion.MotionRubberBand
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconButton
import ai.opencode.core.ui.OpenCodeIconButtonStyle
import ai.opencode.core.ui.OpenCodePrimaryButton
import ai.opencode.core.ui.OpenCodeSecondaryButton
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.openCodeTapTarget
import ai.opencode.feature.composer.AttachmentPickerButton
import ai.opencode.feature.composer.ComposerMorph
import ai.opencode.feature.composer.ComposerMorphTunables
import ai.opencode.feature.composer.LocalComposerMorphContentActive
import ai.opencode.feature.composer.PickerTunables
import ai.opencode.feature.composer.PlusMenuTunables
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The newest photos for the New Plus Menu (OC-354), shared by the photo sheet and the recent
 * strip a hold on + shows, and the photos picked in the sheet, in tap order. iOS
 * `PlusPhotoLibrary`. A plain object with snapshot state, created by the session screen
 * (`remember { PlusPhotoLibrary() }`); call [stop] when the screen goes.
 */
class PlusPhotoLibrary(private val client: PhotoLibraryClient = dependency(PhotoLibraryClient)) {
    private val haptics: HapticsClient = dependency(HapticsClient)
    data class ThumbnailCacheKey(val id: String, val pixelSide: Int, val modified: java.time.Instant?)

    var access: PhotoLibraryAccess by mutableStateOf(PhotoLibraryAccess.NotDetermined)
        private set
    var photos: List<LibraryPhoto> by mutableStateOf(emptyList())
        private set

    /** Picked in the sheet, in tap order; attached with Add. */
    var picks: List<String> by mutableStateOf(emptyList())
        private set

    /**
     * The picks shown while the sheet folds away after Add or Back, so the Add pill and the order
     * badges stay as they were and dissolve with the sheet instead of changing on the tap.
     */
    private var closingPicks: List<String>? by mutableStateOf(null)

    /** What the sheet shows: its picks, or the last set while it folds away. */
    val shownPicks: List<String> get() = closingPicks ?: picks
    val shownPickCount: Int get() = shownPicks.size

    /** Top visible photo ID when the user scrolled the library, so reopening the sheet in the same session returns there. */
    var savedScrollPhotoID: String? = null

    /** Pixels per dp of the display (iOS `defaultDisplayScale`); views keep it current. */
    var displayScale: Float = Resources.getSystem()?.displayMetrics?.density?.takeIf { it > 0 } ?: 3f

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watch: Job? = null
    private var warmupTask: Job? = null
    private var generation = 0L
    private var contentGeneration = 0L
    private var reloadGeneration = 0L
    private var photosById: Map<String, LibraryPhoto> by mutableStateOf(emptyMap())
    private val thumbnails = HashMap<ThumbnailCacheKey, ImageBitmap>()
    private val thumbnailOrder = ArrayList<ThumbnailCacheKey>()
    private var memoryObserver: Pair<Context, ComponentCallbacks2>? = null

    /** iOS `didReceiveMemoryWarningNotification` → [purgeCache]: Android's memory trim. */
    private fun installMemoryObserverIfNeeded() {
        if (memoryObserver != null) return
        val context = runCatching { dependency(AppContext) }.getOrNull() ?: return
        val callbacks = object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) scope.launch { purgeCache() }
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() {
                scope.launch { purgeCache() }
            }
        }
        context.registerComponentCallbacks(callbacks)
        memoryObserver = context to callbacks
    }

    val canRead: Boolean get() = access.canRead

    /** Android only: no library to read ([PhotoLibraryAccess.Unavailable]), so Photos opens the system photo picker instead of the sheet. */
    val usesSystemPicker: Boolean get() = client.access() == PhotoLibraryAccess.Unavailable

    fun cacheKey(id: String, side: Float, scale: Float): ThumbnailCacheKey {
        val pixelSide = (side * max(scale, 1f)).roundToInt()
        return ThumbnailCacheKey(id, pixelSide, photosById[id]?.modified)
    }

    fun photo(id: String): LibraryPhoto? = photosById[id]

    fun cachedThumbnail(id: String, side: Float = GRID_THUMBNAIL_SIDE, scale: Float? = null): ImageBitmap? =
        thumbnails[cacheKey(id, side, max(scale ?: displayScale, 1f))]

    private fun clearCache() {
        contentGeneration += 1
        warmupTask?.cancel()
        warmupTask = null
        thumbnails.clear()
        thumbnailOrder.clear()
        // Never asked: nothing was cached, and the library stays untouched.
        if (access != PhotoLibraryAccess.NotDetermined) client.stopCaching()
    }

    /** iOS memory-warning purge. */
    fun purgeCache() {
        generation += 1
        clearCache()
    }

    fun stop() {
        watch?.cancel()
        watch = null
        memoryObserver?.let { (context, callbacks) -> context.unregisterComponentCallbacks(callbacks) }
        memoryObserver = null
        purgeCache()
    }

    /** The screen is gone for good. */
    fun close() {
        stop()
        scope.cancel()
    }

    private fun storeThumbnail(image: ImageBitmap, key: ThumbnailCacheKey) {
        val current = photosById[key.id] ?: return
        if (current.modified != key.modified) return
        if (thumbnails[key] == null) {
            thumbnailOrder.add(key)
            while (thumbnailOrder.size > MAX_CACHED_THUMBNAILS) thumbnails.remove(thumbnailOrder.removeAt(0))
        }
        thumbnails[key] = image
    }

    /** Reads what access there is without asking, and the newest photos if it can: the recent strip only ever shows what's already allowed. */
    suspend fun prime() {
        installMemoryObserverIfNeeded()
        val expected = generation
        val now = client.access()
        if (access != now) access = now
        // Never asked: watching the library raises nothing on Android, but iOS's would raise the
        // access alert over whatever screen is up; the sheet asks ([load]), as on iOS.
        if (now == PhotoLibraryAccess.NotDetermined) return
        reload(expected)
        if (!currentCoroutineContext().isActive || generation != expected) return
        startWatching(expected)
    }

    /** Asks for access if it was never asked (the sheet is open: the dialog is expected), then loads. */
    suspend fun load() {
        installMemoryObserverIfNeeded()
        val expected = generation
        var now = client.access()
        if (now == PhotoLibraryAccess.NotDetermined) {
            now = client.requestAccess()
            if (!currentCoroutineContext().isActive || generation != expected) return
        }
        if (access != now) access = now
        reload(expected)
        if (!currentCoroutineContext().isActive || generation != expected) return
        startWatching(expected)
    }

    fun toggle(id: String) {
        closingPicks = null
        if (id in picks) {
            picks = picks - id
            haptics.play(Haptic.selection)
        } else if (picks.size < AttachmentPickerButton.MAX_PHOTO_SELECTION) {
            picks = picks + id
            haptics.play(Haptic.selection)
        } else {
            haptics.play(Haptic.warning)
        }
    }

    /** Add answers the finger on the press; the photos land later. */
    fun addPressed() {
        haptics.play(Haptic.tap)
    }

    fun order(id: String): Int? = shownPicks.indexOf(id).takeIf { it >= 0 }?.plus(1)

    /** Picks handed over to be attached; the sheet starts over next time. */
    fun takePicks(): List<String> {
        val taken = picks
        closingPicks = taken
        clearPicks()
        return taken
    }

    fun clearPicks() {
        if (picks.isNotEmpty()) picks = emptyList()
    }

    /** Back: the picks are dropped, but shown until the sheet has folded away. */
    fun setAsidePicks() {
        closingPicks = picks
        clearPicks()
    }

    fun resetClosingPicks() {
        if (closingPicks != null) closingPicks = null
    }

    fun resetSavedScrollPosition() {
        savedScrollPhotoID = null
    }

    suspend fun thumbnail(id: String, side: Float, scale: Float, onDegraded: ((ImageBitmap) -> Unit)? = null): ImageBitmap? {
        val key = cacheKey(id, side, scale)
        thumbnails[key]?.let { return it }
        val expected = generation
        val expectedContent = contentGeneration
        val loaded = client.thumbnail(id, side, scale) { degraded ->
            scope.launch {
                if (generation == expected && contentGeneration == expectedContent && access.canRead) onDegraded?.invoke(degraded)
            }
        }
        if (!currentCoroutineContext().isActive || generation != expected || contentGeneration != expectedContent || !access.canRead) return null
        if (loaded != null) {
            storeThumbnail(loaded, key)
            return loaded
        }
        return thumbnails[key]
    }

    suspend fun imageData(id: String): ByteArray? = client.imageData(id)

    fun manageLimitedSelection() = client.manageLimitedSelection()

    fun openSettings() = client.openSettings()

    private suspend fun reload(expectedGeneration: Long = generation) {
        reloadGeneration += 1
        val expectedReload = reloadGeneration
        warmupTask?.cancel()
        warmupTask = null
        if (!access.canRead) {
            if (photos.isNotEmpty()) photos = emptyList()
            if (photosById.isNotEmpty()) photosById = emptyMap()
            clearCache()
            clearPicks()
            return
        }
        val expectedContent = contentGeneration
        val newest = client.recent(GRID_LIMIT)
        if (!currentCoroutineContext().isActive || generation != expectedGeneration || contentGeneration != expectedContent ||
            reloadGeneration != expectedReload || !access.canRead
        ) {
            return
        }
        if (newest != photos) photos = newest
        val byId = newest.associateBy { it.id }
        if (byId != photosById) photosById = byId
        thumbnails.keys.retainAll { key -> byId[key.id]?.modified == key.modified && byId.containsKey(key.id) }
        thumbnailOrder.retainAll { thumbnails.containsKey(it) }
        if (picks.any { it !in byId }) picks = picks.filter { it in byId }
        val scale = displayScale
        val warmPhotos = newest.take(18)
        warmupTask = scope.launch {
            for (photo in warmPhotos) {
                if (!isActive || generation != expectedGeneration || contentGeneration != expectedContent ||
                    reloadGeneration != expectedReload || !access.canRead
                ) {
                    return@launch
                }
                if (thumbnails[cacheKey(photo.id, GRID_THUMBNAIL_SIDE, scale)] != null) continue
                thumbnail(photo.id, GRID_THUMBNAIL_SIDE, scale)
            }
        }
    }

    private fun startWatching(expectedGeneration: Long) {
        if (generation != expectedGeneration || watch != null) return
        val changes = client.changes()
        watch = scope.launch {
            changes.collect {
                val current = generation
                val now = client.access()
                if (access != now) access = now
                reload(current)
            }
        }
    }

    companion object {
        const val GRID_LIMIT = 300
        const val MAX_CACHED_THUMBNAILS = 72

        /** dp. */
        const val GRID_THUMBNAIL_SIDE = 140f
    }
}

/**
 * Pure detent state and 1:1 finger-tracking geometry for the two-detent photo sheet (compact
 * over the keyboard's place, expanded up to the header ceiling). iOS `PlusPhotoSheetDetent`.
 * Units are whatever the caller measures in (dp for the iOS-matching thresholds). The M3
 * [PlusPhotoSheet] uses `SheetState`'s own detents; this stays for a custom morph surface.
 */
data class PlusPhotoSheetDetent(
    var isExpanded: Boolean = false,
    var compactTop: Float = 0f,
    var ceiling: Float = 0f,
    var top: Float = if (isExpanded) ceiling else compactTop,
    var drag: Float = 0f,
    /** Captured at touch-down and kept through settlement so changing detents can't switch drag geometry in the release frame. */
    var dragOriginExpanded: Boolean? = null,
) {
    sealed interface Settlement {
        data class Stay(val expanded: Boolean, val top: Float, val initialVelocity: Float) : Settlement
        data object Close : Settlement
    }

    fun open(compactTop: Float, ceiling: Float) {
        isExpanded = false
        this.compactTop = compactTop
        this.ceiling = ceiling
        top = compactTop
        drag = 0f
        dragOriginExpanded = null
    }

    /** Maps raw vertical finger translation to sheet `drag`, 1:1 between detents and rubber-banding past them. */
    fun dragChanged(translation: Float): Float {
        if (drag == 0f) dragOriginExpanded = isExpanded
        val maxUp = max(top - ceiling, 0f)
        drag = if (translation < 0) {
            val up = -translation
            if (up <= maxUp) translation else -(maxUp + rubberBand(up - maxUp, limit = 28f, factor = 0.22f))
        } else if (isExpanded) {
            val maxDown = max(compactTop - top, 0f)
            if (translation <= maxDown) translation else maxDown + rubberBand(translation - maxDown, limit = 48f, factor = 0.35f)
        } else {
            translation
        }
        return drag
    }

    /** Resolves the target detent (or dismissal) when a drag ends, with the normalized spring velocity that continues the finger. */
    fun settle(
        predicted: Float?,
        velocity: Float,
        expandDrag: Float = ComposerMorphTunables.photoExpandDrag.toFloat(),
        expandVelocity: Float = ComposerMorphTunables.photoExpandVelocity.toFloat(),
        collapseDrag: Float = ComposerMorphTunables.photoCollapseDrag.toFloat(),
    ): Settlement {
        val currentDrag = drag
        val end = predicted ?: currentDrag
        val currentTop = top + currentDrag
        fun stay(expanded: Boolean): Settlement {
            val targetTop = if (expanded) ceiling else compactTop
            val v0 = normalizedInitialVelocity(velocity, currentTop, targetTop)
            isExpanded = expanded
            top = targetTop
            drag = 0f
            return Settlement.Stay(expanded, targetTop, v0)
        }
        return if (!isExpanded) {
            if (currentDrag < -expandDrag || (velocity < -expandVelocity && currentDrag < -12) || (end < -expandDrag && currentDrag < -16)) {
                stay(true)
            } else if (currentDrag > 0 && closes(currentDrag, end, velocity)) {
                drag = 0f
                Settlement.Close
            } else {
                stay(false)
            }
        } else {
            if (currentDrag > collapseDrag || (velocity > expandVelocity && currentDrag > 12) || (end > collapseDrag && currentDrag > 16)) {
                stay(false)
            } else {
                stay(true)
            }
        }
    }

    fun toggle(): Settlement {
        isExpanded = !isExpanded
        top = if (isExpanded) ceiling else compactTop
        drag = 0f
        return Settlement.Stay(isExpanded, top, 0f)
    }

    companion object {
        /** UIKit's rubber band (`MotionRubberBand`), never more than [limit]. */
        fun rubberBand(excess: Float, limit: Float, factor: Float): Float {
            if (excess <= 0 || limit <= 0 || factor <= 0) return 0f
            return MotionRubberBand(limit.toDouble(), factor.toDouble()).stretch(excess.toDouble()).toFloat()
        }

        fun normalizedInitialVelocity(velocity: Float, from: Float, to: Float): Float {
            val remaining = to - from
            if (abs(remaining) <= 1) return 0f
            val normalized = velocity / remaining
            if (normalized <= 0) return 0f
            return min(normalized, 18f)
        }

        /** iOS `ComposerMorph.closes(translation:predicted:velocity:)`. */
        fun closes(translation: Float, predicted: Float, velocity: Float): Boolean =
            ComposerMorph.closes(translation.toDouble(), predicted.toDouble(), velocity.toDouble())
    }
}

/**
 * Cancels tile and camera taps while a sheet drag is in flight and for 80 ms after it ends,
 * without sticking if a drag begins and ends within one frame or clearing mid-drag if a
 * second drag starts within 80 ms. iOS `PlusPhotoSheetTapSuppression`. (Compose already
 * cancels a click when its pointer turns into a drag; this covers drags owned elsewhere.)
 */
class PlusPhotoSheetTapSuppression(private val scope: CoroutineScope) {
    var isSuppressed: Boolean by mutableStateOf(false)
        private set
    private var task: Job? = null

    fun beginDrag() {
        task?.cancel()
        task = null
        isSuppressed = true
    }

    fun scheduleReset(delayMillis: Long = 80) {
        task?.cancel()
        if (!isSuppressed) {
            task = null
            return
        }
        task = scope.launch {
            delay(delayMillis)
            isSuppressed = false
            task = null
        }
    }

    fun cancel() {
        task?.cancel()
        task = null
        isSuppressed = false
    }
}

/** A node's bounds in screen pixels (iOS `frame(in: .global)` in window points). */
internal fun LayoutCoordinates.boundsOnScreen(): Rect =
    Rect(positionOnScreen(), Size(size.width.toFloat(), size.height.toFloat()))

/**
 * The photo sheet (iOS `PlusPhotoSheetContent` + `PlusPhotoSheetFoot` inside
 * `ComposerMorphSurface`): an M3 modal bottom sheet whose partial detent is iOS's compact
 * detent and whose expanded detent is the header ceiling. The camera tile, then the newest
 * photos three across, with Back and All Photos / Add along its foot. Tapping photos picks
 * them (up to four, in order); Add hands the picks over ([onAdd]) for the session to reserve
 * slots, launch [PhotoFlights] and close the sheet.
 *
 * @param onBack ‹ Back: return to the + menu.
 * @param onAdd the picked photo IDs, in tap order ([PlusPhotoLibrary.takePicks] already ran).
 * @param onCapture a photo taken from the sheet's camera (JPEG), with no review.
 * @param onDismiss the sheet was swiped or tapped away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlusPhotoSheet(
    library: PlusPhotoLibrary,
    flights: PhotoFlights,
    camera: PlusCameraPresentation,
    onBack: () -> Unit,
    onAdd: (List<String>) -> Unit,
    onCapture: (ByteArray) -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
) {
    val scope = rememberCoroutineScope()
    val isExpanded = sheetState.currentValue == SheetValue.Expanded
    fun toggleExpand() {
        scope.launch { if (isExpanded) sheetState.partialExpand() else sheetState.expand() }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { PlusPhotoSheetGrabber(isExpanded = isExpanded, onToggle = ::toggleExpand) },
    ) {
        PhotoLibraryAccessHost()
        var hiddenBottom by remember { mutableFloatStateOf(0f) }
        val windowHeight = LocalWindowInfo.current.containerSize.height
        val navigationInset = WindowInsets.navigationBars.getBottom(LocalDensity.current)
        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { coordinates ->
                    // Keep the foot on the sheet's visible edge at the partial detent.
                    val bottom = coordinates.boundsOnScreen().bottom
                    hiddenBottom = max(0f, bottom - (windowHeight - navigationInset))
                },
        ) {
            PlusPhotoSheetContent(
                library = library,
                flights = flights,
                camera = camera,
                isExpanded = isExpanded,
                onCamera = { camera.expanded = true },
                onDismiss = onDismiss,
                contentBottomPadding = { hiddenBottom },
            )
            PlusPhotoSheetFoot(
                library = library,
                isExpanded = isExpanded,
                onBack = onBack,
                onAllPhotos = { scope.launch { sheetState.expand() } },
                onAdd = { onAdd(library.takePicks()) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .graphicsLayer { translationY = -hiddenBottom },
            )
        }
        PlusCameraSurface(presentation = camera, sheetIsOpen = true, onCapture = onCapture)
    }
}

/**
 * iOS's grabber header: a 36 × 5 capsule in a 22 pt band; tap toggles Half/Full. Hosted in the
 * morph ([onDrag] non-null), a vertical drag past 4 dp moves the sheet too (screen coordinates,
 * so the band moving with the sheet doesn't feed back), and its release settles it.
 */
@Composable
private fun PlusPhotoSheetGrabber(
    isExpanded: Boolean,
    onToggle: () -> Unit,
    /** Floats on the photos' top row ([PickerTunables.PHOTO_GRABBER_OVER_GRID]) instead of its own band. */
    over: Boolean = false,
    onDrag: ((Float) -> Unit)? = null,
    onDragEnded: ((Float, Float) -> Unit)? = null,
    onDragCancelled: (() -> Unit)? = null,
) {
    val coords = remember { CoordinatesHolder() }
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnded by rememberUpdatedState(onDragEnded)
    val currentOnDragCancelled by rememberUpdatedState(onDragCancelled)
    val inset = PlusMenuTunables.GRID_INSET
    Box(
        Modifier
            // Over the photos it takes only its own width, so the top row keeps its taps.
            .then(if (over) Modifier.size(width = 88.dp, height = (GRABBER_HEIGHT + inset).dp) else Modifier.fillMaxWidth())
            .heightIn(min = GRABBER_HEIGHT.dp)
            .onGloballyPositioned { coords.value = it }
            .then(
                if (onDrag == null) Modifier else Modifier.sheetDrag(
                    coords = coords,
                    slopDp = 4f,
                    onMove = { dy, change ->
                        change.consume()
                        currentOnDrag?.invoke(dy)
                    },
                    onEnd = { dy, velocity -> currentOnDragEnded?.invoke(dy + velocity * PREDICT_SECONDS, velocity) },
                    onCancel = { currentOnDragCancelled?.invoke() },
                ),
            )
            .clickable(onClickLabel = null, role = Role.Button, onClick = onToggle)
            .clearAndSetSemantics {
                contentDescription = "Sheet Grabber"
                stateDescription = if (isExpanded) "Full" else "Half"
                role = Role.Button
                testTag = "plus-photo-grabber"
                onClick { onToggle(); true }
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            Modifier
                .padding(top = (if (over) 7f + inset else 7f).dp)
                .size(width = 36.dp, height = 5.dp)
                .then(if (over) Modifier.shadow(2.dp, OpenCodeShape.capsule, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.35f)) else Modifier)
                .background(if (over) Color.White.copy(alpha = 0.8f) else OpenCodeTheme.colors.tertiaryText, OpenCodeShape.capsule),
        )
    }
}

/** A node's latest coordinates, read when a pointer moves; not observed. */
private class CoordinatesHolder {
    var value: LayoutCoordinates? = null
}

/**
 * iOS `DragGesture(minimumDistance:, coordinateSpace: .global)`, observed beside the node's own
 * gestures (`simultaneousGesture`): it watches the initial pass without taking the pointer until
 * [onMove] consumes a change. Translations are root-space dp from the touch's start, so a sheet
 * that moves under the finger doesn't feed back; velocity is dp/s.
 */
private fun Modifier.sheetDrag(
    coords: CoordinatesHolder,
    slopDp: Float,
    onBegin: () -> Unit = {},
    onMove: (dy: Float, change: PointerInputChange) -> Unit,
    onEnd: (dy: Float, velocity: Float) -> Unit,
    onCancel: () -> Unit,
): Modifier = pointerInput(Unit) {
    val px = density
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        fun root(position: Offset): Offset = coords.value?.takeIf { it.isAttached }?.localToRoot(position) ?: position
        val start = root(down.position)
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, start)
        var dragging = false
        var dy = 0f
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                val point = root(change.position)
                tracker.addPosition(change.uptimeMillis, point)
                dy = (point.y - start.y) / px
                if (!change.pressed) {
                    if (dragging) onEnd(dy, tracker.calculateVelocity().y / px)
                    return@awaitEachGesture
                }
                if (!dragging && hypot((point.x - start.x) / px, dy) >= slopDp) {
                    dragging = true
                    onBegin()
                }
                if (dragging) onMove(dy, change)
            }
            if (dragging) onCancel()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            if (dragging) onCancel()
            throw cancelled
        }
    }
}

private const val GRID_SPACING = 2f
private const val GRABBER_HEIGHT = 22f
private const val CAMERA_KEY = "__camera__"

/** UIKit's normal deceleration: a throw's predicted travel is velocity × 0.499 s (SwiftUI `predictedEndTranslation`). */
private const val PREDICT_SECONDS = 0.499f

/**
 * The sheet's content (iOS `PlusPhotoSheetContent`): the camera, then the newest photos, three
 * across. Tapping photos picks them. Laid out once where the sheet rests and revealed by the
 * sheet's surface, so nothing reflows as it grows.
 *
 * In the M3 [PlusPhotoSheet] the sheet owns its drag handle and detents. Hosted in the morph
 * ([ComposerMorphPhotoSheet], [onDrag] non-null), it draws its own grabber and drives the
 * sheet as iOS does: compact, the grid doesn't scroll and a drag anywhere moves the sheet;
 * expanded, it scrolls, and a pull down from its top collapses it (a baseline taken when the
 * pull starts mid-scroll). Taps are suppressed while a sheet drag runs and 80 ms after.
 *
 * @param onDrag the sheet drag's translation (dp).
 * @param onDragEnded predicted end translation and velocity (dp, dp/s).
 */
@Composable
fun PlusPhotoSheetContent(
    library: PlusPhotoLibrary,
    flights: PhotoFlights,
    camera: PlusCameraPresentation,
    isExpanded: Boolean,
    onCamera: () -> Unit,
    onDismiss: () -> Unit,
    contentBottomPadding: () -> Float = { 0f },
    onDrag: ((Float) -> Unit)? = null,
    onDragEnded: (predictedTranslation: Float, velocity: Float) -> Unit = { _, _ -> },
    onDragCancelled: () -> Unit = {},
    onToggleExpand: () -> Unit = {},
) {
    val density = LocalDensity.current.density
    library.displayScale = density
    val scope = rememberCoroutineScope()
    val tapSuppression = remember { PlusPhotoSheetTapSuppression(scope) }
    DisposableEffect(tapSuppression) { onDispose { tapSuppression.cancel() } }
    val gridInset = PlusMenuTunables.GRID_INSET
    val topRadius = max(ComposerMorphTunables.topRadius.toFloat() - gridInset, 14f)
    val bottomRadius = max(ComposerMorphTunables.bottomRadius.toFloat() - gridInset, 20f)
    val gridShape = RoundedCornerShape(topRadius.dp, topRadius.dp, bottomRadius.dp, bottomRadius.dp)
    val photos = library.photos
    val initialIndex = remember {
        library.savedScrollPhotoID?.let { saved -> photos.indexOfFirst { it.id == saved }.takeIf { it >= 0 }?.plus(1) } ?: 0
    }
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = initialIndex)
    // Kept composed by a closed morph (`retainsContent`): ask, load and report frames only while on screen.
    val active = LocalComposerMorphContentActive.current

    LaunchedEffect(library, active) {
        if (!active) return@LaunchedEffect
        // Reopened after the saved place was reset (another session): back to the top, as a fresh sheet.
        val saved = library.savedScrollPhotoID?.let { id -> library.photos.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.plus(1) } ?: 0
        if (saved == 0 && (gridState.firstVisibleItemIndex != 0 || gridState.firstVisibleItemScrollOffset != 0)) gridState.scrollToItem(0)
        library.load()
    }
    LaunchedEffect(gridState, isExpanded) {
        if (!isExpanded) return@LaunchedEffect
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (index, offset) ->
                if (index == 0 && offset <= 4 * density) {
                    library.savedScrollPhotoID = null
                } else if (library.photos.isNotEmpty()) {
                    // iOS: the first visible row (at least the second), its first photo after the camera.
                    val photoIndex = (max(index, 3) - 1).coerceIn(0, library.photos.size - 1)
                    library.savedScrollPhotoID = library.photos[photoIndex].id
                }
            }
    }
    val hosted = onDrag != null
    val currentExpanded by rememberUpdatedState(isExpanded)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnded by rememberUpdatedState(onDragEnded)
    val currentOnDragCancelled by rememberUpdatedState(onDragCancelled)
    val gridCoords = remember { CoordinatesHolder() }
    var collapseBaseline by remember { mutableStateOf<Float?>(null) }
    val footHeight = PlusPhotoSheetFootDefaults.HEIGHT + PlusPhotoSheetFootDefaults.INSET * 2
    val bottomPadding = with(LocalDensity.current) { contentBottomPadding().toDp() } + footHeight.dp
    // Over the grid: the photos sit `gridInset` from the glass on every side and the grabber
    // floats on the top row. Otherwise in its own band above the grid.
    val grabberOver = PickerTunables.PHOTO_GRABBER_OVER_GRID
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = gridState,
            horizontalArrangement = Arrangement.spacedBy(GRID_SPACING.dp),
            verticalArrangement = Arrangement.spacedBy(GRID_SPACING.dp),
            contentPadding = PaddingValues(bottom = bottomPadding),
            // iOS `scrollDisabled(!isExpanded)`: compact, a drag moves the sheet instead.
            userScrollEnabled = !hosted || isExpanded,
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = gridInset.dp,
                    end = gridInset.dp,
                    bottom = gridInset.dp,
                    top = if (!hosted) 0.dp else if (grabberOver) gridInset.dp else GRABBER_HEIGHT.dp,
                )
                .clip(gridShape)
                .onGloballyPositioned { coordinates ->
                    gridCoords.value = coordinates
                    if (!active) return@onGloballyPositioned
                    val frame = coordinates.boundsOnScreen()
                    flights.sheetViewport = frame
                    camera.viewport = frame
                }
                .then(
                    if (!hosted) Modifier else Modifier.sheetDrag(
                        coords = gridCoords,
                        slopDp = 6f,
                        onBegin = { tapSuppression.beginDrag() },
                        onMove = { dy, change ->
                            tapSuppression.beginDrag()
                            if (!currentExpanded) {
                                change.consume()
                                currentOnDrag?.invoke(dy)
                                return@sheetDrag
                            }
                            val baseline = collapseBaseline
                            // How far the grid is scrolled from its top (dp; past the first row, far).
                            val scrolled = if (gridState.firstVisibleItemIndex == 0) gridState.firstVisibleItemScrollOffset / density else Float.MAX_VALUE
                            if (baseline != null) {
                                change.consume()
                                currentOnDrag?.invoke(max(0f, dy - baseline))
                            } else if (dy > 0 && scrolled <= 2f) {
                                val start = if (scrolled <= 0.5f) 0f else dy
                                collapseBaseline = start
                                change.consume()
                                currentOnDrag?.invoke(max(0f, dy - start))
                            }
                        },
                        onEnd = { dy, velocity ->
                            tapSuppression.scheduleReset()
                            val predicted = dy + velocity * PREDICT_SECONDS
                            val baseline = collapseBaseline
                            collapseBaseline = null
                            if (!currentExpanded) {
                                currentOnDragEnded(predicted, velocity)
                            } else if (baseline != null) {
                                val pull = max(0f, dy - baseline)
                                currentOnDragEnded(max(pull, predicted - baseline), velocity)
                            }
                        },
                        onCancel = {
                            collapseBaseline = null
                            currentOnDragCancelled()
                            tapSuppression.scheduleReset()
                        },
                    ),
                )
                .semantics {
                    contentDescription = "Photos"
                    testTag = "plus-photo-grid"
                },
        ) {
            item(key = CAMERA_KEY) {
                PlusCameraTile(camera = camera, active = active, onCamera = { if (!tapSuppression.isSuppressed) onCamera() })
            }
            items(photos.size, key = { photos[it].id }) { index ->
                val photo = photos[index]
                PlusPhotoTile(
                    photo = photo,
                    order = library.order(photo.id),
                    canPickMore = library.picks.size < AttachmentPickerButton.MAX_PHOTO_SELECTION,
                    scale = density,
                    inFlight = flights.hidesPhoto(photo.id),
                    flights = flights,
                    active = active,
                    cached = library.cachedThumbnail(photo.id, PlusPhotoLibrary.GRID_THUMBNAIL_SIDE, density),
                    load = { id, side, scale, onDegraded -> library.thumbnail(id, side, scale, onDegraded) },
                    onTap = { if (!tapSuppression.isSuppressed) library.toggle(photo.id) },
                )
            }
            if (library.access == PhotoLibraryAccess.Denied) {
                item(span = { GridItemSpan(maxLineSpan) }) { DeniedNotice(onOpenSettings = library::openSettings) }
            }
        }
        if (hosted) {
            PlusPhotoSheetGrabber(
                isExpanded = isExpanded,
                onToggle = onToggleExpand,
                over = grabberOver,
                onDrag = onDrag,
                onDragEnded = onDragEnded,
                onDragCancelled = onDragCancelled,
            )
        }
    }
}

@Composable
private fun PlusCameraTile(camera: PlusCameraPresentation, active: Boolean, onCamera: () -> Unit) {
    DisposableEffect(camera, active) {
        camera.mounted = active
        onDispose { camera.mounted = false }
    }
    Box(
        Modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .onGloballyPositioned { if (active) camera.tile = it.boundsOnScreen() }
            .clickable(onClickLabel = null, role = Role.Button, onClick = onCamera)
            .semantics { contentDescription = "Camera" },
        contentAlignment = Alignment.Center,
    ) {
        OpenCodeIcon(SFSymbol.Camera, contentDescription = null, size = 28.dp, tint = OpenCodeTheme.colors.text)
    }
}

@Composable
private fun DeniedNotice(onOpenSettings: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Photo access is off", style = OpenCodeTextStyles.headline, color = OpenCodeTheme.colors.text)
        Text(
            "Allow access in Settings to pick photos here.",
            style = OpenCodeTextStyles.subheadline,
            color = OpenCodeTheme.colors.secondaryText,
            textAlign = TextAlign.Center,
        )
        OpenCodeSecondaryButton(text = "Open Settings", onClick = onOpenSettings)
    }
}

/** iOS `PlusPhotoSheetFoot.height` / `inset` (dp). */
object PlusPhotoSheetFootDefaults {
    /** Material 3 Expressive's floating toolbar height. */
    const val HEIGHT = 64f
    const val INSET = 16f
    /** The toolbar's own inner padding at its ends. */
    const val PADDING = 8f
}

/**
 * The photo sheet's foot (iOS `PlusPhotoSheetFoot`) as a Material 3 Expressive floating toolbar,
 * like the system photo picker's: Back on the leading edge and the trailing [PlusPhotoPillControl]:
 * All Photos with no picks (expands the sheet), Add N Photos once photos are picked; a long press
 * offers All Photos and, under limited access, Select More Photos….
 */
@Composable
fun PlusPhotoSheetFoot(
    library: PlusPhotoLibrary,
    isExpanded: Boolean,
    onBack: () -> Unit,
    onAllPhotos: () -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val count = library.shownPickCount
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = PlusPhotoSheetFootDefaults.INSET.dp, end = PlusPhotoSheetFootDefaults.INSET.dp, bottom = PlusPhotoSheetFootDefaults.INSET.dp)
            .height(PlusPhotoSheetFootDefaults.HEIGHT.dp),
        shape = OpenCodeShape.capsule,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 3.dp,
    ) {
        Row(
            Modifier.padding(horizontal = PlusPhotoSheetFootDefaults.PADDING.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OpenCodeIconButton(
                symbol = SFSymbol.ChevronBackward,
                contentDescription = "Back",
                onClick = onBack,
                // A bare glyph: the toolbar is its surface.
                style = OpenCodeIconButtonStyle.Standard,
                // iOS `accessibilityHint("Returns to attachment options")`: TalkBack reads “Double-tap to return…”.
                modifier = Modifier.semantics {
                    testTag = "plus-photo-back"
                    onClick(label = "Return to attachment options") { onBack(); true }
                },
            )
            Spacer(Modifier.weight(1f).width(12.dp))
            PlusPhotoPillControl(
                count = count,
                isLimited = library.access == PhotoLibraryAccess.Limited,
                onAllPhotos = onAllPhotos,
                onSelectMore = library::manageLimitedSelection,
                onAdd = {
                    library.addPressed()
                    onAdd()
                },
            )
        }
    }
}

/**
 * “▶ 0:50” in a video's corner, on the grid and on its composer thumbnail (iOS `VideoDurationBadge`,
 * `composer.plusMenu.videoBadge*`): a dark backdrop so it reads on light and dark frames; decorative.
 */
@Composable
fun VideoDurationBadge(duration: String, compact: Boolean = false, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(
                Color.Black.copy(alpha = PlusMenuTunables.VIDEO_BADGE_OPACITY.toFloat()),
                RoundedCornerShape(PlusMenuTunables.VIDEO_BADGE_RADIUS.dp),
            )
            .padding(horizontal = if (compact) 5.dp else 6.dp, vertical = if (compact) 2.dp else 3.dp)
            .clearAndSetSemantics {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 4.dp),
    ) {
        // iOS: play.fill at 7 pt (strip) / 8 pt (grid), the duration at 10 pt / caption2, semibold, tabular.
        OpenCodeIcon(SFSymbol.PlayFill, contentDescription = null, size = if (compact) 7.dp else 8.dp, tint = Color.White)
        Text(
            duration,
            color = Color.White,
            style = (if (compact) OpenCodeTextStyles.caption2.copy(fontSize = 10.sp) else OpenCodeTextStyles.caption2).copy(
                fontWeight = FontWeight.SemiBold,
                fontFeatureSettings = "tnum",
            ),
        )
    }
}

private val tileDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(ZoneId.systemDefault())

/** One photo or video in the sheet: its square thumbnail, a duration badge for videos, and its place in the picks. iOS `PlusPhotoTile`. */
@Composable
private fun PlusPhotoTile(
    photo: LibraryPhoto,
    order: Int?,
    canPickMore: Boolean,
    scale: Float,
    inFlight: Boolean,
    flights: PhotoFlights,
    active: Boolean,
    cached: ImageBitmap?,
    load: suspend (String, Float, Float, (ImageBitmap) -> Unit) -> ImageBitmap?,
    onTap: () -> Unit,
) {
    // Starts from the warm cache, so a warmed tile draws on its first frame without a second composition.
    var image by remember(photo.id) { mutableStateOf(cached) }
    val resolved = image ?: cached
    val place = remember { PhotoTilePlace() }
    val picked = order != null

    fun report() {
        val frame = place.frame
        flights.source(
            photo.id,
            if (order == null || frame == Rect.Zero) null else PhotoFlights.Source(frame = frame, radius = 0f, image = resolved, isSheet = true),
        )
    }

    val key = PlusPhotoLibrary.ThumbnailCacheKey(photo.id, (PlusPhotoLibrary.GRID_THUMBNAIL_SIDE * max(scale, 1f)).roundToInt(), photo.modified)
    LaunchedEffect(key) {
        if (cached != null) image = cached
        val loaded = load(photo.id, PlusPhotoLibrary.GRID_THUMBNAIL_SIDE, scale) { preview -> if (image == null) image = preview }
        if (loaded != null) image = loaded
    }
    LaunchedEffect(order, resolved, active) {
        // Closed but kept composed: nothing leaves from an off-screen tile.
        if (!active) flights.source(photo.id, null) else if (order != null) report()
    }
    DisposableEffect(photo.id) { onDispose { flights.source(photo.id, null) } }

    val reduceMotion = rememberReduceMotion()
    // Gone at once as its photo takes off; back on the swap's fade once it lands.
    val alpha by animateFloatAsState(
        targetValue = if (inFlight) 0f else 1f,
        animationSpec = if (inFlight) androidx.compose.animation.core.snap() else Motion.swap.animation(reduceMotion = reduceMotion),
        label = "tile-in-flight",
    )
    // iOS `PhotoSelectionButtonStyle`: press feedback changes only overlays. A press only dims
    // (previewing the next number flashed a "1" on any tile a sheet drag started on). Picked, the
    // photo draws inset with rounded corners under its number, as the system photo picker marks a
    // selection; the number shows at once, with the selection haptic, and the inset springs.
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    val dim = if (isPressed && !picked) PlusMenuTunables.SELECTION_DIM.toFloat() * 0.5f else 0f
    val pickedInset by animateFloatAsState(
        targetValue = if (picked) 1f else 0f,
        animationSpec = MotionRole.MicroIn.animation(if (picked) MotionPhase.Enter else MotionPhase.Exit, reduceMotion),
        label = "tile-picked",
    )
    val clip = remember { Path() }
    // The limit is picked: the rest wash out, so a tap past it reads as “full”, not broken.
    val full = !picked && !canPickMore
    val limitDim by animateFloatAsState(
        targetValue = if (full) PlusMenuTunables.LIMIT_DIM.toFloat() else 0f,
        animationSpec = Motion.swap.animation(if (full) MotionPhase.Enter else MotionPhase.Exit, reduceMotion),
        label = "tile-limit",
    )
    val washColor = MaterialTheme.colorScheme.background
    val description = listOfNotNull(photo.formattedDuration, photo.created?.let(tileDateFormatter.withLocale(appFormattingLocale())::format)).joinToString(", ")
    Box(
        Modifier
            .aspectRatio(1f)
            .graphicsLayer { this.alpha = alpha }
            .onGloballyPositioned {
                if (!active) return@onGloballyPositioned
                place.frame = it.boundsOnScreen()
                if (order != null) report()
            }
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .drawWithContent {
                // The photo first, then the badges over it, then the wash over everything.
                val inset = PlusMenuTunables.SELECTED_TILE_INSET.dp.toPx() * pickedInset
                val photoRect = Rect(inset, inset, size.width - inset, size.height - inset)
                resolved?.let { image ->
                    if (inset > 0.5f) {
                        val radius = PlusMenuTunables.SELECTED_TILE_RADIUS.dp.toPx() * pickedInset
                        clip.reset()
                        clip.addRoundRect(RoundRect(photoRect, CornerRadius(radius)))
                        clipPath(clip) { drawAspectFill(image, photoRect) }
                    } else {
                        drawAspectFill(image, photoRect)
                    }
                }
                if (dim > 0f) drawRect(Color.Black.copy(alpha = dim))
                drawContent()
                if (limitDim > 0f) drawRect(washColor.copy(alpha = limitDim))
            }
            .clickable(interactionSource = interaction, indication = null, enabled = !inFlight, role = Role.Button, onClick = onTap)
            .clearAndSetSemantics {
                contentDescription = if (photo.isVideo) "Video" else "Photo"
                stateDescription = if (full) {
                    listOf(description, "${AttachmentPickerButton.MAX_PHOTO_SELECTION} photos is the most you can add")
                        .filter { it.isNotEmpty() }.joinToString(", ")
                } else {
                    description
                }
                selected = picked
                role = Role.Button
                if (!inFlight) onClick { onTap(); true }
            },
    ) {
        photo.formattedDuration?.let { duration ->
            VideoDurationBadge(duration, modifier = Modifier.align(Alignment.BottomStart).padding(PlusMenuTunables.VIDEO_BADGE_INSET.dp))
        }
        if (picked) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(PlusMenuTunables.SELECTION_BADGE_INSET.dp)
                    .size(PlusMenuTunables.SELECTION_BADGE_SIZE.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                    .border(1.5.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    order?.toString() ?: "",
                    // The accent's own foreground, so the number reads on a light accent too.
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = OpenCodeTextStyles.caption1.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
                )
            }
        }
    }
}

/** A tile's frame on screen, read when a flight leaves from it; not observed, so the sheet moving never recomposes its tiles. */
class PhotoTilePlace {
    var frame: Rect = Rect.Zero
}
