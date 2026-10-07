@file:PortedFrom("OpenCode/Voice/DotScopeSimulation.swift", "4fff88f", partial = "VoiceCallHaptic, DotScopeSimulation.exitDuration")

package ai.opencode.mobilecode

/// Haptic events for a voice call's lifecycle and controls.
enum class VoiceCallHaptic {
    callStarted,
    sweepPass,
    connected,
    muted,
    unmuted,
    ended,
    ;

    companion object {
        /// Maps a mute toggle to its haptic feedback.
        fun forMute(isMuted: Boolean): VoiceCallHaptic = if (isMuted) muted else unmuted
    }
}

/// The CPU half of the dot scope: an LED-matrix oscilloscope whose two traces,
/// you and the agent, braid like a double helix. Each voice is a sum of four
/// travelling sines whose amplitudes follow its loudness through a struck
/// spring, shaped by its spectrum. Amplitudes go into a short history that
/// each point reads delayed by its distance from the voice's own end, so
/// syllables roll outward from their side. Quiet, only the shared carrier is
/// left: two near-flat lines of dim dots crossing with a slow breath.
///
/// The LEDs behave like CRT phosphor: the beam is sampled at 120 Hz, and lit
/// dots fade over a few hundred milliseconds instead of switching off, burning
/// brightest where the beam lingers, so speech leaves soft trails. Behind
/// them, each voice has a glow centred behind its button that swells with it.
///
/// The call's arrival and departure are a scanner: while it connects a single
/// beam sweeps back and forth; once live one last sweep draws the braid behind
/// it; when it ends the beam sweeps on toward End, erasing the braid.
///
/// Stepped at a fixed 240 Hz, so the same inputs always give the same picture.
class DotScopeSimulation {
    companion object {
        /// How long the ending sweep takes; the bar stays up this long after End.
        const val exitDuration: Double = 0.55
    }
}
