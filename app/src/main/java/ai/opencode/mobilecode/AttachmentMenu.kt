@file:PortedFrom("OpenCode/AttachmentMenu.swift", "b479446")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.GlassSurface
import ai.opencode.mobilecode.system.SystemColors
import ai.opencode.mobilecode.system.SystemImage
import ai.opencode.mobilecode.system.TextStyles
import ai.opencode.mobilecode.system.glassEffect
import ai.opencode.mobilecode.system.semibold
import ai.opencode.mobilecode.system.symbolSize
import ai.opencode.mobilecode.tca.Binding
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ext.SdkExtensions
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateRectAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/// Where the attachment menu is. The menu's glass grows out of the composer
/// at the + button (`AttachmentMenuPanel`); the photo-picker card
/// (`AttachmentMenuOverlay`) takes the glass over from the menu, morphs it
/// into the card, and hands it back on the way out.
enum class AttachmentMenuPhase { closed, menu, photos }

/// SwiftUI's `spring(duration:bounce:)` is stiffness (2π / duration)² and a
/// damping ratio of 1 − bounce; its `easeInOut` is this cubic.
object AttachmentMenuMotion {
    private val easeInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

    /// The glass grows out of + while the + turns into a ×.
    fun <T> open(reduceMotion: Boolean): AnimationSpec<T> =
        if (reduceMotion) tween(200, easing = easeInOut) else spring(dampingRatio = 0.82f, stiffness = 247f)

    fun <T> morph(reduceMotion: Boolean): AnimationSpec<T> =
        if (reduceMotion) tween(220, easing = easeInOut) else spring(dampingRatio = 0.88f, stiffness = 158f)

    fun <T> close(reduceMotion: Boolean): AnimationSpec<T> =
        if (reduceMotion) tween(200, easing = easeInOut) else spring(dampingRatio = 1f, stiffness = 341f)
}

object AttachmentMenuMetrics {
    const val cornerRadius = 28f

    /// Between the panel's edge and its rows; the highlight is concentric.
    const val padding = 6f

    /// Between the composer's top edge and the menu's bottom edge.
    const val composerGap = 10f
    const val minimumWidth = 210f
}

/// Media kinds the inline picker can narrow to.
enum class InlinePhotoFilter {
    all,
    screenshots,
    videos,
    ;

    val title: String
        get() = when (this) {
            all -> "All Photos"
            screenshots -> "Screenshots"
            videos -> "Videos"
        }

    val systemImage: String
        get() = when (this) {
            all -> "photo.on.rectangle"
            screenshots -> "camera.viewfinder"
            videos -> "video"
        }

    companion object {
        fun visible(includesVideos: Boolean): List<InlinePhotoFilter> =
            entries.filter { includesVideos || it != videos }
    }
}

/// The inline picker is the system's embedded photo picker where the device
/// has one; elsewhere Photos opens the system picker instead of the card.
enum class PhotoPickerKind { embedded, system }

fun photoPickerKind(): PhotoPickerKind = if (embeddedPhotoPickerSupported()) PhotoPickerKind.embedded else PhotoPickerKind.system

fun embeddedPhotoPickerSupported(): Boolean {
    if (Build.VERSION.SDK_INT < 34) return false
    return SdkExtensions.getExtensionVersion(Build.VERSION_CODES.UPSIDE_DOWN_CAKE) >= 15
}

fun cameraAvailable(context: Context): Boolean =
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

fun clipboardHasImage(context: Context): Boolean {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
    val description = clipboard.primaryClipDescription ?: return false
    return description.hasMimeType("image/png") || description.hasMimeType("image/jpeg") ||
        description.hasMimeType("image/gif") || description.hasMimeType("image/webp") ||
        description.hasMimeType("image/*")
}

// MARK: - Menu

