package ai.opencode.feature.session

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.AccessibilityFocusRequest
import ai.opencode.core.ui.accessibilityFocusRequest
import ai.opencode.core.ui.rememberAccessibilityFocusRequest
import ai.opencode.core.ui.inContainer
import ai.opencode.feature.composer.AttachmentMenuMetrics
import ai.opencode.feature.composer.AttachmentMenuPhase
import ai.opencode.feature.composer.AttachmentPickerButton
import ai.opencode.feature.composer.ComposerImageSource
import ai.opencode.feature.composer.ComposerMorph
import ai.opencode.feature.composer.ComposerMorphGeometry
import ai.opencode.feature.composer.ComposerMorphSurface
import ai.opencode.feature.composer.ComposerMorphTunables
import ai.opencode.feature.composer.modelpicker.ModelPickerTunables
import ai.opencode.feature.composer.plusmenu.PhotoFlights
import ai.opencode.feature.composer.plusmenu.PhotoLibraryAccessHost
import ai.opencode.feature.composer.plusmenu.PlusCameraPresentation
import ai.opencode.feature.composer.plusmenu.PlusCameraSurface
import ai.opencode.feature.composer.plusmenu.PlusCameraTunables
import ai.opencode.feature.composer.plusmenu.PlusPhotoLibrary
import ai.opencode.feature.composer.plusmenu.PlusPhotoSheetContent
import ai.opencode.feature.composer.plusmenu.PlusPhotoSheetDetent
import ai.opencode.feature.composer.plusmenu.PlusPhotoSheetFoot
import ai.opencode.feature.composer.plusmenu.RecentPhotoPeek
import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.max

// Port of iOS `Session/SessionView+PhotoSheet.swift`. The New Plus Menu (graduated in iOS p217):
// the menu's surface grows into the composer's photo sheet (`PlusPhotoSheet` inside
// `ComposerMorphSurface`, [SessionPlusPhotoSheet]):
// the camera first, then the newest photos, Back and Add along its foot, two detents, and the
// picks flying into the composer's thumbnails (`PhotoFlights`). A hold on + shows the newest photos
// over the composer (`RecentPhotoPeek`).

/**
 * iOS `SessionView`'s New Plus Menu `@State`s (`attachmentMenu`, `attachmentMenuRestoresFocus`,
 * `photoSheet`, `photoSheetDetent`, `photoSheetSource`, `photoSheetLandsOn`,
 * `photoSheetFoldsToComposer`, `photoSheetKeepsMenu`, `photoSheetRestoresFocus`,
 * `photoSheetRestingComposer`, `plusPhotos`, `photoFlights`, `plusCamera`, `recentPeek`) and the
 * extension's functions. A plain object owned by `SessionView`; [env] is refreshed every
 * composition with what the functions read from the screen.
 */
@Stable
class SessionPhotoSheetState internal constructor(private val scope: CoroutineScope) {
    /** The + menu's phase (iOS `attachmentMenu`). */
    var attachmentMenu by mutableStateOf(AttachmentMenuPhase.Closed)
    /** The keyboard comes back as the menu closes (iOS `attachmentMenuRestoresFocus`). */
    var attachmentMenuRestoresFocus by mutableStateOf(false)

    val morph = ComposerMorph(scope)
    var detent by mutableStateOf(PlusPhotoSheetDetent())
        private set
    /** Where the sheet's top rests (dp): [detent]'s top, sprung from where a release left it. */
    val top = Animatable(0f)
    var source by mutableStateOf(Rect.Zero)
        private set
    var landsOn by mutableStateOf<Rect?>(null)
        private set
    var foldsToComposer by mutableStateOf(false)
        private set
    /** Back from the sheet hands the keyboard back under the menu (iOS `photoSheetKeepsMenu`). */
    var keepsMenu = false
    /**
     * iOS `photoSheetMenuTakesBack`: the menu takes its glass back as the back fold lands
     * ([handMenuBackOnLanding]), not at the morph's longer generic settle.
     */
    var menuTakesBack by mutableStateOf(false)
        private set
    /** iOS `photoSheetRestoresFocus`: the keyboard comes back as the sheet folds onto the composer. */
    var restoresFocus by mutableStateOf(false)
        private set
    private var restingComposer: Rect? = null

