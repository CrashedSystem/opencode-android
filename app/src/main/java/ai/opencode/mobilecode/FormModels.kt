@file:PortedFrom("OpenCode/FormModels.swift", "553a407")

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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.OffsetDateTime
import java.util.Locale

@Serializable(with = OpenCodeFormValue.Serializer::class)
sealed class OpenCodeFormValue {
    data class string(val value: String) : OpenCodeFormValue()
    data class number(val value: Double) : OpenCodeFormValue()
    data class boolean(val value: Boolean) : OpenCodeFormValue()
    data class strings(val value: List<String>) : OpenCodeFormValue()

    companion object {
        fun typedNumber(text: String, locale: Locale = Locale.getDefault()): OpenCodeFormValue? {
            var cleaned = text.trim()
            val grouping = java.text.DecimalFormatSymbols.getInstance(locale).groupingSeparator
            if (grouping != '\u0000') cleaned = cleaned.replace(grouping.toString(), "")
            val decimal = java.text.DecimalFormatSymbols.getInstance(locale).decimalSeparator
            if (decimal != '.') cleaned = cleaned.replace(decimal, '.')
            return cleaned.toDoubleOrNull()?.let { number(it) }
        }
    }

    object Serializer : KSerializer<OpenCodeFormValue> {
        override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

        override fun deserialize(decoder: Decoder): OpenCodeFormValue {
            val element = (decoder as JsonDecoder).decodeJsonElement()
            val primitive = element as? JsonPrimitive
            if (primitive != null) {
                if (primitive.booleanOrNull != null) return boolean(primitive.booleanOrNull == true)
                primitive.doubleOrNull?.let { return number(it) }
                primitive.contentOrNull?.let { return string(it) }
            }
            val array = element as? JsonArray ?: error("form value")
            return strings(array.map { it.jsonPrimitive.content })
        }

        override fun serialize(encoder: Encoder, value: OpenCodeFormValue) {
            val json = encoder as JsonEncoder
            val element: JsonElement = when (value) {
                is string -> JsonPrimitive(value.value)
                is number -> JsonPrimitive(value.value)
                is boolean -> JsonPrimitive(value.value)
                is strings -> JsonArray(value.value.map { JsonPrimitive(it) })
            }
            json.encodeJsonElement(element)
        }
    }
}

@Serializable
data class OpenCodeFormOption(
    var value: String,
    var label: String,
    var description: String? = null,
) {
    val id: String get() = value
}

@Serializable
data class OpenCodeFormCondition(
    var key: String,
    var op: Operation,
    var value: OpenCodeFormValue,
) {
    @Serializable
    enum class Operation { eq, neq }
}

@Serializable
enum class OpenCodeFormFieldType { string, number, integer, boolean, multiselect, external }