/// The menu's glass, an overlay of the composer capsule. One glass shape is
/// the menu: closed, it is a small seed under the + button; opening springs
/// its frame and corners out into the panel resting above the composer, so
/// the menu pulls out of the composer at the + (which stays put and turns
/// into the menu's ×), and closing shrinks it back in. Compose glass has no
/// container that merges shapes, so the seed is clear at rest: the glass
/// appears as it leaves the + and clears as it folds back. Under Reduce
/// Motion the panel cross-fades in place instead.
@Composable
fun AttachmentMenuPanel(
    /// The panel shows; otherwise the glass rests as the seed.
    isOpen: Boolean,
    items: List<AttachmentMenuItem>,
    anchors: AttachmentMenuAnchors,
    press: AttachmentMenuPress,
    lift: Float = 0f,
    onChoose: (AttachmentMenuItem) -> Unit,
    onPaste: (List<NSItemProvider>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalReduceMotion.current
    val scope = rememberCoroutineScope()
    val dragOffset = remember { Animatable(0f) }
    var panelSize by remember { mutableStateOf(Size(AttachmentMenuMetrics.minimumWidth, 160f)) }
    /// The rows stay mounted while the glass folds back into the seed.
    var rowsLinger by remember { mutableStateOf(false) }
    /// A drag that carries the menu also carries a row under the finger;
    /// its release must not count as a tap.
    var isDragging by remember { mutableStateOf(false) }

    /// The + button's center within the capsule; both anchors are resting
    /// frames, so it holds while the picker card lifts the composer.
    val buttonCenter = anchors.button.center - anchors.composer.topLeft
    val seed = Rect(buttonCenter.x - seedSize / 2, buttonCenter.y - seedSize / 2, buttonCenter.x + seedSize / 2, buttonCenter.y + seedSize / 2)

    @Composable
    fun rows(highlighted: AttachmentMenuItem?, isPanel: Boolean) {
        AttachmentMenuRows(
            items = items,
            buttonCenterX = buttonCenter.x,
            highlighted = highlighted,
            anchors = anchors,
            isResting = isOpen,
            onChoose = { item ->
                if (!isDragging) onChoose(item)
            },
            onPaste = onPaste,
            enabled = isPanel,
        )
    }

    @Composable
    fun surface(rect: Rect, radius: Float, isPanel: Boolean, glassAlpha: () -> Float) {
        val shape = RoundedCornerShape(max(radius, 0f).dp)
        val density = LocalDensity.current
        val rowsAlpha by animateFloatAsState(if (isPanel) 1f else 0f, tween(100), label = "rows")
        Box(
            Modifier
                .frame(rect)
                .graphicsLayer { alpha = glassAlpha() }
                .glassEffect(shape)
                .clip(shape)
                .then(
                    if (!isPanel) {
                        Modifier
                    } else {
                        Modifier.pointerInput(Unit) {
                            val tracker = VelocityTracker()
                            var translation = 0f
                            detectVerticalDragGestures(
                                onDragStart = {
                                    translation = 0f
                                    tracker.resetTracking()
                                    isDragging = true
                                },
                                onVerticalDrag = { change, amount ->
                                    tracker.addPosition(change.uptimeMillis, change.position)
                                    translation += amount / density.density
                                    // Rubber-band upward; follow the finger downward.
                                    val y = translation
                                    scope.launch { dragOffset.snapTo(if (y > 0) y else -sqrt(-y) * 2) }
                                },
                                onDragEnd = {
                                    scope.launch { isDragging = false }
                                    val predicted = translation + tracker.calculateVelocity().y / density.density * 0.25f
                                    if (translation > 50 || predicted > 160) {
                                        onDismiss()
                                        scope.launch { dragOffset.animateTo(0f, AttachmentMenuMotion.close(reduceMotion)) }
                                    } else {
                                        scope.launch { dragOffset.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = 322f)) }
                                    }
                                },
                                onDragCancel = {
                                    isDragging = false
                                    scope.launch { dragOffset.animateTo(0f) }
                                },
                            )
                        }
                    },
                )
                .then(
                    if (isPanel) {
                        Modifier.semantics { testTag = "attachment-menu" }
                    } else {
                        Modifier.clearAndSetSemantics {}
                    },
                ),
            contentAlignment = Alignment.BottomStart,
        ) {
            // Mounted only while the panel is open or folding away: hidden rows
            // inside the glass would still reach assistive technologies.
            AnimatedVisibility(
                visible = isPanel || (rowsLinger && !reduceMotion),
                enter = fadeIn(tween(220, delayMillis = if (reduceMotion) 0 else 80)),
                exit = fadeOut(tween(220, delayMillis = if (reduceMotion) 0 else 80)),
            ) {
                // The rows size themselves; the panel takes their size.
                Box(
                    Modifier
                        .wrapContentSize(Alignment.BottomStart, unbounded = true)
                        .onSizeChanged { panelSize = Size(it.width / density.density, it.height / density.density) }
                        // One layer, so its text fades with it. The rows arrive
                        // once the glass has mostly grown, and leave first.
                        .graphicsLayer { alpha = rowsAlpha },
                ) {
                    rows(highlighted = press.highlighted, isPanel = isPanel)
                }
            }
        }
    }

    BoxWithConstraints(modifier) {
        /// On the composer's leading edge, standing a little above it.
        val panel = Rect(
            0f,
            -AttachmentMenuMetrics.composerGap - panelSize.height,
            min(panelSize.width, maxWidth.value),
            -AttachmentMenuMetrics.composerGap,
        )
        // Where the panel rests, for the photo-picker card to morph from.
        Box(
            Modifier
                .frame(panel)
                .attachmentMenuAnchor(anchors::menu, lift)
                .clearAndSetSemantics {},
        )
        if (reduceMotion) {
            AnimatedVisibility(isOpen, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
                surface(rect = panel, radius = AttachmentMenuMetrics.cornerRadius, isPanel = true, glassAlpha = { 1f })
            }
        } else {
            val spec = if (isOpen) AttachmentMenuMotion.open<Rect>(reduceMotion = false) else AttachmentMenuMotion.close(reduceMotion = false)
            val rect by animateRectAsState(if (isOpen) panel else seed, spec, label = "menu")
            val radius by animateFloatAsState(
                if (isOpen) AttachmentMenuMetrics.cornerRadius else seedSize / 2,
                if (isOpen) AttachmentMenuMotion.open(reduceMotion = false) else AttachmentMenuMotion.close(reduceMotion = false),
                label = "radius",
            )
            val glassAlpha by animateFloatAsState(
                if (isOpen) 1f else 0f,
                if (isOpen) tween(90) else tween(110, delayMillis = 170),
                label = "glass",
            )
            surface(rect = rect.translate(0f, if (isOpen) dragOffset.value else 0f), radius = radius, isPanel = isOpen, glassAlpha = { glassAlpha })
        }
    }
    LaunchedEffect(isOpen) {
        if (isOpen) {
            rowsLinger = true
            return@LaunchedEffect
        }
        if (!rowsLinger) return@LaunchedEffect
        delay(400)
        rowsLinger = false
    }
    // The system's back is the escape gesture.
    BackHandler(enabled = isOpen) { onDismiss() }
}

