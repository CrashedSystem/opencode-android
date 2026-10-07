@file:PortedFrom("OpenCode/SessionDrawerContainer.swift", "ae6c3d3")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.SystemColors
import ai.opencode.mobilecode.system.rememberHaptics
import ai.opencode.mobilecode.tca.Binding
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/// Owns every per-frame drawer value outside SwiftUI so dragging does not
/// invalidate `HomeView` or rebuild its session hierarchy.
/// UIKit transforms the already-rendered card directly and notifies SwiftUI
/// only when the drawer settles.
///
/// During catch-up the same card, with the same hosted `SessionView`, insets
/// into a swipeable stack above the catch-up chrome.
///
/// Compose reads the per-frame values in layer and layout blocks, so a drag
/// redraws the card without recomposing anything.
@Composable
fun SessionDrawerContainer(
    isPresented: Binding<Boolean>,
    width: Dp,
    reducedMotion: Boolean,
    content: @Composable () -> Unit,
    drawer: @Composable () -> Unit,
    chrome: @Composable () -> Unit,
    /// The card behind the open one: the given session, or nothing.
    preview: @Composable (String?) -> Unit,
    catchUp: CatchUpStage?,
    catchUpProxy: CatchUpStageProxy,
    modifier: Modifier = Modifier,
    /// The drawer came to rest open (`true`) or shut, away from where it last rested.
    onSettled: (Boolean) -> Unit = {},
    controller: SessionDrawerViewController = remember { SessionDrawerViewController(isPresented = isPresented.wrappedValue) },
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val haptics = rememberHaptics()
    val dark = isSystemInDarkTheme()
    val highContrast = LocalIncreasedContrast.current
    val separator = SystemColors.separator
    val cardLayer = rememberGraphicsLayer()
    val widthPx = with(density) { width.toPx() }
    // Read here, not only in the effect, so a change recomposes and reaches it.
    val presented = isPresented.wrappedValue
    controller.attach(
        scope = scope,
        density = density.density,
        isDark = dark,
        endEditing = { focusManager.clearFocus() },
        impact = { haptics.impact() },
        captureCard = { if (cardLayer.size == IntSize.Zero) null else cardLayer.toImageBitmap() },
        onPresentationChanged = { presented -> if (isPresented.wrappedValue != presented) isPresented.wrappedValue = presented },
        onSettled = onSettled,
    )
    catchUpProxy.dismiss = { controller.dismissCard(it) }
    catchUpProxy.undo = { controller.undoCard() }
    SideEffect {
        controller.update(width = widthPx, reducedMotion = reducedMotion)
        controller.updateCatchUp(catchUp, animated = controller.isLoaded && !reducedMotion)
        // SwiftUI animates when the change's transaction does; Home animates
        // every change, and Reduce Motion settles at once in the controller.
        controller.setPresented(presented, animated = controller.isLoaded)
        controller.isLoaded = true
    }
    BackHandler(enabled = controller.handlesEscape) { controller.accessibilityPerformEscape() }

    val insets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val layoutDirection = LocalLayoutDirection.current
    val catchingUp = controller.catchUp != null
    val stack = controller.stack
    CompositionLocalProvider(LocalDirectionalPanGestureRecognizer provides controller.panRecognizer) {
        Box(modifier.fillMaxSize().directionalPan(controller)) {
            // The drawer: leading, full height, hidden until the card has moved.
            Box(
                Modifier
                    .width(width)
                    .fillMaxHeight()
                    .graphicsLayer { alpha = if (controller.isDrawerAvailable) 1f else 0f }
                    .then(if (controller.isDrawerAvailable) Modifier else Modifier.blockingTouches())
                    .then(if (controller.isDrawerAccessible) Modifier else Modifier.clearAndSetSemantics {})
                    .drawerTouchRegion(DrawerTouchRegion.drawer),
            ) {
                drawer()
            }

            // The catch-up chrome, behind the cards.
            if (!controller.isChromeHidden) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = controller.chromeAlpha }
                        .background(SystemColors.secondarySystemBackground),
                ) {
                    chrome()
                }
            }

            // The card behind the open one.
            if (catchingUp && !stack.previewHidden) {
                Box(
                    Modifier
                        .stageCard(controller, insets, layoutDirection, inset = true)
                        .graphicsLayer {
                            alpha = stack.previewAlpha
                            scaleX = stack.previewScale
                            scaleY = stack.previewScale
                            translationY = stack.previewLift
                            shape = CardShapes.radius(CatchUpMetrics.cornerRadius.value)
                            clip = true
                        }
                        .cardBorder(separator, enabled = { true }, radius = { CatchUpMetrics.cornerRadius.value })
                        .background(SystemColors.systemBackground)
                        .consumeWindowInsets(insets)
                        .blockingTouches()
                        .clearAndSetSemantics {},
                ) {
                    preview(stack.previewID)
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = stack.previewDimAlpha }.background(Color.Black))
                }
            }

            stack.flight?.takeIf { !it.aboveCard }?.let { CardFlight(it, controller, insets, layoutDirection) }

            // The card: the session screen, full screen, or inset while catching up.
            Box(
                Modifier
                    .stageCard(controller, insets, layoutDirection, inset = false)
                    .graphicsLayer {
                        translationX = controller.offset + stack.cardTranslationX
                        translationY = stack.cardTranslationY
                        rotationZ = stack.cardRotation
                        scaleX = stack.cardScale
                        scaleY = stack.cardScale
                        alpha = stack.cardAlpha
                        shape = CardShapes.radius(controller.cornerRadius)
                        clip = true
                    }
                    .cardBorder(separator, enabled = { controller.catchUp != null }, radius = { controller.cornerRadius })
                    .drawerTouchRegion(DrawerTouchRegion.card)
                    .semantics { testTag = "session-drawer-card" },
            ) {
                // Everything in the card, recorded so a flight can fly it away.
                Box(
                    Modifier
                        .fillMaxSize()
                        .drawWithContent {
                            cardLayer.record { this@drawWithContent.drawContent() }
                            drawLayer(cardLayer)
                        }
                        .background(SystemColors.systemBackground),
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .then(if (catchingUp) Modifier.consumeWindowInsets(insets) else Modifier)
                            .graphicsLayer { alpha = controller.contentFade }
                            .then(if (controller.isContentAccessible) Modifier else Modifier.clearAndSetSemantics {})
                            .then(if (controller.isCardInteractive) Modifier else Modifier.blockingTouches()),
                    ) {
                        content()
                    }
                    if (catchingUp) {
                        CatchUpSwipeOverlay(
                            title = "Mark as\nRead",
                            symbol = "checkmark.circle.fill",
                            tint = CatchUpMetrics.readColor,
                            leading = true,
                            alpha = { stack.readBadgeAlpha },
                        )
                        CatchUpSwipeOverlay(
                            title = "Keep\nUnread",
                            symbol = "envelope.circle.fill",
                            tint = CatchUpMetrics.keepColor,
                            leading = false,
                            alpha = { stack.keepBadgeAlpha },
                        )
                    }
                    // Mutes the card while the drawer is open; a tap on it closes the drawer.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = controller.scrimAlpha }
                            .background(DrawerScrim.color(dark = dark, highContrast = highContrast))
                            .then(
                                if (controller.isScrimInteractive) {
                                    Modifier
                                        .semantics {
                                            contentDescription = "Close session list"
                                            role = Role.Button
                                            onClick {
                                                controller.closeDrawer()
                                                true
                                            }
                                        }
                                        .closeTap(controller)
                                } else {
                                    Modifier.clearAndSetSemantics {}
                                },
                            ),
                    )
                }
            }

            stack.flight?.takeIf { it.aboveCard }?.let { CardFlight(it, controller, insets, layoutDirection) }
        }
    }
}

