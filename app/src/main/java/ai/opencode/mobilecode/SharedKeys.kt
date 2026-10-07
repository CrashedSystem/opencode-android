@file:PortedFrom("OpenCode/SharedKeys.swift", "2e313a1")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.ProcessInfo
import ai.opencode.mobilecode.tca.CoalescedWriter
import ai.opencode.mobilecode.tca.DependencyKey
import ai.opencode.mobilecode.tca.DependencyValues
import ai.opencode.mobilecode.tca.Shared
import ai.opencode.mobilecode.tca.SharedKey
import ai.opencode.mobilecode.tca.StorageDirectory
import ai.opencode.mobilecode.tca.TranscriptCacheDirectory
import ai.opencode.mobilecode.tca.appStorage
import ai.opencode.mobilecode.tca.fileStorage
import ai.opencode.mobilecode.tca.inMemory
import ai.opencode.mobilecode.tca.keychain
import ai.opencode.mobilecode.tca.protectedTranscriptCache
import ai.opencode.mobilecode.tca.withLock
import java.io.File
import java.util.UUID
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Serializable
data class CachedHomeSnapshot(
    var serverID: String,
    var snapshot: OpenCodeHomeSnapshot,
)

@Serializable
data class CachedTranscriptSnapshots(
    var version: Int = currentVersion,
    var entries: List<Entry> = emptyList(),
) {
    @Serializable
    data class Entry(
        var serverID: String,
        var sessionID: String,
        var updatedAt: Long,
        @Serializable(with = TranscriptItemListSerializer::class)
        var items: List<OpenCodeTranscriptItem> = emptyList(),
    )

    fun replaceEntries(serverID: String, replacements: List<Entry>): CachedTranscriptSnapshots {
        if (version != currentVersion) return CachedTranscriptSnapshots().replaceEntries(serverID, replacements)
        val kept = entries.filterNot { it.serverID == serverID } + replacements
        return copy(entries = kept.sortedByDescending { it.updatedAt }.take(maximumEntries))
    }

    /// Encodes each entry once and splices the retained ones into the empty
    /// envelope's `entries` array, so a multi-megabyte snapshot is never
    /// re-encoded while enforcing the on-disk cap.
    fun encodedForPersistence(): String? {
        val empty = transcriptCacheJson.encodeToString(serializer(), CachedTranscriptSnapshots(version = version, entries = emptyList()))
        val marker = "\"entries\":[]"
        val markerAt = empty.indexOf(marker)
        if (markerAt < 0) return null
        val insertion = markerAt + marker.length - 1
        var bytes = empty.toByteArray(Charsets.UTF_8).size
        val retained = mutableListOf<ByteArray>()
        for (entry in entries.take(maximumEntries)) {
            val encodedEntry = runCatching {
                transcriptCacheJson.encodeToString(Entry.serializer(), entry).toByteArray(Charsets.UTF_8)
            }.getOrNull() ?: continue
            val separator = if (retained.isEmpty()) 0 else 1
            if (bytes + encodedEntry.size + separator > maximumBytes) break
            retained += encodedEntry
            bytes += encodedEntry.size + separator
        }
        val prefix = empty.substring(0, insertion).toByteArray(Charsets.UTF_8)
        val suffix = empty.substring(insertion).toByteArray(Charsets.UTF_8)
        val envelope = ByteArray(prefix.size + retained.sumOf { it.size } + (retained.size - 1).coerceAtLeast(0) + suffix.size)
        var cursor = 0
        prefix.copyInto(envelope, cursor)
        cursor += prefix.size
        retained.forEachIndexed { index, entry ->
            if (index > 0) envelope[cursor++] = ','.code.toByte()
            entry.copyInto(envelope, cursor)
            cursor += entry.size
        }
        suffix.copyInto(envelope, cursor)
        return envelope.toString(Charsets.UTF_8)
    }

    fun removeEntry(serverID: String, sessionID: String): CachedTranscriptSnapshots =
        copy(entries = entries.filterNot { it.serverID == serverID && it.sessionID == sessionID })

    fun validItems(serverID: String, sessions: List<OpenCodeSession>): Map<String, List<OpenCodeTranscriptItem>> {
        if (version != currentVersion) return emptyMap()
        val updated = sessions.associate { it.id to it.updatedAt }
        return entries.mapNotNull { entry ->
            if (entry.serverID != serverID || updated[entry.sessionID] != entry.updatedAt) return@mapNotNull null
            entry.sessionID to entry.items
        }.toMap()
    }

    companion object {
        const val currentVersion = 1
        const val maximumBytes = 4_000_000
        const val maximumEntries = 16
    }
}