private const val seedSize = 34f

/// The menu's rows, shared by the panel and the copy the picker card
/// carries while it takes the glass over.
@Composable
fun AttachmentMenuRows(
    items: List<AttachmentMenuItem>,
    /// Where the + glyph's center sits from the panel's leading edge; the
    /// rows' icons line up under it.
    buttonCenterX: Float,
    highlighted: AttachmentMenuItem? = null,
    /// Records each row's frame for choosing by drag; the card's copy doesn't.
    anchors: AttachmentMenuAnchors? = null,
    /// The rows are laid out where the open menu rests.
    isResting: Boolean = true,
    onChoose: (AttachmentMenuItem) -> Unit = {},
    onPaste: (List<NSItemProvider>) -> Unit = {},
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val rowHeight = with(density) { 48.sp.toDp() }
    val iconWidth = with(density) { 26.sp.toDp() }
    val iconInset = max(buttonCenterX - AttachmentMenuMetrics.padding - iconWidth.value / 2, 10f)
    val origin = remember { RowsOrigin() }
    Column(
        modifier
            .onGloballyPositioned { origin.coordinates = it }
            .widthIn(min = AttachmentMenuMetrics.minimumWidth.dp)
            .width(IntrinsicSize.Max)
            .padding(AttachmentMenuMetrics.padding.dp),
    ) {
        items.forEach { item ->
            val label = AttachmentMenuRowLabel(item = item, iconWidth = iconWidth.value, inset = iconInset, height = rowHeight.value)
            Box(Modifier.rowAnchor(item, anchors, isResting, origin)) {
                if (item == AttachmentMenuItem.paste) {
                    AttachmentPasteRow(label = label, onPaste = onPaste, enabled = enabled)
                } else {
                    AttachmentMenuRowButton(label = label, isHighlighted = highlighted == item, enabled = enabled) { onChoose(item) }
                }
            }
        }
    }
}

/// The rows' own top-leading corner, which rests on the panel's.
private class RowsOrigin {
    var coordinates: LayoutCoordinates? = null
}

