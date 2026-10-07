@file:PortedFrom("OpenCode/SessionView.swift", "a2460c7", partial = "all but the voice call surfaces (VoiceCallComposer, voiceCallSurface, VoiceCallFailure, VoiceCallEnded), the dictation bar's DotScopeView, and ProblemReportOfferPill, whose Swift homes are not ported")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.ContentUnavailableView
import ai.opencode.mobilecode.system.GlassBackdrop
import ai.opencode.mobilecode.system.NavigationStack
import ai.opencode.mobilecode.system.NavigationTitle
import ai.opencode.mobilecode.system.SystemColors
import ai.opencode.mobilecode.system.SystemImage
import ai.opencode.mobilecode.system.SystemMenu
import ai.opencode.mobilecode.system.SystemMenuItem
import ai.opencode.mobilecode.system.SystemMenuSection
import ai.opencode.mobilecode.system.SystemSheet
import ai.opencode.mobilecode.system.SystemButton
import ai.opencode.mobilecode.system.TextStyles
import ai.opencode.mobilecode.system.TitleDisplayMode
import ai.opencode.mobilecode.system.ToolbarItem
import ai.opencode.mobilecode.system.ToolbarItemPlacement
import ai.opencode.mobilecode.system.medium
import ai.opencode.mobilecode.system.rememberHaptics
import ai.opencode.mobilecode.system.semibold
import ai.opencode.mobilecode.system.symbolSize
import ai.opencode.mobilecode.tca.AlertButton
import ai.opencode.mobilecode.tca.Binding
import ai.opencode.mobilecode.tca.Store
import ai.opencode.mobilecode.tca.alert
import ai.opencode.mobilecode.tca.binding
import ai.opencode.mobilecode.tca.observe
import ai.opencode.mobilecode.tca.task
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.format.Formatter
import android.util.LruCache
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.io.File
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/// Three stable children: input, leading controls, trailing control. Only their
/// placement changes, so keyboard focus doesn't move to a replacement field.
@Composable
private fun ComposerLayout(
    expanded: Boolean,
    input: @Composable () -> Unit,
    leading: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(contents = listOf(input, leading, trailing), modifier = modifier) { (inputs, leadings, trailings), constraints ->
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else 320.dp.roundToPx()
        val px44 = 44.dp.roundToPx()
        val trailingPlaceable = trailings.first().measure(Constraints())
        val trailingWidth = trailingPlaceable.width
        val leadingWidth = if (expanded) max(px44, width - trailingWidth - 8.dp.roundToPx()) else px44
        val leadingPlaceable = leadings.first().measure(Constraints(maxWidth = leadingWidth))
        val inputWidth = if (expanded) width - 32.dp.roundToPx() else width - 56.dp.roundToPx() - trailingWidth
        val inputPlaceable = inputs.first().measure(Constraints(minWidth = max(0, inputWidth), maxWidth = max(0, inputWidth)))
        val controls = max(px44, max(leadingPlaceable.height, trailingPlaceable.height))
        val height = if (expanded) inputPlaceable.height + 12.dp.roundToPx() + controls else max(inputPlaceable.height, controls)
        layout(width, height) {
            val controlsY = if (expanded) height - controls / 2 else height / 2
            inputPlaceable.place(
                if (expanded) 16.dp.roundToPx() else 52.dp.roundToPx(),
                if (expanded) 0 else height / 2 - inputPlaceable.height / 2,
            )
            leadingPlaceable.place(0, controlsY - leadingPlaceable.height / 2)
            trailingPlaceable.place(width - trailingWidth, controlsY - trailingPlaceable.height / 2)
        }
    }
}

sealed class ComposerTrailingControl {
    data class send(val enabled: Boolean) : ComposerTrailingControl()
    data object stop : ComposerTrailingControl()
    data object mic : ComposerTrailingControl()
    /// Starts a voice call; holds Send's place while the field is empty.
    data object call : ComposerTrailingControl()

    val symbolName: String
        get() = when (this) {
            is send -> "arrow.up"
            stop -> "stop.fill"
            mic -> "mic"
            call -> "waveform"
        }

    val foreground: Color
        @Composable get() = when (this) {
            mic -> SystemColors.secondaryLabel
            is send -> if (enabled) SystemColors.systemBackground else SystemColors.tertiaryLabel
            stop, call -> SystemColors.systemBackground
        }

    val background: Color
        @Composable get() = when (this) {
            is send -> if (enabled) SystemColors.label else Color.Transparent
            stop, call -> SystemColors.label
            mic -> Color.Transparent
        }

    val accessibilityLabel: String
        get() = when (this) {
            is send -> "Send message"
            stop -> "Stop"
            mic -> "Dictate message"
            call -> "Start voice call"
        }
}

data class ComposerControls(
    var trailing: ComposerTrailingControl,
    var secondaryMic: MicPlacement,
    var dictation: DictationControls?,
) {
    enum class MicPlacement { none, leading, trailing }
    data class DictationControls(var isFinishing: Boolean)

    companion object {
        fun make(
            isDictating: Boolean,
            isFinishing: Boolean,
            isRunning: Boolean,
            canSend: Boolean,
            expanded: Boolean,
            canCall: Boolean = false,
        ): ComposerControls {
            val dictation = if (isDictating || isFinishing) DictationControls(isFinishing = isFinishing) else null
            // Empty and idle: the call button holds Send's place and dictation sits
            // beside it. During a run Stop takes the place and the call waits.
            val trailing: ComposerTrailingControl = when {
                canSend -> ComposerTrailingControl.send(enabled = true)
                isRunning -> ComposerTrailingControl.stop
                canCall && dictation == null -> ComposerTrailingControl.call
                else -> ComposerTrailingControl.mic
            }
            val secondaryMic = when {
                dictation != null || trailing == ComposerTrailingControl.mic -> MicPlacement.none
                expanded -> MicPlacement.leading
                else -> MicPlacement.trailing
            }
            return ComposerControls(trailing = trailing, secondaryMic = secondaryMic, dictation = dictation)
        }
    }
}

@Composable
private fun ComposerControlLabel(
    systemImage: String,
    showsProgress: Boolean,
    font: androidx.compose.ui.text.TextStyle,
    foreground: Color,
    background: Color,
) {
    /// The circle grows with the glyph so large text never clips it.
    val diameter = with(LocalDensity.current) { (34 * min(fontScale, 1.35f)).dp }
    Box(Modifier.size(if (diameter > 44.dp) diameter else 44.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(diameter).background(background, CircleShape), contentAlignment = Alignment.Center) {
            if (showsProgress) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = foreground)
            } else {
                SystemImage(systemImage, null, Modifier.symbolSize(font), tint = foreground)
            }
        }
    }
}

/// Where a held mic's finger has gone. Sliding up arms lock and sliding left
/// arms cancel, each measured along one axis. The axis is chosen once the
/// finger is `slop` away and one component leads the other by `ratio`
/// (within about 35° of the axis); a diagonal arms nothing. Back inside
/// `slop`, the axis is free again. Each disarms `hysteresis` short of its
/// line, so the finger can rest on it without flickering.
class DictationHoldTracker {
    enum class Axis { up, left }

    var axis: Axis? = null
        private set
    var armed: SessionFeature.State.DictationHold.Arm? = null
        private set
    var maxDistance: Float = 0f
        private set

    fun update(translation: Offset) {
        val up = -translation.y
        val left = -translation.x
        val distance = hypot(translation.x, translation.y)
        maxDistance = max(maxDistance, distance)
        if (distance < slop) {
            axis = null
        } else if (axis == null) {
            if (up > 0 && up >= left * ratio) {
                axis = Axis.up
            } else if (left > 0 && left >= up * ratio) {
                axis = Axis.left
            }
        }
        val lock = if (axis == Axis.up) up else 0f
        val cancel = if (axis == Axis.left) left else 0f
        when (armed) {
            null ->
                if (lock >= lockDistance) {
                    armed = SessionFeature.State.DictationHold.Arm.lock
                } else if (cancel >= cancelDistance) {
                    armed = SessionFeature.State.DictationHold.Arm.cancel
                }
            SessionFeature.State.DictationHold.Arm.lock -> if (lock < lockDistance - hysteresis) armed = null
            SessionFeature.State.DictationHold.Arm.cancel -> if (cancel < cancelDistance - hysteresis) armed = null
        }
    }

    fun isTap(after: Double): Boolean = armed == null && after < tapDuration && maxDistance < tapSlop

    enum class Ending {
        /// Short and still: locks hands-free.
        tap,

        /// Let go: sends, or commits what's armed.
        release,

        /// The system took the touch: never a send.
        interrupted,
    }

    fun ending(after: Double, cancelled: Boolean): Ending {
        if (cancelled) return Ending.interrupted
        return if (isTap(after)) Ending.tap else Ending.release
    }

    companion object {
        const val slop = 14f
        const val ratio = 1.4f
        const val lockDistance = 56f
        const val cancelDistance = 96f
        const val hysteresis = 14f

        /// Shorter, stiller presses are taps.
        const val tapDuration = 0.3
        const val tapSlop = 10f
    }
}

/// The finger's travel while holding the mic, for the bar to follow.
data class DictationDrag(
    val translation: Offset = Offset.Zero,
    val axis: DictationHoldTracker.Axis? = null,
)

/// The mic, Telegram style: touch and hold to record and let go to send.
/// Sliding up arms lock (keep recording hands-free) and sliding left arms
/// cancel; letting go commits whichever is armed. A quick tap locks. The
/// finger's travel goes to `onDrag` so the recording bar can follow it; this
/// view only owns the touch.
private object HoldToDictateButton {
    fun Modifier.holdToDictate(
        onBegan: () -> Unit,
        onDrag: (DictationDrag) -> Unit,
        onArmed: (SessionFeature.State.DictationHold.Arm?) -> Unit,
        onReleased: () -> Unit,
        onLocked: () -> Unit,
        /// The system cancelled the touch: another finger, a parent that took
        /// the gesture, or the view leaving while the finger was down.
        onInterrupted: () -> Unit,
    ): Modifier = pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val start = System.nanoTime()
            val tracker = DictationHoldTracker()
            onBegan()
            // A lift ends the loop with the pointer up; anything else (the
            // pointer vanishing, another finger, cancellation) is not a lift.
            var lifted = false
            var finished = false
            fun finish(cancelled: Boolean) {
                if (finished) return
                finished = true
                val ending = tracker.ending(after = (System.nanoTime() - start) / 1e9, cancelled = cancelled)
                onDrag(DictationDrag())
                when (ending) {
                    DictationHoldTracker.Ending.tap -> onLocked()
                    DictationHoldTracker.Ending.release -> onReleased()
                    DictationHoldTracker.Ending.interrupted -> onInterrupted()
                }
            }
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.changes.size > 1) break
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        lifted = true
                        break
                    }
                    change.consume()
                    val translation = (change.position - down.position) / density
                    val wasArmed = tracker.armed
                    tracker.update(translation)
                    onDrag(DictationDrag(translation = translation, axis = tracker.axis))
                    if (tracker.armed != wasArmed) onArmed(tracker.armed)
                }
            } finally {
                finish(cancelled = !lifted)
            }
        }
    }
}

/// One recording's view-only progress through its hold. It starts over
/// with every press and when the recording ends, so nothing one hold
/// showed carries into the next.
data class DictationHoldVisuals(
    /// A sustained hold, not a tap: the circle has grown and the targets show.
    val showsGuides: Boolean = false,
    /// The lock target has landed in Stop's slot after a slide.
    val lockDropDone: Boolean = false,
) {
    fun begin() = DictationHoldVisuals()

    fun end() = DictationHoldVisuals()
}

