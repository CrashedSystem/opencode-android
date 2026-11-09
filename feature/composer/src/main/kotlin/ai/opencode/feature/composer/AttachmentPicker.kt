package ai.opencode.feature.composer

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionSpring
import ai.opencode.core.motion.motionPressEffect
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The `ComposerTunables` defaults the + reads (iOS `Session/ComposerViews.swift`, owned by the
 * session area): `innerHorizontal` 6, `plusOffset` 28, `plusScale` 17, `buttonDiameter` 34.
 */
private object PlusButtonComposerTunables {
    const val INNER_HORIZONTAL = 6f
    const val PLUS_OFFSET = 28f
    const val PLUS_SCALE = 17f
    const val BUTTON_DIAMETER = 34f
}

/**
 * The New Plus Menu's hold peek (iOS `RecentPhotoPeek`, `PlusMenu/`): a hold on + shows recent
 * photos instead of the menu; sliding picks one and letting go adds it. Implemented in
 * `ai.opencode.feature.composer.plusmenu`; points are root coordinates.
 */
interface AttachmentPlusPeek {
    val isShown: Boolean
    fun moved(to: Offset, button: Rect)
    /** The photo under the finger as the hold ends, if any. */
    fun ended(): String?
    fun hide()
}

/**
 * The composer's + button (iOS `AttachmentPickerButton`). A tap opens the attachment menu
 * ([ai.opencode.feature.composer.plusmenu.PlusMenuGlassSurface]), which grows up out of the button; touching down and holding
 * opens it too, and the finger can slide across the rows and release on one to choose it.
 * While the menu is open the + turns into a × that closes it. While dictating the same + turns
 * into the recording's ×, which cancels it, and a filled circle grows in behind it in the same
 * spring.
 */
object AttachmentPickerButton {
    /** The recording the × cancels while dictating. */
    data class Dictation(
        /** Sliding the held mic left has armed cancel. */
        val isCancelArmed: Boolean = false,
        /** Finishing after Stop: nothing left to cancel. */
        val isEnabled: Boolean = true,
    )

    /** The + menu and the photo sheet take up to 8 photos (iOS `maxPhotoSelection`). */
    const val MAX_PHOTO_SELECTION = 8

    /** How long a touch rests on + before the menu opens under it. */
    val holdDuration: Duration = PlusMenuTunables.HOLD_DURATION.seconds

    /** Moving this far (dp) before the hold elapses opens the menu at once. */
    internal const val DRAG_DISTANCE = 10f
}

/**
 * @param rows the rows a slide can reach, in root coordinates: none unless the menu itself is
 *   up (not while the photo card has taken its place).
 * @param popsOnClose the + menu is showing; when it collapses back into + the glyph pops
 *   ([PlusPop]).
 * @param glyphOffset moves the glyph's ink inside the target (the composer lines its + up with
 *   the placeholder's leading edge); the target stays put. Dp.
 */
