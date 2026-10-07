@file:PortedFrom("OpenCode/LiveActivity/VoiceLiveActivityClient.swift", "553a407", partial = "VoiceLiveActivity, the client with a no-op live value, LiveActivityStatus; LiveVoiceActivity waits for the voice call slice")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.tca.DependencyKey
import ai.opencode.mobilecode.tca.DependencyValues
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/// How a voice call reads on the Lock Screen and in the Dynamic Island.
object VoiceLiveActivity {
    /// The part of a call the activity shows; observing only this keeps the
    /// call's captions and audio traffic from re-running the mapping.
    data class Call(
        val phase: VoiceFeature.State.Phase,
        val isMuted: Boolean,
        val isReconnecting: Boolean,
        val serverID: String,
        val sessionID: String,
    ) {
        constructor(voice: VoiceFeature.State) : this(
            phase = voice.phase,
            isMuted = voice.isMuted,
            // Already delayed: a reconnect shorter than a second never sets it.
            isReconnecting = voice.isReconnecting,
            serverID = voice.target.server.id,
            sessionID = voice.target.sessionID ?: "",
        )
    }

    fun content(
        `for`: VoiceFeature.State?,
        previous: VoiceCallActivityAttributes.ContentState?,
        now: Instant,
    ): VoiceCallActivityAttributes.ContentState? = content(`for`?.let(::Call), previous = previous, now = now)

    /// Nil when there is no call, including one the user or model hung up
    /// (which stays in state, ended, while its bar sweeps out).
    /// Calls that stop any other way read “Call ended” before dismissing.
    fun content(
        `for`: Call?,
        previous: VoiceCallActivityAttributes.ContentState?,
        now: Instant,
    ): VoiceCallActivityAttributes.ContentState? {
        val call = `for` ?: return null
        val phase = when (call.phase) {
            VoiceFeature.State.Phase.connecting -> VoiceCallActivityAttributes.ContentState.Phase.connecting
            VoiceFeature.State.Phase.live, is VoiceFeature.State.Phase.endingAfterGoodbye ->
                if (call.isReconnecting) VoiceCallActivityAttributes.ContentState.Phase.reconnecting else VoiceCallActivityAttributes.ContentState.Phase.live
            VoiceFeature.State.Phase.ended -> return null
            is VoiceFeature.State.Phase.failed, VoiceFeature.State.Phase.dropped -> VoiceCallActivityAttributes.ContentState.Phase.ended
        }
        val carriedStart = if (previous?.phase == VoiceCallActivityAttributes.ContentState.Phase.ended) null else previous?.startedAt
        val startedAt = carriedStart ?: if (
            phase == VoiceCallActivityAttributes.ContentState.Phase.live || phase == VoiceCallActivityAttributes.ContentState.Phase.reconnecting
        ) {
            now
        } else {
            null
        }
        return VoiceCallActivityAttributes.ContentState(
            phase = phase,
            isMuted = call.isMuted,
            startedAt = if (phase == VoiceCallActivityAttributes.ContentState.Phase.ended) previous?.startedAt else startedAt,
        )
    }

    fun attributes(`for`: Call): VoiceCallActivityAttributes =
        VoiceCallActivityAttributes(serverID = `for`.serverID, sessionID = `for`.sessionID)
}

class VoiceLiveActivityClient(
    /// Shows `content` in the call's one activity, starting it if needed; nil
    /// dismisses it at once and an ended state dismisses it after a moment.
    /// `attributes` are only read when an activity starts.
    var update: suspend (VoiceCallActivityAttributes.ContentState?, VoiceCallActivityAttributes?) -> Unit,
    /// Activities a previous launch left behind; no call survives a relaunch.
    var endAll: suspend () -> Unit,
    /// Button taps from the activity.
    var commands: () -> Flow<VoiceCallCommand>,
) {
    companion object : DependencyKey<VoiceLiveActivityClient> {
        // Android has no Live Activities. The voice call slice gives the call
        // an ongoing notification; until then nothing shows and no taps arrive.
        override val liveValue
            get() = VoiceLiveActivityClient(
                update = { _, _ -> },
                endAll = {},
                commands = { emptyFlow() },
            )

        override val testValue
            get() = VoiceLiveActivityClient(
                update = { _, _ -> },
                endAll = {},
                commands = { emptyFlow() },
            )
    }
}

var DependencyValues.voiceLiveActivity: VoiceLiveActivityClient
    get() = this[VoiceLiveActivityClient]
    set(value) {
        this[VoiceLiveActivityClient] = value
    }

/// What a problem report says about Live Activities: whether Settings allows
/// them and what the last attempt did. Readable off the main actor.
class LiveActivityStatus(
    var isEnabled: Boolean? = null,
    var last: String? = null,
) {
    val summary: String?
        get() {
            if (isEnabled == null && last == null) return null
            val enabled = when (isEnabled) {
                true -> "On"
                false -> "Off in Settings"
                null -> "Unknown"
            }
            return listOfNotNull(enabled, last).joinToString(" · ")
        }

    class Holder {
        private val lock = Any()
        var value = LiveActivityStatus()

        fun <T> withLock(block: (LiveActivityStatus) -> T): T = synchronized(lock) { block(value) }
    }

    companion object {
        val current = Holder()
    }
}
