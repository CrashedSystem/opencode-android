package ai.opencode.feature.composer

import ai.opencode.server.model.OpenCodeOutgoingAttachment
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextLayoutResult
import kotlin.math.max
import kotlin.math.min

/**
 * The composer's text handed off to its bubble (iOS `ComposerSendTransition`). On iOS it
 * snapshots the field's pixels and flies them into the transcript cell (`SendLandingProbe`
 * markers). Here the field reports its laid-out text and where it sits ([ComposerTextInput]);
 * [prepare] captures the send's words, their layout and their visible rect as a [Prepared],
 * which the session screen's flight overlay draws and flies toward the bubble (it owns both
 * ends, as the transcript's list and the composer meet there). Flights overlap: each is keyed
 * by the message it lands on, and preparing the next send never touches one already in the air.
 */
@Stable
/**
 * The composer field's side of a swipe on the composer around it (iOS `ImagePasteTextView`'s
 * `isSwipeYieldedToText` and `selectionClaims(_:)`): a drag that's selecting belongs to the text,
 * so no capsule swipe acts on it.
 */
interface ComposerSwipeInput {
    /**
     * iOS `isSwipeYieldedToText`: the touch on the field is selecting
     * ([ComposerSwipeDown.textOwnsDrag]). Asked before the field has seen the finger move the
     * decision distance, it decides from the hold so far, for good.
     */
    val isSwipeYieldedToText: Boolean

    /** iOS `selectionClaims(_:)`: whether [rootPoint] (root px) is on or near the selection being made. */
    fun selectionClaims(rootPoint: Offset): Boolean

    /**
     * iOS `ImagePasteTextView.takesDoubleTap`: whether a control's tap (the model chip's) is the
     * second half of a finger's double tap on the text's last line ([ComposerDoubleTap]). If so
     * it selects the word under the first tap and the control does nothing.
     */
    fun takesDoubleTap(): Boolean = false

    /**
     * Before a send reads the draft: hands the parent any text the field holds that it hasn't
     * reported yet (the keyboard's latest commit, a composing word), so the send takes what shows.
     */
    fun flushEdits() {}

    /**
     * After a send cleared the draft: the field takes it now, not on the next composition, so the
     * keyboard's next commit lands in the empty field instead of on the sent text.
     */
    fun takeParentText() {}
}

class ComposerSendTransition {
    /** iOS `ComposerWindowTouchObserver` on the window: the screen root feeds it ([composerWindowTouches]). */
    val windowTouches = ComposerWindowTouchObserver()

    /** The next send's words as they leave the composer (window px). */
    data class Prepared(
        val text: String,
        /** The words' visible rect: what iOS snapshots (`usedRect` ∩ the field's bounds). */
        val bounds: Rect,
        /** Whether these are the composer's own laid-out words, not words laid out again where it was. */
        val fromComposer: Boolean,
        /** The words as laid out, drawn with their top-left at [origin] and clipped to [bounds]. */
        val layout: TextLayoutResult? = null,
        val origin: Offset = bounds.topLeft,
    )

    /**
     * The send's photos (iOS `images`): copies of the composer's thumbnails flying into the
     * bubble's image slots. The session's flight drives them alongside the words.
     */
    val images = SentImageFlight()

    /** The field's text and its visible bounds in the window (px), updated by [ComposerTextInput]. */
    var inputText: String = ""
        internal set
    var inputBounds: Rect? = null
        internal set

    /**
     * iOS `sendTransition.input` as the session's capsule swipe reads it (573770b6): the field
     * mounted now, set by [ComposerTextInput] while it's composed.
     */
    var input: ComposerSwipeInput? = null
        internal set

    /** The field's laid-out text and the window position (px) its layout starts at. */
    internal var textLayout: TextLayoutResult? = null
    internal var textOrigin: Offset? = null

    var prepared: Prepared? by mutableStateOf(null)
        private set

    /** iOS `isPrepared`: the next send's words or its photos wait for the list. */
    val isPrepared: Boolean get() = prepared != null || images.preparedToken != null

    /**
     * Captures the next send as it leaves the composer (iOS `prepare(reduceMotion:attachments:)`):
     * its image [attachments]' thumbnails (the New Plus Menu's sends, even under Reduce Motion,
     * where they fade in place), and its words unless motion is reduced. [window] is the root's
     * bounds (px), so a thumbnail scrolled out of the strip doesn't fly.
     */
    fun prepare(reduceMotion: Boolean, attachments: List<OpenCodeOutgoingAttachment> = emptyList(), window: Rect = Rect.Zero) {
        cancelPrepared()
        if (attachments.isNotEmpty()) images.prepare(attachments, reduceMotion, window)
        val visible = inputBounds
        val layout = textLayout
        val origin = textOrigin
        if (reduceMotion || inputText.isEmpty() || visible == null || layout == null || origin == null || layout.lineCount == 0) return
        var left = Float.MAX_VALUE
        var right = 0f
        for (line in 0 until layout.lineCount) {
            left = min(left, layout.getLineLeft(line))
            right = max(right, layout.getLineRight(line))
        }
        val used = Rect(origin.x + left, origin.y, origin.x + right, origin.y + layout.size.height)
        val bounds = used.intersect(visible)
        if (bounds.isEmpty) return
        prepared = Prepared(inputText, bounds, fromComposer = true, layout = layout, origin = origin)
    }