@Composable
fun AttachmentPickerButton(
    isLoading: Boolean,
    isOpen: Boolean,
    anchors: AttachmentMenuAnchors,
    press: AttachmentMenuPress,
    rows: Map<AttachmentMenuItem, Rect>,
    onToggle: () -> Unit,
    onChoose: (AttachmentMenuItem) -> Unit,
    modifier: Modifier = Modifier,
    dictation: AttachmentPickerButton.Dictation? = null,
    onCancelDictation: () -> Unit = {},
    glyphOffset: Float = 0f,
    peek: AttachmentPlusPeek? = null,
    onHoldPeek: () -> Boolean = { false },
    onPeekChoose: (String) -> Unit = {},
    /** The + menu is showing; when it collapses back into + the glyph pops. */
    popsOnClose: Boolean = false,
) {
    val reduceMotion = rememberReduceMotion()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val fontScale = minOf(density.fontScale, 1.6f)
    val isDictating = dictation != null
    val showsX = isOpen || isDictating || peek?.isShown == true
    val cancelArmed = dictation?.isCancelArmed == true
    var isTouching by remember { mutableStateOf(false) }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

    val currentIsOpen by rememberUpdatedState(isOpen)
    val currentRows by rememberUpdatedState(rows)
    val currentPeek by rememberUpdatedState(peek)
    val currentToggle by rememberUpdatedState(onToggle)
    val currentChoose by rememberUpdatedState(onChoose)
    val currentHoldPeek by rememberUpdatedState(onHoldPeek)
    val currentPeekChoose by rememberUpdatedState(onPeekChoose)

    // A touch that began on + (the menu's) isn't the recording's.
    LaunchedEffect(isDictating) {
        if (isDictating && isTouching) {
            isTouching = false
            press.cancelled()
            peek?.hide()
        }
    }

    val visualOffsetX = PlusButtonComposerTunables.PLUS_OFFSET - (PlusButtonComposerTunables.INNER_HORIZONTAL + 22f)
    val rotation by animateFloatAsState(
        if (showsX) 45f else 0f,
        if (showsX) AttachmentMenuMotion.open(reduceMotion) else AttachmentMenuMotion.close(reduceMotion),
        label = "plus-rotation",
    )
    // Arming cancel swells the × in; letting go settles it back.
    val armedPhase = if (cancelArmed) MotionPhase.Enter else MotionPhase.Exit
    val armedSpec = Motion.microIn.animation<Float>(armedPhase, reduceMotion)
    val glyphScale by animateFloatAsState(if (cancelArmed) 1.1f else 1f, armedSpec, label = "plus-armed")
    val dictationSpec = if (isDictating) AttachmentMenuMotion.open<Float>(reduceMotion) else AttachmentMenuMotion.close(reduceMotion)
    val circleScale by animateFloatAsState(
        if (reduceMotion) 1f else if (isDictating) (if (cancelArmed) 1.1f else 1f) else 0.6f,
        if (cancelArmed || !isDictating) armedSpec else dictationSpec,
        label = "dictation-circle-scale",
    )
    val circleAlpha by animateFloatAsState(if (isDictating) 1f else 0f, dictationSpec, label = "dictation-circle")
    val circleColor by animateColorAsState(
        if (cancelArmed) SystemRed else MaterialTheme.colorScheme.surfaceContainerHighest,
        Motion.microIn.animation(armedPhase, reduceMotion),
        label = "dictation-circle-color",
    )
    val glyphX by animateFloatAsState(if (isDictating) 0f else glyphOffset, dictationSpec, label = "plus-glyph-x")
    val pop = remember { PlusPop() }
    var wasPopping by remember { mutableStateOf(popsOnClose) }
    LaunchedEffect(popsOnClose) {
        val was = wasPopping
        wasPopping = popsOnClose
        if (was && !popsOnClose && !currentIsOpen && !reduceMotion) pop.play()
    }
    val glyphColor = if (cancelArmed) Color.White else OpenCodeTheme.colors.text
    val label = when {
        isDictating -> "Cancel dictation"
        isLoading -> "Adding attachment"
        isOpen -> "Close"
        else -> "Add attachment"
    }
    val enabled = if (isDictating) dictation?.isEnabled != false else !isLoading

    fun cancelDictation() {
        if (dictation?.isEnabled == true) onCancelDictation()
    }

    val menuGesture = if (isLoading || isDictating) Modifier else Modifier.pointerInput(press, anchors) {
        val dragDistance = AttachmentPickerButton.DRAG_DISTANCE.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            down.consume()
            var hold: Job? = null
            fun openByHold() {
                hold?.cancel()
                hold = null
                if (!press.holdElapsed()) return
                if (currentPeek == null || !currentHoldPeek()) currentToggle()
            }
            fun toRoot(point: Offset): Offset = coordinates?.localToRoot(point) ?: point
            fun finishTouch() {
                hold?.cancel()
                hold = null
                isTouching = false
            }
            isTouching = true
            press.began(isOpen = currentIsOpen)
            if (!currentIsOpen) {
                hold = scope.launch {
                    delay(AttachmentPickerButton.holdDuration)
                    openByHold()
                }
            }
            var released: Offset? = null
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        released = change.position
                        change.consume()
                        break
                    }
                    if ((change.position - down.position).getDistance() > dragDistance) openByHold()
                    val point = toRoot(change.position)
                    val shownPeek = currentPeek
                    if (shownPeek != null && shownPeek.isShown) shownPeek.moved(point, anchors.button)
                    else press.moved(point, anchors.button, currentRows, dragSlopPx = AttachmentMenuPress.BUTTON_SLOP.dp.toPx())
                    change.consume()
                }
            } catch (cancellation: CancellationException) {
                if (isTouching) {
                    finishTouch()
                    press.cancelled()
                    currentPeek?.hide()
                }
                throw cancellation
            }
            val end = released
            if (end == null) {
                // The system took the touch (iOS's `isPressing` resets): it still finishes.
                if (isTouching) {
                    finishTouch()
                    press.cancelled()
                    currentPeek?.hide()
                }
                return@awaitEachGesture
            }
            if (!isTouching) return@awaitEachGesture
            finishTouch()
            val shownPeek = currentPeek
            if (shownPeek != null && shownPeek.isShown) {
                press.cancelled()
                shownPeek.ended()?.let(currentPeekChoose)
                return@awaitEachGesture
            }
            when (val outcome = press.ended(toRoot(end), anchors.button, currentRows, AttachmentMenuPress.BUTTON_SLOP.dp.toPx())) {
                AttachmentMenuPress.Outcome.None -> Unit
                AttachmentMenuPress.Outcome.Toggle, AttachmentMenuPress.Outcome.Close -> currentToggle()
                is AttachmentMenuPress.Outcome.Choose -> currentChoose(outcome.item)
            }
        }
    }
    val dictationTap = if (!isDictating) Modifier else Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            val up = waitForUpOrCancellationCompat()
            if (up) cancelDictation()
        }
    }

    Box(
        modifier
            .size(48.dp)
            .onGloballyPositioned {
                coordinates = it
                anchors.button = Rect(it.positionInRoot(), it.size.toSize())
            }
            .then(menuGesture)
            .then(dictationTap)
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = label
                testTag = "attachment-menu-button"
                if (!enabled) disabled()
                onClick {
                    if (isDictating) cancelDictation() else if (!isLoading) onToggle()
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .offset(x = visualOffsetX.dp)
                .motionPressEffect(isTouching && !showsX, reduceMotion),
            contentAlignment = Alignment.Center,
        ) {
            val diameter = minOf(34f * fontScale * (PlusButtonComposerTunables.BUTTON_DIAMETER / 34f), 44f)
            // Decoration under the mic, which owns the touch.
            Box(
                Modifier
                    .size(diameter.dp)
                    .graphicsLayer {
                        scaleX = circleScale
                        scaleY = circleScale
                        alpha = circleAlpha
                    }
                    .background(circleColor, CircleShape),
            )
            Box(
                Modifier
                    .offset(x = glyphX.dp)
                    .graphicsLayer {
                        scaleX = pop.scale.value
                        scaleY = pop.scale.value
                        alpha = pop.opacity.value
                    },
                contentAlignment = Alignment.Center,
            ) {
                val glyphSize = (PlusButtonComposerTunables.PLUS_SCALE * 1.3f * fontScale).dp
                if (isLoading && !isDictating) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else if (reduceMotion) {
                    // Under Reduce Motion + and × cross-fade.
                    val xAlpha by animateFloatAsState(if (showsX) 1f else 0f, Motion.easeInOut(0.2), label = "plus-fade")
                    OpenCodeIcon(SFSymbol.Plus, null, Modifier.alpha(1 - xAlpha), size = glyphSize, tint = glyphColor)
                    OpenCodeIcon(SFSymbol.Plus, null, Modifier.graphicsLayer { rotationZ = 45f }.alpha(xAlpha), size = glyphSize, tint = glyphColor)
                } else {
                    // The × is always the turned +.
                    OpenCodeIcon(
                        SFSymbol.Plus,
                        null,
                        Modifier.graphicsLayer {
                            rotationZ = rotation
                            scaleX = glyphScale
                            scaleY = glyphScale
                        },
                        size = glyphSize,
                        tint = glyphColor,
                    )
                }
            }
        }
    }
}