/// Where the recording is, as both halves of the bar draw it: the bar's
/// buttons and scope inside the composer, and the hold layer's circle and
/// lock target above the composer's glass.
data class DictationBarPose(
    val hold: SessionFeature.State.DictationHold,
    val phase: SessionFeature.State.DictationPhase,
    val drag: DictationDrag,
    val reducedMotion: Boolean,
    val visuals: DictationHoldVisuals,
    val isLeaving: Boolean = false,
) {
    val showsHoldGuides: Boolean get() = visuals.showsGuides
    val lockDropDone: Boolean get() = visuals.lockDropDone

    /// The circle under the finger at its full size, rather than the mic's.
    val showsGrownCircle: Boolean get() = showsHoldGuides && !isLeaving && !isLocked

    val isLocked: Boolean get() = hold.isLocked

    /// Slid up and let go, rather than tapped.
    val lockedBySliding: Boolean get() = isLocked && hold.armed == SessionFeature.State.DictationHold.Arm.lock
    val isFinishing: Boolean get() = phase is SessionFeature.State.DictationPhase.finishing
    val isSending: Boolean get() = (phase as? SessionFeature.State.DictationPhase.finishing)?.send != null
    val isHolding: Boolean get() = !isLocked && !isFinishing && !isLeaving

    /// Let go with nothing armed: the circle flies off with the message.
    val isSentFromHold: Boolean get() = !isLocked && isSending
    val cancelArmed: Boolean get() = isHolding && hold.armed == SessionFeature.State.DictationHold.Arm.cancel
    val lockArmed: Boolean get() = isHolding && hold.armed == SessionFeature.State.DictationHold.Arm.lock
    val guidesShown: Boolean get() = isHolding && (showsHoldGuides || hold.armed != null)

    /// The lock target is on its way into Stop.
    val isDroppingLock: Boolean get() = lockedBySliding && !lockDropDone && !reducedMotion

    val lockProgress: Float
        get() {
            if (!isHolding || drag.axis != DictationHoldTracker.Axis.up) return 0f
            return min(1f, max(0f, -drag.translation.y / DictationHoldTracker.lockDistance))
        }
    val cancelProgress: Float
        get() {
            if (!isHolding || drag.axis != DictationHoldTracker.Axis.left) return 0f
            return min(1f, max(0f, -drag.translation.x / DictationHoldTracker.cancelDistance))
        }

    /// How far the circle leans after the finger: along the chosen axis, or a
    /// few points either way before one is chosen. Still under Reduce Motion.
    val follow: Offset
        get() {
            if (!isHolding || reducedMotion) return Offset.Zero
            val t = drag.translation
            return when (drag.axis) {
                DictationHoldTracker.Axis.up ->
                    Offset(0f, min(0f, max(t.y * 0.3f, -(DictationHoldTracker.lockDistance * 0.3f + 4))))
                DictationHoldTracker.Axis.left ->
                    Offset(min(0f, max(t.x * 0.3f, -(DictationHoldTracker.cancelDistance * 0.42f + 6))), 0f)
                null -> Offset(min(4f, max(t.x * 0.25f, -4f)), min(4f, max(t.y * 0.25f, -4f)))
            }
        }

    companion object {
        const val holdDiameter = 68f
        const val targetDiameter = 38f
        const val spacing = 4f
        const val slot = 44f
        const val lockDropDelay = 0.13
        const val lockDropDuration = 0.36
    }
}

/// The composer's trailing control: it grows with the text like the composer's.
@Composable
private fun dictationButtonDiameter(): Float = min(34f * min(LocalDensity.current.fontScale, 1.35f), 44f)

/// Recording takes the composer's control row: Cancel (`xmark`) · the
/// warm-pearl scope (`DotScopeView`) · Stop (`stop.fill`, into the draft) ·
/// Send (`arrow.up`). While the mic is held only ✕ (where sliding left
/// cancels) and the scope show, and `DictationHoldLayer` draws the circle
/// under the finger; locked, the circle settles into Send and the lock
/// target drops into Stop.
@Composable
private fun DictationScopeBar(
    pose: DictationBarPose,
    /// Mid-run, Send steers and touch and hold offers Steer and Queue.
    isRunning: Boolean,
    canSend: Boolean,
    onVisualsChange: ((DictationHoldVisuals) -> DictationHoldVisuals) -> Unit,
    onCancel: () -> Unit,
    onStop: () -> Unit,
    onSend: (OpenCodeDelivery) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = pose
    var lockedAt by remember { mutableStateOf<Long?>(null) }
    val diameter = dictationButtonDiameter()
    val slot = DictationBarPose.slot

    /// After a tap opens the bar, a quick second tap on the mic would land on
    /// a bar button; they ignore taps this long.
    val tapGuard = 0.4

    fun guarded(action: () -> Unit) {
        val locked = lockedAt
        if (!p.lockedBySliding && locked != null && (System.nanoTime() - locked) / 1e9 < tapGuard) return
        action()
    }
    // Initial too: VoiceOver opens the bar already locked, and the tap
    // guard still applies.
    LaunchedEffect(p.isLocked) {
        if (p.isLocked) lockedAt = System.nanoTime()
    }
    LaunchedEffect(p.isDroppingLock) {
        if (!p.isDroppingLock) return@LaunchedEffect
        delay(((DictationBarPose.lockDropDelay + DictationBarPose.lockDropDuration) * 1_000).toLong())
        // Now identical to Stop beneath it: swap without a flicker.
        onVisualsChange { it.copy(lockDropDone = true) }
    }
    // Only a sustained hold grows the circle and offers the targets; a quick
    // tap locks immediately and never flashes them.
    LaunchedEffect(p.isHolding) {
        if (!p.isHolding) return@LaunchedEffect
        delay(200)
        onVisualsChange { it.copy(showsGuides = true) }
    }
    val armedSpring = if (p.reducedMotion) tween<Float>(150) else spring(dampingRatio = 0.75f, stiffness = 800f)
    val cancelScale by animateFloatAsState(if (p.cancelArmed && !p.reducedMotion) 1.1f else 1f, armedSpring, label = "cancel")
    val stopAlpha by animateFloatAsState(
        if (p.isLocked && !p.isDroppingLock) 1f else 0f,
        // Tapped open, Stop follows Send in; slid, the lock target becomes it.
        if (p.reducedMotion) tween(200) else tween(200, delayMillis = (DictationBarPose.lockDropDelay * 1_000).toInt(), easing = EaseOut),
        label = "stop",
    )
    Box(modifier.heightIn(min = 44.dp)) {
        Row(Modifier.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .scale(cancelScale)
                    .clickable(enabled = p.isLocked && !p.isFinishing, interactionSource = null, indication = null, role = Role.Button) { guarded(onCancel) }
                    .then(
                        if (p.isLocked) {
                            Modifier.semantics {
                                contentDescription = "Cancel"
                                testTag = "Cancel dictation"
                            }
                        } else {
                            Modifier.clearAndSetSemantics {}
                        },
                    ),
            ) {
                DictationRoundLabel(
                    "xmark",
                    font = TextStyles.body.medium,
                    foreground = if (p.cancelArmed) Color.White else SystemColors.label,
                    background = if (p.cancelArmed) SystemColors.systemRed else SystemColors.tertiarySystemFill,
                    diameter = diameter,
                )
            }
            Spacer(Modifier.weight(1f).heightIn(min = 44.dp))
            Box(
                Modifier
                    .alpha(stopAlpha)
                    .clickable(enabled = p.isLocked && !p.isFinishing, interactionSource = null, indication = null, role = Role.Button) { guarded(onStop) }
                    .then(
                        if (p.isLocked) {
                            Modifier.semantics {
                                // Not “Stop”: during a run that's the composer's Stop for the run.
                                contentDescription = if (p.isFinishing && !p.isSending) "Finishing dictation" else "Stop recording"
                                testTag = if (p.isFinishing && !p.isSending) "Finishing dictation" else "Stop dictation"
                                stateDescription = "Puts the words in your message"
                            }
                        } else {
                            Modifier.clearAndSetSemantics {}
                        },
                    ),
            ) {
                DictationRoundLabel(
                    "stop.fill",
                    font = TextStyles.caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                    foreground = SystemColors.label,
                    background = SystemColors.tertiarySystemFill,
                    diameter = diameter,
                    showsProgress = p.isFinishing && !p.isSending,
                )
            }
            DictationSendSlot(
                p,
                isRunning = isRunning,
                canSend = canSend,
                onSend = { delivery -> guarded { onSend(delivery) } },
                modifier = Modifier.padding(start = DictationBarPose.spacing.dp),
            )
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = p.guidesShown,
            modifier = Modifier.align(Alignment.CenterStart).offset(x = 50.dp),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            SystemImage(
                "chevron.left",
                null,
                Modifier
                    .symbolSize(TextStyles.caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
                    .alpha(1f - p.cancelProgress * 0.6f)
                    .clearAndSetSemantics {},
                tint = SystemColors.secondaryLabel,
            )
        }
    }
}

/// Send's touch target. Its visible circle is the one the hold grew, drawn
/// by the hold layer, so the circle that was under the finger is the one
/// you tap.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DictationSendSlot(
    p: DictationBarPose,
    isRunning: Boolean,
    canSend: Boolean,
    onSend: (OpenCodeDelivery) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showsDelivery by remember { mutableStateOf(false) }
    val enabled = p.isLocked && !p.isSending && canSend
    Box(
        modifier
            .size(DictationBarPose.slot.dp)
            .clip(CircleShape)
            .combinedClickable(
                enabled = enabled,
                interactionSource = null,
                indication = null,
                role = Role.Button,
                // Like the composer's Send mid-run: tap steers; touch and hold offers
                // the delivery.
                onLongClick = if (isRunning) ({ showsDelivery = true }) else null,
                onClick = { onSend(OpenCodeDelivery.steer) },
            )
            .then(
                if (p.isLocked) {
                    Modifier.semantics {
                        contentDescription = if (p.isSending) "Sending message" else "Send"
                        testTag = "Send dictation"
                        if (isRunning) stateDescription = "Steers at the next step. Touch and hold to queue after this response."
                    }
                } else {
                    Modifier.clearAndSetSemantics {}
                },
            ),
    ) {
        SystemMenu(
            expanded = showsDelivery,
            onDismissRequest = { showsDelivery = false },
            sections = listOf(
                SystemMenuSection(
                    listOf(
                        SystemMenuItem("Steer", systemImage = OpenCodeDelivery.steer.symbolName) {
                            showsDelivery = false
                            onSend(OpenCodeDelivery.steer)
                        },
                        SystemMenuItem("Queue", systemImage = OpenCodeDelivery.queue.symbolName) {
                            showsDelivery = false
                            onSend(OpenCodeDelivery.queue)
                        },
                    ),
                ),
            ),
        )
    }
}

@Composable
private fun DictationRoundLabel(
    systemImage: String,
    font: androidx.compose.ui.text.TextStyle,
    foreground: Color,
    background: Color,
    diameter: Float,
    showsProgress: Boolean = false,
) {
    Box(Modifier.size(DictationBarPose.slot.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(diameter.dp).background(background, CircleShape), contentAlignment = Alignment.Center) {
            if (showsProgress) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = foreground)
            } else {
                SystemImage(systemImage, null, Modifier.symbolSize(font), tint = foreground)
            }
        }
    }
}