    /**
     * A send the composer didn't capture as it left: its words laid out as the composer lays
     * them out, starting where the field (or the dictation bar in its place) sits. [composer]
     * is in window px; [measure] lays out [text] at a maximum width (px).
     */
    fun prepare(
        text: String,
        composer: Rect,
        expandedTopInsetPx: Float,
        contentGutterPx: Float,
        measure: (String, Float) -> TextLayoutResult,
    ) {
        cancelPrepared()
        val layout = measure(text, max(composer.width - contentGutterPx * 2, 40f))
        val width = layout.size.width.toFloat()
        val height = layout.size.height.toFloat()
        // The field's first line: the capsule's top when it's tall, its middle when it's one row.
        val top = min(composer.top + expandedTopInsetPx, composer.center.y - height / 2)
        val bounds = Rect(composer.left + contentGutterPx, top, composer.left + contentGutterPx + width, top + height)
        prepared = Prepared(text, bounds, fromComposer = false, layout = layout, origin = bounds.topLeft)
    }

    /** The prepared send taken for its flight (the overlay now owns it); null when nothing was prepared. */
    fun takePrepared(): Prepared? = prepared.also { prepared = null }

    /**
     * Fades a send's capture out where it is: a send that doesn't fly. Only while it's still the
     * prepared one; its photos fade with it (iOS `dissolvePrepared`).
     */
    fun dissolvePrepared(snapshot: Prepared?) {
        if (prepared != null && prepared === snapshot) {
            images.dissolvePrepared(images.preparedToken)
            prepared = null
        }
    }

    /** Fades the next send's photos where they are (a send going to the queued pill, iOS `flyIntoPill`). */
    fun dissolvePreparedImages() {
        images.dissolvePrepared(images.preparedToken)
    }

    /** Drops the next send's capture, its photos included; flights in the air carry on. */
    fun cancelPrepared() {
        images.cancelPrepared()
        prepared = null
    }

    /** Everything, flights included (the screen is going away; iOS `cancel()`). */
    fun cancel() {
        images.cancel()
        cancelPrepared()
    }

    /** The send's clear's critical-spring ω, until the field takes it ([shrinkOnNextClear]). */
    private var pendingShrinkOmega: Double? = null

    /**
     * iOS `ImagePasteTextView.sendShrinkAnimation`: the send's clear shrinks the field on a
     * critical spring quicker than the list's arrival glide ([sendShrinkOmega]). Both start in
     * the send's update, and the glide aims at the bottom the composer leaves, so the composer
     * must land first: a slower tail would let the glide land and then pull the bubble back
     * down after it. The session calls this as it sends; [ComposerTextInput] takes it on the
     * next text change it didn't make, and animates only a field taller than one line, never
     * under reduce motion.
     */
    fun shrinkOnNextClear(omega: Double) {
        pendingShrinkOmega = omega
        if (inputIsMultiline) isShrinking = true
    }

    fun takeShrinkOmega(): Double? = pendingShrinkOmega.also { pendingShrinkOmega = null }

    /** Drops a [shrinkOnNextClear] nothing took: the send didn't go, so no clear follows. */
    fun cancelShrink() {
        pendingShrinkOmega = null
        isShrinking = false
    }

    /** Whether the field stands taller than one line (its layout keeps this current). */
    internal var inputIsMultiline = false

    /**
     * Whether a send's clear is shrinking the field, from [shrinkOnNextClear] until the field's
     * spring lands. iOS animates the field and the composer around it in the send's one
     * transaction, so the controls ride the composer's bottom edge; here the field's height
     * spring is that one animation, and the composer hugs it rather than chasing it with its
     * own resize spring (two springs in series lag: the controls would rise off the bottom
     * edge, and the composer would land after the list's glide).
     */
    var isShrinking: Boolean by mutableStateOf(false)
        private set

    /** The field's shrink landed (or never rode: a one-line field, reduce motion). */
    internal fun endShrink() {
        isShrinking = false
    }

    companion object {
        /** The label fades and settles into place over the flight's last half, finishing with it. */
        fun chromeProgress(progress: Float): Float {
            val t = min(1f, max(0f, (progress - 0.5f) / 0.5f))
            return t * t * (3 - 2 * t)
        }

        /**
         * iOS `ImagePasteTextView.sendShrinkOmega`: `ComposerTunables.sendShrinkSpeed` ×
         * `BottomSpring.arrivalOmega`. At the default 1.25, up to three frames' lag behind the
         * glide still lands first.
         */
        fun sendShrinkOmega(arrivalOmega: Double, speed: Double = ComposerTextInputTunables.SEND_SHRINK_SPEED): Double =
            arrivalOmega * speed

        fun interpolate(from: Rect, to: Rect, progress: Float) = Rect(
            from.left + (to.left - from.left) * progress,
            from.top + (to.top - from.top) * progress,
            from.right + (to.right - from.right) * progress,
            from.bottom + (to.bottom - from.bottom) * progress,
        )
    }
}