private val SystemRed = Color(0xFFFF3B30)

/**
 * iOS `PlusPop`: the + arriving back as the + menu's glass collapses into it: hidden under the
 * glass, then fading in from `plusPop` and springing to full size.
 */
@Stable
internal class PlusPop {
    val scale = Animatable(1f)
    val opacity = Animatable(1f)

    suspend fun play() = coroutineScope {
        // Held under the folding glass until it arrives, then in on `microIn` (its fade on
        // `swap`'s entrance).
        val delaySeconds = ComposerMorph.wallSeconds(Motion.panelClose.curve().duration * PlusMenuTunables.PLUS_POP_LEAD)
        val pop = Motion.microIn.curve()
        val settle = ComposerMorph.wallSeconds(pop.duration)
        val fade = ComposerMorph.wallSeconds(Motion.swap.curve().duration)
        scale.snapTo(PlusMenuTunables.PLUS_POP.toFloat())
        opacity.snapTo(0f)
        delay((delaySeconds * 1000).roundToLong())
        launch { scale.animateTo(1f, MotionSpring(settle, pop.bounce).spec()) }
        launch { opacity.animateTo(1f, MotionSpring(fade, 0.0).spec()) }
    }
}

/** True on an up, false on a cancel (the gesture moved away or was consumed). */
private suspend fun AwaitPointerEventScope.waitForUpOrCancellationCompat(): Boolean =
    waitForUpOrCancellation()?.also { it.consume() } != null

