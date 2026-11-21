package ai.opencode.feature.transcript.widgets

import ai.opencode.server.model.OpenCodeWidgetMessageValue
import ai.opencode.server.model.OpenCodeWidgetResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** `{ id, type, value? }`; [value] is JSON text. */
data class AgentWidgetEvent(val id: String, val type: Type, val value: String? = null) {
    enum class Type(val wire: String) {
        Press("press"), Change("change"), Pointer("pointer"), Fullscreen("fullscreen"), Key("key"), Focus("focus"),
    }

    val json: String
        get() = buildString {
            append("{\"id\":").append(JsonPrimitive(id).toString()).append(",\"type\":\"").append(type.wire).append('"')
            if (value != null) append(",\"value\":").append(value)
            append('}')
        }

    /** A queued event [next] can stand in for: a newer value of the same control, or a newer move of the same press. */
    fun replaceable(next: AgentWidgetEvent): Boolean = id == next.id && type == next.type && when (type) {
        Type.Change -> true
        Type.Pointer -> value.isMove() && next.value.isMove()
        else -> false
    }

    private fun String?.isMove() = this?.startsWith("{\"kind\":\"move\"") == true

    companion object {
        fun press(id: String) = AgentWidgetEvent(id, Type.Press)
        /** Entering ([on]) or leaving full screen: `{ id: "", type: "fullscreen", value }`. */
        fun fullscreen(on: Boolean) = AgentWidgetEvent("", Type.Fullscreen, on.toString())
        /** Keyboard focus gained or lost: `{ id: "", type: "focus", value }`. */
        fun focus(on: Boolean) = AgentWidgetEvent("", Type.Focus, on.toString())
        /** A key while the widget has keyboard focus; [key] and [code] as in DOM `KeyboardEvent`. */
        fun key(kind: String, key: String, code: String, repeat: Boolean, shift: Boolean, alt: Boolean, ctrl: Boolean, meta: Boolean) =
            AgentWidgetEvent(
                "",
                Type.Key,
                "{\"kind\":\"$kind\",\"key\":${JsonPrimitive(key)},\"code\":${JsonPrimitive(code)},\"repeat\":$repeat," +
                    "\"shift\":$shift,\"alt\":$alt,\"ctrl\":$ctrl,\"meta\":$meta}",
            )
        fun change(id: String, value: Boolean) = AgentWidgetEvent(id, Type.Change, value.toString())
        fun change(id: String, value: Number) = AgentWidgetEvent(id, Type.Change, number(value.toDouble()))
        fun change(id: String, value: String) = AgentWidgetEvent(id, Type.Change, JsonPrimitive(value).toString())
        fun change(id: String, values: List<String>) =
            AgentWidgetEvent(id, Type.Change, values.joinToString(",", "[", "]") { JsonPrimitive(it).toString() })
        fun pointer(id: String, kind: String, x: Float, y: Float, width: Float, height: Float) = AgentWidgetEvent(
            id,
            Type.Pointer,
            "{\"kind\":\"$kind\",\"x\":${number(x.toDouble())},\"y\":${number(y.toDouble())}," +
                "\"width\":${number(width.toDouble())},\"height\":${number(height.toDouble())}}",
        )

        /** Whole numbers without a fraction, as JavaScript prints them. */
        fun number(value: Double): String =
            if (value.isFinite() && value == Math.rint(value) && kotlin.math.abs(value) < 1e15) value.toLong().toString()
            else if (value.isFinite()) value.toString() else "0"
    }
}

/**
 * What a step returned: the next state (JSON text), the view, an answer when it ended the widget,
 * or a message for the agent that leaves it open.
 */
data class AgentWidgetOutput(
    val state: String,
    val view: AgentWidgetNode,
    val response: OpenCodeWidgetResponse?,
    val message: OpenCodeWidgetMessageValue? = null,
    /** The root node's `fullscreen`: true offers full screen, false never, null decides by content. */
    val fullscreen: Boolean? = null,
)

/** The step ABI's inputs, built as JSON text around the message's data and state (which stay text). */
object AgentWidgetStep {
    private val json = Json { ignoreUnknownKeys = true }

    /** The fields every step of a widget shares, parsed once by its run session. */
    fun shared(data: String): String = "{\"data\":$data}"

    // Steps run in a session holding the widget's `data` ([shared]); the input carries the rest.
    @Suppress("UNUSED_PARAMETER")
    fun init(data: String): String = "{\"phase\":\"init\"}"

    @Suppress("UNUSED_PARAMETER")
    fun event(data: String, state: String, event: AgentWidgetEvent): String =
        "{\"phase\":\"event\",\"state\":$state,\"event\":${event.json}}"

    @Suppress("UNUSED_PARAMETER")
    fun draw(data: String, state: String, frame: String): String =
        "{\"phase\":\"draw\",\"state\":$state,\"frame\":$frame}"

    /** Reads a step's output, or throws [AgentWidgetNode.InvalidView] saying why it isn't one. */
    fun output(text: String): AgentWidgetOutput {
        val element = try { json.parseToJsonElement(text) } catch (_: Exception) {
            throw AgentWidgetNode.InvalidView("a step must return an object with state and view")
        }
        val o = element as? JsonObject ?: throw AgentWidgetNode.InvalidView("a step must return an object with state and view")
        val view = AgentWidgetNode.parse(o["view"] ?: throw AgentWidgetNode.InvalidView("a step must return a view"))
        val state = (o["state"] ?: JsonNull).toString()
        val response = when {
            o.containsKey("submit") -> OpenCodeWidgetResponse.Submit((o["submit"] ?: JsonNull).toString())
            (o["dismiss"] as? JsonPrimitive)?.content == "true" -> OpenCodeWidgetResponse.Dismiss
            else -> null
        }
        val message = (o["send"] as? JsonObject)?.let { send ->
            OpenCodeWidgetMessageValue((send["value"] ?: JsonNull).toString(), (send["wake"] as? JsonPrimitive)?.content != "false")
        }
        val fullscreen = ((o["view"] as? JsonObject)?.get("fullscreen") as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()
        return AgentWidgetOutput(state, view, response, if (response == null) message else null, fullscreen)
    }

    /** A submitted value in one line: an object's fields as `key: value`, lists joined, at most 160 characters. */
    fun summary(responseJSON: String?): String {
        val value = responseJSON?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() } ?: return ""
        fun one(v: JsonElement): String = when (v) {
            is JsonNull -> "—"
            is JsonPrimitive -> if (v.isString) v.content else v.doubleOrNull?.let { AgentWidgetEvent.number(it) } ?: v.content
            is kotlinx.serialization.json.JsonArray -> v.joinToString(", ", transform = ::one)
            is JsonObject -> v.toString()
        }
        val text = if (value is JsonObject) value.entries.joinToString(" · ") { (k, v) -> "$k: ${one(v)}" } else one(value)
        return if (text.length > 160) text.take(159) + "…" else text
    }
}