/// The parts of the recording bar that leave its row, drawn above the
/// composer's glass so its rim never crosses them: the circle that grows
/// out of the mic under the finger and becomes Send, and the lock target
/// above it that drops into Stop. Purely visual; the bar owns the buttons.
@Composable
private fun DictationHoldLayer(
    pose: DictationBarPose,
    canSend: Boolean,
    /// In root coordinates, like `barFrame`.
    micFrame: Rect,
    barFrame: Rect,
    modifier: Modifier = Modifier,
) {
    val p = pose
    var origin by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val diameter = dictationButtonDiameter()
    val slot = DictationBarPose.slot
    fun local(point: Offset) = with(density) { Offset((point.x - origin.x).toDp().value, (point.y - origin.y).toDp().value) }
    val barMaxX = local(Offset(barFrame.right, 0f)).x
    val barMidY = local(Offset(0f, barFrame.center.y)).y
    val sendCenter = Offset(barMaxX - slot / 2, barMidY)
    val stopCenter = Offset(barMaxX - slot * 1.5f - DictationBarPose.spacing, barMidY)
    val micCenter = if (micFrame == Rect.Zero) sendCenter else local(micFrame.center)
    /// Directly above the mic, where the finger goes to lock.
    val lockCenter = Offset(micCenter.x, micCenter.y - DictationBarPose.holdDiameter / 2 - 30 - DictationBarPose.targetDiameter / 2)

    val lockedSpring = if (p.reducedMotion) tween<Float>(200) else spring(dampingRatio = 0.9f, stiffness = 340f)
    val circleX by animateFloatAsState((if (p.isLocked) sendCenter.x else micCenter.x) + p.follow.x, lockedSpring, label = "circleX")
    val circleY by animateFloatAsState((if (p.isLocked) sendCenter.y else micCenter.y) + p.follow.y, lockedSpring, label = "circleY")
    val grown = !p.isLocked && p.showsGrownCircle
    val circleDiameter by animateFloatAsState(
        if (grown) DictationBarPose.holdDiameter else diameter,
        if (p.reducedMotion) tween(200) else spring(dampingRatio = 0.85f, stiffness = 400f),
        label = "circleDiameter",
    )
    val sentProgress by animateFloatAsState(if (p.isSentFromHold) 1f else 0f, if (p.reducedMotion) tween(200) else spring(stiffness = 600f), label = "sent")
    val dropped = p.isDroppingLock
    val lockX by animateFloatAsState(if (dropped) stopCenter.x else lockCenter.x, if (p.reducedMotion) tween(0) else tween((DictationBarPose.lockDropDuration * 1_000).toInt(), delayMillis = (DictationBarPose.lockDropDelay * 1_000).toInt()), label = "lockX")
    val lockY by animateFloatAsState(if (dropped) stopCenter.y else lockCenter.y, if (p.reducedMotion) tween(0) else tween((DictationBarPose.lockDropDuration * 1_000).toInt(), delayMillis = (DictationBarPose.lockDropDelay * 1_000).toInt()), label = "lockY")
    val lockScale by animateFloatAsState(if (p.lockArmed && !p.reducedMotion) 1.1f else 1f, if (p.reducedMotion) tween(150) else spring(dampingRatio = 0.75f, stiffness = 800f), label = "lockScale")
    val swells = !p.isLocked && p.isHolding && p.showsHoldGuides && !p.reducedMotion
    val swell = rememberDictationSwell(swells)

    Box(
        modifier
            .onGloballyPositioned { origin = it.boundsInRoot().topLeft }
            .clearAndSetSemantics {},
    ) {
        androidx.compose.animation.AnimatedVisibility(p.guidesShown, enter = fadeIn(), exit = fadeOut()) {
            SystemImage(
                "chevron.up",
                null,
                Modifier
                    .dictationPosition(Offset(micCenter.x, micCenter.y - DictationBarPose.holdDiameter / 2 - 15 + p.follow.y * 0.5f), 14f)
                    .alpha(1f - p.lockProgress * 0.6f)
                    .symbolSize(TextStyles.caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)),
                tint = SystemColors.secondaryLabel,
            )
        }
        androidx.compose.animation.AnimatedVisibility(p.guidesShown || dropped, enter = fadeIn(), exit = fadeOut()) {
            // Glass above the mic while holding; filled when armed. Locked by
            // sliding, it drops into Stop's slot a beat after the circle leaves for
            // Send, and becomes Stop.
            val size = if (dropped) diameter else DictationBarPose.targetDiameter
            Box(
                Modifier
                    .dictationPosition(Offset(lockX, lockY), size)
                    .scale(lockScale)
                    .then(if (dropped) Modifier else Modifier.openCodeGlass(CircleShape))
                    .background(
                        when {
                            p.lockArmed -> SystemColors.label
                            dropped -> SystemColors.tertiarySystemFill
                            else -> Color.Transparent
                        },
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                SystemImage(
                    if (dropped) "stop.fill" else if (p.lockArmed) "lock.fill" else "lock.open.fill",
                    null,
                    Modifier.symbolSize(
                        if (dropped) TextStyles.caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) else TextStyles.subheadline.semibold,
                    ),
                    tint = if (dropped) SystemColors.label else if (p.lockArmed) SystemColors.systemBackground else SystemColors.secondaryLabel,
                )
            }
        }
        // One circle from press to Send: the mic's size at first, swelling under a
        // sustained hold, then shrinking into Send when locked. In Label (black in
        // light, white in dark), so it becomes Send without changing colour.
        Box(
            Modifier
                .dictationPosition(Offset(circleX, circleY - 36 * sentProgress), circleDiameter)
                // Let go with nothing armed: it flies off with the message.
                .scale((1 + swell * 0.1f) * (1 - 0.5f * sentProgress))
                .alpha((1 - sentProgress) * (if (p.isLocked && !canSend && !p.isSending) 0.4f else 1f))
                .background(SystemColors.label, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (p.isLocked && p.isSending) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = SystemColors.systemBackground)
            } else {
                SystemImage(
                    if (p.isLocked) "arrow.up" else "mic.fill",
                    null,
                    Modifier.symbolSize(if (grown) TextStyles.title2.semibold else TextStyles.body.medium),
                    tint = SystemColors.systemBackground,
                )
            }
        }
    }
}

/// Places a square of `size` points centred on `center`, in the layer's points.
private fun Modifier.dictationPosition(center: Offset, size: Float): Modifier =
    offset(x = (center.x - size / 2).dp, y = (center.y - size / 2).dp).size(size.dp)

/// The live input level, eased for the hold circle's swell: quick to rise,
/// slower to fall, never the raw per-buffer value.
private class DictationLevelFollower {
    private var value = 0f
    private var last: Long? = null

    fun step(target: Float, at: Long): Float {
        val elapsed = min(0.1f, (at - (last ?: at)) / 1e9f)
        last = at
        val rate = if (target > value) 18f else 6f
        value += (target - value) * min(1f, elapsed * rate)
        return value
    }
}

@Composable
private fun rememberDictationSwell(swells: Boolean): Float {
    var swell by remember { mutableFloatStateOf(0f) }
    val follower = remember { DictationLevelFollower() }
    LaunchedEffect(swells) {
        if (!swells) {
            swell = 0f
            return@LaunchedEffect
        }
        while (true) {
            withFrameNanos { now -> swell = follower.step(DictationLevels.shared.level, now) }
        }
    }
    return swell
}

/// A glass pill above the composer: background work or pending messages.
@Composable
fun ComposerPillLabel(
    title: String,
    trailingSystemImage: String? = null,
    leading: @Composable () -> Unit,
) {
    // Slim glass, full 44 pt target.
    Box(Modifier.padding(vertical = 5.dp)) {
        ComposerPillContent(title, trailingSystemImage, Modifier.openCodeGlass(CircleShape, interactive = true), leading)
    }
}

/// A composer pill's glyph, title, and chevron, without its glass.
@Composable
fun ComposerPillContent(
    title: String,
    trailingSystemImage: String? = null,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit,
) {
    Row(
        Modifier
            .heightIn(min = 34.dp)
            .then(modifier)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(18.dp).semantics { hideFromAccessibility() }, contentAlignment = Alignment.Center) {
            CompositionLocalProvider(LocalContentColor provides SystemColors.label) { leading() }
        }
        Text(title, style = TextStyles.subheadline.medium, color = SystemColors.label, maxLines = 1)
        if (trailingSystemImage != null) {
            SystemImage(trailingSystemImage, null, Modifier.symbolSize(TextStyles.caption.semibold), tint = SystemColors.secondaryLabel)
        }
    }
}

/// A full-width bar that takes the composer's place, on its same surface.
@Composable
private fun ComposerBanner(
    systemImage: String,
    title: String,
    action: String,
    modifier: Modifier = Modifier,
    iconStyle: Color = SystemColors.secondaryLabel,
    detail: String? = null,
    perform: () -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .openCodeGlass(shape, interactive = true)
            .clickable(role = Role.Button, onClick = perform)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(18.dp).semantics { hideFromAccessibility() }, contentAlignment = Alignment.Center) {
            SystemImage(systemImage, null, Modifier.symbolSize(TextStyles.subheadline), tint = iconStyle)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = TextStyles.subheadline.medium, color = SystemColors.label)
            if (detail != null) {
                Text(detail, style = TextStyles.caption, color = SystemColors.secondaryLabel, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(action, style = TextStyles.subheadline.semibold, color = SystemColors.tint)
    }
}

/// One queued message: its text (tap to expand), and a quiet menu with Send
/// Now and Cancel. Send Now is never one careless gesture away: the menu
/// asks for a second tap.
@Composable
private fun QueuedMessageRow(
    input: OpenCodeQueuedInput,
    isSending: Boolean,
    onSendNow: () -> Unit,
    onCancel: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var showsActions by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // A queued voice delegation reads as its Voice message, not its prompt.
    val text = VoiceHandoff.parse(input.text)?.displayText ?: input.text
    val isLong = text.length > 160 || text.lines().count { it.isNotEmpty() } > 3
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Column(
            Modifier
                .weight(1f)
                .padding(vertical = 4.dp)
                .clickable(interactionSource = null, indication = null) { if (isLong) expanded = !expanded }
                .semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (input.images.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (image in input.images) {
                        CachedImage(
                            image.id,
                            image.data,
                            Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(TranscriptMetrics.controlCornerRadius.dp))
                                .semantics { contentDescription = "Image" },
                        )
                    }
                }
            }
            for (file in input.files) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    SystemImage(AttachmentFileType.symbolName(file.name, file.mime), null, Modifier.symbolSize(TextStyles.subheadline), tint = SystemColors.secondaryLabel)
                    Text(file.name, style = TextStyles.subheadline, color = SystemColors.secondaryLabel, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                }
            }
            if (text.isNotEmpty()) {
                Text(
                    text,
                    Modifier.fillMaxWidth().animateContentSize(),
                    style = TextStyles.body,
                    color = SystemColors.label,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (isSending) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = SystemColors.secondaryLabel)
            }
        } else {
            Box {
                Box(
                    Modifier
                        // Centre the glyph on the first line of text; the target stays 44 pt.
                        .offset(y = (-7).dp)
                        .size(44.dp)
                        .clickable(role = Role.Button) { showsActions = true }
                        .semantics { contentDescription = "Message actions" },
                    contentAlignment = Alignment.Center,
                ) {
                    SystemImage("ellipsis", null, Modifier.symbolSize(TextStyles.body.medium), tint = SystemColors.secondaryLabel)
                }
                SystemMenu(
                    expanded = showsActions,
                    onDismissRequest = { showsActions = false },
                    sections = listOf(
                        SystemMenuSection(
                            listOfNotNull(
                                SystemMenuItem("Send Now", OpenCodeDelivery.steer.symbolName) {
                                    showsActions = false
                                    onSendNow()
                                },
                                if (input.text.isNotEmpty()) {
                                    SystemMenuItem("Copy", "square.on.square") {
                                        showsActions = false
                                        copyToClipboard(context, input.text)
                                    }
                                } else {
                                    null
                                },
                                SystemMenuItem("Cancel Message", "xmark", destructive = true) {
                                    showsActions = false
                                    onCancel()
                                },
                            ),
                        ),
                    ),
                )
            }
        }
    }
}

@Composable
private fun SessionHeaderBackdrop() {
    val parameters = HeaderBlurParameters.shipped
    val background = SystemColors.systemBackground
    // No system scroll-edge effect on Android: the honest fallback is the
    // opaque fade iOS draws under Reduce Transparency.
    Box(
        Modifier
            .fillMaxWidth()
            .height(parameters.bandHeight)
            .background(
                Brush.verticalGradient(
                    0f to background,
                    parameters.middleStop to background,
                    max(parameters.endStop, parameters.middleStop) to background.copy(alpha = 0f),
                ),
            )
            .semantics { hideFromAccessibility() },
    )
}

@Composable
fun SessionView(
    store: Store<SessionFeature.State, SessionFeature.Action>,
    modifier: Modifier = Modifier,
    onOpenSessions: () -> Unit = {},
    /// Set while the session is a catch-up card: the header trades the drawer
    /// button for a checkmark that marks it read and moves on.
    onCatchUpDone: (() -> Unit)? = null,
    /// Set alongside `onCatchUpDone`: the title leaves Catch Up for the session.
    onCatchUpOpen: (() -> Unit)? = null,
    listContext: SessionListContext = SessionListContext(),
) {
    val view = remember { SessionViewState() }
    view.store = store
    view.onOpenSessions = onOpenSessions
    view.onCatchUpDone = onCatchUpDone
    view.onCatchUpOpen = onCatchUpOpen
    view.listContext = listContext
    view.reducedMotion = LocalReduceMotion.current
    remember(store.state.session?.id) {
        LabMarks.markOnce("session_view_init", store.state.session?.id)
    }
    view.Body(modifier)
}

/// SwiftUI's `SessionView` struct: its `@State` and its computed views.
@Stable
private class SessionViewState {
    lateinit var store: Store<SessionFeature.State, SessionFeature.Action>
    var onOpenSessions: () -> Unit = {}
    var onCatchUpDone: (() -> Unit)? = null
    var onCatchUpOpen: (() -> Unit)? = null
    var listContext by mutableStateOf(SessionListContext())
    var reducedMotion = false
    var attachmentError by mutableStateOf<String?>(null)
    var showsQueuedMessages by mutableStateOf(false)
    var showsBackgroundWork by mutableStateOf(false)
    var showsJumpButton by mutableStateOf(false)
    var jumpRequest by mutableIntStateOf(0)
    val imageImporter = ComposerImageImporter()
    val fileImporter = ComposerFileImporter()
    /// `imageImporter.isLoading`, observable: SwiftUI's importer is `@Observable`.
    var isImporting by mutableStateOf(false)
    var imagePreview by mutableStateOf<TranscriptImagePreview?>(null)
    var openFile by mutableStateOf<AgentFileReference?>(null)
    var largeUpload by mutableStateOf<LargeUpload?>(null)
    var messageFieldFocused by mutableStateOf(false)
    /// The held mic's finger travel, for the recording bar to follow.
    var dictationDrag by mutableStateOf(DictationDrag())
    var dictationMicFrame by mutableStateOf(Rect.Zero)
    var dictationBarFrame by mutableStateOf(Rect.Zero)
    var dictationHoldVisuals by mutableStateOf(DictationHoldVisuals())
    var attachmentMenu by mutableStateOf(AttachmentMenuPhase.closed)
    var photoSelection = InlinePhotoSelection()
    var pickerReservation by mutableFloatStateOf(0f)
    val attachmentMenuAnchors = AttachmentMenuAnchors()
    /// Close the attachment menu into a focused field: it had focus when the
    /// menu opened, or something was attached.
    var attachmentMenuRestoresFocus = false
    val scrollEdgeBridge = SessionScrollEdgeBridge()
    val backgroundActivityAnchor = BackgroundActivityAnchor()
    /// The composer column's top and the screen's height, in pixels.
    var composerTop by mutableFloatStateOf(Float.NaN)
    var screenHeight by mutableFloatStateOf(0f)