    val library = PlusPhotoLibrary()
    val flights = PhotoFlights()
    val camera = PlusCameraPresentation()
    val recentPeek = RecentPhotoPeek()

    /**
     * iOS `plusMenuRests`: no menu, sheet, camera or recent-photo peek up, so nothing in the
     * + menu's overlay is there to read.
     */
    val plusMenuRests: Boolean
        get() = attachmentMenu == AttachmentMenuPhase.Closed && !morph.isShown && !recentPeek.isShown && !camera.expanded

    /** iOS `PlusMenuGlassSurface(isOpen:)`: the + menu's own surface shows its rows (not handed to the photo sheet). */
    val plusMenuIsOpen: Boolean
        get() = attachmentMenu == AttachmentMenuPhase.Menu && (!morph.isShown || menuTakesBack)

    internal var env = Env()

    /** What the functions read from the screen, set by `SessionView` on every composition. */
    internal class Env(
        val store: StoreOf<SessionFeature.State, SessionFeature.Action>? = null,
        val host: SessionComposerHost? = null,
        val importer: SessionAttachmentImporter? = null,
        val reducedMotion: Boolean = false,
        val density: Float = 1f,
        /** The header's height (dp). */
        val headerHeight: Float = 0f,
        val imeBottomPx: Int = 0,
        val navigationBottomPx: Int = 0,
        /** Root px → screen px (flights and the peek are in screen px). */
        val rootOffset: Offset = Offset.Zero,
    )

    private val reducedMotion get() = env.reducedMotion
    private val ceiling: Float get() = env.headerHeight + ModelPickerTunables.headerGap.toFloat()

    /** The composer in screen px (iOS `scrollEdgeBridge.composerFrame`), or null before it's laid out. */
    val composerOnScreen: Rect?
        get() = env.host?.attachmentAnchors?.composer?.takeIf { it != Rect.Zero }?.translate(env.rootOffset)

    /**
     * iOS `composerSheetTop(composer:)` (dp): where a sheet grown from the composer rests: the
     * composer's top with the keyboard up; with it down, as high as the composer would stand over
     * the keyboard or `mediumMax` (470 dp).
     */
    private fun composerSheetTop(composer: Rect): Float {
        val d = env.density
        val keyboard = env.imeBottomPx / d
        val software = keyboard > 120
        val focused = env.host?.messageFieldFocused == true
        val composerTop = composer.top / d
        if (focused && software) return composerTop
        val minLift = max(ComposerMorphTunables.leastLift, ModelPickerTunables.mediumMax - max(composer.height / d, 56f)).toFloat()
        val bottom = env.navigationBottomPx / d
        val lift = if (software) keyboard - bottom else minLift
        val bar = if (focused) max(keyboard - bottom, 0f) else 0f
        return composerTop - max(lift - bar, 0f)
    }

    /**
     * Where the sheet rests when it opens over a raised keyboard (dp). iOS draws the sheet over the
     * keys in its keyboard-down shape (`photoSheetCoversKeys`); an Android app can't draw over the
     * keyboard, so the keyboard goes down as the sheet opens and the sheet takes that same shape:
     * the composer's top once it has dropped to the navigation bar, lifted as with no keyboard.
     */
    private fun composerSheetTopWithKeyboardDown(composer: Rect): Float {
        val d = env.density
        val drop = max(env.imeBottomPx / d - env.navigationBottomPx / d, 0f)
        val minLift = max(ComposerMorphTunables.leastLift, ModelPickerTunables.mediumMax - max(composer.height / d, 56f)).toFloat()
        return composer.top / d + drop - minLift
    }

    /** The software keyboard is up (iOS's `software` test: taller than 120 dp). */
    private val keyboardIsUp: Boolean get() = env.imeBottomPx / env.density > 120

