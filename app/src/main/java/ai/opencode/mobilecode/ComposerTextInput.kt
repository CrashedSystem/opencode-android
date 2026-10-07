@file:PortedFrom("OpenCode/ComposerTextInput.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.SystemColors
import ai.opencode.mobilecode.system.TextStyles
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

@Composable
fun ComposerTextInput(
    text: String,
    onText: (String) -> Unit,
    isFocused: Boolean,
    onFocus: (Boolean) -> Unit,
    expanded: Boolean,
    onImagePaste: (List<NSItemProvider>) -> Unit,
    sendTransition: ComposerSendTransition? = null,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var hasFocus by remember { mutableStateOf(false) }
    // `@FocusState`: the binding moves the keyboard as well as reporting it.
    LaunchedEffect(isFocused) {
        if (isFocused && !hasFocus) runCatching { focusRequester.requestFocus() }
        if (!isFocused && hasFocus) focusManager.clearFocus()
    }
    var value by remember(text) { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    if (value.text != text) value = TextFieldValue(text, TextRange(text.length))
    val lineHeight = 22.dp
    val lines = if (expanded) 6 else 1
    val inset = if (expanded) ComposerTextInput.expandedTopInset else 0.dp
    BasicTextField(
        value = value,
        onValueChange = { next ->
            value = next
            onText(next.text)
        },
        modifier = modifier
            .focusRequester(focusRequester)
            .onFocusChanged {
                hasFocus = it.isFocused
                if (it.isFocused != isFocused) onFocus(it.isFocused)
            }
            .heightIn(min = lineHeight + inset, max = lineHeight * lines + inset)
            .padding(PaddingValues(top = inset))
            .onGloballyPositioned { coordinates -> sendTransition?.input = coordinates.boundsInRoot() }
            .semantics { contentDescription = "Message" },
        textStyle = TextStyles.body.copy(color = SystemColors.label),
        cursorBrush = SolidColor(SystemColors.tint),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
        keyboardActions = KeyboardActions(),
        maxLines = lines,
        onTextLayout = { layout ->
            val width = (0 until layout.lineCount).maxOfOrNull { layout.getLineRight(it) } ?: 0f
            sendTransition?.usedText = Rect(0f, 0f, width, layout.size.height.toFloat())
        },
    )
    sendTransition?.text = text
}

object ComposerTextInput {
    val expandedTopInset = 14.dp
}

class ImagePasteTextView {
    var text: String = ""
    var imagePasteHandler: ((List<NSItemProvider>) -> Unit)? = null
    var pinsToEnd: Boolean = false

    fun paste(itemProviders: List<NSItemProvider>) {
        val images = imageProviders(itemProviders)
        if (images.isEmpty()) return
        imagePasteHandler?.invoke(images)
    }

    fun canPaste(itemProviders: List<NSItemProvider>): Boolean = imageProviders(itemProviders).isNotEmpty()

    private fun imageProviders(providers: List<NSItemProvider>): List<NSItemProvider> =
        providers.filter { provider ->
            provider.canLoadImage || provider.registeredTypeIdentifiers.any { it.startsWith("image/") || it == "public.image" || it == "public.png" || it == "public.jpeg" }
        }
}

/// A single, interruptible visual handoff. The real text remains owned by the
/// composer and transcript; these copies exist only for the send animation.
@Stable
class ComposerSendTransition {
    var text: String = ""
    /// The composer's text in root coordinates, laid out as the field shows it.
    internal var input: Rect = Rect.Zero
    internal var usedText: Rect = Rect.Zero
    internal var source by mutableStateOf<Source?>(null)
        private set
    internal var landing by mutableStateOf<Landing?>(null)
        private set
    internal var progress by mutableFloatStateOf(0f)
        private set
    internal var hiddenItemID by mutableStateOf<String?>(null)
        private set
    private var origin: Rect = Rect.Zero
    private var landingItemID = ""
    private val markers = HashMap<String, Rect>()
    /// How far the follower moved the list since a marker last reported.
    private var unreportedMove = 0f
    val isPrepared: Boolean get() = source != null

    internal data class Source(val text: String, val frame: Rect)

    internal data class Landing(val text: String, val size: Size, val destination: Rect)

    fun prepare(reduceMotion: Boolean) {
        cancel()
        if (reduceMotion || text.isEmpty() || input.isEmpty) return
        val textBounds = usedText.translate(input.topLeft).intersect(input)
        if (textBounds.isEmpty) return
        source = Source(text, textBounds)
    }

    /// Starts the flight into the bubble for `itemID`. The caller steps it
    /// every frame, so it lands wherever the list has carried the bubble.
    fun beginLanding(itemID: String): Boolean {
        val source = source ?: return false
        val marker = markers[itemID] ?: return false
        origin = source.frame
        landingItemID = itemID
        val destination = marker.translate(0f, -unreportedMove)
        landing = Landing(source.text, destination.size, destination)
        hide(itemID)
        progress = 0f
        LabMarks.mark("send_flight_started")
        return true
    }

    /// One frame of the flight at `progress` (0...1) toward the bubble's
    /// current place; a bubble that left the screen keeps its last place.
    fun stepLanding(progress: Float) {
        val landing = landing ?: return
        markers[landingItemID]?.let { this.landing = landing.copy(destination = it.translate(0f, -unreportedMove)) }
        this.progress = progress
    }

    fun finishLanding() {
        if (landing == null) return cancel()
        cancel()
        LabMarks.mark("send_flight_finished")
    }

    /// The bubble stays invisible from its first frame until the flight lands.
    fun hide(itemID: String) {
        hiddenItemID = itemID
    }

    /// The follower moved the list by `delta` this frame, before the bubble's
    /// marker can report its new place.
    fun listMoved(delta: Float) {
        if (landing == null && hiddenItemID == null) return
        unreportedMove += delta
    }

    internal fun markerPositioned(itemID: String, bounds: Rect) {
        markers[itemID] = bounds
        unreportedMove = 0f
    }

    internal fun sourceFrame(): Rect = interpolate(origin, landing?.let { textRect(it.destination) } ?: origin, progress)

    internal fun landingFrame(): Rect {
        val landing = landing ?: return Rect.Zero
        return interpolate(bubbleRect(origin), landing.destination, progress)
    }

    fun cancel() {
        hiddenItemID = null
        source = null
        landing = null
        progress = 0f
        markers.clear()
        unreportedMove = 0f
    }

    internal var bubbleInsets: Offset = Offset.Zero

    private fun textRect(destination: Rect) = Rect(
        destination.left + bubbleInsets.x,
        destination.top + bubbleInsets.y,
        destination.right - bubbleInsets.x,
        destination.bottom - bubbleInsets.y,
    )

    private fun bubbleRect(text: Rect) = Rect(
        text.left - bubbleInsets.x,
        text.top - bubbleInsets.y,
        text.right + bubbleInsets.x,
        text.bottom + bubbleInsets.y,
    )

    companion object {
        private fun interpolate(from: Rect, to: Rect, progress: Float) = Rect(
            from.left + (to.left - from.left) * progress,
            from.top + (to.top - from.top) * progress,
            from.right + (to.right - from.right) * progress,
            from.bottom + (to.bottom - from.bottom) * progress,
        )
    }
}

val LocalComposerSendTransition = staticCompositionLocalOf<ComposerSendTransition?> { null }

/// Marks the bubble a send flight lands on, and keeps it hidden while the
/// flight carries its text there.
@Composable
fun Modifier.sendLandingProbe(itemID: String): Modifier {
    val transition = LocalComposerSendTransition.current ?: return this
    return this
        .onGloballyPositioned { transition.markerPositioned(itemID, it.boundsInRoot()) }
        .graphicsLayer { alpha = if (transition.hiddenItemID == itemID) 0f else 1f }
}

/// The flight's two copies, drawn above everything: the composer's text
/// shrinking into the bubble, and the bubble growing out of it.
@Composable
fun SendFlightOverlay(transition: ComposerSendTransition, modifier: Modifier = Modifier) {
    val source = transition.source ?: return
    val density = LocalDensity.current
    transition.bubbleInsets = with(density) {
        Offset(TranscriptMetrics.contentGutter.dp.toPx(), TranscriptMetrics.bubbleVertical.dp.toPx())
    }
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
    Box(modifier.fillMaxSize().onGloballyPositioned { overlayOrigin = it.positionInRoot() }) {
        val landing = transition.landing
        if (landing != null) {
            val size = with(density) { DpSize(landing.size.width.toDp(), landing.size.height.toDp()) }
            Box(
                Modifier
                    .size(size)
                    .graphicsLayer {
                        val frame = transition.landingFrame()
                        transformOrigin = TransformOrigin(0f, 0f)
                        translationX = frame.left - overlayOrigin.x
                        translationY = frame.top - overlayOrigin.y
                        scaleX = if (landing.size.width > 0f) frame.width / landing.size.width else 1f
                        scaleY = if (landing.size.height > 0f) frame.height / landing.size.height else 1f
                        alpha = transition.progress
                    }
                    .background(SystemColors.secondarySystemBackground, RoundedCornerShape(TranscriptMetrics.bubbleCornerRadius.dp))
                    .padding(horizontal = TranscriptMetrics.contentGutter.dp, vertical = TranscriptMetrics.bubbleVertical.dp),
            ) {
                Text(landing.text, style = TextStyles.body.copy(lineHeight = (TranscriptMetrics.bodyLine + 2).sp), color = SystemColors.label)
            }
        }
        val width = with(density) { source.frame.width.toDp() }
        Box(
            Modifier
                .requiredWidthIn(min = width, max = width)
                .graphicsLayer {
                    val frame = transition.sourceFrame()
                    transformOrigin = TransformOrigin(0f, 0f)
                    translationX = frame.left - overlayOrigin.x
                    translationY = frame.top - overlayOrigin.y
                    scaleX = if (source.frame.width > 0f) frame.width / source.frame.width else 1f
                    scaleY = if (source.frame.height > 0f) frame.height / source.frame.height else 1f
                    alpha = 1f - transition.progress
                },
        ) {
            Text(source.text, style = TextStyles.body, color = SystemColors.label, maxLines = 6)
        }
    }
}