@Composable
private fun Modifier.rowAnchor(item: AttachmentMenuItem, anchors: AttachmentMenuAnchors?, isResting: Boolean, origin: RowsOrigin): Modifier {
    if (anchors == null) return this
    val density = LocalDensity.current.density
    androidx.compose.runtime.DisposableEffect(item, anchors) {
        onDispose { anchors.rowOffsets -= item }
    }
    return onGloballyPositioned { coordinates ->
        // Folded into the seed, the rows aren't where the menu rests.
        if (!isResting) return@onGloballyPositioned
        val rows = origin.coordinates?.takeIf { it.isAttached } ?: return@onGloballyPositioned
        val offset = rows.localPositionOf(coordinates, Offset.Zero) / density
        anchors.rowOffsets += item to Rect(offset.x, offset.y, offset.x + coordinates.size.width / density, offset.y + coordinates.size.height / density)
    }
}

private class AttachmentMenuRowLabel(
    val item: AttachmentMenuItem,
    val iconWidth: Float,
    val inset: Float,
    val height: Float,
) {
    companion object {
        const val highlightRadius = AttachmentMenuMetrics.cornerRadius - AttachmentMenuMetrics.padding
    }
}

@Composable
private fun AttachmentMenuRowContent(label: AttachmentMenuRowLabel, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = label.height.dp)
            .padding(start = label.inset.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(label.iconWidth.dp), contentAlignment = Alignment.Center) {
            SystemImage(label.item.systemImage, contentDescription = null, tint = SystemColors.label, modifier = Modifier.symbolSize(TextStyles.body))
        }
        Text(label.item.title, style = TextStyles.body, color = SystemColors.label, maxLines = 1)
        Spacer(Modifier.weight(1f).widthIn(min = 24.dp))
    }
}

/// The system menus' highlight: a quiet fill concentric with the panel, for
/// a press or a finger sliding over the row from +.
@Composable
private fun AttachmentMenuRowButton(label: AttachmentMenuRowLabel, isHighlighted: Boolean, enabled: Boolean, action: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val lit = pressed || isHighlighted
    val fill by animateFloatAsState(if (lit) 1f else 0f, tween(if (lit) 80 else 200), label = "highlight")
    val shape = RoundedCornerShape(AttachmentMenuRowLabel.highlightRadius.dp)
    AttachmentMenuRowContent(
        label,
        Modifier
            .background(SystemColors.tertiarySystemFill.let { it.copy(alpha = it.alpha * fill) }, shape)
            .clip(shape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = action)
            .semantics { contentDescription = label.item.title },
    )
}

/// Paste reads the clipboard when tapped; Android shows no paste alert. iOS's
/// paste control insists on drawing itself opaque, so the row keeps its fill.
@Composable
private fun AttachmentPasteRow(label: AttachmentMenuRowLabel, onPaste: (List<NSItemProvider>) -> Unit, enabled: Boolean) {
    val context = LocalContext.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    /// Opaque, as the control requires: about the rows' highlight over glass.
    val fill = Color(if (dark) 0.2f else 0.92f, if (dark) 0.2f else 0.92f, if (dark) 0.2f else 0.92f)
    val shape = RoundedCornerShape(AttachmentMenuRowLabel.highlightRadius.dp)
    AttachmentMenuRowContent(
        label,
        Modifier
            .background(fill, shape)
            .clip(shape)
            .clickable(enabled = enabled) {
                val providers = clipboardImages(context)
                if (providers.isNotEmpty()) onPaste(providers)
            }
            .semantics { contentDescription = label.item.title },
    )
}

private fun clipboardImages(context: Context): List<NSItemProvider> {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return emptyList()
    val clip = clipboard.primaryClip ?: return emptyList()
    return (0 until clip.itemCount).mapNotNull { index ->
        val item = clip.getItemAt(index)
        val uri = item.uri ?: return@mapNotNull null
        val type = context.contentResolver.getType(uri) ?: return@mapNotNull null
        if (!type.startsWith("image/")) return@mapNotNull null
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@mapNotNull null
        NSItemProvider(registeredTypeIdentifiers = listOf(type), data = bytes, canLoadImage = true)
    }
}

// MARK: - Photo-picker card

object AttachmentMenuOverlay {
    const val cardCornerRadius = 44f
    const val cardInset = 8f

    /// Between the composer's bottom edge and the card's top edge.
    const val composerGap = 10f
}

private data class AttachmentMenuFrames(
    val button: Rect,
    val menu: Rect,
    /// A sliver across the Photos row: the picker opens out of it and folds
    /// back into it.
    val photosSeed: Rect,
    val card: Rect,
    /// Above the composer: tapping here dismisses while the card is up.
    val scrim: Rect,
    val reservation: Float,
)