    private fun composerAnchor(): Rect = env.host?.attachmentAnchors?.composer ?: Rect.Zero

    private fun withMeasuredDetent(): PlusPhotoSheetDetent {
        val next = detent.copy()
        next.ceiling = ceiling
        if (next.compactTop <= 0) next.compactTop = composerSheetTop(composerAnchor())
        return next
    }

    private fun springTop(to: Float, spec: FiniteAnimationSpec<Float>, initialVelocity: Float = 0f) {
        val from = top.value + morph.drag
        morph.drag = 0f
        scope.launch {
            top.snapTo(from)
            top.animateTo(to, spec, initialVelocity)
        }
    }

    private fun snapTop(to: Float) {
        scope.launch { top.snapTo(to) }
    }

    /** iOS `openPhotoSheet()`: the menu's surface grows into the sheet, resting on the keyboard when it's up. */
    fun openPhotoSheet() {
        val host = env.host ?: return
        val menu = host.attachmentAnchors.menu
        if (menu == Rect.Zero) return
        library.resetClosingPicks()
        menuTakesBack = false
        flights.cancelAll()
        val coversKeys = keyboardIsUp
        val compactTop = if (coversKeys) {
            composerSheetTopWithKeyboardDown(host.attachmentAnchors.composer)
        } else {
            composerSheetTop(host.attachmentAnchors.composer)
        }
        if (!morph.isOpening) source = menu
        detent = PlusPhotoSheetDetent().also { it.open(compactTop, ceiling) }
        snapTop(compactTop)
        foldsToComposer = false
        val hadKeyboard = host.messageFieldFocused || attachmentMenuRestoresFocus
        restoresFocus = hadKeyboard
        // Where the composer rests with the keyboard up: photos added from the sheet fly to their
        // places in it as the keyboard comes back.
        restingComposer = if (hadKeyboard) composerOnScreen else null
        // The menu itself doesn't give the keyboard back: the sheet does.
        attachmentMenuRestoresFocus = false
        val open = Motion.panelOpen.animation<Float>(reduceMotion = reducedMotion)
        morph.armThenOpen(
            open,
            contentDelay = ComposerMorphTunables.photoContentDelay,
            prepare = {},
            // The keyboard goes down under the growing sheet; closing or Back brings it back ([restoresFocus]).
            launch = { if (coversKeys) host.dismissComposerKeyboard() },
            opened = {},
        )
    }

    /** iOS `dragPhotoSheet(_:)` (dp). */
    fun dragPhotoSheet(translation: Float) {
        if (!morph.isOpen) return
        val next = withMeasuredDetent()
        morph.drag = next.dragChanged(translation)
        detent = next
    }

    /** iOS `settlePhotoSheet(predicted:velocity:)` (dp, dp/s). */
    fun settlePhotoSheet(predicted: Float?, velocity: Float) {
        if (!morph.isOpen || morph.drag == 0f) return
        val next = withMeasuredDetent()
        next.drag = morph.drag
        when (val settlement = next.settle(predicted, velocity)) {
            PlusPhotoSheetDetent.Settlement.Close -> {
                detent = next
                closePhotoSheet()
            }
            is PlusPhotoSheetDetent.Settlement.Stay -> {
                val from = top.value + morph.drag
                detent = next
                // The release spring carries the finger's speed; iOS's normalized velocity is a
                // fraction of the distance per second.
                val velocity = Motion.release.initialVelocity(settlement.initialVelocity, reducedMotion)
                springTop(settlement.top, Motion.release.animation(reduceMotion = reducedMotion), velocity * (settlement.top - from))
            }
        }
    }

    /** iOS `togglePhotoSheetExpand()`: the grabber's tap, or All Photos. */
    fun togglePhotoSheetExpand() {
        if (!morph.isOpen) return
        val next = withMeasuredDetent()
        next.toggle()
        detent = next
        val phase = if (next.isExpanded) MotionPhase.Enter else MotionPhase.Exit
        springTop(next.top, Motion.layout.animation(phase, reducedMotion))
    }

