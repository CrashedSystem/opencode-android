package ai.opencode.feature.system.liveactivity

import ai.opencode.core.strings.localized
import ai.opencode.feature.system.R
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.awaitClose
import java.time.Instant
import java.util.UUID

/**
 * Port of iOS `VoiceCallActivityAttributes`: what a voice call's Live Update (iOS Live
 * Activity) shows. Android renders it as a promoted ongoing notification
 * ([LiveUpdateNotifications.voiceCall]).
 */
data class VoiceCallActivityAttributes(val serverID: String, val sessionID: String) {
    data class ContentState(
        val phase: Phase,
        val isMuted: Boolean,
        /** When the call first connected; the system counts the timer from here. */
        val startedAt: Instant?,
    ) {
        enum class Phase(val rawValue: String) { Connecting("connecting"), Live("live"), Reconnecting("reconnecting"), Ended("ended") }
    }
}

enum class VoiceCallCommand { End, ToggleMute }

/**
 * Port of iOS `LiveActivityCommands`: carries Live Update button taps from the notification's
 * broadcast (in the app's process) to whichever part of the app is listening.
 */
class LiveActivityCommands<Command : Any>(private val endActivities: suspend () -> Unit = {}) {
    private val lock = Any()
    private val listeners = HashMap<UUID, (Command) -> Unit>()

    /** False when nothing is listening. */
    fun send(command: Command): Boolean {
        val current = synchronized(lock) { listeners.values.toList() }
        for (listener in current) listener(command)
        return current.isNotEmpty()
    }

    /**
     * A button tap: it reaches the listener, or, with nothing to reach (the app was relaunched
     * or left Home), the Live Update it came from is ended.
     */
    suspend fun perform(command: Command) {
        if (send(command)) return
        endActivities()
    }

    /** Each caller gets its own stream; every live stream receives every command. */
    fun stream(): Flow<Command> = callbackFlow {
        val id = UUID.randomUUID()
        synchronized(lock) { listeners[id] = { trySend(it) } }
        awaitClose { synchronized(lock) { listeners.remove(id) } }
    }.buffer(8, BufferOverflow.DROP_OLDEST)

    companion object {
        /** iOS `VoiceCallCommands.shared`. */
        val voiceCall = LiveActivityCommands<VoiceCallCommand>(endActivities = { LiveVoiceActivity.endAllFromCommand() })
        /** iOS `DictationCommands.shared`. */
        val dictation = LiveActivityCommands<DictationCommand>(endActivities = { LiveDictationActivity.endAllFromCommand() })
    }
}

/** Port of iOS `EndVoiceCallIntent` / `ToggleVoiceMuteIntent`: the call's Live Update buttons. */
object VoiceCallIntents {
    val END_TITLE: String get() = localized(R.string.system_call_end_title)
    val TOGGLE_MUTE_TITLE: String get() = localized(R.string.system_call_toggle_mute_title)
}
