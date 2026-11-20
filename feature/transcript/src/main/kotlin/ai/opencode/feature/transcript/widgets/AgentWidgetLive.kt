package ai.opencode.feature.transcript.widgets

import ai.opencode.core.log.AppLog
import ai.opencode.server.model.OpenCodeWidgetMessageValue
import ai.opencode.server.model.OpenCodeWidgetResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One open live widget's program (specs/v2/widgets-live.md): started when the widget first shows,
 * paused while it's out of view or the app is in the background (a paused run costs no CPU), and
 * after 30 s paused snapshotted to free its VM. What the program posts is read on its thread and
 * handed to [events] on the main thread through [scope].
 */
internal class AgentWidgetLiveDriver(
    private val program: AgentWidgetLiveProgram,
    private val data: String,
    private val httpsHosts: List<String>?,
    private val scope: CoroutineScope,
    private val events: Events,
) {
    interface Events {
        fun rendered(view: AgentWidgetNode, fullscreen: Boolean?)
        fun drew(id: String, commandsJson: String)
        fun wantsFrame()
        fun answered(response: OpenCodeWidgetResponse)
        fun sent(message: OpenCodeWidgetMessageValue)
        fun failed(error: AgentWidgetEngineException)
        /** A post that couldn't be used (an invalid view), or a fetch that failed: reported, the run goes on. */
        fun reported(phase: String, message: String)
        /** `main` returned without an answer: the last view stays. */
        fun ended()
    }

    private var run: AgentWidgetLiveRun? = null
    private var visible = false
    private var paused = false
    private var pausedSince = 0L
    private var snapshotJob: Job? = null
    /** The run was dropped while paused (a snapshot that couldn't be taken): start over when shown. */
    private var restartWhenShown = false
    private var finished = false
    private var generation = 0

    val isRunning: Boolean get() = run != null && !finished

    fun setVisible(shown: Boolean) {
        if (shown == visible) return
        visible = shown
        if (shown) show() else hide()
    }

    /** A message for the program (an event or a frame); held by the runner while paused. */
    fun deliver(messageJson: String) {
        if (!finished) run?.deliver(messageJson)
    }

    /** Starts over from `main` (Restart after an error). */
    fun restart() {
        stopRun()
        finished = false
        restartWhenShown = false
        if (visible) start()
    }

    fun stop() {
        finished = true
        stopRun()
    }

    private fun show() {
        snapshotJob?.cancel()
        snapshotJob = null
        when {
            finished -> Unit
            run == null || restartWhenShown -> {
                restartWhenShown = false
                start()
            }
            paused -> {
                paused = false
                AppLog.perf.debug { "Live widget resumed after ${(System.nanoTime() - pausedSince) / 1_000_000} ms" }
                run?.resume()
            }
        }
    }

    private fun hide() {
        val run = run ?: return
        if (finished || paused) return
        paused = true
        pausedSince = System.nanoTime()
        run.pause()
        snapshotJob = scope.launch {
            delay(SNAPSHOT_AFTER_MILLIS)
            if (!paused || this@AgentWidgetLiveDriver.run !== run) return@launch
            if (!run.snapshot()) {
                // A fetch was in flight: stop now and start over from `main` when shown again.
                stopRun()
                restartWhenShown = true
            }
        }
    }

    private fun start() {
        val current = ++generation
        paused = false
        val listener = object : AgentWidgetLiveListener {
            override fun posted(messageJson: String) {
                val post = read(messageJson) ?: return
                scope.launch { if (current == generation) post() }
            }

            override fun warned(message: String) {
                AppLog.app.notice { "Live widget: ${message.take(160)}" }
            }

            override fun fetchFailed(message: String) {
                scope.launch { if (current == generation) events.reported("fetch", message) }
            }

            override fun exited(failure: AgentWidgetEngineException?) {
                scope.launch {
                    if (current != generation) return@launch
                    run = null
                    if (finished) return@launch
                    finished = true
                    AppLog.perf.info { "Live widget exited: ${failure?.message ?: "finished"}" }
                    if (failure != null) events.failed(failure) else events.ended()
                }
            }
        }
        run = try {
            program.start("{\"data\":$data}", listener, httpsHosts)
        } catch (error: AgentWidgetEngineException) {
            finished = true
            events.failed(error)
            null
        }
    }

    private fun stopRun() {
        generation++
        snapshotJob?.cancel()
        snapshotJob = null
        run?.stop()
        run = null
        paused = false
    }

    /** One post, read on the run's thread: what to do with it on the main thread, or null to drop it. */
    private fun read(messageJson: String): (() -> Unit)? {
        val o = runCatching { json.parseToJsonElement(messageJson) as? JsonObject }.getOrNull() ?: return null
        return when ((o["type"] as? JsonPrimitive)?.content) {
            "render" -> {
                val node = o["node"] ?: return null
                val view = try {
                    AgentWidgetNode.parse(node)
                } catch (error: AgentWidgetNode.InvalidView) {
                    AppLog.app.notice { "Live widget render dropped: ${error.message}" }
                    val message = error.message ?: "invalid view"
                    return { events.reported("view", message) }
                }
                val fullscreen = ((node as? JsonObject)?.get("fullscreen") as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()
                ({ events.rendered(view, fullscreen) })
            }
            "draw" -> {
                val id = (o["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
                val commands = o["commands"]?.toString() ?: return null
                ({ events.drew(id, commands) })
            }
            "frame" -> ({ events.wantsFrame() })
            "submit" -> {
                val value = (o["value"] ?: JsonNull).toString()
                ({ events.answered(OpenCodeWidgetResponse.Submit(value)) })
            }
            "dismiss" -> ({ events.answered(OpenCodeWidgetResponse.Dismiss) })
            "send" -> {
                val value = (o["value"] ?: JsonNull).toString()
                val wake = (o["wake"] as? JsonPrimitive)?.content != "false"
                ({ events.sent(OpenCodeWidgetMessageValue(value, wake)) })
            }
            else -> null
        }
    }

    private companion object {
        const val SNAPSHOT_AFTER_MILLIS = 30_000L
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** A live canvas's size (logical px) and pointer, which the frames the program asks for carry. */
internal class AgentWidgetLiveCanvas {
    @Volatile var width = 0f
    @Volatile var height = 0f
    @Volatile var pointer: FloatArray? = null
}
