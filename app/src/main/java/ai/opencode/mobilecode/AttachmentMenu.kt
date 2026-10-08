@file:PortedFrom("OpenCode/AttachmentMenu.swift", "4482259")

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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.runtime.SideEffect
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/// Where the attachment menu is. The menu and the photo card are one
/// floating glass surface (`AttachmentMenuOverlay`) above the composer.
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
    /// The composer's, so the menu and the card read as the same glass.
    const val cornerRadius = 28f

    /// Between the panel's edge and its rows; the highlight is concentric.
    const val padding = 6f

    /// Between the composer's top edge and the menu's or card's bottom edge.
    const val composerGap = 10f
    const val minimumWidth = 210f

    /// The glass the menu grows out of and shrinks back into, above the +.
    const val seedSize = 34f

    /// Between the card's edge and its controls and grid.
    const val cardInset = 8f
    const val cardHeaderHeight = 44f

    /// The card stops short of the session header.
    const val cardTopClearance = 64f
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

// MARK: - Rows

/// The menu's rows. A hidden copy measures where each row rests
/// (`onRowFrame`), so choosing by a slide from + never depends on the glass
/// mid-flight.
@Composable
fun AttachmentMenuRows(
    items: List<AttachmentMenuItem>,
    /// Where the + glyph's center sits from the panel's leading edge; the
    /// rows' icons line up under it.
    buttonCenterX: Float,
    highlighted: AttachmentMenuItem? = null,
    /// Set on the measuring copy, which draws plain labels: each row's frame
    /// in the rows' own space.
    onRowFrame: ((AttachmentMenuItem, Rect) -> Unit)? = null,
    onChoose: (AttachmentMenuItem) -> Unit = {},
    onPaste: (List<NSItemProvider>) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val rowHeight = with(density) { 48.sp.toDp() }
    val iconWidth = with(density) { 26.sp.toDp() }
    val iconInset = max(buttonCenterX - AttachmentMenuMetrics.padding - iconWidth.value / 2, 10f)
    val space = remember { RowsSpace() }
    Column(
        modifier
            .widthIn(min = AttachmentMenuMetrics.minimumWidth.dp)
            .width(IntrinsicSize.Max)
            .padding(AttachmentMenuMetrics.padding.dp)
            .onGloballyPositioned { space.coordinates = it },
    ) {
        items.forEach { item ->
            val label = AttachmentMenuRowLabel(item = item, iconWidth = iconWidth.value, inset = iconInset, height = rowHeight.value)
            if (onRowFrame != null) {
                AttachmentMenuRowContent(
                    label,
                    Modifier.onGloballyPositioned { row ->
                        val rows = space.coordinates?.takeIf { it.isAttached } ?: return@onGloballyPositioned
                        // In the padded rows' own space, as SwiftUI's named space.
                        val origin = rows.localPositionOf(row, Offset.Zero) / density.density
                        val padding = AttachmentMenuMetrics.padding
                        onRowFrame(item, Rect(origin.x + padding, origin.y + padding, origin.x + padding + row.size.width / density.density, origin.y + padding + row.size.height / density.density))
                    },
                )
            } else if (item == AttachmentMenuItem.paste) {
                AttachmentPasteRow(label = label, onPaste = onPaste)
            } else {
                AttachmentMenuRowButton(label = label, isHighlighted = highlighted == item) { onChoose(item) }
            }
        }
    }
}

private class RowsSpace {
    var coordinates: LayoutCoordinates? = null
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
private fun AttachmentMenuRowButton(label: AttachmentMenuRowLabel, isHighlighted: Boolean, action: () -> Unit) {
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
            .clickable(interactionSource = interaction, indication = null, onClick = action)
            .semantics { contentDescription = label.item.title },
    )
}

/// Paste reads the clipboard when tapped; Android shows no paste alert. iOS's
/// paste control insists on drawing itself, opaque, with its icon and title
/// centered, so the row draws the same.
@Composable
private fun AttachmentPasteRow(label: AttachmentMenuRowLabel, onPaste: (List<NSItemProvider>) -> Unit) {
    val context = LocalContext.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    /// Opaque, as the control requires: about the rows' highlight over glass.
    val fill = if (dark) Color(0.2f, 0.2f, 0.2f) else Color(0.92f, 0.92f, 0.92f)
    val shape = RoundedCornerShape(AttachmentMenuRowLabel.highlightRadius.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = label.height.dp)
            .background(fill, shape)
            .clip(shape)
            .clickable {
                val providers = clipboardImages(context)
                if (providers.isNotEmpty()) onPaste(providers)
            }
            .semantics(mergeDescendants = true) { contentDescription = label.item.title },
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SystemImage(label.item.systemImage, contentDescription = null, tint = SystemColors.label, modifier = Modifier.symbolSize(TextStyles.body))
        Text(label.item.title, style = TextStyles.body, color = SystemColors.label, maxLines = 1)
    }
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