/// Drawn over the whole session screen: the dismissing scrim, the inline
/// photo picker, and, while `cardOwnsGlass`, the glass the menu hands it.
/// The picker card sits under the composer, which rises by
/// `pickerReservation` to make room for it.
@Composable
fun AttachmentMenuOverlay(
    phase: Binding<AttachmentMenuPhase>,
    /// Set (without animation) as Photos is chosen: the card's glass replaces
    /// the menu's in place, then morphs into the card. Cleared once Back has
    /// morphed it back onto the menu, which takes it over again.
    cardOwnsGlass: Binding<Boolean>,
    anchors: AttachmentMenuAnchors,
    selection: InlinePhotoSelection,
    items: List<AttachmentMenuItem>,
    /// Videos upload as files, so they appear only when the server takes uploads.
    includesVideos: Boolean,
    /// Space the composer area keeps below the composer while the card is up.
    pickerReservation: Binding<Float>,
    onPhotos: (List<PhotosPickerItem>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalReduceMotion.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val assistive = remember(context) { assistiveTechnologyEnabled(context) }
    var filter by remember { mutableStateOf(InlinePhotoFilter.all) }
    /// Keeps the picker drawn while the card morphs away from it.
    var keepsPickerDrawn by remember { mutableStateOf(false) }
    /// The open phase the panel last showed; the card closes by sliding down.
    var openPhase by remember { mutableStateOf(AttachmentMenuPhase.menu) }
    var origin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val current = phase.wrappedValue
    val safeTop = WindowInsets.systemBars.getTop(density) / density.density
    val safeBottom = WindowInsets.systemBars.getBottom(density) / density.density

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot() / density.density },
    ) {
        val frames = frames(
            width = maxWidth.value,
            height = maxHeight.value,
            safeTop = safeTop,
            safeBottom = safeBottom,
            origin = origin,
            anchors = anchors,
        )
        LaunchedEffect(frames.reservation) { pickerReservation.wrappedValue = frames.reservation }
        // Phase changes animate like their SwiftUI transactions: Photos and Back
        // morph, dismissing closes.
        val closing = current == AttachmentMenuPhase.closed
        val panelTarget = when (current) {
            // Like the keyboard it stands in for, the card leaves downward.
            AttachmentMenuPhase.closed -> frames.card.translate(0f, frames.card.height + AttachmentMenuOverlay.cardInset * 4)
            AttachmentMenuPhase.menu -> frames.menu
            AttachmentMenuPhase.photos -> frames.card
        }
        val owned = cardOwnsGlass.wrappedValue
        // Only the glass the card holds travels; otherwise it waits on the menu.
        val panelRect = animatedRect(panelTarget, if (closing) AttachmentMenuMotion.close(reduceMotion) else AttachmentMenuMotion.morph(reduceMotion), animates = owned)
        val panelRadius = animatedFloat(
            if (current == AttachmentMenuPhase.menu) AttachmentMenuMetrics.cornerRadius else AttachmentMenuOverlay.cardCornerRadius,
            if (closing) AttachmentMenuMotion.close(reduceMotion) else AttachmentMenuMotion.morph(reduceMotion),
            animates = owned,
        )
        /// Where the menu's glass and rows are: travelling with the card, or
        /// under Reduce Motion staying on the menu while they cross-fade.
        val glassRect = if (reduceMotion) frames.menu else panelRect
        val glassRadius = if (reduceMotion) AttachmentMenuMetrics.cornerRadius else panelRadius
        val showsCard = current == AttachmentMenuPhase.photos || (closing && openPhase == AttachmentMenuPhase.photos)
        /// Where the picker shows. It is drawn opaque from the moment the menu
        /// opens, so revealing it never waits on the system's placeholder.
        val pickerMaskRect = animatedRect(
            if (reduceMotion) frames.card else if (showsCard) panelTarget else frames.photosSeed,
            if (closing) AttachmentMenuMotion.close(reduceMotion) else AttachmentMenuMotion.morph(reduceMotion),
            animates = true,
        )
        // Revealed in place while morphing; carried along when the card leaves.
        val pickerRect = animatedRect(
            if (closing && openPhase == AttachmentMenuPhase.photos && !reduceMotion) panelTarget else frames.card,
            AttachmentMenuMotion.close(reduceMotion),
            animates = true,
        )
        /// The system picker takes a moment to load, so it mounts (masked away)
        /// as soon as the menu opens. Its out-of-process elements can't be hidden
        /// from assistive technologies, which therefore get it only once chosen.
        val mountsPicker = photoPickerKind() == PhotoPickerKind.embedded && when (current) {
            AttachmentMenuPhase.photos -> true
            AttachmentMenuPhase.menu -> keepsPickerDrawn || !assistive
            AttachmentMenuPhase.closed -> keepsPickerDrawn
        }

        if (current == AttachmentMenuPhase.photos) {
            // While the card is up, everywhere above the composer dismisses.
            AttachmentMenuDismissArea(
                onDismiss = { phase.wrappedValue = AttachmentMenuPhase.closed },
                modifier = Modifier.frame(frames.scrim),
            )
        }
        if (mountsPicker) {
            val visible = current == AttachmentMenuPhase.photos
            // With Reduce Motion the card stays put and simply fades.
            val drawn by animateFloatAsState(if (!reduceMotion || visible) 1f else 0f, tween(if (reduceMotion) 200 else 0), label = "picker")
            val rect = pickerRect
            // Waiting behind the menu, it parks below the screen: masking hides it
            // from sight but not from accessibility, which would find it instead
            // of the menu's rows. It moves into place, unseen, as the card takes
            // the glass over.
            val parked = if (current == AttachmentMenuPhase.menu && !cardOwnsGlass.wrappedValue) frames.card.bottom + frames.card.height else 0f
            val maskRadius = if (reduceMotion || showsCard) AttachmentMenuOverlay.cardCornerRadius else 0f
            // The mask, then the picker inside it at its own frame.
            Box(
                Modifier
                    .frame(pickerMaskRect.translate(0f, parked))
                    .clip(RoundedCornerShape(maskRadius.dp))
                    .then(if (visible) Modifier else Modifier.clearAndSetSemantics {}),
            ) {
                Box(
                    Modifier
                        .frame(rect.translate(-pickerMaskRect.left, -pickerMaskRect.top))
                        .graphicsLayer { alpha = drawn }
                        .background(SystemColors.systemBackground)
                        .semantics { testTag = "inline-photo-picker" },
                ) {
                    EmbeddedPhotoPickerHost(selection, filter, includesVideos, onPhotos, Modifier.fillMaxSize())
                }
            }
        }
        if (cardOwnsGlass.wrappedValue) {
            val visible = current == AttachmentMenuPhase.menu
            // The menu's glass, taken over by the card. Over the card it clears like
            // a veil, revealing the picker beneath instead of fading the picker
            // itself: the system picker shows a blurred placeholder whenever it is
            // drawn translucent.
            val glassAlpha by animateFloatAsState(
                if (visible) 1f else 0f,
                // Clears as the card opens (after a beat that hides the picker's first
                // placeholder frames); returning, the grid stays readable while it
                // shrinks before the glass frosts back over it.
                if (current == AttachmentMenuPhase.photos) tween(320, delayMillis = 80) else tween(240, delayMillis = if (visible) 100 else 0),
                label = "veil",
            )
            Box(
                Modifier
                    .frame(glassRect)
                    .graphicsLayer { alpha = glassAlpha }
                    .glassEffect(RoundedCornerShape(glassRadius.dp))
                    .clearAndSetSemantics {},
            )
            // The menu's rows, drawn over the glass the card took over, fading as it
            // morphs into the card and back in as it returns.
            val rowsAlpha by animateFloatAsState(
                if (visible) 1f else 0f,
                if (visible) tween(280, delayMillis = 50) else tween(200),
                label = "copy",
            )
            Box(
                Modifier
                    .frame(glassRect)
                    .clip(RoundedCornerShape(glassRadius.dp))
                    .clearAndSetSemantics {},
            ) {
                AttachmentMenuRows(
                    items = items,
                    buttonCenterX = frames.button.center.x - frames.menu.left,
                    enabled = false,
                    modifier = Modifier
                        .frame(frames.menu.translate(-glassRect.left, -glassRect.top))
                        .wrapContentSize(Alignment.BottomCenter, unbounded = true)
                        .graphicsLayer { alpha = rowsAlpha },
                )
            }
        }
        AnimatedVisibility(
            visible = current == AttachmentMenuPhase.photos,
            modifier = Modifier.frame(frames.card),
            // They settle onto the card once it has mostly opened.
            enter = if (reduceMotion) {
                fadeIn()
            } else {
                slideInVertically(spring(dampingRatio = 0.8f, stiffness = 247f)) { with(density) { 18.dp.roundToPx() } } + fadeIn(tween(250, delayMillis = 140))
            },
            exit = fadeOut(tween(120)),
        ) {
            PickerControls(
                filter = filter,
                includesVideos = includesVideos,
                onFilter = { filter = it },
                /// The card morphs back into the menu, which then takes its glass back.
                onBack = { phase.wrappedValue = AttachmentMenuPhase.menu },
            )
        }
    }
    LaunchedEffect(current) {
        if (current != AttachmentMenuPhase.closed) openPhase = current
        if (current == AttachmentMenuPhase.photos) keepsPickerDrawn = true
        if (current == AttachmentMenuPhase.closed) cardOwnsGlass.wrappedValue = false
    }
    LaunchedEffect(current) {
        if (current != AttachmentMenuPhase.menu || !cardOwnsGlass.wrappedValue) return@LaunchedEffect
        // Once the card has settled back onto the menu.
        delay(if (reduceMotion) 260 else 560)
        if (phase.wrappedValue != AttachmentMenuPhase.menu) return@LaunchedEffect
        cardOwnsGlass.wrappedValue = false
    }
    LaunchedEffect(current) {
        if (current == AttachmentMenuPhase.photos || !keepsPickerDrawn) return@LaunchedEffect
        delay(550)
        keepsPickerDrawn = false
        if (phase.wrappedValue == AttachmentMenuPhase.closed) openPhase = AttachmentMenuPhase.menu
    }
}