/// A snapshot of a card flying away or back, drawn where the card sits.
@Composable
private fun CardFlight(
    flight: CatchUpStackController.Flight,
    controller: SessionDrawerViewController,
    insets: WindowInsets,
    layoutDirection: LayoutDirection,
) {
    val image = flight.image ?: return
    Box(
        Modifier
            .stageCard(controller, insets, layoutDirection, inset = true)
            .graphicsLayer {
                translationX = flight.translationX
                translationY = flight.translationY
                rotationZ = flight.rotation
                scaleX = flight.scale
                scaleY = flight.scale
                alpha = flight.alpha
                shadowElevation = if (flight.hasShadow) 24.dp.toPx() else 0f
                ambientShadowColor = Color.Black.copy(alpha = 0.16f)
                spotShadowColor = Color.Black.copy(alpha = 0.16f)
                shape = CardShapes.radius(CatchUpMetrics.cornerRadius.value)
                clip = true
            }
            .clearAndSetSemantics {},
    ) {
        Image(image, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = flight.dimAlpha }.background(Color.Black))
    }
}

/// Lays a card out full screen or at the catch-up stage's inset. The live
/// card follows the morph; [inset] cards (the preview, flights) always sit
/// at the stage.
private fun Modifier.stageCard(
    controller: SessionDrawerViewController,
    insets: WindowInsets,
    layoutDirection: LayoutDirection,
    inset: Boolean,
): Modifier = layout { measurable, constraints ->
    val width = constraints.maxWidth
    val height = constraints.maxHeight
    val progress = if (inset) 1f else controller.morph
    val side = CatchUpMetrics.sideInset.roundToPx()
    val top = insets.getTop(this) + CatchUpMetrics.headerHeight.roundToPx()
    val bottom = insets.getBottom(this) + CatchUpMetrics.footerHeight.roundToPx()
    val horizontal = (side * progress).roundToInt()
    val insetTop = (top * progress).roundToInt()
    val insetBottom = (bottom * progress).roundToInt()
    val placeable = measurable.measure(Constraints.fixed(max(width - 2 * horizontal, 0), max(height - insetTop - insetBottom, 0)))
    if (!inset) {
        controller.stack.containerWidth = width.toFloat()
        controller.stack.cardHeight = max(height - top - bottom, 1).toFloat()
    }
    layout(width, height) { placeable.place(horizontal, insetTop) }
}

