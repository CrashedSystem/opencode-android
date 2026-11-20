package ai.opencode.feature.transcript.widgets

import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.server.AgentWidgetClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.live.isWidgetAlreadyAnswered
import ai.opencode.server.model.OpenCodeJSONValue
import ai.opencode.server.model.OpenCodeToolCall
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.OpenCodeWidgetMessage
import ai.opencode.server.model.OpenCodeWidgetMessageValue
import ai.opencode.server.model.OpenCodeWidgetRef
import ai.opencode.server.model.OpenCodeWidgetResponse
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf

/**
 * One widget as the transcript shows it, keyed by the `widget` tool call that posted it. [handle]
 * is what the server answers it by; [data] and [response] are JSON text.
 */
@Immutable
data class AgentWidgetState(
    val callID: String,
    val title: String?,
    val template: OpenCodeWidgetRef,
    val data: String,
    val status: Status,
    val response: String? = null,
    val handle: String,
) {
    enum class Status { Open, Submitted, Dismissed }

    /** The title, else the template's name. */
    val label: String get() = title?.trim()?.ifEmpty { null } ?: template.name
}

/**
 * The seam between widgets and the server: where widget states come from and how a widget's
 * program, answer and messages travel. Everything else (runtime, machine, renderer) sees only
 * [AgentWidgetState] and [AgentWidgetTransport].
 */
object AgentWidgetAdapter {
    const val TOOL = "widget"

    fun isWidget(call: OpenCodeToolCall): Boolean = call.name == TOOL

    /** The title the tool call asked for, shown while its widget loads. */
    fun title(call: OpenCodeToolCall): String? =
        call.input.obj?.get("title")?.string?.trim()?.ifEmpty { null } ?: call.input.obj?.get("template")?.string

    /**
     * The session's widgets by tool call ID, from its loaded transcript: a `widget` tool call's
     * `metadata.widget` (`{ template, status, response? }`, answered by its call ID), else the
     * `widget` message that names the call.
     */
    fun states(items: List<OpenCodeTranscriptItem>): Map<String, AgentWidgetState> {
        var states: HashMap<String, AgentWidgetState>? = null
        fun put(state: AgentWidgetState) {
            (states ?: HashMap<String, AgentWidgetState>().also { states = it })[state.callID] = state
        }
        for (item in items) {
            when (item) {
                is OpenCodeTranscriptItem.Widget -> {
                    val message = item.widget
                    val callID = message.callID ?: continue
                    if (states?.containsKey(callID) == true) continue
                    put(
                        AgentWidgetState(
                            callID = callID,
                            title = message.title,
                            template = message.template,
                            data = message.data,
                            status = when (message.status) {
                                OpenCodeWidgetMessage.Status.Open -> AgentWidgetState.Status.Open
                                OpenCodeWidgetMessage.Status.Submitted -> AgentWidgetState.Status.Submitted
                                OpenCodeWidgetMessage.Status.Dismissed -> AgentWidgetState.Status.Dismissed
                            },
                            response = message.response,
                            handle = message.messageID,
                        ),
                    )
                }
                is OpenCodeTranscriptItem.Tool -> fromToolCall(item.call)?.let(::put)
                else -> Unit
            }
        }
        return states ?: emptyMap()
    }

    private fun fromToolCall(call: OpenCodeToolCall): AgentWidgetState? {
        if (!isWidget(call)) return null
        val meta = call.metadata["widget"]?.obj ?: return null
        val template = meta["template"]?.obj ?: return null
        val ref = OpenCodeWidgetRef(template["name"]?.string ?: return null, template["version"]?.string ?: return null)
        if (!ref.isValid) return null
        val status = when (meta["status"]?.string) {
            "open" -> AgentWidgetState.Status.Open
            "submitted" -> AgentWidgetState.Status.Submitted
            "dismissed" -> AgentWidgetState.Status.Dismissed
            else -> return null
        }
        val input = call.input.obj
        return AgentWidgetState(
            callID = call.callID,
            title = input?.get("title")?.string,
            template = ref,
            data = input?.get("data")?.let { OpenCodeJSONValue.toElement(it).toString() } ?: "null",
            status = status,
            response = if (status == AgentWidgetState.Status.Submitted) meta["response"]?.let { OpenCodeJSONValue.toElement(it).toString() } ?: "null" else null,
            handle = call.callID,
        )
    }
}

/** Fetches template programs and answers widgets; throws [AgentWidgetTransportException]. */
class AgentWidgetTransport(
    val program: suspend (server: OpenCodeServer, ref: OpenCodeWidgetRef) -> String,
    val respond: suspend (server: OpenCodeServer, sessionID: String, widget: AgentWidgetState, response: OpenCodeWidgetResponse) -> Unit,
    /** A message from a widget that stays open (`send`). */
    val send: suspend (server: OpenCodeServer, sessionID: String, widget: AgentWidgetState, message: OpenCodeWidgetMessageValue) -> Unit,
) {
    companion object Key : DependencyKey<AgentWidgetTransport> {
        override val liveValue: AgentWidgetTransport by lazy {
            val client = dependency(AgentWidgetClient)
            AgentWidgetTransport(
                program = { server, ref -> mapped { client.program(server, ref) } },
                respond = { server, sessionID, widget, response -> mapped { client.respond(server, sessionID, widget.handle, response) } },
                send = { server, sessionID, widget, message -> mapped { client.message(server, sessionID, widget.handle, message) } },
            )
        }

        override val testValue: AgentWidgetTransport
            get() = AgentWidgetTransport(
                program = { _, ref -> throw AgentWidgetTransportException("No widget template ${ref.key} under tests") },
                respond = { _, _, _, _ -> throw AssertionError("Unimplemented: AgentWidgetTransport.respond") },
                send = { _, _, _, _ -> throw AssertionError("Unimplemented: AgentWidgetTransport.send") },
            )

        private inline fun <T> mapped(block: () -> T): T = try {
            block()
        } catch (error: OpenCodeClientError) {
            throw AgentWidgetTransportException(error.message, isClosed = error.isWidgetAlreadyAnswered)
        }
    }
}

/** [isClosed]: the widget was already answered (here or on another client). */
class AgentWidgetTransportException(message: String, val isClosed: Boolean = false) : Exception(message)

/** The list's widget states by tool call ID. */
val LocalAgentWidgetStates = compositionLocalOf<Map<String, AgentWidgetState>> { emptyMap() }
