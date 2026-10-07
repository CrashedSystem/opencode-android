@file:PortedFrom("OpenCode/AttachmentPicker.swift", "a9fd832")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.SystemColors
import ai.opencode.mobilecode.system.SystemImage
import ai.opencode.mobilecode.tca.DependencyValues
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import kotlin.reflect.KMutableProperty0
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/// The composer's + button. A tap opens `AttachmentMenuPanel`, which grows out
/// of the button's glass; touching down and holding opens it too, and the
/// finger can slide across the rows and release on one to choose it. While
/// the menu is open the + turns into a × that closes it.
object AttachmentPickerButton {
    const val maxPhotoSelection = 4

    /// How long a touch rests on + before the menu opens under it.
    val holdDuration: Duration = 250.milliseconds

    /// Moving this far before the hold elapses opens the menu at once.
    internal const val dragDistance = 10f
}

@Composable
fun AttachmentPickerButton(
    isLoading: Boolean,
    isOpen: Boolean,
    anchors: AttachmentMenuAnchors,
    press: AttachmentMenuPress,
    lift: Float = 0f,
    onToggle: () -> Unit,
    onChoose: (AttachmentMenuItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalReduceMotion.current
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    var hold by remember { mutableStateOf<Job?>(null) }
    var isTouching by remember { mutableStateOf(false) }
    val opened by rememberUpdatedState(isOpen)
    val toggle by rememberUpdatedState(onToggle)
    val choose by rememberUpdatedState(onChoose)
    val currentLift by rememberUpdatedState(lift)
    val opacity by animateFloatAsState(if (isTouching && !isOpen) 0.55f else 1f, tween(150), label = "touch")

    /// The button where it is now: `anchors` keeps its resting frame while
    /// the picker card lifts the composer.
    fun liveButton(): Rect = anchors.button.translate(0f, -currentLift)

    fun openByHold() {
        hold?.cancel()
        hold = null
        if (press.holdElapsed()) toggle()
    }

    fun finishTouch() {
        hold?.cancel()
        hold = null
        isTouching = false
    }

    fun cancelPress() {
        if (!isTouching) return
        finishTouch()
        press.cancelled()
    }

    Box(
        modifier
            .alpha(opacity)
            .size(44.dp)
            // Sliding from + to the rows must not open the drawer.
            .excludedFromDrawerPan()
            .attachmentMenuAnchor(anchors::button, lift)
            // The composer's tap-to-focus must not take the touch: the button
            // consumes it.
            .then(
                if (isLoading) {
                    Modifier
                } else {
                    Modifier.pointerInput(press) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            // In the session screen's points, like the anchors.
                            fun location(position: Offset) = liveButton().topLeft + position / density
                            isTouching = true
                            press.began(isOpen = opened)
                            if (!opened) {
                                hold = scope.launch {
                                    delay(AttachmentPickerButton.holdDuration)
                                    openByHold()
                                }
                            }
                            press.moved(to = location(down.position), button = liveButton(), rows = anchors.rows)
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                // Resets however the touch ends, so a press the system takes (the
                                // drawer's pan) still finishes.
                                if (change == null || (change.pressed && change.isConsumed)) {
                                    cancelPress()
                                    return@awaitEachGesture
                                }
                                change.consume()
                                if (!change.pressed) {
                                    if (!isTouching) return@awaitEachGesture
                                    finishTouch()
                                    when (val outcome = press.ended(at = location(change.position), button = liveButton(), rows = anchors.rows)) {
                                        AttachmentMenuPress.Outcome.none -> Unit
                                        AttachmentMenuPress.Outcome.toggle, AttachmentMenuPress.Outcome.close -> toggle()
                                        is AttachmentMenuPress.Outcome.choose -> choose(outcome.item)
                                    }
                                    return@awaitEachGesture
                                }
                                if ((change.position - down.position).getDistance() / density > AttachmentPickerButton.dragDistance) {
                                    openByHold()
                                }
                                press.moved(to = location(change.position), button = liveButton(), rows = anchors.rows)
                            }
                        }
                    }
                },
            )
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = if (isLoading) "Adding attachment" else if (isOpen) "Close" else "Add attachment"
                testTag = "attachment-menu-button"
                onClick {
                    if (!isLoading) toggle()
                    true
                }
                if (isLoading) disabled()
            },
        contentAlignment = Alignment.Center,
    ) {
        AttachmentPickerGlyph(isLoading = isLoading, isOpen = isOpen, reduceMotion = reduceMotion)
    }
}