/// The inset card's one-pixel `separator` edge, drawn inside its bounds as
/// a layer border is, reading the radius at draw time.
private fun Modifier.cardBorder(color: Color, enabled: () -> Boolean, radius: () -> Float): Modifier = drawWithContent {
    drawContent()
    if (!enabled()) return@drawWithContent
    val stroke = 1f
    val corner = max(radius().dp.toPx() - stroke / 2, 0f)
    drawRoundRect(
        color = color,
        topLeft = Offset(stroke / 2, stroke / 2),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(corner, corner),
        style = Stroke(stroke),
    )
}

/// Swallows every touch, as UIKit's disabled interaction does.
private fun Modifier.blockingTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
    }
}

/// The scrim's tap. It waits for the drawer pan, which consumes a horizontal
/// drag, and fails once the finger travels, as `UITapGestureRecognizer` does.
private fun Modifier.closeTap(controller: SessionDrawerViewController): Modifier = pointerInput(controller) {
    awaitEachGesture {
        val down = awaitFirstDown()
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (change.isConsumed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) return@awaitEachGesture
            if (!change.pressed) {
                change.consume()
                controller.closeDrawer()
                return@awaitEachGesture
            }
        }
    }
}

/// Card corners, cached per point so a drag allocates no shape per frame.
private object CardShapes {
    private val shapes = Array(45) { RoundedCornerShape(it.dp) }

    fun radius(points: Float): RoundedCornerShape = shapes[points.roundToInt().coerceIn(0, shapes.lastIndex)]
}

