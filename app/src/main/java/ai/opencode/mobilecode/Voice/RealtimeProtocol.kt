@file:PortedFrom("OpenCode/Voice/RealtimeProtocol.swift", "4084466", partial = "RealtimeJSON")

package ai.opencode.mobilecode

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

// OpenAI Realtime wire protocol (GA shape, `session.type: "realtime"`), kept
// free of audio and OpenCode knowledge so it can be tested from fixtures.

/// Minimal JSON value for tool schemas and function arguments.
@Serializable(with = RealtimeJSON.Serializer::class)
sealed interface RealtimeJSON {
    data class string(val value: String) : RealtimeJSON

    data class number(val value: Double) : RealtimeJSON

    data class bool(val value: Boolean) : RealtimeJSON

    data object `null` : RealtimeJSON

    data class array(val value: List<RealtimeJSON>) : RealtimeJSON

    data class `object`(val value: Map<String, RealtimeJSON>) : RealtimeJSON

    operator fun get(key: String): RealtimeJSON? = (this as? `object`)?.value?.get(key)

    val stringValue: String?
        get() = (this as? string)?.value

    object Serializer : KSerializer<RealtimeJSON> {
        override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

        override fun deserialize(decoder: Decoder): RealtimeJSON = fromElement((decoder as JsonDecoder).decodeJsonElement())

        override fun serialize(encoder: Encoder, value: RealtimeJSON) {
            (encoder as JsonEncoder).encodeJsonElement(toElement(value))
        }

        private fun fromElement(element: JsonElement): RealtimeJSON = when (element) {
            JsonNull -> `null`
            is JsonPrimitive -> when {
                element.isString -> string(element.content)
                element.booleanOrNull != null -> bool(element.booleanOrNull == true)
                else -> number(element.doubleOrNull ?: element.content.toDouble())
            }
            is JsonArray -> array(element.map(::fromElement))
            is JsonObject -> `object`(element.mapValues { fromElement(it.value) })
        }

        private fun toElement(value: RealtimeJSON): JsonElement = when (value) {
            is string -> JsonPrimitive(value.value)
            is number -> JsonPrimitive(value.value)
            is bool -> JsonPrimitive(value.value)
            `null` -> JsonNull
            is array -> JsonArray(value.value.map(::toElement))
            is `object` -> JsonObject(value.value.mapValues { toElement(it.value) })
        }
    }
}