/// One glyph that turns 45° into a ×; under Reduce Motion + and × cross-fade.
@Composable
private fun AttachmentPickerGlyph(isLoading: Boolean, isOpen: Boolean, reduceMotion: Boolean) {
    if (isLoading) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = SystemColors.label, strokeWidth = 2.dp)
    } else if (reduceMotion) {
        val shown by animateFloatAsState(if (isOpen) 1f else 0f, tween(200), label = "glyph")
        Box(contentAlignment = Alignment.Center) {
            SystemImage("plus", contentDescription = null, tint = SystemColors.label, modifier = Modifier.size(20.dp).alpha(1 - shown))
            SystemImage("xmark", contentDescription = null, tint = SystemColors.label, modifier = Modifier.size(20.dp).alpha(shown))
        }
    } else {
        val rotation by animateFloatAsState(
            if (isOpen) 45f else 0f,
            if (isOpen) AttachmentMenuMotion.open(reduceMotion = false) else AttachmentMenuMotion.close(reduceMotion = false),
            label = "glyph",
        )
        SystemImage("plus", contentDescription = null, tint = SystemColors.label, modifier = Modifier.size(20.dp).rotate(rotation))
    }
}

/// What the menu offers. Paste is a system paste control, so it can only be
/// tapped, never chosen by releasing a drag over it.
enum class AttachmentMenuItem {
    camera,
    photos,
    files,
    paste,
    ;

    val title: String
        get() = when (this) {
            camera -> "Camera"
            photos -> "Photos"
            files -> "Files"
            paste -> "Paste"
        }

    val systemImage: String
        get() = when (this) {
            camera -> "camera"
            photos -> "photo.on.rectangle"
            files -> "folder"
            paste -> "doc.on.clipboard"
        }

    val isDragSelectable: Boolean get() = this != paste
}

/// A touch on + and what it does: opening by hold, sliding over rows with a
/// highlight, and releasing to choose, cancel, or leave the menu open. It
/// plays the menu's haptics, each once: a light impact as the menu opens, a
/// selection tick as the finger reaches each row, and a light impact as a
/// release chooses one. Closing plays nothing, like the system's menus.
class AttachmentMenuPress {
    sealed interface Outcome {
        /// Nothing changes: a release away from the rows and the button.
        data object none : Outcome

        /// A plain tap: opens a closed menu, closes an open one.
        data object toggle : Outcome

        /// Released back on the × after sliding away from it.
        data object close : Outcome

        data class choose(val item: AttachmentMenuItem) : Outcome
    }

    /// The row under the finger.
    var highlighted by mutableStateOf<AttachmentMenuItem?>(null)
        private set

    /// Captured when the press is made, as `@Dependency` is.
    private val haptics = DependencyValues.current.haptics
    private var touch: Touch? = null

    private data class Touch(
        val wasOpen: Boolean,
        val openedByHold: Boolean = false,
        val leftButton: Boolean = false,
    )

    /// The menu opened, by a tap, a hold, or assistive technology.
    fun menuOpened() {
        haptics.impact()
    }

    fun began(isOpen: Boolean) {
        touch = Touch(wasOpen = isOpen)
        highlighted = null
    }