/** What the menu offers. Paste can only be tapped, never chosen by releasing a drag over it. */
enum class AttachmentMenuItem(val title: String, val systemImage: SFSymbol) {
    Camera("Camera", SFSymbol.Camera),
    Photos("Photos", SFSymbol.PhotoOnRectangle),
    Files("Files", SFSymbol.Folder),
    Location("Location", SFSymbol.Mappin),
    Paste("Paste", SFSymbol.DocOnClipboard);

    val isDragSelectable: Boolean get() = this != Paste
}

/**
 * A touch on + and what it does: opening by hold, sliding over rows with a highlight, and
 * releasing to choose, cancel, or leave the menu open (iOS `AttachmentMenuPress`). It plays the
 * menu's haptics, each once: a light impact as the menu opens, a selection tick as the finger
 * reaches each row, and a light impact as a release chooses one. Closing plays nothing, like
 * the system's menus. A plain object: it owns per-touch state, not the reducer.
 */
@Stable
class AttachmentMenuPress(private val haptics: HapticsClient = dependency(HapticsClient)) {
    sealed interface Outcome {
        /** Nothing changes: a release away from the rows and the button. */
        data object None : Outcome
        /** A plain tap: opens a closed menu, closes an open one. */
        data object Toggle : Outcome
        /** Released back on the × after sliding away from it. */
        data object Close : Outcome
        data class Choose(val item: AttachmentMenuItem) : Outcome
    }

    /** The row under the finger. */
    var highlighted: AttachmentMenuItem? by mutableStateOf(null)
        private set

    private data class Touch(val wasOpen: Boolean, val openedByHold: Boolean = false, val leftButton: Boolean = false)

    private var touch: Touch? = null

    /** A finger is down on +. */
    val isTouching: Boolean get() = touch != null

    /** The menu opened, by a tap, a hold, or assistive technology. */
    fun menuOpened() {
        haptics.play(Haptic.lightTap)
    }

    /** A row tapped (a slide-release ticks in [ended]). */
    fun rowTapped() {
        haptics.play(Haptic.lightTap)
    }

    fun began(isOpen: Boolean) {
        touch = Touch(wasOpen = isOpen)
        highlighted = null
    }

    /** The hold elapsed, or the finger moved before it did. Whether the menu should open now: once per touch, and only if it was closed. */
    fun holdElapsed(): Boolean {
        val current = touch ?: return false
        if (current.wasOpen || current.openedByHold) return false
        touch = current.copy(openedByHold = true)
        return true
    }

    /** [dragSlopPx]: [BUTTON_SLOP] in pixels. */
    fun moved(to: Offset, button: Rect, rows: Map<AttachmentMenuItem, Rect>, dragSlopPx: Float) {
        val current = touch ?: return
        if (!(current.wasOpen || current.openedByHold)) return
        if (!button.inflate(dragSlopPx).contains(to)) touch = current.copy(leftButton = true)
        val item = item(to, rows)
        if (item == highlighted) return
        highlighted = item
        if (item != null) haptics.play(Haptic.selection)
    }

    fun ended(at: Offset, button: Rect, rows: Map<AttachmentMenuItem, Rect>, dragSlopPx: Float): Outcome {
        val current = touch
        touch = null
        highlighted = null
        current ?: return Outcome.None
        val isMenuUp = current.wasOpen || current.openedByHold
        if (isMenuUp && current.leftButton) {
            val item = item(at, rows)
            if (item != null) {
                haptics.play(Haptic.lightTap)
                return Outcome.Choose(item)
            }
        }
        if (!button.inflate(dragSlopPx).contains(at)) return Outcome.None
        if (!current.openedByHold) return Outcome.Toggle
        // Held open and released in place: the menu stays for a tap.
        return if (current.leftButton) Outcome.Close else Outcome.None
    }

    fun cancelled() {
        touch = null
        highlighted = null
    }

    companion object {
        /** Slop (dp) around the button before the finger counts as having left it. */
        const val BUTTON_SLOP = 8f

        fun item(at: Offset, rows: Map<AttachmentMenuItem, Rect>): AttachmentMenuItem? =
            rows.entries.firstOrNull { it.key.isDragSelectable && it.value.contains(at) }?.key
    }
}

/**
 * Frames the attachment menu lays itself out against, in root coordinates (iOS
 * `AttachmentMenuAnchors`, in its named coordinate space). Measured after layout, so the
 * overlay never depends on the composer's layout mid-update.
 */