val SharedKey.Companion.cachedTranscriptSnapshots: SharedKey<CachedTranscriptSnapshots>
    get() {
        val persistence = DependencyValues.current.transcriptCachePersistence
        return SharedKey.protectedTranscriptCache(
            identity = persistence.identity,
            default = CachedTranscriptSnapshots(),
            serializer = CachedTranscriptSnapshots.serializer(),
            load = persistence.load,
            save = persistence.schedule,
        )
    }

class TranscriptCachePersistence(
    val identity: String,
    val load: () -> CachedTranscriptSnapshots?,
    val schedule: (CachedTranscriptSnapshots) -> Unit,
    val flush: suspend () -> Unit,
    val remove: () -> Unit,
) {
    companion object : DependencyKey<TranscriptCachePersistence> {
        override val liveValue: TranscriptCachePersistence
            get() = make(ProtectedFileStorage.live(TranscriptCacheDirectory.file()))

        override val testValue: TranscriptCachePersistence
            get() = if (ProcessInfo.processInfo.environment["OPENCODE_LAB_MARKS_URL"] == null) {
                make(ProtectedFileStorage.inMemory())
            } else {
                liveValue
            }

        fun make(storage: ProtectedFileStorage): TranscriptCachePersistence {
            val writer = CoalescedWriter.shared(storage.identity) { encoded ->
                if (encoded == null) storage.remove() else storage.set(encoded.toByteArray(Charsets.UTF_8))
            }
            return TranscriptCachePersistence(
                identity = storage.identity,
                load = {
                    if (ProcessInfo.processInfo.environment["OPENCODE_LAB_RESET_TRANSCRIPT_CACHE"] == "1") storage.remove()
                    storage.data()?.let { bytes ->
                        runCatching {
                            transcriptCacheJson.decodeFromString(CachedTranscriptSnapshots.serializer(), bytes.toString(Charsets.UTF_8))
                        }.getOrNull()
                    }
                },
                schedule = { snapshot -> writer.schedule(snapshot.encodedForPersistence()) },
                flush = { writer.flush() },
                remove = { storage.remove() },
            )
        }
    }
}

var DependencyValues.transcriptCachePersistence: TranscriptCachePersistence
    get() = this[TranscriptCachePersistence]
    set(value) {
        this[TranscriptCachePersistence] = value
    }

class ProtectedFileStorage(
    val identity: String,
    private val file: File?,
    private val memory: Memory?,
) {
    fun data(): ByteArray? {
        memory?.let { return it.value }
        val target = file ?: return null
        return target.takeIf(File::exists)?.readBytes()
    }

    fun set(data: ByteArray) {
        memory?.let {
            it.value = data
            return
        }
        val target = file ?: return
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeBytes(data)
        check(temporary.renameTo(target)) { "Couldn't replace $target" }
    }

    fun remove() {
        memory?.let {
            it.value = null
            return
        }
        file?.delete()
    }

    override fun equals(other: Any?): Boolean = other is ProtectedFileStorage && identity == other.identity

    override fun hashCode(): Int = identity.hashCode()

    class Memory {
        @Volatile
        var value: ByteArray? = null
    }

    companion object {
        fun live(file: File) = ProtectedFileStorage(file.absolutePath, file, null)

        fun inMemory() = ProtectedFileStorage(UUID.randomUUID().toString(), null, Memory())
    }
}

