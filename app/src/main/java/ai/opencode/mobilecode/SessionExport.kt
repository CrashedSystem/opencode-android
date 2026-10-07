@file:PortedFrom("OpenCode/SessionExport.swift", "553a407")

package ai.opencode.mobilecode

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/// Turns `GET /api/experimental/session/{id}/export` into a shareable file.
/// Markdown follows the TUI's `formatSessionTranscript` (tools, no thinking);
/// JSON is the server's `SessionTransfer` data, which `opencode import` reads.
object SessionExport {
    class Failure : Exception()

    fun content(body: ByteArray, format: OpenCodeExportFormat): ByteArray {
        val root = runCatching { protocolJson.parseToJsonElement(body.decodeToString()).jsonObject }.getOrNull()
            ?: throw Failure()
        val transfer = root["data"] as? JsonObject ?: throw Failure()
        val info = transfer["info"] as? JsonObject ?: throw Failure()
        val messages = transfer["messages"] as? JsonArray ?: throw Failure()
        val messageObjects = messages.map { it as? JsonObject ?: throw Failure() }
        return when (format) {
            OpenCodeExportFormat.json -> (pretty(transfer) + "\n").toByteArray(Charsets.UTF_8)
            OpenCodeExportFormat.markdown -> markdown(info, messageObjects).toByteArray(Charsets.UTF_8)
        }
    }

    /// A file name for the share sheet: the title without path separators.
    fun fileName(title: String, format: OpenCodeExportFormat): String {
        val cleaned = title.split(fileNameSeparators).filter { it.isNotEmpty() }.joinToString(" ")
        val base = if (cleaned.isEmpty()) "Session" else cleaned.take(80)
        return "$base.${format.fileExtension}"
    }

    fun markdown(info: JsonObject, messages: List<JsonObject>): String {
        val body = messages.mapNotNull { message ->
            when (message.string("type")) {
                "user" -> "## User\n\n${message.string("text") ?: ""}"
                "shell" -> {
                    val output = (message["output"] as? JsonObject)?.string("output") ?: ""
                    "## Shell\n\n```\n$ ${message.string("command") ?: ""}\n$output\n```"
                }
                "assistant" -> {
                    val parts = ((message["content"] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { part ->
                        (part as? JsonObject)?.let { assistantPart(it) }
                    }
                    if (parts.isEmpty()) null else "## Assistant\n\n" + parts.joinToString("\n\n")
                }
                else -> null
            }
        }
        val title = info.string("title")?.takeIf { it.isNotEmpty() } ?: "Session"
        var header = "# $title\n\n**Session ID:** ${info.string("id") ?: ""}"
        val time = info["time"] as? JsonObject
        for ((label, key) in listOf("Created" to "created", "Updated" to "updated")) {
            val milliseconds = time?.number(key) ?: continue
            val date = Instant.ofEpochMilli(milliseconds.toLong())
            header += "\n**$label:** ${dateFormatter.format(date)}"
        }
        return header + "\n\n---\n\n" + body.joinToString("\n\n---\n\n") + "\n"
    }

    private fun assistantPart(part: JsonObject): String? {
        return when (part.string("type")) {
            "text" -> part.string("text")
            "tool" -> {
                val state = part["state"] as? JsonObject ?: JsonObject(emptyMap())
                val input = when (val value = state["input"]) {
                    is JsonPrimitive -> if (value.isString) value.content else pretty(value)
                    null -> "{}"
                    else -> pretty(value)
                }
                val output = when (state.string("status")) {
                    "error" -> (state["error"] as? JsonObject)?.string("message") ?: ""
                    "streaming" -> ""
                    else -> ((state["content"] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { entry ->
                        val item = entry as? JsonObject ?: return@mapNotNull null
                        if (item.string("type") == "text") item.string("text") else item.string("name") ?: item.string("uri")
                    }.joinToString("\n")
                }
                "**Tool: ${part.string("name") ?: "tool"}**\n\n**Input:**\n```json\n$input\n```\n\n$output"
            }
            else -> null
        }
    }

    private fun pretty(element: JsonElement, indent: Int = 0): String = when (element) {
        is JsonObject -> {
            if (element.isEmpty()) return "{}"
            val pad = "  ".repeat(indent)
            val inner = "  ".repeat(indent + 1)
            element.entries.sortedBy { it.key }.joinToString(separator = ",\n", prefix = "{\n", postfix = "\n$pad}") { (key, value) ->
                "$inner${jsonString(key)} : ${pretty(value, indent + 1)}"
            }
        }
        is JsonArray -> {
            if (element.isEmpty()) return "[]"
            val pad = "  ".repeat(indent)
            val inner = "  ".repeat(indent + 1)
            element.joinToString(separator = ",\n", prefix = "[\n", postfix = "\n$pad]") { "$inner${pretty(it, indent + 1)}" }
        }
        is JsonPrimitive -> if (element.isString) jsonString(element.content) else element.content
        else -> "null"
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        for (char in value) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.number(key: String): Double? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        return primitive.doubleOrNull ?: primitive.longOrNull?.toDouble()
    }

    private val fileNameSeparators = Regex("""[/\\:?%*|\"<>\s\p{Cc}]+""")

    private val dateFormatter: DateTimeFormatter =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(Locale.getDefault())
            .withZone(ZoneId.systemDefault())
}