/// SwiftUI's `.frame(width:height:)` and `.offset(x:y:)` from the parent's
/// top-leading corner, in points: the view takes the rect whatever room the
/// parent offers, and takes none from it.
private fun Modifier.frame(rect: Rect): Modifier = layout { measurable, _ ->
    val placeable = measurable.measure(
        Constraints.fixed(rect.width.coerceAtLeast(0f).dp.roundToPx(), rect.height.coerceAtLeast(0f).dp.roundToPx()),
    )
    layout(0, 0) { placeable.place(rect.left.dp.roundToPx(), rect.top.dp.roundToPx()) }
}

/// A frame that animates to each new target with `spec`, as a SwiftUI
/// transaction animates a view's frame, or follows it at once while nothing
/// shows it.
@Composable
private fun animatedRect(target: Rect, spec: AnimationSpec<Rect>, animates: Boolean): Rect {
    val rect = remember { Animatable(target, Rect.VectorConverter) }
    LaunchedEffect(target, animates) {
        if (animates) rect.animateTo(target, spec) else rect.snapTo(target)
    }
    return rect.value
}

@Composable
private fun animatedFloat(target: Float, spec: AnimationSpec<Float>, animates: Boolean): Float {
    val value = remember { Animatable(target) }
    LaunchedEffect(target, animates) {
        if (animates) value.animateTo(target, spec) else value.snapTo(target)
    }
    return value.value
}