@Stable
class SessionDrawerViewController(
    isPresented: Boolean,
    /// Captures the live card for the catch-up flights. Off where nothing
    /// renders (unit tests), so the flights continue without a snapshot.
    private val capturesCards: Boolean = true,
) {
    var onPresentationChanged: (Boolean) -> Unit = {}

    /// Fires once each time the drawer comes to rest on the other side. A drag
    /// that falls back, or a settle interrupted by a reversal, never arrives.
    var onSettled: (Boolean) -> Unit = {}
    private var endEditing: () -> Unit = {}
    private lateinit var scope: CoroutineScope
    internal var isLoaded = false

    /// Pixels per point: drags arrive in pixels, the thresholds are in points.
    var density = 1f
        private set
    val panRecognizer = DirectionalPanGestureRecognizer()
    val stack = CatchUpStackController()

    var catchUp by mutableStateOf<CatchUpStage?>(null)
        private set
    private var modeAnimator: Job? = null
    private var finishMode: (() -> Unit)? = null
    var drawerWidth by mutableFloatStateOf(0f)
        private set
    private var reducedMotion = false
    var isPresented by mutableStateOf(isPresented)
        private set
    private var restsOpen = isPresented
    private var dragStartOffset = 0f
    private var isDragging = false
    private var animator: Job? = null

    /// The card's horizontal offset, in pixels.
    var offset by mutableFloatStateOf(0f)
        private set

    /// The card's corner radius, in points.
    var cornerRadius by mutableFloatStateOf(0f)
        private set
    var scrimAlpha by mutableFloatStateOf(0f)
        private set
    var isDrawerAvailable by mutableStateOf(isPresented)
        private set

    /// UIKit's single `isUserInteractionEnabled` on the hosted session, which
    /// the stack's choreography sets too.
    var isCardInteractive: Boolean
        get() = stack.isContentInteractive
        private set(value) {
            stack.isContentInteractive = value
        }

    /// 0 full screen, 1 at the catch-up stage's inset.
    var morph by mutableFloatStateOf(0f)
        private set
    var chromeAlpha by mutableFloatStateOf(0f)
        private set
    var isChromeHidden by mutableStateOf(true)
        private set

    /// The hosted session fading in as Catch Up swaps it, where UIKit fades
    /// a snapshot of the outgoing screen out.
    var contentFade by mutableFloatStateOf(1f)
        private set

    val progress: Float get() = if (drawerWidth > 0) min(max(offset / drawerWidth, 0f), 1f) else 0f

    private val isOpenAtAll by derivedStateOf { progress > 0 }
    val isScrimInteractive: Boolean get() = catchUp == null && isOpenAtAll
    val isContentAccessible: Boolean get() = catchUp != null || !isOpenAtAll
    val isDrawerAccessible: Boolean get() = catchUp == null && isOpenAtAll
    val handlesEscape: Boolean get() = catchUp != null || isPresented || isOpenAtAll

    init {
        isCardInteractive = !isPresented
        panRecognizer.isDrawerPresented = { this.isPresented }
        panRecognizer.isCardSwipe = { catchUp != null }
    }

    internal fun attach(
        scope: CoroutineScope,
        density: Float,
        isDark: Boolean,
        endEditing: () -> Unit,
        impact: () -> Unit,
        captureCard: suspend () -> androidx.compose.ui.graphics.ImageBitmap?,
        onPresentationChanged: (Boolean) -> Unit,
        onSettled: (Boolean) -> Unit,
    ) {
        this.scope = scope
        this.density = density
        this.endEditing = endEditing
        this.onPresentationChanged = onPresentationChanged
        this.onSettled = onSettled
        stack.scope = scope
        stack.density = density
        stack.isDark = isDark
        stack.endEditing = endEditing
        stack.impact = impact
        if (capturesCards) stack.captureCard = captureCard
    }

    fun update(width: Float, reducedMotion: Boolean) {
        this.reducedMotion = reducedMotion
        stack.reducedMotion = reducedMotion
        if (width == drawerWidth) return
        if (drawerWidth == 0f) {
            // UIKit lays the card out at its first width in `viewDidLoad`.
            drawerWidth = width
            apply(if (isPresented) width else 0f)
            return
        }
        val wasAnimating = animator != null
        val wasDragging = isDragging
        val currentOffset = stopAnimationAtCurrentOffset()
        val progress = if (drawerWidth > 0) min(max(currentOffset / drawerWidth, 0f), 1f) else 0f
        drawerWidth = width
        dragStartOffset = width * progress
        apply(dragStartOffset)

        if (wasDragging) {
            panRecognizer.cancelGesture()
            cancelDrag()
        } else if (wasAnimating) {
            settle(if (isPresented) width else 0f, animated = true)
        }
    }

    fun setPresented(presented: Boolean, animated: Boolean) {
        if (presented == isPresented) return
        isPresented = presented
        settle(if (presented) drawerWidth else 0f, animated)
    }

    internal fun panBegan() {
        if (catchUp != null) {
            finishModeTransition()
            stack.panBegan()
            return
        }
        beginDrag()
    }

    internal fun panChanged(translationX: Float, translationY: Float) {
        if (catchUp != null) {
            stack.panChanged(translationX, translationY)
            return
        }
        drag(translationX)
    }

    internal fun panEnded(translationX: Float, velocityX: Float) {
        if (catchUp != null) {
            stack.panEnded(translationX, velocityX)
            return
        }
        endDrag(velocityX)
    }

    internal fun panCancelled() {
        if (catchUp != null) {
            stack.panCancelled()
            return
        }
        cancelDrag()
    }

    fun beginDrag() {
        isDragging = true
        dragStartOffset = stopAnimationAtCurrentOffset()
        isCardInteractive = false
        endEditing()
    }

    /// [translation] is in pixels.
    fun drag(translation: Float) {
        apply(min(max(dragStartOffset + translation, 0f), drawerWidth))
    }

    /// [velocity] is in pixels per second.
    fun endDrag(velocity: Float) {
        isDragging = false
        val points = velocity / density
        val presented = if (abs(points) > 250) points > 0 else offset > drawerWidth / 2
        isPresented = presented
        onPresentationChanged(presented)
        settle(if (presented) drawerWidth else 0f, animated = true)
    }

    fun cancelDrag() {
        isDragging = false
        settle(if (isPresented) drawerWidth else 0f, animated = true)
    }

    fun accessibilityPerformEscape(): Boolean {
        val catchUp = catchUp
        if (catchUp != null) {
            catchUp.onExit()
            return true
        }
        if (!isPresented && offset <= 0) return false
        closeDrawer()
        return true
    }

    fun closeDrawer() {
        if (!isPresented && offset <= 0) return
        isPresented = false
        onPresentationChanged(false)
        settle(0f, animated = true)
    }

    private fun settle(at: Float, animated: Boolean) {
        val target = at
        stopAnimationAtCurrentOffset()
        if (!animated || reducedMotion) {
            apply(target)
            isCardInteractive = target == 0f
            isDrawerAvailable = target > 0
            arrive(open = target > 0)
            return
        }

        if (target > 0) {
            isCardInteractive = false
            endEditing()
            isDrawerAvailable = true
        }
        val start = offset
        animator = scope.launch {
            animate(0f, 1f, animationSpec = CatchUpStackController.uikitSpring(0.3)) { progress, _ ->
                apply(start + (target - start) * progress)
            }
            isCardInteractive = target == 0f
            isDrawerAvailable = target > 0
            animator = null
            arrive(open = target > 0)
        }
    }

    /// Stopping an animator skips its completion, so only a settle that runs
    /// to the end arrives.
    private fun arrive(open: Boolean) {
        if (open == restsOpen) return
        restsOpen = open
        onSettled(open)
    }

    private fun stopAnimationAtCurrentOffset(): Float {
        val running = animator ?: return offset
        animator = null
        running.cancel()
        return offset
    }

    private fun apply(offset: Float) {
        // The stack owns the card's transform while catching up.
        if (catchUp != null) return
        val progress = if (drawerWidth > 0) min(max(offset / drawerWidth, 0f), 1f) else 0f
        if (progress > 0) isDrawerAvailable = true
        this.offset = offset
        cornerRadius = 44 * progress
        scrimAlpha = progress
    }

    // MARK: Catch-up stack

    fun dismissCard(disposition: HomeFeature.CatchUpDisposition) {
        finishModeTransition()
        stack.fling(disposition)
    }

    fun undoCard() {
        finishModeTransition()
        stack.undo()
    }

    fun updateCatchUp(stage: CatchUpStage?, animated: Boolean) {
        val previous = catchUp
        if (stage == null && previous == null) return
        if ((stage != null) != (previous != null)) finishModeTransition()
        if (stage != null && previous == null) {
            if (isPresented) {
                isPresented = false
                onPresentationChanged(false)
            }
            // Catch-up takes over from the drawer; that is not a close to feel.
            restsOpen = false
            // Keep a closing drawer's offset; the inset animation carries it home.
            stopAnimationAtCurrentOffset()
        }
        catchUp = stage
        when {
            previous == null && stage != null -> {
                stack.begin(stage)
                setCatchingUp(true, animated)
            }
            previous != null && stage == null -> {
                stack.end()
                setCatchingUp(false, animated)
            }
            stage != null -> stack.update(stage)
        }
    }

    /// Morphs the card between full screen and its inset stack position. The
    /// hosted session changes at the same moment, so it fades in over the
    /// first part of the morph instead of popping.
    private fun setCatchingUp(catchingUp: Boolean, animated: Boolean) {
        isChromeHidden = false
        val showsCard = !catchingUp || catchUp?.cardID != null
        val start = floatArrayOf(
            morph, chromeAlpha, cornerRadius, scrimAlpha, offset,
            stack.cardTranslationX, stack.cardTranslationY, stack.cardRotation, stack.cardScale, stack.cardAlpha,
        )
        val endMorph = if (catchingUp) 1f else 0f
        val endRadius = if (catchingUp) CatchUpMetrics.cornerRadius.value else 0f
        val endAlpha = if (showsCard) 1f else 0f
        fun changes(progress: Float) {
            fun lerp(index: Int, end: Float) = start[index] + (end - start[index]) * progress
            morph = lerp(0, endMorph)
            chromeAlpha = lerp(1, endMorph)
            cornerRadius = lerp(2, endRadius)
            scrimAlpha = lerp(3, 0f)
            offset = lerp(4, 0f)
            stack.cardTranslationX = lerp(5, 0f)
            stack.cardTranslationY = lerp(6, 0f)
            stack.cardRotation = lerp(7, 0f)
            stack.cardScale = lerp(8, 1f)
            stack.cardAlpha = lerp(9, endAlpha)
        }
        fun completion() {
            if (catchingUp) {
                stack.revealPreview(animated)
                isDrawerAvailable = false
            } else {
                apply(0f)
            }
            isChromeHidden = !catchingUp
            isCardInteractive = showsCard
            contentFade = 1f
        }
        if (!animated) {
            changes(1f)
            completion()
            return
        }
        finishMode = {
            changes(1f)
            completion()
        }
        // A brief handoff: long enough to read as one surface, short enough
        // that the two sessions never linger as a double exposure.
        contentFade = 0f
        modeAnimator = scope.launch {
            launch {
                animate(0f, 1f, animationSpec = tween(200, easing = CatchUpStackController.EaseOut)) { value, _ -> contentFade = value }
            }
            animate(0f, 1f, animationSpec = CatchUpStackController.uikitSpring(0.45)) { value, _ -> changes(value) }
            modeAnimator = null
            finishMode = null
            changes(1f)
            completion()
        }
    }

    /// Hand ownership from the full-screen morph to the card choreography.
    /// Finishing (rather than cancelling) also ends the handoff fade.
    private fun finishModeTransition() {
        val running = modeAnimator ?: return
        val finish = finishMode
        modeAnimator = null
        finishMode = null
        running.cancel()
        finish?.invoke()
    }
}