    /// The hold elapsed, or the finger moved before it did. Returns whether
    /// the menu should open now: once per touch, and only if it was closed.
    fun holdElapsed(): Boolean {
        val touch = touch ?: return false
        if (touch.wasOpen || touch.openedByHold) return false
        this.touch = touch.copy(openedByHold = true)
        return true
    }

    fun moved(to: Offset, button: Rect, rows: Map<AttachmentMenuItem, Rect>) {
        val touch = touch ?: return
        if (!(touch.wasOpen || touch.openedByHold)) return
        if (!button.inflate(buttonSlop).contains(to)) {
            this.touch = touch.copy(leftButton = true)
        }
        val item = item(at = to, rows = rows)
        if (item == highlighted) return
        highlighted = item
        if (item != null) haptics.selection()
    }

    fun ended(at: Offset, button: Rect, rows: Map<AttachmentMenuItem, Rect>): Outcome {
        val touch = touch
        this.touch = null
        highlighted = null
        if (touch == null) return Outcome.none
        val isMenuUp = touch.wasOpen || touch.openedByHold
        val item = item(at = at, rows = rows)
        if (isMenuUp && touch.leftButton && item != null) {
            haptics.impact()
            return Outcome.choose(item)
        }
        if (!button.inflate(buttonSlop).contains(at)) return Outcome.none
        if (!touch.openedByHold) return Outcome.toggle
        // Held open and released in place: the menu stays for a tap.
        return if (touch.leftButton) Outcome.close else Outcome.none
    }

    fun cancelled() {
        touch = null
        highlighted = null
    }

    companion object {
        /// Slop around the 44 pt button before the finger counts as having left it.
        private const val buttonSlop = 8f

        fun item(at: Offset, rows: Map<AttachmentMenuItem, Rect>): AttachmentMenuItem? =
            rows.entries.firstOrNull { it.key.isDragSelectable && it.value.contains(at) }?.key
    }
}

/// Frames the attachment menu lays itself out against, in the session
/// screen's points. Measured after layout rather than read as preferences:
/// the overlay must not depend on the composer's layout while the
/// composer's text view takes focus mid-update.
class AttachmentMenuAnchors {
    /// The + button the menu grows from and shrinks back into.
    var button by mutableStateOf(Rect.Zero)

    /// The composer surface the menu rests above, where it rests when the
    /// picker card isn't lifting it.
    var composer by mutableStateOf(Rect.Zero)

    /// The menu panel, where the photo-picker card morphs from and back to.
    var menu by mutableStateOf(Rect.Zero)

    /// Each row of the menu, for choosing by sliding a finger from +.
    val rows: Map<AttachmentMenuItem, Rect>
        get() = rowOffsets.mapValues { it.value.translate(menu.topLeft) }

    /// Where each row rests within the menu panel. Compose lays the rows out
    /// where they are drawn, mid-spring included, so their resting frames
    /// come from the resting panel, `menu`, and their place in it.
    var rowOffsets by mutableStateOf(emptyMap<AttachmentMenuItem, Rect>())

    companion object {
        const val space = "attachment-menu"
    }
}

/// `lift` is how far the picker card currently raises the measured view.
/// Recording the resting frame keeps the menu's targets still while the
/// composer travels, so a morph never retargets mid-flight. Frames are in
/// points from the root, the session screen's `AttachmentMenuAnchors.space`.
@Composable
fun Modifier.attachmentMenuAnchor(
    keyPath: KMutableProperty0<Rect>,
    lift: Float = 0f,
): Modifier {
    val density = LocalDensity.current.density
    return onGloballyPositioned { coordinates ->
        val origin = coordinates.positionInRoot() / density
        val size = coordinates.size
        keyPath.set(Rect(origin.x, origin.y + lift, origin.x + size.width / density, origin.y + lift + size.height / density))
    }
}

/// Re-encoding already drops EXIF before upload; this makes the picker's own
/// footer say so instead of "Location Is Included".
@Composable
fun StripPhotoLocation(content: @Composable () -> Unit) {
    content()
}
