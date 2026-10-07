@file:PortedFrom("OpenCode/HapticsClient.swift", "553a407", partial = "HapticsClient on system Haptics; LiveHaptics scheduler not ported")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.Haptics
import ai.opencode.mobilecode.system.ProcessInfo
import ai.opencode.mobilecode.system.VoiceHaptic
import ai.opencode.mobilecode.tca.DependencyKey
import ai.opencode.mobilecode.tca.DependencyValues
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner

class HapticsClient(
    var selection: () -> Unit,
    var impact: () -> Unit,
    var success: () -> Unit,
    var error: () -> Unit,
    /// A pending request arrived and opened for review.
    var attention: () -> Unit = {},
    var warning: () -> Unit = {},
    var voice: (VoiceCallHaptic) -> Unit = {},
    var drawer: () -> Unit = {},
) {
    companion object : DependencyKey<HapticsClient> {
        override val liveValue get() = live(OpenCodeApp.instance)

        override val testValue get() = HapticsClient(
            selection = {},
            impact = {},
            success = {},
            error = {},
            attention = {},
            warning = {},
            voice = {},
            drawer = {},
        )
    }
}

var DependencyValues.haptics: HapticsClient
    get() = this[HapticsClient]
    set(value) {
        this[HapticsClient] = value
    }

private fun live(context: Context?): HapticsClient {
    val playback = context?.let { Haptics(it) { processResumed() } }
    fun fire(play: (Haptics) -> Unit) {
        if (labSuppressesHardware()) return
        playback?.let(play)
    }
    return HapticsClient(
        selection = { fire { it.selection() } },
        impact = { fire { it.impact() } },
        success = { fire { it.success() } },
        error = { fire { it.error() } },
        attention = { fire { it.impact() } },
        warning = { fire { it.error() } },
        voice = { haptic -> fire { it.voice(haptic.toSystem()) } },
        drawer = { fire { it.impact() } },
    )
}

private fun processResumed(): Boolean = runCatching {
    ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
}.getOrDefault(false)

/// Lab runs have no hardware haptics unless `OPENCODE_LAB_VOICE_HAPTICS` is set.
private fun labSuppressesHardware(): Boolean {
    if (!BuildConfig.DEBUG) return false
    val env = ProcessInfo.processInfo.environment
    if (env.containsKey("OPENCODE_LAB_VOICE_HAPTICS")) return false
    return env["OPENCODE_LAB_VOICE_DEMO"] != null ||
        env["OPENCODE_LAB_REALTIME_URL"] != null ||
        env["OPENCODE_LAB_LIVE_URL"] != null ||
        env["OPENCODE_LAB_SERVER_URL"] != null
}

private fun VoiceCallHaptic.toSystem(): VoiceHaptic = when (this) {
    VoiceCallHaptic.callStarted -> VoiceHaptic.CallStarted
    VoiceCallHaptic.sweepPass -> VoiceHaptic.SweepPass
    VoiceCallHaptic.connected -> VoiceHaptic.Connected
    VoiceCallHaptic.muted -> VoiceHaptic.Muted
    VoiceCallHaptic.unmuted -> VoiceHaptic.Unmuted
    VoiceCallHaptic.ended -> VoiceHaptic.Ended
}