/// Mutes the session card while the drawer is open. Its alpha is the
/// drawer's progress, so it follows the drag and the settle exactly.
object DrawerScrim {
    /// Light darkens the white card toward grey. A black scrim would vanish on
    /// the black dark-mode card, so dark mode blends toward grey instead:
    /// the background lifts off the black drawer and the text recedes.
    fun color(dark: Boolean, highContrast: Boolean): Color {
        if (dark) return Color(red = 0.24f, green = 0.24f, blue = 0.24f, alpha = if (highContrast) 0.7f else 0.6f)
        return Color(red = 0f, green = 0f, blue = 0f, alpha = if (highContrast) 0.45f else 0.32f)
    }
}

/// Where on the stage a touch began, for the drawer pan's arbitration: the
/// views UIKit's hit-test walk finds on the way up from the touched view.
enum class DrawerTouchRegion {
    drawer,
    list,
    row,
    card,
}

internal val LocalDirectionalPanGestureRecognizer = staticCompositionLocalOf<DirectionalPanGestureRecognizer?> { null }

/// Marks the region under a touch for the drawer pan. UIKit finds the
/// drawer list (a collection view) and its rows (cells) by walking up from
/// the touched view; Compose has no such walk, so the regions say so.
@Composable
fun Modifier.drawerTouchRegion(region: DrawerTouchRegion): Modifier {
    val recognizer = LocalDirectionalPanGestureRecognizer.current ?: return this
    return pointerInput(recognizer, region) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            recognizer.mark(region)
        }
    }
}