    /// Matches the drawer button: the navigation bar is hidden on this screen.
    @Composable
    fun DrawerControl() {
        val badge = listContext.elsewhere
        Box(
            Modifier.semantics(mergeDescendants = true) {
                contentDescription = "Open sessions"
                stateDescription = when (badge) {
                    DrawerBadge.attention -> "A session needs your input"
                    DrawerBadge.unread -> "Unread sessions"
                    null -> ""
                }
            },
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .openCodeGlass(CircleShape, interactive = true)
                    .clickable(role = Role.Button, onClick = onOpenSessions),
                contentAlignment = Alignment.Center,
            ) {
                DrawerGlyph()
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = badge != null,
                modifier = Modifier.align(Alignment.TopEnd).offset(x = (-3).dp, y = 3.dp),
                enter = if (reducedMotion) fadeIn(snap()) else scaleIn(spring(dampingRatio = 1f, stiffness = 438f)) + fadeIn(),
                exit = if (reducedMotion) fadeOut(snap()) else scaleOut(spring(dampingRatio = 1f, stiffness = 438f)) + fadeOut(),
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(if (badge == DrawerBadge.attention) SystemColors.systemOrange else SystemColors.systemBlue, CircleShape),
                )
            }
        }
    }

    @Composable
    fun FloatingControl(systemImage: String, label: String, modifier: Modifier = Modifier, action: () -> Unit) {
        Box(
            modifier
                .size(44.dp)
                .openCodeGlass(CircleShape, interactive = true)
                .clickable(role = Role.Button, onClick = action)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            SystemImage(systemImage, null, Modifier.symbolSize(TextStyles.body.semibold), tint = SystemColors.label)
        }
    }

    @Composable
    fun SessionContent() {
        val message = store.state.errorMessage
        if (message != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ContentUnavailableView(
                    "Couldn’t Load Session",
                    systemImage = "exclamationmark.triangle",
                    description = message,
                ) {
                    OpenCodeButton("Try Again", prominent = false) { store.send(SessionFeature.Action.task) }
                }
            }
        } else {
            // Separate child view so composer keystrokes (draft changes) don't
            // re-evaluate the whole transcript render tree.
            androidx.compose.runtime.key(store.state.session?.id) {
                SessionTranscriptView(
                    store = store,
                    scrollEdgeBridge = scrollEdgeBridge,
                    dismissKeyboard = { messageFieldFocused = false },
                    onPreviewImage = { imagePreview = it },
                    onOpenFile = { file ->
                        messageFieldFocused = false
                        openFile = file
                    },
                    showsJumpButton = Binding({ showsJumpButton }, { showsJumpButton = it }),
                    jumpRequest = jumpRequest,
                    composerInset = composerInset,
                    keyboardIsMoving = keyboardIsMoving,
                )
            }
        }
    }

    private fun dictationPose(hold: SessionFeature.State.DictationHold, isLeaving: Boolean) = DictationBarPose(
        hold = hold,
        phase = store.state.dictationPhase,
        drag = dictationDrag,
        reducedMotion = reducedMotion,
        visuals = dictationHoldVisuals,
        isLeaving = isLeaving,
    )

    lateinit var composerInset: () -> Float
    lateinit var keyboardIsMoving: () -> Boolean

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    fun Body(modifier: Modifier) {
        val density = LocalDensity.current
        val ime = WindowInsets.ime
        val imeTarget = WindowInsets.imeAnimationTarget
        composerInset = remember(density, ime, imeTarget) {
            {
                val top = composerTop
                if (top.isNaN()) {
                    0f
                } else {
                    // The keyboard's final height lands with the snapshot it belongs to;
                    // leaving, it rides down with the keyboard.
                    val rising = max(0, imeTarget.getBottom(density) - ime.getBottom(density))
                    max(0f, screenHeight - top) + rising
                }
            }
        }
        keyboardIsMoving = remember(density, ime, imeTarget) { { imeTarget.getBottom(density) != ime.getBottom(density) } }
        val session = store.state.session
        CompositionLocalProvider(LocalBackgroundActivityAnchor provides backgroundActivityAnchor) {
            // The transcript is the glass's backdrop; the controls float above it.
            GlassBackdrop(
                modifier
                    .fillMaxSize()
                    .background(SystemColors.systemBackground)
                    .onGloballyPositioned { screenHeight = it.boundsInRoot().bottom },
                content = {
                    SessionContent()
                    Box(Modifier.align(Alignment.TopCenter)) { SessionHeaderBackdrop() }
                },
                overlay = {
                    Box(Modifier.align(Alignment.TopCenter).statusBarsPadding()) { HeaderControls() }
                    Column(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                            .onGloballyPositioned { composerTop = it.boundsInRoot().top },
                    ) {
                        ComposerArea(Modifier.padding(bottom = if (onCatchUpDone == null) 0.dp else 12.dp))
                    }
                    // Above the glass, so its rim never crosses the hold circle.
                    val hold = store.state.dictationHold
                    val holdEnded = hold == null
                    LaunchedEffect(holdEnded) {
                        if (holdEnded) dictationHoldVisuals = dictationHoldVisuals.end()
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = hold != null && store.state.voice == null,
                        modifier = Modifier.matchParentSize(),
                        enter = fadeIn(tween(if (reducedMotion) 200 else 280)),
                        exit = fadeOut(tween(if (reducedMotion) 200 else 280)),
                    ) {
                        if (hold != null) {
                            DictationHoldLayer(
                                pose = dictationPose(hold, isLeaving = transition.targetState == EnterExitState.PostExit),
                                canSend = !store.state.isSubmittingPrompt,
                                micFrame = dictationMicFrame,
                                barFrame = dictationBarFrame,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    BackgroundWork(store, Binding({ showsBackgroundWork }, { showsBackgroundWork = it }))
                    AttachmentMenuOverlayHost()
                    SendFlightOverlay(scrollEdgeBridge.sendTransition)
                },
            )
        }
        ActivePickerSheet()
        // Keyed: the drawer swaps the session in place, so the view keeps its
        // identity and a plain `.task` would never load the new transcript.
        store.task(id = session?.id) {
            LabMarks.mark("session_task_started", session?.id)
            store.send(SessionFeature.Action.task)
        }
        // Once per call: the call keeps running while the drawer swaps sessions.
        val hasVoice = store.state.voice != null
        store.task(id = hasVoice) {
            if (hasVoice) store.send(SessionFeature.Action.voice(ai.opencode.mobilecode.tca.PresentationAction.presented(VoiceFeature.Action.start)))
        }
        ScenePhaseEffects()
        LaunchedEffect(session?.id) {
            cancelImageImport()
            showsJumpButton = false
            attachmentMenu = AttachmentMenuPhase.closed
            photoSelection = InlinePhotoSelection()
        }
        alert(
            "Couldn’t Add Image",
            isPresented = Binding({ attachmentError != null }, { if (!it) attachmentError = null }),
            message = attachmentError ?: "",
            buttons = listOf(AlertButton("OK") { attachmentError = null }),
        )
        val upload = largeUpload
        alert(
            upload?.title ?: "",
            isPresented = Binding({ largeUpload != null }, { if (!it) largeUpload = null }),
            message = upload?.message,
            buttons = listOf(
                AlertButton("Cancel", ai.opencode.mobilecode.system.ButtonRole.cancel) {
                    upload?.files?.forEach { ComposerFileStaging.discard(it.url) }
                    largeUpload = null
                },
                AlertButton("Upload") {
                    if (upload != null) {
                        store.send(SessionFeature.Action.filesStaged(upload.files))
                        upload.onStaged?.invoke()
                    }
                    largeUpload = null
                },
            ),
        )
        imagePreview?.let { preview ->
            FullScreenCover(onDismiss = { imagePreview = null }) {
                TranscriptImageViewer(preview, onDismiss = { imagePreview = null })
            }
        }
        openFile?.let { file ->
            FullScreenCover(onDismiss = { openFile = null }) {
                AgentFileViewer(reference = file, server = store.state.server, directory = store.state.session?.directory, onDismiss = { openFile = null })
            }
        }
    }

    @Composable
    fun ScenePhaseEffects() {
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(lifecycle, store) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> store.send(SessionFeature.Action.visibilityChanged(true))
                    Lifecycle.Event.ON_PAUSE -> store.send(SessionFeature.Action.visibilityChanged(false))
                    Lifecycle.Event.ON_STOP -> store.send(SessionFeature.Action.sceneEnteredBackground)
                    else -> Unit
                }
            }
            store.send(SessionFeature.Action.visibilityChanged(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)))
            lifecycle.addObserver(observer)
            onDispose {
                lifecycle.removeObserver(observer)
                scrollEdgeBridge.sendTransition.cancel()
                cancelImageImport()
                store.send(SessionFeature.Action.visibilityChanged(false))
            }
        }
    }

    @Composable
    fun ActivePickerSheet() {
        val picker = store.state.activePicker ?: return
        val activePicker = store.binding(SessionFeature.State::activePicker, SessionFeature.Action::binding)
        androidx.compose.runtime.key(picker) {
            SystemSheet(onDismissRequest = { activePicker.wrappedValue = null }) {
                when (picker) {
                    SessionFeature.State.ActivePicker.model -> ModelPicker()
                    SessionFeature.State.ActivePicker.project -> ProjectPicker()
                    SessionFeature.State.ActivePicker.requests -> RequestReviewView(store)
                    SessionFeature.State.ActivePicker.session -> {
                        val sheet = store.scope(SessionFeature.State::sessionSheet, SessionFeature.Action.sessionSheet)
                        if (sheet != null) {
                            SessionSheetView(
                                store = sheet,
                                session = store.state.session,
                                context = listContext,
                                currentModelName = store.state.currentModelName,
                                contextUsage = store.state.contextUsage,
                                contextFraction = store.state.contextFraction,
                                contextLimit = store.state.contextLimit,
                                sessionCost = store.state.sessionCost,
                                onModelButtonTapped = { store.send(SessionFeature.Action.modelButtonTapped) },
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun HeaderControls() {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(top = TranscriptMetrics.clustered.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (onCatchUpDone == null) DrawerControl()
            // Holds the trailing edge even before a draft has a title.
            Box(Modifier.weight(1f)) { HeaderTitle(Modifier) }
            TrailingHeaderControls()
        }
        // The menu's rename, delete, and export prompts, while the sheet isn't
        // showing its own.
        if (store.state.activePicker != SessionFeature.State.ActivePicker.session) {
            store.scope(SessionFeature.State::sessionSheet, SessionFeature.Action.sessionSheet)?.let { sheet ->
                SessionActionPresentations(sheet, store.state.session?.title)
            }
        }
    }

    @Composable
    fun HeaderTitle(modifier: Modifier) {
        val title = store.state.session?.title
        if (title.isNullOrEmpty()) return
        val project = listContext.project?.name
        val autoRenameFailed = store.state.sessionSheet?.autoRenameFailed == true
        val isSyncing = store.state.isResyncing || store.state.sessionSheet?.operation == SessionSheetFeature.Operation.autoRenaming
        val busy = store.observe { isBusy }
        val label = listOfNotNull(title, if (autoRenameFailed) "Couldn’t rename" else project).joinToString(", ")
        val value = listOfNotNull(
            if (busy) "Running" else "Idle",
            if (store.state.isResyncing) "Syncing" else if (store.state.refreshFailed) "Last known" else null,
        ).joinToString(", ")
        val tap: (() -> Unit)? = if (onCatchUpDone == null) ({ store.send(SessionFeature.Action.sessionInfoTapped) }) else onCatchUpOpen
        Column(
            modifier
                .heightIn(min = 44.dp)
                .then(if (tap != null) Modifier.clickable(interactionSource = null, indication = null, role = Role.Button, onClick = tap) else Modifier)
                .semantics(mergeDescendants = true) {
                    contentDescription = label
                    stateDescription = value
                    testTag = "session-title"
                    if (tap == null) heading()
                },
            verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                SyncingSessionTitle(title = title, isSyncing = isSyncing, isLastKnown = store.state.refreshFailed)
                if (onCatchUpOpen != null) {
                    SystemImage("chevron.right", null, Modifier.symbolSize(TextStyles.caption2.semibold), tint = SystemColors.tertiaryLabel)
                }
            }
            AnimatedContent(
                targetState = if (autoRenameFailed) "Couldn’t rename" else project,
                transitionSpec = { fadeIn(tween(if (reducedMotion) 0 else 200)) togetherWith fadeOut(tween(if (reducedMotion) 0 else 200)) },
                label = "project-line",
            ) { line ->
                if (line != null) {
                    Text(line, style = TextStyles.caption, color = SystemColors.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

    /// The composer offers a call when Voice is set up and none is running.
    val showsVoiceControl: Boolean get() = store.state.isVoiceAvailable && store.state.voice == null

    @Composable
    fun TrailingHeaderControls() {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val rootID = store.state.familyRootID
            if (rootID != null && rootID != store.state.session?.id) {
                FloatingControl("arrow.uturn.backward", "Back to main session") {
                    store.send(SessionFeature.Action.familyPicked(rootID))
                }
            }
            val catchUpDone = onCatchUpDone
            if (catchUpDone != null) {
                FloatingControl("checkmark", "Mark as read and continue", Modifier.semantics { testTag = "catch-up-done" }, catchUpDone)
            } else if (store.state.session != null) {
                // Session actions; the rim doubles as the context gauge.
                SessionMenuButton(
                    title = store.state.session?.title ?: "Session",
                    sessionID = store.state.session?.id,
                    context = listContext,
                    contextFraction = store.state.contextFraction,
                    contextUsage = store.state.contextUsage,
                    contextLimit = store.state.contextLimit,
                    onAction = { store.send(SessionFeature.Action.sessionMenu(it)) },
                    onSessionInfo = { store.send(SessionFeature.Action.sessionInfoTapped) },
                )
            }
        }
    }

    @Composable
    fun ComposerArea(modifier: Modifier) {
        val showsCallTranscript = store.state.voice?.showsCaptions == true
        val hasPills = hasComposerPills()
        Box(
            modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 6.dp)
                // `windowInsetsPadding` supplies the navigation bar clearance when
                // the keyboard is hidden; with the keyboard up the pill needs its own
                // breathing room so it doesn't sit flush against the keys.
                .padding(bottom = if (messageFieldFocused) 8.dp else 0.dp)
                // The composer rides on top of the inline photo picker.
                .padding(bottom = with(LocalDensity.current) { composerLift.toDp() }),
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // A new session's project and model sit right above where you type.
                if (store.state.isDraft) {
                    Box(Modifier.hiddenUnderVoiceTranscript(showsCallTranscript)) { TranscriptDraftSetupView(store) }
                }
                Box(Modifier.hiddenUnderVoiceTranscript(showsCallTranscript)) { ComposerPills() }
                val error = store.state.settledRequestLoadError
                if (error != null) {
                    ComposerBanner(
                        systemImage = "exclamationmark.triangle.fill",
                        iconStyle = SystemColors.systemOrange,
                        title = "Couldn’t Load Requests",
                        detail = error,
                        action = "Retry",
                        modifier = Modifier.semantics { testTag = "request-load-error" }.hiddenUnderVoiceTranscript(showsCallTranscript),
                    ) { store.send(SessionFeature.Action.refreshRequestsTapped) }
                }
                if (store.state.hasPendingRequests) {
                    val count = store.state.reviewRequestIDs.size
                    ComposerBanner(
                        systemImage = "bubble.left.and.exclamationmark",
                        title = if (count == 1) "1 pending request" else "$count pending requests",
                        action = "Review",
                        modifier = Modifier.semantics(mergeDescendants = true) {
                            contentDescription = "Review requests"
                            stateDescription = "$count pending"
                        },
                    ) { store.send(SessionFeature.Action.reviewRequestsTapped) }
                } else {
                    ComposerCapsule()
                }
            }
            // Without pills, the jump button floats where their row would be, so
            // showing it never changes the transcript's insets.
            AnimatedVisibility(
                visible = showsJumpButton && !hasPills && !showsCallTranscript,
                modifier = Modifier.align(Alignment.TopEnd).offset(y = (-(44 + 10)).dp),
                enter = if (reducedMotion) fadeIn() else fadeIn(tween(200)) + scaleIn(tween(200), initialScale = 0.8f),
                exit = if (reducedMotion) fadeOut() else fadeOut(tween(200)) + scaleOut(tween(200), targetScale = 0.8f),
            ) { JumpToLatestButton() }
        }
        if (showsQueuedMessages) {
            SystemSheet(onDismissRequest = { showsQueuedMessages = false }) { QueuedMessagesSheet() }
        }
    }

    @Composable
    fun ComposerPills() {
        Box(Modifier.animateContentSize(if (reducedMotion) snap() else spring(dampingRatio = 1f, stiffness = 320f))) {
            ComposerPillStack()
        }
    }

    @Composable
    fun hasComposerPills(): Boolean {
        val hasActivity = store.observe { hasBackgroundWork }
        val canMove = store.observe { showsMoveToBackground }
        return hasActivity || canMove || queuedMessages().isNotEmpty() || showsReportOffer
    }

    /// Send Report? waits while you type, dictate, or talk. Problem reports
    /// are not ported yet, so nothing is offered.
    val showsReportOffer: Boolean get() = false

    /// Move to Background, when offered, gets its own row on top. Below it,
    /// one row: background work on the left; queued messages and the jump
    /// button on the right, each keeping its side as the others come and go.
    /// They stack only when the row can't fit (large text).
    @Composable
    fun ComposerPillStack() {
        val hasActivity = store.observe { hasBackgroundWork }
        val canMove = store.observe { showsMoveToBackground }
        val hasQueued = queuedMessages().isNotEmpty()
        val hasOffer = showsReportOffer
        if (!(hasActivity || canMove || hasQueued || hasOffer)) return
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (canMove) MoveToBackground()
            if (hasActivity || hasQueued || hasOffer || showsJumpButton) {
                ViewThatFits(
                    {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (hasActivity) BackgroundActivity()
                            Spacer(Modifier.weight(1f))
                            if (hasQueued) QueuedActivity()
                            if (showsJumpButton) JumpToLatestButton()
                        }
                    },
                    {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.End) {
                            if (hasActivity) Box(Modifier.fillMaxWidth()) { BackgroundActivity() }
                            if (hasQueued || showsJumpButton) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (hasQueued) QueuedActivity()
                                    if (showsJumpButton) JumpToLatestButton()
                                }
                            }
                        }
                    },
                )
            }
        }
    }

    @Composable
    fun JumpToLatestButton() {
        Box(
            Modifier
                .clickable(interactionSource = null, indication = null, role = Role.Button) { jumpRequest += 1 }
                .padding(5.dp)
                .size(34.dp)
                .openCodeGlass(CircleShape, interactive = true)
                .semantics {
                    contentDescription = "Scroll to latest"
                    testTag = "scroll-to-latest"
                },
            contentAlignment = Alignment.Center,
        ) {
            SystemImage("arrow.down", null, Modifier.symbolSize(TextStyles.subheadline.semibold), tint = SystemColors.label)
        }
    }

    @Composable
    fun BackgroundActivity() {
        val agents = store.observe { activeSubagents }
        val shells = store.observe { backgroundShells }
        val summary = BackgroundWorkPanel.summary(agents, shells)
        Box(
            Modifier
                .backgroundActivityAnchor()
                .clickable(interactionSource = null, indication = null, role = Role.Button) {
                    messageFieldFocused = false
                    showsBackgroundWork = !showsBackgroundWork
                }
                .semantics(mergeDescendants = true) {
                    testTag = "background-activity"
                    contentDescription = summary
                    stateDescription = if (showsBackgroundWork) "Expanded" else "Collapsed"
                },
        ) {
            BackgroundWorkCapsuleLabel(summary = summary)
        }
    }

    @Composable
    fun MoveToBackground() {
        Box(
            Modifier
                .clickable(interactionSource = null, indication = null, role = Role.Button) {
                    store.send(SessionFeature.Action.moveToBackgroundTapped)
                }
                .semantics { testTag = "move-to-background" },
        ) {
            ComposerPillLabel(title = "Move to Background") {
                SystemImage("tray.and.arrow.down", null, Modifier.symbolSize(TextStyles.subheadline), tint = SystemColors.label)
            }
        }
    }

    @Composable
    fun queuedMessages(): List<OpenCodeQueuedInput> = store.observe { queuedInputRows.filter { it.waitsInQueuedPill } }

    @Composable
    fun QueuedActivity() {
        val count = queuedMessages().size
        Box(
            Modifier
                .clickable(interactionSource = null, indication = null, role = Role.Button) { showsQueuedMessages = true }
                .semantics(mergeDescendants = true) {
                    testTag = "queued-messages"
                    contentDescription = if (count == 1) "1 queued message" else "$count queued messages"
                },
        ) {
            ComposerPillLabel(title = "$count queued", trailingSystemImage = "chevron.up") {
                SystemImage("text.bubble", null, Modifier.symbolSize(TextStyles.subheadline), tint = SystemColors.label)
            }
        }
    }

    @Composable
    fun QueuedMessagesSheet() {
        val messages = queuedMessages()
        NavigationStack {
            NavigationTitle("Queued Messages", displayMode = TitleDisplayMode.inline)
            ToolbarItem(placement = ToolbarItemPlacement.confirmationAction) {
                SystemButton("Done") { showsQueuedMessages = false }
            }
            if (messages.isEmpty()) {
                ContentUnavailableView(
                    "No Queued Messages",
                    systemImage = "tray",
                    description = "Queued messages send after the current response.",
                    modifier = Modifier.padding(vertical = 48.dp),
                )
            } else {
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(SystemColors.secondarySystemGroupedBackground)
                        .semantics { testTag = "queued-message-list" },
                ) {
                    items(messages, key = { it.id }) { input ->
                        Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                            QueuedMessageRow(
                                input = input,
                                isSending = store.state.promptStates[input.id]?.admission == SessionFeature.State.PromptState.Admission.sent,
                                onSendNow = { store.send(SessionFeature.Action.pendingInputSendNowTapped(input.id)) },
                                onCancel = { store.send(SessionFeature.Action.pendingInputCancelTapped(input.id)) },
                            )
                        }
                    }
                }
            }
        }
    }

    fun sendMessage(action: SessionFeature.Action = SessionFeature.Action.sendTapped) {
        val state = store.state
        if (action == SessionFeature.Action.returnKeyTapped && !canSend) {
            store.send(action)
            return
        }
        if (!state.canSendPrompt || state.hasPendingRequests) return
        // A send that waits for uploads lands later; the flight would snapshot now.
        if (!state.isRunning && !state.isUploadingFiles && (state.session != null || state.draftProject != null)) {
            scrollEdgeBridge.sendTransition.prepare(reduceMotion = reducedMotion)
        }
        store.send(action)
    }

    /// Mid-run sends steer at the next step unless explicitly queued from the menu.
    /// Send stays available while files upload: the message goes once they land.
    val canSend: Boolean
        get() = !store.state.isSubmittingPrompt && store.state.hasPromptContent && !store.state.pendingFiles.any { it.isFailed }

    val hasComposerAttachments: Boolean
        get() = store.state.pendingAttachments.isNotEmpty() || store.state.pendingFiles.isNotEmpty()

    data class ComposerPresentation(val expanded: Boolean, val controls: ComposerControls)

    val composerPresentation: ComposerPresentation
        get() {
            val state = store.state
            // A held mic keeps the composer exactly as it was: the touch belongs to
            // the mic button, which must stay mounted in place until it lifts.
            val holding = state.dictationHold != null
            val dictationActive = (state.isDictating || state.isFinishingDictation) && !holding
            val expanded = messageFieldFocused || dictationActive || state.draft.isNotEmpty() ||
                hasComposerAttachments || state.dictationError != null
            return ComposerPresentation(
                expanded,
                ComposerControls.make(
                    isDictating = state.isDictating && !holding,
                    isFinishing = state.isFinishingDictation && !holding,
                    isRunning = state.isRunning,
                    canSend = canSend,
                    expanded = expanded,
                    canCall = showsVoiceControl && !state.hasPromptContent,
                ),
            )
        }

    /// Keep the field and controls mounted while layout changes, preserving focus,
    /// selection, and attachment-picker state through the expansion animation.
    /// The composer and a call share one glass capsule.
    @Composable
    fun ComposerCapsule() {
        Box(Modifier.fillMaxWidth().openCodeGlass(RoundedCornerShape(28.dp))) {
            Composer()
        }
        // The inline photo picker can't stay under a call's transcript.
        val hasCall = store.state.voice != null
        LaunchedEffect(hasCall) {
            if (hasCall && attachmentMenu != AttachmentMenuPhase.closed) attachmentMenu = AttachmentMenuPhase.closed
        }
    }

    @Composable
    fun Composer() {
        val presentation = composerPresentation
        val composerExpanded = presentation.expanded
        val composerControls = presentation.controls
        val hold = store.state.dictationHold
        val recording = hold != null
        val draft = store.binding(SessionFeature.State::draft, SessionFeature.Action::binding)
        Column(
            Modifier
                .fillMaxWidth()
                .animateContentSize(if (reducedMotion) snap() else spring(dampingRatio = 1f, stiffness = 630f))
                .attachmentMenuAnchor(attachmentMenuAnchors, { attachmentMenuAnchors.composer = it }, composerLift)
                .clickable(interactionSource = null, indication = null) { messageFieldFocused = true }
                .padding(horizontal = 6.dp)
                .padding(bottom = 6.dp, top = if (composerExpanded) 0.dp else 6.dp)
                .semantics { testTag = "session-composer" },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (hasComposerAttachments) PendingAttachmentsRow()
            Box {
                ComposerLayout(
                    expanded = composerExpanded,
                    input = {
                        Box(Modifier.alpha(if (recording && !composerExpanded) 0f else 1f)) {
                            if (store.state.draft.isEmpty()) {
                                Text(
                                    "Message",
                                    Modifier
                                        .padding(top = if (composerExpanded) ComposerTextInput.expandedTopInset else 0.dp)
                                        .semantics { hideFromAccessibility() },
                                    style = TextStyles.body,
                                    color = SystemColors.placeholderText,
                                )
                            }
                            ComposerTextInput(
                                text = draft.wrappedValue,
                                onText = { new ->
                                    val old = draft.wrappedValue
                                    draft.wrappedValue = new
                                    // Vertical-axis fields swallow the send key as a newline; a single
                                    // appended "\n" is the return key (pastes never match this shape).
                                    if (new == old + "\n") sendMessage(SessionFeature.Action.returnKeyTapped)
                                },
                                isFocused = messageFieldFocused,
                                onFocus = { messageFieldFocused = it },
                                expanded = composerExpanded,
                                onImagePaste = { providers -> startImageImport(providers.map { ComposerImageSource.itemProvider(it) }) },
                                sendTransition = scrollEdgeBridge.sendTransition,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    },
                    leading = {
                        Box(Modifier.alpha(if (recording) 0f else 1f).then(if (recording) Modifier.clearAndSetSemantics {} else Modifier)) {
                            ComposerLeadingControls(composerExpanded, composerControls.secondaryMic)
                        }
                    },
                    trailing = {
                        // `ComposerLayout` places exactly three subviews, so everything at the
                        // trailing edge travels as one group: Send/Stop rightmost, dictation
                        // to its left.
                        // The bar replaces these, the run's Stop included, so its own ■
                        // (Stop recording) is the only Stop while dictating.
                        Row(
                            Modifier.alpha(if (recording) 0f else 1f).then(if (recording) Modifier.clearAndSetSemantics {} else Modifier),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            val dictation = composerControls.dictation
                            if (dictation != null) {
                                DictationTrailingControls(dictation)
                            } else {
                                if (composerControls.secondaryMic == ComposerControls.MicPlacement.trailing) {
                                    // Collapsed mid-run: the trailing control is Stop, so dictation
                                    // keeps a home beside it instead of disappearing.
                                    SecondaryMicButton()
                                }
                                TrailingButton(composerControls.trailing)
                            }
                        }
                    },
                )
                androidx.compose.animation.AnimatedVisibility(
                    visible = hold != null,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = fadeIn(tween(if (reducedMotion) 200 else 280)),
                    exit = fadeOut(tween(if (reducedMotion) 200 else 280)),
                ) {
                    if (hold != null) {
                        DictationScopeBar(
                            pose = dictationPose(hold, isLeaving = transition.targetState == EnterExitState.PostExit),
                            isRunning = store.state.isRunning,
                            canSend = !store.state.isSubmittingPrompt,
                            onVisualsChange = { change -> dictationHoldVisuals = change(dictationHoldVisuals) },
                            onCancel = { store.send(SessionFeature.Action.micPressCancelled) },
                            // The words land in the draft; the keyboard stays as it was.
                            onStop = { store.send(SessionFeature.Action.dictationInsertTapped) },
                            onSend = { store.send(if (it == OpenCodeDelivery.queue) SessionFeature.Action.dictationQueueTapped else SessionFeature.Action.dictationSendTapped) },
                            modifier = Modifier.onGloballyPositioned { dictationBarFrame = it.boundsInRoot() },
                        )
                    }
                }
            }
            val dictationError = store.state.dictationError
            if (store.state.isPreparingDictation && !recording) {
                Row(Modifier.padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = SystemColors.secondaryLabel)
                    Text("Preparing dictation…", style = TextStyles.footnote, color = SystemColors.secondaryLabel)
                }
            } else if (dictationError != null) {
                Text(dictationError, Modifier.padding(horizontal = 14.dp), style = TextStyles.footnote, color = SystemColors.systemRed)
            }
        }
    }

    @Composable
    fun ComposerLeadingControls(expanded: Boolean, secondaryMic: ComposerControls.MicPlacement) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AttachmentPickerButton(
                isLoading = isImporting || fileImporter.isLoading,
                anchors = attachmentMenuAnchors,
                lift = composerLift,
                action = { toggleAttachmentMenu() },
            )
            if (expanded) {
                val name = store.state.currentModelName
                Row(
                    Modifier
                        .heightIn(min = 44.dp)
                        .clickable(interactionSource = null, indication = null, role = Role.Button) {
                            store.send(SessionFeature.Action.modelButtonTapped)
                        }
                        .semantics(mergeDescendants = true) {
                            contentDescription = "Choose model"
                            stateDescription = name
                        },
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(name, style = TextStyles.footnote.medium, color = SystemColors.label, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f, fill = false))
                    SystemImage("chevron.down", null, Modifier.symbolSize(TextStyles.caption2.semibold), tint = SystemColors.label)
                }
                if (secondaryMic == ComposerControls.MicPlacement.leading) {
                    Spacer(Modifier.weight(1f))
                    SecondaryMicButton()
                }
            }
        }
    }

    @Composable
    fun SecondaryMicButton() {
        HoldToDictateHost(Modifier.semantics { contentDescription = "Dictate message" }) {
            ComposerControlLabel(
                systemImage = "mic",
                showsProgress = false,
                font = TextStyles.body.medium,
                foreground = SystemColors.secondaryLabel,
                background = Color.Transparent,
            )
        }
    }

    /// A new recording's hold starts from nothing, before its bar mounts.
    fun beginDictationHold() {
        if (store.state.dictationHold == null) dictationHoldVisuals = dictationHoldVisuals.begin()
        store.send(SessionFeature.Action.micPressBegan)
    }

    /// The mic's touch handling. While recording, the composer row is hidden
    /// under `DictationScopeBar`, but this view stays mounted beneath it
    /// because it owns the finger until it lifts.
    @Composable
    fun HoldToDictateHost(modifier: Modifier = Modifier, label: @Composable () -> Unit) {
        with(HoldToDictateButton) {
            Box(
                modifier
                    .onGloballyPositioned { dictationMicFrame = it.boundsInRoot() }
                    .holdToDictate(
                        onBegan = { beginDictationHold() },
                        onDrag = { dictationDrag = it },
                        onArmed = { store.send(SessionFeature.Action.micPressArmed(it)) },
                        onReleased = { store.send(SessionFeature.Action.micPressReleased) },
                        onLocked = { store.send(SessionFeature.Action.micPressLocked) },
                        onInterrupted = { store.send(SessionFeature.Action.micPressInterrupted) },
                    )
                    .semantics {
                        role = Role.Button
                        stateDescription = "Tap to dictate into your message, or touch and hold to record and send"
                        onClick {
                            if (store.state.isDictating) {
                                store.send(SessionFeature.Action.dictationInsertTapped)
                            } else {
                                beginDictationHold()
                                store.send(SessionFeature.Action.micPressLocked)
                            }
                            true
                        }
                    },
            ) { label() }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    fun TrailingButton(control: ComposerTrailingControl) {
        val state = store.state
        val steers = state.isRunning && control == ComposerTrailingControl.send(enabled = true)
        var showsDelivery by remember { mutableStateOf(false) }
        val haptics = rememberHaptics()
        val label = @Composable {
            ComposerControlLabel(
                systemImage = control.symbolName,
                showsProgress = state.isSubmittingPrompt || state.isWaitingForUploads || (control == ComposerTrailingControl.stop && state.isInterrupting),
                font = if (control == ComposerTrailingControl.stop) TextStyles.caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) else TextStyles.body.medium,
                foreground = control.foreground,
                background = control.background,
            )
        }
        val enabled = !(state.isSubmittingPrompt || (isImporting && control == ComposerTrailingControl.send(enabled = true)) || disablesTrailingButton(control))
        val description = when {
            state.isWaitingForUploads -> "Sending after uploads finish"
            state.isSubmittingPrompt -> "Sending message"
            steers -> "Steer message"
            else -> control.accessibilityLabel
        }
        val semantics = Modifier.semantics {
            contentDescription = description
            testTag = control.accessibilityLabel
        }
        when {
            // Tap steers; touch and hold offers the delivery. The menu keeps its
            // hold while the run's updates redraw the composer.
            steers -> Box {
                Box(
                    semantics.combinedClickable(
                        enabled = enabled,
                        role = Role.Button,
                        onLongClick = {
                            haptics.selection()
                            showsDelivery = true
                        },
                        onClick = { sendMessage() },
                    ),
                ) { label() }
                SystemMenu(
                    expanded = showsDelivery,
                    onDismissRequest = { showsDelivery = false },
                    sections = listOf(
                        SystemMenuSection(
                            listOf(
                                SystemMenuItem("Steer", OpenCodeDelivery.steer.symbolName) {
                                    showsDelivery = false
                                    sendMessage()
                                },
                                SystemMenuItem("Queue", OpenCodeDelivery.queue.symbolName) {
                                    showsDelivery = false
                                    sendMessage(SessionFeature.Action.queueTapped)
                                },
                            ),
                        ),
                    ),
                )
            }
            control == ComposerTrailingControl.mic -> HoldToDictateHost(semantics) { label() }
            else -> Box(
                semantics.clickable(enabled = enabled, interactionSource = null, indication = null, role = Role.Button) {
                    when (control) {
                        ComposerTrailingControl.mic -> store.send(SessionFeature.Action.micTapped)
                        is ComposerTrailingControl.send -> sendMessage()
                        ComposerTrailingControl.stop -> store.send(SessionFeature.Action.interruptTapped)
                        ComposerTrailingControl.call -> store.send(SessionFeature.Action.voiceTapped)
                    }
                },
            ) { label() }
        }
    }

    fun disablesTrailingButton(control: ComposerTrailingControl): Boolean = when (control) {
        ComposerTrailingControl.stop -> store.state.isInterrupting
        is ComposerTrailingControl.send -> !control.enabled
        ComposerTrailingControl.mic, ComposerTrailingControl.call -> false
    }

    @Composable
    fun DictationTrailingControls(controls: ComposerControls.DictationControls) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(
                Modifier
                    .clickable(enabled = !controls.isFinishing, interactionSource = null, indication = null, role = Role.Button) {
                        messageFieldFocused = true
                        store.send(SessionFeature.Action.micTapped)
                    }
                    .semantics { contentDescription = if (controls.isFinishing) "Finishing dictation" else "Stop dictation" },
            ) {
                ComposerControlLabel(
                    systemImage = "stop.fill",
                    showsProgress = controls.isFinishing,
                    font = TextStyles.caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                    foreground = SystemColors.label,
                    background = SystemColors.tertiarySystemFill,
                )
            }
            Box(
                Modifier
                    .clickable(enabled = !controls.isFinishing && canSend && !isImporting, interactionSource = null, indication = null, role = Role.Button) {
                        messageFieldFocused = true
                        store.send(SessionFeature.Action.dictationSendTapped)
                    }
                    .semantics { contentDescription = "Send" },
            ) {
                ComposerControlLabel(
                    systemImage = "arrow.up",
                    showsProgress = false,
                    font = TextStyles.body.medium,
                    foreground = SystemColors.systemBackground,
                    background = SystemColors.label,
                )
            }
        }
    }

    @Composable
    fun PendingAttachmentsRow() {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp, end = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            store.state.pendingAttachments.forEachIndexed { index, attachment ->
                androidx.compose.runtime.key(attachment.id) {
                    if (attachment.isImage) AttachmentThumbnail(attachment, index) else InlineFileChip(attachment, index)
                }
            }
            for (file in store.state.pendingFiles) {
                androidx.compose.runtime.key(file.id) { PendingFileChip(file) }
            }
        }
    }

    @Composable
    fun RemovableAttachment(label: String, identifier: String, onRemove: () -> Unit, content: @Composable () -> Unit) {
        Box(Modifier.semantics(mergeDescendants = false) {}) {
            content()
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 8.dp, y = (-8).dp)
                    .size(44.dp)
                    .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onRemove)
                    .semantics {
                        contentDescription = label
                        testTag = identifier
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(22.dp).background(Color.Black.copy(alpha = 0.65f), CircleShape), contentAlignment = Alignment.Center) {
                    SystemImage("xmark", null, Modifier.size(11.dp), tint = Color.White)
                }
            }
        }
    }

    @Composable
    fun PendingFileChip(file: ComposerFileAttachment) {
        RemovableAttachment(
            label = if (file.isUploading) "Cancel upload of ${file.name}" else "Remove ${file.name}",
            identifier = "remove-pending-file-${file.name}",
            onRemove = { store.send(SessionFeature.Action.fileRemoved(file.id)) },
        ) {
            ComposerFileChip(file = file, canRetry = store.state.supportsFileUploads) {
                store.send(SessionFeature.Action.fileRetryTapped(file.id))
            }
        }
    }

    /// A PDF the selected model reads natively: sent inline, no upload.
    @Composable
    fun InlineFileChip(attachment: OpenCodeOutgoingAttachment, index: Int) {
        val name = attachment.name ?: "File"
        RemovableAttachment(
            label = "Remove $name",
            identifier = "remove-pending-file-$name",
            onRemove = { store.send(SessionFeature.Action.attachmentRemoved(index)) },
        ) {
            FileAttachmentCard(
                name = name,
                symbolName = AttachmentFileType.symbolName(name, attachment.mime),
                width = ComposerFileChip.width,
                height = ComposerFileChip.height,
                modifier = Modifier
                    .background(SystemColors.secondarySystemFill, RoundedCornerShape(22.dp))
                    .semantics(mergeDescendants = true) {
                        contentDescription = "File attachment $name"
                        testTag = "pending-file-$name"
                    },
            ) {
                Text(AttachmentFileType.subtitle(name, attachment.mime, attachment.data.size.toLong()))
            }
        }
    }

    @Composable
    fun AttachmentThumbnail(attachment: OpenCodeOutgoingAttachment, index: Int) {
        RemovableAttachment(
            label = "Remove image attachment ${index + 1}",
            identifier = "remove-pending-attachment-$index",
            onRemove = { store.send(SessionFeature.Action.attachmentRemoved(index)) },
        ) {
            CachedImage(
                attachment.id,
                attachment.data,
                Modifier
                    .size(76.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .semantics {
                        contentDescription = "Image attachment ${index + 1}"
                        testTag = "pending-attachment-$index"
                    },
            )
        }
    }

    fun startImageImport(sources: List<ComposerImageSource>, onAdded: (() -> Unit)? = null) {
        val sessionID = store.state.session?.id
        attachmentError = null
        isImporting = true
        imageImporter.start(
            sources,
            onAttachment = { attachment ->
                if (store.state.session?.id == sessionID) store.send(SessionFeature.Action.attachmentAdded(attachment))
            },
            onCompletion = { result ->
                isImporting = false
                if (store.state.session?.id != sessionID) return@start
                if (result.addedCount > 0) {
                    onAdded?.invoke()
                } else {
                    attachmentError = if (result.sawOversizedSource) {
                        "Choose an image smaller than 20 MB."
                    } else {
                        "This image couldn’t be loaded. Try another photo."
                    }
                }
            },
        )
    }

    fun importPickedFiles(context: Context, uris: List<Uri>) {
        val files = uris.mapNotNull { copyUriToCache(context, it) }
        val images = files.filter { ComposerFileRouting.isInlineImage(it) }
        val others = files.filter { !ComposerFileRouting.isInlineImage(it) }
        if (images.isNotEmpty()) {
            startImageImport(images.map { file -> ComposerImageSource.loader { runCatching { file.readBytes() }.getOrNull() } }, onAdded = ::focusComposer)
        }
        if (others.isNotEmpty()) startFileImport(others.map { ComposerFileSource.url(it) }, onStaged = ::focusComposer)
    }

    data class LargeUpload(
        var files: List<ComposerStagedFile>,
        var onStaged: (() -> Unit)? = null,
    ) {
        val title: String
            get() = "Upload ${byteCount(files.sumOf { it.size })}?"

        val message: String
            get() {
                val large = files.filter { it.size > largeUploadBytes }
                val subject = if (large.size == 1) "“${large[0].name}” is a large file" else "${large.size} files are large"
                return "$subject. The message sends once the upload to your server finishes."
            }

        private fun byteCount(bytes: Long): String = when {
            bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
            else -> "%d MB".format((bytes / 1e6).roundToInt())
        }
    }

    fun startFileImport(sources: List<ComposerFileSource>, onStaged: (() -> Unit)? = null, onFailed: (() -> Unit)? = null) {
        val sessionID = store.state.session?.id
        attachmentError = null
        fileImporter.start(
            sources,
            onStaged = { files ->
                if (store.state.session?.id != sessionID) {
                    files.forEach { ComposerFileStaging.discard(it.url) }
                    return@start
                }
                // The server holds an upload in memory, so very large files ask first.
                if (files.any { it.size > largeUploadBytes }) {
                    largeUpload = LargeUpload(files, onStaged)
                    return@start
                }
                store.send(SessionFeature.Action.filesStaged(files))
                onStaged?.invoke()
            },
            onFailure = { count ->
                if (store.state.session?.id != sessionID) return@start
                onFailed?.invoke()
                attachmentError = if (count == 1) "This file couldn’t be added. Try another file." else "$count files couldn’t be added."
            },
        )
    }

    fun focusComposer() {
        messageFieldFocused = true
    }

    // MARK: Attachment menu

    @Composable
    fun AttachmentMenuOverlayHost() {
        val context = LocalContext.current
        AttachmentMenuOverlay(
            phase = attachmentMenu,
            onPhase = { attachmentMenu = it },
            anchors = attachmentMenuAnchors,
            selection = photoSelection,
            includesVideos = store.state.supportsFileUploads,
            canUseCamera = cameraAvailable(context),
            canAttachFiles = store.state.supportsFileUploads,
            canPaste = clipboardHasImage(context),
            onCamera = { attachmentMenuRestoresFocus = false },
            onCameraCapture = { data -> startImageImport(listOf(ComposerImageSource.data(data)), onAdded = ::focusComposer) },
            onFiles = { attachmentMenuRestoresFocus = false },
            onFilesPicked = { uris -> importPickedFiles(context, uris) },
            onPaste = { providers ->
                attachmentMenuRestoresFocus = true
                startImageImport(providers.map { ComposerImageSource.itemProvider(it) })
            },
            onPhotos = { items ->
                photoSelection.items = items
                syncPickedPhotos()
            },
            pickerReservation = pickerReservation,
            onPickerReservation = { pickerReservation = it },
            reduceMotion = reducedMotion,
        )
        LaunchedEffect(attachmentMenu) {
            if (attachmentMenu != AttachmentMenuPhase.closed || !attachmentMenuRestoresFocus) return@LaunchedEffect
            attachmentMenuRestoresFocus = false
            focusComposer()
        }
        LaunchedEffect(messageFieldFocused) {
            // Typing takes the picker's place, like the keyboard replacing it.
            if (!messageFieldFocused || attachmentMenu == AttachmentMenuPhase.closed) return@LaunchedEffect
            attachmentMenuRestoresFocus = false
            attachmentMenu = AttachmentMenuPhase.closed
        }
        val pending = store.state.hasPendingRequests
        LaunchedEffect(pending) {
            // A pending request banner takes the composer's place.
            if (pending) attachmentMenu = AttachmentMenuPhase.closed
        }
        val present = composerAttachmentIDs
        LaunchedEffect(present) {
            for (orphan in photoSelection.attachmentsChanged(present)) removeComposerAttachment(orphan)
        }
    }

    /// How far the picker card raises the composer.
    val composerLift: Float get() = if (attachmentMenu == AttachmentMenuPhase.photos) pickerReservation else 0f

    val composerAttachmentIDs: Set<UUID>
        get() = store.state.pendingAttachments.mapNotNull { runCatching { UUID.fromString(it.id) }.getOrNull() }.toSet() +
            store.state.pendingFiles.map { it.id }

    fun toggleAttachmentMenu() {
        when (attachmentMenu) {
            AttachmentMenuPhase.closed -> {
                attachmentMenuRestoresFocus = messageFieldFocused
                messageFieldFocused = false
                attachmentMenu = AttachmentMenuPhase.menu
            }
            AttachmentMenuPhase.menu -> attachmentMenu = AttachmentMenuPhase.closed
            AttachmentMenuPhase.photos -> attachmentMenu = AttachmentMenuPhase.menu
        }
    }

    /// Picker taps attach immediately: images on the inline image path,
    /// videos as uploads. Deselecting removes the attachment.
    fun syncPickedPhotos() {
        val change = photoSelection.reconcile()
        change.removed.forEach(::removeComposerAttachment)
        if (change.added.isEmpty()) return
        attachmentMenuRestoresFocus = true
        for (video in change.added.filter { it.isVideo }) {
            startFileImport(listOf(ComposerFileSource.photosPickerItem(video.item, video.id)), onFailed = {
                photoSelection.drop(setOf(video.id))
            })
        }
        if (change.added.none { !it.isVideo }) return
        // One import at a time: restart it with every selected image still loading.
        val present = composerAttachmentIDs
        val images = photoSelection.selectedImages.filter { !present.contains(it.id) }
        startImageImport(images.map { ComposerImageSource.photosPickerItem(it.item, it.id) })
    }

    fun removeComposerAttachment(id: UUID) {
        val index = store.state.pendingAttachments.indexOfFirst { it.id == id.toString() }
        if (index >= 0) {
            store.send(SessionFeature.Action.attachmentRemoved(index))
        } else if (store.state.pendingFiles.any { it.id == id }) {
            store.send(SessionFeature.Action.fileRemoved(id))
        }
    }

    fun cancelImageImport() {
        imageImporter.cancel()
        isImporting = false
        attachmentError = null
    }

    // MARK: Pickers

    @Composable
    fun ProjectPicker() {
        val filter = store.binding(SessionFeature.State::projectFilter, SessionFeature.Action::binding)
        PickerSheet(title = "Project", filter = filter, onCancel = { store.binding(SessionFeature.State::activePicker, SessionFeature.Action::binding).wrappedValue = null }) {
            val projects = store.state.filteredProjects
            when {
                store.state.projects.isEmpty() -> ContentUnavailableView(
                    "No Projects Yet",
                    systemImage = "folder.badge.questionmark",
                    description = "New sessions start in a project. On your computer, run `opencode` inside a project folder once, and it will show up here.",
                    modifier = Modifier.padding(vertical = 48.dp),
                )
                projects.isEmpty() -> SearchUnavailable(store.state.projectFilter)
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(projects, key = { it.id }) { project ->
                        PlainListRow(onClick = { store.send(SessionFeature.Action.projectPicked(project)) }) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(project.name, style = TextStyles.body, color = SystemColors.label)
                                Text(project.directory, style = TextStyles.footnote, color = SystemColors.tertiaryLabel, maxLines = 1, overflow = TextOverflow.StartEllipsis)
                            }
                            if (store.state.draftProject?.id == project.id) {
                                SystemImage("checkmark", null, Modifier.symbolSize(TextStyles.subheadline.semibold), tint = SystemColors.tint)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun ModelPicker() {
        val filter = store.binding(SessionFeature.State::modelFilter, SessionFeature.Action::binding)
        PickerSheet(title = "Model", filter = filter, onCancel = { store.binding(SessionFeature.State::activePicker, SessionFeature.Action::binding).wrappedValue = null }) {
            val state = store.state
            when {
                state.isLoadingModels -> Box(Modifier.fillMaxWidth().heightIn(min = 200.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = SystemColors.secondaryLabel)
                }
                state.filteredModels.isEmpty() -> if (state.isFilteringModels) {
                    SearchUnavailable(state.modelFilter)
                } else {
                    ContentUnavailableView(
                        "No Models",
                        systemImage = "cpu",
                        description = "Connect a provider in OpenCode on your computer, then try again.",
                        modifier = Modifier.padding(vertical = 48.dp),
                    )
                }
                // Filling the sheet keeps the last row's hairline inside the list's clip.
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    if (!state.isFilteringModels && state.favoriteModels.isNotEmpty()) {
                        item(key = "favorites") { PlainSectionHeader("Favorites") }
                        items(state.favoriteModels, key = { "favorite-" + it.id }) { ModelRow(it) }
                        item(key = "all") { PlainSectionHeader("All") }
                        items(state.unfavoriteModels, key = { it.id }) { ModelRow(it) }
                    } else {
                        items(state.filteredModels, key = { it.id }) { ModelRow(it) }
                    }
                }
            }
        }
    }

    /// Laid out at the large detent: the medium detent shows its top half
    /// and dragging up reveals the rest, as `[.medium, .large]` does.
    @Composable
    fun PickerSheet(title: String, filter: Binding<String>, onCancel: () -> Unit, content: @Composable () -> Unit) {
        NavigationStack(Modifier.fillMaxHeight()) {
            NavigationTitle(title, displayMode = TitleDisplayMode.inline)
            ToolbarItem(placement = ToolbarItemPlacement.cancellationAction) {
                SystemButton("Cancel", action = onCancel)
            }
            PickerSearchField(filter)
            content()
        }
    }

    @Composable
    fun ModelRow(model: OpenCodeModel) {
        val isCurrent = store.state.currentModel == model.ref
        val isFavorite = store.state.favoriteModelIDs.contains(model.id)
        PlainListRow(onClick = { store.send(SessionFeature.Action.modelPicked(model)) }) {
            Text(
                model.name,
                Modifier.weight(1f),
                style = if (isCurrent) TextStyles.body.semibold else TextStyles.body,
                color = if (isCurrent) SystemColors.tint else SystemColors.label,
                maxLines = 1,
            )
            Text(model.providerID, style = TextStyles.footnote, color = SystemColors.tertiaryLabel, maxLines = 1)
            if (isCurrent) SystemImage("checkmark", null, Modifier.symbolSize(TextStyles.subheadline.semibold), tint = SystemColors.tint)
            Box(
                Modifier
                    .size(44.dp)
                    .clickable(interactionSource = null, indication = null, role = Role.Button) {
                        store.send(SessionFeature.Action.toggleFavoriteModel(model))
                    }
                    .semantics {
                        contentDescription = if (isFavorite) "Remove from Favorites" else "Add to Favorites"
                        stateDescription = model.name
                    },
                contentAlignment = Alignment.Center,
            ) {
                SystemImage(
                    if (isFavorite) "star.fill" else "star",
                    null,
                    Modifier.symbolSize(TextStyles.subheadline),
                    tint = if (isFavorite) SystemColors.systemYellow else SystemColors.secondaryLabel,
                )
            }
        }
    }

    companion object {
        const val largeUploadBytes: Long = 200L * 1024 * 1024
    }
}

/// SwiftUI's `ViewThatFits(in: .horizontal)`: the first option whose ideal
/// width fits, else the last.
@Composable
private fun ViewThatFits(vararg options: @Composable () -> Unit) {
    SubcomposeLayout { constraints ->
        val loose = constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity)
        var chosen = options.size - 1
        for (index in 0 until options.size - 1) {
            // Measured in its own slot, hidden from accessibility: only the chosen option shows.
            val probe = subcompose("probe-$index") { Box(Modifier.clearAndSetSemantics {}) { options[index]() } }
            val width = probe.maxOfOrNull { it.maxIntrinsicWidth(constraints.maxHeight) } ?: 0
            if (width <= constraints.maxWidth) {
                chosen = index
                break
            }
        }
        val placeables = subcompose("option-$chosen", options[chosen]).map { it.measure(constraints.copy(minWidth = 0)) }
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else placeables.maxOfOrNull { it.width } ?: 0
        layout(width, placeables.maxOfOrNull { it.height } ?: 0) { placeables.forEach { it.place(0, 0) } }
    }
}

/// `.searchable(text:, placement: .navigationBarDrawer(displayMode: .always))`.
@Composable
private fun PickerSearchField(text: Binding<String>) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .heightIn(min = 44.dp)
            .background(SystemColors.tertiarySystemFill, CircleShape)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SystemImage("magnifyingglass", null, Modifier.symbolSize(TextStyles.body), tint = SystemColors.secondaryLabel)
        Box(Modifier.weight(1f)) {
            if (text.wrappedValue.isEmpty()) Text("Search", style = TextStyles.body, color = SystemColors.placeholderText)
            BasicTextField(
                value = text.wrappedValue,
                onValueChange = { text.wrappedValue = it },
                singleLine = true,
                textStyle = TextStyles.body.copy(color = SystemColors.label),
                cursorBrush = SolidColor(SystemColors.tint),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search" },
            )
        }
    }
}

