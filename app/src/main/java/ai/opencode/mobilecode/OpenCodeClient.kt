@file:PortedFrom("OpenCode/OpenCodeClient.swift", "8fd69ec")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.tca.DependencyKey
import ai.opencode.mobilecode.tca.DependencyValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ai.opencode.mobilecode.system.AttributedString
import ai.opencode.mobilecode.system.URL
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.Transient
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import java.io.IOException
import java.net.URI
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.BufferOverflow

internal val protocolJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
    isLenient = true
}

data class URLQueryItem(val name: String, val value: String?)

class ServerURL(val absoluteString: String) {
    val path: String get() = runCatching { URI(absoluteString).path }.getOrDefault("")
    fun toHttpUrl() = absoluteString.toHttpUrl()
}

class URLRequest(val url: ServerURL, private val headers: Map<String, String> = emptyMap()) {
    fun value(forHTTPHeaderField: String): String? =
        headers.entries.firstOrNull { it.key.equals(forHTTPHeaderField, ignoreCase = true) }?.value
}

private class OpenCodeReadCache {
    private val modelCatalogs = mutableMapOf<String, OpenCodeModelCatalog>()
    private val mutex = Mutex()

    suspend fun modelCatalog(forKey: String): OpenCodeModelCatalog? = mutex.withLock { modelCatalogs[forKey] }
    suspend fun storeModelCatalog(catalog: OpenCodeModelCatalog, forKey: String) {
        mutex.withLock { modelCatalogs[forKey] = catalog }
    }

    companion object {
        val shared = OpenCodeReadCache()
    }
}

@Serializable
data class OpenCodeServer(
    var url: String,
    var username: String,
    var password: String,
    var alternateURLs: List<String> = emptyList(),
) {
    val id: String get() = url
    val persistenceID: String get() = (listOf(username) + candidateURLs.sorted()).joinToString("|")
    val candidateURLs: List<String>
        get() {
            val seen = mutableSetOf<String>()
            return (listOf(url) + alternateURLs).filter { seen.add(it) }
        }
    val host: String
        get() {
            val components = runCatching { URI(url) }.getOrNull() ?: return url
            val name = components.host ?: return url
            return if (components.port == -1) name else "$name:${components.port}"
        }
    val isSecure: Boolean get() = url.lowercase().startsWith("https")
    val usesPairingToken: Boolean get() = Regex("""^[0-9]{10,}\.[A-Za-z0-9_-]{20,}$""").matches(password)
    val basicAuthorization: String?
        get() {
            if (password.isEmpty()) return null
            val token = Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
            return "Basic $token"
        }

    fun supersedes(saved: OpenCodeServer): Boolean = saved.persistenceID == persistenceID || saved.url == url

    fun at(url: String): OpenCodeServer = OpenCodeServer(
        url = url,
        username = username,
        password = password,
        alternateURLs = candidateURLs.filter { it != url },
    )

    fun endpoint(path: String, query: List<URLQueryItem> = emptyList()): ServerURL? {
        val components = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val scheme = components.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = components.host?.takeIf { it.isNotEmpty() } ?: return null
        val port = if (components.port == -1) "" else ":${components.port}"
        val hostPart = if (host.contains(":") && !host.startsWith("[")) "[$host]" else host
        val queryString = if (query.isEmpty()) "" else "?" + query.joinToString("&") { item ->
            percentEncode(item.name, queryNameSafe) + "=" + percentEncode(item.value ?: "", queryValueSafe)
        }
        return ServerURL("$scheme://$hostPart$port${encodePath(path)}$queryString")
    }

    fun imageRequest(forSource: String, inDirectory: String?): URLRequest? {
        val remote = runCatching { URI(forSource) }.getOrNull()
        if (remote?.scheme?.lowercase() in listOf("http", "https")) return URLRequest(ServerURL(forSource))
        val directory = inDirectory ?: return null
        val path = when {
            forSource.startsWith("file://") -> runCatching { URI(forSource).path }.getOrNull() ?: return null
            forSource.startsWith("sandbox:") -> forSource.removePrefix("sandbox:")
            else -> forSource
        }
        val relative = when {
            path.startsWith("$directory/") -> path.removePrefix("$directory/")
            path.startsWith("/") -> return null
            else -> path
        }
        return fileReadRequest(location = directory, relativePath = relative)
    }

    fun fileReadRequest(location: String, relativePath: String): URLRequest? {
        val target = endpoint(
            path = "/api/fs/read/$relativePath",
            query = listOf(URLQueryItem("location[directory]", location)),
        ) ?: return null
        val headers = basicAuthorization?.let { mapOf("Authorization" to it) } ?: emptyMap()
        return URLRequest(target, headers)
    }

    companion object {
        fun isLoopback(url: String): Boolean {
            val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return false
            return host in setOf("localhost", "127.0.0.1", "::1", "[::1]")
        }

        fun reachabilityRank(url: String): Int {
            val components = runCatching { URI(url) }.getOrNull() ?: return 8
            val host = components.host?.lowercase() ?: return 8
            val scope = when {
                isLoopback(url) -> 3
                host.endsWith(".ts.net") || host.startsWith("fd7a:115c:a1e0:") -> 1
                else -> {
                    val octets = host.split(".").mapNotNull { it.toIntOrNull()?.takeIf { n -> n in 0..255 } }
                    when {
                        octets.size == 4 && octets[0] == 100 && octets[1] in 64..127 -> 1
                        octets.size == 4 && (
                            octets[0] == 10 ||
                                octets[0] == 172 && octets[1] in 16..31 ||
                                octets[0] == 192 && octets[1] == 168 ||
                                octets[0] == 169 && octets[1] == 254
                            ) -> 2
                        host.endsWith(".local") || host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80:") -> 2
                        else -> 0
                    }
                }
            }
            return scope * 2 + if (components.scheme?.lowercase() == "https") 0 else 1
        }
    }
}

data class OpenCodePairingLink(
    var origin: String,
    var code: String,
    var alternateOrigins: List<String> = emptyList(),
) {
    val candidateOrigins: List<String>
        get() {
            val seen = mutableSetOf<String>()
            return (listOf(origin) + alternateOrigins).filter { seen.add(it) }
        }
    val isLoopback: Boolean get() = candidateOrigins.all { OpenCodeServer.isLoopback(it) }

    companion object {
        fun decode(text: String): OpenCodePairingLink? {
            val trimmed = text.trim()
            return if (trimmed.startsWith("{")) decodePayload(trimmed) else decodeLink(trimmed)
        }

        private fun decodePayload(text: String): OpenCodePairingLink? {
            val payload = runCatching { protocolJson.decodeFromString(Payload.serializer(), text) }.getOrNull() ?: return null
            if (!isCode(payload.code)) return null
            val addresses = payload.urls.map { normalizedOrigin(it) }
            if (addresses.isEmpty() || addresses.any { it == null }) return null
            val seen = mutableSetOf<String>()
            val origins = addresses.filterNotNull().filter { seen.add(it) }
            if (origins.isEmpty()) return null
            return OpenCodePairingLink(origin = origins.first(), code = payload.code, alternateOrigins = origins.drop(1))
        }

        private fun decodeLink(text: String): OpenCodePairingLink? {
            val link = runCatching { URI(text) }.getOrNull() ?: return null
            if (!link.rawQuery.isNullOrEmpty() || link.rawFragment != null || !link.userInfo.isNullOrEmpty()) return null
            val parts = link.path?.split("/", limit = 0) ?: return null
            if (parts.size != 4 || parts[0].isNotEmpty() || parts[1] != "auth" || parts[2] != "connect" || !isCode(parts[3])) {
                return null
            }
            val address = runCatching {
                normalizedOrigin(URI(link.scheme, null, link.host, link.port, "", null, null).toString())
            }.getOrNull() ?: return null
            return OpenCodePairingLink(origin = address, code = parts[3])
        }

        private fun normalizedOrigin(address: String): String? {
            val components = runCatching { URI(address) }.getOrNull() ?: return null
            if (!hasValidServerOrigin(components)) return null
            if (components.path.isNotEmpty() && components.path != "/") return null
            val scheme = components.scheme?.lowercase() ?: return null
            val host = components.host ?: return null
            val port = if (components.port == -1) "" else ":${components.port}"
            val hostPart = if (host.contains(":") && !host.startsWith("[")) "[$host]" else host
            return "$scheme://$hostPart$port"
        }

        private fun isCode(code: String): Boolean =
            code.isNotEmpty() && code.all { it.code < 128 && (it.isLetterOrDigit() || it == '-' || it == '_') }

        @Serializable
        private data class Payload(val code: String, val urls: List<String>)
    }
}

@Serializable
data class OpenCodeProject(var id: String, var name: String, var directory: String)

@Serializable
data class OpenCodeModelRef(var id: String, var providerID: String)

data class OpenCodeModel(
    var modelID: String,
    var providerID: String,
    var name: String,
    var inputs: Set<String> = emptySet(),
    var contextLimit: Int? = null,
    var catalogID: String? = null,
) {
    val id: String get() = "$providerID/$modelID"
    val ref: OpenCodeModelRef get() = OpenCodeModelRef(id = modelID, providerID = providerID)
    fun matches(ref: OpenCodeModelRef): Boolean = providerID == ref.providerID && (modelID == ref.id || catalogID == ref.id)
}

data class OpenCodeModelCatalog(var models: List<OpenCodeModel>, var defaultModel: OpenCodeModel?)

@Serializable
data class OpenCodeSession(
    var id: String,
    var title: String,
    var projectID: String,
    var directory: String,
    var updatedAt: Long,
    var idleMilliseconds: Long? = null,
    var viewedMilliseconds: Long? = null,
    var model: OpenCodeModelRef? = null,
    var parentID: String? = null,
    var agent: String? = null,
    var cost: Double? = null,
)

data class OpenCodeContextUsage(var tokens: Int, var model: OpenCodeModelRef)

sealed class OpenCodeContextReading {
    data class measured(val usage: OpenCodeContextUsage) : OpenCodeContextReading()
    data object empty : OpenCodeContextReading()
    data object inconclusive : OpenCodeContextReading()
}

enum class OpenCodeExportFormat { markdown, json;
    val fileExtension: String get() = if (this == markdown) "md" else "json"
}

data class OpenCodeSessionFamily(var sessions: List<OpenCodeSession>, var activeSessionIDs: Set<String>)

data class OpenCodeShell(
    var id: String,
    var command: String,
    var sessionID: String? = null,
    var isUserCommand: Boolean = false,
    var isListed: Boolean = false,
    var status: Status = Status.running,
    var exitCode: Int? = null,
    var startedAt: Long? = null,
    var completedAt: Long? = null,
) {
    enum class Status { running, exited, timeout, killed }
    val title: String get() = command.firstLine
}

@Serializable
data class OpenCodeShellOutputPage(var output: String, var cursor: Int, var size: Int)

@Serializable
data class OpenCodeHomeSnapshot(
    var projects: List<OpenCodeProject>,
    var sessions: List<OpenCodeSession>,
    var activeSessionIDs: Set<String>,
    var activeSessionParents: Map<String, String>? = null,
)

object SessionActivity {
    fun ancestors(of: Set<String>, parents: Map<String, String>): Set<String> {
        val busy = of.toMutableSet()
        for (id in of) {
            var current = id
            val visited = mutableSetOf(id)
            while (true) {
                val parent = parents[current] ?: break
                if (!visited.add(parent)) break
                busy += parent
                current = parent
            }
        }
        return busy
    }
}

enum class OpenCodeDelivery {
    steer, queue;
    val pendingLabel: String get() = if (this == steer) "Steering" else "Queued"
    val symbolName: String get() = if (this == steer) "arrow.turn.up.right" else "text.badge.plus"
}

class OpenCodeClientError(
    override val message: String,
    val statusCode: Int? = null,
    val isUnsupported: Boolean = false,
) : Exception(message) {
    val isRetryableWaitError: Boolean
        get() {
            if (isUnsupported) return false
            statusCode?.let { return it == 408 || it == 429 || it >= 500 }
            val lowercased = message.lowercase()
            return !lowercased.startsWith("run failed:") &&
                !lowercased.contains("no longer in the recent transcript") &&
                !lowercased.contains("does not match the current opencode")
        }

    override fun equals(other: Any?): Boolean {
        val error = other as? OpenCodeClientError ?: return false
        return message == error.message && statusCode == error.statusCode && isUnsupported == error.isUnsupported
    }

    override fun hashCode(): Int = listOf(message, statusCode, isUnsupported).hashCode()

    companion object {
        val uploadsUnsupported = OpenCodeClientError(
            message = "This server doesn’t support file attachments. Update OpenCode on your computer.",
            isUnsupported = true,
        )
    }
}

@Serializable(with = OpenCodeJSONValue.Serializer::class)
sealed class OpenCodeJSONValue {
    data object `null` : OpenCodeJSONValue()
    data class bool(val value: Boolean) : OpenCodeJSONValue()
    data class number(val value: Double) : OpenCodeJSONValue()
    data class string(val value: String) : OpenCodeJSONValue()
    data class array(val values: List<OpenCodeJSONValue>) : OpenCodeJSONValue()
    data class `object`(val values: Map<String, OpenCodeJSONValue>) : OpenCodeJSONValue()

    val displayText: String
        get() = when (this) {
            `null` -> "null"
            is bool -> value.toString()
            is number -> ungrouped(value)
            is string -> value
            is array, is `object` -> compactText
        }
    val compactText: String
        get() = protocolJson.encodeToString(JsonElement.serializer(), Serializer.toElement(this))

    fun bounded(stringLimit: Int = 20_000, collectionLimit: Int = 100): OpenCodeJSONValue = when (this) {
        is string -> string(value.bounded(stringLimit))
        is array -> array(values.take(collectionLimit).map { it.bounded(stringLimit, collectionLimit) })
        is `object` -> {
            val kept = if (values.size <= collectionLimit) values.entries else values.entries.sortedBy { it.key }.take(collectionLimit)
            `object`(kept.associate { it.key to it.value.bounded(stringLimit, collectionLimit) })
        }
        else -> this
    }

    object Serializer : KSerializer<OpenCodeJSONValue> {
        override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor
        override fun deserialize(decoder: Decoder): OpenCodeJSONValue = fromElement((decoder as JsonDecoder).decodeJsonElement())
        override fun serialize(encoder: Encoder, value: OpenCodeJSONValue) {
            (encoder as JsonEncoder).encodeJsonElement(toElement(value))
        }