/// A horizontal scroller (code, a table, attachments) under a touch: the
/// drawer and the catch-up card yield a drag the scroller can still follow.
@Composable
fun Modifier.drawerYieldsToHorizontalScroll(canScrollBackward: () -> Boolean, canScrollForward: () -> Boolean): Modifier {
    val recognizer = LocalDirectionalPanGestureRecognizer.current ?: return this
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    return pointerInput(recognizer, rtl) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val backward = canScrollBackward()
            val forward = canScrollForward()
            recognizer.markScroller(ownsRightwardDrag = if (rtl) forward else backward, ownsLeftwardDrag = if (rtl) backward else forward)
        }
    }
}

/// The drawer's pan, at the root of the stage. It sees each touch before
/// anything under it (Compose's initial pass runs ancestors first), waits for
/// 8 points of travel, then either fails and leaves the touch to the list, a
/// row's swipe, or a scroller, or claims it and consumes every later move so
/// nothing beneath starts. A descendant that claims the touch first (one
/// with a smaller touch slop) wins, as UIKit's failure requirement makes
/// the drawer's pan wait for nothing it would fight.
private fun Modifier.directionalPan(controller: SessionDrawerViewController): Modifier = pointerInput(controller) {
    val recognizer = controller.panRecognizer
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        recognizer.touchesBegan()
        val generation = recognizer.generation
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var resolved = false
        var chosen = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (!resolved) {
                // The regions under the touch marked themselves after this
                // handler saw the down, in the same pass.
                recognizer.resolveOrigin()
                resolved = true
            }
            if (recognizer.generation != generation) return@awaitEachGesture
            val change = event.changes.firstOrNull { it.id == down.id }
            if (change == null || (!chosen && event.changes.count { it.pressed } > 1)) {
                if (chosen) controller.panCancelled()
                return@awaitEachGesture
            }
            val translation = change.position - down.position
            if (!change.pressed) {
                if (chosen) controller.panEnded(translation.x, tracker.calculateVelocity().x)
                return@awaitEachGesture
            }
            tracker.addPosition(change.uptimeMillis, change.position)
            if (!chosen) {
                when (recognizer.decide(dx = translation.x / density, dy = translation.y / density)) {
                    DirectionalPanGestureRecognizer.Decision.wait -> {
                        val final = awaitPointerEvent(PointerEventPass.Final)
                        val claimed = final.changes.any { it.id == down.id && it.isConsumed && it.positionChangeIgnoreConsumed() != Offset.Zero }
                        if (claimed) return@awaitEachGesture
                        continue
                    }
                    DirectionalPanGestureRecognizer.Decision.fail -> return@awaitEachGesture
                    DirectionalPanGestureRecognizer.Decision.begin -> {
                        chosen = true
                        controller.panBegan()
                    }
                }
            }
            change.consume()
            controller.panChanged(translation.x, translation.y)
        }
    }
}