@Serializable
data class OpenCodeFormField(
    var key: String,
    var type: OpenCodeFormFieldType,
    var title: String? = null,
    var description: String? = null,
    var required: Boolean? = null,
    var `when`: List<OpenCodeFormCondition>? = null,
    var format: String? = null,
    var minLength: Int? = null,
    var maxLength: Int? = null,
    var pattern: String? = null,
    var options: List<OpenCodeFormOption>? = null,
    var custom: Boolean? = null,
    var minimum: Double? = null,
    var maximum: Double? = null,
    var minItems: Int? = null,
    var maxItems: Int? = null,
    var url: String? = null,
    @kotlinx.serialization.SerialName("default")
    var defaultValue: OpenCodeFormValue? = null,
) {
    val id: String get() = key

    fun isVisible(answers: Map<String, OpenCodeFormValue>): Boolean {
        return (`when` ?: emptyList()).all { condition ->
            val answer = answers[condition.key] ?: return@all false
            val expected = condition.value
            val matches = if (answer is OpenCodeFormValue.strings && expected is OpenCodeFormValue.string) {
                answer.value.contains(expected.value)
            } else {
                answer == expected
            }
            if (condition.op == OpenCodeFormCondition.Operation.neq) !matches else matches
        }
    }

    fun validationError(value: OpenCodeFormValue?): String? {
        if (type == OpenCodeFormFieldType.external && value != OpenCodeFormValue.boolean(true)) return "Complete this step"
        if (required == true && value == null) return "Required"
        value ?: return null
        return when {
            type == OpenCodeFormFieldType.string && value is OpenCodeFormValue.string -> stringError(value.value)
            (type == OpenCodeFormFieldType.number || type == OpenCodeFormFieldType.integer) && value is OpenCodeFormValue.number ->
                numberError(value.value)
            type == OpenCodeFormFieldType.multiselect && value is OpenCodeFormValue.strings -> stringsError(value.value)
            type == OpenCodeFormFieldType.boolean && value is OpenCodeFormValue.boolean -> null
            type == OpenCodeFormFieldType.external && value is OpenCodeFormValue.boolean -> null
            else -> "Invalid value"
        }
    }

    private fun stringError(text: String): String? {
        if (required == true && text.trim().isEmpty()) return "Required"
        minLength?.let { if (text.length < it) return "Enter at least $it characters" }
        maxLength?.let { if (text.length > it) return "Enter no more than $it characters" }
        pattern?.let { if (!Regex(it).containsMatchIn(text)) return "Invalid format" }
        if (options != null && custom != true && options?.none { it.value == text } == true) return "Choose an available option"
        if (format == "email" && !text.contains("@")) return "Enter a valid email"
        if (format == "uri" && !text.contains("://")) return "Enter a valid URL"
        if (format == "date" && !Regex("""^\d{4}-\d{2}-\d{2}$""").matches(text)) return "Enter a date as YYYY-MM-DD"
        if (format == "date-time" && runCatching { OffsetDateTime.parse(text) }.isFailure) return "Enter a valid date and time"
        return null
    }

    private fun numberError(number: Double): String? {
        minimum?.let { if (number < it) return "Must be at least $it" }
        maximum?.let { if (number > it) return "Must be at most $it" }
        if (type == OpenCodeFormFieldType.integer && number != kotlin.math.round(number)) return "Enter a whole number"
        return null
    }

    private fun stringsError(values: List<String>): String? {
        if (required == true && values.isEmpty()) return "Required"
        minItems?.let { if (values.size < it) return "Choose at least $it" }
        maxItems?.let { if (values.size > it) return "Choose no more than $it" }
        return null
    }
}

@Serializable
data class OpenCodeFormRequest(
    var id: String,
    var sessionID: String,
    var title: String,
    var fields: List<OpenCodeFormField>,
    var metadata: Map<String, OpenCodeJSONValue>? = null,
) {
    val isQuestion: Boolean get() = metadata?.get("kind")?.string == "question"
    val toolCallID: String? get() = metadata?.get("tool")?.`object`?.get("id")?.string
    val toolMessageID: String? get() = metadata?.get("tool")?.`object`?.get("messageID")?.string

    val defaultAnswers: Map<String, OpenCodeFormValue>
        get() = fields.mapNotNull { field ->
            field.defaultValue?.let { field.key to it }
                ?: if (field.type == OpenCodeFormFieldType.external) field.key to OpenCodeFormValue.boolean(false) else null
        }.toMap()

    fun activeFields(answers: Map<String, OpenCodeFormValue>): List<OpenCodeFormField> {
        val active = mutableListOf<OpenCodeFormField>()
        val activeAnswers = answers.toMutableMap()
        for (field in fields) {
            if (field.isVisible(activeAnswers)) active += field
            else activeAnswers.remove(field.key)
        }
        return active
    }

    class ValidationError(override val message: String, val fieldIndex: Int) : Exception(message)

    fun validatedAnswer(answers: Map<String, OpenCodeFormValue>): Result<Map<String, OpenCodeFormValue>> {
        val active = activeFields(answers)
        active.forEachIndexed { index, field ->
            val error = field.validationError(answers[field.key])
            if (error != null) return Result.failure(ValidationError(error, index))
        }
        return Result.success(active.mapNotNull { field -> answers[field.key]?.let { field.key to it } }.toMap())
    }
}
