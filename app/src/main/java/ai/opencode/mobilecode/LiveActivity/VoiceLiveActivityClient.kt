@file:PortedFrom("OpenCode/LiveActivity/VoiceLiveActivityClient.swift", "553a407", partial = "LiveActivityStatus")

package ai.opencode.mobilecode

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