        fun fromElement(element: JsonElement): OpenCodeJSONValue = when (element) {
            JsonNull -> `null`
            is JsonArray -> array(element.map { fromElement(it) })
            is JsonObject -> `object`(element.mapValues { fromElement(it.value) })
            is JsonPrimitive -> when {
                element.isString -> string(element.content)
                element.booleanOrNull != null -> bool(element.booleanOrNull == true)
                else -> number(element.doubleOrNull ?: element.content.toDouble())
            }
        }

        fun toElement(value: OpenCodeJSONValue): JsonElement = when (value) {
            `null` -> JsonNull
            is bool -> JsonPrimitive(value.value)
            is number -> JsonPrimitive(value.value)
            is string -> JsonPrimitive(value.value)
            is array -> JsonArray(value.values.map { toElement(it) })
            is `object` -> JsonObject(value.values.mapValues { toElement(it.value) })
        }
    }
}

@Serializable
data class OpenCodeToolCall(
    var callID: String,
    var messageID: String? = null,
    var name: String,
    var status: Status,
    var input: OpenCodeJSONValue,
    var output: List<String>,
    var error: String? = null,
    var metadata: Map<String, OpenCodeJSONValue>,
    var images: List<OpenCodeImageAttachment>? = null,
    var startedAt: Long? = null,
) {
    @Serializable
    enum class Status { streaming, running, completed, error }

    val imageAttachments: List<OpenCodeImageAttachment> get() = images ?: emptyList()
    val isShell: Boolean get() = name == "shell" || name == "bash"
    val displayName: String get() = displayName(name)
    val fileReference: AgentFileReference?
        get() {
            if (isShell) return null
            val path = inputPath ?: return null
            if (path.contains("*")) return null
            val file = AgentFileReference.withLocation(path) ?: return null
            return file.takeIf { it.hasViewableName }
        }
    val subtitle: String?
        get() {
            if (isShell) return inputString("command")?.firstLine
            if (name == "execute") {
                val code = inputString("code") ?: return null
                val tools = calledTools(code)
                return if (tools.isEmpty()) code.firstLine else tools.joinToString(", ")
            }
            inputPath?.let { return it.substringAfterLast('/') }
            return listOf("pattern", "url", "query", "description", "name").firstNotNullOfOrNull { inputString(it)?.firstLine }
        }
    val arguments: List<Pair<String, String>>
        get() {
            val current = input
            val values = current.`object` ?: return if (current is OpenCodeJSONValue.string && current.value.isNotEmpty()) {
                listOf("input" to current.value)
            } else {
                emptyList()
            }
            return values.keys.sorted().mapNotNull { key ->
                if (isShell && (key == "command" || key == "description")) return@mapNotNull null
                when (val value = values[key] ?: return@mapNotNull null) {
                    OpenCodeJSONValue.`null` -> null
                    is OpenCodeJSONValue.string -> key to value.value
                    is OpenCodeJSONValue.bool -> key to if (value.value) "true" else "false"
                    is OpenCodeJSONValue.number -> key to ungrouped(value.value)
                    is OpenCodeJSONValue.array, is OpenCodeJSONValue.`object` -> key to value.compactText
                }
            }
        }
    val questions: List<OpenCodeQuestion>?
        get() {
            if (name != "question") return null
            val values = (input.`object`?.get("questions") as? OpenCodeJSONValue.array)?.values ?: return null
            val parsed = values.mapNotNull { OpenCodeQuestion.fromToolInput(it) }
            return parsed.ifEmpty { null }
        }

    private fun inputString(key: String): String? = input.`object`?.get(key)?.string?.trim()?.nonEmpty
    private val inputPath: String? get() = listOf("path", "filePath", "file_path").firstNotNullOfOrNull { inputString(it) }

    companion object {
        fun displayName(name: String): String {
            if (name == "bash") return "Shell"
            val words = name.replace("_", " ").replace("-", " ")
            return words.replaceFirstChar { it.uppercase() }
        }

        fun calledTools(code: String): List<String> {
            val pattern = Regex("""\btools(?:\.([A-Za-z_][\w]*)|\[\s*["']([^"']+)["']\s*\])(?:\.([A-Za-z_][\w]*)|\[\s*["']([^"']+)["']\s*\])?\s*\(""")
            val seen = mutableListOf<String>()
            for (match in pattern.findAll(code)) {
                val namespace = match.groupValues[1].ifEmpty { match.groupValues[2] }
                if (namespace.isEmpty()) continue
                val method = match.groupValues[3].ifEmpty { match.groupValues[4] }.ifEmpty { null }
                val tool = listOfNotNull(namespace, method).joinToString(".")
                if (tool !in seen) seen += tool
            }
            return seen
        }

        fun tool(value: String?): Status = value?.let { runCatching { Status.valueOf(it) }.getOrNull() } ?: Status.completed

        fun shell(value: String?, exit: Int?): Status = when {
            value == "running" -> Status.running
            value == "exited" && (exit == null || exit == 0) -> Status.completed
            else -> Status.error
        }
    }
}

@Serializable
data class OpenCodeMarkdownBlock(var id: Int, var kind: Kind) {
    @Serializable
    sealed class Kind {
        @Serializable data class paragraph(val text: AttributedString) : Kind()
        @Serializable data class numberedItem(val marker: String, val text: AttributedString) : Kind()
        @Serializable data class listItem(val marker: String, val level: Int, val text: AttributedString) : Kind()
        @Serializable data class blockquote(val text: AttributedString) : Kind()
        @Serializable data class heading(val level: Int, val text: AttributedString) : Kind()
        @Serializable data class code(val code: HighlightedCode) : Kind()
        @Serializable data class table(val header: List<AttributedString>, val rows: List<List<AttributedString>>) : Kind()
        @Serializable data class image(val alt: String, val source: String) : Kind()
        @Serializable data class pullRequest(val title: String, val url: String) : Kind()
        @Serializable data object divider : Kind()
        @Serializable data class files(val files: List<AgentFileReference>) : Kind()
    }

    @Serializable
    data class HighlightedCode(
        val source: String,
        val language: String?,
        @Transient val text: AttributedString = SyntaxHighlighter.highlight(source, language),
    ) {
        override fun equals(other: Any?): Boolean {
            val code = other as? HighlightedCode ?: return false
            return source == code.source && language == code.language
        }
        override fun hashCode(): Int = source.hashCode() * 31 + (language?.hashCode() ?: 0)
    }

    val markdown: String?
        get() = when (val value = kind) {
            is Kind.paragraph -> markdown(value.text)
            is Kind.numberedItem -> "${value.marker} ${markdown(value.text)}"
            is Kind.listItem -> "  ".repeat(value.level) +
                (if (value.marker == "•") "-" else if (value.marker in taskMarkers) "- ${value.marker}" else value.marker) +
                " " + markdown(value.text)
            is Kind.blockquote -> markdown(value.text).split("\n").joinToString("\n") { "> $it" }
            is Kind.heading -> "#".repeat(value.level) + " " + markdown(value.text)
            is Kind.code -> "```${value.code.language ?: ""}\n${value.code.source}\n```"
            is Kind.table -> {
                val lines = listOf(value.header.map { markdown(it) }, value.header.map { "---" }) + value.rows.map { row -> row.map { markdown(it) } }
                lines.joinToString("\n") { "| " + it.joinToString(" | ") + " |" }
            }
            is Kind.image -> "![${value.alt}](${value.source})"
            Kind.divider -> "---"
            is Kind.pullRequest, is Kind.files -> null
        }

    class InlineCache(
        private val maximumEntries: Int = 512,
        private val maximumSourceBytes: Int = 128 * 1024,
    ) {
        private class Storage {
            val values = HashMap<SourceKey, AttributedString>()
            val insertionOrder = ArrayDeque<SourceKey>()
            var sourceBytes = 0
        }

        // iOS keys the cache by UTF-8 bytes because String equality folds there.
        private class SourceKey(val bytes: ByteArray) {
            val count: Int get() = bytes.size
            override fun equals(other: Any?): Boolean = other is SourceKey && bytes.contentEquals(other.bytes)
            override fun hashCode(): Int = bytes.contentHashCode()
        }

        private val storage = Storage()

        init {
            require(maximumEntries >= 0 && maximumSourceBytes >= 0)
        }

        val entryCount: Int get() = synchronized(storage) { storage.values.size }
        val retainedSourceBytes: Int get() = synchronized(storage) { storage.sourceBytes }

        fun parse(text: String): AttributedString {
            val key = SourceKey(text.toByteArray(Charsets.UTF_8))
            if (maximumEntries == 0 || key.count > maximumSourceBytes) return uncachedInline(text)
            synchronized(storage) { storage.values[key] }?.let { return it }
            val parsed = uncachedInline(text)
            synchronized(storage) {
                if (storage.values[key] == null) {
                    while (storage.values.size >= maximumEntries || storage.sourceBytes + key.count > maximumSourceBytes) {
                        val evicted = storage.insertionOrder.removeFirst()
                        storage.values.remove(evicted)
                        storage.sourceBytes -= evicted.count
                    }
                    storage.values[key] = parsed
                    storage.insertionOrder.addLast(key)
                    storage.sourceBytes += key.count
                }
            }
            return parsed
        }

        companion object {
            val shared = InlineCache()
        }
    }

