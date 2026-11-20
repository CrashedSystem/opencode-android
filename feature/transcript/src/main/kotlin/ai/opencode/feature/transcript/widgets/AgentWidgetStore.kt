package ai.opencode.feature.transcript.widgets

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.core.strings.localized
import ai.opencode.feature.transcript.R
import ai.opencode.feature.transcript.widgets.AgentWidgetMachine.Effect
import ai.opencode.feature.transcript.widgets.AgentWidgetMachine.Event
import ai.opencode.feature.transcript.widgets.AgentWidgetMachine.Phase
import ai.opencode.feature.transcript.widgets.AgentWidgetMachine.RespondFailure
import ai.opencode.feature.transcript.widgets.AgentWidgetMachine.Status
import ai.opencode.server.AgentWidgetClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.live.isWidgetAlreadyAnswered
import ai.opencode.server.model.OpenCodeWidgetMessage
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The transcript list's widgets: one [AgentWidgetController] per widget message, kept while the
 * list is, so a widget scrolled away and back keeps its local state.
 */
class AgentWidgetStore(
    private val programs: AgentWidgetPrograms = dependency(AgentWidgetPrograms),
    private val client: AgentWidgetClient = dependency(AgentWidgetClient),
    private val engine: AgentWidgetEngine = dependency(AgentWidgetEngine),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controllers = HashMap<String, AgentWidgetController>()

    fun controller(message: OpenCodeWidgetMessage, server: OpenCodeServer, sessionID: String): AgentWidgetController {
        val key = "${server.persistenceID}/$sessionID/${message.messageID}"
        return controllers.getOrPut(key) { AgentWidgetController(message, server, sessionID, scope, programs, client, engine) }
    }

    fun close() {
        scope.cancel()
        for (controller in controllers.values) controller.close()
        controllers.clear()
    }
}

val LocalAgentWidgetStore = staticCompositionLocalOf<AgentWidgetStore?> { null }

/** True while the list isn't on screen (its lifecycle is below resumed): shaders stop rendering. */
val LocalAgentWidgetPaused = androidx.compose.runtime.compositionLocalOf { false }