@Composable
private fun SearchUnavailable(query: String) {
    ContentUnavailableView(
        "No Results for “$query”",
        systemImage = "magnifyingglass",
        description = "Check the spelling or try a new search.",
        modifier = Modifier.padding(vertical = 48.dp),
    )
}

@Composable
private fun PlainSectionHeader(title: String) {
    Text(
        title,
        Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp).semantics { heading() },
        style = TextStyles.subheadline.semibold,
        color = SystemColors.secondaryLabel,
    )
}

/// A row of a plain `List`: full width, a hairline under it from the text's edge.
@Composable
private fun PlainListRow(onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
        Box(Modifier.padding(start = 16.dp)) { HairlineRule() }
    }
}

@Composable
private fun FullScreenCover(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize()) { content() }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Message", text))
}

// MARK: - Transcript

/// Owns everything below the header and above the composer. The list owns the
/// scroll mechanics; each transcript entry is still its own composable.
@Composable
fun SessionTranscriptView(
    store: Store<SessionFeature.State, SessionFeature.Action>,
    scrollEdgeBridge: SessionScrollEdgeBridge,
    dismissKeyboard: () -> Unit,
    onPreviewImage: (TranscriptImagePreview) -> Unit,
    onOpenFile: (AgentFileReference) -> Unit,
    /// The button itself lives on the composer's pill row.
    showsJumpButton: Binding<Boolean>,
    jumpRequest: Int,
    composerInset: () -> Float,
    keyboardIsMoving: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val state = store.state
    val isRunning = state.isRunning
    val visibleItems = state.visibleItems
    val entries = remember(visibleItems, isRunning) { TranscriptEntry.build(from = visibleItems, isRunning = isRunning) }
    val density = LocalDensity.current
    val topInset = WindowInsets.statusBars.getTop(density) + with(density) { TranscriptMetrics.headerBand.dp.toPx() }
    TranscriptListView(
        store = store,
        configuration = TranscriptListViewController.Configuration(
            sessionID = state.session?.id,
            historyGeneration = state.historyGeneration,
            entries = entries,
            hasEarlierItems = state.hasEarlierItems,
            isLoadingEarlier = state.isLoadingEarlier,
            earlierHistoryError = state.earlierHistoryError,
            isLoading = state.isLoading,
            isRunning = isRunning,
            hasPendingRequests = state.hasPendingRequests,
            isDraft = state.isDraft,
            // Steering messages land at the next step, so they sit in the
            // transcript as dim bubbles; queued ones wait in the pill.
            queuedInputs = state.queuedInputRows.filter { !it.waitsInQueuedPill },
            sentItemID = state.lastSentItemID,
            sendGeneration = state.sendGeneration,
        ),
        showsJumpButton = showsJumpButton,
        jumpRequest = jumpRequest,
        composerInset = composerInset,
        keyboardIsMoving = keyboardIsMoving,
        topInset = topInset,
        scrollEdgeBridge = scrollEdgeBridge,
        usesSystemEdge = false,
        dismissKeyboard = dismissKeyboard,
        onPreviewImage = onPreviewImage,
        onOpenFile = onOpenFile,
        modifier = modifier,
    )
}