    /** iOS `backFromPhotoSheet()`: the sheet folds onto the menu, which comes back with the keyboard if it was up. */
    fun backFromPhotoSheet() {
        if (!morph.isOpening) return
        // The picks are dropped, but their badges stay until the sheet has folded away.
        library.setAsidePicks()
        val restores = restoresFocus
        restoresFocus = false
        attachmentMenuRestoresFocus = restores
        foldsToComposer = false
        camera.expanded = false
        landsOn = source.takeIf { it != Rect.Zero }
        morph.launchThenFold(
            Motion.panelClose.animation(reduceMotion = reducedMotion),
            reducedMotion,
            launch = { if (restores) raiseMessageKeyboard() },
            edge = { null },
            landed = { handMenuBackOnLanding() },
        )
    }

    /**
     * iOS `handMenuBackOnLanding()`: the menu takes its glass back as the fold lands, in that
     * frame; until then the menu stays handed off and the card sits under the returning
     * keyboard. One frame: the menu draws as the sheet goes.
     */
    private fun handMenuBackOnLanding() {
        if (foldsToComposer) return
        menuTakesBack = true
        morph.reset()
    }

    /** iOS `raiseMessageKeyboard()`: the message field takes the keyboard back with the menu still up. */
    private fun raiseMessageKeyboard() {
        val host = env.host ?: return
        keepsMenu = true
        host.focusComposer()
    }

    /**
     * iOS `closePhotoSheet(restoresFocus:)`: Add, a capture, a tap outside or dragging down dismiss
     * the sheet and the menu: it slides down and fades while the composer comes back.
     */
    fun closePhotoSheet(restoresFocus: Boolean = true) {
        if (!morph.isOpening) return
        val restores = this.restoresFocus && restoresFocus
        this.restoresFocus = false
        attachmentMenuRestoresFocus = false
        library.clearPicks()
        foldDragIntoTop()
        foldsToComposer = true
        landsOn = null
        camera.expanded = false
        attachmentMenu = AttachmentMenuPhase.Closed
        if (restores) env.host?.focusComposer()
        morph.fold(Motion.panelClose.animation(reduceMotion = reducedMotion), reduceMotion = reducedMotion)
    }

    /** iOS `foldPhotoSheet()`: dismissed downward where something else closed the menu. */
    fun foldPhotoSheet() {
        restoresFocus = false
        library.clearPicks()
        foldDragIntoTop()
        foldsToComposer = true
        landsOn = null
        camera.expanded = false
        morph.fold(Motion.panelClose.animation(reduceMotion = reducedMotion), reduceMotion = reducedMotion)
    }

    private fun foldDragIntoTop() {
        if (morph.drag > 0) {
            val next = detent.copy()
            next.top += morph.drag
            detent = next
            snapTop(next.top)
            morph.drag = 0f
        }
    }

    /** iOS `closeAttachmentMenu()`. */
    fun closeAttachmentMenu() {
        if (morph.isOpening) return closePhotoSheet()
        if (morph.isShown && !foldsToComposer) {
            morph.reset()
            landsOn = null
        }
        attachmentMenu = AttachmentMenuPhase.Closed
    }

    /** iOS `toggleAttachmentMenu()`'s New Plus Menu half: a sheet still folding goes at once. */
    fun prepareMenuOpening() {
        if (!morph.isOpening) {
            if (morph.isShown) morph.reset()
            foldsToComposer = false
        }
    }

    /** iOS `attachmentMenu`'s `onChange` to `.closed`: closed some other way, the sheet goes too. */
    fun menuClosed() {
        if (morph.isOpening) {
            foldPhotoSheet()
        } else if (morph.isShown && !foldsToComposer) {
            morph.reset()
            landsOn = null
        }
        keepsMenu = false
    }

    /** iOS `photoSheet.isShown`'s `onChange` to false: folded away, the menu's place is its own again. */
    fun sheetFolded() {
        library.clearPicks()
        library.resetClosingPicks()
        keepsMenu = false
        menuTakesBack = false
        foldsToComposer = false
        if (detent.isExpanded) detent = detent.copy(isExpanded = false)
    }

