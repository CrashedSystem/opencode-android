package ai.opencode.mobilecode.tca

import android.os.Looper
import android.util.Log
import androidx.compose.runtime.Stable
import kotlin.reflect.KMutableProperty1
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * `Store`. The root lives as long as the process, as `OpenCodeApp.store` does on iOS, so a
 * configuration change never rebuilds it or cancels its effects. Views get children from [scope].
 * Actions are reduced on the main thread one at a time, in the order they arrive.
 */
@Stable
class Store<S, A> internal constructor(
    internal val runtime: StoreRuntime,
    private val read: () -> S,
    private val embed: (A) -> Any?,
    private val path: List<Any>,
) {
    val state: S
        get() = read()

    fun send(action: A): StoreTask = runtime.send(embed(action))

    /** `store.scope(state: \.child, action: \.child)` for a child that is always present. */
    fun <CS, CA> scope(state: KMutableProperty1<S, CS>, action: Case<A, CA>): Store<CS, CA> {
        val parent = this
        return Store(runtime, { state.get(parent.state) }, { parent.embed(action.embed(it)) }, path + listOf(state, action))
    }

    /** `store.scope(state: \.child, action: \.child)` for an optional child. Null while the child is nil. */
    @JvmName("scopeOptional")
    fun <CS : Any, CA> scope(state: KMutableProperty1<S, CS?>, action: Case<A, CA>): Store<CS, CA>? {
        val child = state.get(this.state) ?: return null
        val parent = this
        return Store(runtime, { child }, { parent.embed(action.embed(it)) }, path + listOf(state, action, Instance(child)))
    }

    override fun equals(other: Any?) = other is Store<*, *> && other.runtime === runtime && other.path == path

    override fun hashCode() = 31 * System.identityHashCode(runtime) + path.hashCode()

    private class Instance(val value: Any) {
        override fun equals(other: Any?) = other is Instance && other.value === value

        override fun hashCode() = System.identityHashCode(value)
    }
}

/** `Store(initialState: AppFeature.State()) { AppFeature() }` */
@Suppress("FunctionName")
fun <S, A> Store(
    initialState: S,
    withDependencies: (DependencyValues) -> Unit = {},
    reducer: () -> Reducer<S, A>,
): Store<S, A> {
    val mainLooper = Looper.getMainLooper()
    val runtime =
        StoreRuntime.create(
            initialState = initialState,
            reducer = reducer(),
            dependencies = DependencyValues.current.child().also(withDependencies),
            dispatcher = Dispatchers.Main,
            isStoreThread = { mainLooper.isCurrentThread },
            onEffectError = { Log.e("Store", "An effect failed", it) },
        )
    return Store(runtime, { initialState }, { it }, emptyList())
}

/**
 * The work started by one `send`, including the work of every action those effects send.
 * `let task = await store.send(.task)` then `await task.cancel()` or `task.finish()`.
 */
class StoreTask internal constructor() {
    private val jobs = mutableSetOf<Job>()
    private var cancelled = false

    val isCancelled: Boolean
        get() = synchronized(this) { cancelled }

    /** True until every job has completed, including one still cleaning up after cancellation. */
    internal val isActive: Boolean
        get() = synchronized(this) { jobs.isNotEmpty() }

    internal fun track(job: Job) {
        val cancel = synchronized(this) { cancelled.also { if (!it) jobs.add(job) } }
        if (cancel) {
            job.cancel()
            return
        }
        job.invokeOnCompletion { synchronized(this) { jobs.remove(job) } }
    }

    fun cancel() {
        val all = synchronized(this) { cancelled = true; jobs.toList() }
        all.forEach(Job::cancel)
    }

    suspend fun finish() {
        while (true) {
            val pending = synchronized(this) { jobs.toList() }
            if (pending.isEmpty()) return
            pending.joinAll()
        }
    }
}

internal interface ReductionObserver {
    fun willReduce(action: Any?, fromEffect: Boolean)

    fun didReduce(action: Any?, fromEffect: Boolean)
}

/**
 * The engine behind a root store: the action queue, the reducer, and the effect jobs.
 *
 * Every running effect is indexed by its navigation path, the identities of the `ifLet` children it
 * was started under, so a child that goes away takes all of its effects with it. A cancellable effect
 * is also indexed under its ID at every prefix of that path, so an ancestor can cancel a descendant's
 * ID. An effect that an action sent by another effect produces is a sibling, not a child: cancelling
 * the producer never cancels it.
 */