@Stable
class AttachmentMenuAnchors {
    /** The + button the menu grows from and shrinks back into. */
    var button: Rect by mutableStateOf(Rect.Zero)
    /** The composer surface the menu and the photo card stand above. */
    var composer: Rect by mutableStateOf(Rect.Zero)
    /** Each row of the menu where it rests open, for choosing by sliding a finger from +. */
    var rows: Map<AttachmentMenuItem, Rect> by mutableStateOf(emptyMap())
    /** The seed centered above + that the menu grows out of. */
    var seed: Rect by mutableStateOf(Rect.Zero)
    /** The menu's surface where it rests open: the photo sheet grows out of it. */
    var menu: Rect by mutableStateOf(Rect.Zero)
    /**
     * Horizontal offset (px) of the + glyph's center from the menu's leading edge; invariant
     * across vertical keyboard travel so the photo sheet morph never invalidates on every
     * keyboard frame. iOS default 26 pt.
     */
    var buttonCenterXInMenu: Float by mutableFloatStateOf(0f)
}

/** Records this element's frame (root coordinates) into [anchors] (iOS `.attachmentMenuAnchor(_:in:)`). */
@OptIn(ExperimentalLayoutApi::class)
fun Modifier.attachmentMenuAnchor(set: (Rect) -> Unit): Modifier = composed {
    val hold = LocalAttachmentMenuAnchorHold.current
    val keyboardHeight = WindowInsets.imeAnimationTarget.getBottom(LocalDensity.current).toFloat()
    val last = remember { arrayOfNulls<AttachmentMenuAnchorSample>(1) }
    onGloballyPositioned {
        val viewport = it.findRootCoordinates().size
        val sample = AttachmentMenuAnchorSample(
            hold = hold,
            viewportWidth = viewport.width.toFloat(),
            viewportHeight = viewport.height.toFloat(),
            keyboardHeight = keyboardHeight,
            frame = Rect(it.positionInRoot(), it.size.toSize()),
        )
        val old = last[0]
        last[0] = sample
        // iOS `onGeometryChange` acts only when the sample changes.
        if (old == null || (!old.isEquivalent(sample) && AttachmentMenuAnchorSample.writes(old, sample))) set(sample.frame)
    }
}

/**
 * How the composer's anchors follow it while the photo sheet is over it. Followed, the sink moved
 * them every frame and redrew everything that lays out against them, which cost the sheet's grow
 * and fold frames.
 */
enum class AttachmentMenuAnchorHold {
    /** Frame by frame. */
    Follows,

    /**
     * The sheet is over them: they keep where they rest, refreshed only when the viewport changes
     * (a rotation, a resized window, the keys' height after either), so Add and Back fold toward
     * where the composer and + are now.
     */
    Resting,

    /** Folding back onto them: they hold still whatever happens. */
    Folding,
}

/** One reading of an anchor (iOS `AttachmentMenuAnchorSample`). */
data class AttachmentMenuAnchorSample(
    val hold: AttachmentMenuAnchorHold,
    val viewportWidth: Float,
    /** A window resized only in height (split screen, a freeform window) moves them too. */
    val viewportHeight: Float = 0f,
    /** The docked keys' height: it changes with a rotation, never as they travel. */
    val keyboardHeight: Float = 0f,
    val frame: Rect,
) {
    private val viewport: List<Float> get() = listOf(viewportWidth, viewportHeight, keyboardHeight)

    /** iOS `==`: equal when nothing about it is worth writing. */
    fun isEquivalent(other: AttachmentMenuAnchorSample): Boolean {
        if (hold != other.hold) return false
        return when (hold) {
            AttachmentMenuAnchorHold.Follows -> frame == other.frame
            AttachmentMenuAnchorHold.Resting -> viewport == other.viewport
            AttachmentMenuAnchorHold.Folding -> true
        }
    }

    companion object {
        /**
         * Whether moving from [old] to [new] writes `new.frame`: always when following, on a
         * viewport change while resting, never while folding or as a hold begins.
         */
        fun writes(old: AttachmentMenuAnchorSample, new: AttachmentMenuAnchorSample): Boolean = when (new.hold) {
            AttachmentMenuAnchorHold.Follows -> true
            AttachmentMenuAnchorHold.Resting -> old.hold == AttachmentMenuAnchorHold.Resting && old.viewport != new.viewport
            AttachmentMenuAnchorHold.Folding -> false
        }
    }
}

/**
 * iOS `EnvironmentValues.attachmentMenuAnchorHold`: the composer is sinking under the photo
 * sheet, resting under it or rising back from it. The host provides
 * [ComposerMorph.attachmentMenuAnchorHold] around the composer.
 */
val LocalAttachmentMenuAnchorHold = compositionLocalOf { AttachmentMenuAnchorHold.Follows }