    /** iOS `resetPhotoSheet()`: gone at once (another session, the experiment switched off). */
    fun resetPhotoSheet() {
        morph.reset()
        detent = PlusPhotoSheetDetent()
        foldsToComposer = false
        camera.expanded = false
        recentPeek.hide()
        flights.cancelAll()
        restoresFocus = false
        keepsMenu = false
        library.clearPicks()
        library.resetClosingPicks()
        library.resetSavedScrollPosition()
    }

    /** iOS `showRecentPhotos()`: a hold on +: the newest photos over the composer, if readable and the menu isn't up. */
    fun showRecentPhotos(): Boolean {
        val composer = composerOnScreen ?: return false
        if (attachmentMenu != AttachmentMenuPhase.Closed || !library.canRead || library.photos.isEmpty()) return false
        recentPeek.show(library.photos.map { it.id }, above = composer)
        return recentPeek.isShown
    }

    /** iOS `captureCameraPhoto(_:)` (the sheet's camera): attaches at once, flying from the viewfinder. */
    fun captureCameraPhoto(data: ByteArray) {
        val importer = env.importer ?: return
        val batch = UUID.randomUUID()
        val attachmentID = UUID.randomUUID()
        val photoID = "camera-$attachmentID"
        val flies = !reducedMotion
        val rest = if (morph.isOpening && restoresFocus) restingComposer else null
        importer.imageImporter.cancel()
        if (flies) {
            val sourceFrame = camera.captureSourceFrame(env.density, fallback = composerOnScreen ?: Rect.Zero)
            if (sourceFrame != Rect.Zero) {
                val image = runCatching { BitmapFactory.decodeByteArray(data, 0, data.size)?.asImageBitmap() }.getOrNull()
                flights.source(
                    photoID,
                    PhotoFlights.Source(frame = sourceFrame, radius = PlusCameraTunables.captureRadius * env.density, image = image, isEphemeral = true),
                )
            }
        }
        flights.reserve(listOf(attachmentID), batch)
        closePhotoSheet()
        if (flies) flights.launch(listOf(PhotoFlights.Pick(photoID, attachmentID)), batch, restingComposer = rest)
        startImageImport(listOf(ComposerImageSource.Loader(attachmentID) { data }), batch, onAdded = env.host?.let { it::focusComposer })
    }

    /**
     * iOS `addLibraryPhotos(_:)`: photos from the library (the sheet's Add, or the peek), attached as
     * images. Each flies from its tile into its thumbnail as the sheet folds away; its attachment's
     * ID is chosen now so the flight knows it. A video attaches as its frame, as on iOS.
     */
    fun addLibraryPhotos(ids: List<String>) {
        val importer = env.importer ?: return
        val batch = UUID.randomUUID()
        val picks = ids.take(AttachmentPickerButton.MAX_PHOTO_SELECTION).map { PhotoFlights.Pick(it, UUID.randomUUID()) }
        val flies = !reducedMotion && picks.isNotEmpty()
        val rest = if (morph.isOpening && restoresFocus) restingComposer else null
        if (picks.isEmpty()) {
            if (morph.isOpening) closePhotoSheet()
            return
        }
        importer.imageImporter.cancel()
        if (morph.isOpening) closePhotoSheet()
        // Reserve the 76×76 slots now, so the composer grows with the folding sheet instead
        // of jumping once the JPEGs are encoded. A row that isn't showing yet mounts collapsed
        // and opens on the next frame (the composer's row effect calls `revealHeldRow()`).
        val state = env.store?.state
        val holdsRow = flies && state != null && state.pendingAttachments.isEmpty() &&
            state.pendingFiles.isEmpty() && flights.reservedAttachments.isEmpty()
        flights.reserve(picks.map { it.attachment }, batch, holdingRow = holdsRow)
        if (flies) flights.launch(picks, batch, restingComposer = rest)
        val library = library
        startImageImport(picks.map { pick -> ComposerImageSource.Loader(pick.attachment) { library.imageData(pick.photo) } }, batch)
    }