// MARK: - Shared pieces

/// Decodes image data once and caches the result; decoding in a list's
/// composition would otherwise re-decode on every frame.
object CachedImage {
    private val cache = LruCache<String, ImageBitmap>(64)

    @Composable
    operator fun invoke(id: String, data: ByteArray, modifier: Modifier = Modifier) {
        val image = remember(id) { decoded(id, data) } ?: return Box(modifier)
        Image(bitmap = image, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
    }

    fun decoded(id: String, data: ByteArray): ImageBitmap? {
        cache.get(id)?.let { return it }
        val image = BitmapFactory.decodeByteArray(data, 0, data.size)?.asImageBitmap() ?: return null
        cache.put(id, image)
        return image
    }
}

@Composable
private fun SyncingSessionTitle(title: String, isSyncing: Boolean, isLastKnown: Boolean) {
    val reducedMotion = LocalReduceMotion.current
    // A new title replaces the old one word-softly: the old drifts up and
    // fades out, the new rises in.
    AnimatedContent(
        targetState = title,
        transitionSpec = {
            if (reducedMotion) {
                fadeIn(tween(450)) togetherWith fadeOut(tween(450))
            } else {
                (slideInVertically(tween(450)) { it / 3 } + fadeIn(tween(450))) togetherWith
                    (slideOutVertically(tween(450)) { -it / 3 } + fadeOut(tween(450)))
            }
        },
        label = "session-title",
    ) { value ->
        Text(
            value,
            Modifier.titleShimmer(isSyncing),
            style = TextStyles.subheadline.semibold,
            color = if (isLastKnown && !isSyncing) SystemColors.secondaryLabel else SystemColors.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/// The syncing look of a title: dimmed, with a highlight sweeping across
/// it. Used while a session resyncs and while the server writes a title.
@Composable
fun Modifier.titleShimmer(active: Boolean): Modifier {
    if (!active) return this
    if (LocalReduceMotion.current) return alpha(0.4f)
    val phase by rememberInfiniteTransition().animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_400, easing = LinearEasing)),
        label = "title-shimmer",
    )
    val highlight = SystemColors.label
    return alpha(0.4f).drawWithContent {
        drawContent()
        val band = size.width * 0.55f
        val x = size.width * (phase * 1.75f - 0.55f)
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(Color.Transparent, highlight.copy(alpha = 0.9f), Color.Transparent),
                start = Offset(x, 0f),
                end = Offset(x + band, 0f),
            ),
            blendMode = BlendMode.SrcAtop,
        )
    }
}