    companion object {
        const val openTask = "[ ]"
        const val doneTask = "[x]"
        val taskMarkers = listOf(openTask, doneTask)

        fun markdown(blocks: List<OpenCodeMarkdownBlock>): String {
            var result = ""
            var previousIsListItem = false
            for (block in blocks) {
                val markdown = block.markdown ?: continue
                val isListItem = block.kind is Kind.numberedItem || block.kind is Kind.listItem
                if (result.isNotEmpty()) result += if (previousIsListItem && isListItem) "\n" else "\n\n"
                result += markdown
                previousIsListItem = isListItem
            }
            return result
        }

        fun markdown(text: AttributedString): String {
            val result = StringBuilder()
            for (run in text.runs) {
                var piece = text.characters.substring(run.start, run.end)
                val intent = run.inlinePresentationIntent
                if (AttributedString.Intent.code in intent) {
                    piece = "`$piece`"
                } else {
                    if (AttributedString.Intent.emphasized in intent) piece = "*$piece*"
                    if (AttributedString.Intent.stronglyEmphasized in intent) piece = "**$piece**"
                }
                val link = run.link
                if (link != null && AgentFileReference(linkURL = link) == null) {
                    piece = "[$piece](${link.absoluteString})"
                }
                result.append(piece)
            }
            return result.toString()
        }

        fun blocks(
            from: String,
            inlineCache: InlineCache? = InlineCache.shared,
            reusing: List<OpenCodeMarkdownBlock> = emptyList(),
        ): List<OpenCodeMarkdownBlock> {
            val previousCode = buildMap {
                for (block in reusing) {
                    val code = (block.kind as? Kind.code)?.code ?: continue
                    put(CodeSource(code.source, code.language), code)
                }
            }
            val blocks = mutableListOf<OpenCodeMarkdownBlock>()
            val segments = codeFenceSegments(from)
            segments.forEachIndexed { index, segment ->
                if (index % 2 == 0) appendTextBlocks(segment, blocks, inlineCache)
                else appendCode(segment, blocks, previousCode)
            }
            return insertFileCards(blocks)
        }

        fun codeFenceSegments(text: String): List<String> {
            if (text.all { it.code < 0x80 }) return splitSeparator(text, "```")
            return splitFence(text)
        }

        private fun splitSeparator(text: String, separator: String): List<String> {
            val parts = mutableListOf<String>()
            var start = 0
            var index = text.indexOf(separator)
            while (index >= 0) {
                parts += text.substring(start, index)
                start = index + separator.length
                index = text.indexOf(separator, start)
            }
            parts += text.substring(start)
            return parts
        }

        private fun splitFence(text: String): List<String> {
            val parts = mutableListOf<String>()
            var start = 0
            var index = 0
            while (index <= text.length - 3) {
                if (isFenceBoundary(text, index) && isFenceBoundary(text, index + 3) && text.startsWith("```", index)) {
                    parts += text.substring(start, index)
                    start = index + 3
                    index = start
                    continue
                }
                index++
            }
            parts += text.substring(start)
            return parts
        }

        // Swift keeps a joiner on the last backtick inside the fence. BreakIterator
        // on the test JVM reports a boundary there, so the fence scan cannot use it.
        private fun isFenceBoundary(text: String, index: Int): Boolean {
            if (index <= 0 || index >= text.length) return true
            val next = text[index]
            if (next == '\u200D' || next == '\u200C') return false
            val type = Character.getType(next)
            return type != Character.NON_SPACING_MARK.toInt() && type != Character.ENCLOSING_MARK.toInt()
        }

        private data class CodeSource(val source: String, val language: String?)

        private fun appendCode(
            segment: String,
            blocks: MutableList<OpenCodeMarkdownBlock>,
            previous: Map<CodeSource, HighlightedCode>,
        ) {
            val lines = segment.split("\n").toMutableList()
            var language: String? = null
            if (lines.size > 1) {
                val hint = lines.removeAt(0).trim()
                language = hint.ifEmpty { null }
            }
            val code = lines.joinToString("\n").trim()
            if (code.isEmpty()) return
            val reused = previous[CodeSource(code, language)]
            blocks += OpenCodeMarkdownBlock(blocks.size, Kind.code(reused ?: HighlightedCode(code, language)))
        }

        private fun appendTextBlocks(segment: String, blocks: MutableList<OpenCodeMarkdownBlock>, cache: InlineCache?) {
            val paragraph = mutableListOf<String>()
            var listMarker: String? = null
            var listLevel = 0
            val listIndents = mutableListOf<Int>()

            fun flushParagraph() {
                var text = paragraph.joinToString("\n").trim()
                paragraph.clear()
                var marker = listMarker
                listMarker = null
                if (text.isEmpty()) return
                if (marker == "•") {
                    val box = taskMarkers.firstOrNull { text.lowercase().startsWith("$it ") }
                    if (box != null) {
                        marker = box
                        text = text.drop(box.length).trim()
                    }
                }
                val kind = when {
                    marker != null && marker != "•" && marker !in taskMarkers && listLevel == 0 ->
                        Kind.numberedItem(marker, inline(text, cache))
                    marker != null -> Kind.listItem(marker, listLevel, inline(text, cache))
                    else -> Kind.paragraph(inline(text, cache))
                }
                blocks += OpenCodeMarkdownBlock(blocks.size, kind)
                appendPullRequests(text, blocks)
            }

            val lines = segment.split("\n")
            var index = 0
            while (index < lines.size) {
                val trimmed = lines[index].trim()
                if (trimmed.isEmpty()) {
                    flushParagraph()
                    index++
                    continue
                }
                val image = markdownImage(trimmed)
                if (image != null) {
                    flushParagraph()
                    blocks += OpenCodeMarkdownBlock(blocks.size, image)
                    index++
                    continue
                }
                if (trimmed.startsWith(">")) {
                    flushParagraph()
                    val quote = mutableListOf<String>()
                    while (index < lines.size) {
                        val line = lines[index].trim()
                        if (!line.startsWith(">")) break
                        var content = line.drop(1)
                        if (content.startsWith(" ")) content = content.drop(1)
                        quote += content
                        index++
                    }
                    blocks += OpenCodeMarkdownBlock(blocks.size, Kind.blockquote(inline(quote.joinToString("\n"), cache)))
                    continue
                }
                if (isThematicBreak(trimmed)) {
                    flushParagraph()
                    blocks += OpenCodeMarkdownBlock(blocks.size, Kind.divider)
                    index++
                    continue
                }
                if (isListItem(trimmed)) {
                    flushParagraph()
                    val indent = lines[index].takeWhile { it == ' ' || it == '\t' }.sumOf { if (it == '\t') 4 else 1 }
                    while (listIndents.lastOrNull()?.let { it > indent } == true) listIndents.removeAt(listIndents.lastIndex)
                    if (listIndents.lastOrNull() != indent) listIndents += indent
                    listLevel = minOf(listIndents.size - 1, 3)
                    if (trimmed.firstOrNull()?.isDigit() == true) {
                        val space = trimmed.indexOf(' ')
                        listMarker = trimmed.substring(0, space)
                        paragraph += trimmed.substring(space + 1)
                    } else {
                        listMarker = "•"
                        paragraph += trimmed.drop(2)
                    }
                    index++
                    continue
                }
                if (lines[index].firstOrNull() != ' ' && lines[index].firstOrNull() != '\t') listIndents.clear()
                if (trimmed.startsWith("|") && index + 1 < lines.size && isTableSeparator(lines[index + 1].trim())) {
                    flushParagraph()
                    val header = tableCells(trimmed, cache)
                    val rows = mutableListOf<List<AttributedString>>()
                    var next = index + 2
                    while (next < lines.size) {
                        val rowLine = lines[next].trim()
                        if (!rowLine.startsWith("|")) break
                        rows += tableCells(rowLine, cache)
                        next++
                    }
                    blocks += OpenCodeMarkdownBlock(blocks.size, Kind.table(header, rows))
                    index = next
                    continue
                }
                if (trimmed.startsWith("#")) {
                    val level = trimmed.takeWhile { it == '#' }.length
                    val heading = trimmed.drop(level).trim()
                    if (heading.isNotEmpty() && level <= 6) {
                        flushParagraph()
                        blocks += OpenCodeMarkdownBlock(blocks.size, Kind.heading(level, inline(heading, cache)))
                        index++
                        continue
                    }
                }
                paragraph += lines[index]
                index++
            }
            flushParagraph()
        }

        private fun isThematicBreak(line: String): Boolean {
            val mark = line.firstOrNull() ?: return false
            if (mark !in "-*_") return false
            val marks = line.filter { it != ' ' && it != '\t' }
            return marks.length >= 3 && marks.all { it == mark }
        }

        private fun isTableSeparator(line: String): Boolean {
            if (!line.startsWith("|") || '-' !in line) return false
            return line.all { it in "|-: " }
        }

        private fun tableCells(line: String, cache: InlineCache?): List<AttributedString> {
            var body = line
            if (body.startsWith("|")) body = body.drop(1)
            if (body.endsWith("|")) body = body.dropLast(1)
            return body.split("|").map { inline(it.trim(), cache) }
        }

        private fun inline(text: String, cache: InlineCache?): AttributedString =
            cache?.parse(text) ?: uncachedInline(text)

        private val inlineOptions = AttributedString.MarkdownParsingOptions(
            interpretedSyntax = AttributedString.MarkdownParsingOptions.InterpretedSyntax.inlineOnlyPreservingWhitespace,
        )

        private fun uncachedInline(text: String): AttributedString {
            val attributed = runCatching {
                AttributedString(markdown = text, options = inlineOptions)
            }.getOrElse { AttributedString(text) }
            val runs = attributed.runs.map { run ->
                val code = AttributedString.Intent.code in run.inlinePresentationIntent
                if (!code) {
                    val url = run.link
                    if (url == null || url.scheme == AgentFileReference.linkScheme) return@map run
                    val file = AgentFileReference.fromLink(url.absoluteString) ?: return@map run
                    return@map run.copy(link = file.linkURL)
                }
                if (run.link == null) {
                    val file = AgentFileReference.fromInlineCode(attributed.characters.substring(run.start, run.end))
                    if (file != null) return@map run.copy(link = file.linkURL)
                }
                run.copy(foregroundColor = AttributedString.Foreground.secondary)
            }
            return attributed.copy(runs = runs)
        }

        private fun isListItem(line: String): Boolean {
            if (line.startsWith("- ") || line.startsWith("* ") || line.startsWith("• ")) return true
            val digits = line.takeWhile { it.isDigit() }
            return digits.isNotEmpty() && line.drop(digits.length).startsWith(". ")
        }

        private fun appendPullRequests(text: String, blocks: MutableList<OpenCodeMarkdownBlock>) {
            if (!text.contains("github.com/")) return
            val pattern = Regex("""https://github\.com/([\w.-]+)/([\w.-]+)/pull/(\d+)""")
            for (match in pattern.findAll(text)) {
                blocks += OpenCodeMarkdownBlock(
                    blocks.size,
                    Kind.pullRequest(
                        "${match.groupValues[1]}/${match.groupValues[2]} #${match.groupValues[3]}",
                        match.value,
                    ),
                )
            }
        }

        private fun markdownImage(line: String): Kind? {
            if (!line.startsWith("![") || !line.endsWith(")")) return null
            val separator = line.indexOf("](")
            if (separator < 0) return null
            val alt = line.substring(2, separator)
            var source = line.substring(separator + 2, line.lastIndex).trim()
            if (source.startsWith("<") && source.endsWith(">")) source = source.drop(1).dropLast(1)
            val title = source.indexOf(" \"")
            if (title >= 0) source = source.substring(0, title)
            if (source.isEmpty()) return null
            val file = AgentFileReference.fromLink(source)
            if (file != null && file.kind != AgentFileReference.Kind.image && file.kind.showsCard) return Kind.files(listOf(file))
            return Kind.image(alt, source)
        }

        private fun insertFileCards(blocks: List<OpenCodeMarkdownBlock>): List<OpenCodeMarkdownBlock> {
            val seen = mutableSetOf<String>()
            val result = mutableListOf<OpenCodeMarkdownBlock>()
            for (block in blocks) {
                val files = block.kind as? Kind.files
                if (files != null) {
                    val unseen = files.files.filter { seen.add(it.path) }
                    if (unseen.isNotEmpty()) result += OpenCodeMarkdownBlock(result.size, Kind.files(unseen))
                    continue
                }
                result += OpenCodeMarkdownBlock(result.size, block.kind)
                val cards = block.fileReferences.filter { it.kind.showsCard && seen.add(it.path) }
                if (cards.isNotEmpty()) result += OpenCodeMarkdownBlock(result.size, Kind.files(cards))
            }
            return result
        }
    }
}

val OpenCodeMarkdownBlock.fileReferences: List<AgentFileReference>
    get() {
        val files = kind as? OpenCodeMarkdownBlock.Kind.files
        if (files != null) return files.files
        val texts = when (val value = kind) {
            is OpenCodeMarkdownBlock.Kind.paragraph -> listOf(value.text)
            is OpenCodeMarkdownBlock.Kind.blockquote -> listOf(value.text)
            is OpenCodeMarkdownBlock.Kind.heading -> listOf(value.text)
            is OpenCodeMarkdownBlock.Kind.numberedItem -> listOf(value.text)
            is OpenCodeMarkdownBlock.Kind.listItem -> listOf(value.text)
            is OpenCodeMarkdownBlock.Kind.table -> value.header + value.rows.flatten()
            else -> emptyList()
        }
        return texts.flatMap { text -> text.runs.mapNotNull { run -> run.link?.let { AgentFileReference(linkURL = it) } } }
    }

@Serializable
data class OpenCodeImageAttachment(
    var id: String,
    @Serializable(with = ByteArrayBase64Serializer::class) var data: ByteArray,
    var mime: String,
) {
    override fun equals(other: Any?): Boolean {
        val image = other as? OpenCodeImageAttachment ?: return false
        return id == image.id && data.contentEquals(image.data) && mime == image.mime
    }
    override fun hashCode(): Int = id.hashCode() * 31 + data.contentHashCode()
}

data class OpenCodeOutgoingAttachment(
    var id: String = UUID.randomUUID().toString(),
    var data: ByteArray,
    var mime: String,
    var name: String? = null,
) {
    val isImage: Boolean get() = mime.startsWith("image/")
    val dataURL: String get() = "data:$mime;base64,${Base64.getEncoder().encodeToString(data)}"
    override fun equals(other: Any?): Boolean {
        val attachment = other as? OpenCodeOutgoingAttachment ?: return false
        return id == attachment.id && data.contentEquals(attachment.data) && mime == attachment.mime && name == attachment.name
    }
    override fun hashCode(): Int = id.hashCode()
}

@Serializable
data class OpenCodePermissionRequest(
    override var id: String,
    override var sessionID: String,
    var action: String,
    var resources: List<String>,
    var save: List<String>? = null,
) : PendingRequest

@Serializable
enum class OpenCodePermissionReply { once, always, reject }

data class OpenCodeQuestion(
    var question: String,
    var header: String,
) {
    companion object {
        fun fromToolInput(toolInput: OpenCodeJSONValue): OpenCodeQuestion? {
            val value = toolInput.`object` ?: return null
            val question = value["question"]?.string?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return OpenCodeQuestion(
                question = question,
                header = value["header"]?.string?.trim() ?: "",
            )
        }
    }
}

data class OpenCodePendingRequests(
    var permissions: List<OpenCodePermissionRequest>,
    var forms: List<OpenCodeFormRequest> = emptyList(),
)

@Serializable(with = OpenCodeTranscriptItem.Serializer::class)
sealed class OpenCodeTranscriptItem {
    abstract val id: String
    data class userText(override val id: String, val text: String) : OpenCodeTranscriptItem()
    data class userImages(override val id: String, val images: List<OpenCodeImageAttachment>) : OpenCodeTranscriptItem()
    data class userFiles(override val id: String, val files: List<OpenCodeTranscriptFile>) : OpenCodeTranscriptItem()
    data class assistantMarkdown(override val id: String, val blocks: List<OpenCodeMarkdownBlock>) : OpenCodeTranscriptItem()
    data class tool(override val id: String, val call: OpenCodeToolCall) : OpenCodeTranscriptItem()
    data class compaction(override val id: String, val info: OpenCodeCompaction? = null) : OpenCodeTranscriptItem()
    data class info(override val id: String, val text: String) : OpenCodeTranscriptItem()
    data class notice(override val id: String, val text: String, val isError: Boolean = false) : OpenCodeTranscriptItem()
    data class sendFailure(override val id: String, val message: String) : OpenCodeTranscriptItem()

    object Serializer : KSerializer<OpenCodeTranscriptItem> {
        override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor
        override fun deserialize(decoder: Decoder): OpenCodeTranscriptItem {
            val element = (decoder as JsonDecoder).decodeJsonElement().jsonObject
            val (key, value) = element.entries.first()
            val objectValue = value.jsonObject
            return when (key) {
                "compaction" -> compaction(id = objectValue.getValue("id").jsonPrimitive.content)
                else -> error("unsupported transcript item")
            }
        }
        override fun serialize(encoder: Encoder, value: OpenCodeTranscriptItem) {
            val json = encoder as JsonEncoder
            val body = when (value) {
                is compaction -> JsonObject(mapOf("id" to JsonPrimitive(value.id)))
                else -> error("unsupported transcript item")
            }
            val key = when (value) {
                is compaction -> "compaction"
                else -> error("unsupported transcript item")
            }
            json.encodeJsonElement(JsonObject(mapOf(key to body)))
        }
    }
}

data class OpenCodeCompaction(
    var status: Status,
    var isProvider: Boolean = false,
    var summary: List<OpenCodeMarkdownBlock> = emptyList(),
    var errorMessage: String? = null,
    var inputTokens: Int? = null,
    var outputTokens: Int? = null,
) {
    @Serializable
    enum class Status { running, completed, failed, cancelled }
}

data class OpenCodePendingInput(
    var id: String,
    var text: String,
    var isCompaction: Boolean = false,
    var notice: String? = null,
    var delivery: OpenCodeDelivery = OpenCodeDelivery.steer,
    var files: List<OpenCodeFileReference> = emptyList(),
)

data class OpenCodeQueuedInput(
    var input: OpenCodePendingInput,
    var images: List<OpenCodeImageAttachment> = emptyList(),
) {
    val id: String get() = input.id
    val files: List<OpenCodeFileReference> get() = input.files
    val text: String get() = input.text
    val isCompaction: Boolean get() = input.isCompaction
    val notice: String? get() = input.notice
    val delivery: OpenCodeDelivery get() = input.delivery
    val waitsInQueuedPill: Boolean get() = !isCompaction && notice == null && delivery == OpenCodeDelivery.queue
}

sealed class OpenCodeServerEvent {
    data object connected : OpenCodeServerEvent()
    data class executionStarted(val sessionID: String) : OpenCodeServerEvent()
    data class executionEnded(val sessionID: String) : OpenCodeServerEvent()
    data object sessionCreated : OpenCodeServerEvent()
    data class sessionRenamed(val sessionID: String, val title: String) : OpenCodeServerEvent()
    data object sessionViewChanged : OpenCodeServerEvent()
    data class sessionDeleted(val sessionID: String) : OpenCodeServerEvent()
    data class inputQueueChanged(val sessionID: String) : OpenCodeServerEvent()
    data class inputEnqueued(val sessionID: String) : OpenCodeServerEvent()
    data class inputCancelled(val sessionID: String, val inputID: String) : OpenCodeServerEvent()
    data class contentBatch(val sessionID: String, val events: List<OpenCodeLiveContentEvent>) : OpenCodeServerEvent()
    data class messageInvalidated(val sessionID: String) : OpenCodeServerEvent()
    data class attentionChanged(val askedSessionID: String?) : OpenCodeServerEvent()
    data class shellStarted(val shell: OpenCodeShell) : OpenCodeServerEvent()
    data class shellEnded(val shellID: String) : OpenCodeServerEvent()
}