// swift-sharing persistence keys. `@Shared` replaces the hand-rolled
// UserDefaults clients: state reads stay synchronous, writes persist
// automatically, and tests get isolated in-memory storage for free.
//
// Anything carrying a password uses `.keychain` (see Keychain.swift);
// only non-secret preferences use file storage.

/// The last successfully connected server, restored on launch.
val SharedKey.Companion.savedServer: SharedKey<OpenCodeServer?>
    get() = SharedKey.keychain("saved-server", default = null)

/// Every server you've connected to, most recent first, so switching between
/// a laptop and a tailnet box doesn't mean retyping credentials.
val SharedKey.Companion.savedServers: SharedKey<List<OpenCodeServer>>
    get() = SharedKey.keychain("saved-servers", default = emptyList())

/// Saves this as the last-used server at the top of the list. Unchanged
/// values are not rewritten, so routine refreshes leave the keychain alone.
/// An edited address no longer shares an ID with its old saved entry; replace
/// that entry explicitly after the new connection has loaded.
fun OpenCodeServer.remember(replacing: OpenCodeServer? = null) {
    val server = this
    var savedServer by Shared(SharedKey.savedServer)
    var savedServers by Shared(SharedKey.savedServers)
    if (savedServer != server) SharedKey.savedServer.withLock { value = server }
    if (savedServers.firstOrNull() == server && replacing == null) return
    SharedKey.savedServers.withLock {
        value = listOf(server) + value.filterNot { server.supersedes(it) || it.persistenceID == replacing?.persistenceID }
    }
}

/// Gates experimental Voice Mode inside Settings → Advanced. Off by default.
val SharedKey.Companion.voiceModeEnabled: SharedKey<Boolean>
    get() = SharedKey.appStorage("voice-mode-enabled", default = false)

/// Developer Mode: upload voice calls and traces to the log server. Off by
/// default. Keeps the key the voice-only switch used.
val SharedKey.Companion.developerMode: SharedKey<Boolean>
    get() = SharedKey.appStorage("voice-call-uploads", default = false)

/// Raise to Talk: raising the phone to your ear on a session starts a call
/// that plays from the earpiece. Off by default.
val SharedKey.Companion.raiseToTalk: SharedKey<Boolean>
    get() = SharedKey.appStorage("raise-to-talk", default = false)

/// Voice engine for new calls. Defaults to GPT-Live, including for users
/// upgrading from builds that only had Realtime.
val SharedKey.Companion.voiceEngine: SharedKey<VoiceEngine>
    get() = SharedKey.appStorage("voice-engine", default = VoiceEngine.gptLive)

/// Your own OpenAI project key for voice calls; never sent to the OpenCode
/// server. Empty/nil disables Voice.
val SharedKey.Companion.openAIAPIKey: SharedKey<String?>
    get() = SharedKey.keychain("openai-api-key", default = null)

/// The random key that owns this iPhone's uploaded voice calls; whoever
/// has it can view them. Made on first upload.
val SharedKey.Companion.developerModeOwner: SharedKey<String?>
    get() = SharedKey.keychain("voice-call-upload-owner", default = null)

/// Phone-local voice transcripts keyed by `serverID:sessionID`.
val SharedKey.Companion.voiceTranscripts: SharedKey<Map<String, VoiceTranscriptRecord>>
    get() = SharedKey.fileStorage(StorageDirectory.documents.appending(component = "voice-transcripts.json"), default = emptyMap())

val SharedKey.Companion.cachedHomeSnapshot: SharedKey<CachedHomeSnapshot?>
    get() = SharedKey.fileStorage(StorageDirectory.caches.appending(component = "home-snapshot.json"), default = null)