// MARK: - Menu and photo card

private data class AttachmentMenuFrames(
    /// The overlay's origin in `AttachmentMenuAnchors.space`.
    val origin: Offset,
    val button: Rect,
    val seed: Rect,
    val menu: Rect,
    val card: Rect,
    /// The picker's place inside the card, below its controls.
    val grid: Rect,
    /// A sliver across the Photos row: the picker opens out of it and
    /// folds back into it.
    val photosSeed: Rect,
)

private const val gridRadius = AttachmentMenuMetrics.cornerRadius - AttachmentMenuMetrics.cardInset

/// The attachment menu and the photo card: one floating glass surface drawn
/// over the session screen, always above the composer, so the + (a × while
/// it's open) stays visible and the composer and transcript never move.
/// Closed, nothing is drawn. Opening grows the glass up out of a seed just
/// above the + into the menu; Photos grows it on into the card, which frames
/// the inline system picker; Back shrinks it onto the menu and closing
/// shrinks it into the seed. The glass is the composer's, on its own, never
/// merged with the composer's glass, and is placed by layout, never by an
/// offset. Under Reduce Motion the menu and the card cross-fade in place.
@Composable
fun AttachmentMenuOverlay(
    phase: Binding<AttachmentMenuPhase>,
    anchors: AttachmentMenuAnchors,
    press: AttachmentMenuPress,
    selection: InlinePhotoSelection,
    items: List<AttachmentMenuItem>,
    /// Videos upload as files, so they appear only when the server takes uploads.
    includesVideos: Boolean,
    onChoose: (AttachmentMenuItem) -> Unit,
    onPaste: (List<NSItemProvider>) -> Unit,
    onPhotos: (List<PhotosPickerItem>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalReduceMotion.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val assistive = assistiveTechnologyEnabled(context)
    var filter by remember { mutableStateOf(InlinePhotoFilter.all) }
    /// Keeps the picker drawn while the card shrinks away from it.
    var keepsPickerDrawn by remember { mutableStateOf(false) }
    /// The open phase last shown; closing from the card shrinks the picker too.
    var openPhase by remember { mutableStateOf(AttachmentMenuPhase.menu) }
    /// The glass stays while it shrinks away into the seed.
    var glassLingers by remember { mutableStateOf(false) }
    /// The rows stay mounted while they fade; hidden rows inside the glass
    /// would still reach assistive technologies, so they don't stay longer.
    var rowsLinger by remember { mutableStateOf(false) }
    var rowsSize by remember { mutableStateOf(Size(AttachmentMenuMetrics.minimumWidth, 160f)) }
    /// Each row's frame within the rows, from the measuring copy.
    var rowFrames by remember { mutableStateOf(emptyMap<AttachmentMenuItem, Rect>()) }
    val dragOffset = remember { Animatable(0f) }
    /// A drag that carries the menu also carries a row under the finger; its
    /// release must not count as a tap.
    var isDragging by remember { mutableStateOf(false) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val current = phase.wrappedValue
    val safeTop = WindowInsets.systemBars.getTop(density) / density.density
    val safeBottom = WindowInsets.systemBars.getBottom(density) / density.density

    fun dismiss() {
        phase.wrappedValue = AttachmentMenuPhase.closed
    }

    /// The card shrinks back onto the menu.
    fun back() {
        phase.wrappedValue = AttachmentMenuPhase.menu
    }

    // The phase's own animation, as its caller's transaction sets it; the
    // composer moving under attachments or the keyboard glides.
    var previous by remember { mutableStateOf(current) }
    val spec: AnimationSpec<Float> = when {
        previous == current -> if (reduceMotion) snap() else tween(300, easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f))
        current == AttachmentMenuPhase.closed -> AttachmentMenuMotion.close(reduceMotion)
        previous == AttachmentMenuPhase.closed -> AttachmentMenuMotion.open(reduceMotion)
        else -> AttachmentMenuMotion.morph(reduceMotion)
    }
    SideEffect { previous = current }

    val drag = Modifier.pointerInput(Unit) {
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
                    dismiss()
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

    @Composable
    fun GlassPanel(frames: AttachmentMenuFrames, rect: Rect, radius: Float, glass: Boolean, showsRows: Boolean, alpha: () -> Float) {
        val shape = RoundedCornerShape(max(radius, 0f).dp)
        val rowsAlpha by animateFloatAsState(if (showsRows) 1f else 0f, tween(100), label = "rows")
        Box(
            Modifier
                .frame(rect)
                .graphicsLayer { this.alpha = alpha() }
                .then(if (glass) Modifier.glassEffect(shape) else Modifier)
                .clip(shape)
                .then(if (current == AttachmentMenuPhase.closed) Modifier else drag)
                .then(if (showsRows) Modifier.semantics { testTag = "attachment-menu" } else Modifier.clearAndSetSemantics {}),
            contentAlignment = Alignment.BottomStart,
        ) {
            AnimatedVisibility(
                visible = showsRows || (rowsLinger && !reduceMotion),
                // The rows arrive once the glass has mostly grown, and leave first.
                enter = fadeIn(tween(220, delayMillis = if (reduceMotion) 0 else 80)),
                exit = fadeOut(tween(220, delayMillis = if (reduceMotion) 0 else 80)),
            ) {
                AttachmentMenuRows(
                    items = items,
                    buttonCenterX = frames.button.center.x - frames.menu.left,
                    highlighted = press.highlighted,
                    onChoose = { item -> if (!isDragging) onChoose(item) },
                    onPaste = onPaste,
                    // One layer, so its text fades with it.
                    modifier = Modifier
                        .requiredSize(frames.menu.width.dp, frames.menu.height.dp)
                        .graphicsLayer { this.alpha = rowsAlpha },
                )
            }
        }
    }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot() / density.density },
    ) {
        val frames = frames(
            height = maxHeight.value,
            safeTop = safeTop,
            safeBottom = safeBottom,
            origin = origin,
            anchors = anchors,
            rowsSize = rowsSize,
            rowFrames = rowFrames,
        )
        // Measures the rows where nothing can see or reach them.
        AttachmentMenuRows(
            items = items,
            buttonCenterX = frames.button.center.x - frames.menu.left,
            onRowFrame = { item, frame -> if (rowFrames[item] != frame) rowFrames = rowFrames + (item to frame) },
            modifier = Modifier
                .wrapContentSize(Alignment.TopStart, unbounded = true)
                .onSizeChanged { rowsSize = Size(it.width / density.density, it.height / density.density) }
                .graphicsLayer { alpha = 0f }
                .clearAndSetSemantics {},
        )
        if (anchors.composer != Rect.Zero) {
            val isOpen = current != AttachmentMenuPhase.closed
            if (reduceMotion) {
                // The menu and the card each fade in and out in place.
                AnimatedVisibility(current == AttachmentMenuPhase.menu, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
                    GlassPanel(frames, frames.menu, AttachmentMenuMetrics.cornerRadius, glass = true, showsRows = true) { 1f }
                }
                AnimatedVisibility(current == AttachmentMenuPhase.photos, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
                    GlassPanel(frames, frames.card, AttachmentMenuMetrics.cornerRadius, glass = true, showsRows = false) { 1f }
                }
            } else {
                val target = when (current) {
                    AttachmentMenuPhase.closed -> frames.seed
                    AttachmentMenuPhase.menu -> frames.menu
                    AttachmentMenuPhase.photos -> frames.card
                }
                val rect = animatedRect(target, spec)
                val radius = animatedFloat(if (isOpen) AttachmentMenuMetrics.cornerRadius else AttachmentMenuMetrics.seedSize / 2, spec)
                // The seed is clear; the glass gathers as it grows.
                val glassAlpha by animateFloatAsState(
                    if (isOpen) 1f else 0f,
                    if (isOpen) tween(160) else tween(220, delayMillis = 100),
                    label = "glass",
                )
                GlassPanel(
                    frames,
                    rect.translate(0f, if (isOpen) dragOffset.value else 0f),
                    radius,
                    glass = isOpen || glassLingers,
                    showsRows = current == AttachmentMenuPhase.menu,
                ) { glassAlpha }
            }
            /// The system picker takes a moment to load, so it mounts (masked away)
            /// as soon as the menu opens. Its out-of-process elements can't be hidden
            /// from assistive technologies, which therefore get it only once chosen.
            val mountsPicker = photoPickerKind() == PhotoPickerKind.embedded && when (current) {
                AttachmentMenuPhase.photos -> true
                AttachmentMenuPhase.menu -> keepsPickerDrawn || !assistive
                AttachmentMenuPhase.closed -> keepsPickerDrawn
            }
            if (mountsPicker) {
                val visible = current == AttachmentMenuPhase.photos
                // With Reduce Motion the card stays put and simply fades.
                val drawn by animateFloatAsState(if (!reduceMotion || visible) 1f else 0f, tween(if (reduceMotion) 200 else 0), label = "picker")
                val grid = frames.grid
                /// Where the picker shows. It is drawn opaque from the moment the menu
                /// opens, so revealing it never waits on the system's placeholder.
                val maskTarget = when {
                    reduceMotion -> grid
                    current == AttachmentMenuPhase.photos -> grid
                    current == AttachmentMenuPhase.menu -> frames.photosSeed
                    // From the card it shrinks away into the seed with the glass.
                    openPhase == AttachmentMenuPhase.photos -> Rect(frames.seed.center, frames.seed.center)
                    else -> frames.photosSeed
                }
                val mask = animatedRect(maskTarget, spec)
                val maskRadius = animatedFloat(if (reduceMotion || current == AttachmentMenuPhase.photos) gridRadius else 0f, spec)
                // Waiting behind the menu, it parks below the screen: masking hides it
                // from sight but not from accessibility, which would find it instead
                // of the menu's rows. It parks and unparks unseen.
                val parked = if (current == AttachmentMenuPhase.menu && !keepsPickerDrawn) grid.bottom + grid.height + 200 else 0f
                val drift = if (visible) dragOffset.value else 0f
                Box(
                    Modifier
                        .frame(mask.translate(0f, drift + parked))
                        .clip(RoundedCornerShape(max(maskRadius, 0f).dp))
                        .then(if (visible) Modifier else Modifier.clearAndSetSemantics {}),
                ) {
                    Box(
                        Modifier
                            .frame(grid.translate(-mask.left, -mask.top))
                            .graphicsLayer { alpha = drawn }
                            .background(SystemColors.systemBackground)
                            .semantics { testTag = "inline-photo-picker" },
                    ) {
                        EmbeddedPhotoPickerHost(selection, filter, includesVideos, onPhotos, Modifier.fillMaxSize())
                    }
                }
            }
            val card = frames.card.translate(0f, dragOffset.value)
            val inset = AttachmentMenuMetrics.cardInset
            val header = AttachmentMenuMetrics.cardHeaderHeight
            AnimatedVisibility(
                visible = current == AttachmentMenuPhase.photos,
                modifier = Modifier.frame(Rect(card.left, card.top, card.right, card.top + header + inset * 2)),
                // They settle onto the card once it has mostly grown.
                enter = fadeIn(tween(240, delayMillis = if (reduceMotion) 0 else 160)),
                exit = fadeOut(tween(120)),
            ) {
                CardControls(
                    filter = filter,
                    includesVideos = includesVideos,
                    onFilter = { filter = it },
                    onBack = ::back,
                    modifier = Modifier.padding(inset.dp),
                )
            }
        }
        val resting = restingRows(frames, rowFrames, items)
        LaunchedEffect(resting) { anchors.rows = resting }
    }
    LaunchedEffect(current) {
        if (current != AttachmentMenuPhase.closed) {
            openPhase = current
            glassLingers = true
        }
        if (current == AttachmentMenuPhase.menu) rowsLinger = true
        if (current == AttachmentMenuPhase.photos) keepsPickerDrawn = true
    }
    LaunchedEffect(current) {
        if (current == AttachmentMenuPhase.menu || !rowsLinger) return@LaunchedEffect
        delay(400)
        rowsLinger = false
    }
    LaunchedEffect(current) {
        if (current == AttachmentMenuPhase.photos || !keepsPickerDrawn) return@LaunchedEffect
        delay(550)
        keepsPickerDrawn = false
    }
    LaunchedEffect(current) {
        if (current != AttachmentMenuPhase.closed || !glassLingers) return@LaunchedEffect
        delay(550)
        glassLingers = false
        openPhase = AttachmentMenuPhase.menu
    }
    // The system's back is the escape gesture.
    BackHandler(enabled = current != AttachmentMenuPhase.closed) { dismiss() }
}