sealed class OpenCodeLiveContentEvent {
    abstract val messageID: String
    data class stepStarted(override val messageID: String) : OpenCodeLiveContentEvent()
    data class stepStreamed(override val messageID: String) : OpenCodeLiveContentEvent()
    data class stepEnded(override val messageID: String) : OpenCodeLiveContentEvent()
    data class textStarted(override val messageID: String, val ordinal: Int) : OpenCodeLiveContentEvent()
    data class textDelta(override val messageID: String, val ordinal: Int, val delta: String) : OpenCodeLiveContentEvent()
    data class textEnded(override val messageID: String, val ordinal: Int, val text: String) : OpenCodeLiveContentEvent()
    data class reasoningStarted(override val messageID: String, val ordinal: Int) : OpenCodeLiveContentEvent()
    data class reasoningDelta(override val messageID: String, val ordinal: Int, val delta: String) : OpenCodeLiveContentEvent()
    data class reasoningEnded(override val messageID: String, val ordinal: Int, val text: String) : OpenCodeLiveContentEvent()
    data class toolInputStarted(override val messageID: String, val toolID: String, val name: String) : OpenCodeLiveContentEvent()
    data class toolInputDelta(override val messageID: String, val toolID: String, val delta: String) : OpenCodeLiveContentEvent()
    data class toolInputEnded(override val messageID: String, val toolID: String, val text: String) : OpenCodeLiveContentEvent()
    data class toolCalled(override val messageID: String, val toolID: String, val input: OpenCodeJSONValue) : OpenCodeLiveContentEvent()
    data class toolProgress(override val messageID: String, val toolID: String, val metadata: Map<String, OpenCodeJSONValue>) : OpenCodeLiveContentEvent()
    data class toolSucceeded(override val messageID: String, val toolID: String, val output: List<String>, val metadata: Map<String, OpenCodeJSONValue>) : OpenCodeLiveContentEvent()
    data class toolFailed(override val messageID: String, val toolID: String, val error: String, val output: List<String>, val metadata: Map<String, OpenCodeJSONValue>) : OpenCodeLiveContentEvent()

    val isDurableBoundary: Boolean
        get() = this is stepEnded || this is textEnded || this is reasoningEnded || this is toolInputEnded ||
            this is toolCalled || this is toolSucceeded || this is toolFailed

    val transcriptItemID: String?
        get() = when (this) {
            is textStarted -> "$messageID-text-$ordinal"
            is textDelta -> "$messageID-text-$ordinal"
            is textEnded -> "$messageID-text-$ordinal"
            is toolInputStarted -> "$messageID-tool-$toolID"
            is toolInputDelta -> "$messageID-tool-$toolID"
            is toolInputEnded -> "$messageID-tool-$toolID"
            is toolCalled -> "$messageID-tool-$toolID"
            is toolProgress -> "$messageID-tool-$toolID"
            is toolSucceeded -> "$messageID-tool-$toolID"
            is toolFailed -> "$messageID-tool-$toolID"
            else -> null
        }

    val projectsRows: Boolean get() = transcriptItemID != null

    fun appendingDelta(next: OpenCodeLiveContentEvent): OpenCodeLiveContentEvent? = when {
        this is textDelta && next is textDelta && messageID == next.messageID && ordinal == next.ordinal ->
            textDelta(messageID, ordinal, delta + next.delta)
        this is reasoningDelta && next is reasoningDelta && messageID == next.messageID && ordinal == next.ordinal ->
            reasoningDelta(messageID, ordinal, delta + next.delta)
        this is toolInputDelta && next is toolInputDelta && messageID == next.messageID && toolID == next.toolID ->
            toolInputDelta(messageID, toolID, delta + next.delta)
        else -> null
    }
}

fun MutableList<OpenCodeLiveContentEvent>.appendCoalescingDeltas(events: List<OpenCodeLiveContentEvent>) {
    for (event in events) {
        val merged = lastOrNull()?.appendingDelta(event)
        if (merged != null) this[lastIndex] = merged else add(event)
    }
}

private class EventBatcher(private val emit: suspend (OpenCodeServerEvent) -> Unit) {
    private val pending = linkedMapOf<String, MutableList<OpenCodeLiveContentEvent>>()
    private val mutex = Mutex()
    private var flushJob: Job? = null

    suspend fun receive(event: OpenCodeServerEvent) {
        val outgoing = mutex.withLock {
            val batch = event as? OpenCodeServerEvent.contentBatch
            if (batch == null) {
                val flushed = takePending()
                cancelTimer()
                return@withLock flushed + event
            }
            pending.getOrPut(batch.sessionID) { mutableListOf() }.appendCoalescingDeltas(batch.events)
            if (pending.values.sumOf { it.size } >= 256) {
                cancelTimer()
                return@withLock takePending()
            }
            if (flushJob == null) {
                flushJob = CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).launch {
                    delay(75)
                    if (!isActive) return@launch
                    val flushed = mutex.withLock {
                        cancelTimer()
                        takePending()
                    }
                    flushed.forEach { emit(it) }
                }
            }
            emptyList()
        }
        outgoing.forEach { emit(it) }
    }

    suspend fun flush() {
        val outgoing = mutex.withLock {
            cancelTimer()
            takePending()
        }
        outgoing.forEach { emit(it) }
    }

    private fun takePending(): List<OpenCodeServerEvent> {
        val batches = pending.toMap()
        pending.clear()
        return batches.mapNotNull { (sessionID, events) ->
            if (events.isEmpty()) null else OpenCodeServerEvent.contentBatch(sessionID, events.toList())
        }
    }

    private fun cancelTimer() {
        flushJob?.cancel()
        flushJob = null
    }
}

data class OpenCodeTranscriptPage(var items: List<OpenCodeTranscriptItem>, var next: String?)