    /** iOS `startImageImport(_:batch:arrival:onAdded:)`'s flight bookkeeping around the screen's importer. */
    private fun startImageImport(sources: List<ComposerImageSource>, batch: UUID, onAdded: (() -> Unit)? = null) {
        val importer = env.importer ?: return
        importer.startImageImport(
            sources,
            onAdded = onAdded,
            onFailure = { failed -> if (failed != null) flights.dropReservation(failed) },
            onCancel = { flights.importFinished(batch) },
            onCompletion = {
                attachmentsChanged()
                flights.importFinished(batch)
            },
        )
    }

    /** iOS `photoFlights.attachmentsChanged(store.pendingAttachments.map(\.id))`. */
    fun attachmentsChanged() {
        val ids = env.store?.state?.pendingAttachments?.mapNotNull { runCatching { UUID.fromString(it.id) }.getOrNull() } ?: return
        flights.attachmentsChanged(ids)
    }
}

/** The screen's [SessionPhotoSheetState]. */
@Composable
fun rememberSessionPhotoSheetState(): SessionPhotoSheetState {
    val scope = rememberCoroutineScope()
    val state = remember(scope) { SessionPhotoSheetState(scope) }
    DisposableEffect(state) { onDispose { state.library.close() } }
    return state
}

/** The screen's New Plus Menu state, for the composer's + (the hold peek) and its attachment row (flight slots). */
internal val LocalSessionPhotoSheet = staticCompositionLocalOf<SessionPhotoSheetState?> { null }

/** iOS `PlusPhotoSheetContent.grabberHeight`. */
private const val GRABBER_HEIGHT = 22f

/** UIKit's normal deceleration: a throw's predicted travel is velocity × 0.499 s (SwiftUI `predictedEndTranslation`). */
private const val PREDICTION_SECONDS = 0.499f

/**
 * iOS `photoSheetSurface`: the New Plus Menu's photo sheet, grown out of the + menu's surface
 * (`ComposerMorphSurface`) over the keyboard's place: the grabber, the composer's
 * [PlusPhotoSheetContent] (the camera first, then the newest photos), and [PlusPhotoSheetFoot]
 * (Back, All Photos / Add) along its foot. Dragging the grabber, or the grid while it can't scroll,
 * moves it between its detents or dismisses it; a tap outside dismisses it. The sheet's camera
 * ([PlusCameraSurface]) grows from its tile over the screen. Mount full-screen over the session.
 */
@Composable
fun SessionPlusPhotoSheet(state: SessionPhotoSheetState, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val morph = state.morph
    val shown = morph.isShown
    val anchors = state.env.host?.attachmentAnchors
    var screen by remember { mutableStateOf(Rect.Zero) }

    LaunchedEffect(state.library) { state.library.prime() }
    LaunchedEffect(shown) { if (!shown) state.sheetFolded() }

    val px = density.density
    fun rest(screen: Rect, keyboardTop: Float? = null): Rect {
        val column = if (screen.width / px > 600) anchors?.composer?.takeIf { it != Rect.Zero } else null
        return ComposerMorphGeometry.sheet(
            top = state.top.value * px,
            column = column,
            screen = screen,
            keyboardTop = keyboardTop,
            ceiling = (state.env.headerHeight + ModelPickerTunables.headerGap.toFloat()) * px,
            density = px,
        )
    }
    // Dismissed, it slides down from where it rests (iOS `dismissesDown`); Back folds it onto the menu.
    val landsOn = if (state.foldsToComposer && screen != Rect.Zero) {
        ComposerMorphGeometry.downwardDismissSource(rest(screen), (ComposerMorphTunables.dismissDrop * px).toFloat())
    } else {
        state.landsOn
    }
    val source = if (shown && state.source != Rect.Zero) state.source else anchors?.menu?.takeIf { it != Rect.Zero } ?: state.source

    Box(modifier.fillMaxSize().onGloballyPositioned { screen = it.boundsInRoot() }) {
        ComposerMorphSurface(
            morph = morph,
            source = source,
            sourceRadiusDp = AttachmentMenuMetrics.cornerRadius.value.toDouble(),
            target = { s, keyboardTop -> rest(s, keyboardTop) },
            onDismiss = { state.closePhotoSheet() },
            modifier = Modifier.semantics { if (state.camera.expanded) invisibleToUser() },
            openedFrom = state.source.takeIf { it != Rect.Zero },
            landsOn = landsOn,
            stretchesUpward = true,
            backdropDim = ComposerMorphTunables.photoBackdropDim,
            // It rests on the keyboard when it's up, and stays on it as it folds back onto the menu
            // instead of dropping behind the keys (iOS d82670c2).
            contentHasKeyboard = true,
            keyboardStaysAsItFolds = true,
            retainsContent = true,
        ) {
            PhotoSheetBody(state)
        }
    }
    if (shown) PhotoLibraryAccessHost()
    PlusCameraSurface(presentation = state.camera, sheetIsOpen = morph.isOpen, onCapture = { data -> state.captureCameraPhoto(data) }, sheetContent = { morph.content.value })
}