// MARK: Layout

private fun frames(
    width: Float,
    height: Float,
    safeTop: Float,
    safeBottom: Float,
    origin: androidx.compose.ui.geometry.Offset,
    anchors: AttachmentMenuAnchors,
): AttachmentMenuFrames {
    val local = { rect: Rect -> rect.translate(-origin) }
    val button = local(anchors.button)
    val composer = if (anchors.composer == Rect.Zero) button else local(anchors.composer)
    val menu = local(anchors.menu)

    // The overlay spans the whole screen, its safe areas included.
    val cardHeight = min(max(height * 0.5f, 340f), height - safeTop - safeBottom - 220f)
    val card = Rect(
        AttachmentMenuOverlay.cardInset,
        height - AttachmentMenuOverlay.cardInset - cardHeight,
        width - AttachmentMenuOverlay.cardInset,
        height - AttachmentMenuOverlay.cardInset,
    )
    val reservation = max(cardHeight + AttachmentMenuOverlay.cardInset + AttachmentMenuOverlay.composerGap - safeBottom, 0f)
    // Above the composer as the card lifts it.
    val scrim = Rect(0f, 0f, width, max(composer.top - reservation - 6f, 0f))
    val photosRow = anchors.rows[AttachmentMenuItem.photos]?.let(local) ?: Rect(menu.left, menu.center.y, menu.right, menu.center.y)
    val photosSeed = Rect(
        menu.left + AttachmentMenuMetrics.padding,
        photosRow.center.y,
        menu.left + AttachmentMenuMetrics.padding + max(menu.width - AttachmentMenuMetrics.padding * 2, 0f),
        photosRow.center.y,
    )
    return AttachmentMenuFrames(button = button, menu = menu, photosSeed = photosSeed, card = card, scrim = scrim, reservation = reservation)
}

