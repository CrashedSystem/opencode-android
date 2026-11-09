package ai.opencode.feature.composer.plusmenu

import ai.opencode.core.log.AppLog
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.touch.TouchRecovery
import ai.opencode.core.touch.rememberTouchRecovery
import ai.opencode.feature.composer.AttachmentMenuAnchors
import ai.opencode.feature.composer.AttachmentMenuItem
import ai.opencode.feature.composer.AttachmentMenuMetrics
import ai.opencode.feature.composer.AttachmentMenuMotion
import ai.opencode.feature.composer.AttachmentMenuMeasure
import ai.opencode.feature.composer.AttachmentMenuRows
import ai.opencode.feature.composer.PlusMenuTunables
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The New Plus Menu's own surface (iOS `PlusMenuGlassSurface`): the + menu grows out of the +
 * itself over the composer, and collapses back into it, shrinking on into the + glyph's centre
 * and leaving there under the + as it pops back (`AttachmentPickerButton(popsOnClose = true)`).
 * Liquid Glass maps to a tonal Material surface (AGENTS.md).
 *
 * iOS draws it in its own window, or on the keyboard's window so it overlaps the keys. Android's
 * IME is another process's window that no app surface can draw over, so the menu always stands
 * above the keys ([PlusMenuPlacement.visibleMenu] with `coversKeyboard = false`, iOS's own
 * fallback when its guarded keyboard surface is unavailable); `KeyboardBandSurface` is n/a.
 *
 * [AttachmentMenuMeasure] measures the rows and publishes
 * [anchors]; this draws them. Put it in a full-screen box over the session.
 *
 * @param handsOff the photo sheet took the glass: the menu leaves at once, and the next open
 *   (Back from the sheet) starts already open rather than growing from the + again.
 */
@Composable
fun PlusMenuGlassSurface(
    anchors: AttachmentMenuAnchors,
    isOpen: Boolean,
    items: List<AttachmentMenuItem>,
    highlighted: AttachmentMenuItem?,
    onChoose: (AttachmentMenuItem) -> Unit,
    onPaste: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    handsOff: Boolean = false,
) {
    val reduceMotion = rememberReduceMotion()
    val density = LocalDensity.current
    var origin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var size by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    var rootHeight by remember { mutableFloatStateOf(0f) }
    val imeBottom = WindowInsets.ime.getBottom(density)

    /** The host is up: drawn while open and until the close has retired it. */
    var hosted by remember { mutableStateOf(false) }
    var handedOff by remember { mutableStateOf(false) }
    var resumes by remember { mutableStateOf(false) }

    // iOS `PlusMenuWindowBridge.Coordinator.update(from:)`: the host's lifetime.
    if (handsOff) {
        if (hosted || !handedOff) {
            handedOff = true
            hosted = false
            resumes = false
        }
    } else {
        if (!isOpen && !hosted && handedOff) handedOff = false
        if (isOpen && !hosted) {
            hosted = true
            // Back from the photo sheet: take over its fold already open.
            resumes = handedOff
            handedOff = false
        }
    }

    // iOS `update(from:)`: touch ownership follows intent, even during a zero-sized layout. A
    // close that loses its geometry can't run its collapse to retire, so it retires now.
    val hasGeometry = anchors.menu.width > 0f && size.width > 0f
    LaunchedEffect(hasGeometry, isOpen, hosted) {
        if (!hasGeometry && !isOpen && hosted) {
            AppLog.app.error("transcript_touch_recovered owner=plus_menu reason=closed_without_geometry")
            hosted = false
        }
    }

    // iOS `plusMenuTouchRecovery`: a closed menu still hosted after its collapse (one second,
    // well past the close) retires.
    val host = remember { PlusMenuHost() }
    rememberTouchRecovery(
        owner = TouchRecovery.Owner.PlusMenu,
        inspect = {
            if (hosted && !isOpen && !handsOff) TouchRecovery.Fault(TouchRecovery.Reason.Closed, host) else null
        },
        release = { hosted = false },
    )

    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned {
                origin = it.positionInRoot()
                size = androidx.compose.ui.geometry.Size(it.size.width.toFloat(), it.size.height.toFloat())
                rootHeight = it.findRootCoordinates().size.height.toFloat()
            }
            .clearAndSetSemantics {},
    ) {
        if (!hosted || !hasGeometry) return@Box
        val keyboardTop = if (imeBottom > 0) (rootHeight - imeBottom - origin.y).takeIf { it < size.height } else null
        val proposed = anchors.menu.translate(-origin)
        val menu = with(density) {
            PlusMenuPlacement.visibleMenu(
                proposed,
                keyboardTop = keyboardTop,
                coversKeyboard = false,
                gap = AttachmentMenuMetrics.composerGap.toPx(),
            )
        }
        PlusMenuWindowContent(
            menu = menu,
            seed = anchors.seed.translate(-origin),
            isOpen = isOpen,
            resumes = resumes,
            items = items,
            buttonCenterX = anchors.buttonCenterXInMenu,
            highlighted = highlighted,
            reduceMotion = reduceMotion,
            onChoose = onChoose,
            onPaste = onPaste,
            onDismiss = onDismiss,
            onRetired = { if (!isOpen) hosted = false },
        )
    }
    // iOS `accessibilityAction(.escape)`: system Back closes it.
    BackHandler(enabled = isOpen && !handsOff) { onDismiss() }
}