/// SHA-256 fingerprint of the last OpenAI key accepted by the model
/// preflight. This is not a credential and only avoids redundant checks.
val SharedKey.Companion.verifiedOpenAIKeyFingerprint: SharedKey<String?>
    get() = SharedKey.fileStorage(StorageDirectory.caches.appending(component = "verified-openai-key.json"), default = null)

/// Starred models pinned to the top of the model picker.
val SharedKey.Companion.favoriteModelIDs: SharedKey<Set<String>>
    get() = SharedKey.fileStorage(StorageDirectory.documents.appending(component = "favorite-models.json"), default = emptySet())

val SharedKey.Companion.manuallyUnreadSessionKeys: SharedKey<Set<String>>
    get() = SharedKey.fileStorage(StorageDirectory.documents.appending(component = "manually-unread-sessions.json"), default = emptySet())

val SharedKey.Companion.initializedInboxServerIDs: SharedKey<Set<String>>
    get() = SharedKey.fileStorage(StorageDirectory.documents.appending(component = "initialized-inbox-servers.json"), default = emptySet())

/// Servers (by persistence ID) whose upload route is missing this launch.
/// In memory only, so an updated server gets another chance next launch.
val SharedKey.Companion.uploadUnsupportedServerIDs: SharedKey<Set<String>>
    get() = SharedKey.inMemory("upload-unsupported-servers", default = emptySet())

val SharedKey.Companion.sessionReadDates: SharedKey<Map<String, Long>>
    get() = SharedKey.fileStorage(StorageDirectory.documents.appending(component = "session-read-dates.json"), default = emptyMap())

/// Last root chat opened on each server. The transcript cache provides its
/// first frame while the server snapshot revalidates in the background.
val SharedKey.Companion.lastViewedSessionIDs: SharedKey<Map<String, String>>
    get() = SharedKey.fileStorage(StorageDirectory.documents.appending(component = "last-viewed-sessions.json"), default = emptyMap())

val SharedKey.Companion.pinnedSessionKeys: SharedKey<List<String>>
    get() = SharedKey.fileStorage(
        // Keep the existing file so previously followed chats become pins on upgrade.
        StorageDirectory.documents.appending(component = "followed-sessions.json"),
        default = emptyList(),
    )

internal val transcriptCacheJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

private object TranscriptItemListSerializer : KSerializer<List<OpenCodeTranscriptItem>> {
    override val descriptor: SerialDescriptor = ListSerializer(JsonElement.serializer()).descriptor

    override fun serialize(encoder: Encoder, value: List<OpenCodeTranscriptItem>) {
        val json = (encoder as JsonEncoder).json
        encoder.encodeJsonElement(JsonArray(value.map { encodeItem(it, json) }))
    }

    override fun deserialize(decoder: Decoder): List<OpenCodeTranscriptItem> {
        val json = (decoder as JsonDecoder).json
        val array = decoder.decodeJsonElement() as JsonArray
        return array.map { decodeItem(it, json) }
    }
}

private fun encodeItem(item: OpenCodeTranscriptItem, json: Json): JsonObject {
    val (key, body) = when (item) {
        is OpenCodeTranscriptItem.userText -> "userText" to buildJsonObject {
            put("id", item.id)
            put("text", item.text)
        }
        is OpenCodeTranscriptItem.userImages -> "userImages" to buildJsonObject {
            put("id", item.id)
            put("images", json.encodeToJsonElement(ListSerializer(OpenCodeImageAttachment.serializer()), item.images))
        }
        is OpenCodeTranscriptItem.userFiles -> "userFiles" to buildJsonObject {
            put("id", item.id)
            put("files", json.encodeToJsonElement(ListSerializer(OpenCodeTranscriptFile.serializer()), item.files))
        }
        is OpenCodeTranscriptItem.assistantMarkdown -> "assistantMarkdown" to buildJsonObject {
            put("id", item.id)
            put("blocks", json.encodeToJsonElement(ListSerializer(OpenCodeMarkdownBlock.serializer()), item.blocks))
        }
        is OpenCodeTranscriptItem.tool -> "tool" to buildJsonObject {
            put("id", item.id)
            put("call", json.encodeToJsonElement(OpenCodeToolCall.serializer(), item.call))
        }
        is OpenCodeTranscriptItem.compaction -> "compaction" to buildJsonObject {
            put("id", item.id)
            item.info?.let { put("info", encodeCompaction(it, json)) }
        }
        is OpenCodeTranscriptItem.info -> "info" to buildJsonObject {
            put("id", item.id)
            put("text", item.text)
        }
        is OpenCodeTranscriptItem.notice -> "notice" to buildJsonObject {
            put("id", item.id)
            put("text", item.text)
            put("isError", item.isError)
        }
        is OpenCodeTranscriptItem.sendFailure -> "sendFailure" to buildJsonObject {
            put("id", item.id)
            put("message", item.message)
        }
    }
    return JsonObject(mapOf(key to body))
}