class DirectionalPanGestureRecognizer {
    sealed interface TouchOrigin {
        data object drawerRow : TouchOrigin

        data object drawerList : TouchOrigin

        data object drawerChrome : TouchOrigin

        data object exposedCard : TouchOrigin

        data class content(val horizontalScrollerOwnsRightwardDrag: Boolean) : TouchOrigin
    }

    enum class Decision {
        begin,
        fail,
        wait,
    }

    private var touchOrigin: TouchOrigin = TouchOrigin.content(horizontalScrollerOwnsRightwardDrag = false)
    private var cardSwipeOrigin = CardSwipeOrigin.outsideCard
    var isDrawerPresented: () -> Boolean = { false }

    /// Catch-up turns the drawer pan into a two-way card swipe.
    var isCardSwipe: () -> Boolean = { false }

    data class CardSwipeOrigin(
        val onCard: Boolean,
        val scrollerOwnsRightwardDrag: Boolean = false,
        val scrollerOwnsLeftwardDrag: Boolean = false,
    ) {
        companion object {
            val outsideCard = CardSwipeOrigin(onCard = false)
        }
    }

    // What the touch landed on, marked by the regions under it.
    private var hitDrawer = false
    private var hitDrawerList = false
    private var hitDrawerRow = false
    private var hitExposedCard = false
    private var horizontalScrollerOwnsRightwardDrag = false
    private var horizontalScrollerOwnsLeftwardDrag = false