@Composable
private fun PlusMenuWindowContent(
    menu: Rect,
    seed: Rect,
    isOpen: Boolean,
    resumes: Boolean,
    items: List<AttachmentMenuItem>,
    buttonCenterX: Float,
    highlighted: AttachmentMenuItem?,
    reduceMotion: Boolean,
    onChoose: (AttachmentMenuItem) -> Unit,
    onPaste: () -> Unit,
    onDismiss: () -> Unit,
    onRetired: () -> Unit,
) {
    val density = LocalDensity.current
    // `expanded` on iOS, animated: 0 at the shrunk + disc, 1 at the menu.
    val expanded = remember { Animatable(if (resumes && isOpen) 1f else 0f) }
    /** The glass's opacity: Liquid Glass's materialize transition, a fade here. */
    val glass = remember { Animatable(if (resumes && isOpen) 1f else 0f) }
    var generation by remember { mutableIntStateOf(0) }

    LaunchedEffect(isOpen) {
        generation += 1
        val closing = generation
        if (isOpen) {
            launch { glass.animateTo(1f, AttachmentMenuMotion.open(reduceMotion)) }
            expanded.animateTo(1f, AttachmentMenuMotion.open(reduceMotion))
            return@LaunchedEffect
        }
        if (reduceMotion) {
            launch { glass.animateTo(0f, AttachmentMenuMotion.close(true)) }
            expanded.animateTo(0f, AttachmentMenuMotion.close(true))
            if (closing == generation) onRetired()
            return@LaunchedEffect
        }
        // The fold is logically done once the disc reaches the + (a spring's tail would park it
        // there for a beat); then the glass leaves on `microOut`.
        expanded.animateTo(0f, Motion.panelClose.animation(visibilityThreshold = 0.01f))
        if (closing != generation) return@LaunchedEffect
        glass.animateTo(0f, Motion.microOut.animation(reduceMotion = false))
        if (closing == generation) onRetired()
    }

    val end = PlusMenuWindowContent.shrunk(seed, PlusMenuTunables.COLLAPSE_END)
    val p = if (reduceMotion) 1f else expanded.value
    val rect = Rect(
        lerp(end.left, menu.left, p), lerp(end.top, menu.top, p),
        lerp(end.right, menu.right, p), lerp(end.bottom, menu.bottom, p),
    )
    val menuRadius = with(density) { PlusMenuTunables.MENU_RADIUS.dp.toPx() }
    val radius = lerp(end.height / 2, menuRadius, p)
    val shape = RoundedCornerShape(with(density) { radius.toDp() })
    val interactive = isOpen && (!reduceMotion || expanded.value > 0.99f)

    Box(Modifier.fillMaxSize()) {
        // The dismissing tap.
        if (isOpen) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            )
        }
        var dragged by remember { mutableFloatStateOf(0f) }
        Surface(
            modifier = Modifier
                .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
                .requiredSize(with(density) { max(rect.width, 0f).toDp() }, with(density) { max(rect.height, 0f).toDp() })
                .graphicsLayer { alpha = glass.value }
                .pointerInput(isOpen) {
                    if (!isOpen) return@pointerInput
                    val tracker = VelocityTracker()
                    detectVerticalDragGestures(
                        onDragStart = { dragged = 0f; tracker.resetTracking() },
                        onVerticalDrag = { change, amount ->
                            dragged += amount
                            tracker.addPosition(change.uptimeMillis, change.position)
                        },
                        onDragEnd = {
                            val translation = dragged / density.density
                            val velocity = tracker.calculateVelocity().y / density.density
                            // iOS `predictedEndTranslation`: about a fifth of a second of the throw.
                            if (PlusMenuPlacement.dragDismisses(translation, translation + velocity * 0.2f)) onDismiss()
                        },
                    )
                }
                .semantics {
                    isTraversalGroup = true
                    testTag = "attachment-menu"
                    traversalIndex = -1f
                },
            shape = shape,
            // A raised M3 menu container that stands off the dark canvas; the shadow is the
            // menu's own elevation (level 2).
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = MenuDefaults.ShadowElevation,
        ) {
            Box(contentAlignment = Alignment.TopStart) {
                // Carry the rows with the growing surface: cropping full-size rows with the frame
                // wiped a whole column away in one frame.
                PlusMenuRowsHost(
                    items = items,
                    buttonCenterX = with(density) { buttonCenterX.toDp() },
                    highlighted = highlighted,
                    isInteractive = interactive,
                    entranceProgress = expanded.value,
                    menuWidth = with(density) { menu.width.toDp() },
                    reduceMotion = reduceMotion,
                    onChoose = onChoose,
                    onPaste = onPaste,
                    modifier = Modifier
                        .requiredSize(with(density) { menu.width.toDp() }, with(density) { menu.height.toDp() })
                        .graphicsLayer {
                            transformOrigin = TransformOrigin(0f, 0f)
                            scaleX = rect.width / max(menu.width, 1f)
                            scaleY = rect.height / max(menu.height, 1f)
                        },
                )
            }
        }
    }
}

