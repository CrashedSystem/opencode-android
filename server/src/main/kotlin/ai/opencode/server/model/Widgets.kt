package ai.opencode.server.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A widget template version (`name@version`): immutable, so its program is cached forever. */
@Serializable
data class OpenCodeWidgetRef(val name: String, val version: String = "") {
    val key: String get() = "$name@${version.ifEmpty { "latest" }}"

    /** A version is named; without one the program is the template's latest, never cached on disk. */
    val isPinned: Boolean get() = version.isNotEmpty()

    /** Names and versions the server accepts, so a ref is safe as a path segment and a file name. */
    val isValid: Boolean
        get() = NAME.matches(name) && (version.isEmpty() || VERSION.matches(version))

    private companion object {
        val NAME = Regex("^[a-z][a-z0-9-]{0,47}$")
        val VERSION = Regex("^[0-9a-f]{16}$")
    }
}

/** Where a widget stands: open until its one answer, then submitted or dismissed. */
enum class OpenCodeWidgetStatus(val wireName: String) {
    Open("open"),
    Submitted("submitted"),
    Dismissed("dismissed");

    companion object {
        fun of(raw: String?): OpenCodeWidgetStatus? = entries.firstOrNull { it.wireName == raw }
    }
}

/** How the user ended an open widget: the respond endpoint's body. */
sealed interface OpenCodeWidgetResponse {
    /** [json] is the submitted value as JSON text. */
    data class Submit(val json: String) : OpenCodeWidgetResponse
    data object Dismiss : OpenCodeWidgetResponse

    val body: String
        get() = when (this) {
            is Submit -> "{\"value\":$json}"
            Dismiss -> "{\"dismiss\":true}"
        }
}

/** A message a widget sends the agent while it stays open: [json] is the value; without [wake] it waits for the next turn. */
data class OpenCodeWidgetMessageValue(val json: String, val wake: Boolean) {
    val body: String get() = "{\"value\":$json,\"wake\":$wake}"
}

/**
 * The widget plugin's record of one widget (`opencode.widget` `State`): the `widget` tool call it
 * belongs to ([messageID], [callID]), the pinned template, and how it ended. [response] is JSON text.
 */
data class OpenCodeWidgetState(
    val sessionID: String,
    val messageID: String,
    val callID: String,
    val template: OpenCodeWidgetRef,
    val title: String? = null,
    val status: OpenCodeWidgetStatus,
    val response: String? = null,
    val wait: Boolean = false,
    /** Hosts a live template may fetch from (`capabilities.https.hosts`). */
    val httpsHosts: List<String>? = null,
) {
    /** One widget in a session: a call ID is unique only within its message. */
    val key: String get() = key(messageID, callID)

    companion object {
        fun key(messageID: String, callID: String): String = "$messageID/$callID"
    }
}

/** A template version's runnable program and how it runs: `step` (the default) or `live`, with the hosts a live one may fetch from. */
data class OpenCodeWidgetProgram(val program: String, val isLive: Boolean = false, val httpsHosts: List<String>? = null)