/// One activity animation for the session, independent of transcript layout.
@Composable
fun WorkingIndicator(needsInput: Boolean = false) {
    val reducedMotion = LocalReduceMotion.current
    val title = if (needsInput) "Needs your input" else "Working"
    val paused = reducedMotion || needsInput
    val highlight = SystemColors.label
    val shimmer = if (paused) {
        Modifier
    } else {
        val phase by rememberInfiniteTransition().animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1_600, easing = LinearEasing)),
            label = "working",
        )
        Modifier.drawWithContent {
            drawContent()
            val band = size.width * 0.65f
            val x = size.width * (phase * 1.9f - 0.65f)
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(Color.Transparent, highlight, Color.Transparent),
                    start = Offset(x, 0f),
                    end = Offset(x + band, 0f),
                ),
                blendMode = BlendMode.SrcAtop,
            )
        }
    }
    // The size of the Used N rows around it, a step heavier so it reads as live.
    Text(
        title,
        Modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .then(shimmer)
            .semantics { contentDescription = title },
        style = TextStyles.body.medium,
        color = SystemColors.secondaryLabel,
    )
}

/// The drawer button's glyph: a long stroke over a short one, drawn at whole
/// pixels so it renders at full strength like the symbols beside it.
@Composable
private fun DrawerGlyph() {
    val width = with(LocalDensity.current) { 18.sp.toDp() }
    val color = SystemColors.label
    Canvas(Modifier.size(width, width).semantics { hideFromAccessibility() }) {
        fun pixels(points: Float) = (points * density).roundToInt().toFloat()
        val stroke = pixels(2.5f)
        val long = pixels(width.value)
        val gap = pixels(width.value * 0.3f)
        val short = pixels(width.value * 0.6f)
        val top = ((size.height - (stroke * 2 + gap)) / 2).roundToInt().toFloat()
        drawRoundRect(color, topLeft = Offset(0f, top), size = Size(long, stroke), cornerRadius = CornerRadius(stroke / 2))
        drawRoundRect(color, topLeft = Offset(0f, top + stroke + gap), size = Size(short, stroke), cornerRadius = CornerRadius(stroke / 2))
    }
}
