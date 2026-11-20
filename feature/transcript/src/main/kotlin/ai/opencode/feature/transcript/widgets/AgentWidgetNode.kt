package ai.opencode.feature.transcript.widgets

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * The view tree a step returns (the contract's Nodes): every node is `{ type, ...props }`.
 * Unknown props are ignored and unknown node types are dropped from their parent; a known node
 * with a missing or mistyped required prop fails the whole view. An unknown value of an
 * enumerated prop (a tone, a style) falls back to its default.
 */
@Immutable
sealed interface AgentWidgetNode {
    enum class Tone { Default, Muted, Accent, Success, Warning, Error }
    enum class TextStyle { Title, Body, Caption, Code }
    enum class Align { Start, Center, End, Stretch }
    enum class Variant { Primary, Secondary, Danger }

    data class Column(override val children: List<AgentWidgetNode>, val gap: Int?, val align: Align) : AgentWidgetNode
    data class Row(override val children: List<AgentWidgetNode>, val gap: Int?, val align: Align, val wrap: Boolean) : AgentWidgetNode
    data class Text(val text: String, val style: TextStyle, val tone: Tone) : AgentWidgetNode
    data class Markdown(val text: String) : AgentWidgetNode
    data class Image(val src: String, val alt: String?, val aspect: Float?) : AgentWidgetNode
    data class Carousel(val id: String?, val index: Int?, override val children: List<AgentWidgetNode>) : AgentWidgetNode
    data class Card(val id: String?, val selected: Boolean, override val children: List<AgentWidgetNode>) : AgentWidgetNode
    data class Button(val id: String, val label: String, val variant: Variant, val disabled: Boolean) : AgentWidgetNode
    data class Checkbox(val id: String, val label: String, val checked: Boolean, val disabled: Boolean) : AgentWidgetNode
    data class Slider(val id: String, val label: String?, val value: Float, val min: Float, val max: Float, val step: Float?) : AgentWidgetNode
    data class Select(val id: String, val label: String?, val options: List<Option>, val value: List<String>, val multiple: Boolean) : AgentWidgetNode {
        data class Option(val value: String, val label: String, val description: String?)
    }
    data class Input(val id: String, val label: String?, val value: String, val placeholder: String?, val multiline: Boolean) : AgentWidgetNode
    data object Divider : AgentWidgetNode
    data class Canvas(val id: String?, val height: Float, val width: Float?, val fps: Int, val duration: Float?, val alt: String?) : AgentWidgetNode
    data class Shader(
        val id: String?,
        val height: Float,
        val width: Float?,
        val wgsl: String,
        val values: FloatArray,
        val fps: Int,
        val alt: String?,
        val poster: String?,
    ) : AgentWidgetNode {
        override fun equals(other: Any?): Boolean = other is Shader && id == other.id && height == other.height &&
            width == other.width && wgsl == other.wgsl && values.contentEquals(other.values) && fps == other.fps &&
            alt == other.alt && poster == other.poster

        override fun hashCode(): Int = (wgsl.hashCode() * 31 + values.contentHashCode()) * 31 + height.hashCode()
    }

    val children: List<AgentWidgetNode> get() = emptyList()

    companion object {
        const val MAX_FPS = 60
        const val MAX_SHADER_BYTES = 16 * 1024

        /** Reads a view, or throws [InvalidView] saying why it isn't one. */
        fun parse(element: JsonElement): AgentWidgetNode =
            node(element) ?: throw InvalidView("invalid view: unknown root node type")

        /** Visits [node] and every node under it, depth first. */
        fun walk(node: AgentWidgetNode, visit: (AgentWidgetNode) -> Unit) {
            visit(node)
            for (child in node.children) walk(child, visit)
        }

        private fun node(element: JsonElement): AgentWidgetNode? {
            val o = element as? JsonObject ?: throw InvalidView("invalid view: a node must be an object")
            val type = o.string("type") ?: throw InvalidView("invalid view: a node needs a type")
            return when (type) {
                "column" -> Column(o.children(), o.gap(), o.enum("align", Align.Stretch))
                "row" -> Row(o.children(), o.gap(), o.enum("align", Align.Start).let { if (it == Align.Stretch) Align.Start else it }, o.bool("wrap"))
                "text" -> Text(o.required(type, "text"), o.enum("style", TextStyle.Body), o.enum("tone", Tone.Default))
                "markdown" -> Markdown(o.required(type, "text"))
                "image" -> Image(o.required(type, "src"), o.string("alt"), o.number("aspect")?.toFloat()?.takeIf { it > 0f && it.isFinite() })
                "carousel" -> Carousel(o.string("id"), o.number("index")?.toInt(), o.children())
                "card" -> Card(o.string("id"), o.bool("selected"), o.children())
                "button" -> Button(o.required(type, "id"), o.required(type, "label"), o.enum("variant", Variant.Secondary), o.bool("disabled"))
                "checkbox" -> Checkbox(o.required(type, "id"), o.required(type, "label"), o.requiredBool(type, "checked"), o.bool("disabled"))
                "slider" -> {
                    val min = o.requiredNumber(type, "min").toFloat()
                    val max = o.requiredNumber(type, "max").toFloat()
                    Slider(o.required(type, "id"), o.string("label"), o.requiredNumber(type, "value").toFloat(), min, maxOf(min, max), o.number("step")?.toFloat()?.takeIf { it > 0f })
                }
                "select" -> {
                    val options = (o["options"] as? JsonArray ?: throw InvalidView("invalid view: select needs options")).map { option ->
                        val p = option as? JsonObject ?: throw InvalidView("invalid view: a select option must be an object")
                        Select.Option(p.required("option", "value"), p.required("option", "label"), p.string("description"))
                    }
                    val value = when (val raw = o["value"]) {
                        is JsonPrimitive -> listOfNotNull(raw.takeIf { it.isString }?.content)
                        is JsonArray -> raw.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                        else -> emptyList()
                    }
                    Select(o.required(type, "id"), o.string("label"), options, value, o.bool("multiple"))
                }
                "input" -> Input(o.required(type, "id"), o.string("label"), o.required(type, "value"), o.string("placeholder"), o.bool("multiline"))
                "divider" -> Divider
                "canvas" -> Canvas(
                    o.string("id"),
                    o.requiredNumber(type, "height").toFloat().coerceIn(1f, 2000f),
                    o.number("width")?.toFloat()?.takeIf { it > 0f },
                    (o.number("fps")?.toInt() ?: 0).coerceIn(0, MAX_FPS),
                    o.number("duration")?.toFloat()?.takeIf { it >= 0f },
                    o.string("alt"),
                )
                "shader" -> {
                    val values = FloatArray(16)
                    (o["values"] as? JsonArray)?.take(16)?.forEachIndexed { index, value ->
                        values[index] = (value as? JsonPrimitive)?.doubleOrNull?.toFloat() ?: 0f
                    }
                    Shader(
                        o.string("id"),
                        o.requiredNumber(type, "height").toFloat().coerceIn(1f, 2000f),
                        o.number("width")?.toFloat()?.takeIf { it > 0f },
                        o.required(type, "wgsl"),
                        values,
                        (o.number("fps")?.toInt() ?: MAX_FPS).coerceIn(0, MAX_FPS),
                        o.string("alt"),
                        o.string("poster"),
                    )
                }
                else -> null
            }
        }

        private fun JsonObject.children(): List<AgentWidgetNode> {
            val raw = this["children"] ?: return emptyList()
            if (raw is JsonNull) return emptyList()
            val list = raw as? JsonArray ?: throw InvalidView("invalid view: children must be an array")
            return list.mapNotNull(::node)
        }

        private fun JsonObject.gap(): Int? = number("gap")?.toInt()?.coerceIn(0, 3)

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.number(key: String): Double? =
            (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }

        private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: false

        private fun JsonObject.required(type: String, key: String): String =
            string(key) ?: throw InvalidView("invalid view: $type needs a string $key")

        private fun JsonObject.requiredNumber(type: String, key: String): Double =
            number(key) ?: throw InvalidView("invalid view: $type needs a number $key")

        private fun JsonObject.requiredBool(type: String, key: String): Boolean =
            (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: throw InvalidView("invalid view: $type needs a boolean $key")

        private inline fun <reified E : Enum<E>> JsonObject.enum(key: String, default: E): E {
            val raw = string(key) ?: return default
            return enumValues<E>().firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: default
        }
    }

    class InvalidView(message: String) : Exception(message)
}