class OpenCodeClient(
    var health: suspend (OpenCodeServer) -> Unit,
    var redeemPairing: suspend (OpenCodePairingLink) -> OpenCodeServer,
    var homeSnapshot: suspend (OpenCodeServer) -> OpenCodeHomeSnapshot,
    var homeAttention: suspend (OpenCodeServer, List<OpenCodeSession>, List<String>) -> Set<String>,
    var transcript: suspend (OpenCodeServer, String, Int) -> List<OpenCodeTranscriptItem>,
    var message: suspend (OpenCodeServer, String, String) -> List<OpenCodeTranscriptItem>,
    var sendPrompt: suspend (OpenCodeServer, String, String, List<OpenCodeOutgoingAttachment>, List<OpenCodeFileReference>, String, OpenCodeDelivery) -> Unit,
    var sendDeliveredPrompt: suspend (OpenCodeServer, String, String, OpenCodeDelivery?) -> String,
    var assistantReply: suspend (OpenCodeServer, String, String) -> String?,
    var uploadFile: (OpenCodeServer, String?, java.io.File, String) -> Flow<OpenCodeUploadEvent>,
    var waitForIdle: suspend (OpenCodeServer, String) -> Unit,
    var isSessionActive: suspend (OpenCodeServer, String) -> Boolean,
    var interrupt: suspend (OpenCodeServer, String) -> Unit,
    var createSession: suspend (OpenCodeServer, String) -> OpenCodeSession,
    var deleteSession: suspend (OpenCodeServer, String) -> Unit,
    var sessionFamily: suspend (OpenCodeServer, OpenCodeSession) -> OpenCodeSessionFamily,
    var modelCatalog: suspend (OpenCodeServer, String?) -> OpenCodeModelCatalog,
    var switchModel: suspend (OpenCodeServer, String, OpenCodeModelRef) -> Unit,
    var familyPendingRequests: suspend (OpenCodeServer, List<String>, String) -> OpenCodePendingRequests,
    var pendingInputs: suspend (OpenCodeServer, String) -> List<OpenCodePendingInput>,
    var setPendingInputDelivery: suspend (OpenCodeServer, String, String, OpenCodeDelivery) -> Unit,
    var cancelPendingInput: suspend (OpenCodeServer, String, String) -> Unit,
    var replyPermission: suspend (OpenCodeServer, String, String, OpenCodePermissionReply) -> Unit,
    var replyForm: suspend (OpenCodeServer, String, String, Map<String, OpenCodeFormValue>) -> Unit,
    var cancelForm: suspend (OpenCodeServer, String, String) -> Unit,
    var getSession: suspend (OpenCodeServer, String) -> OpenCodeSession,
    var runningShells: suspend (OpenCodeServer, String) -> List<OpenCodeShell>,
    var shell: suspend (OpenCodeServer, String, String) -> OpenCodeShell,
    var shellOutput: suspend (OpenCodeServer, String, String, Long, Long) -> OpenCodeShellOutputPage,
    var stopShell: suspend (OpenCodeServer, String, String) -> Unit,
    var moveToBackground: suspend (OpenCodeServer, String) -> Unit,
    var viewSession: suspend (OpenCodeServer, String, Long) -> Unit,
    var contextUsage: suspend (OpenCodeServer, String) -> OpenCodeContextReading,
    var renameSession: suspend (OpenCodeServer, String, String) -> Unit,
    var compactSession: suspend (OpenCodeServer, String) -> Unit,
    var forkSession: suspend (OpenCodeServer, String) -> OpenCodeSession,
    var exportSession: suspend (OpenCodeServer, String) -> ByteArray,
    var historyPage: suspend (OpenCodeServer, String, String?) -> OpenCodeTranscriptPage,
    var events: (OpenCodeServer) -> Flow<OpenCodeServerEvent>,
) {
    companion object : DependencyKey<OpenCodeClient> {
        var waitClock: suspend (Int) -> Unit = { seconds -> delay(seconds * 1000L) }
        const val locationBootTimeout: Long = 60

        private val httpClient = OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply {
                maxRequests = 64
                maxRequestsPerHost = 64
            })
            .build()

        private val eventClient = httpClient.newBuilder()
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()

        /** Endpoints decode on a background thread so large pages never block the main thread. */
        private suspend inline fun <T> offMain(crossinline block: suspend () -> T): T = withContext(Dispatchers.Default) { block() }

        override val liveValue = OpenCodeClient(
            health = { server -> offMain { requestHealth(server) } },
            redeemPairing = { link -> offMain { requestRedeemPairing(link) } },
            homeSnapshot = { server -> offMain { requestHomeSnapshot(server) } },
            homeAttention = { server, roots, directories -> offMain { requestHomeAttention(server, roots, directories) } },
            transcript = { server, sessionID, limit -> offMain { requestTranscript(server, sessionID, limit) } },
            message = { server, sessionID, messageID -> offMain { requestMessage(server, sessionID, messageID) } },
            sendPrompt = { server, sessionID, text, attachments, files, messageID, delivery ->
                offMain { requestSendPrompt(server, sessionID, text, attachments, files, messageID, delivery) }
            },
            sendDeliveredPrompt = { server, sessionID, text, delivery -> offMain { requestSendDeliveredPrompt(server, sessionID, text, delivery) } },
            assistantReply = { server, sessionID, afterMessageID -> offMain { requestAssistantReply(server, sessionID, afterMessageID) } },
            uploadFile = { server, directory, file, name -> uploadFile(server, directory, file, name) },
            waitForIdle = { server, sessionID -> offMain { requestWaitForIdle(server, sessionID) } },
            isSessionActive = { server, sessionID -> offMain { requestIsSessionActive(server, sessionID) } },
            interrupt = { server, sessionID -> offMain { requestInterrupt(server, sessionID) } },
            createSession = { server, directory -> offMain { requestCreateSession(server, directory) } },
            deleteSession = { server, sessionID -> offMain { requestDeleteSession(server, sessionID) } },
            sessionFamily = { server, root -> offMain { requestSessionFamily(server, root) } },
            modelCatalog = { server, directory -> offMain { requestModelCatalog(server, directory) } },
            switchModel = { server, sessionID, model -> offMain { requestSwitchModel(server, sessionID, model) } },
            familyPendingRequests = { server, sessionIDs, directory -> offMain { requestFamilyPendingRequests(server, sessionIDs, directory) } },
            pendingInputs = { server, sessionID -> offMain { requestPendingInputs(server, sessionID) } },
            setPendingInputDelivery = { server, sessionID, inputID, delivery -> offMain { requestSetPendingInputDelivery(server, sessionID, inputID, delivery) } },
            cancelPendingInput = { server, sessionID, inputID -> offMain { requestCancelPendingInput(server, sessionID, inputID) } },
            replyPermission = { server, sessionID, requestID, reply -> offMain { requestReplyPermission(server, sessionID, requestID, reply) } },
            replyForm = { server, sessionID, formID, answer -> offMain { requestReplyForm(server, sessionID, formID, answer) } },
            cancelForm = { server, sessionID, formID -> offMain { requestCancelForm(server, sessionID, formID) } },
            getSession = { server, sessionID -> offMain { requestGetSession(server, sessionID) } },
            runningShells = { server, directory -> offMain { requestRunningShells(server, directory) } },
            shell = { server, directory, shellID -> offMain { requestShell(server, directory, shellID) } },
            shellOutput = { server, directory, shellID, cursor, limit -> offMain { requestShellOutput(server, directory, shellID, cursor, limit) } },
            stopShell = { server, directory, shellID -> offMain { requestStopShell(server, directory, shellID) } },
            moveToBackground = { server, sessionID -> offMain { requestMoveToBackground(server, sessionID) } },
            viewSession = { server, sessionID, idle -> offMain { requestViewSession(server, sessionID, idle) } },
            contextUsage = { server, sessionID -> offMain { requestContextUsage(server, sessionID) } },
            renameSession = { server, sessionID, title -> offMain { requestRenameSession(server, sessionID, title) } },
            compactSession = { server, sessionID -> offMain { requestCompactSession(server, sessionID) } },
            forkSession = { server, sessionID -> offMain { requestForkSession(server, sessionID) } },
            exportSession = { server, sessionID -> offMain { requestExportSession(server, sessionID) } },
            historyPage = { server, sessionID, cursor -> offMain { requestHistoryPage(server, sessionID, cursor) } },
            events = { server -> subscribeEvents(server) },
        )

        override val testValue get() = OpenCodeClient(
            health = { unimplemented() },
            redeemPairing = { unimplemented() },
            homeSnapshot = { unimplemented() },
            homeAttention = { _, _, _ -> unimplemented() },
            transcript = { _, _, _ -> unimplemented() },
            message = { _, _, _ -> unimplemented() },
            sendPrompt = { _, _, _, _, _, _, _ -> unimplemented() },
            sendDeliveredPrompt = { _, _, _, _ -> unimplemented() },
            assistantReply = { _, _, _ -> unimplemented() },
            uploadFile = { _, _, _, _ -> unimplemented() },
            waitForIdle = { _, _ -> unimplemented() },
            isSessionActive = { _, _ -> unimplemented() },
            interrupt = { _, _ -> unimplemented() },
            createSession = { _, _ -> unimplemented() },
            deleteSession = { _, _ -> unimplemented() },
            sessionFamily = { _, _ -> unimplemented() },
            modelCatalog = { _, _ -> unimplemented() },
            switchModel = { _, _, _ -> unimplemented() },
            familyPendingRequests = { _, _, _ -> unimplemented() },
            pendingInputs = { _, _ -> unimplemented() },
            setPendingInputDelivery = { _, _, _, _ -> unimplemented() },
            cancelPendingInput = { _, _, _ -> unimplemented() },
            replyPermission = { _, _, _, _ -> unimplemented() },
            replyForm = { _, _, _, _ -> unimplemented() },
            cancelForm = { _, _, _ -> unimplemented() },
            getSession = { _, _ -> unimplemented() },
            runningShells = { _, _ -> unimplemented() },
            shell = { _, _, _ -> unimplemented() },
            shellOutput = { _, _, _, _, _ -> unimplemented() },
            stopShell = { _, _, _ -> unimplemented() },
            moveToBackground = { _, _ -> unimplemented() },
            viewSession = { _, _, _ -> unimplemented() },
            contextUsage = { _, _ -> unimplemented() },
            renameSession = { _, _, _ -> unimplemented() },
            compactSession = { _, _ -> unimplemented() },
            forkSession = { _, _ -> unimplemented() },
            exportSession = { _, _ -> unimplemented() },
            historyPage = { _, _, _ -> unimplemented() },
            events = { unimplemented() },
        )

        private fun unimplemented(): Nothing = throw OpenCodeClientError(message = "unimplemented")

        fun decodeTranscript(data: ByteArray): List<OpenCodeTranscriptItem> = decodeTranscriptPage(data).items

        fun decodeTranscriptPage(data: ByteArray): OpenCodeTranscriptPage {
            val payload = decoded(MessagesPayload.serializer(), data, "transcript")
            return OpenCodeTranscriptPage(payload.data.asReversed().flatMap { transcriptItems(it) }, payload.cursor?.next)
        }

        fun decodeContextUsage(data: ByteArray): OpenCodeContextReading {
            val payload = decoded(ContextUsagePayload.serializer(), data, "transcript")
            for (message in payload.data) {
                if (message.type == "compaction" && message.status == "completed") return OpenCodeContextReading.empty
                val tokens = message.tokens
                val model = message.model
                if (message.type != "assistant" || tokens == null || model == null) continue
                val total = (tokens.input + tokens.output + tokens.reasoning + tokens.cache.read + tokens.cache.write).toInt()
                if (total <= 0) return OpenCodeContextReading.empty
                return OpenCodeContextReading.measured(OpenCodeContextUsage(total, model))
            }
            return if (payload.cursor?.next == null) OpenCodeContextReading.empty else OpenCodeContextReading.inconclusive
        }

        fun decodeStreamFrame(data: ByteArray): OpenCodeServerEvent? {
            val envelope = runCatching { protocolJson.decodeFromString(EventEnvelope.serializer(), data.decodeToString()) }.getOrNull()
            val tag = envelope?._tag ?: runCatching { protocolJson.decodeFromString(EventFailureFrame.serializer(), data.decodeToString()) }.getOrNull()?._tag
            if (tag != null) throw OpenCodeClientError(message = "The event stream reported a server failure.")
            return envelope?.let { serverEvent(it) }
        }

        @Serializable
        private data class PromptBody(
            var id: String,
            var text: String,
            var files: List<File>? = null,
            var delivery: String? = null,
            var metadata: Metadata? = null,
        ) {
            @Serializable
            data class File(var uri: String, var name: String? = null)
            @OptIn(ExperimentalSerializationApi::class)
            @Serializable
            data class Metadata(
                var displayText: String,
                /// Desktop only reads `displayText` when `comments` is an array.
                @EncodeDefault var comments: List<String> = emptyList(),
                var attachments: List<OpenCodeFileReference>,
            )
        }

        fun uploadPath(tmp: String, id: UUID = UUID.randomUUID(), name: String): String {
            val base = tmp.removeSuffix("/")
            return "$base/uploads/${id.toString().lowercase()}/${AttachmentFileType.sanitizedName(name)}"
        }

        private suspend fun requestHealth(server: OpenCodeServer) {
            decoded(StatusPayload.serializer(), perform(path = "/api/info", server = server))
        }

        private suspend fun requestRedeemPairing(link: OpenCodePairingLink): OpenCodeServer {
            val reachable = link.candidateOrigins.filter { !OpenCodeServer.isLoopback(it) }
            val candidates = reachable.ifEmpty { link.candidateOrigins }
            val redeemed = supervisorScope {
                val jobs = candidates.map { origin ->
                    async {
                        val anonymous = OpenCodeServer(url = origin, username = "opencode", password = "")
                        try {
                            val session = decoded(PairingSessionPayload.serializer(), perform(path = "/auth/connect/${link.code}", timeout = 5, server = anonymous))
                            Result.success(OpenCodeServer(url = origin, username = "opencode", password = session.token))
                        } catch (error: OpenCodeClientError) {
                            Result.failure(error)
                        }
                    }
                }
                var failure: OpenCodeClientError? = null
                for (job in jobs) {
                    val result = job.await()
                    if (result.isSuccess) {
                        jobs.forEach { it.cancel() }
                        return@supervisorScope result.getOrThrow()
                    }
                    failure = result.exceptionOrNull() as? OpenCodeClientError
                }
                throw pairingFailure(jobs.mapNotNull { it.await().exceptionOrNull() as? OpenCodeClientError }.ifEmpty { listOfNotNull(failure) })
            }
            val info = runCatching { decoded(InfoPayload.serializer(), perform(path = "/api/info", server = redeemed)) }.getOrNull()
            val seen = mutableSetOf(redeemed.url)
            redeemed.alternateURLs = (candidates + (info?.urls ?: emptyList()))
                .map { it.trim('/') }
                .filter { !OpenCodeServer.isLoopback(it) && OpenCodeServer(url = it, username = "", password = "").endpoint("/") != null }
                .filter { seen.add(it) }
            return redeemed
        }

        private fun pairingFailure(failures: List<OpenCodeClientError>): OpenCodeClientError {
            if (failures.any { it.statusCode == 401 }) {
                return OpenCodeClientError(
                    message = "This pairing code has expired or was already used. Run `opencode pair` again and scan the new code.",
                    statusCode = 401,
                )
            }
            if (failures.any { it.statusCode == 404 }) {
                return OpenCodeClientError(
                    message = "This server doesn’t support pairing links. Update OpenCode on your computer, or enter the server manually.",
                    statusCode = 404,
                )
            }
            return failures.lastOrNull() ?: OpenCodeClientError(message = "Couldn’t reach the OpenCode server")
        }

        private suspend fun requestHomeSnapshot(server: OpenCodeServer): OpenCodeHomeSnapshot = try {
            coroutineScope {
                val projects = async { decoded(ListSerializer(ProjectPayload.serializer()), perform(path = "/api/project", server = server)) }
                val sessions = async {
                    decoded(DataPayload.serializer(ListSerializer(SessionPayload.serializer())), perform(
                        path = "/api/session",
                        query = listOf(URLQueryItem("parentID", "null"), URLQueryItem("order", "desc"), URLQueryItem("limit", "50")),
                        server = server,
                    ))
                }
                val active = async {
                    decoded(DataPayload.serializer(MapSerializer(String.serializer(), ActiveSessionPayload.serializer())), perform(path = "/api/session/active", server = server))
                }
                val roots = sessions.await().data.map { session(it) }
                val activeIDs = active.await().data.keys
                val parents = activeSessionParents(server, activeIDs, roots.map { it.id }.toSet())
                OpenCodeHomeSnapshot(
                    projects = projects.await().map { OpenCodeProject(it.id, it.name ?: it.canonical.substringAfterLast('/'), it.canonical) },
                    sessions = roots,
                    activeSessionIDs = activeIDs,
                    activeSessionParents = parents.ifEmpty { null },
                )
            }
        } catch (error: OpenCodeClientError) {
            throw error
        } catch (_: Exception) {
            throw OpenCodeClientError(message = "Couldn’t load projects and sessions")
        }

        private suspend fun activeSessionParents(server: OpenCodeServer, activeIDs: Set<String>, roots: Set<String>): Map<String, String> {
            val visited = roots.toMutableSet()
            val pending = activeIDs.subtract(roots).sorted().toMutableList()
            val parents = mutableMapOf<String, String>()
            while (pending.isNotEmpty()) {
                val batch = pending.take(6)
                repeat(batch.size) { pending.removeAt(0) }
                val results = supervisorScope {
                    batch.filter { visited.add(it) }.map { id ->
                        async {
                            try {
                                decoded(DataPayload.serializer(SessionPayload.serializer()), perform(path = "/api/session/$id", server = server)).data
                            } catch (error: OpenCodeClientError) {
                                if (error.statusCode == 404) null else throw error
                            }
                        }
                    }.map { it.await() }
                }
                for (session in results) {
                    val parent = session?.parentID ?: continue
                    parents[session.id] = parent
                    if (parent !in visited && parent !in pending) pending += parent
                }
            }
            return parents
        }

        private suspend fun requestTranscript(server: OpenCodeServer, sessionID: String, limit: Int): List<OpenCodeTranscriptItem> {
            val data = perform(
                path = "/api/session/$sessionID/message",
                query = listOf(URLQueryItem("order", "desc"), URLQueryItem("limit", limit.toString())),
                server = server,
            )
            return decodeTranscript(data)
        }

        private suspend fun requestMessage(server: OpenCodeServer, sessionID: String, messageID: String): List<OpenCodeTranscriptItem> {
            val data = perform(path = "/api/session/$sessionID/message/$messageID", server = server)
            return transcriptItems(decoded(DataPayload.serializer(MessagePayload.serializer()), data, "message").data)
        }

        private suspend fun requestHistoryPage(server: OpenCodeServer, sessionID: String, cursor: String?): OpenCodeTranscriptPage {
            val query = listOf(URLQueryItem("limit", "40"), cursor?.let { URLQueryItem("cursor", it) } ?: URLQueryItem("order", "desc"))
            return decodeTranscriptPage(perform(path = "/api/session/$sessionID/message", query = query, server = server))
        }

        private suspend fun requestSendPrompt(
            server: OpenCodeServer,
            sessionID: String,
            text: String,
            attachments: List<OpenCodeOutgoingAttachment>,
            files: List<OpenCodeFileReference>,
            messageID: String,
            delivery: OpenCodeDelivery,
        ) {
            postPrompt(server, sessionID, messageID, text, attachments, files, delivery)
        }

        private suspend fun requestSendDeliveredPrompt(server: OpenCodeServer, sessionID: String, text: String, delivery: OpenCodeDelivery?): String {
            val messageID = "msg_" + UUID.randomUUID().toString().replace("-", "").uppercase()
            postPrompt(server, sessionID, messageID, text, delivery = delivery)
            return messageID
        }

        private suspend fun postPrompt(
            server: OpenCodeServer,
            sessionID: String,
            messageID: String,
            text: String,
            attachments: List<OpenCodeOutgoingAttachment> = emptyList(),
            files: List<OpenCodeFileReference> = emptyList(),
            delivery: OpenCodeDelivery?,
        ) {
            val body = encodedBody(
                PromptBody.serializer(),
                PromptBody(
                    id = messageID,
                    text = OpenCodePromptPresentation.modelText(text, files),
                    files = if (attachments.isEmpty()) null else attachments.map { PromptBody.File(uri = it.dataURL, name = it.name) },
                    delivery = delivery?.name,
                    metadata = if (files.isEmpty()) null else PromptBody.Metadata(displayText = text, attachments = files),
                ),
                "message",
            )
            perform(path = "/api/session/$sessionID/prompt", method = "POST", body = body, timeout = 30, server = server)
        }

        private suspend fun requestAssistantReply(server: OpenCodeServer, sessionID: String, afterMessageID: String): String? {
            val data = perform(
                path = "/api/session/$sessionID/message",
                query = listOf(URLQueryItem("order", "desc"), URLQueryItem("limit", "40")),
                server = server,
            )
            val messages = decoded(MessagesPayload.serializer(), data, "transcript").data.asReversed()
            val anchor = messages.indexOfFirst { it.id == afterMessageID }
            if (anchor < 0) throw OpenCodeClientError(message = "The voice request is no longer in the recent transcript.")
            var reply: String? = null
            for (message in messages.drop(anchor + 1)) {
                val text = (message.content ?: emptyList()).filter { it.type == "text" }.mapNotNull { it.text }.joinToString("\n").trim()
                if (message.type == "user") {
                    if (reply != null || VoiceHandoff.parse(text) != null) break
                    continue
                }
                if (message.type != "assistant") continue
                message.error?.let { throw OpenCodeClientError(message = runErrorText(it)) }
                if (text.isNotEmpty()) reply = text
            }
            return reply
        }

        private fun uploadFile(server: OpenCodeServer, directory: String?, file: java.io.File, name: String): Flow<OpenCodeUploadEvent> = flow {
            val info = try {
                decoded(InfoPayload.serializer(), perform(path = "/api/info", server = server))
            } catch (error: OpenCodeClientError) {
                if (error.statusCode == 404) throw OpenCodeClientError.uploadsUnsupported else throw error
            }
            val tmp = info.paths?.tmp?.takeIf { it.isNotEmpty() } ?: throw OpenCodeClientError.uploadsUnsupported
            val query = locationQuery(directory) + URLQueryItem("path", uploadPath(tmp, name = name))
            val url = server.endpoint("/api/experimental/fs/write", query) ?: throw OpenCodeClientError(message = "Enter a valid HTTP or HTTPS server URL.")
            val bytes = file.readBytes()
            val request = Request.Builder()
                .url(url.toHttpUrl())
                .header("Accept", "application/json")
                .header("Content-Type", "application/octet-stream")
                .apply { server.basicAuthorization?.let { header("Authorization", it) } }
                .post(bytes.toRequestBody("application/octet-stream".toMediaType()))
                .build()
            emit(OpenCodeUploadEvent.progress(0))
            val response = execute(httpClient.newBuilder().callTimeout(120, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build(), request)
            val body = response.body
            if (response.code == 404 && !body.decodeToString().contains("Location")) throw OpenCodeClientError.uploadsUnsupported
            if (response.code !in 200..299) throw httpError(response.code)
            emit(OpenCodeUploadEvent.progress(bytes.size.toLong()))
            emit(OpenCodeUploadEvent.finished(decoded(DataPayload.serializer(WrittenFilePayload.serializer()), body).data.path))
        }.flowOn(Dispatchers.IO)

        private suspend fun requestWaitForIdle(server: OpenCodeServer, sessionID: String) {
            var passes = 0
            while (true) {
                val started = System.nanoTime()
                try {
                    perform(path = "/api/experimental/session/$sessionID/wait", method = "POST", timeout = 600, server = server)
                    return
                } catch (error: OpenCodeClientError) {
                    passes += 1
                    val active = passes < 12 && error.isRetryableWaitError && runCatching { requestIsSessionActive(server, sessionID) }.getOrNull() == true
                    if (!active) throw error
                    if (System.nanoTime() - started < 30_000_000_000) {
                        val delaySeconds = minOf(16, 1 shl minOf(passes - 1, 4))
                        waitClock(delaySeconds)
                    }
                }
            }
        }

        private suspend fun requestIsSessionActive(server: OpenCodeServer, sessionID: String): Boolean {
            val active = decoded(
                DataPayload.serializer(MapSerializer(String.serializer(), ActiveSessionPayload.serializer())),
                perform(path = "/api/session/active", server = server),
            )
            return active.data.containsKey(sessionID)
        }

        private suspend fun requestInterrupt(server: OpenCodeServer, sessionID: String) {
            perform(path = "/api/session/$sessionID/interrupt", method = "POST", server = server)
        }

        private suspend fun requestGetSession(server: OpenCodeServer, sessionID: String): OpenCodeSession =
            session(decoded(DataPayload.serializer(SessionPayload.serializer()), perform(path = "/api/session/$sessionID", server = server)).data)

        private suspend fun requestContextUsage(server: OpenCodeServer, sessionID: String): OpenCodeContextReading =
            decodeContextUsage(perform(
                path = "/api/session/$sessionID/message",
                query = listOf(URLQueryItem("order", "desc"), URLQueryItem("limit", "8")),
                server = server,
            ))

        private suspend fun requestRenameSession(server: OpenCodeServer, sessionID: String, title: String) {
            perform(
                path = "/api/session/$sessionID",
                method = "PATCH",
                body = encodedBody(TitleBody.serializer(), TitleBody(title)),
                timeout = if (title.isEmpty()) 60 else 10,
                server = server,
            )
        }

        private suspend fun requestCompactSession(server: OpenCodeServer, sessionID: String) {
            perform(path = "/api/session/$sessionID/compact", method = "POST", body = "{}".toByteArray(), server = server)
        }

        private suspend fun requestForkSession(server: OpenCodeServer, sessionID: String): OpenCodeSession =
            session(decoded(DataPayload.serializer(SessionPayload.serializer()), perform(path = "/api/session/$sessionID/fork", method = "POST", body = "{}".toByteArray(), server = server)).data)

        private suspend fun requestExportSession(server: OpenCodeServer, sessionID: String): ByteArray =
            perform(path = "/api/experimental/session/$sessionID/export", timeout = 60, server = server)

        private suspend fun requestViewSession(server: OpenCodeServer, sessionID: String, idle: Long) {
            perform(path = "/api/session/$sessionID/view", method = "POST", body = encodedBody(IdleBody.serializer(), IdleBody(idle)), server = server)
        }

        private suspend fun requestCreateSession(server: OpenCodeServer, directory: String): OpenCodeSession =
            session(decoded(DataPayload.serializer(SessionPayload.serializer()), perform(
                path = "/api/session",
                method = "POST",
                body = encodedBody(CreateBody.serializer(), CreateBody(CreateBody.Location(directory))),
                server = server,
            )).data)

        private suspend fun requestDeleteSession(server: OpenCodeServer, sessionID: String) {
            perform(path = "/api/session/$sessionID", method = "DELETE", server = server)
        }

        private suspend fun requestSessionFamily(server: OpenCodeServer, root: OpenCodeSession): OpenCodeSessionFamily = try {
            supervisorScope {
                val active = async {
                    captured { decoded(DataPayload.serializer(MapSerializer(String.serializer(), ActiveSessionPayload.serializer())), perform(path = "/api/session/active", server = server)) }
                }
                try {
                val sessions = mutableListOf(root)
                val queue = mutableListOf(root.id)
                var queueIndex = 0
                val visited = mutableSetOf(root.id)
                while (queueIndex < queue.size && sessions.size < 100) {
                    val end = minOf(queueIndex + 6, queue.size)
                    val parents = queue.subList(queueIndex, end).toList()
                    queueIndex = end
                    val jobs = parents.mapIndexed { index, parentID ->
                        async {
                            index to captured {
                                decoded(DataPayload.serializer(ListSerializer(SessionPayload.serializer())), perform(
                                    path = "/api/session",
                                    query = listOf(URLQueryItem("parentID", parentID), URLQueryItem("order", "desc"), URLQueryItem("limit", "100")),
                                    server = server,
                                ))
                            }
                        }
                    }
                    try {
                        val completed = mutableMapOf<Int, Result<DataPayload<List<SessionPayload>>>>()
                        var next = 0
                        for (job in jobs) {
                            if (sessions.size >= 100) break
                            val (index, result) = job.await()
                            completed[index] = result
                            while (sessions.size < 100 && completed.containsKey(next)) {
                                val payload = completed.remove(next)!!.getOrThrow()
                                for (child in payload.data.map { session(it) }) {
                                    if (visited.add(child.id)) {
                                        sessions += child
                                        queue += child.id
                                    }
                                }
                                next += 1
                            }
                            if (sessions.size >= 100) break
                        }
                    } finally {
                        jobs.forEach { it.cancel() }
                        jobs.forEach { runCatching { it.join() } }
                    }
                }
                val activeResult = active.await().getOrThrow()
                OpenCodeSessionFamily(sessions, activeResult.data.keys)
                } finally {
                    active.cancel()
                    runCatching { active.join() }
                }
            }
        } catch (error: OpenCodeClientError) {
            throw error
        } catch (_: CancellationException) {
            throw OpenCodeClientError(message = "Couldn’t reach the OpenCode server")
        } catch (_: Exception) {
            throw OpenCodeClientError(message = "Couldn’t load the session family")
        }

        private fun session(payload: SessionPayload): OpenCodeSession = OpenCodeSession(
            id = payload.id,
            title = payload.title?.trim()?.nonEmpty ?: "Untitled session",
            projectID = payload.projectID,
            directory = payload.location.directory,
            updatedAt = payload.time.updated.toLong(),
            idleMilliseconds = payload.time.idle,
            viewedMilliseconds = payload.time.viewed,
            model = payload.model,
            parentID = payload.parentID,
            agent = payload.agent,
            cost = payload.cost,
        )

        private suspend fun requestModelCatalog(server: OpenCodeServer, directory: String?): OpenCodeModelCatalog {
            val key = "${server.persistenceID}|${directory ?: ""}"
            OpenCodeReadCache.shared.modelCatalog(key)?.let { return it }
            val query = locationQuery(directory)
            val payload = decoded(DataPayload.serializer(ListSerializer(ModelInfoPayload.serializer())), perform(path = "/api/model", query = query, timeout = 20, server = server))
            val models = payload.data.filter { it.enabled && it.status == "active" }.map { it.model }
            val defaultModel = runCatching {
                decoded(DataPayload.serializer(ModelInfoPayload.serializer()), perform(path = "/api/model/default", query = query, timeout = 20, server = server)).data?.model
            }.getOrNull()
            return OpenCodeModelCatalog(models, defaultModel).also { OpenCodeReadCache.shared.storeModelCatalog(it, key) }
        }

        private suspend fun requestSwitchModel(server: OpenCodeServer, sessionID: String, model: OpenCodeModelRef) {
            perform(path = "/api/session/$sessionID/model", method = "POST", body = encodedBody(SwitchBody.serializer(), SwitchBody(model)), server = server)
        }

        private suspend fun requestFamilyPendingRequests(server: OpenCodeServer, sessionIDs: List<String>, directory: String): OpenCodePendingRequests {
            val requests = requestLocationPendingRequests(server, directory)
            val family = sessionIDs.toSet()
            return OpenCodePendingRequests(
                permissions = requests.permissions.filter { it.sessionID in family },
                forms = requests.forms.filter { it.sessionID == "global" || it.sessionID in family },
            )
        }

        private suspend fun requestLocationPendingRequests(server: OpenCodeServer, directory: String): OpenCodePendingRequests = coroutineScope {
            val query = locationQuery(directory)
            val permissions = async {
                captured {
                    decoded(DataPayload.serializer(ListSerializer(OpenCodePermissionRequest.serializer())), perform(path = "/api/permission/request", query = query, timeout = locationBootTimeout, server = server))
                }
            }
            val forms = async {
                captured {
                    decoded(DataPayload.serializer(ListSerializer(OpenCodeFormRequest.serializer())), perform(path = "/api/form", query = query, timeout = locationBootTimeout, server = server))
                }
            }
            val permissionResult = permissions.await()
            val formResult = forms.await()
            OpenCodePendingRequests(permissionResult.getOrThrow().data, formResult.getOrThrow().data)
        }

        private suspend fun requestHomeAttention(server: OpenCodeServer, roots: List<OpenCodeSession>, directories: List<String>): Set<String> {
            val pending = mutableSetOf<String>()
            for (directory in directories) {
                val requests = requestLocationPendingRequests(server, directory)
                pending += requests.permissions.map { it.sessionID }
                pending += requests.forms.map { it.sessionID }.filter { it != "global" }
            }
            val known = roots.associate { it.id to it.id }.toMutableMap()
            return pending.map { requestRootSessionID(server, it, known) }.toSet()
        }

        private suspend fun requestRootSessionID(server: OpenCodeServer, sessionID: String, knownRoots: MutableMap<String, String>): String {
            var currentID = sessionID
            val visited = mutableSetOf<String>()
            val lineage = mutableListOf<String>()
            while (visited.add(currentID)) {
                knownRoots[currentID]?.let { rootID ->
                    lineage.forEach { knownRoots[it] = rootID }
                    return rootID
                }
                lineage += currentID
                val payload = decoded(DataPayload.serializer(SessionPayload.serializer()), perform(path = "/api/session/$currentID", server = server)).data
                val parentID = payload.parentID ?: run {
                    lineage.forEach { knownRoots[it] = payload.id }
                    return payload.id
                }
                currentID = parentID
            }
            return sessionID
        }

        private suspend fun requestPendingInputs(server: OpenCodeServer, sessionID: String): List<OpenCodePendingInput> =
            pendingInputRows(decoded(PendingInputsPayload.serializer(), perform(path = "/api/session/$sessionID/inbox", server = server)))

        private suspend fun requestSetPendingInputDelivery(server: OpenCodeServer, sessionID: String, inputID: String, delivery: OpenCodeDelivery) {
            perform(path = "/api/session/$sessionID/inbox/$inputID", method = "PATCH", body = encodedBody(DeliveryBody.serializer(), DeliveryBody(delivery.name)), server = server)
        }

        private suspend fun requestCancelPendingInput(server: OpenCodeServer, sessionID: String, inputID: String) {
            perform(path = "/api/session/$sessionID/inbox/$inputID", method = "DELETE", server = server)
        }

        private suspend fun requestRunningShells(server: OpenCodeServer, directory: String): List<OpenCodeShell> =
            decoded(DataPayload.serializer(ListSerializer(ShellInfo.serializer())), perform(path = "/api/shell", query = locationQuery(directory), server = server))
                .data.filter { it.status == "running" }.map { it.shell(isListed = true) }

        private suspend fun requestShell(server: OpenCodeServer, directory: String, shellID: String): OpenCodeShell =
            decoded(DataPayload.serializer(ShellInfo.serializer()), perform(path = "/api/shell/$shellID", query = locationQuery(directory), server = server)).data.shell(true)

        private suspend fun requestShellOutput(server: OpenCodeServer, directory: String, shellID: String, cursor: Long, limit: Long): OpenCodeShellOutputPage =
            decoded(DataPayload.serializer(OpenCodeShellOutputPage.serializer()), perform(
                path = "/api/shell/$shellID/output",
                query = locationQuery(directory) + listOf(URLQueryItem("cursor", cursor.toString()), URLQueryItem("limit", limit.toString())),
                timeout = 20,
                server = server,
            )).data

        private suspend fun requestStopShell(server: OpenCodeServer, directory: String, shellID: String) {
            perform(path = "/api/shell/$shellID", query = locationQuery(directory), method = "DELETE", server = server)
        }

        private suspend fun requestMoveToBackground(server: OpenCodeServer, sessionID: String) {
            perform(path = "/api/session/$sessionID/background", method = "POST", server = server)
        }

        private suspend fun requestReplyPermission(server: OpenCodeServer, sessionID: String, requestID: String, reply: OpenCodePermissionReply) {
            perform(path = "/api/session/$sessionID/permission/$requestID/reply", method = "POST", body = encodedBody(DecisionBody.serializer(), DecisionBody(reply), "permission response"), server = server)
        }

        private suspend fun requestReplyForm(server: OpenCodeServer, sessionID: String, formID: String, answer: Map<String, OpenCodeFormValue>) {
            perform(path = "/api/session/$sessionID/form/$formID/reply", method = "POST", body = encodedBody(AnswerBody.serializer(), AnswerBody(answer), "form response"), server = server)
        }

        private suspend fun requestCancelForm(server: OpenCodeServer, sessionID: String, formID: String) {
            perform(path = "/api/session/$sessionID/form/$formID", method = "DELETE", server = server)
        }

        private fun subscribeEvents(server: OpenCodeServer): Flow<OpenCodeServerEvent> = flow {
            val url = server.endpoint("/api/event") ?: throw OpenCodeClientError(message = "Enter a valid HTTP or HTTPS server URL.")
            val request = Request.Builder()
                .url(url.toHttpUrl())
                .header("Accept", "text/event-stream")
                .apply { server.basicAuthorization?.let { header("Authorization", it) } }
                .build()
            val call = eventClient.newCall(request)
            kotlin.coroutines.coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
            val response = withContext(Dispatchers.IO) { call.execute() }
            response.use {
                if (it.code == 401) throw OpenCodeClientError(message = "The server rejected these credentials.", statusCode = 401)
                if (it.code !in 200..299) throw OpenCodeClientError(message = "The event stream returned HTTP ${it.code}.", statusCode = it.code)
                val body = it.body
                val batcher = EventBatcher { event -> emit(event) }
                val source = body.source()
                while (true) {
                    val line = withContext(Dispatchers.IO) { source.readUtf8Line() } ?: break
                    if (!line.startsWith("data:")) continue
                    if (line.toByteArray(Charsets.UTF_8).size - 5 > 16 * 1024 * 1024) {
                        throw OpenCodeClientError(message = "The event stream sent an oversized frame.")
                    }
                    val event = decodeStreamFrame(line.removePrefix("data:").toByteArray()) ?: continue
                    batcher.receive(event)
                }
                batcher.flush()
                throw OpenCodeClientError(message = "The event stream ended.")
            }
        }.buffer(128, BufferOverflow.DROP_OLDEST).flowOn(Dispatchers.IO)

        private fun locationQuery(directory: String?): List<URLQueryItem> =
            directory?.let { listOf(URLQueryItem("location[directory]", it)) } ?: emptyList()

        private fun runErrorText(error: ErrorPayload): String {
            val raw = error.message ?: ""
            val human = raw.substringBefore("{").trim(' ', ':')
            val kind = error.type ?: "error"
            return if (human.isEmpty()) "Run failed: $kind" else "Run failed: $kind — $human"
        }

        private fun transcriptItems(message: MessagePayload): List<OpenCodeTranscriptItem> = when (message.type) {
            "user" -> userItems(message)
            "assistant" -> assistantItems(message)
            "shell" -> listOf(OpenCodeTranscriptItem.tool(message.id, OpenCodeToolCall(
                callID = message.shellID ?: message.id,
                name = "shell",
                status = OpenCodeToolCall.shell(message.status, message.exit),
                input = OpenCodeJSONValue.`object`(mapOf("command" to OpenCodeJSONValue.string(message.command ?: ""))),
                output = listOfNotNull(message.output?.output?.bounded(20_000)),
                error = if (message.status == "killed") "Shell was killed" else message.exit?.takeIf { it != 0 }?.let { "Command exited with code $it" },
                metadata = message.exit?.let { mapOf("exit" to OpenCodeJSONValue.number(it.toDouble())) } ?: emptyMap(),
            )))
            "skill" -> listOf(OpenCodeTranscriptItem.tool(message.id, OpenCodeToolCall(
                callID = message.id,
                name = "skill",
                status = OpenCodeToolCall.Status.completed,
                input = message.name?.let { OpenCodeJSONValue.`object`(mapOf("name" to OpenCodeJSONValue.string(it))) } ?: OpenCodeJSONValue.`null`,
                output = emptyList(),
                metadata = emptyMap(),
            )))
            "compaction" -> listOf(OpenCodeTranscriptItem.compaction(message.id, compaction(message)))
            "synthetic" -> {
                val notice = syntheticNotice(message.description, message.metadata) ?: return emptyList()
                val description = message.description?.trim() ?: ""
                listOf(OpenCodeTranscriptItem.notice(message.id, "$notice · $description", message.metadata?.get("state")?.string == "error"))
            }
            else -> emptyList()
        }

        private fun userItems(message: MessagePayload): List<OpenCodeTranscriptItem> {
            val items = mutableListOf<OpenCodeTranscriptItem>()
            val images = (message.files ?: emptyList()).mapIndexedNotNull { index, file ->
                val mime = file.mime ?: return@mapIndexedNotNull null
                if (!mime.startsWith("image/")) return@mapIndexedNotNull null
                val encoded = file.data ?: return@mapIndexedNotNull null
                val data = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: return@mapIndexedNotNull null
                OpenCodeImageAttachment("${message.id}-img-$index", data, mime)
            }
            if (images.isNotEmpty()) items += OpenCodeTranscriptItem.userImages("${message.id}-images", images)
            val references = OpenCodePromptPresentation.files(message.metadata)
            val inline = (message.files ?: emptyList()).mapIndexedNotNull { index, file ->
                val mime = file.mime ?: return@mapIndexedNotNull null
                if (mime.startsWith("image/") || !file.isAttached) return@mapIndexedNotNull null
                val name = file.name?.substringAfterLast('/')?.ifEmpty { "File" } ?: "File"
                OpenCodeTranscriptFile("${message.id}-file-inline-$index", name, mime)
            }
            val files = references.mapIndexed { index, file ->
                OpenCodeTranscriptFile("${message.id}-file-$index", file.name, file.mime, file.path)
            } + inline
            if (files.isNotEmpty()) items += OpenCodeTranscriptItem.userFiles("${message.id}-files", files)
            val text = OpenCodePromptPresentation.displayText(message.text ?: "", references).trim().nonEmpty
            if (text != null) items += OpenCodeTranscriptItem.userText(message.id, text)
            return items
        }

        private fun assistantItems(message: MessagePayload): List<OpenCodeTranscriptItem> {
            var textOrdinal = 0
            val parts = (message.content ?: emptyList()).mapNotNull { part ->
                when (part.type) {
                    "text" -> {
                        val partID = "${message.id}-text-$textOrdinal"
                        textOrdinal += 1
                        val text = part.text?.trim()?.nonEmpty ?: return@mapNotNull null
                        OpenCodeTranscriptItem.assistantMarkdown(partID, OpenCodeMarkdownBlock.blocks(text))
                    }
                    "tool" -> {
                        val partID = "${message.id}-tool-${part.id ?: "unknown"}"
                        val state = part.state
                        OpenCodeTranscriptItem.tool(partID, OpenCodeToolCall(
                            callID = part.id ?: partID,
                            messageID = message.id,
                            name = part.name ?: "tool",
                            status = OpenCodeToolCall.tool(state?.status),
                            input = (state?.input ?: OpenCodeJSONValue.`null`).bounded(),
                            output = state?.content?.filter { it.type == "text" }?.mapNotNull { it.text?.bounded(20_000) } ?: emptyList(),
                            error = state?.error?.message,
                            metadata = (state?.metadata ?: emptyMap()).mapValues { it.value.bounded() },
                            images = toolImages(state?.content, partID),
                            startedAt = (part.time?.ran ?: part.time?.created)?.toLong(),
                        ))
                    }
                    else -> null
                }
            }.toMutableList()
            message.error?.let { parts += OpenCodeTranscriptItem.info("${message.id}-error", runErrorText(it)) }
            return parts
        }

        private fun toolImages(content: List<ToolContentPayload>?, idPrefix: String): List<OpenCodeImageAttachment> {
            var total = 0
            val images = mutableListOf<OpenCodeImageAttachment>()
            for ((index, part) in (content ?: emptyList()).withIndex()) {
                if (images.size >= 16) break
                val mime = part.mime ?: continue
                if (part.type != "file" || !mime.lowercase().startsWith("image/")) continue
                val uri = part.uri ?: continue
                val comma = uri.indexOf(',')
                if (comma < 0) continue
                val header = uri.substring(0, comma).lowercase()
                if (!header.startsWith("data:${mime.lowercase()}") || !header.endsWith(";base64")) continue
                val encoded = uri.substring(comma + 1)
                if (encoded.toByteArray().size > 20_000_000 * 4 / 3 + 4) continue
                val data = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: continue
                if (data.size > 20_000_000 || total + data.size > 32_000_000) continue
                total += data.size
                images += OpenCodeImageAttachment("$idPrefix-image-$index", data, mime)
            }
            return images
        }

        private fun compaction(message: MessagePayload): OpenCodeCompaction {
            val status = when {
                message.status == "running" -> OpenCodeCompaction.Status.running
                message.status == "failed" && message.error?.type == "aborted" -> OpenCodeCompaction.Status.cancelled
                message.status == "failed" -> OpenCodeCompaction.Status.failed
                else -> OpenCodeCompaction.Status.completed
            }
            val tokens = message.tokens?.let {
                (it.input + it.cache.read + it.cache.write).toInt() to (it.output + it.reasoning).toInt()
            }
            val hasUsage = status != OpenCodeCompaction.Status.running && tokens != null && tokens.first + tokens.second > 0
            return OpenCodeCompaction(
                status = status,
                isProvider = status == OpenCodeCompaction.Status.completed && message.providerContext != null && message.providerContext != OpenCodeJSONValue.`null`,
                summary = OpenCodeMarkdownBlock.blocks((message.summary ?: "").trim().bounded(20_000)),
                errorMessage = if (status == OpenCodeCompaction.Status.failed) message.error?.message?.trim()?.nonEmpty else null,
                inputTokens = if (hasUsage) tokens?.first else null,
                outputTokens = if (hasUsage) tokens?.second else null,
            )
        }

        private fun syntheticNotice(description: String?, metadata: Map<String, OpenCodeJSONValue>?): String? {
            if (description?.trim()?.nonEmpty == null) return null
            val source = metadata?.get("source")?.string
            val status = metadata?.get("state")?.string
            if (source == "shell") return if (status == "error") "Shell failed" else "Shell finished"
            if (source == "subagent") return if (status == "error") "Agent failed" else "Agent finished"
            return "Session update"
        }

        private fun pendingInputRows(payload: PendingInputsPayload): List<OpenCodePendingInput> = payload.data.mapNotNull { item ->
            if (item.type == "move") return@mapNotNull null
            if (item.type == "synthetic") {
                val notice = syntheticNotice(item.payload?.description, item.payload?.metadata) ?: return@mapNotNull null
                return@mapNotNull OpenCodePendingInput(id = item.id, text = "", notice = notice)
            }
            val files = OpenCodePromptPresentation.files(item.payload?.metadata)
            OpenCodePendingInput(
                id = item.id,
                text = OpenCodePromptPresentation.displayText(item.payload?.text ?: "", files).trim(),
                isCompaction = item.type == "compaction",
                delivery = item.delivery?.let { runCatching { OpenCodeDelivery.valueOf(it) }.getOrNull() } ?: OpenCodeDelivery.steer,
                files = files,
            )
        }

        private fun serverEvent(envelope: EventEnvelope): OpenCodeServerEvent? {
            val data = envelope.data
            return when (envelope.type) {
                "server.connected" -> OpenCodeServerEvent.connected
                "session.execution.started" -> data?.sessionID?.let { OpenCodeServerEvent.executionStarted(it) }
                "session.execution.succeeded", "session.execution.failed", "session.execution.interrupted" ->
                    data?.sessionID?.let { OpenCodeServerEvent.executionEnded(it) }
                "session.created" -> OpenCodeServerEvent.sessionCreated
                "session.viewed" -> OpenCodeServerEvent.sessionViewChanged
                "session.renamed" -> {
                    val id = data?.sessionID ?: return null
                    val title = data.title ?: return null
                    OpenCodeServerEvent.sessionRenamed(id, title)
                }
                "session.deleted" -> data?.sessionID?.let { OpenCodeServerEvent.sessionDeleted(it) }
                "session.inbox.cancelled" -> {
                    val id = data?.sessionID ?: return null
                    val inputID = data.inboxID ?: return null
                    OpenCodeServerEvent.inputCancelled(id, inputID)
                }
                "session.inbox.enqueued", "session.inbox.delivery.changed" -> data?.sessionID?.let { OpenCodeServerEvent.inputEnqueued(it) }
                "session.inbox.delivered" -> data?.sessionID?.let { OpenCodeServerEvent.inputQueueChanged(it) }
                "session.step.started" -> contentEvent(envelope) { OpenCodeLiveContentEvent.stepStarted(it) }
                "session.step.streamed" -> contentEvent(envelope) { OpenCodeLiveContentEvent.stepStreamed(it) }
                "session.step.ended", "session.step.failed" -> contentEvent(envelope) { OpenCodeLiveContentEvent.stepEnded(it) }
                "session.text.started" -> ordinalContentEvent(envelope) { id, ordinal -> OpenCodeLiveContentEvent.textStarted(id, ordinal) }
                "session.text.delta" -> data?.delta?.let { delta -> ordinalContentEvent(envelope) { id, ordinal -> OpenCodeLiveContentEvent.textDelta(id, ordinal, delta) } }
                "session.text.ended" -> data?.text?.let { text -> ordinalContentEvent(envelope) { id, ordinal -> OpenCodeLiveContentEvent.textEnded(id, ordinal, text) } }
                "session.reasoning.started" -> ordinalContentEvent(envelope) { id, ordinal -> OpenCodeLiveContentEvent.reasoningStarted(id, ordinal) }
                "session.reasoning.delta" -> data?.delta?.let { delta -> ordinalContentEvent(envelope) { id, ordinal -> OpenCodeLiveContentEvent.reasoningDelta(id, ordinal, delta) } }
                "session.reasoning.ended" -> data?.text?.let { text -> ordinalContentEvent(envelope) { id, ordinal -> OpenCodeLiveContentEvent.reasoningEnded(id, ordinal, text) } }
                "session.tool.input.started" -> data?.name?.let { name -> toolContentEvent(envelope) { messageID, toolID, _ -> OpenCodeLiveContentEvent.toolInputStarted(messageID, toolID, name) } }
                "session.tool.input.delta" -> toolContentEvent(envelope) { messageID, toolID, payload -> OpenCodeLiveContentEvent.toolInputDelta(messageID, toolID, payload.delta ?: "") }
                "session.tool.input.ended" -> toolContentEvent(envelope) { messageID, toolID, payload -> OpenCodeLiveContentEvent.toolInputEnded(messageID, toolID, payload.text ?: "") }
                "session.tool.called" -> toolContentEvent(envelope) { messageID, toolID, payload -> OpenCodeLiveContentEvent.toolCalled(messageID, toolID, payload.input ?: OpenCodeJSONValue.`object`(emptyMap())) }
                "session.tool.progress" -> toolContentEvent(envelope) { messageID, toolID, payload -> OpenCodeLiveContentEvent.toolProgress(messageID, toolID, payload.metadata ?: emptyMap()) }
                "session.tool.success" -> toolContentEvent(envelope) { messageID, toolID, payload -> OpenCodeLiveContentEvent.toolSucceeded(messageID, toolID, payload.textOutput, payload.metadata ?: emptyMap()) }
                "session.tool.failed" -> toolContentEvent(envelope) { messageID, toolID, payload ->
                    OpenCodeLiveContentEvent.toolFailed(messageID, toolID, payload.error?.message ?: "Tool failed", payload.textOutput, payload.metadata ?: emptyMap())
                }
                "session.message.content.updated", "session.synthetic", "session.instructions.updated",
                "session.agent.selected", "session.model.selected", "session.moved", "session.permissions",
                "session.retry.scheduled", "session.compaction.started", "session.compaction.delta",
                "session.compaction.ended", "session.compaction.failed", "session.shell.started", "session.shell.ended" ->
                    data?.sessionID?.let { OpenCodeServerEvent.messageInvalidated(it) }
                "permission.asked", "form.created" -> {
                    val id = data?.sessionID ?: data?.form?.sessionID
                    if (id == null || id == "global") null else OpenCodeServerEvent.attentionChanged(id)
                }
                "permission.replied", "form.replied", "form.cancelled" ->
                    if (data?.sessionID == null) null else OpenCodeServerEvent.attentionChanged(null)
                "shell.created" -> data?.info?.takeIf { it.status == "running" }?.let { OpenCodeServerEvent.shellStarted(it.shell(false)) }
                "shell.exited", "shell.deleted" -> data?.id?.let { OpenCodeServerEvent.shellEnded(it) }
                else -> null
            }
        }

        private fun contentEvent(envelope: EventEnvelope, make: (String) -> OpenCodeLiveContentEvent): OpenCodeServerEvent? {
            val payload = envelope.data ?: return null
            val sessionID = payload.sessionID ?: return null
            val messageID = payload.assistantMessageID ?: return null
            return OpenCodeServerEvent.contentBatch(sessionID, listOf(make(messageID)))
        }

        private fun ordinalContentEvent(envelope: EventEnvelope, make: (String, Int) -> OpenCodeLiveContentEvent): OpenCodeServerEvent? {
            val ordinal = envelope.data?.ordinal ?: return null
            return contentEvent(envelope) { make(it, ordinal) }
        }

        private fun toolContentEvent(
            envelope: EventEnvelope,
            make: (String, String, EventEnvelope.Payload) -> OpenCodeLiveContentEvent,
        ): OpenCodeServerEvent? {
            val payload = envelope.data ?: return null
            val toolID = payload.id ?: return null
            return contentEvent(envelope) { make(it, toolID, payload) }
        }

        private suspend fun <T> captured(block: suspend () -> T): Result<T> =
            try { Result.success(block()) } catch (error: OpenCodeClientError) { Result.failure(error) }

        private fun <T> encodedBody(serializer: KSerializer<T>, value: T, name: String = "request"): ByteArray =
            try { protocolJson.encodeToString(serializer, value).toByteArray() } catch (_: Exception) {
                throw OpenCodeClientError(message = "The $name could not be encoded.")
            }

        private fun <T> decoded(serializer: KSerializer<T>, data: ByteArray, name: String = "server response"): T =
            try { protocolJson.decodeFromString(serializer, data.decodeToString()) } catch (_: Exception) {
                throw OpenCodeClientError(message = "The $name does not match the current OpenCode V2 contract.")
            }

        private suspend fun perform(
            path: String,
            query: List<URLQueryItem> = emptyList(),
            method: String = "GET",
            body: ByteArray? = null,
            timeout: Long = 10,
            server: OpenCodeServer,
        ): ByteArray {
            val url = server.endpoint(path, query) ?: throw OpenCodeClientError(message = "Enter a valid HTTP or HTTPS server URL.")
            val builder = Request.Builder().url(url.toHttpUrl()).header("Accept", "application/json")
            server.basicAuthorization?.let { builder.header("Authorization", it) }
            if (body != null) {
                builder.header("Content-Type", "application/json")
                builder.method(method, body.toRequestBody("application/json".toMediaType()))
            } else if (method != "GET") {
                builder.method(method, ByteArray(0).toRequestBody(null))
            }
            val callClient = httpClient.newBuilder().callTimeout(timeout, TimeUnit.SECONDS).readTimeout(timeout, TimeUnit.SECONDS).build()
            val response = execute(callClient, builder.build())
            if (response.code !in 200..299) throw httpError(response.code)
            return response.body
        }

        private class ReadResponse(val code: Int, val body: ByteArray)

        /** Runs the call and reads its whole body on the IO dispatcher: Android forbids socket reads on the main thread. */
        private suspend fun execute(client: OkHttpClient, request: Request): ReadResponse {
            val call = client.newCall(request)
            kotlin.coroutines.coroutineContext[Job]?.invokeOnCompletion { if (it is CancellationException) call.cancel() }
            return try {
                withContext(Dispatchers.IO) { call.execute().use { ReadResponse(it.code, it.body.bytes()) } }
            } catch (error: OpenCodeClientError) {
                throw error
            } catch (_: Exception) {
                throw OpenCodeClientError(message = "Couldn’t reach the OpenCode server")
            }
        }

        private fun httpError(status: Int): OpenCodeClientError = OpenCodeClientError(
            message = if (status == 401) "The server rejected these credentials." else "The server returned HTTP $status.",
            statusCode = status,
        )
    }
}

