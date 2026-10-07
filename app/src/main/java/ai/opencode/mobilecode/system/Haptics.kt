package ai.opencode.mobilecode.system

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner

enum class VoiceHaptic {
    CallStarted,
    SweepPass,
    Connected,
    Muted,
    Unmuted,
    Ended,
}

class Haptics(
    private val context: Context,
    private val resumed: () -> Boolean = { true },
) {
    fun selection() = play(Kind.Selection)
    fun impact() = play(Kind.Impact)
    fun success() = play(Kind.Success)
    fun error() = play(Kind.Error)
    fun voice(kind: VoiceHaptic) = play(kind.toKind())

    private fun play(kind: Kind) {
        if (!resumed()) return
        val vibrator = context.getSystemService(Vibrator::class.java) ?: return
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(effect(vibrator, kind))
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return remember(context, lifecycle) {
        Haptics(context) { lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
    }
}

private enum class Kind {
    Selection,
    Impact,
    Success,
    Error,
    CallStarted,
    SweepPass,
    Connected,
    Muted,
    Unmuted,
    Ended,
}

private fun VoiceHaptic.toKind() = when (this) {
    VoiceHaptic.CallStarted -> Kind.CallStarted
    VoiceHaptic.SweepPass -> Kind.SweepPass
    VoiceHaptic.Connected -> Kind.Connected
    VoiceHaptic.Muted -> Kind.Muted
    VoiceHaptic.Unmuted -> Kind.Unmuted
    VoiceHaptic.Ended -> Kind.Ended
}

private fun effect(vibrator: Vibrator, kind: Kind): VibrationEffect {
    val click = VibrationEffect.Composition.PRIMITIVE_CLICK
    val tick = VibrationEffect.Composition.PRIMITIVE_TICK
    val thud = VibrationEffect.Composition.PRIMITIVE_THUD
    if (!vibrator.areAllPrimitivesSupported(click, tick, thud)) return predefined(kind)
    val composition = VibrationEffect.startComposition()
    when (kind) {
        Kind.Selection -> composition.addPrimitive(tick, 0.8f)
        Kind.Impact -> composition.addPrimitive(click, 0.40f)
        Kind.Success, Kind.Connected -> composition
            .addPrimitive(click, 0.50f)
            .addPrimitive(tick, 0.80f, 60)
        Kind.Error -> composition
            .addPrimitive(thud, 0.80f)
            .addPrimitive(click, 0.90f, 40)
            .addPrimitive(click, 0.70f, 40)
        Kind.CallStarted -> composition.addPrimitive(click, 0.75f)
        Kind.SweepPass -> composition.addPrimitive(click, 0.50f)
        Kind.Muted -> composition.addPrimitive(thud, 0.60f)
        Kind.Unmuted -> composition.addPrimitive(click, 0.65f)
        Kind.Ended -> composition.addPrimitive(thud, 0.85f)
    }
    return composition.compose()
}

private fun predefined(kind: Kind): VibrationEffect {
    val effect = when (kind) {
        Kind.Success, Kind.Connected -> VibrationEffect.EFFECT_DOUBLE_CLICK
        Kind.Error -> VibrationEffect.EFFECT_HEAVY_CLICK
        Kind.CallStarted, Kind.Unmuted -> VibrationEffect.EFFECT_CLICK
        else -> VibrationEffect.EFFECT_TICK
    }
    return VibrationEffect.createPredefined(effect)
}