/** The grabber, the grid and the foot, laid out where the sheet rests. */
@Composable
private fun PhotoSheetBody(state: SessionPhotoSheetState) {
    val density = LocalDensity.current.density
    val isExpanded = state.detent.isExpanded
    val currentExpanded by rememberUpdatedState(isExpanded)
    // iOS `sheetDragGesture`: compact, the grid doesn't scroll and every drag moves the sheet;
    // expanded, a pull down from the grid's top collapses it.
    val connection = remember(state) {
        object : NestedScrollConnection {
            var dragging = false
            var translation = 0f

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                val dy = available.y / density
                if (!currentExpanded || dragging) {
                    dragging = true
                    translation += dy
                    state.dragPhotoSheet(if (currentExpanded) max(0f, translation) else translation)
                    return available
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || !currentExpanded || available.y <= 0f) return Offset.Zero
                // At the grid's top, pulled down: the sheet follows from here.
                dragging = true
                translation += available.y / density
                state.dragPhotoSheet(max(0f, translation))
                return available
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!dragging) return Velocity.Zero
                val velocity = available.y / density
                val pull = if (currentExpanded) max(0f, translation) else translation
                dragging = false
                translation = 0f
                state.settlePhotoSheet(pull + velocity * PREDICTION_SECONDS, velocity)
                return available
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (dragging) {
                    dragging = false
                    translation = 0f
                    state.settlePhotoSheet(null, 0f)
                }
                return Velocity.Zero
            }
        }
    }
    val contentArrived by remember(state) { derivedStateOf { state.morph.content.value > 0.99f } }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            PhotoSheetGrabber(
                isExpanded = isExpanded,
                onDrag = state::dragPhotoSheet,
                onDragEnded = { predicted, velocity -> state.settlePhotoSheet(predicted, velocity) },
                onToggle = state::togglePhotoSheetExpand,
                // TalkBack's focus moves into the sheet once its content has arrived, onto its
                // first element, as it does into a system bottom sheet (it was on the menu's row).
                focusRequest = rememberAccessibilityFocusRequest(if (state.morph.isOpening && contentArrived) state.morph.generation else null),
            )
            Box(Modifier.weight(1f).fillMaxWidth().nestedScroll(connection)) {
                PlusPhotoSheetContent(
                    library = state.library,
                    flights = state.flights,
                    camera = state.camera,
                    isExpanded = isExpanded,
                    onCamera = {
                        if (state.morph.isOpen) state.camera.expanded = true
                    },
                    onDismiss = { state.closePhotoSheet() },
                )
            }
        }
        PlusPhotoSheetFoot(
            library = state.library,
            isExpanded = isExpanded,
            onBack = state::backFromPhotoSheet,
            onAllPhotos = state::togglePhotoSheetExpand,
            onAdd = { state.addLibraryPhotos(state.library.takePicks()) },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** iOS `PlusPhotoSheetContent.grabber`: a 36 × 5 capsule in a 22 dp band; drag moves the sheet, tap toggles Half/Full. */
@Composable
private fun PhotoSheetGrabber(
    isExpanded: Boolean,
    onDrag: (Float) -> Unit,
    onDragEnded: (predicted: Float, velocity: Float) -> Unit,
    onToggle: () -> Unit,
    focusRequest: AccessibilityFocusRequest? = null,
) {
    val density = LocalDensity.current.density
    val currentDrag by rememberUpdatedState(onDrag)
    val currentEnded by rememberUpdatedState(onDragEnded)
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = GRABBER_HEIGHT.dp)
            .pointerInput(Unit) {
                var translation = 0f
                val tracker = VelocityTracker()
                detectVerticalDragGestures(
                    onDragStart = {
                        translation = 0f
                        tracker.resetTracking()
                    },
                    onDragEnd = {
                        val velocity = tracker.calculateVelocity().y / density
                        currentEnded(translation + velocity * PREDICTION_SECONDS, velocity)
                    },
                    onDragCancel = { currentEnded(translation, 0f) },
                ) { change, amount ->
                    tracker.addPosition(change.uptimeMillis, change.position)
                    translation += amount / density
                    currentDrag(translation)
                }
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onToggle)
            // Ahead of the clearing below, which would drop its marker.
            .accessibilityFocusRequest(focusRequest)
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
                .padding(top = 7.dp)
                .size(width = 36.dp, height = 5.dp)
                .background(OpenCodeTheme.colors.tertiaryText, OpenCodeShape.capsule),
        )
    }
}

