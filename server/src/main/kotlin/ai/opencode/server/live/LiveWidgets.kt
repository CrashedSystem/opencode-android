package ai.opencode.server.live

import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.internal.obj
import ai.opencode.server.internal.req
import ai.opencode.server.internal.str
import ai.opencode.server.model.OpenCodeWidgetMessageValue
import ai.opencode.server.model.OpenCodeWidgetRef
import ai.opencode.server.model.OpenCodeWidgetResponse
import ai.opencode.server.retry.RequestIdempotency

/** One immutable template version's runnable program (`prelude + "\n" + source`). */
internal suspend fun requestWidgetProgram(server: OpenCodeServer, ref: OpenCodeWidgetRef): String {
    val data = perform("/api/experimental/widget/template/${ref.name}/${ref.version}", server = server)
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