private object ByteArrayBase64Serializer : KSerializer<ByteArray> {
    override val descriptor: SerialDescriptor = String.serializer().descriptor
    override fun serialize(encoder: Encoder, value: ByteArray) = encoder.encodeString(Base64.getEncoder().encodeToString(value))
    override fun deserialize(decoder: Decoder): ByteArray = Base64.getDecoder().decode(decoder.decodeString())
}

@Serializable
private data class DataPayload<T>(val data: T)

@Serializable
private data class WrittenFilePayload(val path: String)

@Serializable
private data class TitleBody(val title: String)

@Serializable
private data class IdleBody(val idle: Long)

@Serializable
private data class DeliveryBody(val delivery: String)

@Serializable
private data class DecisionBody(val decision: OpenCodePermissionReply)

@Serializable
private data class AnswerBody(val answer: Map<String, OpenCodeFormValue>)

@Serializable
private data class SwitchBody(val model: OpenCodeModelRef)

@Serializable
private data class CreateBody(val location: Location) {
    @Serializable
    data class Location(val directory: String)
}

@Serializable
private data class PendingInputsPayload(val data: List<Item>) {
    @Serializable
    data class Item(val id: String, val type: String, val payload: Payload? = null, val delivery: String? = null) {
        @Serializable
        data class Payload(val text: String? = null, val description: String? = null, val metadata: Map<String, OpenCodeJSONValue>? = null)
    }
}

