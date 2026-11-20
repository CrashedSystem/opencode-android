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
import ai.opencode.server.model.OpenCodeWidgetStatus
import ai.opencode.server.model.OpenCodeWidgetProgram
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

/** A `Program` (`Summary` + `program`): its program text, mode and https hosts. */
internal fun decodeWidgetProgram(element: JsonElement?): OpenCodeWidgetProgram {
    val o = element as? JsonObject ?: throw OpenCodeClientError("The widget template has no program.")
    val program = (o["program"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw OpenCodeClientError("The widget template has no program.")
    val mode = (o["mode"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val hosts = ((o["capabilities"] as? JsonObject)?.get("https") as? JsonObject)?.get("hosts") as? JsonArray
    return OpenCodeWidgetProgram(program, mode == "live", hosts?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content })
}

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
internal suspend fun requestWidgetProgramRPC(server: OpenCodeServer, directory: String?, ref: OpenCodeWidgetRef): OpenCodeWidgetProgram {
    val input = buildJsonObject {
        put("name", ref.name)
        if (ref.isPinned) put("version", ref.version)
    }
    return decodeWidgetProgram(requestWidgetRPC(server, "program", directory, input, RequestIdempotency.Idempotent))
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
    val status = OpenCodeWidgetStatus.of(str("status")) ?: return null
    val hosts = ((o["capabilities"] as? JsonObject)?.get("https") as? JsonObject)?.get("hosts") as? JsonArray
    return OpenCodeWidgetState(
        sessionID = str("sessionID") ?: return null,
        messageID = str("messageID") ?: return null,
        callID = str("callID") ?: return null,
        template = ref,
        title = str("title"),
        status = status,
        response = if (status == OpenCodeWidgetStatus.Submitted) (o["response"] ?: JsonNull).toString() else null,
        wait = (o["wait"] as? JsonPrimitive)?.booleanOrNull ?: false,
        httpsHosts = hosts?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content },
    )
}

/** A plugin RPC error's type: the widget isn't open any more. */
val OpenCodeClientError.isWidgetClosed: Boolean get() = rpcErrorType == "closed"

/** The plugin isn't loaded in the location yet (a fresh server, until something boots it): try again shortly. */
val OpenCodeClientError.isWidgetRPCUnavailable: Boolean get() = rpcErrorType == "rpc.unavailable"