    /// Bumped to abandon the touch in progress, as UIKit's disable and enable does.
    internal var generation = 0
        private set

    internal fun touchesBegan() {
        hitDrawer = false
        hitDrawerList = false
        hitDrawerRow = false
        hitExposedCard = false
        horizontalScrollerOwnsRightwardDrag = false
        horizontalScrollerOwnsLeftwardDrag = false
        touchOrigin = TouchOrigin.content(horizontalScrollerOwnsRightwardDrag = false)
        cardSwipeOrigin = CardSwipeOrigin.outsideCard
    }

    internal fun mark(region: DrawerTouchRegion) {
        when (region) {
            DrawerTouchRegion.drawer -> hitDrawer = true
            DrawerTouchRegion.list -> hitDrawerList = true
            DrawerTouchRegion.row -> hitDrawerRow = true
            DrawerTouchRegion.card -> hitExposedCard = true
        }
    }

    internal fun markScroller(ownsRightwardDrag: Boolean, ownsLeftwardDrag: Boolean) {
        if (ownsRightwardDrag) horizontalScrollerOwnsRightwardDrag = true
        if (ownsLeftwardDrag) horizontalScrollerOwnsLeftwardDrag = true
    }

    internal fun resolveOrigin() {
        cardSwipeOrigin = CardSwipeOrigin(
            onCard = hitExposedCard,
            scrollerOwnsRightwardDrag = horizontalScrollerOwnsRightwardDrag,
            scrollerOwnsLeftwardDrag = horizontalScrollerOwnsLeftwardDrag,
        )
        touchOrigin = if (isDrawerPresented()) {
            if (hitDrawerRow && hitDrawer) {
                TouchOrigin.drawerRow
            } else if (hitDrawerList && hitDrawer) {
                TouchOrigin.drawerList
            } else if (hitExposedCard) {
                TouchOrigin.exposedCard
            } else {
                TouchOrigin.drawerChrome
            }
        } else {
            TouchOrigin.content(horizontalScrollerOwnsRightwardDrag = horizontalScrollerOwnsRightwardDrag)
        }
    }

    /// [dx] and [dy] are the touch's travel in points.
    internal fun decide(dx: Float, dy: Float): Decision =
        if (isCardSwipe()) {
            cardSwipeDecision(origin = cardSwipeOrigin, dx = dx, dy = dy)
        } else {
            decision(origin = touchOrigin, presented = isDrawerPresented(), dx = dx, dy = dy)
        }

    internal fun cancelGesture() {
        generation += 1
    }

    companion object {
        /// A card swipes either way, except while a horizontal code, table, or
        /// attachment scroller under the finger can still move that way.
        fun cardSwipeDecision(origin: CardSwipeOrigin, dx: Float, dy: Float): Decision {
            if (dx * dx + dy * dy < 64) return Decision.wait
            if (!origin.onCard || abs(dx) <= abs(dy)) return Decision.fail
            val scrollerOwnsDrag = if (dx > 0) origin.scrollerOwnsRightwardDrag else origin.scrollerOwnsLeftwardDrag
            return if (scrollerOwnsDrag) Decision.fail else Decision.begin
        }

        fun decision(
            origin: TouchOrigin,
            presented: Boolean,
            dx: Float,
            dy: Float,
        ): Decision {
            if (dx * dx + dy * dy < 64) return Decision.wait
            if (abs(dx) <= abs(dy)) return Decision.fail
            if (dx <= 0) {
                if (!presented || origin == TouchOrigin.drawerRow) return Decision.fail
                return Decision.begin
            }
            if (presented && (origin == TouchOrigin.drawerRow || origin == TouchOrigin.drawerList)) return Decision.fail
            if (!presented && origin == TouchOrigin.content(horizontalScrollerOwnsRightwardDrag = true)) return Decision.fail
            return Decision.begin
        }
    }
}