@Composable
private fun PickerControls(
    filter: InlinePhotoFilter,
    includesVideos: Boolean,
    onFilter: (InlinePhotoFilter) -> Unit,
    onBack: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().padding(horizontal = 14.dp).padding(bottom = 14.dp), contentAlignment = Alignment.BottomStart) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Box(
                Modifier
                    .size(44.dp)
                    .glassEffect(GlassSurface.Circle, interactive = true)
                    .clickable(onClick = onBack)
                    .semantics {
                        contentDescription = "Back"
                        stateDescription = "Returns to attachment options"
                    },
                contentAlignment = Alignment.Center,
            ) {
                SystemImage("chevron.left", contentDescription = null, tint = SystemColors.label, modifier = Modifier.symbolSize(TextStyles.body.semibold))
            }
            Spacer(Modifier.weight(1f).widthIn(min = 12.dp))
            Box {
                Row(
                    Modifier
                        .heightIn(min = 44.dp)
                        .glassEffect(GlassSurface.Capsule, interactive = true)
                        .clickable { open = true }
                        .padding(horizontal = 16.dp)
                        .semantics(mergeDescendants = true) {
                            contentDescription = "Show"
                            stateDescription = filter.title
                        },
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(filter.title, style = TextStyles.subheadline.semibold, color = SystemColors.label)
                    SystemImage("chevron.up.chevron.down", contentDescription = null, tint = SystemColors.secondaryLabel, modifier = Modifier.symbolSize(TextStyles.caption2.semibold))
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    InlinePhotoFilter.visible(includesVideos).forEach { item ->
                        DropdownMenuItem(
                            text = { Text(item.title) },
                            leadingIcon = { SystemImage(item.systemImage, contentDescription = null) },
                            onClick = {
                                onFilter(item)
                                open = false
                            },
                        )
                    }
                }
            }
        }
    }
}

/// Dismisses the menu on a tap or a downward drag.
@Composable
fun AttachmentMenuDismissArea(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    extent: Float? = null,
) {
    Box(
        modifier
            .then(if (extent != null) Modifier.requiredSize(extent.dp) else Modifier)
            .pointerInput(onDismiss) { detectTapGestures { onDismiss() } }
            .pointerInput(onDismiss) {
                var travel = 0f
                val tracker = VelocityTracker()
                detectVerticalDragGestures(
                    onDragStart = {
                        travel = 0f
                        tracker.resetTracking()
                    },
                    onVerticalDrag = { change, amount ->
                        tracker.addPosition(change.uptimeMillis, change.position)
                        travel += amount / density
                    },
                    onDragEnd = {
                        val predicted = travel + tracker.calculateVelocity().y / density * 0.25f
                        if (travel > 30 || predicted > 120) onDismiss()
                    },
                )
            }
            .clearAndSetSemantics {},
    )
}

/// VoiceOver and Switch Control's counterparts: TalkBack, and switch access.
private fun assistiveTechnologyEnabled(context: Context): Boolean {
    val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
    if (!manager.isEnabled) return false
    return manager.isTouchExplorationEnabled ||
        manager.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC).isNotEmpty()
}

@Composable
internal fun AttachmentMenuSurface(
    phase: AttachmentMenuPhase = AttachmentMenuPhase.menu,
    items: List<AttachmentMenuItem> = AttachmentMenuItem.entries,
    modifier: Modifier = Modifier,
) {
    val anchors = remember { AttachmentMenuAnchors() }
    val press = remember { AttachmentMenuPress() }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .attachmentMenuAnchor(anchors::composer)
                .glassEffect(RoundedCornerShape(28.dp)),
        ) {
            Box(Modifier.padding(6.dp)) {
                AttachmentPickerButton(
                    isLoading = false,
                    isOpen = phase != AttachmentMenuPhase.closed,
                    anchors = anchors,
                    press = press,
                    onToggle = {},
                    onChoose = {},
                )
            }
        }
        Box(Modifier.padding(horizontal = 20.dp, vertical = 16.dp).fillMaxWidth().heightIn(min = 56.dp)) {
            AttachmentMenuPanel(
                isOpen = phase == AttachmentMenuPhase.menu,
                items = items,
                anchors = anchors,
                press = press,
                onChoose = {},
                onPaste = {},
                onDismiss = {},
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}
