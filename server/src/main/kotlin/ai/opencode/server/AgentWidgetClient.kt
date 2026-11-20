package ai.opencode.server

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.server.live.isWidgetRPCUnavailable
import ai.opencode.server.live.requestRespondToWidget
import ai.opencode.server.live.requestRespondToWidgetRPC
import ai.opencode.server.live.requestWidgetMessage
import ai.opencode.server.live.requestWidgetProgram
import ai.opencode.server.live.requestWidgetProgramRPC
import ai.opencode.server.live.requestWidgetSendRPC
import ai.opencode.server.live.requestWidgetStates
import ai.opencode.server.model.OpenCodeWidgetMessageValue
import ai.opencode.server.model.OpenCodeWidgetRef
import ai.opencode.server.model.OpenCodeWidgetState
import ai.opencode.server.model.OpenCodeWidgetResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Agent widgets' server half, the `opencode.widget` plugin RPC: template programs by
 * `name@version` (immutable, so kept on disk forever), the session's widget states, and the one
 * answer to and messages from an open widget. Throws [OpenCodeClientError]; `isWidgetClosed` means
 * it isn't open any more. The `legacy` calls are the REST routes of servers from before the plugin,
 * for the widgets those servers describe as `widget` messages.
 */
class AgentWidgetClient(
    val program: suspend (server: OpenCodeServer, directory: String?, ref: OpenCodeWidgetRef) -> String,
    val states: suspend (server: OpenCodeServer, directory: String?, sessionID: String) -> List<OpenCodeWidgetState>,
    val respond: suspend (server: OpenCodeServer, directory: String?, sessionID: String, messageID: String, callID: String, response: OpenCodeWidgetResponse) -> Unit,
    val send: suspend (server: OpenCodeServer, directory: String?, sessionID: String, messageID: String, callID: String, message: OpenCodeWidgetMessageValue) -> Unit,
    val legacyRespond: suspend (server: OpenCodeServer, sessionID: String, widgetMessageID: String, response: OpenCodeWidgetResponse) -> Unit,
    val legacySend: suspend (server: OpenCodeServer, sessionID: String, widgetMessageID: String, message: OpenCodeWidgetMessageValue) -> Unit,
) {
    companion object Key : DependencyKey<AgentWidgetClient> {
        override val liveValue: AgentWidgetClient by lazy {
            val cache = AgentWidgetProgramCache(File(File(dependency(AppContext).filesDir, "support"), "widget-templates"))
            AgentWidgetClient(
                program = { server, directory, ref ->
                    cache.read(ref) ?: try {
                        requestWidgetProgramRPC(server, directory, ref)
                    } catch (error: OpenCodeClientError) {
                        if (!error.isWidgetRPCUnavailable) throw error
                        requestWidgetProgram(server, ref)
                    }.also { cache.write(ref, it) }
                },
                states = { server, directory, sessionID ->
                    try {
                        requestWidgetStates(server, directory, sessionID)
                    } catch (error: OpenCodeClientError) {
                        if (!error.isWidgetRPCUnavailable) throw error
                        emptyList()
                    }
                },
                respond = { server, directory, sessionID, messageID, callID, response ->
                    requestRespondToWidgetRPC(server, directory, sessionID, messageID, callID, response)
                },
                send = ::requestWidgetSendRPC,
                legacyRespond = ::requestRespondToWidget,
                legacySend = ::requestWidgetMessage,
            )
        }

        override val testValue: AgentWidgetClient
            get() = AgentWidgetClient(
                program = { _, _, ref -> throw OpenCodeClientError("No widget template ${ref.key} under tests") },
                states = { _, _, _ -> emptyList() },
                respond = { _, _, _, _, _, _ -> throw AssertionError("Unimplemented: AgentWidgetClient.respond") },
                send = { _, _, _, _, _, _ -> throw AssertionError("Unimplemented: AgentWidgetClient.send") },
                legacyRespond = { _, _, _, _ -> throw AssertionError("Unimplemented: AgentWidgetClient.legacyRespond") },
                legacySend = { _, _, _, _ -> throw AssertionError("Unimplemented: AgentWidgetClient.legacySend") },
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
