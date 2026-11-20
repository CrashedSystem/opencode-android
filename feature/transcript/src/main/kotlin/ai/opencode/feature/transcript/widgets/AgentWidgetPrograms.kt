package ai.opencode.feature.transcript.widgets

import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeWidgetRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

/**
 * Compiled template programs by `name@version`, fetched (or read from disk) and compiled once and
 * shared by every widget using that version. The few most recently used stay compiled; one in
 * use by a widget stays alive through its controller.
 */
class AgentWidgetPrograms(
    private val engine: () -> AgentWidgetEngine,
    private val transport: () -> AgentWidgetTransport,
    private val limit: Int = 12,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val entries = LinkedHashMap<String, Deferred<Compiled>>(16, 0.75f, true)

    /** A template version's program text and its shared compiled program. */
    class Compiled(val text: String, val program: AgentWidgetProgram)

    /** Throws the fetch's or the compile's error; a failed version is fetched again next time. */
    suspend fun program(server: OpenCodeServer, ref: OpenCodeWidgetRef): Compiled {
        val engine = engine()
        val transport = transport()
        val deferred = synchronized(entries) {
            entries.getOrPut(ref.key) {
                scope.async {
                    val text = transport.program(server, ref)
                    val started = System.nanoTime()
                    val compiled = engine.compile(text)
                    AppLog.perf.info { "Widget ${ref.key} compiled in ${(System.nanoTime() - started) / 1_000} µs (${text.length} chars)" }
                    Compiled(text, compiled)
                }
            }.also {
                // An evicted program a widget still holds keeps stepping; the collector frees it.
                while (entries.size > limit) entries.remove(entries.keys.first())
            }
        }
        return try {
            deferred.await()
        } catch (error: Throwable) {
            synchronized(entries) { if (entries[ref.key] === deferred) entries.remove(ref.key) }
            throw error
        }
    }

    companion object Key : DependencyKey<AgentWidgetPrograms> {
        override val liveValue: AgentWidgetPrograms by lazy {
            AgentWidgetPrograms(engine = { dependency(AgentWidgetEngine) }, transport = { dependency(AgentWidgetTransport) })
        }
        override val testValue: AgentWidgetPrograms
            get() = AgentWidgetPrograms(engine = { dependency(AgentWidgetEngine) }, transport = { dependency(AgentWidgetTransport) })
    }
}

/** Runs [block] on the widget's own lane, so its steps never overlap or block the main thread. */
internal suspend inline fun <T> AgentWidgetLane.run(crossinline block: () -> T): T = withContext(dispatcher) { block() }

@JvmInline
internal value class AgentWidgetLane(val dispatcher: kotlinx.coroutines.CoroutineDispatcher) {
    companion object {
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        fun make() = AgentWidgetLane(Dispatchers.Default.limitedParallelism(1))
    }
}