/// SwiftUI's `.frame(width:height:)` and `.position`, in points from the
/// parent's top-leading corner: the view takes the rect whatever room the
/// parent offers, and takes none from it.
private fun Modifier.frame(rect: Rect): Modifier = layout { measurable, _ ->
    val placeable = measurable.measure(
        Constraints.fixed(rect.width.coerceAtLeast(0f).dp.roundToPx(), rect.height.coerceAtLeast(0f).dp.roundToPx()),
    )
    layout(0, 0) { placeable.place(rect.left.dp.roundToPx(), rect.top.dp.roundToPx()) }
}

/// A frame that animates to each new target with `spec`, as a SwiftUI
/// transaction animates a view's frame.
@Composable
private fun animatedRect(target: Rect, spec: AnimationSpec<Float>): Rect {
    val left = animatedFloat(target.left, spec)
    val top = animatedFloat(target.top, spec)
    val right = animatedFloat(target.right, spec)
    val bottom = animatedFloat(target.bottom, spec)
    return Rect(left, top, right, bottom)
}

@Composable
private fun animatedFloat(target: Float, spec: AnimationSpec<Float>): Float {
    val value = remember { Animatable(target) }
    LaunchedEffect(target) { value.animateTo(target, spec) }
    return value.value
}

// MARK: Layout