@Serializable
private data class ShellInfo(
    val id: String,
    val status: String,
    val command: String,
    val metadata: Map<String, OpenCodeJSONValue>? = null,
    val exit: Double? = null,
    val time: Time? = null,
) {
    @Serializable
    data class Time(val started: Double? = null, val completed: Double? = null)

    fun shell(isListed: Boolean): OpenCodeShell = OpenCodeShell(
        id = id,
        command = command,
        sessionID = metadata?.get("sessionID")?.string,
        isUserCommand = metadata?.get("background") == OpenCodeJSONValue.bool(true),
        isListed = isListed,
        status = runCatching { OpenCodeShell.Status.valueOf(status) }.getOrDefault(OpenCodeShell.Status.exited),
        exitCode = exit?.toInt(),
        startedAt = time?.started?.toLong(),
        completedAt = time?.completed?.toLong(),
    )
}

@Serializable
private data class EventEnvelope(val type: String, val data: Payload? = null, val _tag: String? = null) {
    @Serializable
    data class Payload(
        val sessionID: String? = null,
        val inboxID: String? = null,
        val title: String? = null,
        val form: Form? = null,
        val assistantMessageID: String? = null,
        val ordinal: Int? = null,
        val delta: String? = null,
        val text: String? = null,
        val id: String? = null,
        val name: String? = null,
        val input: OpenCodeJSONValue? = null,
        val metadata: Map<String, OpenCodeJSONValue>? = null,
        val content: List<Content>? = null,
        val error: ErrorPayload? = null,
        val info: ShellInfo? = null,
    ) {
        val textOutput: List<String> get() = content?.filter { it.type == "text" }?.mapNotNull { it.text } ?: emptyList()
        @Serializable
        data class Form(val sessionID: String)
        @Serializable
        data class Content(val type: String, val text: String? = null)
    }
}