/** What a stranded Plus menu's touch recovery names in the log (iOS `PlusMenuHostingController`). */
private class PlusMenuHost

private object PlusMenuWindowContent {
    /** The + frame shrunk about its centre to [fraction] of its size. */
    fun shrunk(seed: Rect, fraction: Float): Rect {
        val dx = seed.width * (1 - fraction) / 2
        val dy = seed.height * (1 - fraction) / 2
        return Rect(seed.left + dx, seed.top + dy, seed.right - dx, seed.bottom - dy)
    }
}

/**
 * The single `AttachmentMenuRows` instance the + menu draws (iOS `PlusMenuRowsHost`, declared
 * in `PlusPhotoSheet.swift`): interactive while open, its rows staggering in out of the + and
 * back into it. Paste is a plain row on Android, so iOS's paste-control warm-up is n/a.
 */
@Composable
fun PlusMenuRowsHost(
    items: List<AttachmentMenuItem>,
    buttonCenterX: androidx.compose.ui.unit.Dp,
    highlighted: AttachmentMenuItem?,
    isInteractive: Boolean,
    entranceProgress: Float,
    onChoose: (AttachmentMenuItem) -> Unit,
    onPaste: () -> Unit,
    modifier: Modifier = Modifier,
    isDragging: Boolean = false,
    menuWidth: androidx.compose.ui.unit.Dp? = null,
    reduceMotion: Boolean = rememberReduceMotion(),
) {
    AttachmentMenuRows(
        items = items,
        buttonCenterX = buttonCenterX,
        modifier = modifier,
        highlighted = if (isInteractive) highlighted else null,
        staggeredEntrance = true,
        entranceProgress = entranceProgress,
        entranceBlur = if (reduceMotion) 0f else PlusMenuTunables.MENU_BLUR,
        entranceScale = if (reduceMotion) 1f else PlusMenuTunables.MENU_SCALE,
        largeMenu = true,
        menuWidth = menuWidth,
        onChoose = { item -> if (isInteractive && !isDragging) onChoose(item) },
        onPaste = { if (isInteractive && !isDragging) onPaste() },
    )
}

/** iOS `PlusMenuPlacement`: where the New Plus Menu stands (all in one space, px or dp alike). */
object PlusMenuPlacement {
    /** Public fallback when the guarded keyboard surface is unavailable or disabled: the menu rises clear of the keys. */
    fun visibleMenu(menu: Rect, keyboardTop: Float?, coversKeyboard: Boolean, gap: Float = AttachmentMenuMetrics.composerGap.value): Rect {
        if (coversKeyboard || keyboardTop == null) return menu
        return menu.translate(0f, min(0f, keyboardTop - gap - menu.bottom))
    }

    fun foot(composer: Rect, screenBottom: Float, keyboardTop: Float?, drop: Float, floor: Float = AttachmentMenuMetrics.overComposerFloor.value): Float =
        min(composer.bottom + drop, screenBottom - floor)

    /** A drag on the menu (dp) past either threshold puts it away. */
    fun dragDismisses(translation: Float, predicted: Float): Boolean = translation > 50 || predicted > 160
}
