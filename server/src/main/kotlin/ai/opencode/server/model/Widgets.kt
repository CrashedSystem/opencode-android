package ai.opencode.server.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A widget template version (`name@version`): immutable, so its program is cached forever. */
@Serializable
data class OpenCodeWidgetRef(val name: String, val version: String) {
    val key: String get() = "$name@$version"

    /** Names and versions the server accepts, so a ref is safe as a path segment and a file name. */
    val isValid: Boolean
        get() = NAME.matches(name) && VERSION.matches(version)

    private companion object {
        val NAME = Regex("^[a-z][a-z0-9-]{0,47}$")
        val VERSION = Regex("^[0-9a-f]{16}$")
    }
}

/**
 * A widget message (`type: "widget"`): the template and the JSON data every step takes, and how
 * it ended. [data] and [response] stay raw JSON text, passed to the program as they are.
 */
@Serializable
data class OpenCodeWidgetMessage(
    val messageID: String,
    val title: String? = null,
    val template: OpenCodeWidgetRef,
    val data: String,
    val status: Status,
    /** The submitted value's JSON; set only when [status] is [Status.Submitted] (may be `null`). */
    val response: String? = null,
) {
    @Serializable
    enum class Status {
        @SerialName("open") Open,
        @SerialName("submitted") Submitted,
        @SerialName("dismissed") Dismissed;

        companion object {
            fun of(raw: String?): Status? = entries.firstOrNull { it.wireName == raw }
        }

        val wireName: String
            get() = when (this) {
                Open -> "open"
                Submitted -> "submitted"
                Dismissed -> "dismissed"
            }
    }

    /** The label the server names it by in the response prompt: the title, else the template's name. */
    val label: String get() = title?.trimmedWhitespace()?.nonEmpty ?: template.name
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
