@file:PortedFrom("OpenCode/UndeliveredPrompts.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.tca.DependencyKey
import ai.opencode.mobilecode.tca.DependencyValues

/// Drafts whose send failed, kept until a screen shows the failure. If the
/// sending screen went away mid-send (the reader opened another session),
/// reopening that session, or the next draft for a failed create, restores
/// the text with the error, so a send is never silently dropped.
class UndeliveredPrompts {
    data class Entry(
        var pending: SessionFeature.PendingPrompt,
        var error: OpenCodeClientError,
    )

    private val entries = HashMap<String, Entry>()

    /// `sessionID` is nil when the session itself couldn't be created.
    fun keep(pending: SessionFeature.PendingPrompt, error: OpenCodeClientError, serverKey: String, sessionID: String?) {
        synchronized(entries) { entries[key(serverKey, sessionID)] = Entry(pending, error) }
    }

    fun take(serverKey: String, sessionID: String?): Entry? =
        synchronized(entries) { entries.remove(key(serverKey, sessionID)) }

    /// A screen showed this failure itself.
    fun discard(messageID: String) {
        synchronized(entries) { entries.entries.removeIf { it.value.pending.messageID == messageID } }
    }

    private fun key(serverKey: String, sessionID: String?) = "$serverKey|${sessionID ?: "new-session"}"

    companion object : DependencyKey<UndeliveredPrompts> {
        override val liveValue: UndeliveredPrompts
            get() = UndeliveredPrompts()

        override val testValue: UndeliveredPrompts
            get() = UndeliveredPrompts()
    }
}

var DependencyValues.undeliveredPrompts: UndeliveredPrompts
    get() = this[UndeliveredPrompts]
    set(value) {
        this[UndeliveredPrompts] = value
    }