/** Runs one widget's machine: fetches and compiles its template, steps it off the main thread, and answers it once. */
@Stable
class AgentWidgetController internal constructor(
    message: OpenCodeWidgetMessage,
    private val server: OpenCodeServer,
    private val sessionID: String,
    private val scope: CoroutineScope,
    private val programs: AgentWidgetPrograms,
    private val client: AgentWidgetClient,
    val engine: AgentWidgetEngine,
) {
    val messageID: String = message.messageID
    val data: String = message.data
    private val ref = message.template
    private var machine: AgentWidgetMachine.Machine
    private var pendingEffects: List<Effect>
    private var started = false
    private var program: AgentWidgetProgram? = null
    private var programText: String? = null
    /** An animating canvas's own program, so its frames never wait on other widgets' steps. */
    private var drawProgram: AgentWidgetProgram? = null
    private val lane = AgentWidgetLane.make()
    private val queued = ArrayDeque<AgentWidgetEvent>()

    var phase: Phase by mutableStateOf(Phase.Loading(status(message)))
        private set

    /** The last event or init step's time on the lane, for the perf log. */
    @Volatile var lastStepMicros: Long = 0
        private set

    init {
        val (opened, effects) = AgentWidgetMachine.open(message.data, status(message))
        machine = opened
        pendingEffects = effects
    }

    fun start() {
        if (started) return
        started = true
        val effects = pendingEffects
        pendingEffects = emptyList()
        run(effects)
    }

    /** The message as the server now reports it. */
    fun update(message: OpenCodeWidgetMessage) {
        val status = status(message)
        if (status != Status.Open) feed(Event.StatusChanged(status))
    }

    /** Text typed into an input and not sent yet: it goes before the next event, or when it rests. */
    private val drafts = LinkedHashMap<String, String>()

    fun draft(id: String, text: String) {
        drafts[id] = text
    }

    fun hasDraft(id: String): Boolean = id in drafts

    /** Sends input [id]'s typing now (it rested, lost focus, or Done). */
    fun commit(id: String) {
        val text = drafts.remove(id) ?: return
        dispatch(AgentWidgetEvent.change(id, text))
    }

    /** What the user did, after any typing not yet sent, so its step sees what was typed. */
    fun send(event: AgentWidgetEvent) {
        if (drafts.isNotEmpty()) {
            for (id in drafts.keys.toList()) if (id != event.id) commit(id)
            drafts.remove(event.id)
        }
        dispatch(event)
    }

    /** An event while the previous one still steps waits for it. */
    private fun dispatch(event: AgentWidgetEvent) {
        when (phase) {
            is Phase.Ready -> feed(Event.Input(event))
            is Phase.Stepping -> {
                val last = queued.lastOrNull()
                if (last != null && last.id == event.id && last.type == event.type && event.type != AgentWidgetEvent.Type.Press) {
                    queued[queued.lastIndex] = event
                } else {
                    queued.addLast(event)
                }
            }
            else -> Unit
        }
    }

    fun retry() = feed(Event.Retry)

    val isLive: Boolean get() = phase is Phase.Ready || phase is Phase.Stepping

    /**
     * One draw step for a canvas on this widget's lane, its output handed to [read] there too.
     * Returns the step's time in µs, or null when there's no program or state yet. [animating]
     * steps on the widget's own copy of the program.
     */
    suspend fun draw(frame: String, animating: Boolean, read: (String) -> Unit): Long? {
        val shared = program ?: return null
        val state = phase.state ?: return null
        val input = AgentWidgetStep.draw(data, state, frame)
        return lane.run {
            val program = if (animating) drawProgram ?: programText?.let { text ->
                engine.compile(text).also { drawProgram = it }
            } ?: shared else shared
            val started = System.nanoTime()
            val text = program.step(input)
            val micros = (System.nanoTime() - started) / 1_000
            read(text)
            micros
        }
    }

    internal fun close() {
        val own = drawProgram
        drawProgram = null
        if (own != null) lane.dispatcher.dispatch(kotlin.coroutines.EmptyCoroutineContext) { own.close() }
    }

    private fun feed(event: Event) {
        val (next, effects) = AgentWidgetMachine.reduce(machine, event)
        machine = next
        phase = next.phase
        if (!started) {
            pendingEffects = pendingEffects + effects
            return
        }
        run(effects)
        if (next.phase is Phase.Ready && queued.isNotEmpty()) feed(Event.Input(queued.removeFirst()))
        if (next.phase !is Phase.Ready && next.phase !is Phase.Stepping) queued.clear()
    }

    private fun run(effects: List<Effect>) {
        for (effect in effects) {
            when (effect) {
                Effect.Fetch -> scope.launch {
                    val event = try {
                        val compiled = programs.program(server, ref)
                        programText = compiled.text
                        program = compiled.program
                        Event.Loaded
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        AppLog.app.notice { "Widget ${ref.key} didn't load: ${error.javaClass.simpleName}" }
                        Event.LoadFailed(error.message ?: error.javaClass.simpleName)
                    }
                    feed(event)
                }
                is Effect.Run -> scope.launch {
                    val program = program
                    val result = if (program == null) {
                        Result.failure(IllegalStateException("The widget's program isn't loaded."))
                    } else {
                        lane.run {
                            runCatching {
                                val started = System.nanoTime()
                                val text = program.step(effect.input)
                                val stepped = System.nanoTime()
                                val output = AgentWidgetStep.output(text)
                                lastStepMicros = (stepped - started) / 1_000
                                AppLog.perf.debug {
                                    "Widget ${ref.name} step ${lastStepMicros} µs, decode ${(System.nanoTime() - stepped) / 1_000} µs, ${text.length} chars"
                                }
                                output
                            }
                        }
                    }
                    feed(Event.Ran(result))
                }
                is Effect.Send -> scope.launch {
                    try {
                        client.message(server, sessionID, messageID, effect.message)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: OpenCodeClientError) {
                        feed(Event.SendFailed(if (error.isWidgetAlreadyAnswered) localized(R.string.transcript_widget_not_open) else error.message))
                    }
                }
                is Effect.Respond -> scope.launch {
                    val failure = try {
                        client.respond(server, sessionID, messageID, effect.response)
                        null
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: OpenCodeClientError) {
                        if (error.isWidgetAlreadyAnswered) RespondFailure.Conflict else RespondFailure.Failed(error.message)
                    }
                    feed(Event.Responded(effect.attempt, failure))
                }
            }
        }
    }

    private companion object {
        fun status(message: OpenCodeWidgetMessage): Status = when (message.status) {
            OpenCodeWidgetMessage.Status.Open -> Status.Open
            OpenCodeWidgetMessage.Status.Submitted -> Status.Answered(AgentWidgetMachine.Closed.Submitted(message.response ?: "null"))
            OpenCodeWidgetMessage.Status.Dismissed -> Status.Answered(AgentWidgetMachine.Closed.Dismissed)
        }
    }
}
