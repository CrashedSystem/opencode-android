package ai.opencode.server

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.server.live.requestRespondToWidget
import ai.opencode.server.live.requestWidgetMessage
import ai.opencode.server.live.requestWidgetProgram
import ai.opencode.server.model.OpenCodeWidgetMessageValue
import ai.opencode.server.model.OpenCodeWidgetRef
import ai.opencode.server.model.OpenCodeWidgetResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Agent widgets' server half: template programs by `name@version` (immutable, so kept on disk
 * forever) and the one response to an open widget. Throws [OpenCodeClientError]; a 409 from
 * [respond] means it was already answered (`isWidgetAlreadyAnswered`).
 */
class AgentWidgetClient(
    val program: suspend (server: OpenCodeServer, ref: OpenCodeWidgetRef) -> String,
    val respond: suspend (server: OpenCodeServer, sessionID: String, messageID: String, response: OpenCodeWidgetResponse) -> Unit,
    /** A message from a widget that stays open (`send`); a 409 means it isn't open any more. */
    val message: suspend (server: OpenCodeServer, sessionID: String, messageID: String, message: OpenCodeWidgetMessageValue) -> Unit,
) {
    companion object Key : DependencyKey<AgentWidgetClient> {
        override val liveValue: AgentWidgetClient by lazy {
            val cache = AgentWidgetProgramCache(File(File(dependency(AppContext).filesDir, "support"), "widget-templates"))
            AgentWidgetClient(
                program = { server, ref ->
                    cache.read(ref) ?: requestWidgetProgram(server, ref).also { cache.write(ref, it) }
                },
                respond = ::requestRespondToWidget,
                message = ::requestWidgetMessage,
            )
        }

        override val testValue: AgentWidgetClient
            get() = AgentWidgetClient(
                program = { _, ref -> throw OpenCodeClientError("No widget template ${ref.key} under tests") },
                respond = { _, _, _, _ -> throw AssertionError("Unimplemented: AgentWidgetClient.respond") },
                message = { _, _, _, _ -> throw AssertionError("Unimplemented: AgentWidgetClient.message") },
            )
    }
}

/** One file per template version, written once through a temporary file so a torn write never reads back. */
class AgentWidgetProgramCache(private val directory: File) {
    suspend fun read(ref: OpenCodeWidgetRef): String? {
        if (!ref.isValid || !ref.isPinned) return null
        return withContext(Dispatchers.IO) {
            val file = file(ref)
            if (file.isFile) runCatching { file.readText() }.getOrNull() else null
        }
    }

    suspend fun write(ref: OpenCodeWidgetRef, program: String) {
        if (!ref.isValid || !ref.isPinned) return
        withContext(Dispatchers.IO) {
            runCatching {
                directory.mkdirs()
                val temporary = File(directory, "${ref.key}.tmp")
                temporary.writeText(program)
                if (!temporary.renameTo(file(ref))) temporary.delete()
            }.onFailure { AppLog.app.notice { "Widget template cache write failed: ${it.javaClass.simpleName}" } }
        }
    }

    private fun file(ref: OpenCodeWidgetRef) = File(directory, "${ref.key}.js")
}