@Serializable
private data class EventFailureFrame(val _tag: String? = null)

@Serializable
private data class StatusPayload(val version: String, val pid: Int)

@Serializable
private data class PairingSessionPayload(val token: String)

@Serializable
private data class InfoPayload(val urls: List<String>? = null, val paths: Paths? = null) {
    @Serializable
    data class Paths(val tmp: String)
}

@Serializable
private data class ProjectPayload(val id: String, val canonical: String, val name: String? = null)

@Serializable
private data class SessionPayload(
    val id: String,
    val projectID: String,
    val title: String? = null,
    val time: TimePayload,
    val location: LocationPayload,
    val model: OpenCodeModelRef? = null,
    val parentID: String? = null,
    val agent: String? = null,
    val cost: Double? = null,
)

@Serializable
private data class ModelInfoPayload(
    val id: String? = null,
    val modelID: String,
    val providerID: String,
    val name: String,
    val enabled: Boolean,
    val status: String,
    val capabilities: Capabilities? = null,
    val limit: Limit? = null,
) {
    @Serializable
    data class Capabilities(val input: List<String>? = null)
    @Serializable
    data class Limit(val context: Int? = null)
    val model: OpenCodeModel
        get() = OpenCodeModel(
            modelID = modelID,
            providerID = providerID,
            name = name,
            inputs = capabilities?.input?.toSet() ?: emptySet(),
            contextLimit = limit?.context?.takeIf { it > 0 },
            catalogID = id?.takeIf { it != modelID },
        )
}

@Serializable
private data class TimePayload(val updated: Double, val idle: Long? = null, val viewed: Long? = null)

@Serializable
private data class LocationPayload(val directory: String)

@Serializable
private data class ActiveSessionPayload(val type: String)

@Serializable
private data class MessagesPayload(val data: List<MessagePayload>, val cursor: Cursor? = null) {
    @Serializable
    data class Cursor(val next: String? = null)
}

@Serializable
private data class ContextUsagePayload(val data: List<Message>, val cursor: MessagesPayload.Cursor? = null) {
    @Serializable
    data class Message(val type: String, val status: String? = null, val model: OpenCodeModelRef? = null, val tokens: TokensPayload? = null)
}

@Serializable
private data class MessagePayload(
    val id: String,
    val type: String,
    val text: String? = null,
    val description: String? = null,
    val metadata: Map<String, OpenCodeJSONValue>? = null,
    val command: String? = null,
    val shellID: String? = null,
    val status: String? = null,
    val exit: Int? = null,
    val output: ShellOutputPayload? = null,
    val name: String? = null,
    val content: List<ContentPartPayload>? = null,
    val error: ErrorPayload? = null,
    val files: List<FilePayload>? = null,
    val summary: String? = null,
    val providerContext: OpenCodeJSONValue? = null,
    val tokens: TokensPayload? = null,
)

@Serializable
private data class TokensPayload(val input: Double, val output: Double, val reasoning: Double, val cache: Cache) {
    @Serializable
    data class Cache(val read: Double, val write: Double)
}

@Serializable
private data class ShellOutputPayload(val output: String)

@Serializable
private data class FilePayload(
    val data: String? = null,
    val mime: String? = null,
    val name: String? = null,
    val mention: OpenCodeJSONValue? = null,
    val source: Source? = null,
) {
    @Serializable
    data class Source(val type: String, val uri: String? = null)
    val isAttached: Boolean
        get() {
            if (mention != null) return false
            val source = source ?: return data != null
            return source.type == "inline" || source.uri?.startsWith("data:") == true
        }
}

@Serializable
private data class ErrorPayload(val type: String? = null, val message: String? = null)

@Serializable
private data class ContentPartPayload(
    val type: String,
    val id: String? = null,
    val text: String? = null,
    val name: String? = null,
    val state: ToolStatePayload? = null,
    val time: PartTime? = null,
) {
    @Serializable
    data class PartTime(val created: Double? = null, val ran: Double? = null)
}

@Serializable
private data class ToolStatePayload(
    val status: String? = null,
    val input: OpenCodeJSONValue? = null,
    val content: List<ToolContentPayload>? = null,
    val error: ErrorPayload? = null,
    val metadata: Map<String, OpenCodeJSONValue>? = null,
)

@Serializable
private data class ToolContentPayload(
    val type: String,
    val text: String? = null,
    val mime: String? = null,
    val uri: String? = null,
)

private val queryNameSafe = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~".toSet()
private val queryValueSafe = queryNameSafe + setOf('/', ':', '@', '!', '$', '&', '\'', '(', ')', '*', '+', ',', ';', '=')
private val pathSegmentSafe = queryValueSafe

private fun encodePath(path: String): String {
    val prefixed = if (path.startsWith("/")) path else "/$path"
    return prefixed.split("/").joinToString("/") { segment ->
        if (segment.isEmpty()) "" else percentEncode(segment, pathSegmentSafe)
    }
}

private fun percentEncode(value: String, safe: Set<Char>): String {
    val out = StringBuilder()
    for (byte in value.toByteArray(Charsets.UTF_8)) {
        val code = byte.toInt() and 0xFF
        val char = code.toChar()
        if (char in safe) out.append(char) else out.append("%${"%02X".format(code)}")
    }
    return out.toString()
}

private fun hasValidServerOrigin(components: URI): Boolean {
    val scheme = components.scheme?.lowercase() ?: return false
    if (scheme != "http" && scheme != "https") return false
    if (components.host.isNullOrEmpty()) return false
    if (components.rawUserInfo != null) return false
    if (components.rawQuery != null || components.rawFragment != null) return false
    return components.port == -1 || components.port in 1..65535
}

val OpenCodeJSONValue.`object`: Map<String, OpenCodeJSONValue>?
    get() = (this as? OpenCodeJSONValue.`object`)?.values

val OpenCodeJSONValue.string: String?
    get() = (this as? OpenCodeJSONValue.string)?.value

private fun ungrouped(value: Double): String =
    if (value == value.toLong().toDouble() && value.isFinite()) value.toLong().toString() else value.toString()

private val String.nonEmpty: String? get() = ifEmpty { null }
private val String.firstLine: String get() = lineSequence().firstOrNull() ?: this
private fun String.bounded(limit: Int): String {
    if (toByteArray(Charsets.UTF_8).size <= limit || length <= limit) return this
    return take(limit) + "\n… output truncated"
}

var DependencyValues.openCodeClient: OpenCodeClient
    get() = this[OpenCodeClient]
    set(value) {
        this[OpenCodeClient] = value
    }
