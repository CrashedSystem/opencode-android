@file:PortedFrom("OpenCode/Voice/VoiceView.swift", "553a407", partial = "hiddenUnderVoiceTranscript")

package ai.opencode.mobilecode

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics

/// What sits above the call bar keeps its place but hides while the call's
/// transcript covers it, so it neither shows through the glass nor stays
/// reachable by VoiceOver.
@Composable
fun Modifier.hiddenUnderVoiceTranscript(isHidden: Boolean): Modifier {
    val reduceMotion = LocalReduceMotion.current
    // `VoiceCallSurface.animation(expanding:)`: unbouncy, quicker to close.
    val opacity by animateFloatAsState(
        if (isHidden) 0f else 1f,
        animationSpec = when {
            reduceMotion -> tween(200)
            isHidden -> spring(dampingRatio = 0.9f, stiffness = 305f)
            else -> spring(dampingRatio = 1f, stiffness = 584f)
        },
        label = "hidden-under-voice-transcript",
    )
    val hidden = if (isHidden) {
        Modifier
            .clearAndSetSemantics {}
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }
    } else {
        Modifier
    }
    return alpha(opacity).then(hidden)
}