internal class StoreRuntime private constructor(
    private val root: Any?,
    private val reducer: Reducer<Any?, Any?>,
    val dependencies: DependencyValues,
    val dispatcher: CoroutineDispatcher,
    val isStoreThread: () -> Boolean,
    onEffectError: (Throwable) -> Unit,
) {
    private class Pending(val action: Any?, val fromEffect: Boolean, val origin: StoreTask)

    private data class CancelKey(val path: List<Segment>, val id: Any)

    var observer: ReductionObserver? = null
    val viewTasks = HashMap<String, Any?>()

    private val rootJob = SupervisorJob()
    private val scope = CoroutineScope(rootJob + dispatcher + CoroutineExceptionHandler { _, error -> onEffectError(error) })
    private val queue = ArrayDeque<Pending>()
    private var draining = false
    private val running = LinkedHashMap<Job, List<Segment>>()
    private val cancellables = HashMap<CancelKey, MutableSet<Job>>()

    init {
        attachGraph(root)
    }

    /** Effects that have not completed, counting ones still cleaning up after cancellation. */
    val inFlightCount: Int
        get() = synchronized(running) { running.size }

    fun send(action: Any?): StoreTask {
        check(isStoreThread()) { "Store.send must be called on the main thread. An effect sends through its Send." }
        val origin = StoreTask()
        queue.addLast(Pending(action, fromEffect = false, origin = origin))
        drain()
        return origin
    }

    fun receive(action: Any?, origin: StoreTask) {
        queue.addLast(Pending(action, fromEffect = true, origin = origin))
        drain()
    }

    fun cancelAll() {
        val jobs = synchronized(running) { running.keys.toList() }
        jobs.forEach(Job::cancel)
    }

    fun close() {
        rootJob.cancel()
    }

    private fun drain() {
        if (draining) return
        draining = true
        try {
            var steps = 0
            while (queue.isNotEmpty()) {
                check(++steps <= 10_000) { "Reducing sent more than 10000 actions in a row" }
                val next = queue.removeFirst()
                observer?.willReduce(next.action, next.fromEffect)
                val effect = StoreContext.run(Phase.Reduce, dependencies) { reducer.reduce(root, next.action) }
                observer?.didReduce(next.action, next.fromEffect)
                start(effect, emptyList(), rootJob, next.origin)
            }
        } catch (error: Throwable) {
            queue.clear()
            throw error
        } finally {
            draining = false
        }
    }

    private fun start(effect: Effect<Any?>, path: List<Segment>, parent: Job, origin: StoreTask) {
        when (effect) {
            None -> Unit
            is SendAction -> queue.addLast(Pending(effect.action, fromEffect = true, origin = origin))
            is Run -> launch(effect, path, parent, origin)
            is Merge -> effect.effects.forEach { start(it, path, parent, origin) }
            is Cancellable -> {
                if (effect.cancelInFlight) cancel(effect.id, path)
                val group = SupervisorJob(parent)
                index(group, effect.id, path)
                start(effect.effect, path, group, origin)
                group.complete()
            }
            is Cancel -> cancel(effect.id, path)
            is InChild -> start(effect.effect, path + effect.segment, parent, origin)
            is CancelChild -> cancelSubtree(path + effect.segment)
        }
    }

    private fun launch(effect: Run<Any?>, path: List<Segment>, parent: Job, origin: StoreTask) {
        val job =
            scope.launch(parent + EffectContext(dependencies)) {
                val send = EffectSend(this@StoreRuntime, currentCoroutineContext().job, origin)
                try {
                    effect.operation(EffectScope.shared, send)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    val handler = effect.catch ?: throw error
                    handler(EffectScope.shared, error, send)
                }
            }
        synchronized(running) { running[job] = path }
        job.invokeOnCompletion { synchronized(running) { running.remove(job) } }
        origin.track(job)
    }

    private fun index(group: Job, id: Any, path: List<Segment>) {
        val keys = (0..path.size).map { CancelKey(path.subList(0, it).toList(), id) }
        synchronized(cancellables) { keys.forEach { cancellables.getOrPut(it, ::mutableSetOf).add(group) } }
        group.invokeOnCompletion {
            synchronized(cancellables) {
                keys.forEach { key ->
                    val jobs = cancellables[key] ?: return@forEach
                    jobs.remove(group)
                    if (jobs.isEmpty()) cancellables.remove(key)
                }
            }
        }
    }

    private fun cancel(id: Any, path: List<Segment>) {
        val jobs = synchronized(cancellables) { cancellables[CancelKey(path, id)]?.toList() } ?: return
        jobs.forEach(Job::cancel)
    }

    private fun cancelSubtree(prefix: List<Segment>) {
        val jobs =
            synchronized(running) {
                running.filterValues { it.size >= prefix.size && it.subList(0, prefix.size) == prefix }.keys.toList()
            }
        jobs.forEach(Job::cancel)
    }

    private class EffectSend(private val runtime: StoreRuntime, private val job: Job, private val origin: StoreTask) : Send<Any?> {
        override suspend fun invoke(action: Any?) {
            if (!job.isActive) return
            if (runtime.isStoreThread()) {
                runtime.receive(action, origin)
                return
            }
            withContext(runtime.dispatcher) {
                if (job.isActive) runtime.receive(action, origin)
            }
        }
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun <S, A> create(
            initialState: S,
            reducer: Reducer<S, A>,
            dependencies: DependencyValues,
            dispatcher: CoroutineDispatcher,
            isStoreThread: () -> Boolean,
            onEffectError: (Throwable) -> Unit,
        ) = StoreRuntime(initialState, reducer as Reducer<Any?, Any?>, dependencies, dispatcher, isStoreThread, onEffectError)
    }
}