private fun decodeItem(element: JsonElement, json: Json): OpenCodeTranscriptItem {
    val (key, value) = element.jsonObject.entries.first()
    val body = value.jsonObject
    val id = body.getValue("id").jsonPrimitive.content
    return when (key) {
        "userText" -> OpenCodeTranscriptItem.userText(id, body.getValue("text").jsonPrimitive.content)
        "userImages" -> OpenCodeTranscriptItem.userImages(
            id,
            json.decodeFromJsonElement(ListSerializer(OpenCodeImageAttachment.serializer()), body.getValue("images")),
        )
        "userFiles" -> OpenCodeTranscriptItem.userFiles(
            id,
            json.decodeFromJsonElement(ListSerializer(OpenCodeTranscriptFile.serializer()), body.getValue("files")),
        )
        "assistantMarkdown" -> OpenCodeTranscriptItem.assistantMarkdown(
            id,
            json.decodeFromJsonElement(ListSerializer(OpenCodeMarkdownBlock.serializer()), body.getValue("blocks")),
        )
        "tool" -> OpenCodeTranscriptItem.tool(
            id,
            json.decodeFromJsonElement(OpenCodeToolCall.serializer(), body.getValue("call")),
        )
        "compaction" -> OpenCodeTranscriptItem.compaction(
            id,
            body["info"]?.let { decodeCompaction(it.jsonObject, json) },
        )
        "info" -> OpenCodeTranscriptItem.info(id, body.getValue("text").jsonPrimitive.content)
        "notice" -> OpenCodeTranscriptItem.notice(
            id,
            body.getValue("text").jsonPrimitive.content,
            body["isError"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
        "sendFailure" -> OpenCodeTranscriptItem.sendFailure(id, body.getValue("message").jsonPrimitive.content)
        else -> error("unsupported transcript item")
    }
}

private fun encodeCompaction(info: OpenCodeCompaction, json: Json): JsonObject = buildJsonObject {
    put("status", info.status.name)
    put("isProvider", info.isProvider)
    put("summary", json.encodeToJsonElement(ListSerializer(OpenCodeMarkdownBlock.serializer()), info.summary))
    info.errorMessage?.let { put("errorMessage", it) }
    info.inputTokens?.let { put("inputTokens", it) }
    info.outputTokens?.let { put("outputTokens", it) }
}

private fun decodeCompaction(body: JsonObject, json: Json): OpenCodeCompaction = OpenCodeCompaction(
    status = OpenCodeCompaction.Status.valueOf(body.getValue("status").jsonPrimitive.content),
    isProvider = body["isProvider"]?.jsonPrimitive?.booleanOrNull ?: false,
    summary = body["summary"]?.let {
        json.decodeFromJsonElement(ListSerializer(OpenCodeMarkdownBlock.serializer()), it)
    } ?: emptyList(),
    errorMessage = body["errorMessage"]?.jsonPrimitive?.contentOrNull,
    inputTokens = body["inputTokens"]?.jsonPrimitive?.intOrNull,
    outputTokens = body["outputTokens"]?.jsonPrimitive?.intOrNull,
)