private fun frames(
    height: Float,
    safeTop: Float,
    safeBottom: Float,
    origin: Offset,
    anchors: AttachmentMenuAnchors,
    rowsSize: Size,
    rowFrames: Map<AttachmentMenuItem, Rect>,
): AttachmentMenuFrames {
    val local = { rect: Rect -> rect.translate(-origin) }
    val button = local(anchors.button)
    val composer = local(anchors.composer)
    val inset = AttachmentMenuMetrics.cardInset
    val header = AttachmentMenuMetrics.cardHeaderHeight
    // Everything rests on this line, a little above the composer.
    val base = composer.top - AttachmentMenuMetrics.composerGap
    val seedSize = AttachmentMenuMetrics.seedSize
    val seed = Rect(button.center.x - seedSize / 2, base - seedSize, button.center.x + seedSize / 2, base)
    val menu = Rect(composer.left, base - rowsSize.height, composer.left + min(rowsSize.width, composer.width), base)
    // About half the screen, short of the session header. The overlay spans
    // the whole screen, so its height already holds the safe areas.
    val screenHeight = height
    val cardHeight = max(min(screenHeight * 0.5f, base - AttachmentMenuMetrics.cardTopClearance - safeTop), menu.height)
    val card = Rect(composer.left, base - cardHeight, composer.right, base)
    val grid = Rect(
        card.left + inset,
        card.top + inset * 2 + header,
        card.left + inset + max(card.width - inset * 2, 0f),
        card.top + inset * 2 + header + max(card.height - inset * 3 - header, 0f),
    )
    val photosRow = rowFrames[AttachmentMenuItem.photos]?.translate(menu.topLeft) ?: Rect(menu.left, menu.center.y, menu.right, menu.center.y)
    val photosSeed = Rect(
        menu.left + AttachmentMenuMetrics.padding,
        photosRow.center.y,
        menu.left + AttachmentMenuMetrics.padding + max(menu.width - AttachmentMenuMetrics.padding * 2, 0f),
        photosRow.center.y,
    )
    return AttachmentMenuFrames(origin = origin, button = button, seed = seed, menu = menu, card = card, grid = grid, photosSeed = photosSeed)
}

/// Where each row rests while the menu is open, in the anchors' space.
private fun restingRows(frames: AttachmentMenuFrames, rowFrames: Map<AttachmentMenuItem, Rect>, items: List<AttachmentMenuItem>): Map<AttachmentMenuItem, Rect> {
    val origin = frames.origin + frames.menu.topLeft
    return rowFrames.filterKeys { it in items }.mapValues { it.value.translate(origin) }
}

/// Back and the filter, glass controls along the card's top edge.
@Composable
private fun CardControls(
    filter: InlinePhotoFilter,
    includesVideos: Boolean,
    onFilter: (InlinePhotoFilter) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val header = AttachmentMenuMetrics.cardHeaderHeight.dp
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(header)
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
                    .heightIn(min = header)
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
    val selection = remember { InlinePhotoSelection() }
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
                    rows = anchors.rows,
                    onToggle = {},
                    onChoose = {},
                )
            }
        }
        AttachmentMenuOverlay(
            phase = Binding({ phase }, {}),
            anchors = anchors,
            press = press,
            selection = selection,
            items = items,
            includesVideos = true,
            onChoose = {},
            onPaste = {},
            onPhotos = {},
        )
    }
}
