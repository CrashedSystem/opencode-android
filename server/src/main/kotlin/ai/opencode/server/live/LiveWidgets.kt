package ai.opencode.server.live

import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.internal.obj
import ai.opencode.server.internal.req
import ai.opencode.server.internal.str
import ai.opencode.server.model.OpenCodeWidgetMessageValue
import ai.opencode.server.model.OpenCodeWidgetRef
import ai.opencode.server.model.OpenCodeWidgetResponse
import ai.opencode.server.internal.WireJson
import ai.opencode.server.model.OpenCodeWidgetMessage
import ai.opencode.server.model.OpenCodeWidgetState
import ai.opencode.server.retry.RequestIdempotency
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One immutable template version's runnable program (`prelude + "\n" + source`). */
internal suspend fun requestWidgetProgram(server: OpenCodeServer, ref: OpenCodeWidgetRef): String {
    val path = if (ref.isPinned) "${ref.name}/${ref.version}" else ref.name
    val data = perform("/api/experimental/widget/template/$path", server = server)
    return decodeWidgetProgram(data.data)
}

internal fun decodeWidgetProgram(data: ByteArray): String =
    decoded(data, "widget template") { it.obj().req("data") { d -> d.obj().req("program") { p -> p.str() } } }

/** Answers an open widget once; a second answer, from here or another client, is a 409. */
internal suspend fun requestRespondToWidget(server: OpenCodeServer, sessionID: String, messageID: String, response: OpenCodeWidgetResponse) {
    perform(
        "/api/experimental/session/$sessionID/widget/$messageID/respond",
        method = "POST",
        body = response.body.toByteArray(),
        idempotency = RequestIdempotency.Idempotent,
        server = server,
    )
}

/** Sends the agent a message from an open widget; a 409 means it isn't open any more. */
internal suspend fun requestWidgetMessage(server: OpenCodeServer, sessionID: String, messageID: String, message: OpenCodeWidgetMessageValue) {
    perform(
        "/api/experimental/session/$sessionID/widget/$messageID/message",
        method = "POST",
        body = message.body.toByteArray(),
        server = server,
    )
}

/** The respond endpoint's conflict: the widget was already answered. */
val OpenCodeClientError.isWidgetAlreadyAnswered: Boolean get() = statusCode == 409

/** `POST /api/rpc/opencode.widget/<method>` in [directory]'s location: `{ input }` in, `output` back. */
internal suspend fun requestWidgetRPC(
    server: OpenCodeServer,
    method: String,
    directory: String?,
    input: JsonObject,
    idempotency: RequestIdempotency? = null,
): JsonElement {
    val body = buildJsonObject { put("input", input) }.toString().toByteArray()
    val data = perform(
        "/api/rpc/opencode.widget/$method",
        query = locationQuery(directory),
        method = "POST",
        body = body,
        idempotency = idempotency,
        server = server,
    )
    return decoded(data.data, "widget $method") { (it.obj()["output"] ?: JsonNull) }
}

/** A template version's program (the latest without a version) from the widget plugin. */
internal suspend fun requestWidgetProgramRPC(server: OpenCodeServer, directory: String?, ref: OpenCodeWidgetRef): String {
    val input = buildJsonObject {
        put("name", ref.name)
        if (ref.isPinned) put("version", ref.version)
    }
    val output = requestWidgetRPC(server, "program", directory, input, RequestIdempotency.Idempotent)
    return (output as? JsonObject)?.get("program")?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        ?: throw OpenCodeClientError("The widget template ${ref.key} has no program.")
}

/** The session's widget states, oldest first; a record this client can't read is skipped. */
internal suspend fun requestWidgetStates(server: OpenCodeServer, directory: String?, sessionID: String): List<OpenCodeWidgetState> {
    val output = requestWidgetRPC(server, "states", directory, buildJsonObject { put("sessionID", sessionID) }, RequestIdempotency.Idempotent)
    return (output as? JsonArray)?.mapNotNull(::decodeWidgetState) ?: emptyList()
}

/** Answers an open widget once; `closed` means it was answered already. */
internal suspend fun requestRespondToWidgetRPC(
    server: OpenCodeServer,
    directory: String?,
    sessionID: String,
    messageID: String,
    callID: String,
    response: OpenCodeWidgetResponse,
): OpenCodeWidgetState? {
    val input = buildJsonObject {
        put("sessionID", sessionID)
        put("messageID", messageID)
        put("callID", callID)
        put("response", WireJson.parseToJsonElement(response.body))
    }
    return decodeWidgetState(requestWidgetRPC(server, "respond", directory, input, RequestIdempotency.Idempotent))
}

/** A message from an open widget; `closed` (not open) and `rate_limited` (over 10 a second) fail. */
internal suspend fun requestWidgetSendRPC(
    server: OpenCodeServer,
    directory: String?,
    sessionID: String,
    messageID: String,
    callID: String,
    message: OpenCodeWidgetMessageValue,
) {
    val input = buildJsonObject {
        put("sessionID", sessionID)
        put("messageID", messageID)
        put("callID", callID)
        put("value", WireJson.parseToJsonElement(message.json))
        put("wake", message.wake)
    }
    requestWidgetRPC(server, "send", directory, input)
}

/** A plugin `State`, or null when it isn't one this client reads. */
internal fun decodeWidgetState(element: JsonElement?): OpenCodeWidgetState? {
    val o = element as? JsonObject ?: return null
    fun str(key: String, from: JsonObject = o) = (from[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val template = o["template"] as? JsonObject ?: return null
    val ref = OpenCodeWidgetRef(str("name", template) ?: return null, str("version", template) ?: return null)
    if (!ref.isValid || !ref.isPinned) return null
    val status = OpenCodeWidgetMessage.Status.of(str("status")) ?: return null
    val hosts = ((o["capabilities"] as? JsonObject)?.get("https") as? JsonObject)?.get("hosts") as? JsonArray
    return OpenCodeWidgetState(
        sessionID = str("sessionID") ?: return null,
        messageID = str("messageID") ?: return null,
        callID = str("callID") ?: return null,
        template = ref,
        title = str("title"),
        status = status,
        response = if (status == OpenCodeWidgetMessage.Status.Submitted) (o["response"] ?: JsonNull).toString() else null,
        wait = (o["wait"] as? JsonPrimitive)?.booleanOrNull ?: false,
        httpsHosts = hosts?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content },
    )
}

/** A plugin RPC error's type: the widget isn't open any more. */
val OpenCodeClientError.isWidgetClosed: Boolean get() = rpcErrorType == "closed" || statusCode == 409

/** The server has no widget plugin (an older server): fall back to its REST routes. */
val OpenCodeClientError.isWidgetRPCUnavailable: Boolean get() = rpcErrorType == "rpc.unavailable"