/**
 * Keeps [state]'s screen readings current and its flights told where the composer is and what it
 * holds. Composed by `SessionView` once per screen.
 */
@Composable
internal fun SessionPhotoSheetEnvironment(
    state: SessionPhotoSheetState,
    store: StoreOf<SessionFeature.State, SessionFeature.Action>,
    host: SessionComposerHost,
    importer: SessionAttachmentImporter,
    headerHeight: Float,
    rootOffset: Offset,
) {
    val density = LocalDensity.current
    val reduceMotion = rememberReduceMotion()
    // The session's own space: a Catch Up card's edges, not the window's ([inContainer]).
    val ime = WindowInsets.ime.inContainer().getBottom(density)
    val navigation = WindowInsets.navigationBars.inContainer().getBottom(density)
    SideEffect {
        state.env = SessionPhotoSheetState.Env(
            store = store,
            host = host,
            importer = importer,
            reducedMotion = reduceMotion,
            density = density.density,
            headerHeight = headerHeight,
            imeBottomPx = ime,
            navigationBottomPx = navigation,
            rootOffset = rootOffset,
        )
    }
    // iOS `scenePhase != .active` and `onDisappear`: no flight survives an inactive or gone screen.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateAsState()
    val resumed = lifecycleState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
    LaunchedEffect(resumed) { if (!resumed) state.flights.reset() }
    DisposableEffect(state) { onDispose { state.flights.reset() } }
    // iOS `.onGeometryChange` on the composer: `photoFlights.composer(frame)`.
    val composer = host.attachmentAnchors.composer
    LaunchedEffect(composer, rootOffset) { if (composer != Rect.Zero) state.flights.composer(composer.translate(rootOffset)) }
    // iOS `onChange(of: composerAttachmentIDs)`.
    val attachmentIDs by store.observe { s -> s.pendingAttachments.map { it.id } }
    LaunchedEffect(attachmentIDs) { state.attachmentsChanged() }
}

/** The root's offset on screen (flights and the peek work in screen px; anchors in root px). */
internal fun Modifier.rootOffsetOnScreen(onOffset: (Offset) -> Unit): Modifier =
    onGloballyPositioned { onOffset(it.positionOnScreen() - it.positionInRoot()) }
